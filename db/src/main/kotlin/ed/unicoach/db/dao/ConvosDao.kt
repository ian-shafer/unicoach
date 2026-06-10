package ed.unicoach.db.dao

import ed.unicoach.db.models.Convo
import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.ConvoName
import ed.unicoach.db.models.ConvoRequest
import ed.unicoach.db.models.ConvoRequestId
import ed.unicoach.db.models.ConvoResponse
import ed.unicoach.db.models.ConvoResponseId
import ed.unicoach.db.models.ConvoResponseRaw
import ed.unicoach.db.models.ConvoTurn
import ed.unicoach.db.models.NewConvo
import ed.unicoach.db.models.NewConvoRequest
import ed.unicoach.db.models.NewConvoResponse
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.SystemPromptId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
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
object ConvosDao {
  // ---------------------------------------------------------------------------
  // Row mappers
  // ---------------------------------------------------------------------------

  private fun mapConvo(rs: ResultSet): Convo =
    Convo(
      id = ConvoId(UUID.fromString(rs.getString("id"))),
      studentId = StudentId(UUID.fromString(rs.getString("student_id"))),
      name = parseConvoName(rs.getString("name")),
      createdAt = rs.getTimestamp("created_at").toInstant(),
      updatedAt = rs.getTimestamp("updated_at").toInstant(),
      deletedAt = rs.getTimestamp("deleted_at")?.toInstant(),
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
      createdAt = rs.getTimestamp("created_at").toInstant(),
      provider = rs.getString("provider"),
      modelRequested = rs.getString("model_requested"),
      systemPromptId = SystemPromptId(UUID.fromString(rs.getString("system_prompt_id"))),
      requestParams = readJsonOrNull(rs, "request_params") as JsonObject?,
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
      content = readJsonOrNull(rs, "${columnPrefix}content"),
      modelResolved = rs.getString("${columnPrefix}model_resolved"),
      stopReason = rs.getString("${columnPrefix}stop_reason"),
      inputTokens = rs.getInt("${columnPrefix}input_tokens").takeUnless { rs.wasNull() },
      outputTokens = rs.getInt("${columnPrefix}output_tokens").takeUnless { rs.wasNull() },
      cacheReadTokens = rs.getInt("${columnPrefix}cache_read_tokens").takeUnless { rs.wasNull() },
      cacheWriteTokens = rs.getInt("${columnPrefix}cache_write_tokens").takeUnless { rs.wasNull() },
      providerRequestId = rs.getString("${columnPrefix}provider_request_id"),
      latencyMs = rs.getInt("${columnPrefix}latency_ms").takeUnless { rs.wasNull() },
      createdAt = rs.getTimestamp("${columnPrefix}created_at").toInstant(),
    )

  private fun mapResponseRaw(rs: ResultSet): ConvoResponseRaw =
    ConvoResponseRaw(
      responseId = ConvoResponseId(rs.getLong("response_id")),
      createdAt = rs.getTimestamp("created_at").toInstant(),
      payload = Json.parseToJsonElement(rs.getString("payload")),
    )

  // ---------------------------------------------------------------------------
  // JSON bind/read helpers
  // ---------------------------------------------------------------------------

  /** Binds a nullable [JsonElement] into a `?::jsonb` slot, NULL as `Types.OTHER`. */
  private fun bindJsonOrNull(
    stmt: PreparedStatement,
    index: Int,
    element: JsonElement?,
  ) {
    if (element != null) {
      stmt.setString(index, element.toString())
    } else {
      stmt.setNull(index, Types.OTHER)
    }
  }

  private fun readJsonOrNull(
    rs: ResultSet,
    column: String,
  ): JsonElement? = rs.getString(column)?.let { Json.parseToJsonElement(it) }

  // ---------------------------------------------------------------------------
  // SoftDeleteScope predicates (fixed SQL fragments; no caller data)
  // ---------------------------------------------------------------------------

  private fun scopePredicate(
    scope: SoftDeleteScope,
    column: String,
  ): String =
    when (scope) {
      SoftDeleteScope.ACTIVE -> "$column IS NULL"
      SoftDeleteScope.DELETED -> "$column IS NOT NULL"
      SoftDeleteScope.ALL -> "TRUE"
    }

  // ---------------------------------------------------------------------------
  // Convo entity
  // ---------------------------------------------------------------------------

