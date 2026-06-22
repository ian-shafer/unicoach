package ed.unicoach.db.models

import java.time.Instant

/**
 * An institution-level row from the `colleges` reference table (RFC 67): a
 * curated subset of College Scorecard data. `unitId` is the federal natural key
 * (UNITID); `id` is the project-convention DB-generated surface UUID. Mutable
 * only via re-ingestion upsert, so it carries logical `createdAt`/`updatedAt`
 * but no versioning or soft-delete.
 */
data class College(
  override val id: CollegeId,
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
  val tuitionInState: Int?,
  val tuitionOutState: Int?,
  val graduationRate: Double?,
  val medianEarnings: Int?,
  val pctPell: Double?,
  val website: String?,
  override val createdAt: Instant,
  override val updatedAt: Instant,
) : Identifiable<CollegeId>,
  Created,
  Updated
