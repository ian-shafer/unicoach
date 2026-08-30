package ed.unicoach.db.dao

import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.NewCollege
import ed.unicoach.db.models.NewCollegeIpeds
import ed.unicoach.db.models.NewCollegeProgramsCensus
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The RFC 144 IPEDS reference tables: the upsert-if-changed three-way split on
 * two UNVERSIONED tables (there is no `version` column to read it off), the raw
 * codes the schema deliberately preserves, the `smallint[]` association list,
 * and the closed non-null-count allowlist.
 */
class CollegeIpedsDaoTest {
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

  @BeforeEach
  fun resetDatabase() {
    connection.autoCommit = true
    connection.createStatement().use { stmt ->
      stmt.execute("TRUNCATE TABLE colleges, college_programs, college_ipeds, college_programs_census CASCADE")
    }
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private fun seedCollege(ipedsUnitId: Int): CollegeId =
    CollegesDao
      .upsert(
        session,
        NewCollege(
          ipedsUnitId = ipedsUnitId,
          opeid = null,
          name = "Test U $ipedsUnitId",
          city = "Townsville",
          state = "CA",
          region = null,
          locale = null,
          latitude = null,
          longitude = null,
          control = 1,
          undergradEnrollmentHeadcount = null,
          admissionRateShare = null,
          satAverageEquivalentScore = null,
          costOfAttendancePerYearUsd = null,
          netPricePerYearUsd = null,
          netPricePerYearIncomeQ1Usd = null,
          netPricePerYearIncomeQ2Usd = null,
          netPricePerYearIncomeQ3Usd = null,
          netPricePerYearIncomeQ4Usd = null,
          netPricePerYearIncomeQ5Usd = null,
          tuitionAndFeesInStatePerYearUsd = null,
          tuitionAndFeesOutOfStatePerYearUsd = null,
          completionRate150pct4yrShare = null,
          medianEarnings10yAfterEntryUsd = null,
          medianDebtAtCompletionUsd = null,
          pellShare = null,
          website = null,
        ),
      ).getOrThrow()
      .id

  private fun ipeds(
    ipedsUnitId: Int,
    surveyYear: Int = 2023,
    cyActive: Boolean = true,
    deathYear: Int? = null,
    closedAt: LocalDate? = null,
    newIpedsUnitId: Int? = null,
    instLevel: Int? = 1,
    ugOffer: Boolean? = true,
    sector: Int? = 4,
    carnegieBasic: Int? = -2,
    carnegieSize: Int? = -2,
    cbsa: Int? = 12620,
    relAffil: Int? = -2,
    hasRotc: Boolean? = true,
    hasStudyAbroad: Boolean? = false,
    disabilityBand: Int? = 2,
    registeredDisabilityPercent: Double? = 6.94,
    offersHousing: Boolean? = true,
    housingCapacityHeadcount: Int? = 3480,
    applicationFeeUsd: Int? = 0,
    athleticAssoc: List<Int> = listOf(1, 3),
    footballConf: Int? = -2,
    testPolicy: Int? = 5,
  ) = NewCollegeIpeds(
    ipedsUnitId = ipedsUnitId,
    surveyYear = surveyYear,
    cyActive = cyActive,
    deathYear = deathYear,
    closedAt = closedAt,
    newIpedsUnitId = newIpedsUnitId,
    instLevel = instLevel,
    ugOffer = ugOffer,
    sector = sector,
    carnegieBasic = carnegieBasic,
    carnegieSize = carnegieSize,
    cbsa = cbsa,
    relAffil = relAffil,
    hasRotc = hasRotc,
    hasStudyAbroad = hasStudyAbroad,
    disabilityBand = disabilityBand,
    registeredDisabilityPercent = registeredDisabilityPercent,
    offersHousing = offersHousing,
    housingCapacityHeadcount = housingCapacityHeadcount,
    applicationFeeUsd = applicationFeeUsd,
    athleticAssoc = athleticAssoc,
    footballConf = footballConf,
    testPolicy = testPolicy,
  )

  private fun <T> scalar(
    sql: String,
    read: (java.sql.ResultSet) -> T,
  ): T =
    connection.prepareStatement(sql).use { stmt ->
      stmt.executeQuery().use { rs ->
        rs.next()
        read(rs)
      }
    }

  // ---------------------------------------------------------------------------
  // college_ipeds upsert
  // ---------------------------------------------------------------------------

  @Test
  fun `the first upsert inserts, an identical one is UNCHANGED, a differing one is CHANGED`() {
    assertEquals(UpsertOutcome.INSERTED, CollegeIpedsDao.upsert(session, ipeds(161280)).getOrThrow())
    assertEquals(UpsertOutcome.UNCHANGED, CollegeIpedsDao.upsert(session, ipeds(161280)).getOrThrow())
    assertEquals(
      UpsertOutcome.CHANGED,
      CollegeIpedsDao.upsert(session, ipeds(161280, testPolicy = 3)).getOrThrow(),
    )
    assertEquals(1, scalar("SELECT count(*) FROM college_ipeds") { it.getInt(1) })
    assertEquals(3, scalar("SELECT test_policy FROM college_ipeds WHERE ipeds_unit_id = 161280") { it.getInt(1) })
  }

  @Test
  fun `an unchanged re-upsert writes nothing at all, updated_at included`() {
    CollegeIpedsDao.upsert(session, ipeds(161280)).getOrThrow()
    val before = scalar("SELECT updated_at FROM college_ipeds WHERE ipeds_unit_id = 161280") { it.getTimestamp(1) }
    Thread.sleep(5)
    assertEquals(UpsertOutcome.UNCHANGED, CollegeIpedsDao.upsert(session, ipeds(161280)).getOrThrow())
    val after = scalar("SELECT updated_at FROM college_ipeds WHERE ipeds_unit_id = 161280") { it.getTimestamp(1) }
    assertEquals(before, after, "a suppressed no-op upsert must not fire the updated_at trigger")
  }

  @Test
  fun `a real change advances updated_at through the _03 trigger`() {
    CollegeIpedsDao.upsert(session, ipeds(161280)).getOrThrow()
    val before = scalar("SELECT updated_at FROM college_ipeds WHERE ipeds_unit_id = 161280") { it.getTimestamp(1) }
    Thread.sleep(5)
    CollegeIpedsDao.upsert(session, ipeds(161280, hasRotc = false)).getOrThrow()
    val after = scalar("SELECT updated_at FROM college_ipeds WHERE ipeds_unit_id = 161280") { it.getTimestamp(1) }
    assertTrue(after.after(before), "updated_at must advance on a real change: $before -> $after")
  }

  @Test
  fun `the raw -2 codes and a real zero application fee survive the round trip`() {
    CollegeIpedsDao
      .upsert(session, ipeds(161280, relAffil = -2, carnegieBasic = -2, footballConf = -2, applicationFeeUsd = 0))
      .getOrThrow()
    assertEquals(-2, scalar("SELECT rel_affil FROM college_ipeds") { it.getInt(1) })
    assertEquals(-2, scalar("SELECT carnegie_basic FROM college_ipeds") { it.getInt(1) })
    assertEquals(-2, scalar("SELECT football_conf FROM college_ipeds") { it.getInt(1) })
    assertEquals(0, scalar("SELECT application_fee_usd FROM college_ipeds") { it.getInt(1) })
  }

  @Test
  fun `nullable attributes round-trip as NULL, not as a coerced zero`() {
    CollegeIpedsDao
      .upsert(
        session,
        ipeds(
          161280,
          hasRotc = null,
          disabilityBand = null,
          registeredDisabilityPercent = null,
          housingCapacityHeadcount = null,
          applicationFeeUsd = null,
          testPolicy = null,
          ugOffer = null,
        ),
      ).getOrThrow()
    val nulls =
      scalar(
        "SELECT has_rotc, disability_band, registered_disability_percent, housing_capacity_headcount, application_fee_usd, " +
          "test_policy, ug_offer FROM college_ipeds",
      ) { rs -> (1..7).map { rs.getObject(it) } }
    assertTrue(nulls.all { it == null }, "every unknown must be SQL NULL: $nulls")
  }

  @Test
  fun `athletic_assoc binds as a smallint array and an empty list is the default empty array`() {
    CollegeIpedsDao.upsert(session, ipeds(161280, athleticAssoc = listOf(1, 3, 6))).getOrThrow()
    CollegeIpedsDao.upsert(session, ipeds(100690, athleticAssoc = emptyList())).getOrThrow()
    assertEquals(
      "{1,3,6}",
      scalar("SELECT athletic_assoc::text FROM college_ipeds WHERE ipeds_unit_id = 161280") { it.getString(1) },
    )
    assertEquals(
      "{}",
      scalar("SELECT athletic_assoc::text FROM college_ipeds WHERE ipeds_unit_id = 100690") { it.getString(1) },
    )
  }

  @Test
  fun `a closed_at date round-trips and an absent one is NULL`() {
    CollegeIpedsDao.upsert(session, ipeds(115728, closedAt = LocalDate.of(2023, 5, 15), deathYear = 2023)).getOrThrow()
    assertEquals(
      LocalDate.of(2023, 5, 15),
      scalar("SELECT closed_at FROM college_ipeds WHERE ipeds_unit_id = 115728") { it.getDate(1).toLocalDate() },
    )
    CollegeIpedsDao.upsert(session, ipeds(161280)).getOrThrow()
    assertNull(scalar("SELECT closed_at FROM college_ipeds WHERE ipeds_unit_id = 161280") { it.getDate(1) })
  }

  @Test
  fun `an out-of-domain code is refused by its named CHECK, not silently stored`() {
    val error =
      CollegeIpedsDao.upsert(session, ipeds(161280, testPolicy = 2)).exceptionOrNull()
    assertTrue(error is ConstraintViolationException, "expected a constraint violation, got $error")
    assertEquals("college_ipeds_test_policy_domain_check", (error as ConstraintViolationException).constraint)
  }

  @Test
  fun `sector accepts the published code set 0 to 9 plus 99, and refuses the unpublished gap`() {
    // IPEDS publishes ten sector codes plus 99 ("sector unknown, not active").
    // A range CHECK of 0..99 would admit 89 codes the source cannot emit, so a
    // shifted-column read would land a plausible integer in an authoritative
    // column instead of being refused.
    for (code in listOf(0, 9, 99)) {
      CollegeIpedsDao.upsert(session, ipeds(161280 + code, sector = code)).getOrThrow()
      assertEquals(
        code,
        scalar("SELECT sector FROM college_ipeds WHERE ipeds_unit_id = ${161280 + code}") { it.getInt(1) },
      )
    }
    for (code in listOf(10, 50, 98)) {
      val error = CollegeIpedsDao.upsert(session, ipeds(910000 + code, sector = code)).exceptionOrNull()
      assertTrue(error is ConstraintViolationException, "[$code] must be refused, got $error")
      assertEquals("college_ipeds_sector_domain_check", (error as ConstraintViolationException).constraint)
    }
  }

  // ---------------------------------------------------------------------------
  // college_programs_census upsert
  // ---------------------------------------------------------------------------

  @Test
  fun `the census upserts on its natural key with the same three-way split`() {
    val collegeId = seedCollege(186131)
    val row = NewCollegeProgramsCensus(collegeId, "050104", 5, 5, 2023)
    assertEquals(
      UpsertOutcome.INSERTED,
      CollegeIpedsDao.upsertProgramsCensus(session, row).getOrThrow(),
    )
    assertEquals(
      UpsertOutcome.UNCHANGED,
      CollegeIpedsDao.upsertProgramsCensus(session, row).getOrThrow(),
    )
    assertEquals(
      UpsertOutcome.CHANGED,
      CollegeIpedsDao.upsertProgramsCensus(session, row.copy(awardsCount = 9)).getOrThrow(),
    )
    assertEquals(1, scalar("SELECT count(*) FROM college_programs_census") { it.getInt(1) })
    assertEquals(9, scalar("SELECT awards_count FROM college_programs_census") { it.getInt(1) })
  }

  @Test
  fun `a non-6-digit cip_code is refused by the format CHECK`() {
    val collegeId = seedCollege(186131)
    val error =
      CollegeIpedsDao
        .upsertProgramsCensus(session, NewCollegeProgramsCensus(collegeId, "05.0104", 5, 5, 2023))
        .exceptionOrNull()
    assertTrue(error is ConstraintViolationException, "expected a constraint violation, got $error")
    assertEquals("college_programs_census_cip_code_format_check", (error as ConstraintViolationException).constraint)
  }

  @Test
  fun `a census row for an unknown college is a foreign-key NotFoundException`() {
    val error =
      CollegeIpedsDao
        .upsertProgramsCensus(
          session,
          NewCollegeProgramsCensus(CollegeId(java.util.UUID.randomUUID()), "050104", 5, 5, 2023),
        ).exceptionOrNull()
    assertTrue(error is NotFoundException, "expected NotFoundException, got $error")
    // The FK arm keeps the same evidence its unique/check sibling keeps: without
    // the constraint name, the server DETAIL naming the offending college_id and
    // the driver SQLException as the cause, an ingest skip logs
    // [constraint=null] [detail=null] and a root-cause walk finds nothing.
    assertEquals("college_programs_census_college_id_fkey", error.constraint)
    assertTrue(
      error.detail?.contains("college_id") == true,
      "the server DETAIL must name the offending key: ${error.detail}",
    )
    assertTrue(error.cause is java.sql.SQLException, "the driver exception is the cause: ${error.cause}")
    assertTrue(
      error.message?.contains("college_programs_census_college_id_fkey") == true,
      "the message carries the constraint too: ${error.message}",
    )
  }

  // ---------------------------------------------------------------------------
  // Reads
  // ---------------------------------------------------------------------------

  @Test
  fun `collegeIdsByIpedsUnitId returns the whole match set keyed by ipeds_unit_id`() {
    val first = seedCollege(186131)
    val second = seedCollege(166027)
    val byIpedsUnitId = CollegeIpedsDao.collegeIdsByIpedsUnitId(session).getOrThrow()
    assertEquals(mapOf(186131 to first, 166027 to second), byIpedsUnitId)
  }

  @Test
  fun `nonNullCounts counts only the populated cells`() {
    CollegeIpedsDao.upsert(session, ipeds(161280)).getOrThrow()
    CollegeIpedsDao.upsert(session, ipeds(100690, testPolicy = null, relAffil = null)).getOrThrow()
    val counts = CollegeIpedsDao.nonNullCounts(session, listOf("test_policy", "rel_affil", "sector")).getOrThrow()
    assertEquals(mapOf("test_policy" to 1, "rel_affil" to 1, "sector" to 2), counts)
  }

  @Test
  fun `nonNullCounts refuses a column outside the closed allowlist`() {
    assertFailsWith<IllegalArgumentException> {
      CollegeIpedsDao.nonNullCounts(session, listOf("test_policy", "ipeds_unit_id; DROP TABLE colleges"))
    }
  }

  /**
   * The allowlist is hand-written next to a migration that owns the real column
   * set, so nothing but this assertion stops the two from drifting: a later
   * migration adding a nullable `college_ipeds` column would otherwise leave the
   * ingest's change summary silently short one axis. Every non-allowlisted
   * column of the table is NOT NULL, so the check needs no exclusion list.
   */
  @Test
  fun `the countable allowlist is exactly the nullable college_ipeds columns`() {
    val nullable = mutableSetOf<String>()
    connection
      .prepareStatement(
        """
        SELECT column_name FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'college_ipeds' AND is_nullable = 'YES'
        """.trimIndent(),
      ).use { stmt ->
        stmt.executeQuery().use { rs -> while (rs.next()) nullable += rs.getString("column_name") }
      }
    assertEquals(
      nullable,
      CollegeIpedsDao.NON_NULL_COUNTABLE_COLUMNS,
      "a nullable column added by a later migration must be counted, or removed from the allowlist",
    )
  }
}