  fun findById(
    session: SqlSession,
    id: ConvoId,
    scope: SoftDeleteScope = SoftDeleteScope.ACTIVE,
  ): Result<Convo> =
    try {
      session.prepareStatement("SELECT * FROM convos WHERE id = ?").use { stmt ->
        stmt.setObject(1, id.value)
        stmt.executeQuery().use { rs ->
          if (!rs.next()) {
            return Result.failure(NotFoundException())
          }
          val convo = mapConvo(rs)
          if (!scopeAdmits(scope, convo.deletedAt)) {
            return Result.failure(NotFoundException())
          }
          Result.success(convo)
        }
      }
    } catch (e: Exception) {
      Result.failure(mapDatabaseError(e))
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
  ): Result<List<Convo>> =
    try {
      val sql =
        """
        SELECT * FROM convos
        WHERE student_id = ? AND ${scopePredicate(scope, "deleted_at")}
        ORDER BY created_at, id
        """.trimIndent()
      session.prepareStatement(sql).use { stmt ->
        stmt.setObject(1, studentId.value)
        stmt.executeQuery().use { rs ->
          val convos = mutableListOf<Convo>()
          while (rs.next()) {
            convos.add(mapConvo(rs))
          }
          Result.success(convos)
        }
      }
    } catch (e: Exception) {
      Result.failure(mapDatabaseError(e))
    }

  fun create(
    session: SqlSession,
    convo: NewConvo,
  ): Result<Convo> =
    try {
      val sql =
        """
        INSERT INTO convos (student_id, name)
        VALUES (?, ?)
        RETURNING *
        """.trimIndent()
      session.prepareStatement(sql).use { stmt ->
        stmt.setObject(1, convo.studentId.value)
        stmt.setString(2, convo.name.value)
        stmt.executeQuery().use { rs ->
          if (rs.next()) {
            Result.success(mapConvo(rs))
          } else {
            Result.failure(DatabaseException(RuntimeException("Insert succeeded but returning failed")))
          }
        }
      }
    } catch (e: SQLException) {
      Result.failure(mapConvoError(e))
    } catch (e: Exception) {
      Result.failure(mapDatabaseError(e))
    }

  fun rename(
    session: SqlSession,
    id: ConvoId,
    name: ConvoName,
  ): Result<Convo> =
    try {
      val sql =
        """
        UPDATE convos
        SET name = ?
        WHERE id = ? AND deleted_at IS NULL
        RETURNING *
        """.trimIndent()
      session.prepareStatement(sql).use { stmt ->
        stmt.setString(1, name.value)
        stmt.setObject(2, id.value)
        stmt.executeQuery().use { rs ->
          if (rs.next()) {
            Result.success(mapConvo(rs))
          } else {
            Result.failure(NotFoundException())
          }
        }
      }
    } catch (e: SQLException) {
      Result.failure(mapConvoError(e))
    } catch (e: Exception) {
      Result.failure(mapDatabaseError(e))
    }

  fun delete(
    session: SqlSession,
    id: ConvoId,
  ): Result<Convo> =
    try {
      val sql =
        """
        UPDATE convos
        SET deleted_at = NOW()
        WHERE id = ? AND deleted_at IS NULL
        RETURNING *
        """.trimIndent()
      session.prepareStatement(sql).use { stmt ->
        stmt.setObject(1, id.value)
        stmt.executeQuery().use { rs ->
          if (rs.next()) {
            Result.success(mapConvo(rs))
          } else {
            Result.failure(NotFoundException())
          }
        }
      }
    } catch (e: Exception) {
      Result.failure(mapDatabaseError(e))
    }

  fun undelete(
    session: SqlSession,
    id: ConvoId,
  ): Result<Convo> =
    try {
      val sql =
        """
        UPDATE convos
        SET deleted_at = NULL
        WHERE id = ? AND deleted_at IS NOT NULL
        RETURNING *
        """.trimIndent()
      session.prepareStatement(sql).use { stmt ->
        stmt.setObject(1, id.value)
        stmt.executeQuery().use { rs ->
          if (rs.next()) {
            Result.success(mapConvo(rs))
          } else {
            Result.failure(NotFoundException())
          }
        }
      }
    } catch (e: Exception) {
      Result.failure(mapDatabaseError(e))
    }

  // ---------------------------------------------------------------------------
  // Logs — write (two transaction boundaries)
  // ---------------------------------------------------------------------------

