package ed.unicoach.db.dao

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises [SystemPromptsDao] against the seeded coach/v1 row (migration
 * 0011). The migrated DB always carries the seed, so this reads it rather than
 * inserting (system_prompts is migration-authored only).
 */
class SystemPromptsDaoTest {
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

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  /**
   * Re-asserts the migration 0011 seed before each case. A sibling DAO test
   * (ConvosDaoTest) truncates system_prompts in its own setup, and JVM test
   * ordering is unspecified, so this case cannot assume the seed survives.
   * The insert mirrors 0011 exactly and is a no-op when the row is present.
   */
  @org.junit.jupiter.api.BeforeEach
  fun ensureSeed() {
    connection.autoCommit = true
    connection
      .prepareStatement(
        """
        INSERT INTO system_prompts (name, version, body)
        VALUES ('coach', 'v1', 'You are Uni, a warm coach.')
        ON CONFLICT (name, version) DO NOTHING
        """.trimIndent(),
      ).use { it.executeUpdate() }
  }

  @Test
  fun `findByNameAndVersion returns the seeded coach prompt`() {
    val prompt = SystemPromptsDao.findByNameAndVersion(session, "coach", "v1").getOrThrow()
    assertEquals("coach", prompt.name)
    assertEquals("v1", prompt.version)
    assertTrue(prompt.body.isNotBlank(), "seeded body must be non-empty")
  }

  @Test
  fun `findByNameAndVersion fails on an unknown pair`() {
    val result = SystemPromptsDao.findByNameAndVersion(session, "coach", "does-not-exist")
    assertTrue(result.exceptionOrNull() is NotFoundException, "got $result")
  }
}
