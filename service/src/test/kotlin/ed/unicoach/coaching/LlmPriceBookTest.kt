package ed.unicoach.coaching

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigResolveOptions
import ed.unicoach.chat.TokenUsage
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins [LlmPriceBook] (RFC 108): the `$per_MTok → nano-per-token` conversion, the
 * two null cases of [LlmPriceBook.costOf], the default-rate fallback + estimated
 * flag, the load-time rate guards, and the boot check.
 *
 * The priced/unknown-model amounts are asserted against the REAL packaged
 * service.conf rates (loaded via [realBook]), so this test also pins those rates
 * to the hand-computed dot-products.
 */
class LlmPriceBookTest {
  private val offlineOptions =
    ConfigResolveOptions
      .defaults()
      .setUseSystemEnvironment(false)

  /** The book built from the packaged service.conf (its rates are the pinned baseline). */
  private fun realBook(): LlmPriceBook =
    LlmPriceBook
      .from(
        ConfigFactory
          .parseString(
            """
            APP_DOMAIN = localhost
            PUBLIC_WEB_PORT = 8082
            GOOGLE_AUTH_PROVIDER = stub
            APPLE_AUTH_PROVIDER = stub
            """.trimIndent(),
          ).withFallback(ConfigFactory.parseResources("service.conf"))
          .resolve(offlineOptions),
      ).getOrThrow()

  private fun bookFrom(hocon: String) = LlmPriceBook.from(ConfigFactory.parseString(hocon))

  // service.conf rates → nano-per-token: input 3.00→3000, output 15.00→15000,
  // cacheRead 0.30→300, cacheWrite 3.75→3750.
  private val fullUsage = TokenUsage(inputTokens = 1000, outputTokens = 100, cacheReadTokens = 1000, cacheWriteTokens = 100)

  @Test
  fun `from parses rates into an exact integer dot-product`() {
    val priced = realBook().costOf("claude-sonnet-4-6", fullUsage)!!
    // 1000*3000 + 100*15000 + 1000*300 + 100*3750 = 5_175_000
    assertEquals(5_175_000L, priced.nanodollars.value)
    assertFalse(priced.estimated)

    // The headline single-class example: 1000 input @ $3/MTok = 3_000_000 nano.
    assertEquals(
      3_000_000L,
      realBook().costOf("claude-sonnet-4-6", TokenUsage(1000, 0, 0, 0))!!.nanodollars.value,
    )
  }

  @Test
  fun `an unknown model prices at the default rate and is flagged estimated`() {
    val priced = realBook().costOf("some-new-model", fullUsage)!!
    // default rates → nano-per-token: input 10→10000, output 50→50000, cacheRead 1→1000, cacheWrite 12.50→12500.
    // 1000*10000 + 100*50000 + 1000*1000 + 100*12500 = 17_250_000
    assertEquals(17_250_000L, priced.nanodollars.value)
    assertTrue(priced.estimated, "a model absent from the book prices at the default rate, flagged")
  }

  @Test
  fun `unreported base usage prices to null, never a false zero`() {
    val book = realBook()
    assertNull(book.costOf("claude-sonnet-4-6", TokenUsage(null, null, null, null)))
    // cache-only counts must not price a call whose base counts are unreported to a false 0.
    assertNull(book.costOf("claude-sonnet-4-6", TokenUsage(null, null, 0, 0)))
    // Same for an unknown (default-priced) model: a null base count still short-circuits to null.
    assertNull(book.costOf("some-new-model", TokenUsage(null, 10, 10, 10)))
  }

  @Test
  fun `null cache classes read as an exact zero`() {
    val book = realBook()
    // input+output only: 1000*3000 + 10*15000 = 3_150_000.
    assertEquals(3_150_000L, book.costOf("claude-sonnet-4-6", TokenUsage(1000, 10, null, null))!!.nanodollars.value)
    assertEquals(0L, book.costOf("claude-sonnet-4-6", TokenUsage(0, 0, 0, 0))!!.nanodollars.value)
  }

  @Test
  fun `a negative base token count prices to null instead of throwing`() {
    val book = realBook()
    // A corrupt/negative input count must degrade to the uncostedCalls gap, never
    // reach Nanodollars' own require(value >= 0) deep in the write path.
    assertNull(book.costOf("claude-sonnet-4-6", TokenUsage(-1, 100, 0, 0)))
    // Same for a negative output count.
    assertNull(book.costOf("claude-sonnet-4-6", TokenUsage(1000, -1, 0, 0)))
    // Both negative.
    assertNull(book.costOf("claude-sonnet-4-6", TokenUsage(-1, -1, 0, 0)))
  }

