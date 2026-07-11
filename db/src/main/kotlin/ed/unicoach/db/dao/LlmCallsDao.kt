package ed.unicoach.db.dao

import ed.unicoach.db.models.LlmCall
import ed.unicoach.db.models.LlmCallOutcome
import ed.unicoach.db.models.LlmFailureKind
import ed.unicoach.db.models.LlmRequest
import ed.unicoach.db.models.LlmRequestId
import ed.unicoach.db.models.LlmResponse
import ed.unicoach.db.models.LlmResponseId
import ed.unicoach.db.models.LlmResponseRaw
import ed.unicoach.db.models.NewLlmRequest
import ed.unicoach.db.models.NewLlmResponse
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.postgresql.util.PSQLException
import java.sql.ResultSet
import java.sql.SQLException

/**
 * Data-access layer over the provider-agnostic LLM call log (RFC 106):
 * `llm_requests`, `llm_responses`, `llm_responses_raw`. Stateless `object`, one
 * [SqlSession] per call, transaction boundaries owned by the caller. Every table
 * is insert-only. This is the generalization of `ConvosDao`'s request/response/
 * raw handling, now shared by every LLM provider call (chat + structured alike).
 *
 * The DAO is the sole boundary between the [LlmCallOutcome] ADT and the flat
 * `outcome` column plus its dependent columns: [appendResponse] destructures the
 * ADT into columns; [mapResponse] reconstructs it, with a total `when` over the
 * five CHECK-constrained `outcome` strings (a corrupt row throws).
 */
object LlmCallsDao {
  // ---------------------------------------------------------------------------
  // Row mappers
  // ---------------------------------------------------------------------------

  /**
   * The `llm_*` column-name suffixes another DAO joins the call log under and
   * hands back to [mapCallColumns]. Kept here so the join-shape lives in one
   * place: `ConvosDao.turnSelect` aliases the request/response/raw columns with
   * these suffixes (e.g. `resp.outcome AS lresp_outcome`) and delegates the whole
   * `LlmCall` reconstruction — including the outcome-ADT `when` — back to this DAO.
   */
  const val JOINED_REQUEST_PREFIX = "lreq_"
  const val JOINED_RESPONSE_PREFIX = "lresp_"
  const val JOINED_RAW_PREFIX = "lraw_"

  /**
   * Reconstructs an [LlmCall] from a joined row whose `llm_requests` /
   * `llm_responses` / `llm_responses_raw` columns carry the [JOINED_REQUEST_PREFIX]
   * / [JOINED_RESPONSE_PREFIX] / [JOINED_RAW_PREFIX] aliases. Response and raw are
   * null when their LEFT JOIN found no row. The single reuse point for a DAO
   * (e.g. `ConvosDao`) that joins the call log rather than reading it directly.
   */
  internal fun mapCallColumns(rs: ResultSet): LlmCall {
    val request = mapRequest(rs, JOINED_REQUEST_PREFIX)
    rs.getLong("${JOINED_RESPONSE_PREFIX}id")
    val response = if (rs.wasNull()) null else mapResponse(rs, JOINED_RESPONSE_PREFIX)
    rs.getLong("${JOINED_RAW_PREFIX}response_id")
    val raw = if (rs.wasNull()) null else mapRaw(rs, JOINED_RAW_PREFIX)
    return LlmCall(request = request, response = response, raw = raw)
  }

