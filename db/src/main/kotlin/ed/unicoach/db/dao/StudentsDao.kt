package ed.unicoach.db.dao

import ed.unicoach.common.models.ValidationResult
import ed.unicoach.db.models.NewStudent
import ed.unicoach.db.models.PartialDate
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.db.models.Student
import ed.unicoach.db.models.StudentEdit
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.StudentVersion
import ed.unicoach.db.models.UserId
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

object StudentsDao :
  SoftDeleteFindable<Student, StudentId>,
  Creatable<NewStudent, Student>,
  Updatable<StudentEdit, Student>,
  OccDeletable<Student, StudentId>,
  VersionHistory<StudentId, StudentVersion> {
  /**
   * Reconstructs the [PartialDate] from the decomposed columns. The DB
   * constraints already guarantee a valid partial date is persisted, so an
   * `Invalid` result here indicates row corruption, not user input. Surfaced as
   * a [CorruptPersistedValueException] (a [ed.unicoach.error.PermanentError])
   * carrying the offending decomposed columns and the structured
   * [ed.unicoach.common.models.ValidationError], never a user-facing validation
   * failure.
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
        throw CorruptPersistedValueException(
          value = "year=$year month=$month day=$day",
          error = result.error,
        )
      }
    }
  }

  private fun mapStudent(rs: ResultSet): Student =
    Student(
      id = StudentId(UUID.fromString(rs.getString("id"))),
      userId = UserId(UUID.fromString(rs.getString("user_id"))),
      expectedHighSchoolGraduationDate = mapGraduationDate(rs),
      version = rs.getInt("version"),
      createdAt = rs.getInstant("created_at"),
      updatedAt = rs.getInstant("updated_at"),
      deletedAt = rs.getInstantOrNull("deleted_at"),
    )

  /** Whether a [SoftDeleteScope] admits a row with the given `deletedAt`. */
  private fun SoftDeleteScope.admits(deletedAt: java.time.Instant?): Boolean =
    when (this) {
      SoftDeleteScope.ACTIVE -> deletedAt == null
      SoftDeleteScope.DELETED -> deletedAt != null
      SoftDeleteScope.ALL -> true
    }

  override fun findById(
    session: SqlSession,
    id: StudentId,
    scope: SoftDeleteScope,
  ): Result<Student> =
    session
      .queryOne(
        "SELECT * FROM students WHERE id = ?",
        bind = { it.setObject(1, id.value) },
        map = ::mapStudent,
      ).mapCatching { student ->
        if (!scope.admits(student.deletedAt)) throw NotFoundException()
        student
      }

  fun findByUserId(
    session: SqlSession,
    userId: UserId,
    scope: SoftDeleteScope = SoftDeleteScope.ACTIVE,
  ): Result<Student> =
    session
      .queryOne(
        "SELECT * FROM students WHERE user_id = ?",
        bind = { it.setObject(1, userId.value) },
        map = ::mapStudent,
      ).mapCatching { student ->
        if (!scope.admits(student.deletedAt)) throw NotFoundException()
        student
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

  override fun create(
    session: SqlSession,
    input: NewStudent,
  ): Result<Student> =
    session.insertReturning(
      table = "students",
      columns =
        gradDateColumns(
          prefix = "user_id" to { stmt, i -> stmt.setObject(i, input.userId.value) },
          date = input.expectedHighSchoolGraduationDate,
        ),
      map = ::mapStudent,
      mapError = ::mapCreateUpdateError,
    )

  override fun update(
    session: SqlSession,
    edit: StudentEdit,
  ): Result<Student> =
    session.updateColumnsReturning(
      table = "students",
      id = edit.id.value,
      currentVersion = edit.version,
      columns = gradDateColumns(prefix = null, date = edit.expectedHighSchoolGraduationDate),
      map = ::mapStudent,
      mapError = ::mapCreateUpdateError,
    )

  /**
   * Builds the three decomposed grad-date columns as independent single-column
   * binders, optionally prefixed by another column (the `user_id` on create).
   */
  private fun gradDateColumns(
    prefix: Pair<String, Bind>? = null,
    date: PartialDate,
  ): Map<String, Bind> {
    val cols = linkedMapOf<String, Bind>()
    if (prefix != null) cols[prefix.first] = prefix.second
    cols["expected_high_school_graduation_year"] = { stmt, i -> stmt.setInt(i, date.year.value) }
    cols["expected_high_school_graduation_month"] = { stmt, i -> stmt.setIntOrNull(i, date.month?.value) }
    cols["expected_high_school_graduation_day"] = { stmt, i -> stmt.setIntOrNull(i, date.day) }
    return cols
  }

  override fun delete(
    session: SqlSession,
    id: StudentId,
    currentVersion: Int,
  ): Result<Student> =
    session.softDeleteReturning(
      table = "students",
      id = id.value,
      currentVersion = currentVersion,
      deleted = true,
      map = ::mapStudent,
      mapError = ::mapCreateUpdateError,
    )

  override fun undelete(
    session: SqlSession,
    id: StudentId,
    currentVersion: Int,
  ): Result<Student> =
    session.softDeleteReturning(
      table = "students",
      id = id.value,
      currentVersion = currentVersion,
      deleted = false,
      map = ::mapStudent,
      mapError = ::mapCreateUpdateError,
    )

  private fun mapStudentVersion(rs: ResultSet): StudentVersion =
    StudentVersion(
      id = StudentId(UUID.fromString(rs.getString("id"))),
      userId = UserId(UUID.fromString(rs.getString("user_id"))),
      expectedHighSchoolGraduationDate = mapGraduationDate(rs),
      version = rs.getInt("version"),
      createdAt = rs.getInstant("created_at"),
      updatedAt = rs.getInstant("updated_at"),
      deletedAt = rs.getInstantOrNull("deleted_at"),
    )

  /** Admin read surface: a student's full version history, ascending by version. */
  override fun listVersions(
    session: SqlSession,
    id: StudentId,
  ): Result<List<StudentVersion>> =
    session.queryList(
      "SELECT * FROM students_versions WHERE id = ? ORDER BY version",
      bind = { it.setObject(1, id.value) },
      map = ::mapStudentVersion,
    )

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
