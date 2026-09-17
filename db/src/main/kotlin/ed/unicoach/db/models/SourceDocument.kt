package ed.unicoach.db.models

import ed.unicoach.common.util.AcademicYear
import java.time.LocalDate
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
  /**
   * Whether [sourceUrl] was still served when we last looked (RFC 200),
   * recorded at INGEST and never asked at render.
   *
   * REQUIRED HERE, with no Kotlin default. [SourceUrlStatus.UNKNOWN] with no
   * [sourceUrlCheckedOn] is what a writer that ran no check says -- and in this
   * type it SAYS it rather than inheriting it, because the one thing this
   * column exists to stop is a claim about a school's file that nobody made.
   * The CDS loader states both from `db/seed/cds/link-status.csv`; every
   * other writer states UNKNOWN and null, in as many words.
   *
   * The COLUMN does carry `DEFAULT 'unknown'`
   * (`db/schema/record-source-url-status.sql`), and the two do not disagree:
   * `unknown` with no date is the honest reading of a row written by a path
   * that ran no check, so an inherited one claims nothing. The stricter rule is
   * this type's, applied to the Kotlin writers, and it is a rule about SAYING
   * the verdict -- not a claim that the storage layer forbids omitting it.
   */
  val sourceUrlStatus: SourceUrlStatus,
  /**
   * The day [sourceUrlStatus] was established, or null where we never asked.
   *
   * An UNKNOWN with a date is a check that RAN and got no usable answer, which
   * must never look like one that passed (RFC 182); the two are told apart
   * here, which is why the enum needs no fourth member.
   */
  val sourceUrlCheckedOn: LocalDate?,
  /**
   * Whether [archiveUrl] was still served when we last looked (RFC 228). Read
   * from the same swept sidecar as the page -- `db/seed/cds/link-status.csv`
   * now covers both columns -- and stated rather than defaulted, for exactly
   * the reason [sourceUrlStatus] is: a verdict nobody reached must never look
   * like one that passed (RFC 182).
   */
  val archiveUrlStatus: SourceUrlStatus = SourceUrlStatus.UNKNOWN,
  /**
   * The day [archiveUrlStatus] was established, or null where we never asked.
   * An archive with a date is evidence; an archive with none is not stored
   * under RFC 220's strict table.
   */
  val archiveUrlCheckedOn: LocalDate? = null,
) {
  init {
    // The Kotlin half of the old `source_documents_checked_when_known`: you
    // cannot claim to know without saying when you looked, for either url.
    // Held here as well as in the schema so an unsatisfiable row cannot be
    // built at all -- the DB would refuse it, but hundreds of rows later and
    // with no writer named.
    require(!sourceUrlStatus.requiresCheckedOn || sourceUrlCheckedOn != null) {
      "a [${sourceUrlStatus.value}] verdict must say when it was reached: " +
        "college=[${collegeId.value}] year=[${academicYear.label}] source_url=[$sourceUrl]"
    }
    require(!archiveUrlStatus.requiresCheckedOn || archiveUrlCheckedOn != null) {
      "a [${archiveUrlStatus.value}] archive verdict must say when it was reached: " +
        "college=[${collegeId.value}] year=[${academicYear.label}] archive_url=[$archiveUrl]"
    }
  }
}
