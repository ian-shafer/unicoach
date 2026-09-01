package ed.unicoach.db.dao

import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeQuery
import ed.unicoach.db.models.CollegeSearchOutcome
import ed.unicoach.db.models.InstitutionControl
import ed.unicoach.db.models.InstitutionSector
import ed.unicoach.db.models.NewAdmissionTestPolicy
import ed.unicoach.db.models.NewAthleticAssociation
import ed.unicoach.db.models.NewCarnegieBasicClass
import ed.unicoach.db.models.NewCarnegieSizeSetting
import ed.unicoach.db.models.NewCipCode
import ed.unicoach.db.models.NewCollege
import ed.unicoach.db.models.NewCollegeIpeds
import ed.unicoach.db.models.NewCollegeProgramsCensus
import ed.unicoach.db.models.NewIpedsRegion
import ed.unicoach.db.models.NewNcesLocale
import ed.unicoach.db.models.NewReligiousAffiliation
import ed.unicoach.db.models.NewSubject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [CollegesDao.rebuildSearchIndex] — the `search-index` phase's whole
 * derivation (RFC 150 D47/D51/D52/D59/D61).
 *
 * The suite seeds `colleges`, `college_ipeds`, `college_programs_census`, the
 * codebook reference tables and `subjects` directly rather than running an
 * ingest: the rebuild is what is under test, and a fixture CSV would put the
 * loaders between the assertion and the thing it asserts.
 */