  /**
   * The aliased `SELECT` fragment a joining DAO splices into its own query so
   * [mapCallColumns] can read the whole call. `[req]`, `[resp]`, `[raw]` are the
   * caller's table aliases for `llm_requests` / `llm_responses` /
   * `llm_responses_raw`. No caller data — fixed identifiers only.
   */
  fun joinedCallColumns(
    reqAlias: String,
    respAlias: String,
    rawAlias: String,
  ): String =
    """
    $reqAlias.id AS ${JOINED_REQUEST_PREFIX}id,
    $reqAlias.created_at AS ${JOINED_REQUEST_PREFIX}created_at,
    $reqAlias.provider AS ${JOINED_REQUEST_PREFIX}provider,
    $reqAlias.model_requested AS ${JOINED_REQUEST_PREFIX}model_requested,
    $reqAlias.system AS ${JOINED_REQUEST_PREFIX}system,
    $reqAlias.content AS ${JOINED_REQUEST_PREFIX}content,
    $reqAlias.max_tokens AS ${JOINED_REQUEST_PREFIX}max_tokens,
    $reqAlias.tools AS ${JOINED_REQUEST_PREFIX}tools,
    $reqAlias.tool_choice AS ${JOINED_REQUEST_PREFIX}tool_choice,
    $reqAlias.params AS ${JOINED_REQUEST_PREFIX}params,
    $respAlias.id AS ${JOINED_RESPONSE_PREFIX}id,
    $respAlias.created_at AS ${JOINED_RESPONSE_PREFIX}created_at,
    $respAlias.request_id AS ${JOINED_RESPONSE_PREFIX}request_id,
    $respAlias.outcome AS ${JOINED_RESPONSE_PREFIX}outcome,
    $respAlias.content AS ${JOINED_RESPONSE_PREFIX}content,
    $respAlias.model_resolved AS ${JOINED_RESPONSE_PREFIX}model_resolved,
    $respAlias.stop_reason AS ${JOINED_RESPONSE_PREFIX}stop_reason,
    $respAlias.provider_request_id AS ${JOINED_RESPONSE_PREFIX}provider_request_id,
    $respAlias.reason AS ${JOINED_RESPONSE_PREFIX}reason,
    $respAlias.input_tokens AS ${JOINED_RESPONSE_PREFIX}input_tokens,
    $respAlias.output_tokens AS ${JOINED_RESPONSE_PREFIX}output_tokens,
    $respAlias.cache_read_tokens AS ${JOINED_RESPONSE_PREFIX}cache_read_tokens,
    $respAlias.cache_write_tokens AS ${JOINED_RESPONSE_PREFIX}cache_write_tokens,
    $respAlias.latency_ms AS ${JOINED_RESPONSE_PREFIX}latency_ms,
    $rawAlias.response_id AS ${JOINED_RAW_PREFIX}response_id,
    $rawAlias.created_at AS ${JOINED_RAW_PREFIX}created_at,
    $rawAlias.payload AS ${JOINED_RAW_PREFIX}payload
    """.trimIndent()

  private fun mapRequest(
    rs: ResultSet,
    columnPrefix: String = "",
  ): LlmRequest =
    LlmRequest(
      id = LlmRequestId(rs.getLong("${columnPrefix}id")),
      createdAt = rs.getInstant("${columnPrefix}created_at"),
      provider = rs.getString("${columnPrefix}provider"),
      modelRequested = rs.getString("${columnPrefix}model_requested"),
      system = rs.getString("${columnPrefix}system"),
      content = Json.parseToJsonElement(rs.getString("${columnPrefix}content")) as JsonArray,
      maxTokens = rs.getInt("${columnPrefix}max_tokens"),
      tools = rs.getJsonbOrNull("${columnPrefix}tools") as JsonArray?,
      toolChoice = rs.getJsonbOrNull("${columnPrefix}tool_choice") as JsonObject?,
      params = rs.getJsonbOrNull("${columnPrefix}params") as JsonObject?,
    )

  private fun mapResponse(
    rs: ResultSet,
    columnPrefix: String = "",
  ): LlmResponse =
    LlmResponse(
      id = LlmResponseId(rs.getLong("${columnPrefix}id")),
      createdAt = rs.getInstant("${columnPrefix}created_at"),
      requestId = LlmRequestId(rs.getLong("${columnPrefix}request_id")),
      outcome = mapOutcome(rs, columnPrefix),
      providerRequestId = rs.getString("${columnPrefix}provider_request_id"),
      inputTokens = rs.getInt("${columnPrefix}input_tokens").takeUnless { rs.wasNull() },
      outputTokens = rs.getInt("${columnPrefix}output_tokens").takeUnless { rs.wasNull() },
      cacheReadTokens = rs.getInt("${columnPrefix}cache_read_tokens").takeUnless { rs.wasNull() },
      cacheWriteTokens = rs.getInt("${columnPrefix}cache_write_tokens").takeUnless { rs.wasNull() },
      latencyMs = rs.getInt("${columnPrefix}latency_ms"),
    )

