package ed.unicoach.rest.models

import java.time.Instant

data class MoneyProfileResponse(
  val profile: PublicMoneyProfile,
)

/**
 * The student-facing money-profile projection (RFC 134): per-field tri-state
 * status with the value present exactly when the status is `answered`.
 */
data class PublicMoneyProfile(
  val incomeBandStatus: String,
  val incomeBand: String?,
  val residencyStatus: String,
  val residencyState: String?,
  val livingPlanStatus: String,
  val livingPlan: String?,
  val version: Int,
  val createdAt: Instant,
  val updatedAt: Instant,
)
