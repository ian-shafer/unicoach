package ed.unicoach.coaching.costs.canonical

import ed.unicoach.coaching.costs.NoTotalReason
import ed.unicoach.db.models.FigureStatus

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
   * What a family is told about a figure with this status, or null for
   * [FigureStatus.REPORTED] -- a plainly reported figure is shown plainly and
   * needs no sentence beside it.
   */
  fun statementOf(status: FigureStatus): String? =
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
