package ed.unicoach.db.dao

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Exercises the `student_llm_token_usage` view (db/schema/0040): the read-only
 * per-student token ledger that unions the four per-call owners (chat's
 * convo_requests, extraction_runs, synthesis_runs, and fit_lens_runs' two ids),
 * each joined FROM the domain owner TO llm_responses via the UNIQUE request_id.
 *
 * The view carries no Kotlin reader (RFC 106 ships none), so it is queried with
 * plain JDBC. The cases pin the three properties the union is designed to give:
 * every owner is summed, an owner-less orphan call is excluded rather than
 * misattributed, and a soft-deleted convo's genuinely-billed spend is still
 * counted.
 */
class StudentLlmTokenUsageViewTest {
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
      stmt.execute(
        "TRUNCATE TABLE convos, convo_requests, extraction_runs, synthesis_runs, fit_lens_runs, fit_suggestions, " +
          "llm_requests, llm_responses, llm_responses_raw, colleges, system_prompts, students, users CASCADE",
      )
      // Restore the migration-seeded prompts for cross-module suites on the shared DB.
      stmt.execute("INSERT INTO system_prompts (name, version, body) VALUES ('coach', 'v1', 'You are Uni, a warm coach.')")
      stmt.execute("INSERT INTO system_prompts (name, version, body) VALUES ('extraction', 'v1', 'distill the transcript')")
      stmt.execute("INSERT INTO system_prompts (name, version, body) VALUES ('synthesis', 'v1', 'reflect over the model')")
    }
  }

  private var promptCounter = 0

  private fun createStudent(): UUID {
    val userId = UUID.randomUUID()
    val studentId = UUID.randomUUID()
    connection.createStatement().use { stmt ->
      stmt.execute("INSERT INTO users (id, email, name, password_hash) VALUES ('$userId', 'tuv-$userId@test.com', 'TUV User', 'ahash')")
      stmt.execute(
        "INSERT INTO students (id, user_id, expected_high_school_graduation_year) VALUES ('$studentId', '$userId', 2028)",
      )
    }
    return studentId
  }

  private fun createSystemPrompt(): UUID {
    val id = UUID.randomUUID()
    connection.prepareStatement("INSERT INTO system_prompts (id, name, version, body) VALUES (?, 'extraction', ?, 'distill')").use { stmt ->
      stmt.setObject(1, id)
      stmt.setString(2, "p${promptCounter++}")
      stmt.executeUpdate()
    }
    return id
  }

  /** Insert an llm_requests row and its 1:1 completed llm_responses row carrying the given token counts; returns the request id. */
  private fun appendCall(
    input: Int,
    output: Int,
    cacheRead: Int,
    cacheWrite: Int,
  ): Long {
    val requestId =
      connection
        .prepareStatement(
          "INSERT INTO llm_requests (provider, model_requested, content, max_tokens) VALUES ('anthropic', 'claude-opus-4-8', '[]'::jsonb, 1024) RETURNING id",
        ).use { stmt ->
          stmt.executeQuery().use { rs ->
            rs.next()
            rs.getLong("id")
          }
        }
    connection
      .prepareStatement(
        """
        INSERT INTO llm_responses
          (request_id, outcome, content, model_resolved, stop_reason, input_tokens, output_tokens, cache_read_tokens, cache_write_tokens, latency_ms)
        VALUES (?, 'completed', '[]'::jsonb, 'm', 'end_turn', ?, ?, ?, ?, 1)
        """.trimIndent(),
      ).use { stmt ->
        stmt.setLong(1, requestId)
        stmt.setInt(2, input)
        stmt.setInt(3, output)
        stmt.setInt(4, cacheRead)
        stmt.setInt(5, cacheWrite)
        stmt.executeUpdate()
      }
    return requestId
  }

  private fun createConvo(studentId: UUID): UUID {
    val convoId = UUID.randomUUID()
    connection.prepareStatement("INSERT INTO convos (id, student_id, name) VALUES (?, ?, 'Convo')").use { stmt ->
      stmt.setObject(1, convoId)
      stmt.setObject(2, studentId)
      stmt.executeUpdate()
    }
    return convoId
  }

  /** Insert a convo_requests row referencing [llmRequestId]; returns its id (an extraction watermark target). */
  private fun appendConvoRequest(
    convoId: UUID,
    llmRequestId: Long,
  ): Long {
    val promptId = createSystemPrompt()
    connection
      .prepareStatement(
        """
        INSERT INTO convo_requests (convo_id, system_prompt_id, llm_request_id, turn_id)
        VALUES (?, ?, ?, nextval('convo_turn_id_seq')) RETURNING id
        """.trimIndent(),
      ).use { stmt ->
        stmt.setObject(1, convoId)
        stmt.setObject(2, promptId)
        stmt.setLong(3, llmRequestId)
        stmt.executeQuery().use { rs ->
          rs.next()
          return rs.getLong("id")
        }
      }
  }

  private fun appendExtractionRun(
    convoId: UUID,
    studentId: UUID,
    throughRequestId: Long,
    llmRequestId: Long,
  ) {
    connection
      .prepareStatement(
        """
        INSERT INTO extraction_runs (convo_id, student_id, through_request_id, outcome, system_prompt_id, llm_request_id)
        VALUES (?, ?, ?, 'applied', ?, ?)
        """.trimIndent(),
      ).use { stmt ->
        stmt.setObject(1, convoId)
        stmt.setObject(2, studentId)
        stmt.setLong(3, throughRequestId)
        stmt.setObject(4, createSystemPrompt())
        stmt.setLong(5, llmRequestId)
        stmt.executeUpdate()
      }
  }

  private fun appendSynthesisRun(
    studentId: UUID,
    llmRequestId: Long,
  ) {
    connection
      .prepareStatement(
        "INSERT INTO synthesis_runs (student_id, outcome, system_prompt_id, llm_request_id) VALUES (?, 'applied', ?, ?)",
      ).use { stmt ->
        stmt.setObject(1, studentId)
        stmt.setObject(2, createSystemPrompt())
        stmt.setLong(3, llmRequestId)
        stmt.executeUpdate()
      }
  }

  private fun appendFitLensRun(
    studentId: UUID,
    queryLlmRequestId: Long,
    reasonLlmRequestId: Long,
  ) {
    connection
      .prepareStatement(
        """
        INSERT INTO fit_lens_runs
          (student_id, outcome, query_system_prompt_id, reason_system_prompt_id, query_llm_request_id, reason_llm_request_id, suggestions_written, matches_considered)
        VALUES (?, 'applied', ?, ?, ?, ?, 0, 0)
        """.trimIndent(),
      ).use { stmt ->
        stmt.setObject(1, studentId)
        stmt.setObject(2, createSystemPrompt())
        stmt.setObject(3, createSystemPrompt())
        stmt.setLong(4, queryLlmRequestId)
        stmt.setLong(5, reasonLlmRequestId)
        stmt.executeUpdate()
      }
  }

  private data class Tokens(
    val input: Int,
    val output: Int,
    val cacheRead: Int,
    val cacheWrite: Int,
  )

  /** Reads the view row for [studentId]; a student with no attributed spend has no row (Tokens all-zero). */
  private fun usageFor(studentId: UUID): Tokens {
    connection
      .prepareStatement(
        "SELECT input_tokens, output_tokens, cache_read_tokens, cache_write_tokens FROM student_llm_token_usage WHERE student_id = ?",
      ).use { stmt ->
        stmt.setObject(1, studentId)
        stmt.executeQuery().use { rs ->
          if (!rs.next()) return Tokens(0, 0, 0, 0)
          return Tokens(
            rs.getInt("input_tokens"),
            rs.getInt("output_tokens"),
            rs.getInt("cache_read_tokens"),
            rs.getInt("cache_write_tokens"),
          )
        }
      }
  }

  @Test
  fun `the view sums a student's spend across all four call owners`() {
    val studentA = createStudent()

    // Chat: convo + convo_requests call.
    val convo = createConvo(studentA)
    val chatCall = appendCall(input = 10, output = 1, cacheRead = 100, cacheWrite = 1000)
    val throughRequest = appendConvoRequest(convo, chatCall)

    // Extraction call (watermarked at the chat request above).
    val extractionCall = appendCall(input = 20, output = 2, cacheRead = 200, cacheWrite = 2000)
    appendExtractionRun(convo, studentA, throughRequest, extractionCall)

    // Synthesis call.
    val synthesisCall = appendCall(input = 40, output = 4, cacheRead = 400, cacheWrite = 4000)
    appendSynthesisRun(studentA, synthesisCall)

    // Fit-lens pair (query + reason call).
    val queryCall = appendCall(input = 80, output = 8, cacheRead = 800, cacheWrite = 8000)
    val reasonCall = appendCall(input = 160, output = 16, cacheRead = 1600, cacheWrite = 16000)
    appendFitLensRun(studentA, queryCall, reasonCall)

    // All five calls (across four owners) sum into the one student row.
    assertEquals(
      Tokens(
        input = 10 + 20 + 40 + 80 + 160,
        output = 1 + 2 + 4 + 8 + 16,
        cacheRead = 100 + 200 + 400 + 800 + 1600,
        cacheWrite = 1000 + 2000 + 4000 + 8000 + 16000,
      ),
      usageFor(studentA),
    )
  }

  @Test
  fun `a fit-lens pair's tokens attribute to its own student`() {
    val studentA = createStudent()
    val studentB = createStudent()

    val queryA = appendCall(input = 5, output = 1, cacheRead = 0, cacheWrite = 0)
    val reasonA = appendCall(input = 7, output = 1, cacheRead = 0, cacheWrite = 0)
    appendFitLensRun(studentA, queryA, reasonA)

    val queryB = appendCall(input = 11, output = 2, cacheRead = 3, cacheWrite = 4)
    val reasonB = appendCall(input = 13, output = 2, cacheRead = 5, cacheWrite = 6)
    appendFitLensRun(studentB, queryB, reasonB)

    // Student B's pair lands wholly on student B, not leaking to A.
    assertEquals(Tokens(input = 5 + 7, output = 1 + 1, cacheRead = 0, cacheWrite = 0), usageFor(studentA))
    assertEquals(Tokens(input = 11 + 13, output = 2 + 2, cacheRead = 3 + 5, cacheWrite = 4 + 6), usageFor(studentB))
  }

  @Test
  fun `an orphan call referenced by no owner is excluded from every total`() {
    val studentA = createStudent()

    // An attributed synthesis call so the student has a row at all.
    val synthesisCall = appendCall(input = 100, output = 10, cacheRead = 0, cacheWrite = 0)
    appendSynthesisRun(studentA, synthesisCall)

    // A crash-window orphan: a logged call no domain owner references.
    appendCall(input = 999, output = 999, cacheRead = 999, cacheWrite = 999)

    // The orphan contributes to no student — the ledger undercounts it rather than misattributing.
    assertEquals(Tokens(input = 100, output = 10, cacheRead = 0, cacheWrite = 0), usageFor(studentA))
  }

  @Test
  fun `a soft-deleted convo's genuinely-billed spend is still counted`() {
    val studentA = createStudent()

    val convo = createConvo(studentA)
    val chatCall = appendCall(input = 50, output = 5, cacheRead = 0, cacheWrite = 0)
    appendConvoRequest(convo, chatCall)

    // Soft-delete the convo: its partial spend was genuinely billed, so the view's
    // convo_requests -> convos join deliberately ignores convos.deleted_at.
    connection.prepareStatement("UPDATE convos SET deleted_at = NOW() WHERE id = ?").use { stmt ->
      stmt.setObject(1, convo)
      stmt.executeUpdate()
    }

    assertEquals(Tokens(input = 50, output = 5, cacheRead = 0, cacheWrite = 0), usageFor(studentA))
  }
}