class CollegeSearchIndexRebuildTest {
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
        SearchIndexFixture.truncate(connection)
        connection.close()
      }
    }
  }

  @BeforeEach
  fun resetDatabase() {
    connection.autoCommit = true
    SearchIndexFixture.truncate(connection)
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private fun seedSubjects() {
    CodebooksDao
      .upsertSubject(session, NewSubject("literature", "Literature", listOf("2301")))
      .getOrThrow()
    CodebooksDao
      .upsertSubject(session, NewSubject("biology", "Biology", listOf("26")))
      .getOrThrow()
  }

  private fun newCollege(
    ipedsUnitId: Int,
    name: String = "Test U $ipedsUnitId",
    state: String = "CA",
    control: Int = 1,
    region: Int? = 8,
    locale: Int? = 13,
    undergradEnrollmentHeadcount: Int? = 5000,
    admissionRateShare: Double? = 0.5,
    netPricePerYearUsd: Int? = 20000,
    satAverageEquivalentScore: Int? = 1200,
  ) = NewCollege(
    ipedsUnitId = ipedsUnitId,
    opeid = null,
    name = name,
    city = "Townsville",
    state = state,
    region = region,
    locale = locale,
    latitude = null,
    longitude = null,
    control = control,
    undergradEnrollmentHeadcount = undergradEnrollmentHeadcount,
    admissionRateShare = admissionRateShare,
    satAverageEquivalentScore = satAverageEquivalentScore,
    costOfAttendancePerYearUsd = null,
    netPricePerYearUsd = netPricePerYearUsd,
    netPricePerYearIncomeQ1Usd = null,
    netPricePerYearIncomeQ2Usd = null,
    netPricePerYearIncomeQ3Usd = null,
    netPricePerYearIncomeQ4Usd = null,
    netPricePerYearIncomeQ5Usd = null,
    tuitionAndFeesInStatePerYearUsd = null,
    tuitionAndFeesOutOfStatePerYearUsd = null,
    completionRate150pct4yrShare = 0.7,
    medianEarnings10yAfterEntryUsd = null,
    medianDebtAtCompletionUsd = null,
    housingAndFoodOnCampusPerYearUsd = null,
    housingAndFoodOffCampusPerYearUsd = null,
    booksAndSuppliesPerYearUsd = null,
    otherExpensesOnCampusPerYearUsd = null,
    otherExpensesOffCampusPerYearUsd = null,
    otherExpensesWithFamilyPerYearUsd = null,
    pellShare = null,
    website = null,
  )

  private fun insertCollege(input: NewCollege): CollegeId = CollegesDao.upsert(session, input).getOrThrow().id

  private fun newIpeds(
    ipedsUnitId: Int,
    sector: Int? = 1,
    instLevel: Int? = 1,
    cyActive: Boolean = true,
    deathYear: Int? = null,
    athleticAssoc: List<Int> = listOf(1),
    relAffil: Int? = 30,
    testPolicy: Int? = 5,
    carnegieBasic: Int? = 15,
    carnegieSize: Int? = 17,
  ) = NewCollegeIpeds(
    ipedsUnitId = ipedsUnitId,
    surveyYear = 2023,
    cyActive = cyActive,
    deathYear = deathYear,
    closedAt = null,
    newIpedsUnitId = null,
    instLevel = instLevel,
    ugOffer = true,
    sector = sector,
    carnegieBasic = carnegieBasic,
    carnegieSize = carnegieSize,
    cbsa = null,
    relAffil = relAffil,
    hasRotc = true,
    hasStudyAbroad = false,
    disabilityBand = null,
    registeredDisabilityPercent = null,
    offersHousing = true,
    housingCapacityHeadcount = null,
    applicationFeeUsd = null,
    athleticAssoc = athleticAssoc,
    footballConf = null,
    testPolicy = testPolicy,
  )

  /** Every column of every index row, as text, in a stable order — the D59 comparison. */
  private fun snapshotEveryColumn(): List<String> {
    val rows = mutableListOf<String>()
    connection
      .prepareStatement("SELECT i::text AS whole_row FROM college_search_index i ORDER BY i.college_id")
      .use { stmt ->
        stmt.executeQuery().use { rs ->
          while (rs.next()) rows += rs.getString("whole_row")
        }
      }
    return rows
  }

  private fun <T> readColumn(
    column: String,
    ipedsUnitId: Int,
    read: (java.sql.ResultSet, String) -> T,
  ): T {
    connection
      .prepareStatement("SELECT $column AS v FROM college_search_index WHERE ipeds_unit_id = ?")
      .use { stmt ->
        stmt.setInt(1, ipedsUnitId)
        stmt.executeQuery().use { rs ->
          assertTrue(rs.next(), "no index row for ipeds_unit_id $ipedsUnitId")
          return read(rs, "v")
        }
      }
  }

  private fun stringOrNull(
    column: String,
    ipedsUnitId: Int,
  ): String? = readColumn(column, ipedsUnitId) { rs, c -> rs.getString(c) }

  private fun stringList(
    column: String,
    ipedsUnitId: Int,
  ): List<String> = stringListOrNull(column, ipedsUnitId) ?: emptyList()

  /**
   * The same read, keeping the two states of an array column APART: null is
   * "nothing was reported" and an empty list is "reported: none" (RFC 150).
   * [stringList] flattens them, which is exactly the confusion the sentinel
   * default used to bake into the schema.
   */
  private fun stringListOrNull(
    column: String,
    ipedsUnitId: Int,
  ): List<String>? =
    readColumn(column, ipedsUnitId) { rs, c ->
      // `slug[]` comes back as Object[] of PGobject, not String[]: the element
      // type is a DOMAIN, which the driver maps to neither a String nor a
      // String array. `toString()` is the domain's text value.
      (rs.getArray(c)?.array as Array<*>?)?.map { it.toString() }
    }

  private fun indexRowCount(): Int {
    connection.prepareStatement("SELECT count(*) FROM college_search_index").use { stmt ->
      stmt.executeQuery().use { rs ->
        rs.next()
        return rs.getInt(1)
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Shape
  // ---------------------------------------------------------------------------

  @Test
  fun `rebuild writes exactly one row per college and returns that count`() {
    SearchIndexFixture.seedCodebooks(session)
    seedSubjects()
    repeat(4) { i -> insertCollege(newCollege(200000 + i)) }
    val written = CollegesDao.rebuildSearchIndex(session).getOrThrow()
    assertEquals(4, written, "one row per college, and the returned count IS that number")
    assertEquals(4, indexRowCount())
  }

  @Test
  fun `the index key is a real FK onto colleges, declared ON DELETE CASCADE`() {
    // Asserted from the catalog first, because in production this cascade can
    // never be OBSERVED: `colleges` carries `trigger_00_prevent_colleges_delete`
    // (0023), so a college is never deleted. The constraint is still what D47
    // argued for — it stops an index row from outliving its college through any
    // path that ever does remove one — so what a test can check is that it is
    // declared, and declared with the right action.
    connection
      .prepareStatement(
        """
        SELECT confdeltype, confupdtype
        FROM pg_constraint
        WHERE conrelid = 'college_search_index'::regclass
          AND confrelid = 'colleges'::regclass
          AND contype = 'f'
        """.trimIndent(),
      ).use { stmt ->
        stmt.executeQuery().use { rs ->
          assertTrue(rs.next(), "college_search_index must reference colleges")
          assertEquals("c", rs.getString("confdeltype"), "ON DELETE CASCADE")
          assertTrue(!rs.next(), "exactly one foreign key onto colleges")
        }
      }
  }

  @Test
  fun `an orphan index row is impossible by construction, and both guards are named`() {
    SearchIndexFixture.seedCodebooks(session)
    insertCollege(newCollege(200100))
    CollegesDao.rebuildSearchIndex(session).getOrThrow()
    assertEquals(1, indexRowCount())
    // The cascade cannot be OBSERVED here, and that is worth stating rather
    // than working around. `colleges` rows are protected twice over: by
    // `trigger_00_prevent_colleges_delete` (0023) and, past that, by
    // `colleges_versions_id_fkey`, which is RESTRICT because history must
    // outlive nothing. Suppressing both to watch one row disappear would be a
    // test of the suppression, not of the schema. What D47 buys is asserted
    // above, from the catalog: the reference EXISTS and its action is CASCADE,
    // so no path that ever does remove a college can leave an index row behind.
    connection
      .prepareStatement("SELECT count(*) FROM colleges c WHERE NOT EXISTS (SELECT 1 FROM college_search_index i WHERE i.college_id = c.id)")
      .use { stmt ->
        stmt.executeQuery().use { rs ->
          rs.next()
          assertEquals(0, rs.getInt(1), "every college has an index row after a rebuild")
        }
      }
  }

  // ---------------------------------------------------------------------------
  // The LEFT JOIN discipline (D61) — the assertion that catches an INNER JOIN
  // ---------------------------------------------------------------------------

  @Test
  fun `a college whose region code has no codebook row survives with region NULL`() {
    SearchIndexFixture.seedCodebooks(session)
    // Region 9 is a code `colleges` accepts and `ipeds_regions` (seeded with 8
    // only) does not explain — the exact shape of RFC 147 D46's unknown code.
    insertCollege(newCollege(200200, region = 9))
    insertCollege(newCollege(200201, region = 8))
    val written = CollegesDao.rebuildSearchIndex(session).getOrThrow()
    assertEquals(2, written, "an unknown code must NEVER drop a college from the index")
    assertNull(stringOrNull("region", 200200), "the column goes NULL; the college stays searchable")
    assertEquals("far-west", stringOrNull("region", 200201))
  }

  @Test
  fun `every coded column tolerates an unknown code and none of them drops the college`() {
    SearchIndexFixture.seedCodebooks(session)
    // Every code here must be one the shared fixture does NOT seed, or the
    // column resolves and the assertion below is asserting nothing. `locale` is
    // no longer among them: migration 0067 foreign-keys `colleges.locale` onto
    // `nces_locales`, so an unknown locale can no longer be STORED, let alone
    // reach the rebuild. Region 9 still can — `colleges.region` keeps a plain
    // 0..9 range check — and so can every `college_ipeds` code column below.
    insertCollege(newCollege(200300, region = 9))
    CollegeIpedsDao
      .upsert(
        session,
        newIpeds(200300, relAffil = 71, testPolicy = 1, carnegieBasic = 33, carnegieSize = 18, athleticAssoc = listOf(1, 6)),
      ).getOrThrow()
    assertEquals(1, CollegesDao.rebuildSearchIndex(session).getOrThrow())
    assertNull(stringOrNull("region", 200300))
    assertNull(stringOrNull("religious_affiliation", 200300))
    assertNull(stringOrNull("test_policy", 200300))
    assertNull(stringOrNull("carnegie_class", 200300))
    assertNull(stringOrNull("carnegie_size", 200300))
    // The array's asymmetry is deliberate: the unknown ORDINAL is dropped by the
    // inner join inside the LATERAL, the known one is kept, and the college is
    // kept by the outer LEFT JOIN.
    assertEquals(listOf("ncaa"), stringList("athletic_associations", 200300))
  }

  @Test
  fun `every element of every athletic_associations array is a real codebook slug`() {
    SearchIndexFixture.seedCodebooks(session)
    insertCollege(newCollege(200400))
    CollegeIpedsDao.upsert(session, newIpeds(200400, athleticAssoc = listOf(2, 1, 5))).getOrThrow()
    CollegesDao.rebuildSearchIndex(session).getOrThrow()
    // Postgres cannot foreign-key array ELEMENTS, so this test IS the constraint
    // (D61). Ordered by the published code, not by the stored ordinal order.
    assertEquals(listOf("ncaa", "naia"), stringList("athletic_associations", 200400))
    connection
      .prepareStatement(
        """
        SELECT count(*) FROM college_search_index i, unnest(i.athletic_associations) AS s
        WHERE NOT EXISTS (SELECT 1 FROM athletic_associations a WHERE a.slug = s)
        """.trimIndent(),
      ).use { stmt ->
        stmt.executeQuery().use { rs ->
          rs.next()
          assertEquals(0, rs.getInt(1), "no array element may be a slug the reference table does not define")
        }
      }
  }

  @Test
  fun `an unreported athletic_associations is NULL and a reported none is the empty array`() {
    SearchIndexFixture.seedCodebooks(session)
    insertCollege(newCollege(200410))
    CollegeIpedsDao.upsert(session, newIpeds(200410, athleticAssoc = listOf(1))).getOrThrow()
    // An IPEDS row that reports NO association: the KNOWN answer "belongs to
    // none", which is most of the country.
    insertCollege(newCollege(200411))
    CollegeIpedsDao.upsert(session, newIpeds(200411, athleticAssoc = emptyList())).getOrThrow()
    // No IPEDS row at all: nothing was reported either way.
    insertCollege(newCollege(200412))
    CollegesDao.rebuildSearchIndex(session).getOrThrow()

    assertEquals(listOf("ncaa"), stringListOrNull("athletic_associations", 200410))
    assertEquals(emptyList(), stringListOrNull("athletic_associations", 200411))
    assertNull(
      stringListOrNull("athletic_associations", 200412),
      "an unreported college must be NULL, not the '{}' sentinel that made it unjudgeable",
    )
  }

  // ---------------------------------------------------------------------------
  // The two authored vocabularies (D61a, D61b)
  // ---------------------------------------------------------------------------

  @Test
  fun `control stores the InstitutionControl label for every defined code`() {
    SearchIndexFixture.seedCodebooks(session)
    for (control in InstitutionControl.entries) {
      insertCollege(newCollege(200500 + control.code, control = control.code))
    }
    CollegesDao.rebuildSearchIndex(session).getOrThrow()
    for (control in InstitutionControl.entries) {
      assertEquals(control.label, stringOrNull("control", 200500 + control.code))
    }
  }

  @Test
  fun `a control code the enum does not define fails the rebuild rather than storing a blank`() {
    SearchIndexFixture.seedCodebooks(session)
    // `colleges_control_valid_check` (0015) is the only thing keeping a fourth
    // code out of `colleges` today, so suspending it is how this precondition is
    // reached at all. The subject is the REBUILD's own guard: `CONTROL_CASE`
    // yields NULL for an undefined code and `control` is NOT NULL, so the
    // statement must fail. A guard nothing ever triggers is a guard nothing
    // tests.
    connection.createStatement().use { it.execute("ALTER TABLE colleges DROP CONSTRAINT colleges_control_valid_check") }
    try {
      insertCollege(newCollege(200800, control = 4))
      val rebuilt = CollegesDao.rebuildSearchIndex(session)
      assertTrue(rebuilt.isFailure, "a school with no control word must not reach the index")
      // NAMED, not a bare NOT NULL violation: the operator needs the CODE and
      // how many colleges carry it, which a constraint name cannot give them.
      val error = rebuilt.exceptionOrNull()
      assertTrue(error is UnmappedControlCodeException, "expected a named failure, got: $error")
      assertEquals(mapOf("4" to 1), error.counts)
      assertTrue(error.message!!.contains("[4] on 1 row(s)"), error.message!!)
      assertEquals(0, indexRowCount(), "and the refused rebuild leaves no partial index behind")
    } finally {
      // TRUNCATE, not DELETE: `trigger_00_prevent_colleges_delete` (0023) makes
      // a college undeletable, and the CHECK cannot be re-added while the row
      // that violates it is still there.
      connection.createStatement().use {
        it.execute("TRUNCATE TABLE colleges, college_programs, college_ipeds, college_programs_census, college_search_index CASCADE")
      }
      connection.createStatement().use {
        it.execute("ALTER TABLE colleges ADD CONSTRAINT colleges_control_valid_check CHECK (control IN (1, 2, 3))")
      }
    }
  }

  @Test
  fun `an unmapped sector code is COUNTED, not silently degraded to NULL`() {
    SearchIndexFixture.seedCodebooks(session)
    // `college_ipeds_sector_domain_check` is what keeps a twelfth value out
    // today, so suspending it is how this precondition is reached. Unlike
    // `control` the rebuild has an honest NULL to fall back on, so the college
    // stays searchable — and that is precisely why it needs counting: nothing
    // else in the run would ever mention it.
    connection.createStatement().use { it.execute("ALTER TABLE college_ipeds DROP CONSTRAINT college_ipeds_sector_domain_check") }
    try {
      insertCollege(newCollege(200650))
      CollegeIpedsDao.upsert(session, newIpeds(200650, sector = 42)).getOrThrow()
      CollegesDao.rebuildSearchIndex(session).getOrThrow()

      assertNull(stringOrNull("sector", 200650), "an unnamed code degrades to NULL, keeping the college searchable")
      assertEquals(
        mapOf("42" to 1),
        CollegesDao.unmappedSectorCodes(session).getOrThrow(),
        "and the degradation is reported, with the code and the row count",
      )
    } finally {
      connection.createStatement().use {
        it.execute("TRUNCATE TABLE colleges, college_programs, college_ipeds, college_programs_census, college_search_index CASCADE")
      }
      connection.createStatement().use {
        it.execute(
          "ALTER TABLE college_ipeds ADD CONSTRAINT college_ipeds_sector_domain_check " +
            "CHECK (sector IS NULL OR sector BETWEEN 0 AND 9 OR sector = 99)",
        )
      }
    }
  }

  @Test
  fun `sector 99 is the word unknown and an absent IPEDS row is NULL`() {
    SearchIndexFixture.seedCodebooks(session)
    insertCollege(newCollege(200600))
    CollegeIpedsDao.upsert(session, newIpeds(200600, sector = 99)).getOrThrow()
    insertCollege(newCollege(200601)) // no college_ipeds row at all
    CollegesDao.rebuildSearchIndex(session).getOrThrow()
    // Two distinct outcomes, not one: the publisher REPORTED "unknown", while
    // the second college's source never reached the question (D61b).
    assertEquals("unknown", stringOrNull("sector", 200600))
    assertNull(stringOrNull("sector", 200601))
  }

  @Test
  fun `every InstitutionSector code round-trips to its word`() {
    SearchIndexFixture.seedCodebooks(session)
    for (sector in InstitutionSector.entries) {
      val unitId = 200700 + sector.code
      insertCollege(newCollege(unitId))
      CollegeIpedsDao.upsert(session, newIpeds(unitId, sector = sector.code)).getOrThrow()
    }
    CollegesDao.rebuildSearchIndex(session).getOrThrow()
    for (sector in InstitutionSector.entries) {
      assertEquals(sector.value, stringOrNull("sector", 200700 + sector.code))
    }
  }

  @Test
  fun `an absent IPEDS row leaves is_four_year NULL and is_active TRUE`() {
    SearchIndexFixture.seedCodebooks(session)
    insertCollege(newCollege(200800))
    CollegesDao.rebuildSearchIndex(session).getOrThrow()
    assertEquals(true, readColumn("is_active", 200800) { rs, c -> rs.getBoolean(c) })
    // Unknown level is NULL, never coalesced to false: D56's default universe
    // INCLUDES it.
    assertNull(readColumn("is_four_year", 200800) { rs, c -> rs.getBoolean(c).takeUnless { rs.wasNull() } })
  }

  @Test
  fun `a closed college is not active`() {
    SearchIndexFixture.seedCodebooks(session)
    insertCollege(newCollege(200900))
    CollegeIpedsDao.upsert(session, newIpeds(200900, cyActive = false, deathYear = 2019)).getOrThrow()
    CollegesDao.rebuildSearchIndex(session).getOrThrow()
    assertEquals(false, readColumn("is_active", 200900) { rs, c -> rs.getBoolean(c) })
  }

  // ---------------------------------------------------------------------------
  // The taxonomy expansion (D51)
  // ---------------------------------------------------------------------------

  @Test
  fun `subject_slugs and cip_codes are materialised from the census through the taxonomy`() {
    SearchIndexFixture.seedCodebooks(session)
    seedSubjects()
    val id = insertCollege(newCollege(201000))
    for (cip in listOf("230101", "260101")) {
      CollegeIpedsDao
        .upsertProgramsCensus(session, NewCollegeProgramsCensus(id, cip, 5, 12, 2023))
        .getOrThrow()
    }
    insertCollege(newCollege(201001)) // no census rows at all
    CollegesDao.rebuildSearchIndex(session).getOrThrow()
    assertEquals(listOf("230101", "260101"), stringListOrNull("cip_codes", 201000))
    assertEquals(listOf("biology", "literature"), stringListOrNull("subject_slugs", 201000))
    // NULL, not an empty array: this college reported no program census at all,
    // which is "we do not know", and the `excluded_unknown` count is built on
    // exactly that distinction. Under the old `NOT NULL DEFAULT '{}'` both
    // states collapsed into one and every judged NO was counted as unknown.
    assertNull(stringListOrNull("cip_codes", 201001))
    assertNull(stringListOrNull("subject_slugs", 201001))
  }

  @Test
  fun `subject_slugs is EMPTY when the programs are known and none is a subject`() {
    SearchIndexFixture.seedCodebooks(session)
    seedSubjects()
    val id = insertCollege(newCollege(201010))
    // A real census row whose code no seeded subject expands to: the programs
    // are KNOWN and none of them is a taxonomy subject. That is a judged NO —
    // an empty array — and it must not read as "we do not know".
    CollegeIpedsDao
      .upsertProgramsCensus(session, NewCollegeProgramsCensus(id, "231303", 5, 12, 2023))
      .getOrThrow()
    CollegesDao.rebuildSearchIndex(session).getOrThrow()
    assertEquals(listOf("231303"), stringListOrNull("cip_codes", 201010))
    assertEquals(emptyList(), stringListOrNull("subject_slugs", 201010))
  }

  // ---------------------------------------------------------------------------
  // Percentiles (D52)
  // ---------------------------------------------------------------------------

  @Test
  fun `percentiles are computed over the default universe only and are NULL outside it`() {
    SearchIndexFixture.seedCodebooks(session)
    // Three in the universe, with distinct enrollments.
    for ((i, enrollment) in listOf(1000, 5000, 9000).withIndex()) {
      val unitId = 201100 + i
      insertCollege(newCollege(unitId, undergradEnrollmentHeadcount = enrollment))
      CollegeIpedsDao.upsert(session, newIpeds(unitId, instLevel = 1)).getOrThrow()
    }
    // A two-year school: OUTSIDE the universe (is_four_year = false).
    insertCollege(newCollege(201110, undergradEnrollmentHeadcount = 20000))
    CollegeIpedsDao.upsert(session, newIpeds(201110, instLevel = 2, sector = 4)).getOrThrow()
    // An inactive school: also outside.
    insertCollege(newCollege(201111, undergradEnrollmentHeadcount = 30000))
    CollegeIpedsDao.upsert(session, newIpeds(201111, cyActive = false, deathYear = 2018)).getOrThrow()
    CollegesDao.rebuildSearchIndex(session).getOrThrow()

    fun percentile(unitId: Int): Double? =
      readColumn("undergrad_enrollment_percentile_share", unitId) { rs, c ->
        rs.getDouble(c).takeUnless { rs.wasNull() }
      }
    assertEquals(0.0, percentile(201100))
    assertEquals(0.5, percentile(201101))
    assertEquals(1.0, percentile(201102))
    // The 20,000-student two-year school would be the top of a naive ranking.
    // It is NULL, because it is not in the corpus a student is searching.
    assertNull(percentile(201110))
    assertNull(percentile(201111))
  }

  @Test
  fun `a row missing one percentile input still ranks on the others`() {
    SearchIndexFixture.seedCodebooks(session)
    insertCollege(newCollege(201200, undergradEnrollmentHeadcount = 1000, admissionRateShare = null))
    insertCollege(newCollege(201201, undergradEnrollmentHeadcount = 9000, admissionRateShare = 0.2))
    CollegesDao.rebuildSearchIndex(session).getOrThrow()
    assertEquals(
      0.0,
      readColumn("undergrad_enrollment_percentile_share", 201200) { rs, c -> rs.getDouble(c) },
    )
    assertNull(
      readColumn("admission_rate_percentile_share", 201200) { rs, c -> rs.getDouble(c).takeUnless { rs.wasNull() } },
    )
  }

  @Test
  fun `the SAT percentile reads its input from colleges, which the index does not carry`() {
    SearchIndexFixture.seedCodebooks(session)
    insertCollege(newCollege(201300, satAverageEquivalentScore = 1000))
    insertCollege(newCollege(201301, satAverageEquivalentScore = 1500))
    CollegesDao.rebuildSearchIndex(session).getOrThrow()
    assertEquals(0.0, readColumn("sat_average_percentile_share", 201300) { rs, c -> rs.getDouble(c) })
    assertEquals(1.0, readColumn("sat_average_percentile_share", 201301) { rs, c -> rs.getDouble(c) })
  }

  /**
   * The pin the two copies of the universe used to lack. The percentile corpus
   * and the corpus a default search returns are ONE definition (D52); when they
   * were written twice they had already diverged on `sector`, and a percentile
   * ranked a student against system central offices they can never apply to.
   */
  @Test
  fun `the percentile corpus is exactly the default search universe`() {
    SearchIndexFixture.seedCodebooks(session)
    // Two ordinary four-year colleges: inside on all three axes.
    for ((i, enrollment) in listOf(1000, 5000).withIndex()) {
      val unitId = 201400 + i
      insertCollege(newCollege(unitId, undergradEnrollmentHeadcount = enrollment))
      CollegeIpedsDao.upsert(session, newIpeds(unitId, instLevel = 1)).getOrThrow()
    }
    // One out on each axis: a system central office, a two-year school, a
    // closed school. Each would be the top of a naive ranking.
    insertCollege(newCollege(201410, undergradEnrollmentHeadcount = 20000))
    CollegeIpedsDao.upsert(session, newIpeds(201410, instLevel = 1, sector = 0)).getOrThrow()
    insertCollege(newCollege(201411, undergradEnrollmentHeadcount = 30000))
    CollegeIpedsDao.upsert(session, newIpeds(201411, instLevel = 2, sector = 4)).getOrThrow()
    insertCollege(newCollege(201412, undergradEnrollmentHeadcount = 40000))
    CollegeIpedsDao.upsert(session, newIpeds(201412, cyActive = false, deathYear = 2018)).getOrThrow()
    CollegesDao.rebuildSearchIndex(session).getOrThrow()

    val searched =
      when (val outcome = CollegesDao.search(session, CollegeQuery(limit = 25)).getOrThrow()) {
        is CollegeSearchOutcome.Page -> {
          outcome.page.matches
            .map { it.ipedsUnitId }
            .toSet()
        }

        is CollegeSearchOutcome.UnresolvableProgramFilter -> {
          throw AssertionError("a refusal: [${outcome.field}] [${outcome.value}] ${outcome.cause}")
        }

        is CollegeSearchOutcome.IndexNotBuilt -> {
          throw AssertionError("college_search_index has never been built")
        }
      }
    for (unitId in listOf(201400, 201401, 201410, 201411, 201412)) {
      val ranked =
        readColumn("undergrad_enrollment_percentile_share", unitId) { rs, c ->
          rs.getDouble(c).takeUnless { rs.wasNull() }
        } != null
      assertEquals(
        unitId in searched,
        ranked,
        "[$unitId]: a percentile must describe exactly the corpus the default search returns",
      )
    }
  }

  // ---------------------------------------------------------------------------
  // Reproducibility (D59) — the acceptance criterion
  // ---------------------------------------------------------------------------

  @Test
  fun `rebuilding twice reproduces every column of every row`() {
    SearchIndexFixture.seedCodebooks(session)
    seedSubjects()
    for (i in 0 until 6) {
      val unitId = 201400 + i
      val id =
        insertCollege(
          newCollege(
            unitId,
            name = "Reproducible College $i",
            control = 1 + (i % 3),
            region = if (i % 2 == 0) 8 else 9,
            locale = if (i % 3 == 0) 13 else null,
            undergradEnrollmentHeadcount = 1000 * (i + 1),
            admissionRateShare = 0.1 * (i + 1),
            netPricePerYearUsd = 10000 + 1000 * i,
            satAverageEquivalentScore = 1000 + 50 * i,
          ),
        )
      CollegeIpedsDao
        .upsert(session, newIpeds(unitId, sector = if (i == 5) 99 else 1, athleticAssoc = listOf(2, 1)))
        .getOrThrow()
      for (cip in listOf("230101", "260101")) {
        CollegeIpedsDao.upsertProgramsCensus(session, NewCollegeProgramsCensus(id, cip, 5, 10 + i, 2023)).getOrThrow()
      }
    }
    CollegesDao.rebuildSearchIndex(session).getOrThrow()
    val first = snapshotEveryColumn()
    assertEquals(6, first.size)
    CollegesDao.rebuildSearchIndex(session).getOrThrow()
    val second = snapshotEveryColumn()
    // EVERY column, with no exclusions: D60 removed `build_id`, which was the
    // one column that would have had to be exempted, and no column holds NOW().
    assertEquals(first, second, "the same snapshot at the same method_version must reproduce the index")
  }
}
