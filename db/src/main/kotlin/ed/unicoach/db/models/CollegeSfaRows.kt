package ed.unicoach.db.models

import ed.unicoach.common.util.AcademicYear

/**
 * One `college_sfa` staging cell (RFC 162): an institution's one published
 * IPEDS SFA variable, the aid year it describes, the value, and the raw X
 * imputation flag beside it.
 *
 * [variable] is the published name lowercase, exactly as the CSV header
 * spells it, so a stored row leads straight back to the NCES dictionary
 * entry. [value] is null exactly when the flag bears no value; [flag] is the
 * typed reading of the letter, and the raw letter is what the table stores.
 */
data class NewCollegeSfaCell(
  val ipedsUnitId: Int,
  /**
   * The START year of the FILE's own aid year (SFA2223 -> 2022), stamped on
   * every cell it wrote. NOT a survey year: `college_ipeds.survey_year` is
   * 2023 for the same published file set.
   */
  val aidYearStart: Int,
  val variable: String,
  /** The year this cell describes: the file-relative suffix resolved against [aidYearStart]. */
  val aidYear: AcademicYear,
  val value: Double?,
  val flag: IpedsImputationFlag,
)

/** One `college_sfa` row as read back for the canonical fill. */
data class CollegeSfaCell(
  val ipedsUnitId: Int,
  val variable: String,
  val aidYear: AcademicYear,
  val value: Double?,
  val flag: IpedsImputationFlag,
)

/**
 * This staged cell as a [FigureReading] (RFC 162 D3): the X flag decides the
 * status by an EXHAUSTIVE [FlagMeaning] `when`, and the value must agree with
 * it. [unit] converts the source's published value to the stored one through
 * [MeasureUnit.storedValueOf] -- a unit conversion, never a derivation, and the
 * measure's own unit rather than a bare factor a caller could mis-apply.
 *
 * NULL for the one disagreement the source should never publish -- a
 * value-bearing flag over an empty cell, measured at zero occurrences over
 * 1.95M pairs. Inventing a value to match the flag, or a status to match the
 * missing value, would both be the reader deciding something the publisher did
 * not say; `not_reported_by_institution` in particular is a claim about the
 * INSTITUTION that SFA never makes -- NCES imputes rather than blanking.
 *
 * PURE: the caller decides what a null means for its table and TALLIES it,
 * which is where the count belongs (the `MapResult` convention: the mapper
 * maps, the loop folds the counters).
 */
fun CollegeSfaCell.reading(unit: MeasureUnit = MeasureUnit.USD_PER_YEAR): FigureReading<Double>? =
  when (val meaning = flag.meaning) {
    is FlagMeaning.Absent -> FigureReading.Absent(meaning.absence)
    is FlagMeaning.Bears -> value?.let { FigureReading.Present(unit.storedValueOf(it), meaning.bearing) }
  }
