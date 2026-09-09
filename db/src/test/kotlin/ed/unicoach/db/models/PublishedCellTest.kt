package ed.unicoach.db.models

import ed.unicoach.db.dao.CorruptPersistedValueException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The published cell (RFC 184): the `(source, source_variable)` pair a stored
 * money row carries, closed into one type and decoded ONCE at the read
 * boundary.
 *
 * What is worth pinning here is not the tier -- [AssuranceTierTest] owns that,
 * and owns it through the resolver whose signature did not change. It is the
 * DECODE: that each stored source lands on the arm that stands for it, that an
 * arm reports the source it stands for rather than one it was handed, and that
 * the one arm that can refuse still refuses in the house's located shape.
 */
class PublishedCellTest {
  @Test
  fun `each stored source decodes to the arm that stands for it`() {
    // The round trip that matters: the pair goes in as the two columns a row
    // holds, and what comes back knows which publisher it is without anybody
    // carrying the source alongside it.
    assertIs<PublishedCell.Surveyed>(PublishedCell.of(MoneySource.IPEDS_SFA, "NPIST2", ROW))
    assertIs<PublishedCell.Surveyed>(PublishedCell.of(MoneySource.IPEDS_IC_AY, "CHG2AY3", ROW))
    assertIs<PublishedCell.SelfPublished>(PublishedCell.of(MoneySource.COMMON_DATA_SET, "H.209", ROW))
    assertIs<PublishedCell.ScorecardCell>(PublishedCell.of(MoneySource.SCORECARD, "TUITIONFEE_IN", ROW))
  }

  @Test
  fun `a decoded cell reports back the source and variable it was built from`() {
    // The whole point of closing the pair is that nothing above the DAO has to
    // keep the two columns beside each other, so the cell must be able to say
    // both. `source` in particular is what every existing read site -- the wire
    // builders, the report page, the status copy -- still asks for.
    for (source in MoneySource.entries) {
      val variable = if (source == MoneySource.SCORECARD) "COSTT4_A" else "a variable this publisher publishes"
      val cell = PublishedCell.of(source, variable, ROW)
      assertEquals(source, cell.source, "the cell must report the publisher it was decoded from")
      assertEquals(variable, cell.variable, "and the publisher's own name for the cell, unaltered")
    }
  }

  @Test
  fun `the two surveys report their own publisher, and a survey cannot name a third one`() {
    // `Survey` exists so that `Surveyed` cannot be handed the Scorecard: there
    // is no such member to hand it. Two members, and each maps back to the
    // column value the store writes, so nothing above this file learns a second
    // spelling for a publisher it already knows.
    assertEquals(MoneySource.IPEDS_SFA, PublishedCell.Surveyed(PublishedCell.Survey.SFA, "NPIST2").source)
    assertEquals(MoneySource.IPEDS_IC_AY, PublishedCell.Surveyed(PublishedCell.Survey.IC_AY, "CHG2AY3").source)
    assertEquals(
      setOf(MoneySource.IPEDS_SFA, MoneySource.IPEDS_IC_AY),
      PublishedCell.Survey.entries
        .map { it.source }
        .toSet(),
      "the survey vocabulary is the two IPEDS surveys and nothing else",
    )
  }

  @Test
  fun `a self-published cell stands for the Common Data Set, whatever field id it carries`() {
    // It takes no source at all, so there is no pairing to get wrong: the
    // instrument is a property of the publisher on this arm, and there is
    // exactly one self-published publisher in this corpus.
    for (fieldId in listOf("H.209", "H.801", "a field id nobody files")) {
      val cell = PublishedCell.SelfPublished(fieldId)
      assertEquals(MoneySource.COMMON_DATA_SET, cell.source)
      assertEquals(AssuranceTier.VOLUNTARY_SELF_REPORT, cell.assurance)
    }
  }

  @Test
  fun `a blank variable is refused at the construction site, on every arm that takes one`() {
    // The union's own guard, and the only rule the three arms share. Every
    // fact table already CHECKs `source_variable <> ''`, so a cell whose
    // publisher named nothing may not exist above the store either.
    //
    // IllegalArgumentException and NOT the located corrupt-value path, which
    // is the distinction worth pinning: this refuses a CONSTRUCTION SITE that
    // named nothing -- a programming error the build should never ship --
    // whereas the corrupt-value path is for a value read back OUT of the
    // store, where one bad row must cost one row and no more. The two arms
    // below are the ones a caller hands a variable to directly; the Scorecard
    // arm can only be reached through `of`, whose tier lookup answers first.
    assertFailsWith<IllegalArgumentException> {
      PublishedCell.Surveyed(PublishedCell.Survey.SFA, "")
    }
    assertFailsWith<IllegalArgumentException> {
      PublishedCell.SelfPublished("   ")
    }
    val thrown =
      assertFailsWith<IllegalArgumentException> {
        PublishedCell.of(MoneySource.IPEDS_IC_AY, "", ROW)
      }
    assertTrue(
      "source_variable" in thrown.message.orEmpty(),
      "the refusal names the column it stands for: [${thrown.message}]",
    )
  }

