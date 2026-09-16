package ed.unicoach.db.dao

import ed.unicoach.db.models.PartialDate
import ed.unicoach.db.models.PartialDateColumns
import java.sql.Connection
import java.time.LocalDate
import java.time.Year
import java.util.UUID

/**
 * The one way a test writes a `students` row in raw SQL (RFC 209).
 *
 * Before this fixture the corpus held 33 hand-written `INSERT INTO students`
 * statements naming the three decomposed graduation columns, in 33 files across
 * four modules. Each one was a copy of the storage shape, so changing that
 * shape meant editing 33 tests that have nothing to do with graduation dates.
 * Now a test says what date it wants, in [PartialDate] -- the type the product
 * speaks -- and the next change of storage costs this file.
 *
 * It lives in `:db`'s test fixtures because `:service`, `:college` and
 * `:admin-web` tests already consume them; it adds no module coupling.
 */
object StudentRowFixture {
  /**
   * The year-only 2028 every existing fixture was written against, and the
   * shape signup records most often: a family that knows the graduating class
   * and not the ceremony date.
   */
  val DEFAULT_GRADUATION: PartialDate = PartialDate.YearOnly(Year.of(2028))

  /**
   * A comfortably 13+ date of birth (RFC 222). A LITERAL, never
   * `LocalDate.now() - 13 years`: a fixture that drifts with the wall clock
   * makes the boundary tests lie about which side of the floor they are on.
   */
  val DEFAULT_DATE_OF_BIRTH: LocalDate = LocalDate.of(2009, 7, 10)

  private const val INSERT_SQL =
    "INSERT INTO students (id, user_id, expected_high_school_graduation_on, " +
      "expected_high_school_graduation_precision, date_of_birth) VALUES (?, ?, ?, ?, ?)"

  /** Inserts one student row. The owning `users` row must already exist. */
  fun insert(
    connection: Connection,
    studentId: UUID,
    userId: UUID,
    graduation: PartialDate = DEFAULT_GRADUATION,
    dateOfBirth: LocalDate = DEFAULT_DATE_OF_BIRTH,
  ) {
    connection.prepareStatement(INSERT_SQL).use { stmt ->
      stmt.setObject(1, studentId)
      stmt.setObject(2, userId)
      bindGraduation(stmt, 3, graduation)
      stmt.setObject(5, dateOfBirth)
      stmt.executeUpdate()
    }
  }

  /**
   * Moves an existing student's graduation date.
   *
   * `version = version + 1` is not optional: `students` carries the
   * optimistic-concurrency trigger, which refuses an UPDATE that does not
   * advance the version.
   */
  fun updateGraduation(
    connection: Connection,
    studentId: UUID,
    graduation: PartialDate,
  ) {
    connection
      .prepareStatement(
        "UPDATE students SET expected_high_school_graduation_on = ?, " +
          "expected_high_school_graduation_precision = ?, version = version + 1 WHERE id = ?",
      ).use { stmt ->
        bindGraduation(stmt, 1, graduation)
        stmt.setObject(3, studentId)
        stmt.executeUpdate()
      }
  }

  /** A year-only date, the shape most of the corpus wants. */
  fun graduationYear(year: Int): PartialDate = PartialDate.YearOnly(Year.of(year))

  private fun bindGraduation(
    stmt: java.sql.PreparedStatement,
    firstIndex: Int,
    graduation: PartialDate,
  ) {
    stmt.setObject(firstIndex, PartialDateColumns.periodStart(graduation))
    stmt.setString(firstIndex + 1, PartialDateColumns.precision(graduation).value)
  }
}
