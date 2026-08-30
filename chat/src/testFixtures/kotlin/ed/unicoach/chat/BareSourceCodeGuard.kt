package ed.unicoach.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * The generalised "no bare source code reaches a tool result" guard (RFC 143),
 * shared by every tool that renders a source's data to the model.
 *
 * RFC 143 wrote this walker twice, once per tool test, and said the THIRD tool
 * was the trigger to unify it. `college_admissions_profile` (RFC 148) is that
 * third tool, so the walker moved here, to `:chat`'s test fixtures — the one
 * module `:service` and `:college` already share.
 *
 * Only the walker is shared. The ALLOWLIST is a parameter, because the two
 * original copies had already diverged on exactly that point: the cost copy
 * derives its list from `CostField.entries`, the search copy names its eight
 * fields. Each tool's test keeps its own allowlist and its own exact-list
 * positive control, which is what makes the guard a statement about that
 * tool rather than a shared approximation.
 */
object BareSourceCodeGuard {
  /**
   * Neither a leading nor a trailing `\b`, deliberately: `_` is a word
   * character, so `\bq[1-5]\b` matches nothing inside
   * `net_price_per_year_income_q5_usd` -- the very key this guard has to catch.
   * The lookahead rejects only a LONGER token (`q12`, `q5a`), so a bucket code
   * stays visible wherever it sits in an identifier, whatever units the column
   * name grew around it.
   */
  val QUINTILE_CODE: Regex = Regex("""q[1-5](?![0-9a-z])""", RegexOption.IGNORE_CASE)

  /** The Scorecard net-price column family, a raw source name no coach ever says. */
  const val NPT4: String = "NPT4"

  /**
   * Every way [payload] carries a bare source code, in the general form RFC 143
   * put in place of RFC 142's string-specific grep: a `qN` bucket token, the
   * `NPT4` column family, or any field carrying a bare number that is not a
   * number by contract for this tool ([numbersByContract]). Read over the WHOLE
   * payload, so a field added later is covered without anyone remembering to
   * extend the test.
   *
   * Returns the reasons rather than asserting them, so a test can also drive it
   * with a doctored payload and prove it still reacts.
   */
  fun listViolations(
    payload: JsonElement,
    numbersByContract: Set<String>,
  ): List<BareSourceCode> =
    buildList {
      val text = payload.toString()
      QUINTILE_CODE.find(text)?.let { add(BareSourceCode.QuintileToken(it.value)) }
      if (text.contains(NPT4)) add(BareSourceCode.Npt4ColumnFamily)
      addAll(
        listNumericFields(payload)
          .filterNot { it in numbersByContract }
          .map { BareSourceCode.BareNumberField(it) },
      )
    }

  /** The one place a violation becomes words -- an assertion message, never something a caller matches on. */
  fun mapMessage(violation: BareSourceCode): String =
    when (violation) {
      is BareSourceCode.QuintileToken -> "quintile code [${violation.token}]"
      is BareSourceCode.Npt4ColumnFamily -> "source column family [$NPT4]"
      is BareSourceCode.BareNumberField -> "bare code in field [${violation.field}]"
    }

  /**
   * Every field name in [element], at any depth, whose value is a bare number.
   *
   * A number with NO field name -- a payload that is itself a primitive, or an
   * array of bare numbers -- is deliberately NOT reported: the finding this
   * guard yields is a field name, and a root-level number has none to give.
   * Every tool result is a JSON OBJECT by the tool contract, so the blind spot
   * cannot arise for a real payload; only a hand-built fixture can reach it.
   */
  fun listNumericFields(
    element: JsonElement,
    key: String? = null,
  ): List<String> =
    when (element) {
      is JsonObject -> element.flatMap { (name, value) -> listNumericFields(value, name) }
      is JsonArray -> element.flatMap { listNumericFields(it, key) }
      is JsonPrimitive -> if (key != null && !element.isString && element.doubleOrNull != null) listOf(key) else emptyList()
    }
}

/**
 * One way a payload carries a bare source code -- the VALUES, not a sentence.
 *
 * The guard is RFC 143's enforcement point and is now shared by three tools
 * (a fourth is due), so a caller asserting on literal English would make the
 * sharpest check in the suite brittle in exactly the place it must stay sharp.
 * The kind and the offending field are typed; [BareSourceCodeGuard.mapMessage] is
 * the single renderer for an assertion message.
 */
sealed interface BareSourceCode {
  /** A `qN` income-bucket token found anywhere in the payload text. */
  data class QuintileToken(
    val token: String,
  ) : BareSourceCode

  /** The Scorecard net-price column family reached the payload. */
  data object Npt4ColumnFamily : BareSourceCode

  /** A field holding a bare number that is not a number by contract for this tool. */
  data class BareNumberField(
    val field: String,
  ) : BareSourceCode
}
