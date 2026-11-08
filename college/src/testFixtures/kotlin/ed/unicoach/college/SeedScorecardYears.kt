package ed.unicoach.college

import ed.unicoach.common.util.AcademicYear

/**
 * The two academic years the COMMITTED data dictionary dates the Scorecard's
 * money to (RFC 274), READ through the production reader -- so no test types
 * a Scorecard year, and a newer fetched release moves every assertion with it.
 *
 * Read per column the fill dates, by a representative of each publisher
 * cohort: the published charges (`TUITIONFEE_IN`) and the blended average and
 * net prices (`COSTT4_A`). That the rest of each family agrees is
 * `ScorecardDictionaryTest`'s claim, checked against the same file.
 */
object SeedScorecardYears {
  /** The year the published charges are stamped with. */
  val PUBLISHED_CHARGE: AcademicYear
    get() = SeedScorecardDictionary.dictionary.academicYearOf(ScorecardInstitutionColumns.TUITIONFEE_IN)

  /** The year `COSTT4_A` and the net-price family are stamped with. */
  val BLENDED_AVERAGE: AcademicYear
    get() = SeedScorecardDictionary.dictionary.academicYearOf(ScorecardInstitutionColumns.COSTT4_A)
}
