package ed.unicoach.db.models

/**
 * An IPEDS item-imputation flag: the `X`-prefixed twin every measured IPEDS
 * variable carries (`npist2` -> `xnpist2`), and the [status] it means in our
 * own vocabulary (RFC 162).
 *
 * The thirteen codes are the published list -- `SFA2223_Dict.zip` ->
 * `sfa2223.xlsx`, sheet "imputation values", titled "Code values for item
 * imputation variables Xvarname" -- transcribed here rather than fetched,
 * because a thirteen-row table that changes when NCES changes its survey is
 * authored vocabulary with a citation, not a data file to parse at runtime.
 * The Stata syntax archives carry no `label define` for them at all.
 *
 * The mapping is measured, not assumed. Over four aid years and 1.95M
 * (value, flag) pairs only `R`, `A`, `Z`, `P`, `C` and `N` ever occur, with
 * three invariants and zero exceptions: `A` always has an empty value cell,
 * `R`/`C`/`P`/`N` always have one, and `Z` always has exactly `0`.
 *
 * [IMPLIED_ZERO] is why this enum exists rather than a `when` at the read
 * site. `Z` is a REAL PUBLISHED ZERO -- the institution awarded none of that
 * aid, and NCES writes it as `0` -- so it is [FigureStatus.REPORTED]. Calling
 * it an imputation would label 17,357 honest zeros in one file as publisher
 * guesses, and would make "this school awards no state grants" read as
 * unreliable.
 *
 * An unknown letter has NO mapping and must be fatal at the read site: the
 * publisher changed the vocabulary, and which of our six statuses that means
 * is a review, never a default.
 */
enum class IpedsImputationFlag(
  /** The raw published letter, stored as-is on the fact row's `publisher_flag`. */
  val code: String,
  /** What the letter means, PARTITIONED: a value-bearing reason or an absence. */
  val meaning: FlagMeaning,
) {
  /** "Reported": the institution reported the value. */
  REPORTED("R", FlagMeaning.Bears(ValueBearingStatus.REPORTED)),

  /** "Analyst corrected reported value": still the institution's datum, corrected. */
  ANALYST_CORRECTED("C", FlagMeaning.Bears(ValueBearingStatus.REPORTED)),

  /** "Implied zero": a real published 0, never an imputation -- see the class doc. */
  IMPLIED_ZERO("Z", FlagMeaning.Bears(ValueBearingStatus.REPORTED)),

  /** "Not applicable": the cell cannot apply here, and the value cell is empty. */
  NOT_APPLICABLE("A", FlagMeaning.Absent(AbsenceStatus.NOT_APPLICABLE)),

  /** "Imputed using Carry Forward procedure" (prior year). */
  CARRY_FORWARD("P", FlagMeaning.Bears(ValueBearingStatus.IMPUTED_BY_PUBLISHER)),

  /** "Imputed using Nearest Neighbor procedure". */
  NEAREST_NEIGHBOR("N", FlagMeaning.Bears(ValueBearingStatus.IMPUTED_BY_PUBLISHER)),

  /** "Data generated from other data values". */
  GENERATED("G", FlagMeaning.Bears(ValueBearingStatus.IMPUTED_BY_PUBLISHER)),

  /** "Logical imputation". */
  LOGICAL("J", FlagMeaning.Bears(ValueBearingStatus.IMPUTED_BY_PUBLISHER)),

  /** "Ratio adjustment". */
  RATIO_ADJUSTMENT("K", FlagMeaning.Bears(ValueBearingStatus.IMPUTED_BY_PUBLISHER)),

  /** "Imputed using the Group Median procedure". */
  GROUP_MEDIAN("L", FlagMeaning.Bears(ValueBearingStatus.IMPUTED_BY_PUBLISHER)),

  /** "Institution left item blank". Never seen in SFA -- NCES imputes instead of blanking -- but IC does publish it. */
  LEFT_BLANK("B", FlagMeaning.Absent(AbsenceStatus.NOT_REPORTED_BY_INSTITUTION)),

  /** "Do not know". */
  DO_NOT_KNOW("D", FlagMeaning.Absent(AbsenceStatus.NOT_REPORTED_BY_INSTITUTION)),

  /**
   * "Value not derived - data not usable". Filed as an ABSENCE rather than a
   * publisher imputation because NOTHING WAS IMPUTED: NCES could not derive a
   * number, so the cell carries none and there is no published figure to
   * attribute. The status is named for the institution's silence, which is
   * the closest of the two absence readings -- the cell is empty either way.
   * Never observed in SFA across four aid years, so this mapping is reasoned,
   * not measured: a first sighting is worth re-deciding on.
   */
  NOT_DERIVED("H", FlagMeaning.Absent(AbsenceStatus.NOT_REPORTED_BY_INSTITUTION)),
  ;

  /** What the letter means in the `figure_statuses` vocabulary, flattened for the stored column. */
  val status: FigureStatus get() = meaning.status

  companion object {
    /**
     * `Y`, a professional-practice program: a REAL published code, but one that
     * never occurs on a variable either IPEDS loader reads. It is named here --
     * beside the thirteen that ARE read -- so a fatal can say "published, but
     * never on a loaded variable" instead of "unknown", and it is deliberately
     * NOT an entry: giving it a status would be inventing a reading.
     */
    const val PROFESSIONAL_PRACTICE = "Y"

    private val BY_CODE = entries.associateBy { it.code }

    /** The readable codes, sorted -- for a fatal that has to say what it would have accepted. */
    val CODES: List<String> = BY_CODE.keys.sorted()

    /**
     * The flag for a raw cell, or null when the letter is not a published
     * code. Case-insensitive and trimmed, because the code is read out of a
     * CSV cell; NULL is returned rather than a default so the caller decides
     * how loudly to fail (both IPEDS loaders raise the failure with the row
     * identity they hold, which a shared throw could not name).
     */
    fun fromCode(raw: String?): IpedsImputationFlag? = raw?.let { BY_CODE[it.trim().uppercase()] }
  }
}

/**
 * What an IPEDS flag says about the cell beside it: a reason the cell BEARS a
 * value, or a reason it bears NONE. The partition is fixed when the code is
 * authored, so it is carried on [IpedsImputationFlag] rather than rediscovered
 * at the read site by searching [ValueBearingStatus] and [AbsenceStatus] --
 * which made "neither" a runtime fatal for a case the type can forbid.
 *
 * Mirrors the [FigureReading] partition it feeds: a `Bears` cell becomes a
 * [FigureReading.Present], an `Absent` one a [FigureReading.Absent].
 */
sealed interface FlagMeaning {
  val status: FigureStatus

  /** The cell carries a value, for this value-bearing reason. */
  data class Bears(
    val bearing: ValueBearingStatus,
  ) : FlagMeaning {
    override val status: FigureStatus get() = bearing.status
  }

  /** The cell carries no value, for this reason. */
  data class Absent(
    val absence: AbsenceStatus,
  ) : FlagMeaning {
    override val status: FigureStatus get() = absence.status
  }
}
