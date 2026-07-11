package ed.unicoach.db.dao

import ed.unicoach.db.models.ArchiveScope
import ed.unicoach.db.models.Convo
import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.ConvoName
import ed.unicoach.db.models.ConvoRequest
import ed.unicoach.db.models.ConvoRequestId
import ed.unicoach.db.models.ConvoRequestKind
import ed.unicoach.db.models.ConvoTurn
import ed.unicoach.db.models.ConvoTurnId
import ed.unicoach.db.models.ConvoWithActivity
import ed.unicoach.db.models.LlmCall
import ed.unicoach.db.models.LlmRequestId
import ed.unicoach.db.models.NewConvo
import ed.unicoach.db.models.NewConvoRequest
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.SystemPromptId
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

/**
 * Data-access layer over the coaching-conversation tables (RFC 32): `convos`
 * (the mutable entity) and `convo_requests` (the coaching-extension log). Since
 * RFC 106 the request I/O and the response live in the generic LLM call log
 * (`llm_requests` / `llm_responses` / `llm_responses_raw`); a convo's response
 * is reached via `convo_requests.llm_request_id -> llm_responses` (1:1), so the
 * turn reads join the call log and there is no `appendResponse` here — the
 * response is written by `LlmCallLog`.
 *
 * Stateless `object`, one [SqlSession] per call, transaction boundaries owned by
 * the caller. Mutating methods carry no optimistic-concurrency guard because
 * `convos` has no `version` column (RFC 32 disabled versioning).
 */