  fun appendRequest(
    session: SqlSession,
    request: NewConvoRequest,
  ): Result<ConvoRequest> =
    try {
      val sql =
        """
        INSERT INTO convo_requests (
          convo_id, provider, model_requested, system_prompt_id, request_params, content
        )
        VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb)
        RETURNING *
        """.trimIndent()
      session.prepareStatement(sql).use { stmt ->
        stmt.setObject(1, request.convoId.value)
        stmt.setString(2, request.provider)
        stmt.setString(3, request.modelRequested)
        stmt.setObject(4, request.systemPromptId.value)
        bindJsonOrNull(stmt, 5, request.requestParams)
        stmt.setString(6, request.content.toString())
        stmt.executeQuery().use { rs ->
          if (rs.next()) {
            Result.success(mapRequest(rs))
          } else {
            Result.failure(DatabaseException(RuntimeException("Insert succeeded but returning failed")))
          }
        }
      }
    } catch (e: SQLException) {
      Result.failure(mapConvoError(e))
    } catch (e: Exception) {
      Result.failure(mapDatabaseError(e))
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
  ): Result<ConvoResponse> =
    try {
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
      val inserted =
        session.prepareStatement(sql).use { stmt ->
          stmt.setLong(1, response.requestId.value)
          stmt.setObject(2, response.convoId.value)
          bindJsonOrNull(stmt, 3, response.content)
          if (response.modelResolved != null) stmt.setString(4, response.modelResolved) else stmt.setNull(4, Types.VARCHAR)
          stmt.setString(5, response.stopReason)
          bindNullableInt(stmt, 6, response.inputTokens)
          bindNullableInt(stmt, 7, response.outputTokens)
          bindNullableInt(stmt, 8, response.cacheReadTokens)
          bindNullableInt(stmt, 9, response.cacheWriteTokens)
          if (response.providerRequestId != null) {
            stmt.setString(10, response.providerRequestId)
          } else {
            stmt.setNull(10, Types.VARCHAR)
          }
          bindNullableInt(stmt, 11, response.latencyMs)
          stmt.executeQuery().use { rs ->
            if (rs.next()) {
              mapResponse(rs)
            } else {
              return Result.failure(DatabaseException(RuntimeException("Insert succeeded but returning failed")))
            }
          }
        }

      if (rawPayload != null) {
        insertRaw(session, inserted.id, rawPayload)
      }

      Result.success(inserted)
    } catch (e: SQLException) {
      Result.failure(mapConvoError(e))
    } catch (e: Exception) {
      Result.failure(mapDatabaseError(e))
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

  private fun bindNullableInt(
    stmt: PreparedStatement,
    index: Int,
    value: Int?,
  ) {
    if (value != null) stmt.setInt(index, value) else stmt.setNull(index, Types.INTEGER)
  }

  // ---------------------------------------------------------------------------
  // Logs — read
  // ---------------------------------------------------------------------------

  fun listTurns(
    session: SqlSession,
    convoId: ConvoId,
    scope: SoftDeleteScope = SoftDeleteScope.ACTIVE,
  ): Result<List<ConvoTurn>> =
    try {
      val sql =
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
        WHERE r.convo_id = ? AND ${scopePredicate(scope, "c.deleted_at")}
        ORDER BY r.created_at, r.id
        """.trimIndent()
      session.prepareStatement(sql).use { stmt ->
        stmt.setObject(1, convoId.value)
        stmt.executeQuery().use { rs ->
          val turns = mutableListOf<ConvoTurn>()
          while (rs.next()) {
            turns.add(mapTurn(rs))
          }
          Result.success(turns)
        }
      }
    } catch (e: Exception) {
      Result.failure(mapDatabaseError(e))
    }

  private fun mapTurn(rs: ResultSet): ConvoTurn {
    val request =
      ConvoRequest(
        id = ConvoRequestId(rs.getLong("req_id")),
        convoId = ConvoId(UUID.fromString(rs.getString("req_convo_id"))),
        createdAt = rs.getTimestamp("req_created_at").toInstant(),
        provider = rs.getString("req_provider"),
        modelRequested = rs.getString("req_model_requested"),
        systemPromptId = SystemPromptId(UUID.fromString(rs.getString("req_system_prompt_id"))),
        requestParams = readJsonOrNull(rs, "req_request_params") as JsonObject?,
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
    try {
      session.prepareStatement("SELECT * FROM convo_responses_raw WHERE response_id = ?").use { stmt ->
        stmt.setLong(1, responseId.value)
        stmt.executeQuery().use { rs ->
          if (rs.next()) {
            Result.success(mapResponseRaw(rs))
          } else {
            Result.failure(NotFoundException())
          }
        }
      }
    } catch (e: Exception) {
      Result.failure(mapDatabaseError(e))
    }

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
