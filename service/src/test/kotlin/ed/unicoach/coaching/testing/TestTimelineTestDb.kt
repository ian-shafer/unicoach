package ed.unicoach.coaching.testing

import ed.unicoach.coaching.CoachingTestDb
import ed.unicoach.coaching.admissions.AdmissionsTestDb
import ed.unicoach.db.dao.AdmissionTestCalendarFixture
import ed.unicoach.db.dao.AdmissionTestStateProgrammeFixture
import ed.unicoach.db.dao.MoneyProfilesDao
import ed.unicoach.db.dao.StudentRowFixture
import ed.unicoach.db.models.AdmissionTest
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.MoneyProfileUpsert
import ed.unicoach.db.models.NewAdmissionTestAdministration
import ed.unicoach.db.models.NewAdmissionTestStateProgramme
import ed.unicoach.db.models.ScoreRelease
import ed.unicoach.db.models.StudentId
import java.time.Instant
import java.time.InstantSource
import java.time.LocalDate

/**
 * The admission-test timeline suites' own fixture: the calendar rows, a student
 * whose GRADUATION YEAR the test chose, and a service with its clock pinned.
 *
 * The college, list-entry and CDS-deadline seeders are [AdmissionsTestDb]'s and
 * are reused rather than copied -- `college_deadlines` is the table this read and
 * the admissions read must agree about, so they seed it through one seeder.
 */
object TestTimelineTestDb {
  /** The graduation year every test states for itself: the resolved anchor year is DERIVED from it, so it is never incidental. */
  const val DEFAULT_GRADUATION_YEAR = 2027

  /**
   * Truncates what this suite touches and empties the calendar.
   *
   * The calendar is emptied on purpose. It is MIGRATION-seeded, so `db-reset`
   * leaves the real fifteen rows there -- and a suite that needs rows whose
   * `expires_on` it chose would otherwise be asserting against a mixture of its
   * own rows and the committed ones, which is the one thing no expiry test may do
   * (the expiry is the MINIMUM over all rows).
   */
  fun reset() {
    AdmissionsTestDb.reset()
    AdmissionTestCalendarFixture.deleteAll(AdmissionsTestDb.sqlSession)
    // The state programmes are emptied for the SAME reason as the calendar, one
    // table along: they are migration-seeded, so `db-reset` leaves the real
    // corpus there, and a suite that decides what one state says -- or that
    // reads an EXPIRED row, which no committed row may ever be (D20) -- would
    // otherwise be asserting against a mixture of its own rows and 31 shipped
    // ones.
    AdmissionTestStateProgrammeFixture.deleteAll(AdmissionsTestDb.sqlSession)
  }

  /** The state-programme rows this test means. Every one of them is the suite's own; nothing here reads the corpus. */
  fun seedProgrammes(rows: List<NewAdmissionTestStateProgramme>) =
    AdmissionTestStateProgrammeFixture.seed(AdmissionsTestDb.sqlSession, rows)

  /**
   * The student's residency state, answered -- the ONE field this feature infers
   * from, written through the same DAO the money surface writes it with.
   *
   * The three residency writers below are separate calls and not one nullable
   * parameter, because ANSWERED, UNANSWERED and DECLINED are the tri-state the
   * whole D10 sentence turns on: a test says which of the three it means by
   * which one it calls.
   */
  fun answerResidency(
    student: StudentId,
    state: String,
  ) = upsertProfile(MoneyProfileUpsert(studentId = student, residency = MoneyProfileUpsert.FieldWrite.Answer(state)))

  /** A profile that EXISTS with the residency question never put: the income field is written so the row is real. */
  fun leaveResidencyUnanswered(student: StudentId) =
    upsertProfile(
      MoneyProfileUpsert(studentId = student, income = MoneyProfileUpsert.FieldWrite.Answer(IncomeBand.K48_TO_75K)),
    )

  /** The student was asked and chose not to say. It is never asked again, here or anywhere. */
  fun declineResidency(student: StudentId) =
    upsertProfile(MoneyProfileUpsert(studentId = student, residency = MoneyProfileUpsert.FieldWrite.Declined))

  private fun upsertProfile(input: MoneyProfileUpsert) {
    MoneyProfilesDao.upsertForStudent(AdmissionsTestDb.sqlSession, input).getOrThrow()
  }

  /** A student with the graduation year this test means, stated rather than inherited from a shared default. */
  fun createStudent(graduationYear: Int = DEFAULT_GRADUATION_YEAR): StudentId {
    val studentId = AdmissionsTestDb.createStudent()
    StudentRowFixture.updateGraduation(
      CoachingTestDb.connection,
      studentId.value,
      StudentRowFixture.graduationYear(graduationYear),
    )
    return studentId
  }

  /** The real committed calendar -- the fifteen rows that ship. */
  fun seedCommittedCalendar() = AdmissionTestCalendarFixture.seedCommitted(AdmissionsTestDb.sqlSession)

  /** An arbitrary calendar, for the tests whose subject IS `expires_on`. */
  fun seedCalendar(rows: List<NewAdmissionTestAdministration>) = AdmissionTestCalendarFixture.seed(AdmissionsTestDb.sqlSession, rows)

  /**
   * One row, spelled out by keyword so a test reads as the calendar it means.
   * Every date is a parameter because every date is the subject of some test.
   */
  fun administration(
    test: AdmissionTest,
    testDate: String,
    registrationDeadline: String,
    lateRegistrationDeadline: String? = null,
    scoreReleaseDate: String? = null,
    expiresOn: String,
    checkedOn: String = "2026-09-11",
    cycle: String = "2026-27",
  ): NewAdmissionTestAdministration =
    NewAdmissionTestAdministration(
      test = test,
      cycle = cycle,
      testDate = LocalDate.parse(testDate),
      registrationDeadline = LocalDate.parse(registrationDeadline),
      lateRegistrationDeadline = lateRegistrationDeadline?.let(LocalDate::parse),
      scoreRelease =
        scoreReleaseDate?.let {
          // The date and the page it was read from are ONE value, so a fixture
          // cannot write an announced date with no citation under it either.
          ScoreRelease(LocalDate.parse(it), "https://example.test/${test.value}-scores")
        },
      datesSourceUrl = "https://example.test/${test.value}-dates",
      checkedOn = LocalDate.parse(checkedOn),
      expiresOn = LocalDate.parse(expiresOn),
    )

  /**
   * The service with today pinned. The pinned value is an INSTANT and carries no
   * zone at all (the `CoachingServiceTest` precedent): the service applies
   * `COACH_ZONE` itself, so a test pinning `02:30Z` and expecting the previous US
   * day is what proves the zone is applied.
   */
  fun serviceAt(instant: String): TestTimelineService =
    TestTimelineService(AdmissionsTestDb.database, InstantSource.fixed(Instant.parse(instant)))
}