object ConvosDao :
  SoftDeleteFindable<Convo, ConvoId>,
  Creatable<NewConvo, Convo>,
  Deletable<Convo, ConvoId> {
  // ---------------------------------------------------------------------------
  // Row mappers
  // ---------------------------------------------------------------------------

  private fun mapConvo(rs: ResultSet): Convo =
    Convo(
      id = ConvoId(UUID.fromString(rs.getString("id"))),
      studentId = StudentId(UUID.fromString(rs.getString("student_id"))),
      name = parseConvoName(rs.getString("name")),
      createdAt = rs.getInstant("created_at"),
      updatedAt = rs.getInstant("updated_at"),
      deletedAt = rs.getInstantOrNull("deleted_at"),
      archivedAt = rs.getInstantOrNull("archived_at"),
    )

  /**
   * Reconstructs the persisted name. The DB checks already guarantee a valid,
   * trimmed, bounded name is stored, so an `Invalid` result here indicates row
   * corruption, not user input — surfaced as a [DatabaseException], never a
   * user-facing validation failure.
   */
  private fun parseConvoName(value: String): ConvoName =
    when (val result = ConvoName.create(value)) {
      is ed.unicoach.common.models.ValidationResult.Valid -> {
        result.value
      }

      is ed.unicoach.common.models.ValidationResult.Invalid -> {
        throw SQLException(
          "Persisted convo name does not form a valid ConvoName " +
            "(${result.error}): \"$value\"",
        )
      }
    }

  private fun mapRequest(
    rs: ResultSet,
    columnPrefix: String = "",
  ): ConvoRequest =
    ConvoRequest(
      id = ConvoRequestId(rs.getLong("${columnPrefix}id")),
      convoId = ConvoId(UUID.fromString(rs.getString("${columnPrefix}convo_id"))),
      createdAt = rs.getInstant("${columnPrefix}created_at"),
      systemPromptId = SystemPromptId(UUID.fromString(rs.getString("${columnPrefix}system_prompt_id"))),
      llmRequestId = LlmRequestId(rs.getLong("${columnPrefix}llm_request_id")),
      kind = parseRequestKind(rs.getString("${columnPrefix}kind"), rs.getLong("${columnPrefix}id")),
      turnId = ConvoTurnId(rs.getLong("${columnPrefix}turn_id")),
    )

  /**
   * Reconstructs the persisted request kind for the `convo_requests` row [rowId].
   * The `convo_requests_kind_valid_check` CHECK guarantees a valid value is
   * stored, so an unknown value here indicates row corruption, not user input —
   * surfaced as a [SQLException] naming the offending row and value.
   */
  private fun parseRequestKind(
    value: String,
    rowId: Long,
  ): ConvoRequestKind =
    ConvoRequestKind.fromValue(value)
      ?: throw SQLException("Persisted convo_requests.kind is not a valid value for row id=[$rowId]: [$value]")

  // ---------------------------------------------------------------------------
  // ArchiveScope predicate (fixed SQL fragment; no caller data)
  // ---------------------------------------------------------------------------

  private fun archivePredicate(
    scope: ArchiveScope,
    column: String,
  ): String =
    when (scope) {
      ArchiveScope.UNARCHIVED -> "$column IS NULL"
      ArchiveScope.ARCHIVED -> "$column IS NOT NULL"
      ArchiveScope.ALL -> "TRUE"
    }

  // ---------------------------------------------------------------------------
  // Convo entity
  // ---------------------------------------------------------------------------

  override fun findById(
    session: SqlSession,
    id: ConvoId,
    scope: SoftDeleteScope,
  ): Result<Convo> =
    session
      .queryOne(
        "SELECT * FROM convos WHERE id = ?",
        bind = { it.setObject(1, id.value) },
        map = ::mapConvo,
      ).mapCatching { convo ->
        if (!scopeAdmits(scope, convo.deletedAt)) throw NotFoundException()
        convo
      }

  private fun scopeAdmits(
    scope: SoftDeleteScope,
    deletedAt: java.time.Instant?,
  ): Boolean =
    when (scope) {
      SoftDeleteScope.ACTIVE -> deletedAt == null
      SoftDeleteScope.DELETED -> deletedAt != null
      SoftDeleteScope.ALL -> true
    }

  fun listByStudent(
    session: SqlSession,
    studentId: StudentId,
    scope: SoftDeleteScope = SoftDeleteScope.ACTIVE,
  ): Result<List<Convo>> {
    val sql =
      """
      SELECT * FROM convos
      WHERE student_id = ? AND ${scope.predicate("deleted_at")}
      ORDER BY created_at, id
      """.trimIndent()
    return session.queryList(
      sql,
      bind = { it.setObject(1, studentId.value) },
      map = ::mapConvo,
    )
  }

  override fun create(
    session: SqlSession,
    input: NewConvo,
  ): Result<Convo> =
    session.insertReturning(
      table = "convos",
      columns =
        linkedMapOf<String, Bind>(
          "student_id" to { stmt, i -> stmt.setObject(i, input.studentId.value) },
          "name" to { stmt, i -> stmt.setString(i, input.name.value) },
        ),
      map = ::mapConvo,
      mapError = ::mapConvoError,
    )

  /**
   * Renames an active convo. The `deleted_at IS NULL` active-row guard is a
   * non-id WHERE predicate that the generic `updateColumnsReturning` (id-only
   * WHERE in non-OCC mode) cannot express without leaking renames onto
   * soft-deleted rows, so this write stays hand-written via [mutateReturning].
   */
  fun rename(
    session: SqlSession,
    id: ConvoId,
    name: ConvoName,
  ): Result<Convo> {
    val sql =
      """
      UPDATE convos
      SET name = ?
      WHERE id = ? AND deleted_at IS NULL
      RETURNING *
      """.trimIndent()
    return session.mutateReturning(
      sql,
      bind = { stmt ->
        stmt.setString(1, name.value)
        stmt.setObject(2, id.value)
      },
      map = ::mapConvo,
      mapError = ::mapConvoError,
    )
  }

  override fun delete(
    session: SqlSession,
    id: ConvoId,
  ): Result<Convo> =
    session.softDeleteReturning(
      table = "convos",
      id = id.value,
      currentVersion = null,
      deleted = true,
      map = ::mapConvo,
    )

  override fun undelete(
    session: SqlSession,
    id: ConvoId,
  ): Result<Convo> =
    session.softDeleteReturning(
      table = "convos",
      id = id.value,
      currentVersion = null,
      deleted = false,
      map = ::mapConvo,
    )

  /**
   * Archives a convo: idempotent toggle that keeps the original `archived_at`
   * on re-archive (`COALESCE`). Rejects soft-deleted rows ([NotFoundException]
   * when no active row matches). Suppresses the `update_timestamp` trigger via
   * the bypass GUC so `updated_at` does not advance (the contract pins
   * "updatedAt advances on rename only"). Because `SET LOCAL` persists for the
   * remainder of the transaction, a caller combining rename and archive in one
   * transaction MUST rename first.
   */
  fun archive(
    session: SqlSession,
    id: ConvoId,
  ): Result<Convo> = setArchivedAt(session, id, archive = true)

  /**
   * Unarchives a convo: idempotent toggle clearing `archived_at` (also succeeds
   * on a never-archived row). Rejects soft-deleted rows. Suppresses the
   * `updated_at` trigger as [archive] does.
   */
  fun unarchive(
    session: SqlSession,
    id: ConvoId,
  ): Result<Convo> = setArchivedAt(session, id, archive = false)

  private fun setArchivedAt(
    session: SqlSession,
    id: ConvoId,
    archive: Boolean,
  ): Result<Convo> {
    // Precedent: UsersDao.updatePhysicalRecord. SET LOCAL holds for the rest
    // of the transaction, so a combined rename+archive must rename first.
    val bypass = session.execute("SET LOCAL unicoach.bypass_logical_timestamp = 'true'")
    if (bypass.isFailure) {
      return Result.failure(bypass.exceptionOrNull()!!)
    }
    val setClause = if (archive) "archived_at = COALESCE(archived_at, NOW())" else "archived_at = NULL"
    val sql =
      """
      UPDATE convos
      SET $setClause
      WHERE id = ? AND deleted_at IS NULL
      RETURNING *
      """.trimIndent()
    return session.mutateReturning(
      sql,
      bind = { it.setObject(1, id.value) },
      map = ::mapConvo,
      mapError = ::mapConvoError,
    )
  }

  /**
   * Lists a student's convos with each row's derived `lastActivityAt`
   * (`MAX(convo_requests.created_at)`, null with no turns). Filters by
   * [archive] and excludes soft-deleted rows per [scope]. One query: a LEFT
   * JOIN grouped by convo, ordered most-recent-activity first with a
   * deterministic tiebreak.
   */
  fun listByStudentWithActivity(
    session: SqlSession,
    studentId: StudentId,
    archive: ArchiveScope = ArchiveScope.UNARCHIVED,
    scope: SoftDeleteScope = SoftDeleteScope.ACTIVE,
    limit: Int? = null,
    offset: Int = 0,
  ): Result<List<ConvoWithActivity>> {
    // limit = null preserves the existing unbounded behaviour for coaching
    // callers; the admin student panel passes a bound. The LIMIT/OFFSET clause is
    // a fixed SQL fragment (no caller-supplied identifiers) with bound values.
    if (limit != null) require(limit > 0) { "limit must be positive, got $limit" }
    require(offset >= 0) { "offset must be non-negative, got $offset" }
    val pageClause = if (limit == null) "" else "LIMIT ? OFFSET ?"
    val sql =
      """
      SELECT c.*, MAX(r.created_at) AS last_activity_at
      FROM convos c
      LEFT JOIN convo_requests r ON r.convo_id = c.id
      WHERE c.student_id = ?
        AND ${scope.predicate("c.deleted_at")}
        AND ${archivePredicate(archive, "c.archived_at")}
      GROUP BY c.id
      ORDER BY MAX(r.created_at) DESC NULLS LAST, c.created_at DESC, c.id
      $pageClause
      """.trimIndent()
    return session.queryList(
      sql,
      bind = {
        it.setObject(1, studentId.value)
        if (limit != null) {
          it.setInt(2, limit)
          it.setInt(3, offset)
        }
      },
      map = ::mapConvoWithActivity,
    )
  }

  /**
   * Global, paginated convo list with each row's derived `lastActivityAt`, for
   * the admin `/convo` list page. Ordered `c.created_at DESC, c.id`. [scope]
   * filters `deleted_at`; all archive states are returned (admin sees archived
   * rows).
   */
  fun listWithActivity(
    session: SqlSession,
    scope: SoftDeleteScope,
    limit: Int,
    offset: Int,
  ): Result<List<ConvoWithActivity>> {
    require(limit > 0) { "limit must be positive, got $limit" }
    require(offset >= 0) { "offset must be non-negative, got $offset" }
    val sql =
      """
      SELECT c.*, MAX(r.created_at) AS last_activity_at
      FROM convos c
      LEFT JOIN convo_requests r ON r.convo_id = c.id
      WHERE ${scope.predicate("c.deleted_at")}
      GROUP BY c.id
      ORDER BY c.created_at DESC, c.id
      LIMIT ? OFFSET ?
      """.trimIndent()
    return session.queryList(
      sql,
      bind = {
        it.setInt(1, limit)
        it.setInt(2, offset)
      },
      map = ::mapConvoWithActivity,
    )
  }

  /**
   * Loads one convo with its derived `lastActivityAt`, honouring [scope].
   * [NotFoundException] when no row matches.
   */
  fun findByIdWithActivity(
    session: SqlSession,
    id: ConvoId,
    scope: SoftDeleteScope = SoftDeleteScope.ACTIVE,
  ): Result<ConvoWithActivity> {
    val sql =
      """
      SELECT c.*, MAX(r.created_at) AS last_activity_at
      FROM convos c
      LEFT JOIN convo_requests r ON r.convo_id = c.id
      WHERE c.id = ? AND ${scope.predicate("c.deleted_at")}
      GROUP BY c.id
      """.trimIndent()
    return session.queryOne(
      sql,
      bind = { it.setObject(1, id.value) },
      map = ::mapConvoWithActivity,
    )
  }

  private fun mapConvoWithActivity(rs: ResultSet): ConvoWithActivity =
    ConvoWithActivity(
      convo = mapConvo(rs),
      lastActivityAt = rs.getInstantOrNull("last_activity_at"),
    )

  // ---------------------------------------------------------------------------
  // Logs — write (two transaction boundaries)
  // ---------------------------------------------------------------------------

  /**
   * Mints the next `turn_id` for a new logical user turn (one read of
   * `convo_turn_id_seq`). The chat loop reads it once when the user opener is
   * written and threads the same value onto every `tool_result` continuation row
   * of the excursion, so all rows of one turn share one `turn_id`.
   */
  fun nextTurnId(session: SqlSession): Result<ConvoTurnId> =
    session.queryOne(
      "SELECT nextval('convo_turn_id_seq') AS turn_id",
      bind = {},
      map = { rs -> ConvoTurnId(rs.getLong("turn_id")) },
    )

  /**
   * Appends one `convo_requests` coaching-extension row: the coaching columns
   * plus [NewConvoRequest.llmRequestId], the FK into the generic `llm_requests`
   * call log the caller obtained from `LlmCallLog`. The request I/O envelope is
   * written by `LlmCallLog`, not here.
   */
  fun appendRequest(
    session: SqlSession,
    request: NewConvoRequest,
  ): Result<ConvoRequest> {
    val sql =
      """
      INSERT INTO convo_requests (
        convo_id, system_prompt_id, llm_request_id, kind, turn_id
      )
      VALUES (?, ?, ?, ?, ?)
      RETURNING *
      """.trimIndent()
    return session.mutateReturning(
      sql,
      bind = { stmt ->
        stmt.setObject(1, request.convoId.value)
        stmt.setObject(2, request.systemPromptId.value)
        stmt.setLong(3, request.llmRequestId.value)
        stmt.setString(4, request.kind.value)
        stmt.setLong(5, request.turnId.value)
      },
      map = { mapRequest(it) },
      mapError = ::mapConvoError,
    )
  }

  // ---------------------------------------------------------------------------
  // Logs — read
  // ---------------------------------------------------------------------------

  /**
   * The shared turn projection: the `convo_requests` coaching columns aliased
   * `req_*`, plus the joined LLM call (`llm_requests` → `llm_responses` →
   * `llm_responses_raw`) columns aliased via [LlmCallsDao.joinedCallColumns], so
   * [mapTurn] reads the request and the whole `LlmCall` from one row. The
   * response now lives in the generic call log (RFC 106), reached through
   * `convo_requests.llm_request_id`. Reused by the per-convo and global turn reads.
   */
  private val turnSelect =
    """
    SELECT
      r.id   AS req_id,
      r.convo_id AS req_convo_id,
      r.created_at AS req_created_at,
      r.system_prompt_id AS req_system_prompt_id,
      r.llm_request_id AS req_llm_request_id,
      r.kind AS req_kind,
      r.turn_id AS req_turn_id,
      ${LlmCallsDao.joinedCallColumns("lreq", "lresp", "lraw")}
    FROM convo_requests r
    JOIN convos c ON c.id = r.convo_id
    JOIN llm_requests lreq ON lreq.id = r.llm_request_id
    LEFT JOIN llm_responses lresp ON lresp.request_id = lreq.id
    LEFT JOIN llm_responses_raw lraw ON lraw.response_id = lresp.id
    """.trimIndent()

  fun listTurns(
    session: SqlSession,
    convoId: ConvoId,
    scope: SoftDeleteScope = SoftDeleteScope.ACTIVE,
    limit: Int? = null,
    offset: Int = 0,
  ): Result<List<ConvoTurn>> {
    // limit = null preserves the existing unbounded behaviour for callers that
    // need every turn (e.g. the coaching transcript); the admin convo-detail
    // panel passes a bound. The LIMIT/OFFSET clause is a fixed SQL fragment (no
    // caller-supplied identifiers) with bound values.
    if (limit != null) require(limit > 0) { "limit must be positive, got $limit" }
    require(offset >= 0) { "offset must be non-negative, got $offset" }
    val pageClause = if (limit == null) "" else "LIMIT ? OFFSET ?"
    val sql =
      """
      $turnSelect
      WHERE r.convo_id = ? AND ${scope.predicate("c.deleted_at")}
      ORDER BY r.created_at, r.id
      $pageClause
      """.trimIndent()
    return session.queryList(
      sql,
      bind = {
        it.setObject(1, convoId.value)
        if (limit != null) {
          it.setInt(2, limit)
          it.setInt(3, offset)
        }
      },
      map = ::mapTurn,
    )
  }

  /**
   * Global, paginated turn firehose for the admin `/convo-request` list page. One
   * row per request, LEFT JOINed to its 1:1 response. Ordered `r.id DESC` (the
   * BIGINT IDENTITY PK is monotonic with insertion, so most-recent first comes
   * off the PK index with no sort over a non-indexed column). [scope] filters the
   * owning convo's `deleted_at`.
   */
  fun listTurns(
    session: SqlSession,
    scope: SoftDeleteScope,
    limit: Int,
    offset: Int,
  ): Result<List<ConvoTurn>> {
    require(limit > 0) { "limit must be positive, got $limit" }
    require(offset >= 0) { "offset must be non-negative, got $offset" }
    val sql =
      """
      $turnSelect
      WHERE ${scope.predicate("c.deleted_at")}
      ORDER BY r.id DESC
      LIMIT ? OFFSET ?
      """.trimIndent()
    return session.queryList(
      sql,
      bind = {
        it.setInt(1, limit)
        it.setInt(2, offset)
      },
      map = ::mapTurn,
    )
  }

  /**
   * One turn by request id, for the admin `/convo-request/{id}` detail page: the
   * request plus its paired response (null when none). [NotFoundException] when no
   * request matches, or when the owning convo is excluded by [scope].
   */
  fun findTurnByRequestId(
    session: SqlSession,
    requestId: ConvoRequestId,
    scope: SoftDeleteScope,
  ): Result<ConvoTurn> {
    val sql =
      """
      $turnSelect
      WHERE r.id = ? AND ${scope.predicate("c.deleted_at")}
      """.trimIndent()
    return session.queryOne(
      sql,
      bind = { it.setLong(1, requestId.value) },
      map = ::mapTurn,
    )
  }

  private fun mapTurn(rs: ResultSet): ConvoTurn {
    val request =
      ConvoRequest(
        id = ConvoRequestId(rs.getLong("req_id")),
        convoId = ConvoId(UUID.fromString(rs.getString("req_convo_id"))),
        createdAt = rs.getInstant("req_created_at"),
        systemPromptId = SystemPromptId(UUID.fromString(rs.getString("req_system_prompt_id"))),
        llmRequestId = LlmRequestId(rs.getLong("req_llm_request_id")),
        kind = parseRequestKind(rs.getString("req_kind"), rs.getLong("req_id")),
        turnId = ConvoTurnId(rs.getLong("req_turn_id")),
      )
    // The request always joins a call (llm_request_id is NOT NULL); response/raw
    // are null when their LEFT JOIN found no row.
    return ConvoTurn(request, LlmCallsDao.mapCallColumns(rs))
  }

  /**
   * The latest `convo_requests.id` for [convoId], or null when the conversation
   * has no requests yet. Backs the admin extraction trigger's `throughRequestId`
   * (RFC 100): the same window boundary the automatic per-turn enqueue passes.
   * Does not filter on the owning convo's `deleted_at` — the caller has already
   * resolved the convo.
   */
  fun findLatestRequestIdForConvo(
    session: SqlSession,
    convoId: ConvoId,
  ): Result<ConvoRequestId?> =
    session.queryOne(
      "SELECT MAX(id) AS max_id FROM convo_requests WHERE convo_id = ?",
      bind = { it.setObject(1, convoId.value) },
      map = { rs ->
        rs.getLong("max_id").takeUnless { rs.wasNull() }?.let(::ConvoRequestId)
      },
    )

  // ---------------------------------------------------------------------------
  // Error mapping
  // ---------------------------------------------------------------------------

  /**
   * SQLSTATE discrimination for the write paths. The 23503 branch resolves a
   * specific message from the violated FK constraint name in [e].message;
   * 23505 and 23514 map to the generic [ConstraintViolationException]. All other
   * failures route through the shared [mapDatabaseError].
   */
  private fun mapConvoError(e: SQLException): Exception =
    when (e.sqlState) {
      "23503" -> {
        val message = e.message ?: ""
        when {
          message.contains("convos_student_id_fkey") -> NotFoundException("Owning student not found")
          message.contains("convo_requests_convo_id_fkey") -> NotFoundException("Convo not found")
          message.contains("convo_requests_system_prompt_id_fkey") -> NotFoundException("System prompt not found")
          message.contains("convo_requests_llm_request_id_fkey") -> NotFoundException("LLM request not found")
          else -> NotFoundException()
        }
      }

      "23505", "23514" -> {
        ConstraintViolationException(e)
      }

      else -> {
        mapDatabaseError(e)
      }
    }
}