  /**
   * Reconstructs the [LlmCallOutcome] ADT from the flat `outcome` column plus
   * its dependent columns. Total over the five CHECK-constrained `outcome`
   * strings: `completed` → [LlmCallOutcome.Completed] (its content/model/stop
   * columns are non-null by CHECK); each failure string → [LlmCallOutcome.Failed]
   * carrying the matching [LlmFailureKind] and the non-null `reason`. An
   * unrecognized value is a corrupt row (raw SQL bypassing the app) and throws,
   * mirroring `ExtractionRunsDao` — never a silent misread.
   */
  private fun mapOutcome(
    rs: ResultSet,
    columnPrefix: String,
  ): LlmCallOutcome =
    when (val outcome = rs.getString("${columnPrefix}outcome")) {
      "completed" -> {
        LlmCallOutcome.Completed(
          content = Json.parseToJsonElement(rs.getString("${columnPrefix}content")),
          modelResolved = rs.getString("${columnPrefix}model_resolved"),
          stopReason = rs.getString("${columnPrefix}stop_reason"),
        )
      }

      else -> {
        val kind =
          LlmFailureKind.fromValue(outcome)
            ?: throw SQLException(
              "Persisted llm_responses.outcome is not a valid value for llm_responses.id=[${rs.getLong(
                "${columnPrefix}id",
              )}] request_id=[${rs.getLong("${columnPrefix}request_id")}]: [$outcome]",
            )
        LlmCallOutcome.Failed(kind = kind, reason = rs.getString("${columnPrefix}reason"))
      }
    }

  private fun mapRaw(
    rs: ResultSet,
    columnPrefix: String = "",
  ): LlmResponseRaw =
    LlmResponseRaw(
      responseId = LlmResponseId(rs.getLong("${columnPrefix}response_id")),
      createdAt = rs.getInstant("${columnPrefix}created_at"),
      payload = Json.parseToJsonElement(rs.getString("${columnPrefix}payload")),
    )

  // ---------------------------------------------------------------------------
  // Writes
  // ---------------------------------------------------------------------------

  /**
   * Appends one `llm_requests` row from the request envelope, returning it with
   * its id. Hand-written SQL with `?::jsonb` casts (matching `ConvosDao`) because
   * the JDBC driver binds jsonb columns from a `setString` value only through an
   * explicit cast — the generic `insertReturning` emits a bare `?`.
   */
  fun appendRequest(
    session: SqlSession,
    input: NewLlmRequest,
  ): Result<LlmRequest> {
    val sql =
      """
      INSERT INTO llm_requests (
        provider, model_requested, system, content, max_tokens, tools, tool_choice, params
      )
      VALUES (?, ?, ?, ?::jsonb, ?, ?::jsonb, ?::jsonb, ?::jsonb)
      RETURNING *
      """.trimIndent()
    return session.mutateReturning(
      sql,
      bind = { stmt ->
        stmt.setString(1, input.provider)
        stmt.setString(2, input.modelRequested)
        stmt.setStringOrNull(3, input.system)
        stmt.setJsonbOrNull(4, input.content)
        stmt.setInt(5, input.maxTokens)
        stmt.setJsonbOrNull(6, input.tools)
        stmt.setJsonbOrNull(7, input.toolChoice)
        stmt.setJsonbOrNull(8, input.params)
      },
      map = { mapRequest(it) },
      mapError = ::mapCallError,
    )
  }

  /**
   * Inserts the `llm_responses` row and, when [rawPayload] is non-null, the
   * verbatim `llm_responses_raw` row keyed to it. Both inserts run inside the
   * single transaction the caller provides, so the response and its raw sibling
   * are atomic together. A null [rawPayload] is a bodiless terminal (a failure
   * that carried no payload): only the response row is written.
   */
  fun appendResponse(
    session: SqlSession,
    input: NewLlmResponse,
    rawPayload: JsonElement?,
  ): Result<LlmResponse> {
    // Destructure the outcome ADT into the flat columns: a Completed row carries
    // content/model/stop-reason and a null reason; a Failed row carries the
    // failure-kind outcome string, a null content/model/stop-reason, and the
    // reason. The exhaustive `when` forces every variant to be handled.
    val cols =
      when (val outcome = input.outcome) {
        is LlmCallOutcome.Completed -> {
          ResponseOutcomeColumns(
            outcome = outcome.value,
            content = outcome.content,
            modelResolved = outcome.modelResolved,
            stopReason = outcome.stopReason,
            reason = null,
          )
        }

        is LlmCallOutcome.Failed -> {
          ResponseOutcomeColumns(
            outcome = outcome.value,
            content = null,
            modelResolved = null,
            stopReason = null,
            reason = outcome.reason,
          )
        }
      }
    val sql =
      """
      INSERT INTO llm_responses (
        request_id, outcome, content, model_resolved, stop_reason,
        provider_request_id, reason,
        input_tokens, output_tokens, cache_read_tokens, cache_write_tokens, latency_ms
      )
      VALUES (?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      RETURNING *
      """.trimIndent()
    val insertedResult =
      session.mutateReturning(
        sql,
        bind = { stmt ->
          stmt.setLong(1, input.requestId.value)
          stmt.setString(2, cols.outcome)
          stmt.setJsonbOrNull(3, cols.content)
          stmt.setStringOrNull(4, cols.modelResolved)
          stmt.setStringOrNull(5, cols.stopReason)
          stmt.setStringOrNull(6, input.providerRequestId)
          stmt.setStringOrNull(7, cols.reason)
          stmt.setIntOrNull(8, input.inputTokens)
          stmt.setIntOrNull(9, input.outputTokens)
          stmt.setIntOrNull(10, input.cacheReadTokens)
          stmt.setIntOrNull(11, input.cacheWriteTokens)
          stmt.setInt(12, input.latencyMs)
        },
        map = { mapResponse(it) },
        mapError = ::mapCallError,
      )

    val inserted = insertedResult.getOrElse { return Result.failure(it) }

    if (rawPayload != null) {
      return try {
        insertRaw(session, inserted.id, rawPayload)
        Result.success(inserted)
      } catch (e: SQLException) {
        Result.failure(mapCallError(e))
      } catch (e: Exception) {
        Result.failure(mapDatabaseError(e))
      }
    }

    return Result.success(inserted)
  }

  /** The flat column values a response-outcome variant maps to on insert. */
  private data class ResponseOutcomeColumns(
    val outcome: String,
    val content: JsonElement?,
    val modelResolved: String?,
    val stopReason: String?,
    val reason: String?,
  )

  private fun insertRaw(
    session: SqlSession,
    responseId: LlmResponseId,
    payload: JsonElement,
  ) {
    val sql = "INSERT INTO llm_responses_raw (response_id, payload) VALUES (?, ?::jsonb)"
    session.prepareStatement(sql).use { stmt ->
      stmt.setLong(1, responseId.value)
      stmt.setString(2, payload.toString())
      stmt.executeUpdate()
    }
  }

  // ---------------------------------------------------------------------------
  // Reads
  // ---------------------------------------------------------------------------

  /**
   * The shared call projection: the [joinedCallColumns] fragment over the DAO's own
   * `r` / `resp` / `raw` table aliases, so [mapCallColumns] reads all three parts
   * from one row. Built from the same fragment a joining DAO splices in, so the two
   * read paths never drift. Reused by the per-id and firehose reads (mirrors
   * `ConvosDao.turnSelect`).
   */
  private val callSelect =
    """
    SELECT
    ${joinedCallColumns("r", "resp", "raw")}
    FROM llm_requests r
    LEFT JOIN llm_responses resp ON resp.request_id = r.id
    LEFT JOIN llm_responses_raw raw ON raw.response_id = resp.id
    """.trimIndent()

  /**
   * One call by request id: the request plus its 1:1 response (null when none
   * yet) and its 0..1 raw payload (null when the terminal carried no body).
   * [NotFoundException] when no request matches. Mirrors
   * `ConvosDao.findTurnByRequestId`.
   */
  fun findCallByRequestId(
    session: SqlSession,
    requestId: LlmRequestId,
  ): Result<LlmCall> =
    session.queryOne(
      "$callSelect WHERE r.id = ?",
      bind = { it.setLong(1, requestId.value) },
      map = ::mapCallColumns,
    )

  /**
   * Global, paginated call firehose for the admin call-list page. One row per
   * request, LEFT JOINed to its response and raw. Ordered `r.id DESC` (the BIGINT
   * IDENTITY PK is monotonic with insertion, so most-recent first comes off the
   * PK index). Mirrors `ConvosDao.listTurns`.
   */
  fun listCalls(
    session: SqlSession,
    limit: Int,
    offset: Int,
  ): Result<List<LlmCall>> {
    require(limit > 0) { "limit must be positive, got $limit" }
    require(offset >= 0) { "offset must be non-negative, got $offset" }
    return session.queryList(
      "$callSelect ORDER BY r.id DESC LIMIT ? OFFSET ?",
      bind = { stmt ->
        stmt.setInt(1, limit)
        stmt.setInt(2, offset)
      },
      map = ::mapCallColumns,
    )
  }

  /**
   * Logged calls that no domain row references — the operator's unlinked-call
   * report for the best-effort call↔domain-row linkage (RFC 106). A single
   * anti-join returns calls older than [olderThan] that no `convo_requests`,
   * `extraction_runs`, `synthesis_runs`, or `fit_lens_runs` (its two ids) row
   * points at. Orphans are rare and permanent (only a hard crash between the
   * `llm_responses` write and the domain-row write produces one), so the query
   * runs only on demand; [limit] bounds the page and the age threshold is
   * parameterized (not a magic literal). Ordered most-recent first.
   */
  fun listUnlinkedCalls(
    session: SqlSession,
    olderThan: java.time.Duration,
    limit: Int,
    offset: Int,
  ): Result<List<LlmCall>> {
    require(limit > 0) { "limit must be positive, got $limit" }
    require(offset >= 0) { "offset must be non-negative, got $offset" }
    val sql =
      """
      $callSelect
      WHERE r.created_at < NOW() - (? || ' seconds')::interval
        AND NOT EXISTS (SELECT 1 FROM convo_requests cq WHERE cq.llm_request_id = r.id)
        AND NOT EXISTS (SELECT 1 FROM extraction_runs er WHERE er.llm_request_id = r.id)
        AND NOT EXISTS (SELECT 1 FROM synthesis_runs sr WHERE sr.llm_request_id = r.id)
        AND NOT EXISTS (
          SELECT 1 FROM fit_lens_runs flr
          WHERE flr.query_llm_request_id = r.id OR flr.reason_llm_request_id = r.id
        )
      ORDER BY r.id DESC
      LIMIT ? OFFSET ?
      """.trimIndent()
    return session.queryList(
      sql,
      bind = { stmt ->
        stmt.setString(1, olderThan.seconds.toString())
        stmt.setInt(2, limit)
        stmt.setInt(3, offset)
      },
      map = ::mapCallColumns,
    )
  }

  // ---------------------------------------------------------------------------
  // Error mapping
  // ---------------------------------------------------------------------------

  private fun mapCallError(e: SQLException): Exception =
    when (e.sqlState) {
      "23503" -> {
        val message = e.message ?: ""
        when {
          message.contains("llm_responses_request_id_fkey") -> NotFoundException("Request not found")
          message.contains("llm_responses_raw_response_id_fkey") -> NotFoundException("Response not found")
          else -> NotFoundException()
        }
      }

      "23505", "23514" -> {
        // Populate the violated-constraint name (CollegesDao's precedent) so a
        // caller can bucket precisely — e.g. LlmCallLog.writeCancelledIfAbsent
        // swallows only the llm_responses_request_id_key 1:1 idempotency race,
        // never a CHECK (23514) integrity defect.
        val serverError = (e as? PSQLException)?.serverErrorMessage
        ConstraintViolationException(e, serverError?.constraint, serverError?.detail)
      }

      else -> {
        mapDatabaseError(e)
      }
    }
}
