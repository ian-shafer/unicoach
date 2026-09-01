package ed.unicoach.db.models

import java.time.Instant

/**
 * A student's share link for the Family Cost Report (RFC 155). A hashed
 * credential row: the raw token is never stored, only its SHA-256 hash, and the
 * row's only mutation is stamping [revokedAt] exactly once. A row with a null
 * [revokedAt] is *live*; at most one live row exists per student.
 */
data class CostReportShare(
  override val id: CostReportShareId,
  val studentId: StudentId,
  val tokenHash: TokenHash,
  val revokedAt: Instant?,
  override val createdAt: Instant,
) : Identifiable<CostReportShareId>,
  Created
