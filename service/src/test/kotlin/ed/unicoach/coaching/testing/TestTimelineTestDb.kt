package ed.unicoach.coaching.testing

import ed.unicoach.coaching.CoachingTestDb
import ed.unicoach.coaching.admissions.AdmissionsTestDb
import ed.unicoach.db.dao.AdmissionTestCalendarFixture
import ed.unicoach.db.models.AdmissionTest
import ed.unicoach.db.models.NewAdmissionTestAdministration
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
  }

  /** A student with the graduation year this test means, stated rather than inherited from a shared default. */
  fun createStudent(graduationYear: Int = DEFAULT_GRADUATION_YEAR): StudentId {
    val studentId = AdmissionsTestDb.createStudent()
    CoachingTestDb.connection.createStatement().use { stmt ->
      // `version = version + 1` is not optional: `students` carries the OCC
      // trigger, which refuses an UPDATE that does not advance the version.
      stmt.executeUpdate(
        "UPDATE students SET expected_high_school_graduation_year = $graduationYear, version = version + 1 " +
          "WHERE id = '${studentId.value}'",
      )
    }
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
