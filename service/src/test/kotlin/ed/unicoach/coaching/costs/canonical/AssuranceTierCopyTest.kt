package ed.unicoach.coaching.costs.canonical

import ed.unicoach.db.models.AssuranceTier
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.MoneySource
import org.junit.jupiter.api.Test
import java.io.File
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
    // LINKED: the longest form of every sentence, so a tier that is blank or
    // slug-shaped cannot hide behind a deleted last sentence.
    val sentences = AssuranceTier.entries.associateWith { AssuranceTierCopy.statementOf(it, FilingLink.LINKED) }
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
      val sentence = AssuranceTierCopy.statementOf(tier, FilingLink.LINKED)
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
    val tier = AssuranceTierCopy.statementOf(AssuranceTier.MANDATORY_SURVEY, FilingLink.LINKED)
    assertFalse(status.orEmpty().contains(tier))
    assertFalse(tier.contains(status.orEmpty()))
  }

  /**
   * The self-report sentence is byte-frozen against the immutable v19 prompt
   * row, written out here as ONE literal.
   *
   * Written out rather than derived from the constants under test, because a
   * test that composes the same two constants the production code composes
   * asserts nothing about their bytes: RFC 200 splits this string in place, and
   * what has to be proved is that the split did not change a character.
   */
  @Test
  fun `the self-report sentence is byte-identical to the frozen literal`() {
    assertEquals(
      "The college published this about itself. No one checks it. We link to the college's own file " +
        "so you can see it.",
      AssuranceTierCopy.statementOf(AssuranceTier.VOLUNTARY_SELF_REPORT, FilingLink.LINKED),
    )
  }

  /**
   * The unlinkable form is a STRICT PREFIX of the frozen sentence -- which is
   * the whole assertion, not a convenience.
   *
   * A DELETION of the final sentence leaves a prefix; a REWORD does not. So
   * this case cannot be satisfied by softening the promise into "we usually
   * link", by trimming it to a clause, or by any other edit to the frozen
   * bytes (brief 0009 D1: a restatement may be deleted, a frozen sentence may
   * never be reworded). The inequality is asserted too, so a
   * [FilingLink.NOT_LINKED] arm that simply returned the whole string
   * -- promise and all -- would fail rather than pass on `startsWith` alone.
   */
  @Test
  fun `the unlinkable self-report sentence is a strict prefix of the frozen one`() {
    val frozen = AssuranceTierCopy.statementOf(AssuranceTier.VOLUNTARY_SELF_REPORT, FilingLink.LINKED)
    val unlinkable = AssuranceTierCopy.statementOf(AssuranceTier.VOLUNTARY_SELF_REPORT, FilingLink.NOT_LINKED)

    assertTrue(frozen.startsWith(unlinkable), "a REWORD is not a prefix: [$unlinkable] vs [$frozen]")
    assertTrue(unlinkable.length < frozen.length, "the promise sentence must be GONE, not present: [$unlinkable]")
    assertFalse(unlinkable.contains(PROMISE.trim()), "the promise must not be said about an unlinkable filing")
  }

  /**
   * The promise sentence is written in exactly ONE place in `:service`'s main
   * source set.
   *
   * Counted as the SENTENCE TEXT over every `.kt` file in the tree, never as
   * one spelling of a call: a second copy typed by hand, pasted into another
   * object, or split across a `" +` line break is what this has to catch, and
   * a grep for `SELF_REPORT_LINK_PROMISE` would see none of them. The
   * normalisation therefore joins concatenated literals and collapses
   * whitespace BEFORE counting, so the count moves whichever way the sentence
   * is typed.
   *
   * It is an EQUALITY on the occurrence count, not a ceiling and not a
   * per-file check: RFC 200 drops this sentence for a filing we cannot link,
   * and that deletion is only sound while there is a single seam to delete it
   * at. Zero fails as loudly as two -- a sweep that silently stopped finding
   * the sentence is not evidence of anything.
   */
  @Test
  fun `the promise sentence is written in exactly one place in the service main source set`() {
    val sources =
      File("src/main")
        .walkTopDown()
        .filter { it.isFile && it.name.endsWith(".kt") }
        .toList()
    assertTrue(
      sources.size >= MIN_SCANNED_FILES,
      "the sweep read only ${sources.size} files: it lost the source tree, and proves nothing",
    )

    val occurrencesByFile =
      sources.associate { file ->
        val normalised = file.readText().replace(CONCATENATION_JOIN, "").replace(WHITESPACE_RUN, " ")
        file.path to
          PROMISE
            .trim()
            .toRegex(RegexOption.LITERAL)
            .findAll(normalised)
            .count()
      }
    val total = occurrencesByFile.values.sum()

    assertEquals(
      1,
      total,
      "the promise sentence must be written exactly once: ${occurrencesByFile.filterValues { it > 0 }}",
    )
  }

  private companion object {
    /** The locator [AssuranceTier.of] requires; this file resolves pairs it does not read back. */
    const val ROW = "the assurance-tier copy test (a named pair)"

    /** The promise sentence, written out here rather than read off the constant the sweep is counting. */
    const val PROMISE = " We link to the college's own file so you can see it."

    /**
     * A Kotlin string concatenation across a line break -- `" +` then the next
     * literal's opening quote -- removed before counting so a sentence split
     * over two lines still counts as one occurrence.
     */
    val CONCATENATION_JOIN = """"\s*\+\s*"""".toRegex()

    /** Any run of whitespace, collapsed to one space so indentation cannot hide a match. */
    val WHITESPACE_RUN = """\s+""".toRegex()

    /**
     * The floor the file count must clear for the sweep to have read the tree.
     *
     * Well below what `:service` holds, so it is not a number to maintain; it
     * is the tripwire for a walk that started in the wrong directory, which
     * drops the count to zero rather than by one.
     */
    const val MIN_SCANNED_FILES = 50
  }
}
