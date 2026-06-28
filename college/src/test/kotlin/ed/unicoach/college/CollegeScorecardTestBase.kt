package ed.unicoach.college

import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.SqlSession
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import java.io.File

/**
 * Shared DB-test scaffolding for the College Scorecard loader suites: opens one
 * pooled [Database] for the class, truncates the two scorecard tables before each
 * test, and offers the fixture/session/count helpers both suites need. Concrete
 * suites supply only their fixtures and assertions.
 */
abstract class CollegeScorecardTestBase {
  companion object {
    private lateinit var database: Database

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config = AppConfig.load("common.conf", "db.conf").getOrThrow()
      val dbConfig = DatabaseConfig.from(config).getOrThrow()
      database = Database(dbConfig)
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::database.isInitialized) database.close()
    }
  }

  protected val database: Database get() = Companion.database

  protected fun fixture(name: String): File {
    val url = requireNotNull(this::class.java.classLoader.getResource(name)) { "missing fixture [$name]" }
    return File(url.toURI())
  }

  @BeforeEach
  fun resetDatabase() =
    runBlocking {
      database.withConnection { session ->
        session.prepareStatement("TRUNCATE TABLE colleges, college_programs CASCADE").use { it.execute() }
      }
      Unit
    }

  protected fun <T> withSession(block: (SqlSession) -> T): T = runBlocking { database.withConnection(block) }

  protected fun count(
    session: SqlSession,
    table: String,
  ): Int =
    session.prepareStatement("SELECT count(*) FROM $table").use { stmt ->
      stmt.executeQuery().use { rs ->
        rs.next()
        rs.getInt(1)
      }
    }
}
