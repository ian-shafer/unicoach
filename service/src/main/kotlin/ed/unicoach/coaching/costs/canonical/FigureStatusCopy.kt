package ed.unicoach.coaching.costs.canonical

import ed.unicoach.coaching.costs.NoTotalReason
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.MoneySource

/**
 * WHOSE gap a missing figure is (RFC 166 §6) -- the OURS/THEIRS split RFC 149
 * D-B established and this file reuses a fourth time.
 *
 * It is not decoration. `ArrangementGap` may only ever claim what the SCHOOL
 * published, so a gap of ours routed into that vocabulary would tell a family
 * "this school does not publish it" about a cell we simply have not collected.
 * The two are therefore different types of sentence, and which one a status
 * produces is decided once, here.
 */
enum class FigureGapOwner {
  /** The school did not report it, or the cell does not apply to it. */
  SCHOOL,

  /** The publisher withheld it, or estimated it rather than receiving it. */
  PUBLISHER,

  /** OURS: we have not collected it. Never an `ArrangementGap`. */
  UNICOACH,
}

/**
 * The six figure statuses, spoken (RFC 166 §6).
 *
 * `db/schema/0083` has stored `suppressed_by_publisher`, `imputed_by_publisher`
 * and `not_collected_by_us` since RFC 158 and nothing could read them. A NULL
 * column cannot tell a family whether a number is missing because the publisher
 * withheld it, because the school never reported it, or because we have not
 * collected it -- three different facts that a blank cell collapsed into one.
 *
 * Every `when` here is exhaustive with NO `else`: a seventh status added to
 * [FigureStatus] must fail to compile at both sites rather than ship a code with
 * no words, which is exactly how `not_collected_by_us` reached production
 * unspoken in the first place.
 */
object FigureStatusCopy {
  /**
   * What a family is told about a figure with this status when NO publisher is
   * in hand, or null for [FigureStatus.REPORTED] -- a plainly reported figure is
   * shown plainly and needs no sentence beside it.
   *
   * AGENTLESS by name (RFC 177), not by arity. These six sentences say "the
   * publisher" and name nobody, which is the hedge this seam exists to remove,
   * so a caller that HOLDS a [MoneySource] must not be able to reach them by
   * dropping an argument: the two forms used to differ only in how many
   * arguments were typed, and the agentless one compiled, formatted and passed
   * everywhere the naming one was meant. Only a caller with genuinely no
   * publisher -- no row answers for the cell -- says this.
   */
  fun agentlessStatementOf(status: FigureStatus): String? =
    when (status) {
      FigureStatus.REPORTED -> {
        null
      }

      FigureStatus.IMPUTED_BY_PUBLISHER -> {
        "This is the publisher's own estimate for this school, not a figure the school reported."
      }

      FigureStatus.SUPPRESSED_BY_PUBLISHER -> {
        "This figure is withheld by the publisher for privacy."
      }

      FigureStatus.NOT_REPORTED_BY_INSTITUTION -> {
        "This school did not report this figure."
      }

      FigureStatus.NOT_APPLICABLE -> {
        "This figure does not apply at this school, and the source says so."
      }

      FigureStatus.NOT_COLLECTED_BY_US -> {
        "We have not collected this figure yet."
      }
    }

  /**
   * What a family is told about a figure with this status, naming the publisher
   * that actually published it where the sentence is about the PUBLISHER's own
   * act (RFC 177 D3).
   *
   * A second function rather than a widened one, and
   * [agentlessStatementOf] is byte-frozen (D2): `SystemPromptCatalogTest`
   * asserts that the IMMUTABLE v19 prompt row recites the shipping sentence for
   * every status, so rewording the six would fail against a row no new seed can
   * fix.
   *
   * Three arms name the publisher and three deliberately do not. The rule is
   * [ownerOf]: a sentence about what the PUBLISHER did says which publisher did
   * it, and a sentence about the SCHOOL's silence or about OUR own gap does not
   * -- naming a publisher there would hand our gap, or the school's, to somebody
   * who never had it. Those three arms delegate to [agentlessStatementOf], which
   * is what they say and where they say it from.
   *
   * [source] is NON-NULL: every canonical money row carries its publisher
   * (`PriceFigure.source`, `CohortMoneyStat.source`), so a figure this function
   * speaks for always has one. Where no row answers at all there is no figure
   * and no provenance -- the caller holds nothing and says
   * [agentlessStatementOf] instead.
   */
  fun statementOf(
    status: FigureStatus,
    source: MoneySource,
  ): String? =
    when (status) {
      // The arm that is null agentless. A plainly reported figure needs no
      // qualification, but WHO published it is a fact about it that a family
      // could not learn anywhere else on the surface.
      FigureStatus.REPORTED -> {
        "This figure comes from ${MoneySourceCopy.labelOf(source)}."
      }

      // The publisher's own act, so the publisher is named: "the publisher"
      // estimated nothing -- IPEDS did, or the Scorecard did.
      FigureStatus.IMPUTED_BY_PUBLISHER -> {
        "${MoneySourceCopy.openingLabelOf(source)} estimated this for the school; the school did not report it."
      }

      // The publisher's own act again, and the one a family most often reads as
      // the school hiding something.
      FigureStatus.SUPPRESSED_BY_PUBLISHER -> {
        "${MoneySourceCopy.openingLabelOf(source)} withholds this figure to protect students' privacy."
      }

      // The SCHOOL's silence. The publisher published faithfully what it was
      // given, and naming it here would read as the publisher's failure.
      FigureStatus.NOT_REPORTED_BY_INSTITUTION -> {
        agentlessStatementOf(status)
      }

      // The school's answer, relayed. "the source says so" is already the
      // publisher, agentlessly, and the fact is that the cell does not apply --
      // not that any publisher decided anything.
      FigureStatus.NOT_APPLICABLE -> {
        agentlessStatementOf(status)
      }

      // OURS, and the gap is not the publisher's doing even though one is in
      // hand -- naming it would misattribute our own gap, which is the lie this
      // whole seam exists to remove.
      FigureStatus.NOT_COLLECTED_BY_US -> {
        agentlessStatementOf(status)
      }
    }

