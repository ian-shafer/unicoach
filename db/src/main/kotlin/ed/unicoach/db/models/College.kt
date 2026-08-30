package ed.unicoach.db.models

import java.time.Instant

/**
 * An institution-level row from the `colleges` reference table (RFC 67): a
 * curated subset of College Scorecard data. `unitId` is the federal natural key
 * (UNITID); `id` is the project-convention DB-generated surface UUID. Mutable
 * only via re-ingestion upsert, so it carries logical `createdAt`/`updatedAt`.
 * The row is versioned via a trigger-managed `version` and a `colleges_versions`
 * history table (the upsert bumps `version` only on a real content change), with
 * no soft-delete.
 */
data class College(
  override val id: CollegeId,
  override val version: Int,
  val unitId: Int,
  val opeid: String?,
  val name: String,
  val city: String,
  val state: String,
  val region: Int?,
  val locale: Int?,
  val latitude: Double?,
  val longitude: Double?,
  val control: Int,
  val undergradEnrollment: Int?,
  val admissionRate: Double?,
  val satAvg: Int?,
  val costAttendance: Int?,
  val netPrice: Int?,
  // Average annual net price by household income bracket (RFC 133, Scorecard
  // NPT41..NPT45 keyed on control): q1 = $0-30k, q2 = $30,001-48k,
  // q3 = $48,001-75k, q4 = $75,001-110k, q5 = $110k+. Negative values are
  // legitimate (aid exceeding cost, 0022); null = not reported/suppressed.
  val netPriceQ1: Int?,
  val netPriceQ2: Int?,
  val netPriceQ3: Int?,
  val netPriceQ4: Int?,
  val netPriceQ5: Int?,
  val tuitionInState: Int?,
  val tuitionOutState: Int?,
  val graduationRate: Double?,
  val medianEarnings: Int?,
  // Median cumulative federal debt of completers (Scorecard GRAD_DEBT_MDN, RFC 133).
  val medianDebt: Int?,
  val pctPell: Double?,
  val website: String?,
  // Curated nicknames ("Mizzou", "UMass Amherst") from db/data/college-aliases.json
  // (RFC 139): repo data, not Scorecard data, applied by ingest after the
  // Scorecard upsert phase. Feeds the search text (name + aliases) both the
  // one-keystroke word table and the substring arm range over (RFC 146); empty
  // when uncurated.
  val aliases: List<String>,
  override val createdAt: Instant,
  override val updatedAt: Instant,
) : Identifiable<CollegeId>,
  Created,
  Updated,
  Versioned
