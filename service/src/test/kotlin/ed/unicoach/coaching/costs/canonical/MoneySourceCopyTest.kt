package ed.unicoach.coaching.costs.canonical

import ed.unicoach.college.CanonicalMoneyLoader
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.MoneySource
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The one hedge seam (RFC 177): who published a money figure, said in English
 * exactly once.
 *
 * The defect these tests stand against shipped for months. One constant said
 * every cost figure came from the College Scorecard, while
 * `CanonicalMoneyLoader.ORDERED_SOURCES` ranks both IPEDS surveys above it -- so
 * a family reading a page or a coach reading a payload was told the wrong
 * publisher for most figures, and nothing failed, because no test anywhere
 * paired a [FigureStatus] with a [MoneySource].
 */
class MoneySourceCopyTest {
  /**
   * EXHAUSTIVE over the enum, and the compile-time half of the same rule is the
   * `when` with no `else` inside [MoneySourceCopy]: a fifth source cannot ship
   * with no words, and cannot ship wearing another publisher's.
   */
  @Test
  fun `every money source is spoken, in words of its own`() {
    val labels = MoneySource.entries.associateWith { MoneySourceCopy.labelOf(it) }
    labels.forEach { (source, label) ->
      assertTrue(label.isNotBlank(), "[${source.value}] must be spoken: [$label]")
      assertFalse(label.contains(source.value), "[${source.value}] must be spoken in WORDS, never as its stored slug")
    }
    assertEquals(
      MoneySource.entries.size,
      labels.values.toSet().size,
      "two publishers spoken with one name are indistinguishable to a family: [${labels.values}]",
    )
    // The opening form is the same name, capitalised once and here: a caller
    // that hand-capitalised would be a second home for the copy.
    MoneySource.entries.forEach { source ->
      assertEquals(
        MoneySourceCopy.labelOf(source).replaceFirstChar { it.uppercase() },
        MoneySourceCopy.openingLabelOf(source),
      )
    }
  }

  /**
   * The spoken order mirrors the loader's precedence, so the publisher that
   * wins most cells is named first. Distinctness is the assertion with teeth: a
   * shared rank makes the order of a page's source sentence depend on map
   * iteration order.
   */
  @Test
  fun `every money source is ranked, and no two share a rank`() {
    val ranks = MoneySource.entries.map { MoneySourceCopy.rankOf(it) }
    assertEquals(MoneySource.entries.size, ranks.toSet().size, "ranks must be distinct: [$ranks]")
    // The loader's OWN list, never a literal restating it: a re-rank in
    // :college now fails here, instead of leaving rankOf and a hand-typed copy
    // of it agreeing with each other while every page speaks the old order.
    assertEquals(
      CanonicalMoneyLoader.ORDERED_SOURCES,
      MoneySource.entries
        .sortedBy { MoneySourceCopy.rankOf(it) }
        .filter { it in CanonicalMoneyLoader.ORDERED_SOURCES },
      "the sources that compete for one natural key are spoken in CanonicalMoneyLoader.ORDERED_SOURCES order",
    )
    // And the sources that compete with nobody are spoken AFTER all of them:
    // the publisher that answers for most cells is named first.
    assertTrue(
      (MoneySource.entries - CanonicalMoneyLoader.ORDERED_SOURCES.toSet()).all { unranked ->
        CanonicalMoneyLoader.ORDERED_SOURCES.all { MoneySourceCopy.rankOf(unranked) > MoneySourceCopy.rankOf(it) }
      },
      "a source the loader's precedence does not rank is spoken after every source it does: [$ranks]",
    )
  }

  /** A page or a payload naming several publishers reads as one English list, in rank order. */
  @Test
  fun `several publishers are spoken as one list, in precedence order, and no publisher twice`() {
    // NULL, not "": absence has one representation, so no caller can splice a
    // hole into a family-facing sentence (the rule `phraseOf` already carries).
    assertNull(MoneySourceCopy.spokenListOf(emptyList()), "no figure, no publisher to name")
    assertEquals(
      MoneySourceCopy.labelOf(MoneySource.SCORECARD),
      MoneySourceCopy.spokenListOf(listOf(MoneySource.SCORECARD, MoneySource.SCORECARD)),
      "one publisher is named once however many of its figures are on the page",
    )
    assertEquals(
      "${MoneySourceCopy.labelOf(MoneySource.IPEDS_SFA)} and ${MoneySourceCopy.labelOf(MoneySource.SCORECARD)}",
      MoneySourceCopy.spokenListOf(listOf(MoneySource.SCORECARD, MoneySource.IPEDS_SFA)),
      "the winning publisher is named first, whatever order the figures arrived in",
    )
  }

  /**
   * The arm that was `null` and untested: a plainly reported figure now says
   * where it came from, and says something DIFFERENT for each of the four
   * sources.
   */
  @Test
  fun `a reported figure names the publisher it came from, one sentence per source`() {
    val sentences =
      MoneySource.entries.associateWith {
        assertNotNull(
          FigureStatusCopy.statementOf(FigureStatus.REPORTED, it),
          "a reported figure with a known publisher must say who published it",
        )
      }
    sentences.forEach { (source, sentence) ->
      assertTrue(
        sentence.contains(MoneySourceCopy.labelOf(source)),
        "[${source.value}] must be named in its own words: [$sentence]",
      )
    }
    assertEquals(MoneySource.entries.size, sentences.values.toSet().size, "four publishers, four sentences")
    // The agentless form still says nothing here, and that is the whole reason
    // the two are separate functions: with no publisher in hand there is
    // nothing honest to say about where a plainly reported figure came from.
    assertNull(FigureStatusCopy.agentlessStatementOf(FigureStatus.REPORTED))
  }

