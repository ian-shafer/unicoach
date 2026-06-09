package ed.unicoach.db.dao

import ed.unicoach.common.models.ValidationResult
import ed.unicoach.db.models.NewStudent
import ed.unicoach.db.models.PartialDate
import ed.unicoach.db.models.Student
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.UserId
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

object StudentsDao {
  /**
   * Reconstructs the [PartialDate] from the decomposed columns. The DB
   * constraints already guarantee a valid partial date is persisted, so an
   * `Invalid` result here indicates row corruption, not user input. Surfaced as
   * a [DatabaseException] (a [ed.unicoach.error.PermanentError] mapping error),
   * never a user-facing validation failure.
   */
  private fun mapGraduationDate(rs: ResultSet): PartialDate {
    val year = rs.getInt("expected_high_school_graduation_year")
    val monthRaw = rs.getInt("expected_high_school_graduation_month")
    val month = if (rs.wasNull()) null else monthRaw
    val dayRaw = rs.getInt("expected_high_school_graduation_day")
    val day = if (rs.wasNull()) null else dayRaw

    return when (val result = PartialDate.of(year, month, day)) {
      is ValidationResult.Valid -> {
        result.value
      }

      is ValidationResult.Invalid -> {
        throw SQLException("Persisted graduation date columns do not form a valid partial date")
      }
    }
  }

  private fun mapStudent(rs: ResultSet): Student =
    Student(
      id = StudentId(UUID.fromString(rs.getString("id"))),
      userId = UserId(UUID.fromString(rs.getString("user_id"))),
      expectedHighSchoolGraduationDate = mapGraduationDate(rs),
      version = rs.getInt("version"),
      createdAt = rs.getTimestamp("created_at").toInstant(),
      updatedAt = rs.getTimestamp("updated_at").toInstant(),
      deletedAt = rs.getTimestamp("deleted_at")?.toInstant(),
    )

  private fun bindGraduationDate(
    stmt: java.sql.PreparedStatement,
    yearIndex: Int,
    date: PartialDate,
  ) {
    stmt.setInt(yearIndex, date.year.value)
    val month = date.month
    if (month != null) stmt.setInt(yearIndex + 1, month.value) else stmt.setNull(yearIndex + 1, java.sql.Types.SMALLINT)
    val day = date.day
    if (day != null) stmt.setInt(yearIndex + 2, day) else stmt.setNull(yearIndex + 2, java.sql.Types.SMALLINT)
  }

  fun findById(
    session: SqlSession,
    id: StudentId,
    includeDeleted: Boolean = false,
  ): Result<Student> =
    try {
      session.prepareStatement("SELECT * FROM students WHERE id = ?").use { stmt ->
        stmt.setObject(1, id.value)
        stmt.executeQuery().use { rs ->
          if (!rs.next()) {
            return Result.failure(NotFoundException())
          }
          val student = mapStudent(rs)
          if (!includeDeleted && student.deletedAt != null) {
            return Result.failure(NotFoundException())
          }
          Result.success(student)
        }
      }
    } catch (e: Exception) {
      Result.failure(mapDatabaseError(e))
    }

  fun findByUserId(
    session: SqlSession,
    userId: UserId,
    includeDeleted: Boolean = false,
  ): Result<Student> =
    try {
      session.prepareStatement("SELECT * FROM students WHERE user_id = ?").use { stmt ->
        stmt.setObject(1, userId.value)
        stmt.executeQuery().use { rs ->
          if (!rs.next()) {
            return Result.failure(NotFoundException())
          }
          val student = mapStudent(rs)
          if (!includeDeleted && student.deletedAt != null) {
            return Result.failure(NotFoundException())
          }
          Result.success(student)
        }
      }
    } catch (e: Exception) {
      Result.failure(mapDatabaseError(e))
    }

  fun findByIdForUpdate(
    session: SqlSession,
    id: StudentId,
  ): Result<Student> =
    try {
      session.prepareStatement("SELECT * FROM students WHERE id = ? FOR UPDATE NOWAIT").use { stmt ->
        stmt.setObject(1, id.value)
        stmt.executeQuery().use { rs ->
          if (!rs.next()) {
            return Result.failure(NotFoundException())
          }
          Result.success(mapStudent(rs))
        }
      }
    } catch (e: SQLException) {
      if (e.sqlState == "55P03") {
        return Result.failure(LockAcquisitionFailureException())
      }
      Result.failure(mapDatabaseError(e))
    } catch (e: Exception) {
      Result.failure(mapDatabaseError(e))
    }

