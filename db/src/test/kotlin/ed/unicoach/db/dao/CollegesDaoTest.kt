package ed.unicoach.db.dao

import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeQuery
import ed.unicoach.db.models.CredentialLevel
import ed.unicoach.db.models.NewCollege
import ed.unicoach.db.models.NewCollegeProgram
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.SQLException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollegesDaoTest {
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
      stmt.execute("TRUNCATE TABLE colleges, college_programs CASCADE")
    }
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private fun newCollege(
    unitId: Int,
    name: String = "Test U $unitId",
    city: String = "Townsville",
    state: String = "CA",
    control: Int = 1,
    undergradEnrollment: Int? = 5000,
    admissionRate: Double? = 0.5,
    netPrice: Int? = 20000,
    netPriceQ1: Int? = 9000,
    netPriceQ2: Int? = 11000,
    netPriceQ3: Int? = 14000,
    netPriceQ4: Int? = 17000,
    netPriceQ5: Int? = 21000,
    graduationRate: Double? = 0.7,
    medianEarnings: Int? = 55000,
    medianDebt: Int? = 23000,
    pctPell: Double? = 0.4,
    locale: Int? = 13,
    region: Int? = 8,
  ) = NewCollege(
    unitId = unitId,
    opeid = "0012$unitId",
    name = name,
    city = city,
    state = state,
    region = region,
    locale = locale,
    latitude = 34.0,
    longitude = -118.0,
    control = control,
    undergradEnrollment = undergradEnrollment,
    admissionRate = admissionRate,
    satAvg = 1200,
    costAttendance = 40000,
    netPrice = netPrice,
    netPriceQ1 = netPriceQ1,
    netPriceQ2 = netPriceQ2,
    netPriceQ3 = netPriceQ3,
    netPriceQ4 = netPriceQ4,
    netPriceQ5 = netPriceQ5,
    tuitionInState = 12000,
    tuitionOutState = 30000,
    graduationRate = graduationRate,
    medianEarnings = medianEarnings,
    medianDebt = medianDebt,
    pctPell = pctPell,
    website = "https://test$unitId.edu",
  )

  private fun seed(input: NewCollege): CollegeId = CollegesDao.upsert(session, input).getOrThrow().id

  // ---------------------------------------------------------------------------
  // Upserts
  // ---------------------------------------------------------------------------

  @Test
  fun `upsert inserts a new college and returns it with a generated id`() {
    val college = CollegesDao.upsert(session, newCollege(100100)).getOrThrow()
    assertNotNull(college.id)
    assertEquals(100100, college.unitId)
    assertEquals(1, college.control)
    assertEquals(20000, college.netPrice)
    assertEquals(9000, college.netPriceQ1)
    assertEquals(11000, college.netPriceQ2)
    assertEquals(14000, college.netPriceQ3)
    assertEquals(17000, college.netPriceQ4)
    assertEquals(21000, college.netPriceQ5)
    assertEquals(23000, college.medianDebt)
  }

  @Test
  fun `upsert on existing unit_id updates in place and advances updated_at`() {
    val first = CollegesDao.upsert(session, newCollege(100200, name = "Old Name")).getOrThrow()
    Thread.sleep(5)
    val second = CollegesDao.upsert(session, newCollege(100200, name = "New Name")).getOrThrow()

    assertEquals(first.id, second.id)
    assertEquals("New Name", second.name)
    assertTrue(!second.updatedAt.isBefore(first.updatedAt))

    val count =
      connection.prepareStatement("SELECT count(*) FROM colleges WHERE unit_id = 100200").use { stmt ->
        stmt.executeQuery().use { rs ->
          rs.next()
          rs.getInt(1)
        }
      }
    assertEquals(1, count)
  }

  @Test
  fun `upsertProgram enforces (college_id, cip_code, credential_level) uniqueness`() {
    val collegeId = seed(newCollege(100300))
    val first =
      CollegesDao
        .upsertProgram(session, NewCollegeProgram(collegeId, "260702", "Marine Biology", 3))
        .getOrThrow()
    val second =
      CollegesDao
        .upsertProgram(session, NewCollegeProgram(collegeId, "260702", "Marine Biology and Oceanography", 3))
        .getOrThrow()

    assertEquals(first.id, second.id)
    assertEquals("Marine Biology and Oceanography", second.cipTitle)

    val count =
      connection.prepareStatement("SELECT count(*) FROM college_programs").use { stmt ->
        stmt.executeQuery().use { rs ->
          rs.next()
          rs.getInt(1)
        }
      }
    assertEquals(1, count)
  }

  // ---------------------------------------------------------------------------
  // Constraint enforcement
  // ---------------------------------------------------------------------------

  @Test
  fun `control outside the set is rejected`() {
    val result = CollegesDao.upsert(session, newCollege(100400, control = 9))
    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is ConstraintViolationException)
  }

  @Test
  fun `admission_rate above 1 is rejected`() {
    val result = CollegesDao.upsert(session, newCollege(100500, admissionRate = 1.5))
    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is ConstraintViolationException)
  }

  @Test
  fun `negative undergrad_enrollment is rejected`() {
    val result = CollegesDao.upsert(session, newCollege(100600, undergradEnrollment = -1))
    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is ConstraintViolationException)
  }

  @Test
  fun `negative net_price is accepted`() {
    // Net price is cost of attendance minus average aid, so a heavily-subsidized
    // institution (e.g. a community college) publishes a negative figure.
    val result = CollegesDao.upsert(session, newCollege(100650, netPrice = -982))
    assertTrue(result.isSuccess, "expected negative net_price to be accepted")
    assertEquals(-982, result.getOrThrow().netPrice)
  }

  @Test
  fun `negative band net price is accepted, negative median_debt is rejected`() {
    // The five band columns follow the net_price precedent (0022): no nonneg
    // CHECK, because aid exceeding cost publishes a negative figure -- and the
    // low-income bands go negative most often.
    val ok = CollegesDao.upsert(session, newCollege(100660, netPriceQ1 = -1913))
    assertTrue(ok.isSuccess, "expected negative net_price_q1 to be accepted")
    assertEquals(-1913, ok.getOrThrow().netPriceQ1)

    // median_debt is a loan amount: genuinely nonneg, CHECK-backed.
    val bad = CollegesDao.upsert(session, newCollege(100661, medianDebt = -1))
    assertTrue(bad.isFailure)
    assertTrue(bad.exceptionOrNull() is ConstraintViolationException)
  }

  @Test
  fun `state of length not two is rejected`() {
    val result = CollegesDao.upsert(session, newCollege(100700, state = "CAL"))
    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is ConstraintViolationException)
  }

  @Test
  fun `cip_code at 2, 4 or 6 digits is accepted`() {
    val collegeId = seed(newCollege(100800))
    // The three real CIP widths: series, family, detail. The Scorecard
    // Field-of-Study file carries all three; the 4-digit '0901' is the case
    // the old six-only CHECK wrongly rejected.
    for (cip in listOf("09", "0901", "090101")) {
      val result = CollegesDao.upsertProgram(session, NewCollegeProgram(collegeId, cip, "Comm", 3))
      assertTrue(result.isSuccess, "expected cip_code [$cip] to be accepted")
    }
  }

  @Test
  fun `cip_code of an impossible width or non-digit is rejected`() {
    val collegeId = seed(newCollege(100850))
    // 1/3/5-digit widths cannot be a real CIP (a 3-digit value is most often a
    // leading zero stripped from a 4-digit code); 7 digits overshoots detail.
    for (cip in listOf("9", "260", "26070", "2607021", "26x7")) {
      val result = CollegesDao.upsertProgram(session, NewCollegeProgram(collegeId, cip, "Bad Cip", 3))
      assertTrue(result.isFailure, "expected cip_code [$cip] to be rejected")
      assertTrue(result.exceptionOrNull() is ConstraintViolationException)
    }
  }

  @Test
  fun `ConstraintViolationException carries the constraint name and detail`() {
    // A named CHECK violation (23514) surfaces the constraint that failed and
    // the server DETAIL line (the "Failing row contains (...)" tuple Postgres
    // attaches for a CHECK violation), captured verbatim for log drill-down.
    val checkResult = CollegesDao.upsert(session, newCollege(100870, control = 4))
    assertTrue(checkResult.isFailure)
    val checkError = checkResult.exceptionOrNull() as ConstraintViolationException
    assertEquals("colleges_control_valid_check", checkError.constraint)
    val detail = checkError.detail
    assertNotNull(detail)
    assertTrue(
      detail.contains("Failing row"),
      "expected CHECK detail to carry the failing row, got [$detail]",
    )
  }

  // ---------------------------------------------------------------------------
  // findByUnitId
  // ---------------------------------------------------------------------------

  @Test
  fun `findByUnitId returns the row or null`() {
    seed(newCollege(100900))
    assertNotNull(CollegesDao.findByUnitId(session, 100900).getOrThrow())
    assertNull(CollegesDao.findByUnitId(session, 999999).getOrThrow())
  }

  // ---------------------------------------------------------------------------
  // search
  // ---------------------------------------------------------------------------

  @Test
  fun `search with no filters returns all rows ordered by enrollment desc, unit_id asc`() {
    seed(newCollege(201, undergradEnrollment = 1000))
    seed(newCollege(202, undergradEnrollment = 9000))
    seed(newCollege(203, undergradEnrollment = 9000))

    val matches = CollegesDao.search(session, CollegeQuery(limit = 25)).getOrThrow().matches
    assertEquals(listOf(202, 203, 201), matches.map { it.unitId })
  }

  @Test
  fun `search by cipPrefix joins programs and matches 2, 4 and 6 digit prefixes`() {
    val collegeId = seed(newCollege(301))
    CollegesDao.upsertProgram(session, NewCollegeProgram(collegeId, "260702", "Marine Biology", 3)).getOrThrow()

    for (prefix in listOf("26", "2607", "260702")) {
      val matches = CollegesDao.search(session, CollegeQuery(cipPrefix = prefix, limit = 25)).getOrThrow().matches
      assertEquals(1, matches.size, "prefix $prefix should match")
      assertEquals(listOf("Marine Biology"), matches.single().programTitles)
    }

    val miss = CollegesDao.search(session, CollegeQuery(cipPrefix = "27", limit = 25)).getOrThrow().matches
    assertTrue(miss.isEmpty())
  }

  @Test
  fun `search by maxNetPrice includes and excludes`() {
    seed(newCollege(401, netPrice = 10000))
    seed(newCollege(402, netPrice = 40000))
    val matches = CollegesDao.search(session, CollegeQuery(maxNetPrice = 20000, limit = 25)).getOrThrow().matches
    assertEquals(listOf(401), matches.map { it.unitId })
  }

  @Test
  fun `search by size band includes and excludes`() {
    seed(newCollege(411, undergradEnrollment = 800))
    seed(newCollege(412, undergradEnrollment = 5000))
    seed(newCollege(413, undergradEnrollment = 50000))
    val matches =
      CollegesDao
        .search(session, CollegeQuery(minUndergradEnrollment = 1000, maxUndergradEnrollment = 10000, limit = 25))
        .getOrThrow()
        .matches
    assertEquals(listOf(412), matches.map { it.unitId })
  }

  @Test
  fun `search by states includes and excludes`() {
    seed(newCollege(421, state = "CA"))
    seed(newCollege(422, state = "OR"))
    seed(newCollege(423, state = "TX"))
    val matches = CollegesDao.search(session, CollegeQuery(states = listOf("CA", "OR"), limit = 25)).getOrThrow().matches
    assertEquals(setOf(421, 422), matches.map { it.unitId }.toSet())
  }

  @Test
  fun `search by control includes and excludes`() {
    seed(newCollege(431, control = 1))
    seed(newCollege(432, control = 2))
    seed(newCollege(433, control = 3))
    val matches = CollegesDao.search(session, CollegeQuery(control = listOf(2, 3), limit = 25)).getOrThrow().matches
    assertEquals(setOf(432, 433), matches.map { it.unitId }.toSet())
  }

  @Test
  fun `search by admission rate band includes and excludes`() {
    seed(newCollege(441, admissionRate = 0.1))
    seed(newCollege(442, admissionRate = 0.5))
    seed(newCollege(443, admissionRate = 0.9))
    val matches =
      CollegesDao
        .search(session, CollegeQuery(minAdmissionRate = 0.2, maxAdmissionRate = 0.6, limit = 25))
        .getOrThrow()
        .matches
    assertEquals(listOf(442), matches.map { it.unitId })
  }

  @Test
  fun `search by minGraduationRate includes and excludes`() {
    seed(newCollege(451, graduationRate = 0.4))
    seed(newCollege(452, graduationRate = 0.8))
    val matches = CollegesDao.search(session, CollegeQuery(minGraduationRate = 0.6, limit = 25)).getOrThrow().matches
    assertEquals(listOf(452), matches.map { it.unitId })
  }

  @Test
  fun `search returns the outcome columns`() {
    seed(newCollege(501, graduationRate = 0.65, medianEarnings = 62000, pctPell = 0.33))
    val match =
      CollegesDao
        .search(session, CollegeQuery(limit = 25))
        .getOrThrow()
        .matches
        .single()
    assertEquals(0.65, match.graduationRate)
    assertEquals(62000, match.medianEarnings)
    assertEquals(0.33, match.pctPell)
    assertEquals(9000, match.netPriceQ1)
    assertEquals(11000, match.netPriceQ2)
    assertEquals(14000, match.netPriceQ3)
    assertEquals(17000, match.netPriceQ4)
    assertEquals(21000, match.netPriceQ5)
    assertEquals(23000, match.medianDebt)
  }

  @Test
  fun `search combines filters conjunctively`() {
    // The motivating example: small + coastal-state set + marine-biology CIP + net-price ceiling.
    val target = seed(newCollege(601, state = "CA", undergradEnrollment = 2000, netPrice = 18000))
    CollegesDao.upsertProgram(session, NewCollegeProgram(target, "260702", "Marine Biology", 3)).getOrThrow()

    // Too big.
    val big = seed(newCollege(602, state = "OR", undergradEnrollment = 40000, netPrice = 18000))
    CollegesDao.upsertProgram(session, NewCollegeProgram(big, "260702", "Marine Biology", 3)).getOrThrow()

    // No marine biology program.
    seed(newCollege(603, state = "CA", undergradEnrollment = 2000, netPrice = 18000))

    // Too expensive.
    val pricey = seed(newCollege(604, state = "CA", undergradEnrollment = 2000, netPrice = 60000))
    CollegesDao.upsertProgram(session, NewCollegeProgram(pricey, "260702", "Marine Biology", 3)).getOrThrow()

    val matches =
      CollegesDao
        .search(
          session,
          CollegeQuery(
            cipPrefix = "2607",
            states = listOf("CA", "OR", "WA"),
            maxUndergradEnrollment = 5000,
            maxNetPrice = 25000,
            limit = 25,
          ),
        ).getOrThrow()
        .matches
    assertEquals(listOf(601), matches.map { it.unitId })
  }

  @Test
  fun `search applies limit and the limit is honored at the SQL level`() {
    for (u in 700..710) seed(newCollege(u, undergradEnrollment = u))
    val matches = CollegesDao.search(session, CollegeQuery(limit = 3)).getOrThrow().matches
    assertEquals(3, matches.size)
  }

  // ---------------------------------------------------------------------------
  // Versioning (RFC 82)
  // ---------------------------------------------------------------------------

  @Test
  fun `upsert inserts at version 1 and logs one history row`() {
    val college = CollegesDao.upsert(session, newCollege(800100, name = "Original")).getOrThrow()
    assertEquals(1, college.version)

    val history = CollegesDao.listVersions(session, college.id).getOrThrow()
    assertEquals(1, history.size)
    assertEquals(1, history.single().version)
    assertEquals("Original", history.single().entity.name)
    assertEquals(college.unitId, history.single().entity.unitId)
  }

  @Test
  fun `upsert of changed content bumps version and logs a second history row`() {
    val first = CollegesDao.upsert(session, newCollege(800200, name = "Old Name")).getOrThrow()
    assertEquals(1, first.version)
    val second = CollegesDao.upsert(session, newCollege(800200, name = "New Name")).getOrThrow()
    assertEquals(2, second.version)
    assertEquals("New Name", second.name)

    val history = CollegesDao.listVersions(session, first.id).getOrThrow()
    assertEquals(listOf(1, 2), history.map { it.version })
    assertEquals("New Name", history.last().entity.name)
  }

  @Test
  fun `re-upsert of identical content is a no-op`() {
    val input = newCollege(800300, name = "Steady U")
    val first = CollegesDao.upsert(session, input).getOrThrow()
    assertEquals(1, first.version)
    val second = CollegesDao.upsert(session, input).getOrThrow()
    assertEquals(1, second.version, "identical re-upsert must not bump version")
    assertEquals(first.id, second.id)
    assertEquals(first.updatedAt, second.updatedAt, "no-op must not advance updated_at")

    val history = CollegesDao.listVersions(session, first.id).getOrThrow()
    assertEquals(1, history.size)
  }

  @Test
  fun `a change in only net_price_q3 bumps version and logs history carrying all six new fields`() {
    // RFC 133: the six new columns are in the upsert's IS DISTINCT FROM tuple,
    // so a re-ingest differing only in one band price is a real content change.
    val first = CollegesDao.upsert(session, newCollege(800250)).getOrThrow()
    assertEquals(1, first.version)

    val second = CollegesDao.upsert(session, newCollege(800250, netPriceQ3 = 14500)).getOrThrow()
    assertEquals(2, second.version)
    assertEquals(14500, second.netPriceQ3)

    val history = CollegesDao.listVersions(session, first.id).getOrThrow()
    assertEquals(listOf(1, 2), history.map { it.version })
    val latest = history.last().entity
    assertEquals(9000, latest.netPriceQ1)
    assertEquals(11000, latest.netPriceQ2)
    assertEquals(14500, latest.netPriceQ3)
    assertEquals(17000, latest.netPriceQ4)
    assertEquals(21000, latest.netPriceQ5)
    assertEquals(23000, latest.medianDebt)
  }

  @Test
  fun `upsert preserves id and created_at across a change`() {
    val first = CollegesDao.upsert(session, newCollege(800400, name = "Before")).getOrThrow()
    val second = CollegesDao.upsert(session, newCollege(800400, name = "After")).getOrThrow()
    assertEquals(first.id, second.id)
    assertEquals(first.createdAt, second.createdAt)
  }

  @Test
  fun `findById returns the row, or NotFoundException when absent`() {
    val seeded = CollegesDao.upsert(session, newCollege(800500)).getOrThrow()
    assertEquals(seeded.id, CollegesDao.findById(session, seeded.id).getOrThrow().id)

    val missing = CollegesDao.findById(session, CollegeId(UUID.randomUUID()))
    assertTrue(missing.isFailure)
    assertTrue(missing.exceptionOrNull() is NotFoundException)
  }

  @Test
  fun `list pages name-stable with limit and offset`() {
    seed(newCollege(800601, name = "Charlie College"))
    seed(newCollege(800602, name = "Alpha College"))
    seed(newCollege(800603, name = "Bravo College"))
    seed(newCollege(800604, name = "Delta College"))

    val page1 = CollegesDao.list(session, limit = 2, offset = 0).getOrThrow()
    val page2 = CollegesDao.list(session, limit = 2, offset = 2).getOrThrow()
    assertEquals(listOf("Alpha College", "Bravo College"), page1.map { it.name })
    assertEquals(listOf("Charlie College", "Delta College"), page2.map { it.name })
    assertTrue((page1.map { it.id } + page2.map { it.id }).toSet().size == 4, "pages must not overlap")
  }

  @Test
  fun `listVersions orders ascending by version`() {
    val first = CollegesDao.upsert(session, newCollege(800700, name = "v1")).getOrThrow()
    CollegesDao.upsert(session, newCollege(800700, name = "v2")).getOrThrow()
    CollegesDao.upsert(session, newCollege(800700, name = "v3")).getOrThrow()
    CollegesDao.upsert(session, newCollege(800700, name = "v4")).getOrThrow()

    val history = CollegesDao.listVersions(session, first.id).getOrThrow()
    assertEquals(listOf(1, 2, 3, 4), history.map { it.version })
  }

  @Test
  fun `listByIds returns the full rows for the given ids and skips absent ones`() {
    val a = seed(newCollege(800750, name = "Alpha U"))
    val b = seed(newCollege(800751, name = "Beta U"))
    seed(newCollege(800752, name = "Gamma U"))

    val rows = CollegesDao.listByIds(session, listOf(a, b, CollegeId(UUID.randomUUID()))).getOrThrow()
    assertEquals(setOf("Alpha U", "Beta U"), rows.map { it.name }.toSet())
    assertEquals(9000, rows.first { it.id == a }.netPriceQ1, "the full cost columns must ride the row")

    assertEquals(emptyList(), CollegesDao.listByIds(session, emptyList()).getOrThrow(), "empty ids short-circuit")
  }

  @Test
  fun `physical delete on colleges is rejected`() {
    val college = CollegesDao.upsert(session, newCollege(800800)).getOrThrow()
    assertFailsWith<SQLException> {
      connection.prepareStatement("DELETE FROM colleges WHERE id = ?").use { stmt ->
        stmt.setObject(1, college.id.value)
        stmt.executeUpdate()
      }
    }
  }

  @Test
  fun `updating id or created_at on colleges is rejected`() {
    val college = CollegesDao.upsert(session, newCollege(800900)).getOrThrow()
    assertFailsWith<SQLException> {
      connection.prepareStatement("UPDATE colleges SET id = ? WHERE id = ?").use { stmt ->
        stmt.setObject(1, UUID.randomUUID())
        stmt.setObject(2, college.id.value)
        stmt.executeUpdate()
      }
    }
    assertFailsWith<SQLException> {
      connection.prepareStatement("UPDATE colleges SET created_at = NOW() + INTERVAL '1 day' WHERE id = ?").use { stmt ->
        stmt.setObject(1, college.id.value)
        stmt.executeUpdate()
      }
    }
  }

  // ---------------------------------------------------------------------------
  // searchByName (RFC 137)
  // ---------------------------------------------------------------------------

  @Test
  fun `searchByName matches case-insensitive substrings`() {
    seed(newCollege(810100, name = "Columbia University"))
    seed(newCollege(810101, name = "University of Michigan"))

    val matches = CollegesDao.searchByName(session, "columbia", 25).getOrThrow()
    assertEquals(listOf("Columbia University"), matches.map { it.name })
    assertEquals("Townsville", matches.single().city)
    assertEquals("CA", matches.single().state)
  }

  @Test
  fun `searchByName orders prefix matches first, then enrollment desc, then name`() {
    seed(newCollege(810200, name = "District of Columbia College", undergradEnrollment = 90000))
    seed(newCollege(810201, name = "Columbia College", undergradEnrollment = 900))
    seed(newCollege(810202, name = "Columbia University", undergradEnrollment = 30000))
    seed(newCollege(810203, name = "Columbia Bible College", undergradEnrollment = null))

    val matches = CollegesDao.searchByName(session, "Columbia", 25).getOrThrow()
    assertEquals(
      listOf("Columbia University", "Columbia College", "Columbia Bible College", "District of Columbia College"),
      matches.map { it.name },
    )
  }

  @Test
  fun `searchByName escapes LIKE wildcards in the query`() {
    seed(newCollege(810300, name = "A percent % College"))
    seed(newCollege(810301, name = "A plain College"))
    seed(newCollege(810302, name = "Under_score College"))
    seed(newCollege(810303, name = "Underscore College"))

    // The ILIKE arm is escaped: '%' must not act as a wildcard, so the literal
    // '%' name matches and "A plain College" is only reachable via the trigram
    // arms (which ignore punctuation) — the literal match must rank first.
    val percent = CollegesDao.searchByName(session, "percent %", 25).getOrThrow()
    assertEquals("A percent % College", percent.first().name)

    // An unescaped underscore would ALSO match "Underscore" on the ILIKE arm.
    // Since RFC 139 the trigram arms legitimately surface "Underscore College"
    // as a fuzzy neighbour (trigrams ignore '_'), so the literal-match guarantee
    // is ranking, not exclusivity: the exact row must come first.
    val underscore = CollegesDao.searchByName(session, "Under_score", 25).getOrThrow()
    assertEquals("Under_score College", underscore.first().name)
    assertTrue("Underscore College" in underscore.map { it.name }, "the fuzzy arm should also surface the near-identical name")

    val backslash = CollegesDao.searchByName(session, "\\", 25).getOrThrow()
    assertEquals(emptyList(), backslash.map { it.name })
  }

  @Test
  fun `searchByName applies the limit`() {
    for (u in 810400..810410) seed(newCollege(u, name = "Limit U $u"))
    val matches = CollegesDao.searchByName(session, "Limit U", 3).getOrThrow()
    assertEquals(3, matches.size)
  }

  // ---------------------------------------------------------------------------
  // searchByName — fuzzy arms (RFC 139)
  // ---------------------------------------------------------------------------

  @Test
  fun `searchByName finds a typo'd full name via trigram similarity`() {
    seed(newCollege(820100, name = "Amherst College"))
    seed(newCollege(820101, name = "Hampshire College"))

    val matches = CollegesDao.searchByName(session, "Amhurst Colege", 25).getOrThrow()
    assertEquals("Amherst College", matches.first().name)
  }

  @Test
  fun `searchByName finds a nickname via the aliases word-similarity arm`() {
    seed(newCollege(820200, name = "University of Missouri-Columbia"))
    seed(newCollege(820201, name = "University of Central Missouri"))
    CollegesDao.updateAliases(session, 820200, listOf("Mizzou", "University of Missouri")).getOrThrow()

    val matches = CollegesDao.searchByName(session, "Mizzou", 25).getOrThrow()
    assertEquals("University of Missouri-Columbia", matches.first().name)
  }

  @Test
  fun `searchByName substring arm ranges over alias text, not just the name`() {
    // "izzo" is an exact substring of the alias "Mizzou" but of no college
    // name, and it is too dissimilar for either trigram arm's threshold — so
    // only the ILIKE arm over college_search_text(name, aliases) can find it.
    seed(newCollege(820400, name = "University of Missouri-Columbia"))
    CollegesDao.updateAliases(session, 820400, listOf("Mizzou")).getOrThrow()

    val matches = CollegesDao.searchByName(session, "izzo", 25).getOrThrow()
    assertEquals(listOf("University of Missouri-Columbia"), matches.map { it.name })
  }

  @Test
  fun `searchByName finds a multi-word alias fragment`() {
    seed(newCollege(820300, name = "University of Massachusetts-Amherst"))
    CollegesDao.updateAliases(session, 820300, listOf("UMass Amherst", "UMass")).getOrThrow()

    val matches = CollegesDao.searchByName(session, "UMass Amherst", 25).getOrThrow()
    assertEquals("University of Massachusetts-Amherst", matches.first().name)
  }

  @Test
  fun `searchByName pins its own trigram thresholds, ignoring hostile session GUCs`() {
    seed(newCollege(820500, name = "Amherst College"))
    seed(newCollege(820501, name = "Hampshire College"))

    // Hostile session defaults: at 1.0 neither trigram arm can ever fire, so a
    // search that inherited its bounds from the server would find nothing. The
    // DAO's SET LOCAL must win inside the transaction it runs in.
    connection.autoCommit = false
    try {
      connection.createStatement().use { stmt ->
        stmt.execute("SET pg_trgm.similarity_threshold = 1")
        stmt.execute("SET pg_trgm.word_similarity_threshold = 1")
      }

      val matches = CollegesDao.searchByName(session, "Amhurst Colege", 25).getOrThrow()
      assertEquals("Amherst College", matches.first().name)

      connection.createStatement().use { stmt ->
        stmt
          .executeQuery(
            "SELECT current_setting('pg_trgm.similarity_threshold') AS s, " +
              "current_setting('pg_trgm.word_similarity_threshold') AS w",
          ).use { rs ->
            assertTrue(rs.next())
            assertEquals(CollegesDao.SIMILARITY_THRESHOLD, rs.getString("s").toDouble())
            assertEquals(CollegesDao.WORD_SIMILARITY_THRESHOLD, rs.getString("w").toDouble())
          }
      }
    } finally {
      connection.rollback()
      connection.autoCommit = true
    }
  }

  // ---------------------------------------------------------------------------
  // nonNullCounts (RFC 139)
  // ---------------------------------------------------------------------------

  @Test
  fun `nonNullCounts counts only allowlisted columns and refuses anything else`() {
    seed(newCollege(825100, admissionRate = 0.4))
    seed(newCollege(825101, admissionRate = null))

    val counts = CollegesDao.nonNullCounts(session, listOf("admission_rate", "net_price")).getOrThrow()
    assertEquals(1, counts["admission_rate"])
    assertEquals(2, counts["net_price"])

    // An identifier outside the allowlist never reaches SQL — including one
    // that would otherwise be a valid injection point.
    val thrown =
      assertFailsWith<IllegalArgumentException> {
        CollegesDao.nonNullCounts(session, listOf("admission_rate", "1) AS x, (SELECT 1"))
      }
    assertTrue(thrown.message!!.contains("unknown colleges column"), "the refusal names the fault: ${thrown.message}")
    assertFailsWith<IllegalArgumentException> { CollegesDao.nonNullCounts(session, listOf("name")) }
  }

  // ---------------------------------------------------------------------------
  // updateAliases (RFC 139)
  // ---------------------------------------------------------------------------

  @Test
  fun `updateAliases applies a change with a version bump and history row`() {
    val id = seed(newCollege(830100))
    val outcome = CollegesDao.updateAliases(session, 830100, listOf("Nickname U")).getOrThrow()
    assertEquals(CollegesDao.AliasUpdateOutcome.APPLIED, outcome)

    val college = CollegesDao.findById(session, id).getOrThrow()
    assertEquals(listOf("Nickname U"), college.aliases)
    assertEquals(2, college.version)

    val history = CollegesDao.listVersions(session, id).getOrThrow()
    assertEquals(listOf(1, 2), history.map { it.version })
    assertEquals(listOf("Nickname U"), history.last().entity.aliases)
  }

  @Test
  fun `updateAliases with the same aliases is suppressed - no bump, no history`() {
    val id = seed(newCollege(830200))
    CollegesDao.updateAliases(session, 830200, listOf("Steady", "Nick")).getOrThrow()

    val again = CollegesDao.updateAliases(session, 830200, listOf("Steady", "Nick")).getOrThrow()
    assertEquals(CollegesDao.AliasUpdateOutcome.UNCHANGED, again)

    val college = CollegesDao.findById(session, id).getOrThrow()
    assertEquals(2, college.version, "the suppressed re-apply must not bump")
    assertEquals(2, CollegesDao.listVersions(session, id).getOrThrow().size)
  }

  @Test
  fun `updateAliases on an unknown unit_id reports it, never throws`() {
    val outcome = CollegesDao.updateAliases(session, 999999, listOf("Ghost U")).getOrThrow()
    assertEquals(CollegesDao.AliasUpdateOutcome.UNKNOWN_UNIT_ID, outcome)
  }

  @Test
  fun `scorecard upsert of a changed row preserves curated aliases`() {
    seed(newCollege(830300, name = "Before"))
    CollegesDao.updateAliases(session, 830300, listOf("Kept")).getOrThrow()

    val after = CollegesDao.upsert(session, newCollege(830300, name = "After")).getOrThrow()
    assertEquals(listOf("Kept"), after.aliases, "the Scorecard upsert must not clobber curated aliases")
  }

  // ---------------------------------------------------------------------------
  // search — sortBy / credentialLevel / totalMatches (RFC 139)
  // ---------------------------------------------------------------------------

  @Test
  fun `search sortBy admission rate ascends with NULLS LAST`() {
    seed(newCollege(840100, admissionRate = 0.9))
    seed(newCollege(840101, admissionRate = 0.1))
    seed(newCollege(840102, admissionRate = null))

    val query = CollegeQuery(sortBy = CollegeQuery.SortBy.ADMISSION_RATE_ASC, limit = 25)
    val matches = CollegesDao.search(session, query).getOrThrow().matches
    assertEquals(listOf(840101, 840100, 840102), matches.map { it.unitId })
  }

  @Test
  fun `search sortBy net price ascends with NULLS LAST`() {
    seed(newCollege(840200, netPrice = 30000))
    seed(newCollege(840201, netPrice = null))
    seed(newCollege(840202, netPrice = 5000))

    val query = CollegeQuery(sortBy = CollegeQuery.SortBy.NET_PRICE_ASC, limit = 25)
    val matches = CollegesDao.search(session, query).getOrThrow().matches
    assertEquals(listOf(840202, 840200, 840201), matches.map { it.unitId })
  }

  @Test
  fun `search sortBy graduation rate descends with NULLS LAST`() {
    seed(newCollege(840300, graduationRate = 0.5))
    seed(newCollege(840301, graduationRate = null))
    seed(newCollege(840302, graduationRate = 0.9))

    val query = CollegeQuery(sortBy = CollegeQuery.SortBy.GRADUATION_RATE_DESC, limit = 25)
    val matches = CollegesDao.search(session, query).getOrThrow().matches
    assertEquals(listOf(840302, 840300, 840301), matches.map { it.unitId })
  }

  @Test
  fun `search sortBy name ascends with unit_id tiebreak`() {
    seed(newCollege(840401, name = "Bravo College"))
    seed(newCollege(840400, name = "Alpha College"))
    seed(newCollege(840403, name = "Same Name College"))
    seed(newCollege(840402, name = "Same Name College"))

    val query = CollegeQuery(sortBy = CollegeQuery.SortBy.NAME_ASC, limit = 25)
    val matches = CollegesDao.search(session, query).getOrThrow().matches
    assertEquals(listOf(840400, 840401, 840402, 840403), matches.map { it.unitId })
  }

  @Test
  fun `search sortBy never filters - a NULL-keyed row sinks, it does not vanish`() {
    seed(newCollege(840500, netPrice = null))
    val query = CollegeQuery(sortBy = CollegeQuery.SortBy.NET_PRICE_ASC, limit = 25)
    val page = CollegesDao.search(session, query).getOrThrow()
    assertEquals(listOf(840500), page.matches.map { it.unitId })
    assertEquals(1, page.totalMatches)
  }

  @Test
  fun `search credentialLevel narrows the program join`() {
    val bachelor = seed(newCollege(850100))
    CollegesDao.upsertProgram(session, NewCollegeProgram(bachelor, "230101", "English", 3)).getOrThrow()
    val master = seed(newCollege(850101))
    CollegesDao.upsertProgram(session, NewCollegeProgram(master, "230101", "English", 5)).getOrThrow()

    val bachelors =
      CollegesDao
        .search(session, CollegeQuery(cipPrefix = "23", credentialLevel = CredentialLevel.BACHELORS, limit = 25))
        .getOrThrow()
    assertEquals(listOf(850100), bachelors.matches.map { it.unitId })
    assertEquals(1, bachelors.totalMatches)

    val unfiltered =
      CollegesDao
        .search(session, CollegeQuery(cipPrefix = "23", limit = 25))
        .getOrThrow()
    assertEquals(setOf(850100, 850101), unfiltered.matches.map { it.unitId }.toSet())
  }

  @Test
  fun `search totalMatches exceeds the returned slice when more rows match`() {
    for (u in 860100..860129) seed(newCollege(u))
    val page = CollegesDao.search(session, CollegeQuery(limit = 5)).getOrThrow()
    assertEquals(5, page.matches.size)
    assertEquals(30, page.totalMatches)
  }

  // ---------------------------------------------------------------------------
  // insertIndexBuild (RFC 139)
  // ---------------------------------------------------------------------------

  @Test
  fun `insertIndexBuild writes one provenance row and returns its id`() {
    val started =
      java.time.Instant
        .now()
        .minusSeconds(60)
    val id =
      CollegesDao
        .insertIndexBuild(
          session,
          ed.unicoach.db.models.NewCollegeIndexBuild(
            startedAt = started,
            finishedAt = started.plusSeconds(41),
            sources =
              Json
                .parseToJsonElement(
                  """[{"file":"institution.csv","sha256":"ab12","bytes":143,"source_arg":"institution.csv"}]""",
                ).jsonArray,
            rowsIngested =
              Json
                .parseToJsonElement(
                  """{"colleges":{"seen":5,"inserted":5,"changed":0,"unchanged":0,"skipped":0}}""",
                ).jsonObject,
            indexRows = null,
            changeSummary =
              Json
                .parseToJsonElement(
                  """{"non_null":{"admission_rate":{"before":0,"after":4}},"version_bumps":5}""",
                ).jsonObject,
            methodVersion = 1,
          ),
        ).getOrThrow()

    connection.prepareStatement("SELECT method_version, index_rows FROM college_index_build WHERE id = ?").use { stmt ->
      stmt.setObject(1, id)
      stmt.executeQuery().use { rs ->
        assertTrue(rs.next())
        assertEquals(1, rs.getInt("method_version"))
        rs.getInt("index_rows")
        assertTrue(rs.wasNull())
      }
    }
  }

  @Test
  fun `insertIndexBuild rejects finished_at before started_at`() {
    val started = java.time.Instant.now()
    val result =
      CollegesDao.insertIndexBuild(
        session,
        ed.unicoach.db.models.NewCollegeIndexBuild(
          startedAt = started,
          finishedAt = started.minusSeconds(1),
          sources = JsonArray(emptyList()),
          rowsIngested = JsonObject(emptyMap()),
          indexRows = null,
          changeSummary = JsonObject(emptyMap()),
          methodVersion = 1,
        ),
      )
    assertTrue(result.isFailure)
    assertTrue(result.exceptionOrNull() is ConstraintViolationException)
  }
}
