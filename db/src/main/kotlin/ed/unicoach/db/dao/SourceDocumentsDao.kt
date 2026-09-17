package ed.unicoach.db.dao

import ed.unicoach.db.models.NewExternalDocument
import ed.unicoach.db.models.NewSourceDocument
import ed.unicoach.db.models.SourceDocumentId
import ed.unicoach.db.models.SourceUrlStatus
import java.sql.PreparedStatement
import java.time.LocalDate
import java.util.UUID

/**
 * Data-access layer over `source_documents`, the one home of a published
 * document's urls (RFC 170, D13).
 *
 * Stateless `object`, one [SqlSession] per call, transaction boundaries owned
 * by the caller -- the [CdsAdmissionsDao] shape it serves. The table is
 * unversioned reference data: its history is the `college_index_build`
 * provenance row.
 *
 * [upsert] is the shape every fact loader needs, because a fact row
 * cannot be written before the document it cites exists. It is one statement,
 * not a read-then-write: two concurrent runs racing on the same natural key
 * would otherwise both read "absent" and one would fail the unique index.
 *
 * It is also the ONLY thing here. Nothing reads a document by id or counts the
 * table -- the CDS reads join `source_documents` for the two urls
 * ([CdsAdmissionsDao] and [AidPolicyDao] both do) and never fetch a document on
 * its own. A `find`/`rowCount` pair written "for later" would be a surface with
 * no caller to keep it honest; it comes back with the slice that needs it.
 */
object SourceDocumentsDao {
  /**
   * Upserts the document for `(college, source, academicYear)` and returns its
   * id, whether it was already there or not.
   *
   * The two urls are OVERWRITTEN on conflict: the corpus is re-fetched, a
   * school republishes a corrected pdf, and the newest run is the truth. That
   * is the whole point of the document being one row -- the correction lands
   * once and every fact citing it follows.
   *
   * The RFC 200 link-rot pair is overwritten with them, and for the same
   * reason: a re-run that found the school's file back is the newest answer,
   * and the pair is a fact ABOUT the url this statement is writing. Leaving a
   * previous run's verdict in place would attach yesterday's answer to today's
   * url -- the one way the column could state a check that never happened.
   */
  fun upsert(
    session: SqlSession,
    input: NewSourceDocument,
  ): Result<SourceDocumentId> =
    // runCatching so an unevidenced citation is a RESULT failure rather than a
    // thrown exception: the loader's row-location wrapper turns Result failures
    // into its own located LoadException, and a thrown IllegalStateException
    // would bypass that contract.
    runCatching {
      // The two urls now live in external_documents (RFC 228). The page is
      // upserted -- the loader has just ASKED, via the swept sidecar, so a
      // re-run that found the school's file back is the newest answer. The
      // mirror is upserted too, with its OWN swept evidence from the same file.
      // Both resolutions happen inside the caller's transaction, so a refused
      // fact row takes its documents with it.
      ExternalDocumentsDao
        .upsert(
          session,
          NewExternalDocument(
            input.sourceUrl,
            input.sourceUrlStatus,
            checkedDate(input.sourceUrlStatus, input.sourceUrlCheckedOn, input.sourceUrl),
          ),
        ).mapCatching { pageId ->
          val archiveId =
            input.archiveUrl?.let {
              ExternalDocumentsDao
                .upsert(
                  session,
                  NewExternalDocument(it, input.archiveUrlStatus, checkedDate(input.archiveUrlStatus, input.archiveUrlCheckedOn, it)),
                ).getOrThrow()
            }
          session
            .mutateReturning(
              """
              INSERT INTO source_documents (
                college_id, source, academic_year, external_document_id, archive_external_document_id
              )
              VALUES (?, ?, ?, ?, ?)
              ON CONFLICT (college_id, source, academic_year) DO UPDATE
                SET external_document_id = EXCLUDED.external_document_id,
                    archive_external_document_id = EXCLUDED.archive_external_document_id
              RETURNING id
              """.trimIndent(),
              bind = { stmt: PreparedStatement ->
                stmt.setObject(1, input.collegeId.value)
                stmt.setString(2, input.source.value)
                stmt.setInt(3, input.academicYear.firstCalendarYear)
                stmt.setObject(4, pageId.value)
                stmt.setObject(5, archiveId?.value)
              },
              map = { rs -> SourceDocumentId(UUID.fromString(rs.getString("id"))) },
              // The write-path mapping, so a refused document is a NAMED
              // constraint violation (an academic year outside the domain, a
              // college that is not there) rather than "general database error" --
              // the loader wraps this in its own row-located exception, and a
              // bare sentence would leave the operator with no constraint to look
              // up.
              mapError = ::mapCollegeWriteError,
            ).getOrThrow()
        }.getOrThrow()
    }

  /**
   * The date a verdict carries, refusing the one case RFC 220 made
   * unrepresentable: UNKNOWN with no date is "we never asked", and there is no
   * such row in external_documents any more. [NewSourceDocument]'s own init
   * already refuses a FOUND or NOT_FOUND verdict with no date, so the only way
   * a null date reaches here is a "we never asked" the caller tried to store;
   * that is a defect surfaced, never a date backfilled.
   */
  private fun checkedDate(
    status: SourceUrlStatus,
    checkedOn: LocalDate?,
    url: String,
  ): LocalDate =
    checkedOn
      ?: throw IllegalStateException(
        "an external document verdict [${status.value}] with no date cannot be stored: " +
          "external_documents.url_checked_on is NOT NULL (RFC 220), url=[$url]",
      )
}
