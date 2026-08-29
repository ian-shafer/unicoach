package ed.unicoach.db.models

import java.time.Instant
import java.util.UUID

/**
 * Surface id for a [CollegeDeadline] row. Lives alongside the model rather than
 * in its own file because `college_deadlines` is bulk-upserted reference data
 * with no standalone id-keyed read path (the [CollegeProgramId] precedent).
 */
@JvmInline
value class CollegeDeadlineId(
  val value: UUID,
) : Id {
  override val asString get() = value.toString()
}

/**
 * One application round for one college and CDS cycle, from the
 * `college_deadlines` reference table (RFC 140). [offered] is the reliable bit
 * (CDS C.2101/C.2201/C.1601/C.1401); the [CdsMonthDay] pairs are best-effort
 * and cycle-relative (CDS reports no year), so the render layer says "Jan 15"
 * against the cycle -- never a fabricated date. A null pair is "not reported".
 */
data class CollegeDeadline(
  override val id: CollegeDeadlineId,
  val collegeId: CollegeId,
  val sourceYear: Int,
  val round: ApplicationRound,
  val offered: Boolean,
  /** When applications close, or null when unreported. */
  val closing: CdsMonthDay?,
  /** When the school notifies, or null when unreported. */
  val notification: CdsMonthDay?,
  /** The school's own published CDS document. */
  val sourceUrl: String,
  /** The corpus's archived copy. */
  val archiveUrl: String?,
  override val createdAt: Instant,
  override val updatedAt: Instant,
) : Identifiable<CollegeDeadlineId>,
  Created,
  Updated
