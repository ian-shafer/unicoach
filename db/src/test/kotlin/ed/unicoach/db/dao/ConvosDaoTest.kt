package ed.unicoach.db.dao

import ed.unicoach.common.models.ValidationResult
import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.ConvoName
import ed.unicoach.db.models.ConvoResponseId
import ed.unicoach.db.models.NewConvo
import ed.unicoach.db.models.NewConvoRequest
import ed.unicoach.db.models.NewConvoResponse
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.SystemPromptId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConvosDaoTest {
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
      if (::connection.isInitialized && !connection.isClosed) {
        connection.close()
      }
    }
  }

  @BeforeEach
  fun resetDatabase() {
    connection.autoCommit = true
    connection.createStatement().use { stmt ->
      stmt.execute(
        "TRUNCATE TABLE convos, convo_requests, convo_responses, convo_responses_raw, system_prompts, students, users CASCADE",
      )
    }
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private fun createStudent(): StudentId {
    val userId = UUID.randomUUID()
    val studentId = UUID.randomUUID()
    connection.createStatement().use { stmt ->
      stmt.execute(
        "INSERT INTO users (id, email, name, password_hash) VALUES ('$userId', 'conv-$userId@test.com', 'Conv User', 'ahash')",
      )
      stmt.execute(
        """
        INSERT INTO students (id, user_id, expected_high_school_graduation_year)
        VALUES ('$studentId', '$userId', 2028)
        """.trimIndent(),
      )
    }
    return StudentId(studentId)
  }

  private var promptCounter = 0

  /** Inserts an immutable system_prompts row (RFC 33) and returns its id. */
  private fun createSystemPrompt(): SystemPromptId {
    val id = UUID.randomUUID()
    val version = "v${promptCounter++}"
    connection
      .prepareStatement(
        "INSERT INTO system_prompts (id, name, version, body) VALUES (?, 'coach', ?, 'be a good coach')",
      ).use { stmt ->
        stmt.setObject(1, id)
        stmt.setString(2, version)
        stmt.executeUpdate()
      }
    return SystemPromptId(id)
  }

  private fun name(value: String): ConvoName = (ConvoName.create(value) as ValidationResult.Valid).value

  private fun newConvo(
    studentId: StudentId,
    nameStr: String = "A conversation",
  ): NewConvo = NewConvo(studentId, name(nameStr))

  private fun json(raw: String): JsonElement = Json.parseToJsonElement(raw)

  private fun obj(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject(block)

  private fun newRequest(
    convoId: ConvoId,
    provider: String = "anthropic",
    systemPromptId: SystemPromptId = createSystemPrompt(),
    requestParams: JsonObject? = obj { put("temperature", 0.7) },
    content: JsonElement = json("""[{"type":"text","text":"hello"}]"""),
  ): NewConvoRequest =
    NewConvoRequest(
      convoId = convoId,
      provider = provider,
      modelRequested = "claude-opus-4-8",
      systemPromptId = systemPromptId,
      requestParams = requestParams,
      content = content,
    )

  private fun appendRequestFor(convoId: ConvoId): ed.unicoach.db.models.ConvoRequest =
    ConvosDao.appendRequest(session, newRequest(convoId)).getOrThrow()

  private fun successResponse(
    requestId: ed.unicoach.db.models.ConvoRequestId,
    convoId: ConvoId,
    content: JsonElement? = json("""[{"type":"text","text":"hi there"}]"""),
    stopReason: String = "end_turn",
    modelResolved: String? = "claude-opus-4-8",
    inputTokens: Int? = 10,
  ): NewConvoResponse =
    NewConvoResponse(
      requestId = requestId,
      convoId = convoId,
      content = content,
      modelResolved = modelResolved,
      stopReason = stopReason,
      inputTokens = inputTokens,
      outputTokens = 20,
      cacheReadTokens = 0,
      cacheWriteTokens = 0,
      providerRequestId = "req_abc",
      latencyMs = 123,
    )

  // ---------------------------------------------------------------------------
  // Convo entity
  // ---------------------------------------------------------------------------

  @Test
  fun `create returns convo with generated id, name, and equal created and updated timestamps`() {
    val student = createStudent()
    val convo = ConvosDao.create(session, newConvo(student, "First convo")).getOrThrow()

    assertEquals(student, convo.studentId)
    assertEquals("First convo", convo.name.value)
    assertEquals(convo.createdAt, convo.updatedAt)
    assertNull(convo.deletedAt)
  }

  @Test
  fun `create with absent student returns NotFoundException`() {
    val orphan = StudentId(UUID.randomUUID())
    val result = ConvosDao.create(session, newConvo(orphan))
    assertTrue(result.exceptionOrNull() is NotFoundException, "got $result")
  }

  @Test
  fun `findById ACTIVE returns an active convo`() {
    val student = createStudent()
    val convo = ConvosDao.create(session, newConvo(student)).getOrThrow()
    val found = ConvosDao.findById(session, convo.id).getOrThrow()
    assertEquals(convo.id, found.id)
  }

  @Test
  fun `findById ACTIVE returns NotFoundException for a soft-deleted convo`() {
    val student = createStudent()
    val convo = ConvosDao.create(session, newConvo(student)).getOrThrow()
    ConvosDao.delete(session, convo.id).getOrThrow()
    val result = ConvosDao.findById(session, convo.id)
    assertTrue(result.exceptionOrNull() is NotFoundException, "got $result")
  }

  @Test
  fun `findById DELETED returns a soft-deleted convo and NotFoundException for an active one`() {
    val student = createStudent()
    val active = ConvosDao.create(session, newConvo(student)).getOrThrow()
    val deleted = ConvosDao.create(session, newConvo(student)).getOrThrow()
    ConvosDao.delete(session, deleted.id).getOrThrow()

    assertEquals(deleted.id, ConvosDao.findById(session, deleted.id, SoftDeleteScope.DELETED).getOrThrow().id)
    assertTrue(ConvosDao.findById(session, active.id, SoftDeleteScope.DELETED).exceptionOrNull() is NotFoundException)
  }

  @Test
  fun `findById ALL returns a convo regardless of deletion`() {
    val student = createStudent()
    val convo = ConvosDao.create(session, newConvo(student)).getOrThrow()
    assertTrue(ConvosDao.findById(session, convo.id, SoftDeleteScope.ALL).isSuccess)
    ConvosDao.delete(session, convo.id).getOrThrow()
    assertTrue(ConvosDao.findById(session, convo.id, SoftDeleteScope.ALL).isSuccess)
  }

  @Test
  fun `findById returns NotFoundException for an absent id`() {
    val result = ConvosDao.findById(session, ConvoId(UUID.randomUUID()))
    assertTrue(result.exceptionOrNull() is NotFoundException, "got $result")
  }

  @Test
  fun `listByStudent ACTIVE returns only active convos ordered by created_at then id`() {
    val student = createStudent()
    val a = ConvosDao.create(session, newConvo(student, "a")).getOrThrow()
    val b = ConvosDao.create(session, newConvo(student, "b")).getOrThrow()
    val c = ConvosDao.create(session, newConvo(student, "c")).getOrThrow()
    ConvosDao.delete(session, b.id).getOrThrow()

    val active = ConvosDao.listByStudent(session, student).getOrThrow()
    assertEquals(listOf(a.id, c.id), active.map { it.id })
  }

  @Test
  fun `listByStudent DELETED returns only soft-deleted convos`() {
    val student = createStudent()
    ConvosDao.create(session, newConvo(student, "active")).getOrThrow()
    val deleted = ConvosDao.create(session, newConvo(student, "gone")).getOrThrow()
    ConvosDao.delete(session, deleted.id).getOrThrow()

    val result = ConvosDao.listByStudent(session, student, SoftDeleteScope.DELETED).getOrThrow()
    assertEquals(listOf(deleted.id), result.map { it.id })
  }

  @Test
  fun `listByStudent ALL returns active and deleted convos in order`() {
    val student = createStudent()
    val a = ConvosDao.create(session, newConvo(student, "a")).getOrThrow()
    val b = ConvosDao.create(session, newConvo(student, "b")).getOrThrow()
    ConvosDao.delete(session, b.id).getOrThrow()

    val all = ConvosDao.listByStudent(session, student, SoftDeleteScope.ALL).getOrThrow()
    assertEquals(listOf(a.id, b.id), all.map { it.id })
  }

  @Test
  fun `rename updates the name and bumps updated_at`() {
    val student = createStudent()
    val convo = ConvosDao.create(session, newConvo(student, "old")).getOrThrow()
    val renamed = ConvosDao.rename(session, convo.id, name("new")).getOrThrow()
    assertEquals("new", renamed.name.value)
    assertTrue(renamed.updatedAt >= convo.updatedAt)
  }

  @Test
  fun `rename returns NotFoundException for a soft-deleted convo`() {
    val student = createStudent()
    val convo = ConvosDao.create(session, newConvo(student)).getOrThrow()
    ConvosDao.delete(session, convo.id).getOrThrow()
    val result = ConvosDao.rename(session, convo.id, name("new"))
    assertTrue(result.exceptionOrNull() is NotFoundException, "got $result")
  }

  @Test
  fun `rename returns NotFoundException for an absent convo`() {
    val result = ConvosDao.rename(session, ConvoId(UUID.randomUUID()), name("new"))
    assertTrue(result.exceptionOrNull() is NotFoundException, "got $result")
  }

  @Test
  fun `delete sets deleted_at and returns the convo`() {
    val student = createStudent()
    val convo = ConvosDao.create(session, newConvo(student)).getOrThrow()
    val deleted = ConvosDao.delete(session, convo.id).getOrThrow()
    assertNotNull(deleted.deletedAt)
  }

  @Test
  fun `delete returns NotFoundException for an already-deleted convo`() {
    val student = createStudent()
    val convo = ConvosDao.create(session, newConvo(student)).getOrThrow()
    ConvosDao.delete(session, convo.id).getOrThrow()
    val result = ConvosDao.delete(session, convo.id)
    assertTrue(result.exceptionOrNull() is NotFoundException, "got $result")
  }

  @Test
  fun `undelete clears deleted_at`() {
    val student = createStudent()
    val convo = ConvosDao.create(session, newConvo(student)).getOrThrow()
    ConvosDao.delete(session, convo.id).getOrThrow()
    val restored = ConvosDao.undelete(session, convo.id).getOrThrow()
    assertNull(restored.deletedAt)
  }

  @Test
  fun `undelete returns NotFoundException for an active convo`() {
    val student = createStudent()
    val convo = ConvosDao.create(session, newConvo(student)).getOrThrow()
    val result = ConvosDao.undelete(session, convo.id)
    assertTrue(result.exceptionOrNull() is NotFoundException, "got $result")
  }

  // ---------------------------------------------------------------------------
  // Logs — write
  // ---------------------------------------------------------------------------

  @Test
  fun `appendRequest inserts a row and round-trips content and request_params JSON`() {
    val student = createStudent()
    val convo = ConvosDao.create(session, newConvo(student)).getOrThrow()
    val content = json("""[{"type":"text","text":"hello world"}]""")
    val params = obj { put("temperature", 0.5) }
    val promptId = createSystemPrompt()
    val request =
      ConvosDao
        .appendRequest(session, newRequest(convo.id, systemPromptId = promptId, requestParams = params, content = content))
        .getOrThrow()

    assertEquals(content, request.content)
    assertEquals(params, request.requestParams)
    assertEquals(convo.id, request.convoId)
    assertEquals("anthropic", request.provider)
    assertEquals(promptId, request.systemPromptId)
  }

  @Test
  fun `appendRequest accepts a null request_params`() {
    val student = createStudent()
    val convo = ConvosDao.create(session, newConvo(student)).getOrThrow()
    val request =
      ConvosDao
        .appendRequest(session, newRequest(convo.id, requestParams = null))
        .getOrThrow()
    assertNull(request.requestParams)
  }

  @Test
  fun `appendRequest with an absent convo returns NotFoundException`() {
    val result = ConvosDao.appendRequest(session, newRequest(ConvoId(UUID.randomUUID())))
    assertTrue(result.exceptionOrNull() is NotFoundException, "got $result")
  }

  @Test
  fun `appendRequest with content over 1 MiB returns ConstraintViolationException`() {
    val student = createStudent()
    val convo = ConvosDao.create(session, newConvo(student)).getOrThrow()
    val big = "x".repeat(1_048_577)
    val content = json("""[{"type":"text","text":${Json.encodeToString(kotlinx.serialization.json.JsonPrimitive(big))}}]""")
    val result = ConvosDao.appendRequest(session, newRequest(convo.id, content = content))
    assertTrue(result.exceptionOrNull() is ConstraintViolationException, "got $result")
  }

  @Test
  fun `appendRequest with a non-allowlisted provider returns ConstraintViolationException`() {
    val student = createStudent()
    val convo = ConvosDao.create(session, newConvo(student)).getOrThrow()
    val result = ConvosDao.appendRequest(session, newRequest(convo.id, provider = "openai"))
    assertTrue(result.exceptionOrNull() is ConstraintViolationException, "got $result")
  }

  @Test
  fun `appendResponse with a raw payload writes both the response and the raw row`() {
    val student = createStudent()
    val convo = ConvosDao.create(session, newConvo(student)).getOrThrow()
    val request = appendRequestFor(convo.id)
    val raw = json("""{"id":"msg_1","role":"assistant"}""")
    val response = ConvosDao.appendResponse(session, successResponse(request.id, convo.id), raw).getOrThrow()

    val storedRaw = ConvosDao.findRawByResponseId(session, response.id).getOrThrow()
    assertEquals(raw, storedRaw.payload)
    assertEquals(response.id, storedRaw.responseId)
  }

  @Test
  fun `appendResponse without a raw payload writes only the response row`() {
    val student = createStudent()
    val convo = ConvosDao.create(session, newConvo(student)).getOrThrow()
    val request = appendRequestFor(convo.id)
    val response =
      ConvosDao
        .appendResponse(
          session,
          successResponse(request.id, convo.id, content = null, stopReason = "error", modelResolved = null),
          rawPayload = null,
        ).getOrThrow()

    assertNull(response.content)
    assertEquals("error", response.stopReason)
    assertTrue(ConvosDao.findRawByResponseId(session, response.id).exceptionOrNull() is NotFoundException)
  }

  @Test
  fun `appendResponse a second time for the same request_id returns ConstraintViolationException`() {
    val student = createStudent()
    val convo = ConvosDao.create(session, newConvo(student)).getOrThrow()
    val request = appendRequestFor(convo.id)
    ConvosDao.appendResponse(session, successResponse(request.id, convo.id), rawPayload = null).getOrThrow()
    val second = ConvosDao.appendResponse(session, successResponse(request.id, convo.id), rawPayload = null)
    assertTrue(second.exceptionOrNull() is ConstraintViolationException, "got $second")
  }

  @Test
  fun `appendResponse with null content and a non-error stop_reason returns ConstraintViolationException`() {
    val student = createStudent()
    val convo = ConvosDao.create(session, newConvo(student)).getOrThrow()
    val request = appendRequestFor(convo.id)
    val result =
      ConvosDao.appendResponse(
        session,
        successResponse(request.id, convo.id, content = null, stopReason = "end_turn", modelResolved = null),
        rawPayload = null,
      )
    assertTrue(result.exceptionOrNull() is ConstraintViolationException, "got $result")
  }

  @Test
  fun `appendResponse with an absent request returns NotFoundException`() {
    val student = createStudent()
    val convo = ConvosDao.create(session, newConvo(student)).getOrThrow()
    val result =
      ConvosDao.appendResponse(
        session,
        successResponse(
          ed.unicoach.db.models
            .ConvoRequestId(999_999L),
          convo.id,
        ),
        rawPayload = null,
      )
    assertTrue(result.exceptionOrNull() is NotFoundException, "got $result")
  }

  @Test
  fun `appendResponse with a negative token count returns ConstraintViolationException`() {
    val student = createStudent()
    val convo = ConvosDao.create(session, newConvo(student)).getOrThrow()
    val request = appendRequestFor(convo.id)
    val result =
      ConvosDao.appendResponse(
        session,
        successResponse(request.id, convo.id, inputTokens = -1),
        rawPayload = null,
      )
    assertTrue(result.exceptionOrNull() is ConstraintViolationException, "got $result")
  }

  @Test
  fun `appendResponse writes the response and its raw row atomically within a caller transaction`() {
    val student = createStudent()
    val convo = ConvosDao.create(session, newConvo(student)).getOrThrow()
    val request = appendRequestFor(convo.id)
    val raw = json("""{"id":"msg_atomic"}""")

    connection.autoCommit = false
    try {
      val response = ConvosDao.appendResponse(session, successResponse(request.id, convo.id), raw).getOrThrow()
      connection.commit()

      assertTrue(ConvosDao.findRawByResponseId(session, response.id).isSuccess)
      assertEquals(1, countResponses(request.id))
    } finally {
      connection.autoCommit = true
    }
  }

  @Test
  fun `appendResponse and its raw insert commit or roll back together in the caller transaction`() {
    val student = createStudent()
    val convo = ConvosDao.create(session, newConvo(student)).getOrThrow()
    val request = appendRequestFor(convo.id)

    // appendResponse runs the response insert and the raw insert inside the one
    // transaction the caller provides. If the raw insert fails the whole turn
    // must unwind, leaving no orphaned response row. We can't provoke a raw PK
    // collision from outside (a fresh response always gets a fresh, unoccupied
    // id, and the log triggers forbid deleting a response to reuse it), so we
    // prove the equivalent transactional binding directly: a successful
    // appendResponse followed by a caller rollback must erase both rows together.
    connection.autoCommit = false
    try {
      val response = ConvosDao.appendResponse(session, successResponse(request.id, convo.id), json("""{"r":1}""")).getOrThrow()
      assertEquals(1, countResponses(request.id))
      assertTrue(ConvosDao.findRawByResponseId(session, response.id).isSuccess)
      connection.rollback()
    } finally {
      connection.autoCommit = true
    }

    assertEquals(0, countResponses(request.id))
    assertTrue(ConvosDao.listTurns(session, convo.id, SoftDeleteScope.ALL).getOrThrow().all { it.response == null })
  }

  // ---------------------------------------------------------------------------
  // Logs — read
  // ---------------------------------------------------------------------------

  @Test
  fun `listTurns returns turns ordered by created_at then id, each request paired with its response`() {
    val student = createStudent()
    val convo = ConvosDao.create(session, newConvo(student)).getOrThrow()
    val r1 = appendRequestFor(convo.id)
    ConvosDao.appendResponse(session, successResponse(r1.id, convo.id), rawPayload = null).getOrThrow()
    val r2 = appendRequestFor(convo.id)
    ConvosDao.appendResponse(session, successResponse(r2.id, convo.id), rawPayload = null).getOrThrow()

    val turns = ConvosDao.listTurns(session, convo.id).getOrThrow()
    assertEquals(listOf(r1.id, r2.id), turns.map { it.request.id })
    assertTrue(turns.all { it.response != null })
    assertEquals(r1.id, turns[0].response!!.requestId)
  }

  @Test
  fun `listTurns yields a null response for a request whose response has not been written`() {
    val student = createStudent()
    val convo = ConvosDao.create(session, newConvo(student)).getOrThrow()
    val r1 = appendRequestFor(convo.id)

    val turns = ConvosDao.listTurns(session, convo.id).getOrThrow()
    assertEquals(1, turns.size)
    assertEquals(r1.id, turns[0].request.id)
    assertNull(turns[0].response)
  }

  @Test
  fun `listTurns ACTIVE excludes the turns of a soft-deleted convo`() {
    val student = createStudent()
    val convo = ConvosDao.create(session, newConvo(student)).getOrThrow()
    appendRequestFor(convo.id)
    ConvosDao.delete(session, convo.id).getOrThrow()

    assertTrue(ConvosDao.listTurns(session, convo.id).getOrThrow().isEmpty())
  }

  @Test
  fun `listTurns DELETED returns only a soft-deleted convo's turns`() {
    val student = createStudent()
    val convo = ConvosDao.create(session, newConvo(student)).getOrThrow()
    val r1 = appendRequestFor(convo.id)
    ConvosDao.delete(session, convo.id).getOrThrow()

    val turns = ConvosDao.listTurns(session, convo.id, SoftDeleteScope.DELETED).getOrThrow()
    assertEquals(listOf(r1.id), turns.map { it.request.id })
  }

  @Test
  fun `listTurns ALL returns turns regardless of convo deletion`() {
    val student = createStudent()
    val convo = ConvosDao.create(session, newConvo(student)).getOrThrow()
    val r1 = appendRequestFor(convo.id)
    ConvosDao.delete(session, convo.id).getOrThrow()

    val turns = ConvosDao.listTurns(session, convo.id, SoftDeleteScope.ALL).getOrThrow()
    assertEquals(listOf(r1.id), turns.map { it.request.id })
  }

  @Test
  fun `findRawByResponseId returns the stored payload`() {
    val student = createStudent()
    val convo = ConvosDao.create(session, newConvo(student)).getOrThrow()
    val request = appendRequestFor(convo.id)
    val raw = json("""{"verbatim":true}""")
    val response = ConvosDao.appendResponse(session, successResponse(request.id, convo.id), raw).getOrThrow()

    assertEquals(raw, ConvosDao.findRawByResponseId(session, response.id).getOrThrow().payload)
  }

  @Test
  fun `findRawByResponseId returns NotFoundException for an absent response id`() {
    val result = ConvosDao.findRawByResponseId(session, ConvoResponseId(999_999L))
    assertTrue(result.exceptionOrNull() is NotFoundException, "got $result")
  }

  // ---------------------------------------------------------------------------
  // Raw SQL helpers for atomicity tests
  // ---------------------------------------------------------------------------

  private fun countResponses(requestId: ed.unicoach.db.models.ConvoRequestId): Int {
    connection.prepareStatement("SELECT COUNT(*) FROM convo_responses WHERE request_id = ?").use { stmt ->
      stmt.setLong(1, requestId.value)
      stmt.executeQuery().use { rs ->
        rs.next()
        return rs.getInt(1)
      }
    }
  }
}
