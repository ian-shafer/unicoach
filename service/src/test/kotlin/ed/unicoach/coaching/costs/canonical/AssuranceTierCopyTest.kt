package ed.unicoach.coaching.costs.canonical

import ed.unicoach.db.models.AssuranceTier
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.MoneySource
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The three assurance sentences (RFC 179): what KIND of number a figure is,
 * said in English exactly once.
 *
 * The anti-vacuity criterion the slice was accepted on has two halves: the three
 * arms must produce three DIFFERENT sentences, and the three figures a family
 * actually sees must land on three DIFFERENT tiers. A map that answered one tier
 * for everything would pass the first half alone; a copy object that answered
 * one sentence for everything would pass the second alone.
 *
 * Both halves are asserted here AS PURE FUNCTIONS. The stored-row half -- that
 * a real seeded row reaches each of those tiers through the read paths -- is
 * asserted in the DB-backed cases named on the second case below.
 */
class AssuranceTierCopyTest {
  @Test
  fun `every tier is spoken, in words of its own`() {
    val sentences = AssuranceTier.entries.associateWith { AssuranceTierCopy.statementOf(it) }
    sentences.forEach { (tier, sentence) ->
      assertTrue(sentence.isNotBlank(), "[${tier.value}] must be spoken: [$sentence]")
      assertFalse(sentence.contains(tier.value), "[${tier.value}] is spoken in WORDS, never as its own slug")
    }
    assertEquals(
      AssuranceTier.entries.size,
      sentences.values.toSet().size,
      "two tiers said the same way are indistinguishable to a family: [${sentences.values}]",
    )
  }

  @Test
  fun `each of the three figures a family sees lands on its own tier`() {
    // One per tier, as the RESOLVER answers it: the median federal debt the
    // report page prints, the published in-state tuition the coach answers "what
    // does it cost to go there?" with, and the average percent of need met the
    // aid-policy section carries. Three publishers, three `(source, variable)`
    // pairs -- and nothing here is seeded or read back.
    //
    // The SEEDED-ROW half of the same criterion is carried where the rows are,
    // and this case is vacuous without it:
    // `CollegeCostServiceTest`'s "a shown figure carries a tier and no status
    // prose, and the note list cannot flood" reads MANDATORY_SURVEY off the
    // stored IPEDS tuition row and ADMINISTRATIVE_RECORD off the Scorecard's
    // `GRAD_DEBT_MDN`, and `CollegeCostChatToolTest`'s "the two Common Data Set
    // averages carry the softest tier, resolved from the row's own field id"
    // reads VOLUNTARY_SELF_REPORT off `H.209`/`H.211` through the real
    // `AidPolicyDao`.
    assertEquals(
      AssuranceTier.ADMINISTRATIVE_RECORD,
      AssuranceTier.of(MoneySource.SCORECARD, "GRAD_DEBT_MDN", ROW),
    )
    assertEquals(
      AssuranceTier.MANDATORY_SURVEY,
      AssuranceTier.of(MoneySource.IPEDS_IC_AY, "CHG2AY3", ROW),
    )
    assertEquals(
      AssuranceTier.VOLUNTARY_SELF_REPORT,
      AssuranceTier.of(MoneySource.COMMON_DATA_SET, "H.209", ROW),
    )
  }

  @Test
  fun `a tier sentence never names a publisher, and never carries a number`() {
    // D9: composing the publisher's own name into the mandatory-survey sentence
    // would print a FALSE claim for the 22 Scorecard strings it relays -- the
    // college filed those with IPEDS, not with the Scorecard. The publisher is
    // named exactly once, by the seam that owns publisher names, in the sentence
    // this one sits beside.
    val publisherWords = MoneySource.entries.map { MoneySourceCopy.labelOf(it) } + listOf("Scorecard", "IPEDS")
    AssuranceTier.entries.forEach { tier ->
      val sentence = AssuranceTierCopy.statementOf(tier)
      publisherWords.forEach { word ->
        assertFalse(sentence.contains(word, ignoreCase = true), "[${tier.value}] must not name [$word]: [$sentence]")
      }
      // No number, no score, no rating -- ever (brief 0008 D7).
      assertFalse(sentence.any { it.isDigit() }, "a tier is never a score: [$sentence]")
    }
  }

  @Test
  fun `the tier is composed BESIDE a status sentence and never inside one`() {
    // The status seam's own words are byte-frozen by the immutable v19 prompt
    // row, so a tier appended into `statementOf`'s return value would move a
    // sentence no new seed can fix. Asserted as containment in neither
    // direction: two sentences, said in order, and never one.
    val status = FigureStatusCopy.statementOf(FigureStatus.IMPUTED_BY_PUBLISHER, MoneySource.IPEDS_SFA)
    val tier = AssuranceTierCopy.statementOf(AssuranceTier.MANDATORY_SURVEY)
    assertFalse(status.orEmpty().contains(tier))
    assertFalse(tier.contains(status.orEmpty()))
  }

  private companion object {
    /** The locator [AssuranceTier.of] requires; this file resolves pairs it does not read back. */
    const val ROW = "the assurance-tier copy test (a named pair)"
  }
}
