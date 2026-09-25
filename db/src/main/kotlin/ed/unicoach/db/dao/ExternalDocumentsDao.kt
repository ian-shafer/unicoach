package ed.unicoach.db.dao

import ed.unicoach.db.models.ExternalDocument
import ed.unicoach.db.models.ExternalDocumentId
import ed.unicoach.db.models.NewExternalDocument
import ed.unicoach.db.models.SourceUrlStatus
import java.sql.PreparedStatement
import java.time.LocalDate
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
 *  * [idOf] is for a caller that is merely CITING. It states the evidence it has
 *    for a document we do not yet hold -- since RFC 220 there is no way to store
 *    one without evidence -- but it must not overwrite what a SWEEP established,
 *    which is precisely the check-that-never-ran-looking-like-one-that-passed
 *    failure RFC 182 names. So its evidence is written on insert and never on
 *    conflict.
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
          stmt.setObject(3, input.urlCheckedOn)
        },
        map = ::mapId,
        mapError = { e ->
          mapReferenceWriteError(e, "[external_documents] row for url [${input.url}] could not be written")
        },
      )

  /**
   * The id of the document [input] describes, creating the row where we do not
   * hold one and touching NOTHING else about it.
   *
   * IT STILL TAKES THE EVIDENCE (RFC 220). There is no such thing as storing a
   * url with no status and no date any more -- the columns are NOT NULL with no
   * default -- so a citing caller states what it knows, and what it knows is
   * used ONLY when the document is new to us. The conflict arm is unchanged, so
   * this call still cannot record "we never asked" over an answer a sweep got
   * (RFC 182); that is what makes it, and not [upsert], the right call for a
   * caller that is merely citing.
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
    input: NewExternalDocument,
  ): Result<ExternalDocumentId> =
    session
      .mutateReturning(
        """
        INSERT INTO external_documents (url, url_status, url_checked_on)
        VALUES (?, ?, ?)
        ON CONFLICT (url) DO UPDATE SET url = EXCLUDED.url
        RETURNING id
        """.trimIndent(),
        bind = { stmt: PreparedStatement ->
          stmt.setString(1, input.url)
          stmt.setString(2, input.urlStatus.value)
          stmt.setObject(3, input.urlCheckedOn)
        },
        map = ::mapId,
        mapError = { e ->
          mapReferenceWriteError(e, "[external_documents] row for url [${input.url}] could not be cited")
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
   *
   * [readOn] IS THE EVIDENCE, AND IT IS THE CITED ROW'S OWN READING DATE
   * (RFC 220). A citation cannot be stored with no evidence at all any more, so
   * a cited corpus states the one fact it genuinely has: a person opened these
   * pages on that day and wrote the row from what they served, which is FOUND on
   * that day. It is recorded only where the document is new to us -- [idOf]
   * leaves an existing row alone -- so a swept verdict is never overwritten by
   * it.
   */
  fun citationIds(
    session: SqlSession,
    sourceUrl: String,
    archiveUrl: String?,
    readOn: LocalDate,
  ): Result<Pair<ExternalDocumentId, ExternalDocumentId?>> =
    idOf(session, NewExternalDocument(sourceUrl, SourceUrlStatus.FOUND, readOn)).mapCatching { sourceId ->
      sourceId to archiveUrl?.let { idOf(session, NewExternalDocument(it, SourceUrlStatus.FOUND, readOn)).getOrThrow() }
    }

  /**
   * The document we hold for [url], or null where we hold none -- a READ, which
   * is the whole point: [idOf] would CREATE the row, and a caller asking "do we
   * have a citation for this rule yet" must not answer its own question by
   * writing one.
   *
   * `AutomaticAwardService` is the caller: ACT's composite-rounding rule is
   * ACT's published arithmetic and not ours, so the combination is refused
   * while this returns null, and the refusal names the missing page (RFC 220,
   * RFC 239 D-H guardrail 2).
   */
  fun findByUrl(
    session: SqlSession,
    url: String,
  ): Result<ExternalDocument?> =
    session
      .queryList(
        "SELECT * FROM external_documents WHERE url = ?",
        bind = { stmt: PreparedStatement -> stmt.setString(1, url) },
        map = ::mapDocument,
      ).map { it.firstOrNull() }

  private fun mapDocument(rs: java.sql.ResultSet): ExternalDocument {
    val url = rs.getString("url")
    val statusRaw = rs.getString("url_status")
    return ExternalDocument(
      id = ExternalDocumentId(UUID.fromString(rs.getString("id"))),
      url = url,
      urlStatus =
        SourceUrlStatus.fromValue(statusRaw)
          ?: throw corruptValue(statusRaw, "SourceUrlStatus", "external_documents.url_status (url [$url])"),
      urlCheckedOn = rs.getDate("url_checked_on").toLocalDate(),
    )
  }

  private fun mapId(rs: java.sql.ResultSet): ExternalDocumentId = ExternalDocumentId(UUID.fromString(rs.getString("id")))
}