  /**
   * ANTI-VACUITY, both halves. Three arms name the publisher and produce three
   * different sentences; three arms deliberately do not and are byte-identical
   * to the one-argument text.
   *
   * The three that do not are the point of the split (D3). The school's own
   * silence and OUR own gap are not the publisher's act, and naming a publisher
   * beside either would hand somebody else's gap to it -- which is the same
   * class of lie as the constant this seam replaced, pointed the other way.
   */
  @Test
  fun `only the publisher's own act names the publisher`() {
    val named = listOf(FigureStatus.REPORTED, FigureStatus.IMPUTED_BY_PUBLISHER, FigureStatus.SUPPRESSED_BY_PUBLISHER)
    val unnamed =
      listOf(
        FigureStatus.NOT_REPORTED_BY_INSTITUTION,
        FigureStatus.NOT_APPLICABLE,
        FigureStatus.NOT_COLLECTED_BY_US,
      )
    assertEquals(FigureStatus.entries.size, (named + unnamed).size, "every status is on one side of the line")

    val source = MoneySource.IPEDS_SFA
    val spoken =
      named.associateWith {
        assertNotNull(FigureStatusCopy.statementOf(it, source), "[${it.value}] must speak with a publisher in hand")
      }
    assertEquals(named.size, spoken.values.toSet().size, "three publisher-naming arms, three sentences: [$spoken]")
    spoken.forEach { (status, sentence) ->
      assertTrue(
        sentence.contains(MoneySourceCopy.labelOf(source), ignoreCase = true),
        "[${status.value}] is the publisher's own act and must name it: [$sentence]",
      )
      assertFalse(
        sentence.contains("the publisher"),
        "with a publisher in hand the agentless word is never used: [$sentence]",
      )
    }

    unnamed.forEach { status ->
      assertEquals(
        FigureStatusCopy.agentlessStatementOf(status),
        FigureStatusCopy.statementOf(status, source),
        "[${status.value}] is not the publisher's act, so its sentence must not change when one is in hand",
      )
      val sentence = assertNotNull(FigureStatusCopy.statementOf(status, source))
      MoneySource.entries.forEach {
        assertFalse(
          sentence.contains(MoneySourceCopy.labelOf(it), ignoreCase = true),
          "[${status.value}] must name no publisher: [$sentence]",
        )
      }
    }
  }

  /**
   * The agentless form names NOBODY, at every status -- which is what makes it
   * safe for the one caller that holds no publisher, and what makes it wrong for
   * every caller that does.
   *
   * A [MoneySource] cannot be null on this seam any more (a canonical money row
   * always carries its publisher), so the two forms are told apart by NAME
   * rather than by arity: this test pins what the agentless name promises.
   */
  @Test
  fun `the agentless sentences name no publisher at all`() {
    FigureStatus.entries.forEach { status ->
      val sentence = FigureStatusCopy.agentlessStatementOf(status) ?: return@forEach
      MoneySource.entries.forEach { source ->
        assertFalse(
          sentence.contains(MoneySourceCopy.labelOf(source), ignoreCase = true),
          "[${status.value}] is spoken with no publisher in hand and must name none: [$sentence]",
        )
      }
    }
  }

  /**
   * D2, the regression guard. [FigureStatusCopy.agentlessStatementOf] is
   * BYTE-FROZEN, and these six literals are the freeze.
   *
   * Not politeness to callers. `SystemPromptCatalogTest` walks
   * [FigureStatus.entries] and asserts that the IMMUTABLE v19 prompt row recites
   * the SHIPPING sentence for each -- so rewording any of the six fails against
   * a committed row that no new seed can fix, and a prompt row can never be
   * updated in place (`db/schema/0007`'s triggers). RFC 177 therefore added a
   * SECOND, differently NAMED function rather than widening this one, and this test says so at the
   * place a reword would be typed instead of leaving it to be discovered at the
   * pre-commit hook.
   *
   * The coupling itself is asserted where the prompt row can actually be read --
   * `SystemPromptCatalogTest`'s enum walk over the seeded v19 body. These are
   * literals typed beside the code they pin, and they fail FIRST and locally.
   */
  @Test
  fun `the agentless sentences are byte-frozen literals, one per status`() {
    assertNull(FigureStatusCopy.agentlessStatementOf(FigureStatus.REPORTED))
    assertEquals(
      "This is the publisher's own estimate for this school, not a figure the school reported.",
      FigureStatusCopy.agentlessStatementOf(FigureStatus.IMPUTED_BY_PUBLISHER),
    )
    assertEquals(
      "This figure is withheld by the publisher for privacy.",
      FigureStatusCopy.agentlessStatementOf(FigureStatus.SUPPRESSED_BY_PUBLISHER),
    )
    assertEquals(
      "This school did not report this figure.",
      FigureStatusCopy.agentlessStatementOf(FigureStatus.NOT_REPORTED_BY_INSTITUTION),
    )
    assertEquals(
      "This figure does not apply at this school, and the source says so.",
      FigureStatusCopy.agentlessStatementOf(FigureStatus.NOT_APPLICABLE),
    )
    assertEquals(
      "We have not collected this figure yet.",
      FigureStatusCopy.agentlessStatementOf(FigureStatus.NOT_COLLECTED_BY_US),
    )
  }
}
