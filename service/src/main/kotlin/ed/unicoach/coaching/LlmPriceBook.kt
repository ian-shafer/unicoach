package ed.unicoach.coaching

import com.typesafe.config.Config
import com.typesafe.config.ConfigObject
import ed.unicoach.chat.TokenUsage
import ed.unicoach.common.money.Nanodollars
import ed.unicoach.db.models.FrozenCost
import java.math.BigDecimal

/**
 * The per-token price of one model, one integer rate per token class, in
 * nano-dollars (1e-9 USD) PER TOKEN (RFC 108). The `$per_MTok` rates in config
 * are converted to this form exactly once, at load: `$per_MTok / 1e6` USD per
 * token is `× 1000` nano-dollars per token, so a call's cost is a pure integer
 * dot-product with no division and no rounding.
 */
class ModelPrice(
  val inputNanoPerToken: Nanodollars,
  val outputNanoPerToken: Nanodollars,
  val cacheReadNanoPerToken: Nanodollars,
  val cacheWriteNanoPerToken: Nanodollars,
)

/**
 * Thrown by [LlmPriceBook.requireExplicitlyPriced] when one or more requested
 * model ids have no entry of their own in the book. Carries [missingModels] as
 * structured data (not just the rendered message) so a caller — e.g. a test, or
 * future boot-error reporting — can consume the exact list without re-parsing it
 * out of the message string.
 */
class UnpricedModelsException(
  val missingModels: List<String>,
) : IllegalStateException(
    "LLM models are configured but not explicitly priced in llmPricing.models: [${missingModels.joinToString(", ")}]",
  )

/**
 * The typed, pure `model → price` table (RFC 108), read from the `llmPricing`
 * block of service.conf. Keyed on the RESOLVED model id
 * (`llm_responses.model_resolved` — the model the provider reports actually ran,
 * and therefore the model actually billed).
 *
 * A configured book (every book a composition root wires, from [from]) carries a
 * [default] rate, so an unknown model is not a null case — it prices at the
 * default with `estimated = true`. Only [EMPTY], the cost-agnostic construction,
 * has a null [default]; that null is the price-nothing discriminator, never
 * `prices.isEmpty()`.
 */
class LlmPriceBook private constructor(
  private val prices: Map<String, ModelPrice>,
  private val default: ModelPrice?,
) {
  /**
   * The cost of [usage] under [model]'s price — under the [default] rate (flagged
   * `estimated = true`) when [model] is absent from the book. Returns `null` in
   * exactly three cases: either base count ([TokenUsage.inputTokens] /
   * [TokenUsage.outputTokens]) is null OR negative (a billed call whose usage is
   * unreported or corrupt — an `uncostedCalls` gap, not a measured zero, and never
   * something that reaches [Nanodollars]'s own non-negative guard), or the book
   * has no entry for [model] and no [default] to fall back on (reachable only
   * through [EMPTY]). A null cache count reads as an exact `0` (no caching
   * occurred); a NEGATIVE cache count — a lesser-severity provider glitch than a
   * corrupt base count — also coerces to that same `0` rather than voiding the
   * whole computation.
   */
  fun costOf(
    model: String,
    usage: TokenUsage,
  ): FrozenCost? {
    val inputTokens = usage.inputTokens?.takeIf { it >= 0 } ?: return null
    val outputTokens = usage.outputTokens?.takeIf { it >= 0 } ?: return null
    val cacheReadTokens = (usage.cacheReadTokens ?: 0).takeIf { it >= 0 } ?: 0
    val cacheWriteTokens = (usage.cacheWriteTokens ?: 0).takeIf { it >= 0 } ?: 0
    val entry = prices[model]
    val price = entry ?: default ?: return null
    val estimated = entry == null
    val nanodollars =
      inputTokens.toLong() * price.inputNanoPerToken.value +
        outputTokens.toLong() * price.outputNanoPerToken.value +
        cacheReadTokens.toLong() * price.cacheReadNanoPerToken.value +
        cacheWriteTokens.toLong() * price.cacheWriteNanoPerToken.value
    return FrozenCost(nanodollars = Nanodollars.of(nanodollars), estimated = estimated)
  }

  /**
   * Boot guard: fails when any id in [models] has no entry of its OWN in the book
   * (the [default] does not count), naming the missing ids. Each composition root
   * calls this with the caller models it wires so an env override to an unpriced
   * id fails startup rather than silently metering at the default rate. It is
   * explicitly not a completeness guarantee for the lookup — it validates the ids
   * we REQUEST, while [costOf] keys on the ids we are SERVED, a set nothing can
   * guarantee in advance.
   */
  fun requireExplicitlyPriced(models: Collection<String>): Result<Unit> {
    val missing = models.filter { it !in prices }
    return if (missing.isEmpty()) {
      Result.success(Unit)
    } else {
      Result.failure(UnpricedModelsException(missing))
    }
  }

  companion object {
    private val THOUSAND = BigDecimal(1000)

    /** No prices, no default; every [costOf] returns null. The cost-agnostic constructor default. */
    val EMPTY: LlmPriceBook = LlmPriceBook(emptyMap(), null)

    /**
     * Reads `llmPricing.models` (per-model `$per_MTok` rates) plus the required
     * `llmPricing.default` block, converting each rate to an integer
     * nano-dollars-per-token (`× 1000`) via [BigDecimal] once at load. Returns
     * [Result.failure] on a rate finer than a tenth of a cent per MTok (no exact
     * integer form — never silently rounded), a NEGATIVE rate (which would
     * otherwise reach the DB and fail the terminal insert), or a missing
     * `default` block.
     */
    fun from(config: Config): Result<LlmPriceBook> =
      runCatching {
        val pricing = config.getConfig("llmPricing")
        val modelsObject = pricing.getConfig("models").root()
        val prices =
          modelsObject.entries.associate { (modelId, value) ->
            modelId to modelPrice((value as ConfigObject).toConfig())
          }
        val default = modelPrice(pricing.getConfig("default"))
        LlmPriceBook(prices, default)
      }

    private fun modelPrice(rates: Config): ModelPrice =
      ModelPrice(
        inputNanoPerToken = nanoPerToken(rates, "input"),
        outputNanoPerToken = nanoPerToken(rates, "output"),
        cacheReadNanoPerToken = nanoPerToken(rates, "cacheRead"),
        cacheWriteNanoPerToken = nanoPerToken(rates, "cacheWrite"),
      )

    /**
     * Converts one `$per_MTok` rate at [key] to integer nano-dollars per token
     * (`× 1000`). The rate's exact decimal is taken from its original text (not a
     * lossy binary double), so `toBigIntegerExact` throws — surfacing as
     * [Result.failure] — when the rate is finer than a tenth of a cent per MTok.
     * A negative rate is rejected explicitly here (before it ever reaches
     * [Nanodollars]'s own non-negative guard) so the failure names the offending
     * rate [key].
     */
    private fun nanoPerToken(
      rates: Config,
      key: String,
    ): Nanodollars {
      val rate = BigDecimal(rates.getValue(key).unwrapped().toString())
      require(rate.signum() >= 0) { "llmPricing rate [$key] must be non-negative, got [$rate]" }
      val nanoPerMTok = rate.multiply(THOUSAND)
      return try {
        Nanodollars.of(nanoPerMTok.toBigIntegerExact().longValueExact())
      } catch (e: ArithmeticException) {
        throw IllegalArgumentException(
          "llmPricing rate [$key] = [$rate] has no exact nano-dollar-per-token form " +
            "(finer than a tenth of a cent per MTok)",
          e,
        )
      }
    }
  }
}
