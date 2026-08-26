package ed.unicoach.fixture

import ed.unicoach.db.Database
import ed.unicoach.util.Argon2Hasher
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorldApplierTest {
  companion object {
    private lateinit var connection: Connection
    private lateinit var database: Database

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
      database = Database(dbConfig)
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::database.isInitialized) {
        database.close()
      }
      if (::connection.isInitialized && !connection.isClosed) {
        connection.close()
      }
    }
  }

  @BeforeEach
  fun resetDatabase() {
    connection.createStatement().use { stmt ->
      stmt.execute("TRUNCATE TABLE users CASCADE")
    }
  }

  private val applier get() = WorldApplier(database)

  private data class UserRow(
    val email: String,
    val name: String,
    val isAdmin: Boolean,
    val emailVerifiedAt: java.sql.Timestamp?,
    val passwordHash: String,
  )

  private fun findRow(email: String): UserRow? {
    connection
      .prepareStatement(
        "SELECT email, name, is_admin, email_verified_at, password_hash FROM users WHERE email = ? AND deleted_at IS NULL",
      ).use { stmt ->
        stmt.setString(1, email)
        stmt.executeQuery().use { rs ->
          if (!rs.next()) return null
          return UserRow(
            email = rs.getString("email"),
            name = rs.getString("name"),
            isAdmin = rs.getBoolean("is_admin"),
            emailVerifiedAt = rs.getTimestamp("email_verified_at"),
            passwordHash = rs.getString("password_hash"),
          )
        }
      }
  }

  private fun activeUserCount(): Int {
    connection.createStatement().use { stmt ->
      stmt.executeQuery("SELECT COUNT(*) FROM users WHERE deleted_at IS NULL").use { rs ->
        rs.next()
        return rs.getInt(1)
      }
    }
  }

  @Test
  fun `happy path creates users with expected fields and verification state`() {
    val world =
      WorldFile.parse(
        """
        users:
          - email: alpha@example.com
            password: Hunter2hunter2
            name: Alpha One
          - email: beta@example.com
            password: Hunter2hunter2
            verified: false
            admin: true
        """.trimIndent(),
      )

    val result = runBlocking { applier.apply(world) }
    assertEquals(2, result.users.size)

    val alpha = assertNotNull(findRow("alpha@example.com"))
    assertEquals("Alpha One", alpha.name)
    assertTrue(!alpha.isAdmin)
    assertNotNull(alpha.emailVerifiedAt, "verified (default true) must set email_verified_at")

    val beta = assertNotNull(findRow("beta@example.com"))
    assertEquals("beta", beta.name, "name must default to the email local part")
    assertTrue(beta.isAdmin)
    assertNull(beta.emailVerifiedAt, "verified: false must leave email_verified_at NULL")
  }

  @Test
  fun `created password hash verifies against the plaintext`() {
    val world =
      WorldFile.parse(
        """
        users:
          - email: roundtrip@example.com
            password: Hunter2hunter2
        """.trimIndent(),
      )

    runBlocking { applier.apply(world) }

    val row = assertNotNull(findRow("roundtrip@example.com"))
    val verifies = runBlocking { Argon2Hasher().verify(row.passwordHash, "Hunter2hunter2") }
    assertTrue(verifies, "The stored argon2 hash must verify against the declared plaintext")
  }

  @Test
  fun `a password signup would reject is rejected with field errors`() {
    val world =
      WorldFile.parse(
        """
        users:
          - email: short@example.com
            password: aB1
        """.trimIndent(),
      )

    val error = assertThrows<WorldApplyException> { runBlocking { applier.apply(world) } }
    assertTrue(
      error.message!!.contains("short@example.com") && error.message!!.contains("password"),
      "Error must name the user and the failing field, was: ${error.message}",
    )
    assertEquals(0, activeUserCount(), "Nothing may be created for an invalid world")
  }

  @Test
  fun `duplicate active email names the email and rolls back the whole world`() {
    // Pre-existing user the world collides with.
    runBlocking {
      applier.apply(
        WorldFile.parse(
          """
          users:
            - email: existing@example.com
              password: Hunter2hunter2
          """.trimIndent(),
        ),
      )
    }
    assertEquals(1, activeUserCount())

    val world =
      WorldFile.parse(
        """
        users:
          - email: fresh@example.com
            password: Hunter2hunter2
          - email: existing@example.com
            password: Hunter2hunter2
        """.trimIndent(),
      )

    val error = assertThrows<WorldApplyException> { runBlocking { applier.apply(world) } }
    assertTrue(error.message!!.contains("existing@example.com"), "Error must name the duplicate email")
    assertTrue(error.message!!.contains("-f"), "Error must suggest -f")
    assertNull(findRow("fresh@example.com"), "The earlier valid user must be rolled back")
    assertEquals(1, activeUserCount(), "Only the pre-existing user may remain")
  }

  @Test
  fun `re-applying the same world is a duplicate error`() {
    val world =
      WorldFile.parse(
        """
        users:
          - email: once@example.com
            password: Hunter2hunter2
        """.trimIndent(),
      )

    runBlocking { applier.apply(world) }
    val error = assertThrows<WorldApplyException> { runBlocking { applier.apply(world) } }
    assertTrue(error.message!!.contains("once@example.com"), "Create-only: re-apply must fail naming the email")
    assertEquals(1, activeUserCount())
  }

  @Test
  fun `an empty world applies as a no-op`() {
    val result = runBlocking { applier.apply(WorldFile()) }
    assertTrue(result.users.isEmpty())
    assertEquals(0, activeUserCount())
  }
}
