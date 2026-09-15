package ed.unicoach.db.dao

import ed.unicoach.db.models.ExternalDocumentId
import ed.unicoach.db.models.NewExternalDocument
import java.sql.PreparedStatement
import java.util.UUID

/**
 * Write layer over `external_documents` (RFC 213): the one home for a url we
 * cite and for whether it still resolves.
 *
 * Keyed by the URL, because a url IS the identity of a published document and
 * one document is cited by many rows and by many KINDS of row. Measured in the
 * committed CDS seed: 446 distinct (college, year) pairs resolve to 441 distinct
 * urls -- one University of Washington filing backs three campuses -- and the
 * college-keyed `source_documents` stores that file once per college.
 *
 * TWO ENTRY POINTS, AND THE DIFFERENCE IS WHO KNOWS THE LINK-ROT STATE:
 *
 *  * [upsert] is for a caller that just ASKED -- a sweep. It overwrites the
 *    status pair, for [SourceDocumentsDao.upsert]'s reason: the pair is a fact
 *    about the url this statement is writing, and leaving a previous run's
 *    verdict in place would attach yesterday's answer to today's url.
 *  * [idOf] is for a caller that is merely CITING and knows nothing about
 *    whether the page is still served. It must not overwrite a swept status with
 *    "we never asked", which is precisely the check-that-never-ran-looking-like-
 *    one-that-passed failure RFC 182 names.
 */
object ExternalDocumentsDao {
  fun upsert(
    session: SqlSession,
    input: NewExternalDocument,
  ): Result<ExternalDocumentId> =
    session
      .mutateReturning(
        """
        INSERT INTO external_documents (url, url_status, url_checked_on)
        VALUES (?, ?, ?)
        ON CONFLICT (url) DO UPDATE
          SET url_status = EXCLUDED.url_status,
              url_checked_on = EXCLUDED.url_checked_on
        RETURNING id
        """.trimIndent(),
        bind = { stmt: PreparedStatement ->
          stmt.setString(1, input.url)
          stmt.setString(2, input.urlStatus.value)
          stmt.setDateOrNull(3, input.urlCheckedOn)
        },
        map = ::mapId,
        mapError = { e ->
          mapReferenceWriteError(e, "[external_documents] row for url [${input.url}] could not be written")
        },
      )

  /**
   * The id of the document at [url], creating the row where we do not hold one
   * and touching NOTHING else about it.
   *
   * The conflict arm assigns the url to itself. That is not a no-op statement
   * dressed up: `ON CONFLICT ... DO NOTHING` returns no row, so a caller would
   * need a second SELECT and would race another writer between the two. This
   * costs one statement and cannot lose that race. What it does cost is the
   * `updated_at` trigger firing on a row whose content did not change, which is
   * honest enough -- a citation of the document IS a touch of it -- and is
   * cheaper than the race.
   */
  fun idOf(
    session: SqlSession,
    url: String,
  ): Result<ExternalDocumentId> =
    session
      .mutateReturning(
        """
        INSERT INTO external_documents (url)
        VALUES (?)
        ON CONFLICT (url) DO UPDATE SET url = EXCLUDED.url
        RETURNING id
        """.trimIndent(),
        bind = { stmt: PreparedStatement -> stmt.setString(1, url) },
        map = ::mapId,
        mapError = { e ->
          mapReferenceWriteError(e, "[external_documents] row for url [$url] could not be cited")
        },
      )

  /**
   * THE CITATION PAIR A CITED CORPUS WRITES: the page a row was read from, and
   * the archived address it was read THROUGH where the publisher's own page
   * opens for no client we have. One call, both ids, in the caller's
   * transaction.
   *
   * IT EXISTS BECAUSE TWO CORPORA NOW ASK THE SAME QUESTION -- RFC 213's own
   * rule for `link_status`, applied to code instead of to a vocabulary:
   * `admission_test_state_programmes` (RFC 208) and `fafsa_filing_deadlines`
   * (RFC 215) had the identical twelve lines, comment included, and a third
   * cited corpus would have copied whichever it found first.
   *
   * IT GOES THROUGH [idOf] AND NEVER [upsert], and that is the whole policy
   * this function carries: a caller CITING a page knows its address and knows
   * NOTHING about whether the page is still served, so it must not overwrite a
   * swept status with "we never asked" -- the check-that-never-ran looking like
   * one that passed, which RFC 182 forbids.
   *
   * Both statements run in the caller's transaction, so a refused fact row
   * takes its documents with it. A caller in autocommit gets two committed
   * documents and no fact, which is untidy rather than wrong: a document row is
   * a url and its link state, true whoever cites it.
   */
  fun citationIds(
    session: SqlSession,
    sourceUrl: String,
    archiveUrl: String?,
  ): Result<Pair<ExternalDocumentId, ExternalDocumentId?>> =
    idOf(session, sourceUrl).mapCatching { sourceId ->
      sourceId to archiveUrl?.let { idOf(session, it).getOrThrow() }
    }

  private fun mapId(rs: java.sql.ResultSet): ExternalDocumentId = ExternalDocumentId(UUID.fromString(rs.getString("id")))
}
