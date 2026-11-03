package ed.unicoach.db

import ed.unicoach.db.dao.ErasureFixture
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.UserId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.test.assertEquals

/**
 * The backfill that gives every account closed before RFC 267 its erase job
 * (`schedule-erasure-of-closed-accounts.sql`, decision D-E), executed against
 * the real database.
 *
 * The migration has already run on this database, before any account here
 * existed, so the COMMITTED file is executed verbatim again inside one
 * transaction that is always rolled back. A copy of its SQL in this test would
 * prove only that the copy schedules.
 */
class ScheduleErasureBackfillTest {
  companion object {
    private lateinit var connection: Connection

    private val MIGRATION = File("schema/schedule-erasure-of-closed-accounts.sql")

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
  }

  @BeforeEach
  fun begin() {
    connection.autoCommit = false
  }

  @AfterEach
  fun rollback() {
    connection.rollback()
    connection.autoCommit = true
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  /** The ERASE_ACCOUNT jobs naming [userId], as (payload closedAt, scheduled_at). */
  private fun erasureJobs(userId: UserId): List<Pair<Instant, Instant>> =
    connection
      .prepareStatement(
        "SELECT payload::text, scheduled_at FROM jobs WHERE job_type = 'ERASE_ACCOUNT' AND payload->>'userId' = ?",
      ).use {
        it.setString(1, userId.asString)
        it.executeQuery().use { rs ->
          buildList {
            while (rs.next()) {
              val closedAt =
                Json
                  .parseToJsonElement(rs.getString(1))
                  .jsonObject
                  .getValue("closedAt")
                  .jsonPrimitive.content
              add(OffsetDateTime.parse(closedAt).toInstant() to rs.getTimestamp(2).toInstant())
            }
          }
        }
      }

  private fun deletedAt(userId: UserId): Instant =
    connection.prepareStatement("SELECT deleted_at FROM users WHERE id = ?").use {
      it.setObject(1, userId.value)
      it.executeQuery().use { rs ->
        rs.next()
        rs.getTimestamp(1).toInstant()
      }
    }

  @Test
  fun `a closed account that is not erased gets one job, due 7 days after its close`() {
    val closed = ErasureFixture.build(session)
    ErasureFixture.close(session, closed.userId)
    val erased = ErasureFixture.build(session)
    ErasureFixture.close(session, erased.userId)
    connection.prepareStatement("SELECT count(*) FROM erase_account(?)").use {
      it.setObject(1, erased.userId.value)
      it.executeQuery().close()
    }
    val live = closed.other.userId

    // UTC, which has no daylight saving: across a change, the migration's
    // calendar '7 days' is not seven times 24 hours.
    connection.createStatement().use { it.execute("SET LOCAL TIME ZONE 'UTC'") }
    connection.createStatement().use { it.execute(MIGRATION.readText()) }

    val closedAt = deletedAt(closed.userId)
    assertEquals(
      listOf(closedAt to closedAt.plus(Duration.ofDays(7))),
      erasureJobs(closed.userId),
      "the job its close would have scheduled",
    )
    assertEquals(emptyList(), erasureJobs(live), "a live account has nothing to erase")
    assertEquals(emptyList(), erasureJobs(erased.userId), "an erased account is not erased again")
  }
}
