package ed.unicoach.db.dao

import ed.unicoach.db.models.NewCollege
import ed.unicoach.db.models.PublishedScoreScale
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The six percentile score-band columns, asserted against the database that
 * actually has them (RFC 195, `add-college-score-bands.sql`).
 *
 * Three claims, and the first is the slice's named acceptance criterion:
 *
 * 1. **A write survives, and the history mirrors it.** `log_college_version()`
 *    names every `colleges` column literally on both sides of its `VALUES`,
 *    and a plpgsql body is stored as TEXT and is not dependency-tracked -- so
 *    `ADD COLUMN` neither rewrote it nor failed on it. A stale body passes the
 *    migration and then kills the very next write to `colleges` with
 *    `42703 record "new" has no field ...`, from inside the trigger, in the
 *    middle of an ingest. This suite writes a row with all six bands set and
 *    then changes it, which is the only thing that can prove the restatement
 *    landed.
 * 2. **The domains.** An SAT section is 200-800 and an ACT composite 1-36; a
 *    value outside is a loader fault, never data.
 * 3. **A band is an ordered PAIR.** Half a band is not a range, and `75 = 25`
 *    is a real narrow class rather than a fault.
 *
 * The CHECK claims are driven through RAW SQL on purpose: `NewCollege` cannot
 * express half a band the DAO would then have to reject, and the constraint is
 * the DATABASE's guarantee -- the one `ScoreBand`'s KDoc leans on when it
 * declines to re-check the ordering in Kotlin.
 */
class CollegeScoreBandsMigrationTest {
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

    /** A `colleges` row this suite owns, kept out of every other suite's id space. */
    private const val BASE_UNIT_ID = 950000
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  @BeforeEach
  fun resetDatabase() {
    connection.autoCommit = true
    // TRUNCATE, not DELETE: `colleges` refuses a physical delete outright
    // (0015's `prevent_college_delete`), which is the rule a version history
    // exists to keep -- and CASCADE takes `colleges_versions` with it, which
    // is exactly what this suite must see empty before each case.
    connection.createStatement().use { it.execute("TRUNCATE TABLE colleges, colleges_versions CASCADE") }
    CodebookReferenceFixture.seed(session)
  }

  /**
   * The bands as a fixture, so each test varies only the figures it is about.
   * The reading band is deliberately NARROWER than the math band and both
   * differ from the ACT, so a mapper that crossed two columns would show up as
   * a wrong NUMBER rather than as a passing test.
   */
  private fun collegeWithBands(
    ipedsUnitId: Int,
    name: String,
    satReading: Pair<Int, Int>? = 620 to 700,
    satMath: Pair<Int, Int>? = 640 to 750,
    act: Pair<Int, Int>? = 28 to 33,
  ): NewCollege =
    newCollegeFixture(ipedsUnitId, name).copy(
      satReading25thPercentileScore = satReading?.first,
      satReading75thPercentileScore = satReading?.second,
      satMath25thPercentileScore = satMath?.first,
      satMath75thPercentileScore = satMath?.second,
      actComposite25thPercentileScore = act?.first,
      actComposite75thPercentileScore = act?.second,
    )