  @Test
  fun `a negative cache token count coerces to zero rather than voiding the whole cost`() {
    val book = realBook()
    // A bad cache count is lesser-severity than a bad base count: it must not
    // abort the computation, only contribute 0 — same as a null cache count.
    // 1000*3000 + 10*15000 = 3_150_000, cache terms both coerced to 0.
    assertEquals(
      3_150_000L,
      book.costOf("claude-sonnet-4-6", TokenUsage(1000, 10, -1, -1))!!.nanodollars.value,
    )
    // One negative, one positive cache count: only the negative one coerces to 0.
    // 1000*3000 + 10*15000 + 100*3750(cacheWrite) = 3_525_000.
    assertEquals(
      3_525_000L,
      book.costOf("claude-sonnet-4-6", TokenUsage(1000, 10, -1, 100))!!.nanodollars.value,
    )
  }

  @Test
  fun `from rejects a rate finer than a tenth of a cent per MTok`() {
    val result =
      bookFrom(
        """
        llmPricing {
          models { "m" { input = 0.0005, output = 15.00, cacheRead = 0.30, cacheWrite = 3.75 } }
          default { input = 10.00, output = 50.00, cacheRead = 1.00, cacheWrite = 12.50 }
        }
        """.trimIndent(),
      )
    assertTrue(result.isFailure, "0.0005 \$/MTok has no exact nano-per-token integer form")
    val message = result.exceptionOrNull()?.message
    assertTrue(
      message?.contains("input") == true && message.contains("0.0005"),
      "the failure must name the offending rate key and value, got: $message",
    )
  }

  @Test
  fun `from rejects a rate that is not a number, naming the key and the raw text`() {
    // A typo, a currency symbol, an unresolved substitution: the text never
    // reaches Nanodollars, so only the reader's own wrapper can say what broke.
    val result =
      bookFrom(
        """
        llmPricing {
          models { "m" { input = "3..00", output = 15.00, cacheRead = 0.30, cacheWrite = 3.75 } }
          default { input = 10.00, output = 50.00, cacheRead = 1.00, cacheWrite = 12.50 }
        }
        """.trimIndent(),
      )
    assertTrue(result.isFailure, "a rate that is not a decimal must fail the load")
    val message = result.exceptionOrNull()?.message
    assertTrue(
      message?.contains("input") == true && message.contains("3..00"),
      "the failure must name the offending rate key and value, got: $message",
    )
  }

  @Test
  fun `from rejects a negative rate`() {
    val result =
      bookFrom(
        """
        llmPricing {
          models { "m" { input = -3.00, output = 15.00, cacheRead = 0.30, cacheWrite = 3.75 } }
          default { input = 10.00, output = 50.00, cacheRead = 1.00, cacheWrite = 12.50 }
        }
        """.trimIndent(),
      )
    assertTrue(result.isFailure, "a negative rate must not reach the DB as a below-zero cost")
  }

  @Test
  fun `from rejects a config with models but no default`() {
    val result =
      bookFrom(
        """
        llmPricing {
          models { "m" { input = 3.00, output = 15.00, cacheRead = 0.30, cacheWrite = 3.75 } }
        }
        """.trimIndent(),
      )
    assertTrue(result.isFailure, "the default block is required — the lookup must have a total answer")
  }

  @Test
  fun `requireExplicitlyPriced passes only when every named model has its own entry`() {
    val book = realBook()
    assertTrue(book.requireExplicitlyPriced(listOf("claude-sonnet-4-6")).isSuccess)

    // A model the book WOULD price at the default rate still fails: priceability is
    // universal in a configured book, so only the explicit-entry check pins intent.
    val failure = book.requireExplicitlyPriced(listOf("claude-sonnet-4-6", "unpriced-model"))
    assertTrue(failure.isFailure)
    assertEquals(
      listOf("unpriced-model"),
      (failure.exceptionOrNull() as UnpricedModelsException).missingModels,
    )
  }

  @Test
  fun `EMPTY prices nothing, including a model a configured book would default-price`() {
    assertNull(LlmPriceBook.EMPTY.costOf("claude-sonnet-4-6", fullUsage))
    assertNull(LlmPriceBook.EMPTY.costOf("any-unknown-model", fullUsage), "EMPTY carries no default — the second null case")
  }
}
