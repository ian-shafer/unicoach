package ed.unicoach.db.models

import ed.unicoach.db.dao.CorruptPersistedValueException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The assurance-tier resolver (RFC 179): what KIND of number a stored cell is,
 * from the `(source, source_variable)` pair the row carries.
 *
 * Three of the four arms answer for a whole publisher and one is keyed on the
 * variable, so the two things worth pinning are that a whole-source arm answers
 * for EVERY variable it is given -- including one it has never seen -- and that
 * the keyed arm refuses a variable it does not know rather than guessing.
 */
class AssuranceTierTest {
  @Test
  fun `the two IPEDS surveys and the Common Data Set answer for every variable, whatever it is`() {
    // The instrument is a property of the source on these three: an IC_AY cell
    // is a compelled, edit-checked survey answer whichever variable it is, and
    // a CDS cell is one filing a school published about itself whichever field
    // id it carries. A variable no loader writes is answered for the same way,
    // which is the point of these arms not being keyed.
    for (variable in listOf("CHG2AY3", "npist2", "H.209", "a variable nobody publishes")) {
      assertEquals(AssuranceTier.MANDATORY_SURVEY, AssuranceTier.of(MoneySource.IPEDS_IC_AY, variable, ROW))
      assertEquals(AssuranceTier.MANDATORY_SURVEY, AssuranceTier.of(MoneySource.IPEDS_SFA, variable, ROW))
      assertEquals(AssuranceTier.VOLUNTARY_SELF_REPORT, AssuranceTier.of(MoneySource.COMMON_DATA_SET, variable, ROW))
    }
  }

  @Test
  fun `the Scorecard is keyed on the variable, because it relays some publishers and originates others`() {
    // The whole reason the tier cannot hang off MoneySource: one publisher, two
    // answers, and the majority of its columns are somebody else's survey.
    assertEquals(AssuranceTier.MANDATORY_SURVEY, AssuranceTier.of(MoneySource.SCORECARD, "TUITIONFEE_IN", ROW))
    assertEquals(AssuranceTier.MANDATORY_SURVEY, AssuranceTier.of(MoneySource.SCORECARD, "NPT41_PRIV", ROW))
    assertEquals(AssuranceTier.ADMINISTRATIVE_RECORD, AssuranceTier.of(MoneySource.SCORECARD, "GRAD_DEBT_MDN", ROW))
    assertEquals(AssuranceTier.ADMINISTRATIVE_RECORD, AssuranceTier.of(MoneySource.SCORECARD, "MD_EARN_WNE_P10", ROW))
  }

  @Test
  fun `an unmapped Scorecard variable is fatal, and the message names both halves of the pair`() {
    // Asserted as a THROW, never as a fallback value: a test that accepted a
    // default would be the defect. `source_variable` is an open TEXT column, so
    // the compiler cannot close this arm and the run-time refusal is the whole
    // of the guard -- the ORDERED_SOURCES unranked-member precedent.
    // The HOUSE's located corrupt-value failure, not a bare
    // IllegalStateException: this is a read-path reconstruction fault over a
    // stored pair, so it carries the `PermanentError` marker, both halves of the
    // pair as its `value`, and the caller's locator.
    val thrown =
      assertFailsWith<CorruptPersistedValueException> {
        AssuranceTier.of(MoneySource.SCORECARD, "DEBT_MDN", ROW)
      }
    assertEquals("source=[scorecard] source_variable=[DEBT_MDN]", thrown.value, "both halves of the pair, as data")
    assertEquals(ROW, thrown.location, "and where the offending row is")
    val message = thrown.message.orEmpty()
    assertTrue("source=[scorecard]" in message, "the message names the source's own slug: [$message]")
    assertTrue("source_variable=[DEBT_MDN]" in message, "and the variable that has no tier: [$message]")
  }

  @Test
  fun `DEBT_MDN is absent on purpose -- a tier for a variable no loader writes is a row with no reader`() {
    assertTrue("DEBT_MDN" !in AssuranceTier.SCORECARD_TIERS)
    assertTrue("GRAD_DEBT_MDN" in AssuranceTier.SCORECARD_TIERS, "the column this corpus DOES load is mapped")
  }

  @Test
  fun `the map holds the twenty-four strings the loader writes, and no more`() {
    // Twenty-four strings for eighteen cells: the NPT4 family is published
    // twice, `_PUB` and `_PRIV`, and the map is keyed on the literal string a
    // row carries. Both counts are true and they count different things.
    assertEquals(24, AssuranceTier.SCORECARD_TIERS.size)
    assertEquals(
      2,
      AssuranceTier.SCORECARD_TIERS.values.count { it == AssuranceTier.ADMINISTRATIVE_RECORD },
      "exactly two of the Scorecard's columns are federal administrative records",
    )
    assertEquals(
      22,
      AssuranceTier.SCORECARD_TIERS.values.count { it == AssuranceTier.MANDATORY_SURVEY },
      "the other twenty-two strings are the sixteen IPEDS cells it re-publishes",
    )
  }

  @Test
  fun `no tier is stored anywhere, so every slug is distinct and none is a score`() {
    // The slug is what a reader keys on instead of parsing our English, so two
    // members answering one slug would be unreadable -- and a numeric slug is
    // the rating this vocabulary may never become (brief 0008 D7).
    val slugs = AssuranceTier.entries.map { it.value }
    assertEquals(slugs.size, slugs.toSet().size)
    assertTrue(slugs.none { slug -> slug.any { it.isDigit() } }, "a tier is a word, never a number: [$slugs]")
  }

  private companion object {
    /**
     * The locator every call in this file passes: [AssuranceTier.of] requires
     * one, so a corrupt-pair fault always names the row it was read from.
     */
    const val ROW = "the assurance-tier test (a fixture row)"
  }
}
