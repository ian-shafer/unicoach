package ed.unicoach.db.dao

import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.MoneyProfileEdit
import ed.unicoach.db.models.MoneyProfileId
import ed.unicoach.db.models.MoneyProfileUpsert
import ed.unicoach.db.models.NewMoneyProfile
import ed.unicoach.db.models.SoftDeleteScope
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MoneyProfilesDaoTest {
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
      stmt.execute("TRUNCATE TABLE money_profiles, students, users CASCADE")
    }
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  private fun createStudent(): StudentId {
    val userId = UUID.randomUUID()
    val studentId = UUID.randomUUID()
    connection.createStatement().use { stmt ->
      stmt.execute("INSERT INTO users (id, email, name, password_hash) VALUES ('$userId', 'mp-$userId@test.com', 'MP User', 'ahash')")
      stmt.execute(
        "INSERT INTO students (id, user_id, expected_high_school_graduation_year) VALUES ('$studentId', '$userId', 2028)",
      )
    }
    return StudentId(studentId)
  }

  private fun unansweredProfile(studentId: StudentId): NewMoneyProfile =
    NewMoneyProfile(
      studentId = studentId,
      incomeBand = null,
      incomeBandStatus = AnswerStatus.UNANSWERED,
      residencyState = null,
      residencyStatus = AnswerStatus.UNANSWERED,
    )

  private fun countVersions(id: MoneyProfileId): Int {
    connection.prepareStatement("SELECT COUNT(*) FROM money_profiles_versions WHERE id = ?").use { stmt ->
      stmt.setObject(1, id.value)
      stmt.executeQuery().use { rs ->
        rs.next()
        return rs.getInt(1)
      }
    }
  }

  @Test
  fun `create persists an unanswered profile with defaults`() {
    val student = createStudent()

    val profile = MoneyProfilesDao.create(session, unansweredProfile(student)).getOrThrow()

    assertEquals(student, profile.studentId)
    assertNull(profile.incomeBand)
    assertEquals(AnswerStatus.UNANSWERED, profile.incomeBandStatus)
    assertNull(profile.residencyState)
    assertEquals(AnswerStatus.UNANSWERED, profile.residencyStatus)
    assertEquals(1, profile.version)
    assertNull(profile.deletedAt)
    assertEquals(1, countVersions(profile.id))
  }

  @Test
  fun `create persists an answered profile with both values`() {
    val student = createStudent()

    val profile =
      MoneyProfilesDao
        .create(
          session,
          NewMoneyProfile(
            studentId = student,
            incomeBand = IncomeBand.K48_TO_75K,
            incomeBandStatus = AnswerStatus.ANSWERED,
            residencyState = "CA",
            residencyStatus = AnswerStatus.ANSWERED,
          ),
        ).getOrThrow()

    assertEquals(IncomeBand.K48_TO_75K, profile.incomeBand)
    assertEquals(AnswerStatus.ANSWERED, profile.incomeBandStatus)
    assertEquals("CA", profile.residencyState)
    assertEquals(AnswerStatus.ANSWERED, profile.residencyStatus)
  }

  @Test
  fun `create with an unknown student_id raises NotFoundException Owning student not found`() {
    val result = MoneyProfilesDao.create(session, unansweredProfile(StudentId(UUID.randomUUID())))
    val error = result.exceptionOrNull()
    assertTrue(error is NotFoundException && error.message!!.startsWith("Owning student not found"), "got $error")
  }

  @Test
  fun `findActiveByStudent returns the profile and NotFoundException before the first write`() {
    val student = createStudent()

    val before = MoneyProfilesDao.findActiveByStudent(session, student)
    assertTrue(before.exceptionOrNull() is NotFoundException, "got ${before.exceptionOrNull()}")

    val created = MoneyProfilesDao.create(session, unansweredProfile(student)).getOrThrow()
    val found = MoneyProfilesDao.findActiveByStudent(session, student).getOrThrow()
    assertEquals(created.id, found.id)
  }

  @Test
  fun `upsertForStudent creates then updates and untouched fields carry over`() {
    val student = createStudent()

    val created =
      MoneyProfilesDao
        .upsertForStudent(
          session,
          MoneyProfileUpsert(
            studentId = student,
            income = MoneyProfileUpsert.FieldWrite.Answer(IncomeBand.UNDER_30K),
          ),
        ).getOrThrow()
    assertEquals(1, created.version)
    assertEquals(IncomeBand.UNDER_30K, created.incomeBand)
    assertEquals(AnswerStatus.UNANSWERED, created.residencyStatus)

    val updated =
      MoneyProfilesDao
        .upsertForStudent(
          session,
          MoneyProfileUpsert(
            studentId = student,
            residency = MoneyProfileUpsert.FieldWrite.Answer("CA"),
          ),
        ).getOrThrow()
    assertEquals(created.id, updated.id)
    assertEquals(2, updated.version)
    assertEquals(IncomeBand.UNDER_30K, updated.incomeBand, "the untouched field must carry over")
    assertEquals(AnswerStatus.ANSWERED, updated.incomeBandStatus)
    assertEquals("CA", updated.residencyState)
    assertEquals(2, countVersions(created.id))
  }

  @Test
  fun `a second create-shaped upsertForStudent updates instead of violating active-uniqueness`() {
    val student = createStudent()
    val write =
      MoneyProfileUpsert(
        studentId = student,
        income = MoneyProfileUpsert.FieldWrite.Answer(IncomeBand.K48_TO_75K),
        residency = MoneyProfileUpsert.FieldWrite.Answer("NY"),
      )

    val first = MoneyProfilesDao.upsertForStudent(session, write).getOrThrow()
    val second = MoneyProfilesDao.upsertForStudent(session, write).getOrThrow()

    assertEquals(first.id, second.id, "the conflicting INSERT must convert into an UPDATE of the same row")
    assertEquals(2, second.version)
    assertEquals(IncomeBand.K48_TO_75K, second.incomeBand)
    assertEquals("NY", second.residencyState)
  }

  @Test
  fun `an identical no-change upsertForStudent still bumps the version and logs history`() {
    // The OCC-entity convention (college_list_entries): every UPDATE bumps the
    // version and writes a history row, even a content-identical one -- only
    // the reference `colleges` upsert skips no-ops.
    val student = createStudent()
    val write =
      MoneyProfileUpsert(
        studentId = student,
        income = MoneyProfileUpsert.FieldWrite.Answer(IncomeBand.OVER_110K),
      )
    val first = MoneyProfilesDao.upsertForStudent(session, write).getOrThrow()

    val second = MoneyProfilesDao.upsertForStudent(session, write).getOrThrow()

    assertEquals(2, second.version)
    assertEquals(2, countVersions(first.id))
  }

  @Test
  fun `upsertForStudent history preserves the answer then decline trail`() {
    val student = createStudent()
    val created =
      MoneyProfilesDao
        .upsertForStudent(
          session,
          MoneyProfileUpsert(student, income = MoneyProfileUpsert.FieldWrite.Answer(IncomeBand.UNDER_30K)),
        ).getOrThrow()
    val declined =
      MoneyProfilesDao
        .upsertForStudent(
          session,
          MoneyProfileUpsert(student, income = MoneyProfileUpsert.FieldWrite.Declined),
        ).getOrThrow()
    assertNull(declined.incomeBand)
    assertEquals(AnswerStatus.DECLINED, declined.incomeBandStatus)

    val history = MoneyProfilesDao.listVersions(session, created.id).getOrThrow()
    assertEquals(listOf(1, 2), history.map { it.entity.version })
    assertEquals(
      listOf(AnswerStatus.ANSWERED, AnswerStatus.DECLINED),
      history.map { it.entity.incomeBandStatus },
    )
  }

  @Test
  fun `upsertForStudent after a soft-delete creates a fresh active row`() {
    val student = createStudent()
    val first = MoneyProfilesDao.create(session, unansweredProfile(student)).getOrThrow()
    MoneyProfilesDao.delete(session, first.id, first.version).getOrThrow()

    val second =
      MoneyProfilesDao
        .upsertForStudent(
          session,
          MoneyProfileUpsert(student, income = MoneyProfileUpsert.FieldWrite.Answer(IncomeBand.UNDER_30K)),
        ).getOrThrow()
    assertTrue(second.id != first.id, "the deleted row is outside the partial index; the upsert must insert anew")
    assertEquals(1, second.version)
  }

  @Test
  fun `upsertForStudent with an unknown student_id raises NotFoundException Owning student not found`() {
    val result = MoneyProfilesDao.upsertForStudent(session, MoneyProfileUpsert(StudentId(UUID.randomUUID())))
    val error = result.exceptionOrNull()
    assertTrue(error is NotFoundException && error.message!!.startsWith("Owning student not found"), "got $error")
  }

  @Test
  fun `every IncomeBand member round-trips through the income_band CHECK`() {
    // Holds the Kotlin enum to `money_profiles_income_band_check` label-for-
    // label: a member added, removed, or respelled on either side fails here
    // for that member, not just for the bands other tests happen to exercise.
    for (band in IncomeBand.entries) {
      val student = createStudent()
      val written =
        MoneyProfilesDao
          .upsertForStudent(
            session,
            MoneyProfileUpsert(student, income = MoneyProfileUpsert.FieldWrite.Answer(band)),
          ).getOrThrow()
      assertEquals(band, written.incomeBand, "band [$band] must round-trip through the DB")
      assertEquals(AnswerStatus.ANSWERED, written.incomeBandStatus)
    }
  }

  @Test
  fun `answer then decline then re-answer is versioned updates that write history`() {
    val student = createStudent()
    val created = MoneyProfilesDao.create(session, unansweredProfile(student)).getOrThrow()

    val answered =
      MoneyProfilesDao
        .update(
          session,
          MoneyProfileEdit(
            id = created.id,
            version = created.version,
            incomeBand = IncomeBand.UNDER_30K,
            incomeBandStatus = AnswerStatus.ANSWERED,
            residencyState = null,
            residencyStatus = AnswerStatus.UNANSWERED,
          ),
        ).getOrThrow()
    assertEquals(2, answered.version)
    assertEquals(IncomeBand.UNDER_30K, answered.incomeBand)

    val declined =
      MoneyProfilesDao
        .update(
          session,
          MoneyProfileEdit(
            id = created.id,
            version = answered.version,
            incomeBand = null,
            incomeBandStatus = AnswerStatus.DECLINED,
            residencyState = null,
            residencyStatus = AnswerStatus.UNANSWERED,
          ),
        ).getOrThrow()
    assertEquals(3, declined.version)
    assertNull(declined.incomeBand)
    assertEquals(AnswerStatus.DECLINED, declined.incomeBandStatus)

    val reAnswered =
      MoneyProfilesDao
        .update(
          session,
          MoneyProfileEdit(
            id = created.id,
            version = declined.version,
            incomeBand = IncomeBand.OVER_110K,
            incomeBandStatus = AnswerStatus.ANSWERED,
            residencyState = null,
            residencyStatus = AnswerStatus.UNANSWERED,
          ),
        ).getOrThrow()
    assertEquals(4, reAnswered.version)
    assertEquals(IncomeBand.OVER_110K, reAnswered.incomeBand)

    assertEquals(4, countVersions(created.id))
    val history = MoneyProfilesDao.listVersions(session, created.id).getOrThrow()
    assertEquals(listOf(1, 2, 3, 4), history.map { it.entity.version })
    assertEquals(
      listOf(AnswerStatus.UNANSWERED, AnswerStatus.ANSWERED, AnswerStatus.DECLINED, AnswerStatus.ANSWERED),
      history.map { it.entity.incomeBandStatus },
    )
  }

  @Test
  fun `update with a stale version raises ConcurrentModificationException`() {
    val student = createStudent()
    val created = MoneyProfilesDao.create(session, unansweredProfile(student)).getOrThrow()
    MoneyProfilesDao
      .update(
        session,
        MoneyProfileEdit(created.id, created.version, null, AnswerStatus.DECLINED, null, AnswerStatus.UNANSWERED),
      ).getOrThrow()

    val stale =
      MoneyProfilesDao.update(
        session,
        MoneyProfileEdit(created.id, created.version, null, AnswerStatus.DECLINED, null, AnswerStatus.UNANSWERED),
      )
    assertTrue(stale.exceptionOrNull() is ConcurrentModificationException, "got ${stale.exceptionOrNull()}")
  }

  @Test
  fun `a value without answered status violates the value-iff-answered CHECK`() {
    val student = createStudent()
    val result =
      MoneyProfilesDao.create(
        session,
        NewMoneyProfile(
          studentId = student,
          incomeBand = IncomeBand.UNDER_30K,
          incomeBandStatus = AnswerStatus.DECLINED,
          residencyState = null,
          residencyStatus = AnswerStatus.UNANSWERED,
        ),
      )
    val error = result.exceptionOrNull()
    assertTrue(
      error is ConstraintViolationException && error.constraint == "money_profiles_income_band_value_iff_answered_check",
      "got $error",
    )
  }

  @Test
  fun `an answered status without a value violates the value-iff-answered CHECK`() {
    val student = createStudent()
    val result =
      MoneyProfilesDao.create(
        session,
        NewMoneyProfile(
          studentId = student,
          incomeBand = null,
          incomeBandStatus = AnswerStatus.UNANSWERED,
          residencyState = null,
          residencyStatus = AnswerStatus.ANSWERED,
        ),
      )
    val error = result.exceptionOrNull()
    assertTrue(
      error is ConstraintViolationException && error.constraint == "money_profiles_residency_value_iff_answered_check",
      "got $error",
    )
  }

  @Test
  fun `a lowercase residency state violates the format CHECK`() {
    val student = createStudent()
    val result =
      MoneyProfilesDao.create(
        session,
        NewMoneyProfile(
          studentId = student,
          incomeBand = null,
          incomeBandStatus = AnswerStatus.UNANSWERED,
          residencyState = "ca",
          residencyStatus = AnswerStatus.ANSWERED,
        ),
      )
    val error = result.exceptionOrNull()
    assertTrue(
      error is ConstraintViolationException && error.constraint == "money_profiles_residency_state_format_check",
      "got $error",
    )
  }

  @Test
  fun `a second active profile for the same student violates the active-uniqueness index`() {
    val student = createStudent()
    MoneyProfilesDao.create(session, unansweredProfile(student)).getOrThrow()

    val second = MoneyProfilesDao.create(session, unansweredProfile(student))
    val error = second.exceptionOrNull()
    assertTrue(
      error is ConstraintViolationException && error.constraint == "money_profiles_student_active_idx",
      "got $error",
    )
  }

  @Test
  fun `soft-delete then recreate succeeds as a new row and the old row stays findable by id`() {
    val student = createStudent()
    val first = MoneyProfilesDao.create(session, unansweredProfile(student)).getOrThrow()
    MoneyProfilesDao.delete(session, first.id, first.version).getOrThrow()

    val active = MoneyProfilesDao.findActiveByStudent(session, student)
    assertTrue(active.exceptionOrNull() is NotFoundException, "deleted profile must not be active")

    val second = MoneyProfilesDao.create(session, unansweredProfile(student)).getOrThrow()
    assertTrue(second.id != first.id)
    assertEquals(second.id, MoneyProfilesDao.findActiveByStudent(session, student).getOrThrow().id)

    val deleted = MoneyProfilesDao.findById(session, first.id, SoftDeleteScope.ALL).getOrThrow()
    assertTrue(deleted.deletedAt != null)
  }
}
