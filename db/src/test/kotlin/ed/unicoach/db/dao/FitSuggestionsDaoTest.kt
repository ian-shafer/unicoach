package ed.unicoach.db.dao

import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.FitSuggestionStatus
import ed.unicoach.db.models.NewCollege
import ed.unicoach.db.models.NewFitSuggestion
import ed.unicoach.db.models.StudentId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FitSuggestionsDaoTest {
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

  @BeforeEach
  fun resetDatabase() {
    connection.createStatement().use { stmt ->
      stmt.execute("TRUNCATE TABLE fit_suggestions, convos, college_list_entries, students, users, colleges CASCADE")
    }
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  private var ipedsUnitIdCounter = 700000

  private fun createStudent(): StudentId {
    val userId = UUID.randomUUID()
    val studentId = UUID.randomUUID()
    connection.createStatement().use { stmt ->
      stmt.execute("INSERT INTO users (id, email, name, password_hash) VALUES ('$userId', 'fs-$userId@test.com', 'FS User', 'ahash')")
      stmt.execute(
        "INSERT INTO students (id, user_id, expected_high_school_graduation_year) VALUES ('$studentId', '$userId', 2028)",
      )
    }
    return StudentId(studentId)
  }

  private fun createCollege(
    name: String = "Test College",
    city: String = "Townsville",
    state: String = "CA",
  ): CollegeId =
    CollegesDao
      .upsert(
        session,
        NewCollege(
          ipedsUnitId = ipedsUnitIdCounter++,
          opeid = null,
          name = name,
          city = city,
          state = state,
          region = 8,
          locale = 13,
          latitude = 34.0,
          longitude = -118.0,
          control = 1,
          undergradEnrollmentHeadcount = 5000,
          admissionRateShare = 0.5,
          satAverageEquivalentScore = 1200,
          costOfAttendancePerYearUsd = 40000,
          netPricePerYearUsd = 20000,
          netPricePerYearIncomeQ1Usd = null,
          netPricePerYearIncomeQ2Usd = null,
          netPricePerYearIncomeQ3Usd = null,
          netPricePerYearIncomeQ4Usd = null,
          netPricePerYearIncomeQ5Usd = null,
          tuitionAndFeesInStatePerYearUsd = 12000,
          tuitionAndFeesOutOfStatePerYearUsd = 30000,
          completionRate150pct4yrShare = 0.7,
          medianEarnings10yAfterEntryUsd = 55000,
          medianDebtAtCompletionUsd = null,
          pellShare = 0.4,
          website = null,
        ),
      ).getOrThrow()
      .id

  private fun createConvo(studentId: StudentId): ConvoId {
    val convoId = UUID.randomUUID()
    connection.prepareStatement("INSERT INTO convos (id, student_id, name) VALUES (?, ?, 'A convo')").use { stmt ->
      stmt.setObject(1, convoId)
      stmt.setObject(2, studentId.value)
      stmt.executeUpdate()
    }
    return ConvoId(convoId)
  }

  @Test
  fun `create persists an open suggestion`() {
    val student = createStudent()
    val college = createCollege()

    val created = FitSuggestionsDao.create(session, NewFitSuggestion(student, college, "You'd love the CS program")).getOrThrow()

    assertEquals(student, created.studentId)
    assertEquals(college, created.collegeId)
    assertEquals(FitSuggestionStatus.OPEN, created.status)
    assertEquals("You'd love the CS program", created.rationale)
    assertNull(created.surfacedAt)
    assertNull(created.surfacedInConvoId)
  }

  @Test
  fun `a duplicate student-college suggestion is rejected`() {
    val student = createStudent()
    val college = createCollege()

    FitSuggestionsDao.create(session, NewFitSuggestion(student, college, "first")).getOrThrow()
    val second = FitSuggestionsDao.create(session, NewFitSuggestion(student, college, "second"))

    assertTrue(second.isFailure, "The UNIQUE(student_id, college_id) backstop must reject a re-suggestion")
    assertTrue(
      second.exceptionOrNull() is ConstraintViolationException,
      "Expected a constraint violation, got: ${second.exceptionOrNull()}",
    )
    // Pin the exact constraint name: FitLensService.writePhase discriminates the
    // benign novelty collision from other constraint violations by matching this
    // literal, so a migration rename must break loudly here rather than silently
    // turning a real failure into a swallowed no-op.
    val violation = second.exceptionOrNull() as ConstraintViolationException
    assertEquals("fit_suggestions_student_college_unique", violation.constraint)
  }

  @Test
  fun `listSuggestedCollegeIds returns every suggested college regardless of status`() {
    val student = createStudent()
    val open = createCollege(name = "Open College")
    val surfaced = createCollege(name = "Surfaced College")

    FitSuggestionsDao.create(session, NewFitSuggestion(student, open, "open one")).getOrThrow()
    val toSurface = FitSuggestionsDao.create(session, NewFitSuggestion(student, surfaced, "surfaced one")).getOrThrow()
    val convo = createConvo(student)
    FitSuggestionsDao.markSurfaced(session, toSurface.id, convo).getOrThrow()

    val ids = FitSuggestionsDao.listSuggestedCollegeIds(session, student).getOrThrow()

    assertEquals(setOf(open, surfaced), ids.toSet(), "Both open and surfaced suggestions must appear in the novelty recheck")
  }

  @Test
  fun `listOpenForOpener returns open rows joined to the college name city state and excludes surfaced rows`() {
    val student = createStudent()
    val openCollege = createCollege(name = "Reed College", city = "Portland", state = "OR")
    val surfacedCollege = createCollege(name = "Hidden College", city = "Nowhere", state = "NV")

    FitSuggestionsDao.create(session, NewFitSuggestion(student, openCollege, "grounded pitch")).getOrThrow()
    val toSurface = FitSuggestionsDao.create(session, NewFitSuggestion(student, surfacedCollege, "already raised")).getOrThrow()
    val convo = createConvo(student)
    FitSuggestionsDao.markSurfaced(session, toSurface.id, convo).getOrThrow()

    val forOpener = FitSuggestionsDao.listOpenForOpener(session, student).getOrThrow()

    assertEquals(1, forOpener.size, "Only the open suggestion is surfaced through the opener")
    val row = forOpener.single()
    assertEquals("Reed College", row.collegeName)
    assertEquals("Portland", row.city)
    assertEquals("OR", row.state)
    assertEquals("grounded pitch", row.rationale)
  }

  @Test
  fun `markSurfaced flips status and sets the surfacing columns`() {
    val student = createStudent()
    val college = createCollege()
    val created = FitSuggestionsDao.create(session, NewFitSuggestion(student, college, "pitch")).getOrThrow()
    val convo = createConvo(student)

    val surfaced = FitSuggestionsDao.markSurfaced(session, created.id, convo).getOrThrow()

    assertEquals(FitSuggestionStatus.SURFACED, surfaced.status)
    assertNotNull(surfaced.surfacedAt)
    assertEquals(convo, surfaced.surfacedInConvoId)
  }
}
