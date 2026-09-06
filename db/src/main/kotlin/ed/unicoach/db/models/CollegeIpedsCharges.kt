package ed.unicoach.db.models

import ed.unicoach.common.util.AcademicYear
import java.time.Instant
import java.util.UUID

/*
 * The IPEDS IC_AY published-charges staging rows (RFC 161).
 *
 * IC2023_AY.csv is a 235-column wide table whose value columns are a
 * year × tier × component cross-product. `college_ipeds_charges` is the NARROW
 * reshape of it: one row per college × charge variable stem × academic year,
 * carrying the published value and its raw X imputation flag verbatim. No
 * unicoach reading is applied here — that happens once, in the canonical-money
 * fill.
 */

/**
 * Input for upserting one `college_ipeds_charges` row on its natural key
 * `(collegeId, chargeVariable, academicYear)`. Carries no `id` (DB-generated)
 * and no timestamps (DB-managed).
 *
 * [chargeVariable] is the IPEDS stem with the `0`-`3` year suffix removed, so
 * `CHG2AY3` arrives as `CHG2AY` + academic year 2023 (its START year; the
 * '2023-24' label is rendered at read time -- RFC 170, D14). [amountUsd] is `null` exactly when
 * [imputationFlag] bears no value -- WHICH published codes those are belongs to
 * [IpedsImputationFlag], their one declaration, and is not restated
 * here. The loader enforces the pairing at parse, the
 * `college_ipeds_charges_value_iff_flag_check` CHECK enforces it in the
 * database, and the canonical fill re-reads the flag.
 */
data class NewCollegeIpedsCharge(
  val collegeId: UUID,
  val chargeVariable: String,
  val academicYear: AcademicYear,
  val amountUsd: Int?,
  val imputationFlag: String,
)

/**
 * One `college_ipeds_charges` NATURAL KEY: the triple a staged row is
 * identified by, and so exactly what a run's prune keeps
 * ([ed.unicoach.db.dao.CollegeIpedsChargesDao.deleteNotIn]).
 *
 * It is a type rather than three parallel collections because the prune's
 * correctness is precisely that the three parts travel TOGETHER: a keep-set
 * built from three independent axes spares a row whose college, variable and
 * year each occur somewhere in the run, which is the row a failed upsert left
 * holding the previous file's amount.
 */
data class ChargeKey(
  val collegeId: UUID,
  val chargeVariable: String,
  val academicYear: AcademicYear,
)

/**
 * Surface id for a [CollegeIpedsCharge] row. Lives alongside the model rather
 * than in its own file because `college_ipeds_charges` is bulk-upserted staging
 * data with no standalone id-keyed read path (the [CollegeMeritAidId]
 * precedent).
 */
@JvmInline
value class CollegeIpedsChargeId(
  val value: UUID,
) : Id {
  override val asString get() = value.toString()
}

/**
 * One STORED `college_ipeds_charges` row as the canonical-money fill reads it
 * back. It differs from [NewCollegeIpedsCharge] by exactly what the database
 * owns and a write input cannot carry — the surrogate [id] and the two
 * timestamps (the [CollegeMeritAid] precedent) — which is what makes it a
 * distinct type rather than a rename of the input.
 */
data class CollegeIpedsCharge(
  override val id: CollegeIpedsChargeId,
  val collegeId: UUID,
  val chargeVariable: String,
  val academicYear: AcademicYear,
  val amountUsd: Int?,
  val imputationFlag: String,
  override val createdAt: Instant,
  override val updatedAt: Instant,
) : Identifiable<CollegeIpedsChargeId>,
  Created,
  Updated
