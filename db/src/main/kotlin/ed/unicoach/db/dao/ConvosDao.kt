package ed.unicoach.db.dao

import ed.unicoach.db.models.ArchiveScope
import ed.unicoach.db.models.Convo
import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.ConvoName
import ed.unicoach.db.models.ConvoRequest
import ed.unicoach.db.models.ConvoRequestId
import ed.unicoach.db.models.ConvoResponse
import ed.unicoach.db.models.ConvoResponseId
import ed.unicoach.db.models.ConvoResponseRaw
import ed.unicoach.db.models.ConvoTurn
import ed.unicoach.db.models.ConvoWithActivity
import ed.unicoach.db.models.NewConvo
import ed.unicoach.db.models.NewConvoRequest
import ed.unicoach.db.models.NewConvoResponse
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.SystemPromptId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

/**
 * Data-access layer over the coaching-conversation tables (RFC 32): `convos`,
 * `convo_requests`, `convo_responses`, `convo_responses_raw`.
 *
 * Stateless `object`, one [SqlSession] per call, transaction boundaries owned by
 * the caller. Mutating methods carry no optimistic-concurrency guard because
 * `convos` has no `version` column (RFC 32 disabled versioning).
 *
 * A coaching turn writes the request row and the response (+ raw) rows in two
 * separate caller transactions; [appendRequest] and [appendResponse] are
 * deliberately distinct methods with no combined "write whole turn" method.
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

  private fun mapRequest(rs: ResultSet): ConvoRequest =
    ConvoRequest(
      id = ConvoRequestId(rs.getLong("id")),
      convoId = ConvoId(UUID.fromString(rs.getString("convo_id"))),
      createdAt = rs.getInstant("created_at"),
      provider = rs.getString("provider"),
      modelRequested = rs.getString("model_requested"),
      systemPromptId = SystemPromptId(UUID.fromString(rs.getString("system_prompt_id"))),
      requestParams = rs.getJsonbOrNull("request_params") as JsonObject?,
      content = Json.parseToJsonElement(rs.getString("content")),
    )

  private fun mapResponse(
    rs: ResultSet,
    columnPrefix: String = "",
  ): ConvoResponse =
    ConvoResponse(
      id = ConvoResponseId(rs.getLong("${columnPrefix}id")),
      requestId = ConvoRequestId(rs.getLong("${columnPrefix}request_id")),
      convoId = ConvoId(UUID.fromString(rs.getString("${columnPrefix}convo_id"))),
      content = rs.getJsonbOrNull("${columnPrefix}content"),
      modelResolved = rs.getString("${columnPrefix}model_resolved"),
      stopReason = rs.getString("${columnPrefix}stop_reason"),
      inputTokens = rs.getInt("${columnPrefix}input_tokens").takeUnless { rs.wasNull() },
      outputTokens = rs.getInt("${columnPrefix}output_tokens").takeUnless { rs.wasNull() },
      cacheReadTokens = rs.getInt("${columnPrefix}cache_read_tokens").takeUnless { rs.wasNull() },
      cacheWriteTokens = rs.getInt("${columnPrefix}cache_write_tokens").takeUnless { rs.wasNull() },
      providerRequestId = rs.getString("${columnPrefix}provider_request_id"),
      latencyMs = rs.getInt("${columnPrefix}latency_ms").takeUnless { rs.wasNull() },
      createdAt = rs.getInstant("${columnPrefix}created_at"),
    )

  private fun mapResponseRaw(rs: ResultSet): ConvoResponseRaw =
    ConvoResponseRaw(
      responseId = ConvoResponseId(rs.getLong("response_id")),
      createdAt = rs.getInstant("created_at"),
      payload = Json.parseToJsonElement(rs.getString("payload")),
    )

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

  fun appendRequest(
    session: SqlSession,
    request: NewConvoRequest,
  ): Result<ConvoRequest> {
    val sql =
      """
      INSERT INTO convo_requests (
        convo_id, provider, model_requested, system_prompt_id, request_params, content
      )
      VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb)
      RETURNING *
      """.trimIndent()
    return session.mutateReturning(
      sql,
      bind = { stmt ->
        stmt.setObject(1, request.convoId.value)
        stmt.setString(2, request.provider)
        stmt.setString(3, request.modelRequested)
        stmt.setObject(4, request.systemPromptId.value)
        stmt.setJsonbOrNull(5, request.requestParams)
        stmt.setString(6, request.content.toString())
      },
      map = ::mapRequest,
      mapError = ::mapConvoError,
    )
  }

  /**
   * Inserts the response row and, when [rawPayload] is non-null, the verbatim raw
   * row keyed to it. Both inserts run inside the single transaction the caller
   * provides, so the response and its raw sibling are atomic together. A null
   * [rawPayload] is the transport-error turn (`stopReason = "error"`,
   * `content = null`): only the response row is written.
   */
  fun appendResponse(
    session: SqlSession,
    response: NewConvoResponse,
    rawPayload: JsonElement?,
  ): Result<ConvoResponse> {
    val sql =
      """
      INSERT INTO convo_responses (
        request_id, convo_id, content, model_resolved, stop_reason,
        input_tokens, output_tokens, cache_read_tokens, cache_write_tokens,
        provider_request_id, latency_ms
      )
      VALUES (?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?)
      RETURNING *
      """.trimIndent()
    val insertedResult =
      session.mutateReturning(
        sql,
        bind = { stmt ->
          stmt.setLong(1, response.requestId.value)
          stmt.setObject(2, response.convoId.value)
          stmt.setJsonbOrNull(3, response.content)
          stmt.setStringOrNull(4, response.modelResolved)
          stmt.setString(5, response.stopReason)
          stmt.setIntOrNull(6, response.inputTokens)
          stmt.setIntOrNull(7, response.outputTokens)
          stmt.setIntOrNull(8, response.cacheReadTokens)
          stmt.setIntOrNull(9, response.cacheWriteTokens)
          stmt.setStringOrNull(10, response.providerRequestId)
          stmt.setIntOrNull(11, response.latencyMs)
        },
        map = ::mapResponse,
        mapError = ::mapConvoError,
      )

    val inserted = insertedResult.getOrElse { return Result.failure(it) }

    if (rawPayload != null) {
      return try {
        insertRaw(session, inserted.id, rawPayload)
        Result.success(inserted)
      } catch (e: SQLException) {
        Result.failure(mapConvoError(e))
      } catch (e: Exception) {
        Result.failure(mapDatabaseError(e))
      }
    }

    return Result.success(inserted)
  }

  private fun insertRaw(
    session: SqlSession,
    responseId: ConvoResponseId,
    payload: JsonElement,
  ) {
    val sql = "INSERT INTO convo_responses_raw (response_id, payload) VALUES (?, ?::jsonb)"
    session.prepareStatement(sql).use { stmt ->
      stmt.setLong(1, responseId.value)
      stmt.setString(2, payload.toString())
      stmt.executeUpdate()
    }
  }

  // ---------------------------------------------------------------------------
  // Logs — read
  // ---------------------------------------------------------------------------

  /**
   * The shared turn projection: every `convo_requests` column aliased `req_*`
   * and every LEFT-JOINed `convo_responses` column aliased `resp_*`, so [mapTurn]
   * reads both halves from one row. Reused by the per-convo and global turn reads.
   */
  private val turnSelect =
    """
    SELECT
      r.id   AS req_id,
      r.convo_id AS req_convo_id,
      r.created_at AS req_created_at,
      r.provider AS req_provider,
      r.model_requested AS req_model_requested,
      r.system_prompt_id AS req_system_prompt_id,
      r.request_params AS req_request_params,
      r.content AS req_content,
      resp.id AS resp_id,
      resp.request_id AS resp_request_id,
      resp.convo_id AS resp_convo_id,
      resp.content AS resp_content,
      resp.model_resolved AS resp_model_resolved,
      resp.stop_reason AS resp_stop_reason,
      resp.input_tokens AS resp_input_tokens,
      resp.output_tokens AS resp_output_tokens,
      resp.cache_read_tokens AS resp_cache_read_tokens,
      resp.cache_write_tokens AS resp_cache_write_tokens,
      resp.provider_request_id AS resp_provider_request_id,
      resp.latency_ms AS resp_latency_ms,
      resp.created_at AS resp_created_at
    FROM convo_requests r
    JOIN convos c ON c.id = r.convo_id
    LEFT JOIN convo_responses resp ON resp.request_id = r.id
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
        provider = rs.getString("req_provider"),
        modelRequested = rs.getString("req_model_requested"),
        systemPromptId = SystemPromptId(UUID.fromString(rs.getString("req_system_prompt_id"))),
        requestParams = rs.getJsonbOrNull("req_request_params") as JsonObject?,
        content = Json.parseToJsonElement(rs.getString("req_content")),
      )
    // resp_id is NULL when the LEFT JOIN found no response row.
    rs.getLong("resp_id")
    val response = if (rs.wasNull()) null else mapResponse(rs, "resp_")
    return ConvoTurn(request, response)
  }

  fun findRawByResponseId(
    session: SqlSession,
    responseId: ConvoResponseId,
  ): Result<ConvoResponseRaw> =
    session.queryOne(
      "SELECT * FROM convo_responses_raw WHERE response_id = ?",
      bind = { it.setLong(1, responseId.value) },
      map = ::mapResponseRaw,
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
          message.contains("convo_responses_request_id_fkey") -> NotFoundException("Request not found")
          message.contains("convo_responses_convo_id_fkey") -> NotFoundException("Convo not found")
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