  fun findByUserIdForUpdate(
    session: SqlSession,
    userId: UserId,
  ): Result<Student> =
    try {
      session.prepareStatement("SELECT * FROM students WHERE user_id = ? AND deleted_at IS NULL FOR UPDATE NOWAIT").use { stmt ->
        stmt.setObject(1, userId.value)
        stmt.executeQuery().use { rs ->
          if (!rs.next()) {
            return Result.failure(NotFoundException())
          }
          Result.success(mapStudent(rs))
        }
      }
    } catch (e: SQLException) {
      if (e.sqlState == "55P03") {
        return Result.failure(LockAcquisitionFailureException())
      }
      Result.failure(mapDatabaseError(e))
    } catch (e: Exception) {
      Result.failure(mapDatabaseError(e))
    }

  fun create(
    session: SqlSession,
    student: NewStudent,
  ): Result<Student> =
    try {
      val sql =
        """
        INSERT INTO students (
          user_id,
          expected_high_school_graduation_year,
          expected_high_school_graduation_month,
          expected_high_school_graduation_day
        )
        VALUES (?, ?, ?, ?)
        RETURNING *
        """.trimIndent()
      session.prepareStatement(sql).use { stmt ->
        stmt.setObject(1, student.userId.value)
        bindGraduationDate(stmt, 2, student.expectedHighSchoolGraduationDate)

        stmt.executeQuery().use { rs ->
          if (rs.next()) {
            Result.success(mapStudent(rs))
          } else {
            Result.failure(DatabaseException(RuntimeException("Insert succeeded but returning failed")))
          }
        }
      }
    } catch (e: SQLException) {
      Result.failure(mapCreateUpdateError(e))
    } catch (e: Exception) {
      Result.failure(mapDatabaseError(e))
    }

  fun update(
    session: SqlSession,
    student: Student,
  ): Result<Student> =
    try {
      val sql =
        """
        UPDATE students
        SET version = ?,
            expected_high_school_graduation_year = ?,
            expected_high_school_graduation_month = ?,
            expected_high_school_graduation_day = ?
        WHERE id = ? AND version = ?
        RETURNING *
        """.trimIndent()
      session.prepareStatement(sql).use { stmt ->
        stmt.setInt(1, student.version + 1)
        bindGraduationDate(stmt, 2, student.expectedHighSchoolGraduationDate)
        stmt.setObject(5, student.id.value)
        stmt.setInt(6, student.version)

        stmt.executeQuery().use { rs ->
          if (rs.next()) {
            Result.success(mapStudent(rs))
          } else {
            distinguishNotFoundOrConflict(session, student.id)
          }
        }
      }
    } catch (e: SQLException) {
      Result.failure(mapCreateUpdateError(e))
    } catch (e: Exception) {
      Result.failure(mapDatabaseError(e))
    }

  fun delete(
    session: SqlSession,
    id: StudentId,
    currentVersion: Int,
  ): Result<Student> =
    try {
      val sql =
        """
        UPDATE students
        SET version = ?, deleted_at = NOW()
        WHERE id = ? AND version = ?
        RETURNING *
        """.trimIndent()
      session.prepareStatement(sql).use { stmt ->
        stmt.setInt(1, currentVersion + 1)
        stmt.setObject(2, id.value)
        stmt.setInt(3, currentVersion)

        stmt.executeQuery().use { rs ->
          if (rs.next()) {
            Result.success(mapStudent(rs))
          } else {
            distinguishNotFoundOrConflict(session, id)
          }
        }
      }
    } catch (e: SQLException) {
      Result.failure(mapCreateUpdateError(e))
    } catch (e: Exception) {
      Result.failure(mapDatabaseError(e))
    }

  private fun distinguishNotFoundOrConflict(
    session: SqlSession,
    id: StudentId,
  ): Result<Student> =
    session.prepareStatement("SELECT version FROM students WHERE id = ?").use { checkStmt ->
      checkStmt.setObject(1, id.value)
      checkStmt.executeQuery().use { checkRs ->
        if (checkRs.next()) {
          Result.failure(ConcurrentModificationException())
        } else {
          Result.failure(NotFoundException())
        }
      }
    }

  /**
   * SQLSTATE discrimination for create/update operations.
   * - `23505` on the total unique index -> [StudentAlreadyExistsException].
   * - `22008` / `23514` on the grad-date constraints -> [ConstraintViolationException]
   *   (the existing validation-failure pathway: a permanent, caller-correctable error).
   * - `23503` (FK) -> [NotFoundException] (owning user absent).
   * - `40001` (OCC) and others -> general mapping ([mapDatabaseError]).
   */
  private fun mapCreateUpdateError(e: SQLException): Exception =
    when (e.sqlState) {
      "23505" -> {
        if (e.message?.contains("students_user_id_unique_idx") == true) {
          StudentAlreadyExistsException()
        } else {
          ConstraintViolationException(e)
        }
      }

      "22008", "23514" -> {
        ConstraintViolationException(e)
      }

      "23503" -> {
        NotFoundException("Owning user not found")
      }

      else -> {
        mapDatabaseError(e)
      }
    }
}
