package ed.unicoach.cron.dao

import ed.unicoach.cron.PeriodicJobName
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.SqlSession
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.Timestamp
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PeriodicJobsDaoTest {
  companion object {
    private lateinit var connection: Connection
    private lateinit var jdbcUrl: String
    private lateinit var user: String
    private var password: String = ""

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf")
          .getOrThrow()
      val dbConfig = DatabaseConfig.from(config).getOrThrow()
      jdbcUrl = dbConfig.jdbcUrl
      user = dbConfig.user
      password = dbConfig.password ?: ""
      connection = DriverManager.getConnection(jdbcUrl, user, password)
      // Manual transaction control: the SKIP-LOCKED test holds a row lock across a
      // second connection's claim, which requires the FOR UPDATE lock to persist
      // (i.e. no autocommit releasing it immediately).
      connection.autoCommit = false
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::connection.isInitialized && !connection.isClosed) {
        connection.close()
      }
    }
  }

  @BeforeEach
  fun resetDatabase() {
    connection.createStatement().use { stmt ->
      stmt.execute("TRUNCATE TABLE periodic_jobs")
    }
    connection.commit()
  }

  private fun sessionOn(conn: Connection): SqlSession =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = conn.prepareStatement(sql)
    }

  private val session = sessionOn(connection)
  private val dao = PeriodicJobsDao()

  /** Inserts a raw periodic_jobs row and returns its name. */
  private fun insertRow(
    name: String = "test-job",
    // The DAO is job-type-agnostic; use a reserved test variant that always
    // exists so the row's job_type maps without depending on production types.
    jobType: String = "TEST_JOB",
    schedule: String = "0 3 * * *",
    timezone: String = "UTC",
    nextRunAt: Instant,
    enabled: Boolean = true,
  ): PeriodicJobName {
    connection
      .prepareStatement(
        """
        INSERT INTO periodic_jobs (name, job_type, payload, schedule, timezone, next_run_at, enabled)
        VALUES (?, ?, '{}'::jsonb, ?, ?, ?, ?)
        """.trimIndent(),
      ).use { stmt ->
        stmt.setString(1, name)
        stmt.setString(2, jobType)
        stmt.setString(3, schedule)
        stmt.setString(4, timezone)
        stmt.setTimestamp(5, Timestamp.from(nextRunAt))
        stmt.setBoolean(6, enabled)
        stmt.executeUpdate()
      }
    return PeriodicJobName(name)
  }

  /**
   * Inserts a row whose `payload` is malformed JSON (a bare token, not an object).
   * The column is `jsonb`, which would reject the bad text — so it is written as a
   * valid JSON string literal and then rewritten in place to the malformed bytes
   * via `#>>'{}'`, reproducing the on-disk state a `JsonObject` decode rejects.
   * This exercises the `payload` branch of `mapRow` (the `job_type` branch already
   * has coverage via [insertRow] with an unknown type).
   */
  private fun insertMalformedPayloadRow(
    name: String = "bad-payload",
    nextRunAt: Instant,
  ): PeriodicJobName {
    insertRow(name = name, nextRunAt = nextRunAt)
    connection
      .prepareStatement("UPDATE periodic_jobs SET payload = to_jsonb(?::text) WHERE name = ?")
      .use { stmt ->
        // A JSON string ("not-an-object") decodes fine as a JsonElement but not as
        // a JsonObject, so mapRow's Json.decodeFromString<JsonObject> throws.
        stmt.setString(1, "not-an-object")
        stmt.setString(2, name)
        stmt.executeUpdate()
      }
    return PeriodicJobName(name)
  }

  // ---------------------------------------------------------------------------
  // claimDue
  // ---------------------------------------------------------------------------

  @Test
  fun `claimDue returns a due enabled row and the DB clock`() {
    val name = insertRow(nextRunAt = Instant.now().minusSeconds(60), enabled = true)

    val result = dao.claimDue(session)
    assertTrue(result is ClaimDueResult.Claimed, "Expected Claimed, got: $result")
    assertEquals(name, result.job.name)
    assertTrue(result.job.enabled)
    assertNotNull(result.dbNow)
    assertTrue(result.job.nextRunAt.isBefore(result.dbNow.plusSeconds(1)))
    connection.rollback()
  }

  @Test
  fun `claimDue returns NoneDue when the only due row is disabled`() {
    insertRow(nextRunAt = Instant.now().minusSeconds(60), enabled = false)

    val result = dao.claimDue(session)
    assertTrue(result is ClaimDueResult.NoneDue, "A disabled row must not be claimed, got: $result")
    connection.rollback()
  }

  @Test
  fun `claimDue returns NoneDue when the only enabled row is in the future`() {
    insertRow(nextRunAt = Instant.now().plusSeconds(3600), enabled = true)

    val result = dao.claimDue(session)
    assertTrue(result is ClaimDueResult.NoneDue, "A future row must not be claimed, got: $result")
    connection.rollback()
  }

  @Test
  fun `claimDue returns Corrupt (not DatabaseFailure) for a due row with an unknown job_type`() {
    // No CHECK ties job_type to the JobType enum, so a stale/renamed value is a
    // real reachable row state. claimDue locks the row but mapRow cannot
    // reconstruct it -> a distinct Corrupt outcome carrying the row name + DB clock,
    // NOT the generic DatabaseFailure (which would make the scheduler back off and
    // leave the corrupt row cheapest-due forever).
    val name = insertRow(jobType = "NO_SUCH_JOB_TYPE", nextRunAt = Instant.now().minusSeconds(60), enabled = true)

    val result = dao.claimDue(session)
    assertTrue(result is ClaimDueResult.Corrupt, "An unreconstructable due row must be Corrupt, got: $result")
    assertEquals(name, result.name)
    assertEquals("job_type", result.cause.field)
    assertEquals("NO_SUCH_JOB_TYPE", result.cause.value)
    assertNotNull(result.dbNow)
    connection.rollback()
  }

  @Test
  fun `a concurrent claimDue skips the locked row`() {
    insertRow(nextRunAt = Instant.now().minusSeconds(60), enabled = true)
    // Commit the insert so a second, independent connection can also see the row
    // (an uncommitted row would be invisible to the other transaction, not merely
    // locked, which would not exercise SKIP LOCKED).
    connection.commit()

    // First connection claims and holds the row lock (no commit/rollback yet).
    val firstClaim = dao.claimDue(session)
    assertTrue(firstClaim is ClaimDueResult.Claimed, "First claim must succeed, got: $firstClaim")

    // A second, independent transaction sees the row locked -> SKIP LOCKED -> NoneDue.
    val otherConn = DriverManager.getConnection(jdbcUrl, user, password)
    try {
      otherConn.autoCommit = false
      val otherSession = sessionOn(otherConn)
      val secondClaim = dao.claimDue(otherSession)
      assertTrue(
        secondClaim is ClaimDueResult.NoneDue,
        "A concurrent claim of the same locked row must be NoneDue (SKIP LOCKED), got: $secondClaim",
      )
      otherConn.rollback()
    } finally {
      otherConn.close()
    }
    connection.rollback()
  }

  // ---------------------------------------------------------------------------
  // advance
  // ---------------------------------------------------------------------------

  @Test
  fun `advance sets next_run_at and last_run_at`() {
    val name = insertRow(nextRunAt = Instant.now().minusSeconds(60), enabled = true)
    val target = Instant.parse("2027-01-01T03:00:00Z")

    val result = dao.advance(session, name, target)
    assertTrue(result is PeriodicUpdateResult.Success, "Expected Success, got: $result")
    assertEquals(target, result.job.nextRunAt)
    assertNotNull(result.job.lastRunAt)
    connection.commit()

    val reread = dao.findByName(session, name)
    assertTrue(reread is PeriodicFindResult.Success)
    assertEquals(target, reread.job.nextRunAt)
    assertNotNull(reread.job.lastRunAt)
    connection.commit()
  }

  @Test
  fun `advance on an unknown name returns NotFound`() {
    val result = dao.advance(session, PeriodicJobName("nope"), Instant.now())
    assertTrue(result is PeriodicUpdateResult.NotFound, "Expected NotFound, got: $result")
    connection.rollback()
  }

  // ---------------------------------------------------------------------------
  // park
  // ---------------------------------------------------------------------------

  @Test
  fun `park moves next_run_at without stamping last_run_at`() {
    val name = insertRow(nextRunAt = Instant.now().minusSeconds(60), enabled = true)
    val retryAt = Instant.parse("2027-06-01T00:00:00Z")

    // park returns a ParkResult with no reconstructed job (it must succeed even on
    // a corrupt row), so the persisted effect is asserted via a re-read.
    val result = dao.park(session, name, retryAt)
    assertTrue(result is ParkResult.Success, "Expected Success, got: $result")
    connection.commit()

    val reread = dao.findByName(session, name)
    assertTrue(reread is PeriodicFindResult.Success)
    assertEquals(retryAt, reread.job.nextRunAt)
    // A parked row never ran, so last_run_at must persist as null (unlike advance).
    assertNull(reread.job.lastRunAt, "last_run_at must persist as null after park")
    connection.commit()
  }

  @Test
  fun `park succeeds on a corrupt row without reconstructing it`() {
    // The park write must NOT re-throw the corruption it exists to quarantine: it
    // confirms the next_run_at write hit from was_updated alone, never mapRow.
    val name = insertRow(jobType = "NO_SUCH_JOB_TYPE", nextRunAt = Instant.now().minusSeconds(60), enabled = true)
    val retryAt = Instant.parse("2027-06-01T00:00:00Z")

    val result = dao.park(session, name, retryAt)
    assertTrue(result is ParkResult.Success, "park must succeed on a corrupt row, got: $result")
    connection.commit()

    // The row's next_run_at moved out of the due window even though it is corrupt;
    // read it back raw (findByName would re-throw on the unknown job_type).
    val nextRunAt =
      connection.prepareStatement("SELECT next_run_at FROM periodic_jobs WHERE name = ?").use { stmt ->
        stmt.setString(1, name.value)
        stmt.executeQuery().use { rs ->
          rs.next()
          rs.getTimestamp("next_run_at").toInstant()
        }
      }
    assertEquals(retryAt, nextRunAt, "park must move a corrupt row's next_run_at out of the due window")
    connection.commit()
  }

  @Test
  fun `park on an unknown name returns NotFound`() {
    val result = dao.park(session, PeriodicJobName("nope"), Instant.now())
    assertTrue(result is ParkResult.NotFound, "Expected NotFound, got: $result")
    connection.rollback()
  }

  // ---------------------------------------------------------------------------
  // list / findByName
  // ---------------------------------------------------------------------------

  @Test
  fun `list and findByName return persisted fields`() {
    val name = insertRow(name = "row-a", nextRunAt = Instant.now(), schedule = "*/15 * * * *", timezone = "America/New_York")
    connection.commit()

    val listResult = dao.list(session)
    assertTrue(listResult is PeriodicListResult.Success)
    assertEquals(1, listResult.jobs.size)
    assertTrue(listResult.corruptRows.isEmpty(), "A healthy table reports no corrupt rows")
    connection.commit()

    val findResult = dao.findByName(session, name)
    assertTrue(findResult is PeriodicFindResult.Success)
    assertEquals("*/15 * * * *", findResult.job.schedule)
    assertEquals("America/New_York", findResult.job.timezone.id)
    connection.commit()

    val missing = dao.findByName(session, PeriodicJobName("missing"))
    assertTrue(missing is PeriodicFindResult.NotFound)
    connection.commit()
  }

  @Test
  fun `list degrades per-row - a corrupt row surfaces without hiding the healthy rows`() {
    // One healthy row + one corrupt (unknown job_type) row. The whole call must NOT
    // fold to DatabaseFailure (that would 500 the admin list and hide every healthy
    // row); the healthy row is returned and the corrupt row is reported by name + field.
    val healthy = insertRow(name = "healthy", nextRunAt = Instant.now())
    val corrupt = insertRow(name = "corrupt-type", jobType = "NO_SUCH_JOB_TYPE", nextRunAt = Instant.now())
    connection.commit()

    val result = dao.list(session)
    assertTrue(result is PeriodicListResult.Success, "One corrupt row must not fold list() to DatabaseFailure, got: $result")
    assertEquals(listOf(healthy), result.jobs.map { it.name }, "The healthy row is returned")
    assertEquals(1, result.corruptRows.size, "The corrupt row is surfaced, not silently dropped")
    val reported = result.corruptRows.single()
    assertEquals(corrupt, reported.name)
    assertEquals("job_type", reported.field)
    assertEquals("NO_SUCH_JOB_TYPE", reported.value)
    connection.commit()
  }

  @Test
  fun `list surfaces a malformed-payload corrupt row`() {
    // The payload branch of mapRow (only the job_type branch is exercised elsewhere):
    // a row whose payload is valid JSON but not a JSON object surfaces as a CorruptRow
    // keyed on the payload field, not a whole-table DatabaseFailure.
    val healthy = insertRow(name = "healthy", nextRunAt = Instant.now())
    val badPayload = insertMalformedPayloadRow(name = "bad-payload", nextRunAt = Instant.now())
    connection.commit()

    val result = dao.list(session)
    assertTrue(result is PeriodicListResult.Success, "A malformed payload row must not fold list(), got: $result")
    assertEquals(listOf(healthy), result.jobs.map { it.name })
    val reported = result.corruptRows.single()
    assertEquals(badPayload, reported.name)
    assertEquals("payload", reported.field, "The corrupt field is payload, not job_type")
    connection.commit()
  }

  @Test
  fun `findByName returns Corrupt for an unreconstructable row`() {
    // A found-but-corrupt row is a distinct Corrupt outcome (mirroring
    // ClaimDueResult.Corrupt), never a DatabaseFailure, so the admin detail can
    // report it instead of 500ing. Cover both corruption sources.
    val badType = insertRow(name = "corrupt-type", jobType = "NO_SUCH_JOB_TYPE", nextRunAt = Instant.now())
    val badPayload = insertMalformedPayloadRow(name = "bad-payload", nextRunAt = Instant.now())
    connection.commit()

    val typeResult = dao.findByName(session, badType)
    assertTrue(typeResult is PeriodicFindResult.Corrupt, "An unknown job_type row must be Corrupt, got: $typeResult")
    assertEquals(badType, typeResult.name)
    assertEquals("job_type", typeResult.cause.field)
    assertEquals("NO_SUCH_JOB_TYPE", typeResult.cause.value)

    val payloadResult = dao.findByName(session, badPayload)
    assertTrue(payloadResult is PeriodicFindResult.Corrupt, "A malformed payload row must be Corrupt, got: $payloadResult")
    assertEquals(badPayload, payloadResult.name)
    assertEquals("payload", payloadResult.cause.field)
    connection.commit()
  }

  // ---------------------------------------------------------------------------
  // setEnabled
  // ---------------------------------------------------------------------------

  @Test
  fun `setEnabled toggles the flag and is idempotent, unknown name is NotFound`() {
    val name = insertRow(nextRunAt = Instant.now(), enabled = false)
    connection.commit()

    // setEnabled confirms from the was_updated flag alone (never mapRow), so its
    // Success carries no reconstructed row; the flag is asserted via a re-read.
    val enable = dao.setEnabled(session, name, true)
    assertTrue(enable is SetEnabledResult.Success, "Expected Success enabling, got: $enable")
    connection.commit()
    assertTrue((dao.findByName(session, name) as PeriodicFindResult.Success).job.enabled)
    connection.commit()

    // Idempotent: re-enabling an already-enabled row re-Successes.
    val reEnable = dao.setEnabled(session, name, true)
    assertTrue(reEnable is SetEnabledResult.Success, "Re-enabling must be idempotent Success, got: $reEnable")
    connection.commit()
    assertTrue((dao.findByName(session, name) as PeriodicFindResult.Success).job.enabled)
    connection.commit()

    val disable = dao.setEnabled(session, name, false)
    assertTrue(disable is SetEnabledResult.Success, "Expected Success disabling, got: $disable")
    connection.commit()
    assertTrue(!(dao.findByName(session, name) as PeriodicFindResult.Success).job.enabled)
    connection.commit()

    val unknown = dao.setEnabled(session, PeriodicJobName("nope"), true)
    assertTrue(unknown is SetEnabledResult.NotFound, "Unknown name must be NotFound, got: $unknown")
    connection.rollback()
  }

  @Test
  fun `setEnabled succeeds on a corrupt row without reconstructing it`() {
    // The operator's quarantine remedy: disabling a corrupt row must NOT re-throw
    // the corruption (setEnabled confirms from was_updated alone, never mapRow), so
    // it Successes and the flag actually flips on disk.
    val name = insertRow(name = "corrupt-type", jobType = "NO_SUCH_JOB_TYPE", nextRunAt = Instant.now(), enabled = true)
    connection.commit()

    val disable = dao.setEnabled(session, name, false)
    assertTrue(disable is SetEnabledResult.Success, "Disabling a corrupt row must Success, got: $disable")
    connection.commit()

    // Read enabled back raw (findByName would report Corrupt, not the flag).
    val enabled =
      connection.prepareStatement("SELECT enabled FROM periodic_jobs WHERE name = ?").use { stmt ->
        stmt.setString(1, name.value)
        stmt.executeQuery().use { rs ->
          rs.next()
          rs.getBoolean("enabled")
        }
      }
    assertTrue(!enabled, "The corrupt row is now disabled (quarantined)")
    connection.commit()
  }

  @Test
  fun `payload round-trips as an empty object by default`() {
    val name = insertRow(nextRunAt = Instant.now())
    connection.commit()
    val found = dao.findByName(session, name)
    assertTrue(found is PeriodicFindResult.Success)
    assertTrue(found.job.payload.isEmpty())
    assertNull(found.job.lastRunAt)
    connection.commit()
  }
}
