package ed.unicoach.db.dao

import ed.unicoach.db.models.NewSourceDocument
import ed.unicoach.db.models.SourceDocumentId
import java.sql.PreparedStatement
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
   */
  fun upsert(
    session: SqlSession,
    input: NewSourceDocument,
  ): Result<SourceDocumentId> =
    session
      .mutateReturning(
        """
        INSERT INTO source_documents (college_id, source, academic_year, source_url, archive_url)
        VALUES (?, ?, ?, ?, ?)
        ON CONFLICT (college_id, source, academic_year) DO UPDATE
          SET source_url = EXCLUDED.source_url,
              archive_url = EXCLUDED.archive_url
        RETURNING id
        """.trimIndent(),
        bind = { stmt: PreparedStatement ->
          stmt.setObject(1, input.collegeId.value)
          stmt.setString(2, input.source.value)
          stmt.setInt(3, input.academicYear.firstCalendarYear)
          stmt.setString(4, input.sourceUrl)
          stmt.setStringOrNull(5, input.archiveUrl)
        },
        map = { rs -> SourceDocumentId(UUID.fromString(rs.getString("id"))) },
        // The write-path mapping, so a refused document is a NAMED constraint
        // violation (an academic year outside the domain, an empty url, a
        // college that is not there) rather than "general database error" --
        // the loader wraps this in its own row-located exception, and a bare
        // sentence would leave the operator with no constraint to look up.
        mapError = ::mapCollegeWriteError,
      )
}