  /** Whose gap a figure with this status is -- the input to the [ArrangementGap]/[NoTotalReason] routing below. */
  fun ownerOf(status: FigureStatus): FigureGapOwner =
    when (status) {
      // A value-bearing status is nobody's gap; the owner is stated anyway
      // because an imputed figure is the PUBLISHER's number even when it is
      // shown, and the sentence beside it says so.
      FigureStatus.REPORTED -> FigureGapOwner.SCHOOL

      FigureStatus.IMPUTED_BY_PUBLISHER -> FigureGapOwner.PUBLISHER

      FigureStatus.SUPPRESSED_BY_PUBLISHER -> FigureGapOwner.PUBLISHER

      FigureStatus.NOT_REPORTED_BY_INSTITUTION -> FigureGapOwner.SCHOOL

      FigureStatus.NOT_APPLICABLE -> FigureGapOwner.SCHOOL

      FigureStatus.NOT_COLLECTED_BY_US -> FigureGapOwner.UNICOACH
    }

  /**
   * Whether a figure with this status may be claimed as something the SCHOOL
   * published or failed to publish.
   *
   * FALSE for [FigureStatus.NOT_COLLECTED_BY_US] and for the publisher's own
   * suppression: neither is the school's silence, and
   * `ArrangementGap`/`data_availability` would misattribute it.
   */
  fun isSchoolsOwnSilence(status: FigureStatus): Boolean = ownerOf(status) == FigureGapOwner.SCHOOL

  /**
   * The [NoTotalReason] a component missing with this status produces (RFC 166
   * §6 rule 1).
   *
   * TWO things are decided here, not one. First, WHERE the answer goes: an
   * absence reaches [NoTotalReason], which is a sentence about the total, and
   * never `ArrangementGap`, whose every sentence is a statement about the
   * school's own price list. Second, WHICH sentence: a gap of OURS gets
   * [NoTotalReason.PART_NOT_COLLECTED_BY_US], because
   * [NoTotalReason.PART_NOT_PUBLISHED] says the part is not published and we
   * cannot say that about a figure we simply never collected.
   *
   * Exhaustive with no `else`, so a seventh status must decide which side of
   * that line it falls on before it compiles.
   */
  fun noTotalReasonOf(status: FigureStatus): NoTotalReason =
    when (ownerOf(status)) {
      // The school did not report it, or said it does not apply: the figure is
      // genuinely absent from the price list, which is what the phrase says.
      FigureGapOwner.SCHOOL -> NoTotalReason.PART_NOT_PUBLISHED

      // The publisher withheld it or estimated it. The phrase is agentless --
      // "a part ... is not published" -- and stays true: what we hold has no
      // published number in it. The school is not named and is not blamed.
      FigureGapOwner.PUBLISHER -> NoTotalReason.PART_NOT_PUBLISHED

      // OURS, and the one arm that must NOT say "not published": we have not
      // collected the part, and the school may publish it perfectly well.
      FigureGapOwner.UNICOACH -> NoTotalReason.PART_NOT_COLLECTED_BY_US
    }

  /**
   * What a family is told about a figure this school published, but not in the
   * academic year the rest of its price is quoted at (RFC 166 §3).
   *
   * OURS, and said as ours: the school published the figure, and the reason it
   * is not shown is that we hold it for another year and never mix years inside
   * one total. Before this sentence existed such a figure was dropped with no
   * key and no status at all, which is the silence this whole surface is
   * against.
   */
  fun yearGapStatementOf(
    servedYear: String,
    heldYear: String,
  ): String =
    "We show this school's prices for $servedYear, and the most recent year we hold this figure for is " +
      "$heldYear. We do not mix years inside one total, so it is not shown here."

  /**
   * The status a YEAR GAP is spoken under: ours, never the school's.
   *
   * [FigureStatus.NOT_COLLECTED_BY_US] is the store's own word for a gap of
   * ours, and it is reused rather than a seventh status invented: what we have
   * not collected is this figure FOR THIS YEAR, and [yearGapStatementOf] says
   * exactly that. Routing it anywhere else would let a year of ours be read as
   * the school's silence.
   *
   * It is NOT the held row's own status. That row is value-bearing by
   * definition (`CollegeFigures.yearGapOf` declines a value-free one), so its
   * status is `reported` or `imputed_by_publisher` -- codes that say the figure
   * is SHOWN, beside a figure this answer is deliberately not showing, and
   * `reported` has no sentence at all. What distinguishes this case from a cell
   * we have genuinely never collected is the two YEARS, and those now travel as
   * data on the note (`FigureStatusNote.heldAcademicYear`) rather than only
   * inside [yearGapStatementOf].
   */
  val YEAR_GAP_STATUS: FigureStatus = FigureStatus.NOT_COLLECTED_BY_US
}
