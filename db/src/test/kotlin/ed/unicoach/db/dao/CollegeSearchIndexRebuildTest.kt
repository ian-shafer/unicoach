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
import ed.unicoach.db.models.PriceRuler
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    /**
     * The two academic years these fixtures seed, in the shape the column now
     * stores.
     *
     * RFC 170 (`0087`) made `price_figures.academic_year` a SMALLINT DOMAIN
     * named by its FIRST calendar year — `2023` IS the 2023-24 academic year,
     * and the `'2023-24'` label is rendered at read time by `AcademicYear` and
     * never stored. The rebuild's `ORDER BY academic_year DESC` is now a numeric
     * ordering rather than a lexicographic one, which picks the same latest year
     * and is the more honest comparison.
     */
    private const val ACADEMIC_YEAR_2022_23 = 2022

    private const val ACADEMIC_YEAR_2023_24 = 2023

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

  /**
   * One raw `price_figures` row (RFC 169). Raw SQL, on the
   * `CanonicalMoneyDaoTest` precedent: `CanonicalMoneyDao`'s write API is
   * wholesale-only and carries change detection a fixture has no business
   * faking, and the rebuild reads the TABLE, not the DAO.
   *
   * A NULL [amountUsd] writes a row whose status bears no value — an absence to
   * report, never a zero. To model the other absence, the cell no source
   * carries, seed no row at all (P7).
   */
  private fun seedPriceFigure(
    collegeId: CollegeId,
    concept: String,
    residency: String,
    arrangement: String,
    amountUsd: Int?,
    academicYear: Int = ACADEMIC_YEAR_2023_24,
    status: String = if (amountUsd == null) "suppressed_by_publisher" else "reported",
  ) {
    connection
      .prepareStatement(
        "INSERT INTO price_figures (college_id, price_concept, residency_basis, arrangement, " +
          "academic_year, amount_usd, status, source, source_variable) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
      ).use { stmt ->
        stmt.setObject(1, collegeId.value)
        stmt.setString(2, concept)
        stmt.setString(3, residency)
        stmt.setString(4, arrangement)
        stmt.setInt(5, academicYear)
        if (amountUsd == null) stmt.setNull(6, java.sql.Types.INTEGER) else stmt.setInt(6, amountUsd)
        stmt.setString(7, status)
        stmt.setString(8, "ipeds_ic_ay")
        stmt.setString(9, "FIXTURE")
        stmt.executeUpdate()
      }
  }

  /**
   * The four cells a published on-campus total is made of, at both tuition
   * tiers: the shape a complete college has. A `null` argument seeds NO row for
   * that cell, which is how the source layer says "no figure" (P7).
   */
  private fun seedPublishedOnCampus(
    collegeId: CollegeId,
    inStateTuition: Int? = 11000,
    outOfStateTuition: Int? = 31000,
    housingAndFood: Int? = 12000,
    booksAndSupplies: Int? = 1200,
    otherExpenses: Int? = 2800,
    academicYear: Int = ACADEMIC_YEAR_2023_24,
  ) {
    inStateTuition?.let {
      seedPriceFigure(collegeId, "tuition_and_fees", "in_state", "not_applicable", it, academicYear)
    }
    outOfStateTuition?.let {
      seedPriceFigure(collegeId, "tuition_and_fees", "out_of_state", "not_applicable", it, academicYear)
    }
    housingAndFood?.let {
      seedPriceFigure(collegeId, "housing_and_food", "not_applicable", "on_campus", it, academicYear)
    }
    booksAndSupplies?.let {
      seedPriceFigure(collegeId, "books_and_supplies", "not_applicable", "not_applicable", it, academicYear)
    }
    otherExpenses?.let {
      seedPriceFigure(collegeId, "other_expenses", "not_applicable", "on_campus", it, academicYear)
    }
  }

  private fun intOrNull(
    column: String,
    ipedsUnitId: Int,
  ): Int? = readColumn(column, ipedsUnitId) { rs, c -> rs.getInt(c).takeUnless { rs.wasNull() } }

  private fun bigDecimalOrNull(
    column: String,
    ipedsUnitId: Int,
  ): java.math.BigDecimal? = readColumn(column, ipedsUnitId) { rs, c -> rs.getBigDecimal(c) }

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
  // The published-price ruler (RFC 169)
  // ---------------------------------------------------------------------------

  @Test
  fun `the published on-campus total is the sum of four canonical components`() {
    SearchIndexFixture.seedCodebooks(session)
    val id = insertCollege(newCollege(201500))
    seedPublishedOnCampus(id)
    CollegesDao.rebuildSearchIndex(session).getOrThrow()
    // 11000 + 12000 + 1200 + 2800 and 31000 + 12000 + 1200 + 2800: the SAME
    // three living components under both tuition tiers, because residency lives
    // on exactly one addend.
    assertEquals(27000, intOrNull("published_price_in_state_on_campus_per_year_usd", 201500))
    assertEquals(47000, intOrNull("published_price_out_of_state_on_campus_per_year_usd", 201500))
  }

  @Test
  fun `a private that publishes one price holds the same number under both tiers`() {
    SearchIndexFixture.seedCodebooks(session)
    val id = insertCollege(newCollege(201501, control = 2))
    seedPublishedOnCampus(id, inStateTuition = 48000, outOfStateTuition = 48000)
    CollegesDao.rebuildSearchIndex(session).getOrThrow()
    assertEquals(64000, intOrNull("published_price_in_state_on_campus_per_year_usd", 201501))
    assertEquals(64000, intOrNull("published_price_out_of_state_on_campus_per_year_usd", 201501))
  }

  @Test
  fun `a missing component makes the total NULL, not a short sum`() {
    SearchIndexFixture.seedCodebooks(session)
    // Three of the four parts: no books-and-supplies cell anywhere. A short sum
    // would make this school look 1,200 dollars cheaper than the school beside
    // it and sort it first -- which is why the rule is four parts or no number.
    val id = insertCollege(newCollege(201502))
    seedPublishedOnCampus(id, booksAndSupplies = null)
    // A college with NO canonical money at all keeps its index row (LEFT JOIN).
    insertCollege(newCollege(201503))
    CollegesDao.rebuildSearchIndex(session).getOrThrow()
    assertNull(intOrNull("published_price_in_state_on_campus_per_year_usd", 201502))
    assertNull(intOrNull("published_price_out_of_state_on_campus_per_year_usd", 201502))
    assertNull(intOrNull("published_price_in_state_on_campus_per_year_usd", 201503))
    assertEquals(2, indexRowCount())
  }

  @Test
  fun `one tier can be complete while the other is not`() {
    SearchIndexFixture.seedCodebooks(session)
    val id = insertCollege(newCollege(201504))
    seedPublishedOnCampus(id, outOfStateTuition = null)
    CollegesDao.rebuildSearchIndex(session).getOrThrow()
    assertEquals(27000, intOrNull("published_price_in_state_on_campus_per_year_usd", 201504))
    assertNull(intOrNull("published_price_out_of_state_on_campus_per_year_usd", 201504))
  }

  @Test
  fun `a figure with no value is an absence, not a zero`() {
    SearchIndexFixture.seedCodebooks(session)
    // The row EXISTS and says `suppressed_by_publisher`, so `amount_usd` is
    // NULL. Counting it as a component and summing it as zero would publish a
    // 25,800 dollar total for a school whose books line nobody reported.
    val id = insertCollege(newCollege(201505))
    seedPublishedOnCampus(id, booksAndSupplies = null)
    seedPriceFigure(id, "books_and_supplies", "not_applicable", "not_applicable", null)
    CollegesDao.rebuildSearchIndex(session).getOrThrow()
    assertNull(intOrNull("published_price_in_state_on_campus_per_year_usd", 201505))
    assertNull(intOrNull("published_price_out_of_state_on_campus_per_year_usd", 201505))
  }

  @Test
  fun `the latest academic year wins per college per figure`() {
    SearchIndexFixture.seedCodebooks(session)
    // An IPEDS-shaped college carrying two years of every cell...
    val ipedsShaped = insertCollege(newCollege(201506))
    seedPublishedOnCampus(ipedsShaped, academicYear = ACADEMIC_YEAR_2022_23)
    seedPublishedOnCampus(
      ipedsShaped,
      inStateTuition = 13000,
      outOfStateTuition = 33000,
      academicYear = ACADEMIC_YEAR_2023_24,
    )
    // ...and a Scorecard-only college that exists ONLY at 2022-23. A literal
    // IPEDS year in the rebuild would drop it entirely; the latest year is a
    // per-college fact, not one year across the table (brief 0006 D15).
    val scorecardOnly = insertCollege(newCollege(201507))
    seedPublishedOnCampus(scorecardOnly, academicYear = ACADEMIC_YEAR_2022_23)
    CollegesDao.rebuildSearchIndex(session).getOrThrow()
    // 13000 + the 2023-24 living components, not the 2022-23 tuition.
    assertEquals(29000, intOrNull("published_price_in_state_on_campus_per_year_usd", 201506))
    assertEquals(49000, intOrNull("published_price_out_of_state_on_campus_per_year_usd", 201506))
    assertEquals(27000, intOrNull("published_price_in_state_on_campus_per_year_usd", 201507))
  }

  @Test
  fun `an off-campus or with-family figure never enters the on-campus total`() {
    SearchIndexFixture.seedCodebooks(session)
    // The column NAMES its arrangement. An off-campus housing line is a real
    // figure and a different question; summing it here would put two
    // arrangements in one ladder.
    val id = insertCollege(newCollege(201508))
    seedPublishedOnCampus(id, housingAndFood = null)
    seedPriceFigure(id, "housing_and_food", "not_applicable", "off_campus", 9000)
    seedPriceFigure(id, "other_expenses", "not_applicable", "with_family", 1000)
    CollegesDao.rebuildSearchIndex(session).getOrThrow()
    assertNull(intOrNull("published_price_in_state_on_campus_per_year_usd", 201508))
  }

  @Test
  fun `the in-district tuition tier is not summed into either published column`() {
    SearchIndexFixture.seedCodebooks(session)
    // `in_district` is a real residency basis this RFC does not rank. It must
    // not be mistaken for the in-state tier, which would quote a community
    // college rate to a family the state never offered it to.
    val id = insertCollege(newCollege(201509))
    seedPublishedOnCampus(id, inStateTuition = null)
    seedPriceFigure(id, "tuition_and_fees", "in_district", "not_applicable", 4000)
    CollegesDao.rebuildSearchIndex(session).getOrThrow()
    assertNull(intOrNull("published_price_in_state_on_campus_per_year_usd", 201509))
    assertEquals(47000, intOrNull("published_price_out_of_state_on_campus_per_year_usd", 201509))
  }

  @Test
  fun `both published shares are positions on one ladder`() {
    SearchIndexFixture.seedCodebooks(session)
    // Four schools on the out-of-state ladder at 20k, 30k, 40k, 50k. The first
    // school's IN-STATE total is 40,000 -- the same number the third school
    // sits at out of state -- so if the in-state share were a second
    // percent_rank() over in-state values it would read differently from the
    // third school's. One ladder means one answer for one number.
    val tiers =
      listOf(
        201600 to (40000 to 20000),
        201601 to (30000 to 30000),
        201602 to (40000 to 40000),
        201603 to (50000 to 50000),
      )
    for ((unitId, prices) in tiers) {
      val (inState, outOfState) = prices
      val id = insertCollege(newCollege(unitId))
      seedPublishedOnCampus(
        id,
        inStateTuition = inState - 16000,
        outOfStateTuition = outOfState - 16000,
      )
    }
    CollegesDao.rebuildSearchIndex(session).getOrThrow()

    fun share(
      column: String,
      unitId: Int,
    ) = bigDecimalOrNull(column, unitId)?.toDouble()

    // (rank - 1) / (n - 1) over {20000, 30000, 40000, 50000}.
    assertEquals(0.0, share("published_price_out_of_state_on_campus_ladder_share", 201600))
    assertEquals(1.0 / 3.0, share("published_price_out_of_state_on_campus_ladder_share", 201601)!!, 0.0001)
    assertEquals(2.0 / 3.0, share("published_price_out_of_state_on_campus_ladder_share", 201602)!!, 0.0001)
    assertEquals(1.0, share("published_price_out_of_state_on_campus_ladder_share", 201603))
    // The in-state share of the first school is the place 40,000 takes on that
    // SAME ladder -- byte for byte the third school's out-of-state share.
    assertEquals(
      bigDecimalOrNull("published_price_out_of_state_on_campus_ladder_share", 201602),
      bigDecimalOrNull("published_price_in_state_on_campus_ladder_share", 201600),
    )
  }

  @Test
  fun `a corpus member's stored share equals percent_rank over that corpus`() {
    SearchIndexFixture.seedCodebooks(session)
    for ((i, tuition) in listOf(4000, 9000, 14000, 19000, 24000).withIndex()) {
      val id = insertCollege(newCollege(201610 + i))
      seedPublishedOnCampus(id, inStateTuition = tuition, outOfStateTuition = tuition)
    }
    CollegesDao.rebuildSearchIndex(session).getOrThrow()
    // The stored share is a count-of-strictly-smaller expression, not a window
    // function. This asserts the two definitions agree on the corpus itself, so
    // the in-state column can borrow the same expression without becoming a
    // second ruler.
    connection
      .prepareStatement(
        """
        SELECT count(*) AS disagreements
        FROM (
            SELECT ipeds_unit_id,
                   published_price_out_of_state_on_campus_ladder_share AS stored,
                   round(
                       (percent_rank() OVER (
                            ORDER BY published_price_out_of_state_on_campus_per_year_usd))::numeric,
                       4) AS windowed
            FROM college_search_index
            WHERE published_price_out_of_state_on_campus_per_year_usd IS NOT NULL
        ) t
        WHERE stored IS DISTINCT FROM windowed
        """.trimIndent(),
      ).use { stmt ->
        stmt.executeQuery().use { rs ->
          rs.next()
          assertEquals(0, rs.getInt("disagreements"), "the stored share IS percent_rank() on the corpus")
        }
      }
  }

  @Test
  fun `a row missing the published input still ranks on the others`() {
    SearchIndexFixture.seedCodebooks(session)
    insertCollege(newCollege(201620, undergradEnrollmentHeadcount = 1000))
    val priced = insertCollege(newCollege(201621, undergradEnrollmentHeadcount = 9000))
    seedPublishedOnCampus(priced)
    CollegesDao.rebuildSearchIndex(session).getOrThrow()
    assertEquals(0.0, readColumn("undergrad_enrollment_percentile_share", 201620) { rs, c -> rs.getDouble(c) })
    assertNull(bigDecimalOrNull("published_price_out_of_state_on_campus_ladder_share", 201620))
    assertNull(bigDecimalOrNull("published_price_in_state_on_campus_ladder_share", 201620))
  }

  @Test
  fun `published shares are NULL outside the default universe`() {
    SearchIndexFixture.seedCodebooks(session)
    // Two priced four-year schools, so the ladder has a corpus of more than one
    // and a NULL share below can only mean "outside the universe".
    for ((i, unitId) in listOf(201630, 201632).withIndex()) {
      val inside = insertCollege(newCollege(unitId))
      CollegeIpedsDao.upsert(session, newIpeds(unitId, instLevel = 1)).getOrThrow()
      seedPublishedOnCampus(inside, outOfStateTuition = 31000 + 1000 * i)
    }
    // A two-year school with a real published total: it is priced, and it is
    // still not on a ladder a four-year search ranks against.
    val outside = insertCollege(newCollege(201631))
    CollegeIpedsDao.upsert(session, newIpeds(201631, instLevel = 2, sector = 4)).getOrThrow()
    seedPublishedOnCampus(outside, inStateTuition = 2000, outOfStateTuition = 3000)
    CollegesDao.rebuildSearchIndex(session).getOrThrow()
    // The VALUE is written for every row -- the corpus is not restricted, only
    // the ladder's population is (brief 0005 §2.4).
    assertEquals(19000, intOrNull("published_price_out_of_state_on_campus_per_year_usd", 201631))
    assertNull(bigDecimalOrNull("published_price_out_of_state_on_campus_ladder_share", 201631))
    assertNotNull(bigDecimalOrNull("published_price_out_of_state_on_campus_ladder_share", 201630))
  }

  @Test
  fun `a one-school ladder is NULL, not a division error`() {
    SearchIndexFixture.seedCodebooks(session)
    val id = insertCollege(newCollege(201640))
    seedPublishedOnCampus(id)
    insertCollege(newCollege(201641))
    CollegesDao.rebuildSearchIndex(session).getOrThrow()
    assertEquals(47000, intOrNull("published_price_out_of_state_on_campus_per_year_usd", 201640))
    assertNull(bigDecimalOrNull("published_price_out_of_state_on_campus_ladder_share", 201640))
  }

  @Test
  fun `a share outside zero to one is refused by the percentile range CHECK`() {
    SearchIndexFixture.seedCodebooks(session)
    val id = insertCollege(newCollege(201650))
    seedPublishedOnCampus(id)
    CollegesDao.rebuildSearchIndex(session).getOrThrow()
    // Both NEW columns, named: a percentile column left outside the constraint
    // is an unpoliced one, which is why 0091 REPLACED the four-clause CHECK
    // rather than leaving it beside the new columns.
    for (
    column in
    listOf(
      "published_price_in_state_on_campus_ladder_share",
      "published_price_out_of_state_on_campus_ladder_share",
    )
    ) {
      val thrown =
        assertFailsWith<Exception>(column) {
          connection
            .prepareStatement("UPDATE college_search_index SET $column = 1.5 WHERE ipeds_unit_id = 201650")
            .use { it.executeUpdate() }
        }
      assertTrue(
        thrown.message!!.contains("college_search_index_percentile_range_check"),
        "[$column]: ${thrown.message}",
      )
    }
  }

  @Test
  fun `the index carries exactly six share columns, and D19 added no column at all`() {
    // Two pins in one, because they are the same claim from both sides.
    //
    // A percentile column outside the named CHECK is an unpoliced one -- the
    // reason 0091 REPLACED that constraint rather than supplementing it -- so
    // the share columns are read from the catalog and every one of them is
    // asserted to be inside it.
    //
    // And brief 0006 D19 costs NO DDL (option (a)): the tier-basis label is
    // derived live from `price_figures` for the returned page, so the index gains
    // no in-district column, no ladder and no btree. A column added here later
    // fails this test rather than arriving unnoticed as speculative DDL.
    val shareColumns = columnsMatching("%ladder_share", "%percentile_share")
    assertEquals(
      listOf(
        "admission_rate_percentile_share",
        "net_price_percentile_share",
        "published_price_in_state_on_campus_ladder_share",
        "published_price_out_of_state_on_campus_ladder_share",
        "sat_average_percentile_share",
        "undergrad_enrollment_percentile_share",
      ),
      shareColumns,
    )
    assertEquals(emptyList(), columnsMatching("%in_district%"), "D19 stores nothing on the index")

    val definition =
      connection
        .prepareStatement(
          "SELECT pg_get_constraintdef(oid) FROM pg_constraint " +
            "WHERE conname = 'college_search_index_percentile_range_check'",
        ).use { stmt ->
          stmt.executeQuery().use { rs ->
            assertTrue(rs.next(), "the named percentile CHECK must exist")
            rs.getString(1)
          }
        }
    shareColumns.forEach { column ->
      assertTrue(definition.contains(column), "[$column] must be policed by the range CHECK: $definition")
    }
  }

  /** `college_search_index` column names matching any of [patterns], sorted — read from the catalog, never typed. */
  private fun columnsMatching(vararg patterns: String): List<String> {
    val clause = patterns.joinToString(" OR ") { "column_name LIKE ?" }
    return connection
      .prepareStatement(
        "SELECT column_name FROM information_schema.columns " +
          "WHERE table_name = 'college_search_index' AND ($clause) ORDER BY column_name",
      ).use { stmt ->
        patterns.forEachIndexed { index, pattern -> stmt.setString(index + 1, pattern) }
        stmt.executeQuery().use { rs ->
          val names = mutableListOf<String>()
          while (rs.next()) names += rs.getString(1)
          names
        }
      }
  }

  @Test
  fun `a tuition status the vocabulary cannot read is a loud failure, not a silence`() {
    // `figure_statuses` is an AUTHORED VOCABULARY TABLE, so a seventh code can
    // exist in the database with no Kotlin change. Decoded to null it would be
    // the SAME value that means "no in_district row at all" -- and that silence
    // is exactly what prints the publisher-does-not-separate sentence to a
    // family. A code we cannot read must never become a claim we cannot support.
    SearchIndexFixture.seedCodebooks(session)
    val id = insertCollege(newCollege(201800, state = "NV", control = 1))
    seedPublishedOnCampus(id)
    CollegesDao.rebuildSearchIndex(session).getOrThrow()
    // Written past the enum, the way a new authored code would arrive.
    connection
      .prepareStatement("INSERT INTO figure_statuses (slug, description, value_bearing) VALUES (?, ?, FALSE)")
      .use { stmt ->
        stmt.setString(1, "withdrawn_by_publisher")
        stmt.setString(2, "a status this Kotlin build does not know")
        stmt.executeUpdate()
      }
    seedPriceFigure(id, "tuition_and_fees", "in_district", "not_applicable", null, status = "withdrawn_by_publisher")

    val thrown =
      assertFailsWith<Exception> {
        CollegesDao.search(session, CollegeQuery(priceRuler = PriceRuler.Published("NV"), limit = 25)).getOrThrow()
      }
    val message = generateSequence<Throwable>(thrown) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
    assertTrue(message.contains("withdrawn_by_publisher"), "the failure names the value it could not read: $message")
    assertTrue(message.contains("price_figures.status"), "and where it sits: $message")
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
      val id = insertCollege(newCollege(unitId, undergradEnrollmentHeadcount = enrollment))
      seedPublishedOnCampus(id, outOfStateTuition = 20000 + 1000 * i)
      CollegeIpedsDao.upsert(session, newIpeds(unitId, instLevel = 1)).getOrThrow()
    }
    // One out on each axis: a system central office, a two-year school, a
    // closed school. Each would be the top of a naive ranking -- and each
    // carries a published price too, so the published ladder has to exclude
    // them for the same reason and not because it has no number.
    seedPublishedOnCampus(insertCollege(newCollege(201410, undergradEnrollmentHeadcount = 20000)))
    CollegeIpedsDao.upsert(session, newIpeds(201410, instLevel = 1, sector = 0)).getOrThrow()
    seedPublishedOnCampus(insertCollege(newCollege(201411, undergradEnrollmentHeadcount = 30000)))
    CollegeIpedsDao.upsert(session, newIpeds(201411, instLevel = 2, sector = 4)).getOrThrow()
    seedPublishedOnCampus(insertCollege(newCollege(201412, undergradEnrollmentHeadcount = 40000)))
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
      // The published ladder answers to the same corpus (RFC 169). Every one of
      // these five colleges carries a complete published total, so a NULL share
      // here can only mean "outside the universe" -- the ladder's POPULATION is
      // restricted by a missing value, never the corpus by a filter.
      assertEquals(
        unitId in searched,
        bigDecimalOrNull("published_price_out_of_state_on_campus_ladder_share", unitId) != null,
        "[$unitId]: the published ladder ranks exactly the corpus the default search returns",
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
      // Canonical money on all but one college, at two academic years, so the
      // whole-row snapshot actually COVERS the published value and share
      // columns and the latest-year pick: a derivation that read a clock, or
      // that broke the tie between two years differently on a second pass,
      // would show up here rather than nowhere.
      if (i < 5) {
        seedPublishedOnCampus(id, academicYear = ACADEMIC_YEAR_2022_23)
        seedPublishedOnCampus(
          id,
          inStateTuition = 9000 + 500 * i,
          outOfStateTuition = 29000 + 700 * i,
          academicYear = ACADEMIC_YEAR_2023_24,
        )
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
