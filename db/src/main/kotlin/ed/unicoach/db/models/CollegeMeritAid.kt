package ed.unicoach.db.models

import java.time.Instant
import java.util.UUID

/**
 * Surface id for a [CollegeMeritAid] row. Lives alongside the model rather than
 * in its own file because `college_merit_aid` is bulk-upserted reference data
 * with no standalone id-keyed read path (the [CollegeProgramId] precedent).
 */
@JvmInline
value class CollegeMeritAidId(
  val value: UUID,
) : Id {
  override val asString get() = value.toString()
}

/**
 * One CDS cycle's H2A merit-aid facts for one college, from the
 * `college_merit_aid` reference table (RFC 140). [sourceYear] is the CDS cycle
 * as the `cds_source_year` domain defines it (db/schema/0054 -- the one home of
 * that convention). The merit share is DERIVED at read time
 * as [noNeedMeritRecipientsHeadcount] / [firstTimeFullTimeFreshmenHeadcount] and labeled "share of ALL FT
 * freshmen" -- never stored.
 */
data class CollegeMeritAid(
  override val id: CollegeMeritAidId,
  val collegeId: CollegeId,
  val sourceYear: Int,
  /** H.201: TOTAL degree-seeking first-time full-time freshmen enrolled. */
  val firstTimeFullTimeFreshmenHeadcount: Int?,
  /** H.2A01: freshmen with NO financial need awarded institutional merit aid. */
  val noNeedMeritRecipientsHeadcount: Int?,
  /** H.2A02: school-reported average award, whole US dollars. */
  val noNeedMeritAverageUsd: Int?,
  /** The school's own published CDS document. */
  val sourceUrl: String,
  /** The corpus's archived copy. */
  val archiveUrl: String?,
  override val createdAt: Instant,
  override val updatedAt: Instant,
) : Identifiable<CollegeMeritAidId>,
  Created,
  Updated