  /** One raw insert of a `colleges` row with the six band cells set exactly as given. */
  private fun insertRawBands(
    ipedsUnitId: Int,
    satReading25: Int?,
    satReading75: Int?,
    satMath25: Int? = null,
    satMath75: Int? = null,
    act25: Int? = null,
    act75: Int? = null,
  ) {
    connection
      .prepareStatement(
        """
        INSERT INTO colleges (
          ipeds_unit_id, name, city, state, control,
          sat_reading_25th_percentile_score, sat_reading_75th_percentile_score,
          sat_math_25th_percentile_score, sat_math_75th_percentile_score,
          act_composite_25th_percentile_score, act_composite_75th_percentile_score
        ) VALUES (?, 'Raw College', 'Townsville', 'CA', 1, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
      ).use { stmt ->
        stmt.setInt(1, ipedsUnitId)
        listOf(satReading25, satReading75, satMath25, satMath75, act25, act75).forEachIndexed { index, value ->
          if (value == null) stmt.setNull(index + 2, java.sql.Types.INTEGER) else stmt.setInt(index + 2, value)
        }
        stmt.executeUpdate()
      }
  }

  /** The SQLSTATE of a rejected write, so a test asserts the CHECK fired and not merely that something failed. */
  private fun rejectionStateOf(block: () -> Unit): String = assertFailsWith<SQLException> { block() }.sqlState

  // ---------------------------------------------------------------------------
  // 1. The 42703 trap -- the acceptance criterion
  // ---------------------------------------------------------------------------

  @Test
  fun `a write with all six bands set survives, and colleges_versions mirrors them`() {
    val unitId = BASE_UNIT_ID + 1
    val first = CollegesDao.upsert(session, collegeWithBands(unitId, "Band College")).getOrThrow()
    assertEquals(620, first.satReading25thPercentileScore, "the live row carries the band the write set")
    assertEquals(33, first.actComposite75thPercentileScore)

    // The UPDATE is the half that matters: the trigger fires on both, but a
    // stale `log_college_version()` body fails on the NEW record's missing
    // field, so the second write is where 42703 would land.
    val second = CollegesDao.upsert(session, collegeWithBands(unitId, "Band College Renamed")).getOrThrow()
    assertEquals(2, second.version, "a real content change must still bump the version")

    val history = CollegesDao.listVersions(session, first.id).getOrThrow()
    assertEquals(listOf(1, 2), history.map { it.version }, "the trigger wrote a row for each write")
    for (version in history) {
      val mirrored = version.entity
      assertEquals(620, mirrored.satReading25thPercentileScore, "version ${version.version} reading 25th")
      assertEquals(700, mirrored.satReading75thPercentileScore, "version ${version.version} reading 75th")
      assertEquals(640, mirrored.satMath25thPercentileScore, "version ${version.version} math 25th")
      assertEquals(750, mirrored.satMath75thPercentileScore, "version ${version.version} math 75th")
      assertEquals(28, mirrored.actComposite25thPercentileScore, "version ${version.version} ACT 25th")
      assertEquals(33, mirrored.actComposite75thPercentileScore, "version ${version.version} ACT 75th")
    }
    assertEquals("Band College", history.first().entity.name, "the PRIOR version is the prior content")
    assertEquals("Band College Renamed", history.last().entity.name)
  }

  @Test
  fun `a college with no band at all still writes, and its history carries six nulls`() {
    // The 5,234-of-6,273 case, and the reason all six columns are NULLable:
    // most colleges publish no score, and the migration backfills nothing.
    val unitId = BASE_UNIT_ID + 2
    val first =
      CollegesDao
        .upsert(session, collegeWithBands(unitId, "Silent College", satReading = null, satMath = null, act = null))
        .getOrThrow()
    CollegesDao
      .upsert(session, collegeWithBands(unitId, "Silent College Renamed", satReading = null, satMath = null, act = null))
      .getOrThrow()

    val latest =
      CollegesDao
        .listVersions(session, first.id)
        .getOrThrow()
        .last()
        .entity
    assertNull(latest.satReading25thPercentileScore)
    assertNull(latest.satMath75thPercentileScore)
    assertNull(latest.actComposite25thPercentileScore)
  }

  // ---------------------------------------------------------------------------
  // 2. The domains
  // ---------------------------------------------------------------------------

  @Test
  fun `a score outside its published scale is rejected by the database`() {
    // The four probes are DERIVED from the one published scale
    // ([PublishedScoreScale]) rather than typed as 199 / 801 / 37 / 0. Typed,
    // they are a second statement of the domain that nothing compares to the
    // first: widen a CHECK and the hand-typed probe goes on asserting the old
    // boundary, green, about a database that no longer draws it there.
    val satSection = PublishedScoreScale.SAT_SECTION
    val actComposite = PublishedScoreScale.ACT_COMPOSITE
    assertEquals(
      CHECK_VIOLATION,
      rejectionStateOf { insertRawBands(BASE_UNIT_ID + 10, satReading25 = satSection.first - 1, satReading75 = 700) },
      "[${satSection.first - 1}] is below the SAT section floor",
    )
    assertEquals(
      CHECK_VIOLATION,
      rejectionStateOf { insertRawBands(BASE_UNIT_ID + 11, satReading25 = 700, satReading75 = satSection.last + 1) },
      // The ceiling is proved on the 75th column deliberately: an over-ceiling
      // value in the 25th would break the ordering CHECK as well, and one
      // SQLSTATE cannot say which of the two fired. The floor above is proved
      // on the 25th for the same reason, so between them both columns are
      // covered.
      "[${satSection.last + 1}] is above the SAT section ceiling, proved on the 75th column",
    )
    assertEquals(
      CHECK_VIOLATION,
      rejectionStateOf { insertRawBands(BASE_UNIT_ID + 12, null, null, act25 = 30, act75 = actComposite.last + 1) },
      "[${actComposite.last + 1}] is above the ACT ceiling",
    )
    assertEquals(
      CHECK_VIOLATION,
      rejectionStateOf { insertRawBands(BASE_UNIT_ID + 13, null, null, act25 = actComposite.first - 1, act75 = 30) },
      "[${actComposite.first - 1}] is below the ACT floor -- the scale starts at ${actComposite.first}",
    )
    // The probes must be the boundary they claim to be: a scale that started
    // at 0 would make the ACT floor probe `-1`, which the column would reject
    // for a reason that has nothing to do with this CHECK.
    assertEquals(200..800, satSection, "the SAT section scale this suite probes is the one the CHECK draws")
    assertEquals(1..36, actComposite, "and so is the ACT composite scale")
  }

  @Test
  fun `an implausible but in-domain ACT band is stored as published`() {
    // D-D, and it is a real pair of rows: Southeastern Baptist College
    // publishes ACTCM25=5 / ACTCM75=7 and Conception Seminary College 1 / 5.
    // The ACT scale starts at 1, so both are IN domain, and there is no
    // plausibility floor anywhere in this slice -- deciding which published
    // numbers are believable is not ours to do silently.
    val unitId = BASE_UNIT_ID + 14
    val stored =
      CollegesDao
        .upsert(session, collegeWithBands(unitId, "Seminary College", satReading = null, satMath = null, act = 1 to 5))
        .getOrThrow()
    assertEquals(1, stored.actComposite25thPercentileScore)
    assertEquals(5, stored.actComposite75thPercentileScore)
  }

  // ---------------------------------------------------------------------------
  // 3. Pairing and order
  // ---------------------------------------------------------------------------

  @Test
  fun `half a band is rejected, an inverted band is rejected, and a one-score band is accepted`() {
    assertEquals(
      CHECK_VIOLATION,
      rejectionStateOf { insertRawBands(BASE_UNIT_ID + 20, satReading25 = 620, satReading75 = null) },
      "a 25th with no 75th is not a range",
    )
    assertEquals(
      CHECK_VIOLATION,
      rejectionStateOf { insertRawBands(BASE_UNIT_ID + 21, satReading25 = null, satReading75 = 700) },
      "and neither is a 75th with no 25th",
    )
    assertEquals(
      CHECK_VIOLATION,
      rejectionStateOf { insertRawBands(BASE_UNIT_ID + 22, satReading25 = 700, satReading75 = 620) },
      "75 < 25 is an inverted band",
    )

    // `>=`, not `>`: a school whose quartiles land on the same score has a
    // genuinely narrow admitted class, which is data and not a fault.
    val unitId = BASE_UNIT_ID + 23
    insertRawBands(unitId, satReading25 = 700, satReading75 = 700)
    val stored = CollegesDao.findByIpedsUnitId(session, unitId).getOrThrow()
    assertNotNull(stored, "a 75 = 25 band must be stored")
    assertEquals(700, stored.satReading25thPercentileScore)
    assertEquals(700, stored.satReading75thPercentileScore)
  }
}

/** Postgres `check_violation`, named rather than typed at four assertion sites. */
private const val CHECK_VIOLATION = "23514"
