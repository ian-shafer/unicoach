package ed.unicoach.db.dao

import ed.unicoach.db.models.AdmissionTest
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The migration-seeded `admission_test_administrations` calendar and its DAO
 * (RFC 197). Like [PolicyParametersDaoTest], the rows under test are the
 * migration's own -- `bin/test` re-migrates before the suite runs -- so the read
 * is asserted against the seed rather than against a fixture's idea of it.
 */
class AdmissionTestCalendarDaoTest {
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

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  @Test
  fun `the seeded calendar reads back in test-date order, with both publishers' own rows`() {
    // The suites that truncate put the committed rows back; this one does not
    // truncate, so it seeds idempotently instead of depending on whether a
    // sibling suite ran first.
    AdmissionTestCalendarFixture.seedCommitted(session)
    val rows = AdmissionTestCalendarDao.list(session).getOrThrow()

    assertTrue(rows.size >= 15, "the seeded calendar should carry a whole cycle, got [${rows.size}]")
    assertEquals(
      rows.map { it.testDate }.sorted(),
      rows.map { it.testDate },
      "list() must order by test_date, so no publisher's rows come first as a block",
    )
    assertEquals(
      setOf(AdmissionTest.SAT, AdmissionTest.ACT),
      rows.map { it.test }.toSet(),
      "both tests must be present; neither is the default",
    )
    // The publisher's silence, read back as a silence: College Board has
    // published no score release for the spring-2027 SAT sittings, and those
    // rows must come back with NO ScoreRelease at all -- neither a guessed date
    // nor a citation with nothing under it.
    assertTrue(
      rows.any { it.scoreRelease == null },
      "an unannounced score release must read back as null, with no citation under it",
    )
  }

  @Test
  fun `a test code the enum does not name is row corruption, located`() {
    AdmissionTestCalendarFixture.seedCommitted(session)
    // Written past the CHECK on purpose: the constraint is what makes this
    // unreachable in production, and the DAO's refusal is what stops a corrupt
    // row being silently skipped -- a skipped sitting is a date no family is
    // ever told about.
    connection.createStatement().use { stmt ->
      stmt.execute("ALTER TABLE admission_test_administrations DROP CONSTRAINT admission_test_administrations_test_check")
      stmt.execute("UPDATE admission_test_administrations SET test = 'psat' WHERE test_date = DATE '2026-10-03'")
    }
    try {
      val failure = assertFailsWith<CorruptPersistedValueException> { AdmissionTestCalendarDao.list(session).getOrThrow() }
      assertEquals("psat", failure.value)
      assertTrue(
        failure.location?.contains("admission_test_administrations.test") == true,
        "the refusal must name the column and the row: [${failure.location}]",
      )
    } finally {
      connection.createStatement().use { stmt ->
        stmt.execute("DELETE FROM admission_test_administrations WHERE test = 'psat'")
        stmt.execute(
          "ALTER TABLE admission_test_administrations ADD CONSTRAINT admission_test_administrations_test_check " +
            "CHECK (test IN ('sat', 'act'))",
        )
      }
      AdmissionTestCalendarFixture.seedCommitted(session)
    }
  }
}