  @Test
  fun `the tier rides on the cell, and the Scorecard arm is the one keyed on the variable`() {
    // Read as a PROPERTY of the cell rather than resolved again by each caller,
    // which is what RFC 184 moved. The values are `AssuranceTier`'s own and are
    // pinned there; what is pinned HERE is that the cell answers with them.
    assertEquals(
      AssuranceTier.MANDATORY_SURVEY,
      PublishedCell.of(MoneySource.IPEDS_IC_AY, "a variable nobody publishes", ROW).assurance,
    )
    assertEquals(
      AssuranceTier.MANDATORY_SURVEY,
      PublishedCell.of(MoneySource.SCORECARD, "TUITIONFEE_IN", ROW).assurance,
    )
    assertEquals(
      AssuranceTier.ADMINISTRATIVE_RECORD,
      PublishedCell.of(MoneySource.SCORECARD, "MD_EARN_WNE_P10", ROW).assurance,
      "one publisher, two answers -- which is why this arm is keyed on the variable at all",
    )
  }

  @Test
  fun `an unmapped Scorecard variable is refused, in the house's located shape, with both halves of the pair`() {
    // The ONE runtime refusal this type keeps. `source_variable` is an open
    // TEXT column and the set of legal Scorecard names lives in `:college`, so
    // it cannot be closed from here -- the refusal is the whole of the guard,
    // and a test that accepted a default tier would BE the defect.
    // It leaves as the located corrupt-value fault every sibling decode raises,
    // so the DAO's row boundary catches it and the cell costs one row rather
    // than the family's whole answer.
    val thrown =
      assertFailsWith<CorruptPersistedValueException> {
        PublishedCell.of(MoneySource.SCORECARD, "DEBT_MDN", ROW)
      }
    assertEquals("source=[scorecard] source_variable=[DEBT_MDN]", thrown.value, "both halves of the pair, as data")
    assertEquals(ROW, thrown.location, "and where the offending row is")
    val message = thrown.message.orEmpty()
    assertTrue("source=[scorecard]" in message, "the message names the source's own slug: [$message]")
    assertTrue("source_variable=[DEBT_MDN]" in message, "and the variable that has no tier: [$message]")
  }

  @Test
  fun `the direct Scorecard factory refuses the same string the decoder does`() {
    // `ScorecardCell.of` is the ONLY door to that arm -- its constructor is
    // private and `@ConsistentCopyVisibility` closes `copy()` with it -- so a
    // caller that reaches for it directly meets the same guard, not a softer
    // one. That is what stops the defect this RFC is about from being moved one
    // type inward: no caller can pair `TUITIONFEE_IN` with
    // `ADMINISTRATIVE_RECORD` by hand.
    assertFailsWith<CorruptPersistedValueException> {
      PublishedCell.ScorecardCell.of("DEBT_MDN", ROW)
    }
    assertEquals(
      AssuranceTier.ADMINISTRATIVE_RECORD,
      PublishedCell.ScorecardCell.of("GRAD_DEBT_MDN", ROW).assurance,
      "the tier comes off SCORECARD_TIERS and cannot be talked out of it",
    )
  }

  @Test
  fun `the resolver still answers exactly what the cell does, because it now IS the cell`() {
    // AssuranceTier.of kept its signature and delegates here (RFC 184), so
    // there is one `when` over the pair in the tree. This pins the delegation
    // itself: if the two ever disagreed, every caller that still holds the two
    // columns would be reading a second answer to one question.
    for (source in MoneySource.entries) {
      for (variable in AssuranceTier.SCORECARD_TIERS.keys + "H.209" + "CHG2AY3") {
        // The Scorecard arm answers only for its own vocabulary, so the
        // non-Scorecard names are skipped for it rather than expected to throw
        // -- the refusal has its own test above.
        if (source == MoneySource.SCORECARD && variable !in AssuranceTier.SCORECARD_TIERS) continue
        assertEquals(
          PublishedCell.of(source, variable, ROW).assurance,
          AssuranceTier.of(source, variable, ROW),
          "the resolver and the cell must never disagree: source=[${source.value}] variable=[$variable]",
        )
      }
    }
  }

  private companion object {
    /** A row locator in the shape the DAO mappers pass -- the natural key, never a rendered sentence. */
    const val ROW = "college_id=[00000000-0000-0000-0000-000000000000] price_concept=[tuition_and_fees]"
  }
}
