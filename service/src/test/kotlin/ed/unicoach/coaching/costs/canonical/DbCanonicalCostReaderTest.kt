package ed.unicoach.coaching.costs.canonical

import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.DaoException
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.CollegeId
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.sql.PreparedStatement
import java.sql.SQLException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The store-backed reader's FAILURE contract (RFC 166 §2). A read that cannot
 * run leaves this `:service` port as [CanonicalReadFailedException] carrying
 * the store fault, never as the `:db` exception the DAO mapped the driver fault
 * to -- so no caller has to import `ed.unicoach.db.dao` to name what it is
 * catching.
 *
 * No rows are needed: the fault is at the statement, which is the one place a
 * pool timeout or a dropped connection shows up. [Database] is built only
 * because the reader takes one for its own-connection overload; nothing here
 * touches it.
 */
class DbCanonicalCostReaderTest {
  companion object {
    private lateinit var database: Database

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf", "service.conf")
          .getOrThrow()
      database = Database(DatabaseConfig.from(config).getOrThrow())
    }
  }

  /** A session that cannot hand out a statement at all -- a pool checkout that failed. */
  private val brokenSession =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement =
        throw SQLException("the connection was closed under the read", "08006")
    }

  private val collegeIds = listOf(CollegeId(UUID.randomUUID()))

  @Test
  fun `a store fault leaves the port as its own failure type, with the cause kept`() {
    val reader = DbCanonicalCostReader(database)

    val failure = assertFailsWith<CanonicalReadFailedException> { reader.read(brokenSession, collegeIds) }

    assertEquals("canonical money read failed", failure.message)
    assertTrue(
      failure.cause is DaoException,
      "the store fault must be kept whole for the log, not summarised: ${failure.cause}",
    )
  }

  @Test
  fun `the cohort-only read fails the same way, so one port has one failure`() {
    val reader = DbCanonicalCostReader(database)

    val failure = assertFailsWith<CanonicalReadFailedException> { reader.readCohortStats(brokenSession, collegeIds) }

    assertTrue(failure.cause is DaoException, "${failure.cause}")
  }
}
