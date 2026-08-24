package ed.unicoach.db.dao

import ed.unicoach.db.models.NewSystemPrompt
import ed.unicoach.db.models.SystemPromptId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
   * Reads the migration 0011 seed directly. No fixture seeds or truncates
   * system_prompts: it is a migration-seeded, immutable catalog (RFC 33/0007)
   * and bin/test re-migrates before every run, so the row is simply there
   * (RFC 129).
   */
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

  /** A unique name suffix per test, so cases never collide on (name, version). */
  private fun uniqueName(prefix: String): String = "rfc63-$prefix-${UUID.randomUUID()}"

  @Test
  fun `create round-trips the submitted columns`() {
    val name = uniqueName("a")
    val created =
      SystemPromptsDao
        .create(session, NewSystemPrompt(name, "v1", "Body A"))
        .getOrThrow()
    assertEquals(name, created.name)
    assertEquals("v1", created.version)
    assertEquals("Body A", created.body)
    assertNotNull(created.id, "create must return a generated id")
    assertNotNull(created.createdAt, "create must return a populated createdAt")
  }

  @Test
  fun `findById returns the created row and fails for an unknown id`() {
    val name = uniqueName("find")
    val created =
      SystemPromptsDao
        .create(session, NewSystemPrompt(name, "v1", "Findable body"))
        .getOrThrow()

    val found = SystemPromptsDao.findById(session, created.id).getOrThrow()
    assertEquals(created, found)

    val missing = SystemPromptsDao.findById(session, SystemPromptId(UUID.randomUUID()))
    assertTrue(missing.exceptionOrNull() is NotFoundException, "got $missing")
  }

  @Test
  fun `list orders inserted rows by name then version`() {
    // One family name shared across versions, plus a sibling family, so the
    // assertion is over relative order of these three inserted rows only.
    val nameA = uniqueName("list-a")
    val nameB = uniqueName("list-b")
    val a1 = SystemPromptsDao.create(session, NewSystemPrompt(nameA, "v1", "A1")).getOrThrow()
    val a2 = SystemPromptsDao.create(session, NewSystemPrompt(nameA, "v2", "A2")).getOrThrow()
    val b1 = SystemPromptsDao.create(session, NewSystemPrompt(nameB, "v1", "B1")).getOrThrow()

    // Page the full catalog until exhausted, tolerating the seed and foreign rows.
    val all = mutableListOf<SystemPromptId>()
    val limit = 100
    var offset = 0
    while (true) {
      val page = SystemPromptsDao.list(session, limit, offset).getOrThrow()
      all.addAll(page.map { it.id })
      if (page.size < limit) break
      offset += limit
    }

    val inserted = setOf(a1.id, a2.id, b1.id)
    val relativeOrder = all.filter { it in inserted }
    assertEquals(
      listOf(a1.id, a2.id, b1.id),
      relativeOrder,
      "list must order by name ASC then version ASC over the inserted rows",
    )
  }

  @Test
  fun `create with a duplicate name-version fails as ConstraintViolationException`() {
    val name = uniqueName("dup")
    SystemPromptsDao.create(session, NewSystemPrompt(name, "v1", "Original")).getOrThrow()
    val second = SystemPromptsDao.create(session, NewSystemPrompt(name, "v1", "Duplicate"))
    assertTrue(
      second.exceptionOrNull() is ConstraintViolationException,
      "duplicate (name, version) must map 23505 → ConstraintViolationException, got $second",
    )
  }

  @Test
  fun `create with a blank body fails the CHECK as ConstraintViolationException`() {
    val name = uniqueName("check")
    val result = SystemPromptsDao.create(session, NewSystemPrompt(name, "v1", "   "))
    assertTrue(
      result.exceptionOrNull() is ConstraintViolationException,
      "blank body must map the 23514 CHECK violation → ConstraintViolationException, got $result",
    )
  }
}
