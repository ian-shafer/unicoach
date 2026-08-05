package ed.unicoach.db.dao

import ed.unicoach.common.money.Nanodollars
import ed.unicoach.db.models.FrozenCost
import ed.unicoach.db.models.LlmCallOutcome
import ed.unicoach.db.models.LlmFailureKind
import ed.unicoach.db.models.LlmRequestId
import ed.unicoach.db.models.NewLlmRequest
import ed.unicoach.db.models.NewLlmResponse
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LlmCallsDaoTest {
  companion object {
    private lateinit var connection: Connection

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf")
          .getOrThrow()
      val dbConfig =
        ed.unicoach.db.DatabaseConfig
          .from(config)
          .getOrThrow()
      connection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::connection.isInitialized && !connection.isClosed) connection.close()
    }
  }

  @BeforeEach
  fun resetDatabase() {
    connection.autoCommit = true
    connection.createStatement().use { stmt ->
      stmt.execute("TRUNCATE TABLE llm_requests, llm_responses, llm_responses_raw CASCADE")
    }
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  private val messages =
    buildJsonArray {
      add(
        buildJsonObject {
          put("role", "user")
          put(
            "content",
            buildJsonArray {
              add(
                buildJsonObject {
                  put("type", "text")
                  put("text", "hello")
                },
              )
            },
          )
        },
      )
    }

  private fun newRequest(): NewLlmRequest =
    NewLlmRequest(
      provider = "log",
      modelRequested = "claude-sonnet-4-6",
      system = "You are a coach.",
      content = messages,
      maxTokens = 1024,
      tools =
        buildJsonArray {
          add(
            buildJsonObject {
              put("name", "record")
              put("description", "record something")
            },
          )
        },
      toolChoice = buildJsonObject { put("type", "tool") },
      params = buildJsonObject { put("temperature", 0) },
    )

  private fun completed(): LlmCallOutcome.Completed =
    LlmCallOutcome.Completed(
      content =
        buildJsonArray {
          add(
            buildJsonObject {
              put("type", "text")
              put("text", "hi there")
            },
          )
        },
      modelResolved = "claude-sonnet-4-6-20990101",
      stopReason = "end_turn",
    )

  /** A minimal completed [NewLlmResponse] whose only varying inputs are the request id and the two cost fields. */
  private fun newResponse(
    requestId: LlmRequestId,
    cost: FrozenCost?,
  ): NewLlmResponse =
    NewLlmResponse(
      requestId = requestId,
      outcome = completed(),
      providerRequestId = null,
      inputTokens = null,
      outputTokens = null,
      cacheReadTokens = null,
      cacheWriteTokens = null,
      cost = cost,
      latencyMs = 1,
    )

  @Test
  fun `appendRequest round-trips the full envelope byte-equal`() {
    val appended = LlmCallsDao.appendRequest(session, newRequest()).getOrThrow()

    val fetched = LlmCallsDao.findCallByRequestId(session, appended.id).getOrThrow().request
    assertEquals("log", fetched.provider)
    assertEquals("claude-sonnet-4-6", fetched.modelRequested)
    assertEquals("You are a coach.", fetched.system)
    assertEquals(1024, fetched.maxTokens)
    assertEquals(messages, fetched.content)
    val original = newRequest()
    assertEquals(original.tools, fetched.tools)
    assertEquals(original.toolChoice, fetched.toolChoice)
    assertEquals(original.params, fetched.params)
  }

  @Test
  fun `appendRequest with null system, tools, tool_choice, params reads back null`() {
    val appended =
      LlmCallsDao
        .appendRequest(
          session,
          newRequest().copy(system = null, tools = null, toolChoice = null, params = null),
        ).getOrThrow()
    val fetched = LlmCallsDao.findCallByRequestId(session, appended.id).getOrThrow().request
    assertNull(fetched.system)
    assertNull(fetched.tools)
    assertNull(fetched.toolChoice)
    assertNull(fetched.params)
  }

  @Test
  fun `provider CHECK rejects an unknown provider`() {
    val ex =
      runCatching {
        connection
          .prepareStatement(
            "INSERT INTO llm_requests (provider, model_requested, content, max_tokens) VALUES ('openai', 'gpt-4', '[]'::jsonb, 10)",
          ).use { it.executeUpdate() }
      }.exceptionOrNull()
    assertTrue(ex is java.sql.SQLException && ex.sqlState == "23514", "got $ex")
  }

  @Test
  fun `content must be a JSON array`() {
    val ex =
      runCatching {
        connection
          .prepareStatement(
            "INSERT INTO llm_requests (provider, model_requested, content, max_tokens) VALUES ('log', 'm', '{}'::jsonb, 10)",
          ).use { it.executeUpdate() }
      }.exceptionOrNull()
    assertTrue(ex is java.sql.SQLException && ex.sqlState == "23514", "got $ex")
  }

  @Test
  fun `max_tokens must be positive`() {
    val ex =
      runCatching {
        connection
          .prepareStatement(
            "INSERT INTO llm_requests (provider, model_requested, content, max_tokens) VALUES ('log', 'm', '[]'::jsonb, 0)",
          ).use { it.executeUpdate() }
      }.exceptionOrNull()
    assertTrue(ex is java.sql.SQLException && ex.sqlState == "23514", "got $ex")
  }

  @Test
  fun `appendResponse completed writes response and raw, reconstructs Completed`() {
    val req = LlmCallsDao.appendRequest(session, newRequest()).getOrThrow()
    val raw = buildJsonObject { put("verbatim", true) }
    val resp =
      LlmCallsDao
        .appendResponse(
          session,
          NewLlmResponse(
            requestId = req.id,
            outcome = completed(),
            providerRequestId = "req_abc",
            inputTokens = 10,
            outputTokens = 20,
            cacheReadTokens = 5,
            cacheWriteTokens = 0,
            cost = null,
            latencyMs = 1500,
          ),
          rawPayload = raw,
        ).getOrThrow()

    assertEquals(completed(), resp.outcome)
    assertEquals("req_abc", resp.providerRequestId)
    assertEquals(1500, resp.latencyMs)

    val call = LlmCallsDao.findCallByRequestId(session, req.id).getOrThrow()
    assertEquals(completed(), call.response!!.outcome)
    assertEquals(10, call.response!!.inputTokens)
    assertEquals(raw, call.raw!!.payload)
  }

  @Test
  fun `appendResponse failed writes reason, no raw when bodiless, reconstructs Failed`() {
    val req = LlmCallsDao.appendRequest(session, newRequest()).getOrThrow()
    val resp =
      LlmCallsDao
        .appendResponse(
          session,
          NewLlmResponse(
            requestId = req.id,
            outcome = LlmCallOutcome.Failed(LlmFailureKind.TRANSIENT_FAILURE, "overloaded"),
            providerRequestId = null,
            inputTokens = null,
            outputTokens = null,
            cacheReadTokens = null,
            cacheWriteTokens = null,
            cost = null,
            latencyMs = 42,
          ),
          rawPayload = null,
        ).getOrThrow()

    assertEquals(LlmCallOutcome.Failed(LlmFailureKind.TRANSIENT_FAILURE, "overloaded"), resp.outcome)

    val call = LlmCallsDao.findCallByRequestId(session, req.id).getOrThrow()
    assertEquals(LlmCallOutcome.Failed(LlmFailureKind.TRANSIENT_FAILURE, "overloaded"), call.response!!.outcome)
    assertNull(call.raw, "bodiless failure writes no raw row")
  }

  @Test
  fun `each failure kind round-trips`() {
    for (kind in LlmFailureKind.entries) {
      val req = LlmCallsDao.appendRequest(session, newRequest()).getOrThrow()
      val resp =
        LlmCallsDao
          .appendResponse(
            session,
            NewLlmResponse(
              requestId = req.id,
              outcome = LlmCallOutcome.Failed(kind, "reason for ${kind.value}"),
              providerRequestId = null,
              inputTokens = null,
              outputTokens = null,
              cacheReadTokens = null,
              cacheWriteTokens = null,
              cost = null,
              latencyMs = 1,
            ),
            rawPayload = null,
          ).getOrThrow()
      assertEquals(LlmCallOutcome.Failed(kind, "reason for ${kind.value}"), resp.outcome)
    }
  }

  @Test
  fun `completed with null content is rejected by presence CHECK (raw SQL)`() {
    val req = LlmCallsDao.appendRequest(session, newRequest()).getOrThrow()
    val ex =
      runCatching {
        connection
          .prepareStatement(
            "INSERT INTO llm_responses (request_id, outcome, latency_ms) VALUES (?, 'completed', 5)",
          ).use { stmt ->
            stmt.setLong(1, req.id.value)
            stmt.executeUpdate()
          }
      }.exceptionOrNull()
    assertTrue(ex is java.sql.SQLException && ex.sqlState == "23514", "got $ex")
  }

  @Test
  fun `failure with non-null content is rejected by presence CHECK (raw SQL)`() {
    val req = LlmCallsDao.appendRequest(session, newRequest()).getOrThrow()
    val ex =
      runCatching {
        connection
          .prepareStatement(
            "INSERT INTO llm_responses (request_id, outcome, content, reason, latency_ms) VALUES (?, 'rejected', '[]'::jsonb, 'r', 5)",
          ).use { stmt ->
            stmt.setLong(1, req.id.value)
            stmt.executeUpdate()
          }
      }.exceptionOrNull()
    assertTrue(ex is java.sql.SQLException && ex.sqlState == "23514", "got $ex")
  }

  @Test
  fun `unknown outcome value is rejected by CHECK`() {
    val req = LlmCallsDao.appendRequest(session, newRequest()).getOrThrow()
    val ex =
      runCatching {
        connection
          .prepareStatement(
            "INSERT INTO llm_responses (request_id, outcome, reason, latency_ms) VALUES (?, 'bogus', 'r', 5)",
          ).use { stmt ->
            stmt.setLong(1, req.id.value)
            stmt.executeUpdate()
          }
      }.exceptionOrNull()
    assertTrue(ex is java.sql.SQLException && ex.sqlState == "23514", "got $ex")
  }

  @Test
  fun `negative latency and negative tokens rejected`() {
    val req = LlmCallsDao.appendRequest(session, newRequest()).getOrThrow()
    val badLatency =
      runCatching {
        connection
          .prepareStatement(
            "INSERT INTO llm_responses (request_id, outcome, reason, latency_ms) VALUES (?, 'rejected', 'r', -1)",
          ).use { stmt ->
            stmt.setLong(1, req.id.value)
            stmt.executeUpdate()
          }
      }.exceptionOrNull()
    assertTrue(badLatency is java.sql.SQLException && badLatency.sqlState == "23514", "got $badLatency")

    val badTokens =
      runCatching {
        connection
          .prepareStatement(
            "INSERT INTO llm_responses (request_id, outcome, reason, input_tokens, latency_ms) VALUES (?, 'rejected', 'r', -5, 5)",
          ).use { stmt ->
            stmt.setLong(1, req.id.value)
            stmt.executeUpdate()
          }
      }.exceptionOrNull()
    assertTrue(badTokens is java.sql.SQLException && badTokens.sqlState == "23514", "got $badTokens")
  }

  @Test
  fun `response is 1-1 with request (second response rejected)`() {
    val req = LlmCallsDao.appendRequest(session, newRequest()).getOrThrow()
    LlmCallsDao
      .appendResponse(
        session,
        newResponse(req.id, cost = null),
        rawPayload = null,
      ).getOrThrow()
    val second =
      LlmCallsDao.appendResponse(
        session,
        newResponse(req.id, cost = null),
        rawPayload = null,
      )
    assertTrue(second.exceptionOrNull() is ConstraintViolationException, "got ${second.exceptionOrNull()}")
  }

  @Test
  fun `findCallByRequestId returns response null when no response yet`() {
    val req = LlmCallsDao.appendRequest(session, newRequest()).getOrThrow()
    val call = LlmCallsDao.findCallByRequestId(session, req.id).getOrThrow()
    assertNull(call.response)
    assertNull(call.raw)
  }

  @Test
  fun `findCallByRequestId NotFound for unknown id`() {
    val miss = LlmCallsDao.findCallByRequestId(session, LlmRequestId(999_999L))
    assertTrue(miss.exceptionOrNull() is NotFoundException, "got ${miss.exceptionOrNull()}")
  }

  @Test
  fun `listCalls pages, orders most-recent first, composes request+response+raw`() {
    val r1 = LlmCallsDao.appendRequest(session, newRequest()).getOrThrow()
    LlmCallsDao
      .appendResponse(
        session,
        newResponse(r1.id, cost = null),
        rawPayload = buildJsonObject { put("x", 1) },
      ).getOrThrow()
    val r2 = LlmCallsDao.appendRequest(session, newRequest()).getOrThrow()

    val page = LlmCallsDao.listCalls(session, 10, 0).getOrThrow()
    assertEquals(listOf(r2.id, r1.id), page.map { it.request.id })
    // The most recent has no response yet; the older has a completed response + raw.
    assertNull(page[0].response)
    assertEquals(completed(), page[1].response!!.outcome)
    assertEquals(buildJsonObject { put("x", 1) }, page[1].raw!!.payload)

    assertEquals(listOf(r1.id), LlmCallsDao.listCalls(session, 1, 1).getOrThrow().map { it.request.id })
  }

  @Test
  fun `UPDATE and DELETE on each llm table raise P0001`() {
    val req = LlmCallsDao.appendRequest(session, newRequest()).getOrThrow()
    val resp =
      LlmCallsDao
        .appendResponse(
          session,
          newResponse(req.id, cost = null),
          rawPayload = buildJsonObject { put("x", 1) },
        ).getOrThrow()

    val updates =
      listOf(
        "UPDATE llm_requests SET model_requested = 'x' WHERE id = ${req.id.value}",
        "DELETE FROM llm_requests WHERE id = ${req.id.value}",
        "UPDATE llm_responses SET latency_ms = 9 WHERE id = ${resp.id.value}",
        "DELETE FROM llm_responses WHERE id = ${resp.id.value}",
        "UPDATE llm_responses_raw SET payload = '{}'::jsonb WHERE response_id = ${resp.id.value}",
        "DELETE FROM llm_responses_raw WHERE response_id = ${resp.id.value}",
      )
    for (sql in updates) {
      val ex = runCatching { connection.createStatement().use { it.execute(sql) } }.exceptionOrNull()
      assertTrue(ex is java.sql.SQLException && ex.sqlState == "P0001", "for [$sql] got $ex")
    }
  }

  @Test
  fun `cost columns round-trip on the response`() {
    // A priced call: both cost columns persist and read back (RFC 108).
    val reqPriced = LlmCallsDao.appendRequest(session, newRequest()).getOrThrow()
    val priced =
      LlmCallsDao
        .appendResponse(
          session,
          newResponse(reqPriced.id, cost = FrozenCost(nanodollars = Nanodollars.of(4200L), estimated = false)),
          rawPayload = null,
        ).getOrThrow()
    assertEquals(4200L, priced.cost?.nanodollars?.value)
    assertEquals(false, priced.cost?.estimated)
    val fetchedPriced = LlmCallsDao.findCallByRequestId(session, reqPriced.id).getOrThrow().response!!
    assertEquals(4200L, fetchedPriced.cost?.nanodollars?.value)
    assertEquals(false, fetchedPriced.cost?.estimated)

    // An uncosted call: both columns read back null.
    val reqNull = LlmCallsDao.appendRequest(session, newRequest()).getOrThrow()
    LlmCallsDao
      .appendResponse(session, newResponse(reqNull.id, cost = null), rawPayload = null)
      .getOrThrow()
    val fetchedNull = LlmCallsDao.findCallByRequestId(session, reqNull.id).getOrThrow().response!!
    assertNull(fetchedNull.cost)

    // The estimated flag round-trips true.
    val reqEst = LlmCallsDao.appendRequest(session, newRequest()).getOrThrow()
    LlmCallsDao
      .appendResponse(
        session,
        newResponse(reqEst.id, cost = FrozenCost(nanodollars = Nanodollars.of(999L), estimated = true)),
        rawPayload = null,
      ).getOrThrow()
    assertEquals(
      true,
      LlmCallsDao
        .findCallByRequestId(session, reqEst.id)
        .getOrThrow()
        .response!!
        .cost
        ?.estimated,
    )
  }

  /**
   * Runs a raw `llm_responses` INSERT with the given cost-column [tail] appended to
   * a minimal `rejected` row (cost columns are orthogonal to the outcome, so a
   * short failure row exercises the cost CHECKs), binding `?` = the request id.
   * Returns any thrown exception.
   */
  private fun rawCostInsert(
    requestId: LlmRequestId,
    columns: String,
    values: String,
  ): Throwable? =
    runCatching {
      val sql =
        "INSERT INTO llm_responses (request_id, outcome, reason, latency_ms, $columns) " +
          "VALUES (?, 'rejected', 'r', 5, $values)"
      connection.prepareStatement(sql).use { stmt ->
        stmt.setLong(1, requestId.value)
        stmt.executeUpdate()
      }
    }.exceptionOrNull()

  @Test
  fun `negative cost is rejected by the non-negative CHECK`() {
    val req = LlmCallsDao.appendRequest(session, newRequest()).getOrThrow()
    val ex = rawCostInsert(req.id, "cost_nanodollars, cost_is_estimated", "-1, false")
    assertTrue(ex is java.sql.SQLException && ex.sqlState == "23514", "got $ex")
  }

  @Test
  fun `cost and its estimated flag must be present together`() {
    // A cost with no estimated flag, and an estimated flag with no cost, each
    // violate llm_responses_cost_estimated_check.
    val reqA = LlmCallsDao.appendRequest(session, newRequest()).getOrThrow()
    val costNoFlag = rawCostInsert(reqA.id, "cost_nanodollars", "100")
    assertTrue(costNoFlag is java.sql.SQLException && costNoFlag.sqlState == "23514", "got $costNoFlag")

    val reqB = LlmCallsDao.appendRequest(session, newRequest()).getOrThrow()
    val flagNoCost = rawCostInsert(reqB.id, "cost_is_estimated", "false")
    assertTrue(flagNoCost is java.sql.SQLException && flagNoCost.sqlState == "23514", "got $flagNoCost")
  }
}
