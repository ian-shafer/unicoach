package ed.unicoach.db.dao

import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeQuery
import ed.unicoach.db.models.NewCollege
import ed.unicoach.db.models.NewCollegeProgram
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
    graduationRate: Double? = 0.7,
    medianEarnings: Int? = 55000,
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
    tuitionInState = 12000,
    tuitionOutState = 30000,
    graduationRate = graduationRate,
    medianEarnings = medianEarnings,
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

    val matches = CollegesDao.search(session, CollegeQuery(limit = 25)).getOrThrow()
    assertEquals(listOf(202, 203, 201), matches.map { it.unitId })
  }

  @Test
  fun `search by cipPrefix joins programs and matches 2, 4 and 6 digit prefixes`() {
    val collegeId = seed(newCollege(301))
    CollegesDao.upsertProgram(session, NewCollegeProgram(collegeId, "260702", "Marine Biology", 3)).getOrThrow()

    for (prefix in listOf("26", "2607", "260702")) {
      val matches = CollegesDao.search(session, CollegeQuery(cipPrefix = prefix, limit = 25)).getOrThrow()
      assertEquals(1, matches.size, "prefix $prefix should match")
      assertEquals(listOf("Marine Biology"), matches.single().programTitles)
    }

    val miss = CollegesDao.search(session, CollegeQuery(cipPrefix = "27", limit = 25)).getOrThrow()
    assertTrue(miss.isEmpty())
  }

  @Test
  fun `search by maxNetPrice includes and excludes`() {
    seed(newCollege(401, netPrice = 10000))
    seed(newCollege(402, netPrice = 40000))
    val matches = CollegesDao.search(session, CollegeQuery(maxNetPrice = 20000, limit = 25)).getOrThrow()
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
    assertEquals(listOf(412), matches.map { it.unitId })
  }

  @Test
  fun `search by states includes and excludes`() {
    seed(newCollege(421, state = "CA"))
    seed(newCollege(422, state = "OR"))
    seed(newCollege(423, state = "TX"))
    val matches = CollegesDao.search(session, CollegeQuery(states = listOf("CA", "OR"), limit = 25)).getOrThrow()
    assertEquals(setOf(421, 422), matches.map { it.unitId }.toSet())
  }

  @Test
  fun `search by control includes and excludes`() {
    seed(newCollege(431, control = 1))
    seed(newCollege(432, control = 2))
    seed(newCollege(433, control = 3))
    val matches = CollegesDao.search(session, CollegeQuery(control = listOf(2, 3), limit = 25)).getOrThrow()
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
    assertEquals(listOf(442), matches.map { it.unitId })
  }

  @Test
  fun `search by minGraduationRate includes and excludes`() {
    seed(newCollege(451, graduationRate = 0.4))
    seed(newCollege(452, graduationRate = 0.8))
    val matches = CollegesDao.search(session, CollegeQuery(minGraduationRate = 0.6, limit = 25)).getOrThrow()
    assertEquals(listOf(452), matches.map { it.unitId })
  }

  @Test
  fun `search returns the outcome columns`() {
    seed(newCollege(501, graduationRate = 0.65, medianEarnings = 62000, pctPell = 0.33))
    val match = CollegesDao.search(session, CollegeQuery(limit = 25)).getOrThrow().single()
    assertEquals(0.65, match.graduationRate)
    assertEquals(62000, match.medianEarnings)
    assertEquals(0.33, match.pctPell)
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
    assertEquals(listOf(601), matches.map { it.unitId })
  }

  @Test
  fun `search applies limit and the limit is honored at the SQL level`() {
    for (u in 700..710) seed(newCollege(u, undergradEnrollment = u))
    val matches = CollegesDao.search(session, CollegeQuery(limit = 3)).getOrThrow()
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
}
