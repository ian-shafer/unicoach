package ed.unicoach.student

import ed.unicoach.db.Database
import ed.unicoach.db.dao.ConcurrentModificationException
import ed.unicoach.db.dao.NotFoundException
import ed.unicoach.db.dao.SessionsDao
import ed.unicoach.db.dao.StudentAlreadyExistsException
import ed.unicoach.db.dao.StudentsDao
import ed.unicoach.db.dao.UsersDao
import ed.unicoach.db.models.NewStudent
import ed.unicoach.db.models.PartialDate
import ed.unicoach.db.models.Student
import ed.unicoach.db.models.StudentVersionId
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.UserId
import ed.unicoach.db.models.ValidationResult
import ed.unicoach.error.FieldError

class StudentService(
  private val database: Database,
) {
  companion object {
    private const val GRADUATION_DATE_FIELD = "expectedHighSchoolGraduationDate"

    private fun graduationDateError(): List<FieldError> = listOf(FieldError(GRADUATION_DATE_FIELD, "Invalid graduation date format"))
  }

  suspend fun createStudent(
    userId: UserId,
    graduationDateIso: String,
  ): Result<CreateStudentResult> {
    val parsed = PartialDate.parse(graduationDateIso)
    if (parsed !is ValidationResult.Valid) {
      return Result.success(CreateStudentResult.ValidationFailure(graduationDateError()))
    }

    return try {
      database.withConnection { session ->
        val result = StudentsDao.create(session, NewStudent(userId, parsed.value))
        if (result.isSuccess) {
          return@withConnection Result.success(CreateStudentResult.Success(result.getOrThrow()))
        }
        when (result.exceptionOrNull()) {
          is StudentAlreadyExistsException -> Result.success(CreateStudentResult.AlreadyExists)
          else -> Result.failure(result.exceptionOrNull()!!)
        }
      }
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  suspend fun getStudentForUser(userId: UserId): Result<Student?> =
    try {
      database.withConnection { session ->
        val result = StudentsDao.findByUserId(session, userId)
        if (result.isSuccess) {
          Result.success(result.getOrThrow())
        } else if (result.exceptionOrNull() is NotFoundException) {
          Result.success(null)
        } else {
          Result.failure(result.exceptionOrNull()!!)
        }
      }
    } catch (e: Exception) {
      Result.failure(e)
    }

  suspend fun updateStudent(
    userId: UserId,
    expectedVersion: StudentVersionId,
    graduationDateIso: String,
  ): Result<UpdateStudentResult> {
    val parsed = PartialDate.parse(graduationDateIso)
    if (parsed !is ValidationResult.Valid) {
      return Result.success(UpdateStudentResult.ValidationFailure(graduationDateError()))
    }

    return try {
      database.withConnection { session ->
        val existingResult = StudentsDao.findByUserId(session, userId)
        if (existingResult.isFailure) {
          return@withConnection if (existingResult.exceptionOrNull() is NotFoundException) {
            Result.success(UpdateStudentResult.NotFound)
          } else {
            Result.failure(existingResult.exceptionOrNull()!!)
          }
        }
        val existing = existingResult.getOrThrow()
        if (existing.versionId != expectedVersion) {
          return@withConnection Result.success(UpdateStudentResult.VersionConflict)
        }

        val updateResult =
          StudentsDao.update(
            session,
            existing.copy(expectedHighSchoolGraduationDate = parsed.value),
          )
        if (updateResult.isSuccess) {
          return@withConnection Result.success(UpdateStudentResult.Success(updateResult.getOrThrow()))
        }
        when (updateResult.exceptionOrNull()) {
          is ConcurrentModificationException -> Result.success(UpdateStudentResult.VersionConflict)
          is NotFoundException -> Result.success(UpdateStudentResult.NotFound)
          else -> Result.failure(updateResult.exceptionOrNull()!!)
        }
      }
    } catch (e: Exception) {
      Result.failure(e)
    }
  }

  suspend fun deleteStudentAndAccount(
    userId: UserId,
    currentTokenHash: TokenHash,
  ): Result<DeleteStudentResult> =
    try {
      database.withConnection { session ->
        val studentResult = StudentsDao.findByUserIdForUpdate(session, userId)
        if (studentResult.isFailure) {
          return@withConnection if (studentResult.exceptionOrNull() is NotFoundException) {
            Result.success(DeleteStudentResult.NotFound)
          } else {
            Result.failure(studentResult.exceptionOrNull()!!)
          }
        }
        val student = studentResult.getOrThrow()

        val user = UsersDao.findByIdForUpdate(session, userId).getOrThrow()

        StudentsDao.delete(session, student.id, student.versionId).getOrThrow()
        UsersDao.delete(session, user.id, user.versionId).getOrThrow()
        SessionsDao.revokeByTokenHash(session, currentTokenHash).getOrThrow()

        Result.success(DeleteStudentResult.Success)
      }
    } catch (e: Exception) {
      Result.failure(e)
    }
}
