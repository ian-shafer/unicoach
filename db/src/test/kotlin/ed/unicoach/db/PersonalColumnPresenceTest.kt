package ed.unicoach.db

import ed.unicoach.db.dao.StudentRowFixture
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Presence while live (RFC 263 §4, brief 0002 standing D7).
 *
 * Every pii.* column is nullable, so erasure can clear it. What keeps a LIVE
 * row from holding a NULL there is a per-column CHECK -- and, where a value's
 * presence was already tied to another column, an erased arm on that rule.
 * Each test drives the real constraint: refused while `deleted_at` is NULL,
 * admitted once it is set.
 */
class PersonalColumnPresenceTest {
  companion object {
    private lateinit var connection: Connection

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf")
          .getOrThrow()
      val dbConfig = DatabaseConfig.from(config).getOrThrow()
      connection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::connection.isInitialized && !connection.isClosed) connection.close()
    }

    /** PostgreSQL's SQLState for a CHECK violation. */
    private const val CHECK_VIOLATION = "23514"
  }

  private fun execute(
    sql: String,
    vararg params: Any?,
  ): Int =
    connection.prepareStatement(sql).use { stmt ->
      params.forEachIndexed { i, p -> stmt.setObject(i + 1, p) }
      stmt.executeUpdate()
    }

  /** Runs [sql] and asserts it is refused as a CHECK violation by one of [constraints]. */
  private fun assertRefusedBy(
    constraints: List<String>,
    sql: String,
    vararg params: Any?,
  ) {
    val refused = assertFailsWith<SQLException> { execute(sql, *params) }
    assertEquals(CHECK_VIOLATION, refused.sqlState, "expected a CHECK violation, got: ${refused.message}")
    assertTrue(
      constraints.any { refused.message.orEmpty().contains("\"$it\"") },
      "expected a refusal by one of $constraints, got: ${refused.message}",
    )
  }

  private fun user(): UUID {
    val id = UUID.randomUUID()
    execute("INSERT INTO users (id, email, name) VALUES (?, ?, ?)", id, "presence-$id@example.com", "Presence User")
    return id
  }

  private fun student(): UUID {
    val id = UUID.randomUUID()
    StudentRowFixture.insert(connection, id, user())
    return id
  }

  private fun llmRequest(): Long =
    connection
      .prepareStatement(
        "INSERT INTO llm_requests (provider, model_requested, content, max_tokens) " +
          "VALUES ('log', 'm', '[]'::jsonb, 1) RETURNING id",
      ).use { stmt ->
        stmt.executeQuery().use { rs ->
          rs.next()
          rs.getLong(1)
        }
      }

  private fun systemPromptId(): UUID =
    connection.prepareStatement("SELECT id FROM system_prompts LIMIT 1").use { stmt ->
      stmt.executeQuery().use { rs ->
        assertTrue(rs.next(), "the migrations seed at least one system prompt")
        rs.getObject(1, UUID::class.java)
      }
    }

  @Test
  fun `a live user cannot lose their name`() {
    val id = user()
    assertRefusedBy(
      listOf("users_name_present_while_live_check"),
      "UPDATE users SET name = NULL, version = version + 1 WHERE id = ?",
      id,
    )
  }

  @Test
  fun `a deleted user can lose every personal column`() {
    val id = user()
    execute("UPDATE users SET deleted_at = NOW(), version = version + 1 WHERE id = ?", id)
    execute(
      "UPDATE users SET email = NULL, name = NULL, display_name = NULL, password_hash = NULL, " +
        "version = version + 1 WHERE id = ?",
      id,
    )

    // The update wrote a history row with the same NULLs: the _versions
    // table's own presence CHECK admits it because deleted_at rode along.
    connection
      .prepareStatement(
        "SELECT email IS NULL AND name IS NULL AND display_name IS NULL AND password_hash IS NULL " +
          "AND deleted_at IS NOT NULL FROM users_versions WHERE id = ? ORDER BY version DESC LIMIT 1",
      ).use { stmt ->
        stmt.setObject(1, id)
        stmt.executeQuery().use { rs ->
          assertTrue(rs.next(), "the erasing update must write a users_versions row")
          assertTrue(rs.getBoolean(1), "the latest history row must carry the NULLs and the deleted_at")
        }
      }
    connection.prepareStatement("SELECT email IS NULL AND name IS NULL FROM users WHERE id = ?").use { stmt ->
      stmt.setObject(1, id)
      stmt.executeQuery().use { rs ->
        assertTrue(rs.next())
        assertTrue(rs.getBoolean(1), "the live table holds the NULLs")
      }
    }
  }

  @Test
  fun `a live user cannot be given an empty email`() {
    // No empty string is ever the erased value. users.email carries four format
    // rules and PostgreSQL reports the first by name, which for '' is the format
    // rule rather than not-empty; either refusal proves '' cannot be stored.
    val id = UUID.randomUUID()
    assertRefusedBy(
      listOf("users_email_not_empty_check", "users_email_format_check"),
      "INSERT INTO users (id, email, name) VALUES (?, '', 'Empty Email')",
      id,
    )
    // name has no rule ahead of not-empty, so its refusal names it exactly.
    assertRefusedBy(
      listOf("users_name_not_empty_check"),
      "INSERT INTO users (id, email, name) VALUES (?, ?, '')",
      id,
      "empty-name-$id@example.com",
    )
  }

  @Test
  fun `a completed response keeps its content while live, and loses it when deleted`() {
    val insert =
      "INSERT INTO llm_responses (request_id, outcome, content, model_resolved, stop_reason, latency_ms, deleted_at) " +
        "VALUES (?, 'completed', NULL, 'm', 'end_turn', 1, ?)"
    assertRefusedBy(listOf("llm_responses_completed_content_check"), insert, llmRequest(), null)
    assertEquals(1, execute(insert, llmRequest(), java.sql.Timestamp(System.currentTimeMillis())))
  }

  @Test
  fun `an answered money question keeps its answer while live, and loses it when deleted`() {
    val insert =
      "INSERT INTO money_profiles (student_id, income_band_status, income_band, deleted_at) " +
        "VALUES (?, 'answered', NULL, ?)"
    assertRefusedBy(listOf("money_profiles_income_band_value_iff_answered_check"), insert, student(), null)
    assertEquals(1, execute(insert, student(), java.sql.Timestamp(System.currentTimeMillis())))
  }

  @Test
  fun `a failed run keeps its reason while live, and loses it when deleted`() {
    val prompt = systemPromptId()
    val insert =
      "INSERT INTO fit_lens_runs (student_id, outcome, query_system_prompt_id, reason_system_prompt_id, " +
        "suggestions_written, failure_category, failure_reason, query_llm_request_id, deleted_at) " +
        "VALUES (?, 'failed', ?, ?, 0, 'malformed_output', NULL, ?, ?)"
    // Two rules tie the reason to the run; PostgreSQL names the first it checks.
    assertRefusedBy(
      listOf("fit_lens_runs_failure_consistency_check", "fit_lens_runs_failure_pairing_check"),
      insert,
      student(),
      prompt,
      prompt,
      llmRequest(),
      null,
    )
    assertEquals(1, execute(insert, student(), prompt, prompt, llmRequest(), java.sql.Timestamp(System.currentTimeMillis())))
  }

  @Test
  fun `a deleted run that lost its reason still ties its outcome to its category`() {
    // Erasure frees the reason only: a deleted run with no reason is still failed
    // exactly when it carries a category, in both directions.
    val prompt = systemPromptId()
    val insert =
      "INSERT INTO fit_lens_runs (student_id, outcome, query_system_prompt_id, reason_system_prompt_id, " +
        "suggestions_written, failure_category, failure_reason, query_llm_request_id, deleted_at) " +
        "VALUES (?, ?, ?, ?, 0, ?, NULL, ?, NOW())"
    assertRefusedBy(
      listOf("fit_lens_runs_failure_consistency_check"),
      insert,
      student(),
      "failed",
      prompt,
      prompt,
      null,
      llmRequest(),
    )
    assertRefusedBy(
      listOf("fit_lens_runs_failure_consistency_check"),
      insert,
      student(),
      "applied",
      prompt,
      prompt,
      "malformed_output",
      llmRequest(),
    )
  }
}
