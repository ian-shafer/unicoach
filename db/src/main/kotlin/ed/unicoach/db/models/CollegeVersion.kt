package ed.unicoach.db.models

import java.time.Instant

/**
 * An immutable snapshot of one `colleges_versions` row (RFC 82): the curated
 * [College] facts as they stood at a given [version], recorded by the ingest
 * upsert's history trigger. Mirrors [UserVersion]; carries no soft-delete or
 * physical-clock columns because `colleges` has none.
 */
data class CollegeVersion(
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
  val tuitionInState: Int?,
  val tuitionOutState: Int?,
  val graduationRate: Double?,
  val medianEarnings: Int?,
  val pctPell: Double?,
  val website: String?,
  override val createdAt: Instant,
  val updatedAt: Instant,
) : Identifiable<CollegeId>,
  Created,
  Versioned
