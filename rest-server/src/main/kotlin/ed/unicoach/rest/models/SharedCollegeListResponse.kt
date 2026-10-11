package ed.unicoach.rest.models

import ed.unicoach.coaching.collegelist.SharedCollegeListEntry
import java.time.Instant
import java.util.UUID

/** `GET /api/v1/students/{studentId}/college-list` (RFC 250): a linked adult's view of a student's list. */
data class SharedCollegeListResponse(
  val entries: List<PublicSharedCollegeListEntry>,
)

/**
 * One school on the student's list as a linked adult sees it. No `reasons`
 * and no supporting observations — the student's own words from their coach
 * conversations — and no entry id or version, which only serve writes.
 */
data class PublicSharedCollegeListEntry(
  val collegeId: UUID,
  val collegeName: String,
  val status: String,
  /** This school's own living plan, or null: "no override, use the usual plan" (RFC 152). */
  val livingPlan: String?,
  val updatedAt: Instant,
)

fun SharedCollegeListEntry.toResponse(): PublicSharedCollegeListEntry =
  PublicSharedCollegeListEntry(
    collegeId = collegeId.value,
    collegeName = collegeName,
    status = status.value,
    livingPlan = livingPlan?.value,
    updatedAt = updatedAt,
  )
