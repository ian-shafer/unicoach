package ed.unicoach.db.dao

import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.models.AdmissionTest
import ed.unicoach.db.models.AdmissionTestStateProgramme
import ed.unicoach.db.models.AdmissionTestStateProgrammeId
import ed.unicoach.db.models.GradeRange
import ed.unicoach.db.models.NewAdmissionTestStateProgramme
import ed.unicoach.db.models.SchoolDayParticipation
import ed.unicoach.db.models.SchoolDayTestChoice
import ed.unicoach.db.models.SchoolYearBasis
import ed.unicoach.db.models.TestChooser
import java.sql.ResultSet
import java.util.UUID

/**
 * Read layer over the `admission_test_state_programmes` reference table (RFC
 * 208): the state-run, school-day SAT and ACT programmes, seeded by migration
 * from the committed CSV under
 * `db/seed/test-state-programmes/`.
 *
 * [AdmissionTestCalendarDao] is the shape this follows, because it is the same
 * shape one table along -- a small migration-seeded reference table, a stateless
 * `object`, one [SqlSession] per call, the transaction boundary owned by the
 * caller, and an [upsert] that exists for the test fixtures of suites that
 * TRUNCATE rather than for any production write.
 *
 * It differs in ONE respect, and the difference is the table's load-bearing
 * decision: this corpus is APPEND-ONLY HISTORY. A re-read adds a row for the new
 * school year rather than replacing one, so there is no `list()` of "the" row
 * per state and the only read is [listNewestPerState]. Last year's answer stays
 * true about last year, and a caller that wants today's answer says so.
 */
object AdmissionTestStateProgrammeDao {
  private const val TABLE = "admission_test_state_programmes"

  /**
   * The newest row for every state we hold one for.
   *
   * IT COSTS ONE STATEMENT, whatever the size of the corpus: the `DISTINCT ON`
   * below reads the whole table, which is at most a few hundred rows and has no
   * index by design (the DDL says why). Nothing here runs a statement per row.
   *
   * THE TWO JOINS ARE THE CITATION (RFC 213). The urls live in
   * `external_documents` now, so the source is an INNER join -- a row with no
   * source document cannot exist, the column is NOT NULL -- and the archive is a
   * LEFT join, because most rows needed no archived address. The mapper reads
   * `source_url` and `archive_url` exactly as it did when they were columns of
   * this table, so nothing above this DAO learns that the storage moved.
   *
   * NEWEST IS `(school_year DESC, checked_on DESC)` and both halves are load
   * bearing. The school year is which year the row is ABOUT, so it orders first;
   * `checked_on` breaks the tie a same-year correction creates, which is the
   * only way one state can have two rows for one year.
   */
  fun listNewestPerState(session: SqlSession): Result<List<AdmissionTestStateProgramme>> =
    session
      .queryList(
        """
        SELECT DISTINCT ON (p.state) p.*, s.url AS source_url, a.url AS archive_url
        FROM $TABLE p
        JOIN external_documents s ON s.id = p.source_document_id
        LEFT JOIN external_documents a ON a.id = p.archive_document_id
        ORDER BY p.state, p.school_year DESC, p.checked_on DESC
        """.trimIndent(),
        bind = {},
        map = ::mapRow,
      )

  /**
   * One row, on the natural key `(state, school_year)` -- for the test fixtures
   * and for the DAO's own suite, and for nothing else. Production rows arrive by
   * migration, so this path is never on a request.
   *
   * It UPSERTS because a corrected re-read of the SAME school year is a
   * correction of that row rather than a second contradictory one -- the natural
   * key the generated seed writes against. A re-read of a LATER year is a new
   * row, and nothing here can overwrite one: that is what append-only means.
   */
  fun upsert(
    session: SqlSession,
    row: NewAdmissionTestStateProgramme,
  ): Result<AdmissionTestStateProgramme> =
    // THE CITATION IS RESOLVED FIRST, and through [ExternalDocumentsDao.idOf]
    // rather than its upsert: this path knows the row's two urls and knows
    // NOTHING about whether either still resolves, so it must not overwrite a
    // swept status with "we never asked" (RFC 182). Both statements run in the
    // caller's transaction, so a refused programme row takes its documents with
    // it.
    ExternalDocumentsDao
      .idOf(session, row.sourceUrl)
      .mapCatching { sourceId ->
        val archiveId = row.archiveUrl?.let { ExternalDocumentsDao.idOf(session, it).getOrThrow() }
        sourceId to archiveId
      }.mapCatching { (sourceId, archiveId) ->
        session
          .mutateReturning(
            """
            INSERT INTO $TABLE (
              state, school_year, school_year_basis, participation, school_must_offer,
              test_chooser, test, lowest_grade, highest_grade, state_funded,
              source_document_id, archive_document_id, checked_on, expires_on
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (state, school_year) DO UPDATE SET
              school_year_basis = EXCLUDED.school_year_basis,
              participation = EXCLUDED.participation,
              school_must_offer = EXCLUDED.school_must_offer,
              test_chooser = EXCLUDED.test_chooser,
              test = EXCLUDED.test,
              lowest_grade = EXCLUDED.lowest_grade,
              highest_grade = EXCLUDED.highest_grade,
              state_funded = EXCLUDED.state_funded,
              source_document_id = EXCLUDED.source_document_id,
              archive_document_id = EXCLUDED.archive_document_id,
              checked_on = EXCLUDED.checked_on,
              expires_on = EXCLUDED.expires_on
            RETURNING *,
              (SELECT e.url FROM external_documents e WHERE e.id = $TABLE.source_document_id) AS source_url,
              (SELECT e.url FROM external_documents e WHERE e.id = $TABLE.archive_document_id) AS archive_url
            """.trimIndent(),
            bind = { stmt ->
              // A cursor, never fourteen hand-typed ordinals
              // ([AdmissionTestCalendarDao]'s idiom): most slots are nullable, so a
              // one-slot renumber slip writes a row that passes every CHECK and says
              // something else.
              var idx = 1
              stmt.setString(idx++, row.state)
              stmt.setInt(idx++, row.schoolYear.firstCalendarYear)
              stmt.setString(idx++, row.schoolYearBasis.value)
              stmt.setString(idx++, row.participation.value)
              stmt.setBooleanOrNull(idx++, row.schoolMustOffer)
              stmt.setString(idx++, row.choice.chooser.value)
              stmt.setStringOrNull(idx++, (row.choice as? SchoolDayTestChoice.StateChose)?.test?.value)
              stmt.setIntOrNull(idx++, row.grades?.lowest)
              stmt.setIntOrNull(idx++, row.grades?.highest)
              stmt.setBooleanOrNull(idx++, row.stateFunded)
              stmt.setObject(idx++, sourceId.value)
              stmt.setObject(idx++, archiveId?.value)
              stmt.setObject(idx++, row.checkedOn)
              stmt.setObject(idx, row.expiresOn)
            },
            map = ::mapRow,
            // The state column still has no foreign key (the DDL says why and
            // which slice adds it), so a bad state is a CHECK violation like any
            // other and the mapper's constraint name is what locates it.
            mapError = { e ->
              mapReferenceWriteError(
                e,
                "[$TABLE] row for state [${row.state}] could not be written",
              )
            },
          ).getOrThrow()
      }

