package ed.unicoach.db.dao

import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeListEntryEdit
import ed.unicoach.db.models.CollegeListEntryId
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.db.models.LivingArrangement
import ed.unicoach.db.models.NewCollege
import ed.unicoach.db.models.NewCollegeListEntry
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollegeListEntriesDaoTest {
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
      stmt.execute("TRUNCATE TABLE college_list_entries, students, users, colleges CASCADE")
    }
    // The published state/locale rows `colleges` foreign-keys into since
    // migration 0067. Truncating `colleges` does not empty them, but another
    // suite on this shared database does, so each suite puts them back.
    CodebookReferenceFixture.seed(session)
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  private var ipedsUnitIdCounter = 900000

  private fun createStudent(): StudentId {
    val userId = UUID.randomUUID()
    val studentId = UUID.randomUUID()
    connection.createStatement().use { stmt ->
      stmt.execute("INSERT INTO users (id, email, name, password_hash) VALUES ('$userId', 'cle-$userId@test.com', 'CLE User', 'ahash')")
      stmt.execute(
        "INSERT INTO students (id, user_id, expected_high_school_graduation_year) VALUES ('$studentId', '$userId', 2028)",
      )
    }
    return StudentId(studentId)
  }

  private fun createCollege(): CollegeId =
    CollegesDao
      .upsert(
        session,
        NewCollege(
          housingAndFoodOnCampusPerYearUsd = null,
          housingAndFoodOffCampusPerYearUsd = null,
          booksAndSuppliesPerYearUsd = null,
          otherExpensesOnCampusPerYearUsd = null,
          otherExpensesOffCampusPerYearUsd = null,
          otherExpensesWithFamilyPerYearUsd = null,
          ipedsUnitId = ipedsUnitIdCounter++,
          opeid = null,
          name = "Test College",
          city = "Townsville",
          state = "CA",
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

  private fun newEntry(
    studentId: StudentId,
    collegeId: CollegeId,
    status: CollegeListEntryStatus = CollegeListEntryStatus.CONSIDERING,
    reasons: String? = null,
    livingPlan: LivingArrangement? = null,
  ): NewCollegeListEntry = NewCollegeListEntry(studentId, collegeId, status, reasons, livingPlan)

  @Test
  fun `every LivingArrangement member round-trips through the living_plan CHECK`() {
    // The per-college twin of the money-profile binding test (RFC 152 D2a).
    // LivingArrangement is ONE enum serving two tables, so this holds the
    // Kotlin enum to `college_list_entries_living_plan_check` member-for-member
    // -- a member added, removed or respelled on either side fails here for
    // that member, not just for whichever plan another test happens to write.
    val student = createStudent()
    for (plan in LivingArrangement.entries) {
      val college = createCollege()
      val written =
        CollegeListEntriesDao
          .create(session, newEntry(student, college, livingPlan = plan))
          .getOrThrow()
      assertEquals(plan, written.livingPlan, "plan [$plan] must round-trip through the DB")

      // And the same value survives an update, which is the path the override
      // is actually written on: the chat tool and REST both write it wholesale.
      val cleared =
        CollegeListEntriesDao
          .update(
            session,
            CollegeListEntryEdit(written.id, written.version, written.status, written.reasons, null),
          ).getOrThrow()
      assertNull(cleared.livingPlan, "NULL is 'no override', and it must be writable as such")
    }
  }

  @Test
  fun `a corrupt stored living plan is refused, never softened to no override`() {
    // The CHECK makes this unreachable through the DAO, so the row is written
    // with the constraint dropped for the length of the test -- the only way to
    // exercise the mapper's corrupt-value path. Softening it to `null` would be
    // read by the resolver as "no override, use the usual plan", which answers
    // this school with the wrong arrangement and says nothing about it.
    val student = createStudent()
    val college = createCollege()
    val entry =
      CollegeListEntriesDao
        .create(session, newEntry(student, college, livingPlan = LivingArrangement.OFF_CAMPUS))
        .getOrThrow()

    // `version = version + 1` because enforce_versioning refuses an UPDATE that
    // does not bump it; the trigger is the entity's rule, not this test's
    // obstacle, so it is obeyed rather than disabled.
    connection.createStatement().use { stmt ->
      stmt.execute("ALTER TABLE college_list_entries DROP CONSTRAINT college_list_entries_living_plan_check")
      stmt.execute(
        "UPDATE college_list_entries SET living_plan = 'in_a_yurt', version = version + 1 " +
          "WHERE id = '${entry.id.value}'",
      )
    }

    val error = CollegeListEntriesDao.findById(session, entry.id).exceptionOrNull()

    // Restored BEFORE the assertions, and the row with it: the suite shares one
    // database, so a corrupt row left behind would fail whichever test next
    // lists every entry -- a failure with nothing to do with its own subject.
    connection.createStatement().use { stmt ->
      stmt.execute(
        "UPDATE college_list_entries SET living_plan = 'off_campus', version = version + 1 " +
          "WHERE id = '${entry.id.value}'",
      )
      stmt.execute(
        "ALTER TABLE college_list_entries ADD CONSTRAINT college_list_entries_living_plan_check " +
          "CHECK (living_plan IS NULL OR living_plan IN ('on_campus','off_campus','with_family'))",
      )
    }

    assertTrue(error is CorruptPersistedValueException, "got $error")
    assertEquals(
      "in_a_yurt",
      error.value,
      "the failure must carry the value that defeated the enum, not merely that one did",
    )
    assertTrue(
      error.message!!.contains("college_list_entries.living_plan") && error.message!!.contains(entry.id.value.toString()),
      "and must name the corrupt column and row, as its money-profile twin does: [${error.message}]",
    )
  }

  private fun countVersions(id: CollegeListEntryId): Int {
    connection.prepareStatement("SELECT COUNT(*) FROM college_list_entries_versions WHERE id = ?").use { stmt ->
      stmt.setObject(1, id.value)
      stmt.executeQuery().use { rs ->
        rs.next()
        return rs.getInt(1)
      }
    }
  }

  @Test
  fun `create persists all columns with default status considering`() {
    val student = createStudent()
    val college = createCollege()

    val entry =
      CollegeListEntriesDao
        .create(session, NewCollegeListEntry(student, college, CollegeListEntryStatus.CONSIDERING, null, null))
        .getOrThrow()

    assertEquals(student, entry.studentId)
    assertEquals(college, entry.collegeId)
    assertEquals(CollegeListEntryStatus.CONSIDERING, entry.status)
    assertNull(entry.reasons)
    assertEquals(1, entry.version)
    assertNull(entry.deletedAt)
  }

  @Test
  fun `create with an unknown college_id raises NotFoundException College not found`() {
    val student = createStudent()
    val result = CollegeListEntriesDao.create(session, newEntry(student, CollegeId(UUID.randomUUID())))
    val error = result.exceptionOrNull()
    assertTrue(error is NotFoundException && error.message == "College not found", "got $error")
  }

  @Test
  fun `create with an unknown student_id raises NotFoundException Owning student not found`() {
    val college = createCollege()
    val result = CollegeListEntriesDao.create(session, newEntry(StudentId(UUID.randomUUID()), college))
    val error = result.exceptionOrNull()
    assertTrue(error is NotFoundException && error.message == "Owning student not found", "got $error")
  }

  @Test
  fun `create duplicate active student_id college_id raises ConstraintViolationException`() {
    val student = createStudent()
    val college = createCollege()
    CollegeListEntriesDao.create(session, newEntry(student, college)).getOrThrow()

    val second = CollegeListEntriesDao.create(session, newEntry(student, college))
    assertTrue(second.exceptionOrNull() is ConstraintViolationException, "got ${second.exceptionOrNull()}")
  }

  @Test
  fun `create for the same student_id college_id after soft-delete succeeds as a new row`() {
    val student = createStudent()
    val college = createCollege()
    val first = CollegeListEntriesDao.create(session, newEntry(student, college)).getOrThrow()
    CollegeListEntriesDao.delete(session, first.id, first.version).getOrThrow()

    val second = CollegeListEntriesDao.create(session, newEntry(student, college)).getOrThrow()
    assertTrue(second.id != first.id, "the re-added entry must be a new row")
  }

  @Test
  fun `findById respects SoftDeleteScope`() {
    val student = createStudent()
    val college = createCollege()
    val entry = CollegeListEntriesDao.create(session, newEntry(student, college)).getOrThrow()

    assertTrue(CollegeListEntriesDao.findById(session, entry.id, SoftDeleteScope.ACTIVE).isSuccess)
    assertTrue(CollegeListEntriesDao.findById(session, entry.id, SoftDeleteScope.DELETED).isFailure)

    val deleted = CollegeListEntriesDao.delete(session, entry.id, entry.version).getOrThrow()

    assertTrue(CollegeListEntriesDao.findById(session, deleted.id, SoftDeleteScope.ACTIVE).isFailure)
    assertTrue(CollegeListEntriesDao.findById(session, deleted.id, SoftDeleteScope.DELETED).isSuccess)
    assertTrue(CollegeListEntriesDao.findById(session, deleted.id, SoftDeleteScope.ALL).isSuccess)
  }

  @Test
  fun `findByIdAndStudent returns NotFoundException for a wrong-owner id`() {
    val student = createStudent()
    val otherStudent = createStudent()
    val college = createCollege()
    val entry = CollegeListEntriesDao.create(session, newEntry(student, college)).getOrThrow()

    val result = CollegeListEntriesDao.findByIdAndStudent(session, entry.id, otherStudent)
    assertTrue(result.exceptionOrNull() is NotFoundException, "got ${result.exceptionOrNull()}")

    assertTrue(CollegeListEntriesDao.findByIdAndStudent(session, entry.id, student).isSuccess)
  }

  @Test
  fun `update bumps version and rejects a stale currentVersion`() {
    val student = createStudent()
    val college = createCollege()
    val entry = CollegeListEntriesDao.create(session, newEntry(student, college)).getOrThrow()

    val updated =
      CollegeListEntriesDao
        .update(session, CollegeListEntryEdit(entry.id, entry.version, CollegeListEntryStatus.APPLYING, "Great fit", null))
        .getOrThrow()
    assertEquals(2, updated.version)
    assertEquals(CollegeListEntryStatus.APPLYING, updated.status)
    assertEquals("Great fit", updated.reasons)

    val stale =
      CollegeListEntriesDao.update(session, CollegeListEntryEdit(entry.id, entry.version, CollegeListEntryStatus.ADMITTED, null, null))
    assertTrue(stale.exceptionOrNull() is ConcurrentModificationException, "got ${stale.exceptionOrNull()}")
  }

  @Test
  fun `listActiveByStudent excludes soft-deleted rows and orders created_at id`() {
    val student = createStudent()
    val c1 = createCollege()
    val c2 = createCollege()
    val c3 = createCollege()
    val e1 = CollegeListEntriesDao.create(session, newEntry(student, c1)).getOrThrow()
    val e2 = CollegeListEntriesDao.create(session, newEntry(student, c2)).getOrThrow()
    val e3 = CollegeListEntriesDao.create(session, newEntry(student, c3)).getOrThrow()
    CollegeListEntriesDao.delete(session, e2.id, e2.version).getOrThrow()

    val active = CollegeListEntriesDao.listActiveByStudent(session, student).getOrThrow()
    assertEquals(listOf(e1.id, e3.id), active.map { it.id })
  }

  @Test
  fun `delete undelete round-trip via OccDeletable`() {
    val student = createStudent()
    val college = createCollege()
    val entry = CollegeListEntriesDao.create(session, newEntry(student, college)).getOrThrow()

    val deleted = CollegeListEntriesDao.delete(session, entry.id, entry.version).getOrThrow()
    assertNotNull(deleted.deletedAt)
    assertEquals(2, deleted.version)

    val restored = CollegeListEntriesDao.undelete(session, deleted.id, deleted.version).getOrThrow()
    assertNull(restored.deletedAt)
    assertEquals(3, restored.version)
  }

  @Test
  fun `listVersions returns ascending version history after multiple updates`() {
    val student = createStudent()
    val college = createCollege()
    val v1 = CollegeListEntriesDao.create(session, newEntry(student, college)).getOrThrow()
    val v2 =
      CollegeListEntriesDao
        .update(session, CollegeListEntryEdit(v1.id, v1.version, CollegeListEntryStatus.APPLYING, "a", null))
        .getOrThrow()
    CollegeListEntriesDao.update(session, CollegeListEntryEdit(v2.id, v2.version, CollegeListEntryStatus.ADMITTED, "b", null)).getOrThrow()

    val versions = CollegeListEntriesDao.listVersions(session, v1.id).getOrThrow()
    assertEquals(listOf(1, 2, 3), versions.map { it.version })
    assertEquals(CollegeListEntryStatus.CONSIDERING, versions[0].entity.status)
    assertEquals(CollegeListEntryStatus.APPLYING, versions[1].entity.status)
    assertEquals(CollegeListEntryStatus.ADMITTED, versions[2].entity.status)
    assertEquals(2, countVersions(v1.id) - 1)
  }

  @Test
  fun `reasons length 2049 chars is rejected by the DB CHECK`() {
    val student = createStudent()
    val college = createCollege()
    val tooLong = "a".repeat(2049)
    val result = CollegeListEntriesDao.create(session, newEntry(student, college, reasons = tooLong))
    assertTrue(result.exceptionOrNull() is ConstraintViolationException, "got ${result.exceptionOrNull()}")
  }

  @Test
  fun `reasons empty string or all-whitespace is rejected but NULL succeeds`() {
    val student = createStudent()
    val college1 = createCollege()
    val college2 = createCollege()
    val college3 = createCollege()

    val empty = CollegeListEntriesDao.create(session, newEntry(student, college1, reasons = ""))
    assertTrue(empty.exceptionOrNull() is ConstraintViolationException, "got ${empty.exceptionOrNull()}")

    val whitespace = CollegeListEntriesDao.create(session, newEntry(student, college2, reasons = "   "))
    assertTrue(whitespace.exceptionOrNull() is ConstraintViolationException, "got ${whitespace.exceptionOrNull()}")

    val nullReasons = CollegeListEntriesDao.create(session, newEntry(student, college3, reasons = null))
    assertTrue(nullReasons.isSuccess)
  }
}
