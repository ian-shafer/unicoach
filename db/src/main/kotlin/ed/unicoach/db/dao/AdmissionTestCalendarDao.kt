package ed.unicoach.db.dao

import ed.unicoach.db.models.AdmissionTest
import ed.unicoach.db.models.AdmissionTestAdministration
import ed.unicoach.db.models.AdmissionTestAdministrationId
import ed.unicoach.db.models.NewAdmissionTestAdministration
import ed.unicoach.db.models.ScoreRelease
import java.sql.ResultSet
import java.time.LocalDate
import java.util.UUID

/**
 * Read layer over the `admission_test_administrations` reference table (RFC
 * 197): the national SAT and ACT calendar, seeded by migration from the
 * committed CSV under `db/seed/test-calendar/`.
 *
 * [PolicyParametersDao] is the shape this follows -- a small migration-seeded
 * reference table, a stateless `object`, one [SqlSession] per call, the
 * transaction boundary owned by the caller. It differs from that DAO in one
 * respect, and only one: [upsert] exists, for the test fixtures of suites that
 * TRUNCATE (nothing in production writes this table, and a refresh is a new
 * generated seed migration).
 *
 * The whole table is fifteen rows per cycle, so the read is ONE statement for
 * all of it and no per-cycle or per-test filter is offered: the caller that
 * wants this year's future sittings is answering a question about today, which
 * is not a fact this DAO holds.
 */
object AdmissionTestCalendarDao {
  private const val TABLE = "admission_test_administrations"

  private fun mapRow(rs: ResultSet): AdmissionTestAdministration {
    val testDate = rs.getDate("test_date").toLocalDate()
    return AdmissionTestAdministration(
      id = AdmissionTestAdministrationId(UUID.fromString(rs.getString("id"))),
      test = parseTest(rs.getString("test"), testDate),
      cycle = rs.getString("cycle"),
      testDate = testDate,
      registrationDeadline = rs.getDate("registration_deadline").toLocalDate(),
      lateRegistrationDeadline = rs.getDate("late_registration_deadline")?.toLocalDate(),
      scoreRelease = scoreReleaseOf(rs, testDate),
      datesSourceUrl = rs.getString("dates_source_url"),
      checkedOn = rs.getDate("checked_on").toLocalDate(),
      expiresOn = rs.getDate("expires_on").toLocalDate(),
    )
  }

  /**
   * The announced score release, or null when the publisher has announced none.
   *
   * The two columns are read TOGETHER because [ScoreRelease] is one value: the
   * schema's citation CHECK already refuses a date with no page and a page with
   * no date, so a row that carries one half is corruption, and it is surfaced
   * with the same LOCATED refusal a bad test code gets rather than silently
   * dropping whichever half is present.
   */
  private fun scoreReleaseOf(
    rs: ResultSet,
    testDate: LocalDate,
  ): ScoreRelease? {
    val date = rs.getDate("score_release_date")?.toLocalDate()
    val sourceUrl = rs.getString("score_release_source_url")
    return when {
      date == null && sourceUrl == null -> {
        null
      }

      date != null && sourceUrl != null -> {
        ScoreRelease(date, sourceUrl)
      }

      else -> {
        throw corruptValue(
          raw = "score_release_date=[$date] score_release_source_url=[$sourceUrl]",
          domain = "ScoreRelease",
          location = "$TABLE (test_date [$testDate])",
        )
      }
    }
  }

  /**
   * Reconstructs a persisted test code. The schema CHECK already guarantees a
   * member value is stored, so a miss here is row corruption, surfaced as the
   * house's LOCATED [CorruptPersistedValueException] ([PolicyParametersDao]'s
   * convention) -- never silently skipped, because a skipped sitting is a date a
   * family would never be told about, which is indistinguishable to them from
   * the sitting not existing.
   */
  private fun parseTest(
    value: String,
    testDate: LocalDate,
  ): AdmissionTest =
    AdmissionTest.fromValue(value)
      ?: throw corruptValue(
        raw = value,
        domain = "AdmissionTest",
        location = "$TABLE.test (test_date [$testDate])",
      )

  /**
   * The whole committed calendar, in the order a family hears it: by test date,
   * and within one date by test.
   *
   * ORDERED BY DATE AND NOT BY TEST, which is a product rule and not a
   * convenience: grouping the SATs before the ACTs would put the SAT first in
   * every answer, and the SAT is never the default (brief 0010 D13). Two
   * sittings on one day are broken by `test` only so the order is total and the
   * read is reproducible.
   */
  fun list(session: SqlSession): Result<List<AdmissionTestAdministration>> =
    session.queryList(
      "SELECT * FROM $TABLE ORDER BY test_date, test",
      bind = {},
      map = ::mapRow,
    )

  /**
   * One row, on the natural key `(test, test_date)` -- for the test fixtures,
   * and for nothing else. Production rows arrive by migration, so this path is
   * never on a request.
   *
   * It upserts rather than inserts so a fixture can be called twice, and so a
   * suite can replace one sitting's dates without first deleting it.
   */
  fun upsert(
    session: SqlSession,
    row: NewAdmissionTestAdministration,
  ): Result<AdmissionTestAdministration> =
    session.mutateReturning(
      """
      INSERT INTO $TABLE (
        test, cycle, test_date, registration_deadline, late_registration_deadline,
        score_release_date, dates_source_url, score_release_source_url, checked_on, expires_on
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT (test, test_date) DO UPDATE SET
        cycle = EXCLUDED.cycle,
        registration_deadline = EXCLUDED.registration_deadline,
        late_registration_deadline = EXCLUDED.late_registration_deadline,
        score_release_date = EXCLUDED.score_release_date,
        dates_source_url = EXCLUDED.dates_source_url,
        score_release_source_url = EXCLUDED.score_release_source_url,
        checked_on = EXCLUDED.checked_on,
        expires_on = EXCLUDED.expires_on
      RETURNING *
      """.trimIndent(),
      bind = { stmt ->
        // A cursor, never ten hand-typed ordinals (`CanonicalCohortFixture`'s
        // idiom): every slot but two is a DATE, so a one-slot renumber slip
        // writes a row that passes every CHECK and says something else.
        var idx = 1
        stmt.setString(idx++, row.test.value)
        stmt.setString(idx++, row.cycle)
        stmt.setObject(idx++, row.testDate)
        stmt.setObject(idx++, row.registrationDeadline)
        stmt.setDateOrNull(idx++, row.lateRegistrationDeadline)
        stmt.setDateOrNull(idx++, row.scoreRelease?.date)
        stmt.setString(idx++, row.datesSourceUrl)
        stmt.setStringOrNull(idx++, row.scoreRelease?.sourceUrl)
        stmt.setObject(idx++, row.checkedOn)
        stmt.setObject(idx, row.expiresOn)
      },
      map = ::mapRow,
    )
}
