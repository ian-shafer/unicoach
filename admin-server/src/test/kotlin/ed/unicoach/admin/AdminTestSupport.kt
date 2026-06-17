package ed.unicoach.admin

import ed.unicoach.auth.AuthService
import ed.unicoach.common.config.AppConfig
import ed.unicoach.common.models.EmailAddress
import ed.unicoach.common.models.ValidationResult
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.StudentsDao
import ed.unicoach.db.dao.UsersDao
import ed.unicoach.db.models.AuthMethod
import ed.unicoach.db.models.NewStudent
import ed.unicoach.db.models.NewUser
import ed.unicoach.db.models.PartialDate
import ed.unicoach.db.models.PasswordHash
import ed.unicoach.db.models.PersonName
import ed.unicoach.db.models.Student
import ed.unicoach.db.models.User
import ed.unicoach.db.models.UserId
import ed.unicoach.util.Argon2Hasher
import ed.unicoach.util.TokenGenerator
import io.ktor.server.application.Application
import kotlinx.coroutines.runBlocking
import java.sql.DriverManager

/**
 * Shared scaffolding for admin-server tests: a real test-DB-backed Database,
 * AuthService, and the configured admin module, plus user/student seeders. The
 * test DB is reset to a clean migrated state by `bin/test` before the suite.
 */
object AdminTestSupport {
  val config =
    AppConfig
      .load("common.conf", "db.conf", "admin-server.conf")
      .getOrThrow()

  private val dbConfig = DatabaseConfig.from(config).getOrThrow()
  val adminConfig = AdminConfig.from(config).getOrThrow()

  val database = Database(dbConfig)
  val argon2Hasher = Argon2Hasher()
  val authService = AuthService(database, argon2Hasher, TokenGenerator())

  fun Application.installTestAdminModule() {
    adminModule(database, authService, argon2Hasher, adminConfig)
  }

  /** Truncate users (cascades to sessions/students) for an isolated test. */
  fun resetDatabase() {
    DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "").use { conn ->
      conn.createStatement().use { it.execute("TRUNCATE TABLE users CASCADE") }
    }
  }

  fun seedUser(
    email: String,
    password: String = "Password123!",
    name: String = "Test User",
    isAdmin: Boolean = false,
  ): User =
    runBlocking {
      val hash = argon2Hasher.hash(password)
      val newUser =
        NewUser(
          email = (EmailAddress.create(email) as ValidationResult.Valid).value,
          name = (PersonName.create(name) as ValidationResult.Valid).value,
          displayName = null,
          authMethod = AuthMethod.Password((PasswordHash.create(hash) as ValidationResult.Valid).value),
          isAdmin = isAdmin,
        )
      database.withConnection { session -> UsersDao.create(session, newUser) }.getOrThrow()
    }

  fun seedStudent(
    userId: UserId,
    gradIso: String = "2028",
  ): Student =
    runBlocking {
      val date = (PartialDate.parse(gradIso) as ValidationResult.Valid).value
      database.withConnection { session -> StudentsDao.create(session, NewStudent(userId, date)) }.getOrThrow()
    }

  /** Logs in and returns the raw session cookie value for the admin session cookie. */
  fun login(
    email: String,
    password: String,
  ): String =
    runBlocking {
      val result =
        authService
          .login(
            email = email,
            password = password,
            oldCookieToken = null,
            sessionExpirationSeconds = adminConfig.sessionExpirationSeconds,
            userAgent = "test",
            initialIp = "127.0.0.1",
          ).getOrThrow()
      (result as ed.unicoach.auth.LoginResult.Success).token
    }

  fun cookieHeader(token: String): String = "${adminConfig.cookieName}=$token"

  fun uniqueEmail(): String = "admin-test-${java.util.UUID.randomUUID()}@example.com"
}