  private fun mapRow(rs: ResultSet): AdmissionTestStateProgramme {
    val state = rs.getString("state")
    val schoolYear = AcademicYear(rs.getInt("school_year"))
    val location = "$TABLE (state [$state] school_year [${schoolYear.label}])"
    return AdmissionTestStateProgramme(
      id = AdmissionTestStateProgrammeId(UUID.fromString(rs.getString("id"))),
      state = state,
      schoolYear = schoolYear,
      schoolYearBasis =
        SchoolYearBasis.fromValue(rs.getString("school_year_basis"))
          ?: throw corruptValue(rs.getString("school_year_basis"), "SchoolYearBasis", "$location.school_year_basis"),
      participation =
        SchoolDayParticipation.fromValue(rs.getString("participation"))
          ?: throw corruptValue(rs.getString("participation"), "SchoolDayParticipation", "$location.participation"),
      schoolMustOffer = rs.getBoolean("school_must_offer").takeUnless { rs.wasNull() },
      choice = choiceOf(rs, location),
      grades = gradesOf(rs, location),
      stateFunded = rs.getBoolean("state_funded").takeUnless { rs.wasNull() },
      sourceUrl = rs.getString("source_url"),
      archiveUrl = rs.getString("archive_url"),
      checkedOn = rs.getDate("checked_on").toLocalDate(),
      expiresOn = rs.getDate("expires_on").toLocalDate(),
    )
  }

  /**
   * The state's choice, read from the two columns TOGETHER because
   * [SchoolDayTestChoice] is one value: the schema's pairing CHECK already
   * refuses a named test without a state chooser and a state chooser without a
   * test, so a row carrying one half is corruption and is surfaced with the same
   * LOCATED refusal a bad code gets -- never silently resolved to whichever half
   * is present, because both resolutions tell a family something no source says.
   */
  private fun choiceOf(
    rs: ResultSet,
    location: String,
  ): SchoolDayTestChoice {
    val chooserRaw = rs.getString("test_chooser")
    val chooser =
      TestChooser.fromValue(chooserRaw)
        ?: throw corruptValue(chooserRaw, "TestChooser", "$location.test_chooser")
    val testRaw = rs.getString("test")
    return when {
      chooser == TestChooser.STATE && testRaw != null -> {
        SchoolDayTestChoice.StateChose(
          AdmissionTest.fromValue(testRaw)
            ?: throw corruptValue(testRaw, "AdmissionTest", "$location.test"),
        )
      }

      chooser != TestChooser.STATE && testRaw == null -> {
        SchoolDayTestChoice.ChosenLocally(chooser)
      }

      else -> {
        throw corruptValue(
          raw = "test_chooser=[$chooserRaw] test=[$testRaw]",
          domain = "SchoolDayTestChoice",
          location = location,
        )
      }
    }
  }

  /**
   * The grades, both ends or neither -- the same read-them-together rule, for the
   * pair the schema's grade-pairing CHECK refuses to half-fill. A half-open range
   * would otherwise become a confident sentence about one grade the source never
   * named.
   */
  private fun gradesOf(
    rs: ResultSet,
    location: String,
  ): GradeRange? {
    val lowest = rs.getIntOrNull("lowest_grade")
    val highest = rs.getIntOrNull("highest_grade")
    return when {
      lowest == null && highest == null -> {
        null
      }

      lowest != null && highest != null -> {
        GradeRange(lowest, highest)
      }

      else -> {
        throw corruptValue(
          raw = "lowest_grade=[$lowest] highest_grade=[$highest]",
          domain = "GradeRange",
          location = location,
        )
      }
    }
  }
}
