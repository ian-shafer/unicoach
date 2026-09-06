package ed.unicoach.db.models

import ed.unicoach.common.util.AcademicYear
import java.util.UUID

/** Surface id for a `source_documents` row, and the reference every CDS fact carries. */
@JvmInline
value class SourceDocumentId(
  val value: UUID,
) : Id {
  override val asString get() = value.toString()
}

/**
 * Input for upserting one `source_documents` row on its natural key
 * `(collegeId, source, academicYear)` (RFC 170, D13): the school's own
 * publication for one publisher and one academic year, plus our mirror when
 * one exists. Carries no `id` (DB-generated) and no timestamps (DB-managed).
 *
 * A url is a property of a DOCUMENT, not of a fact. The three CDS tables each
 * carried their own copy of the same filing's two urls, so the copies could
 * disagree and a corrected url had to be written in three places. Every fact
 * table cites this row instead.
 *
 * There is no read-shaped twin: nothing fetches a document on its own, and the
 * reads that need the urls JOIN this table for them. One arrives with the
 * first caller that needs it.
 *
 * [archiveUrl] is null when no mirror exists -- never an empty string, which
 * is a url-shaped absence and would render as a link to nowhere.
 */
data class NewSourceDocument(
  val collegeId: CollegeId,
  val source: MoneySource,
  val academicYear: AcademicYear,
  val sourceUrl: String,
  val archiveUrl: String?,
)
