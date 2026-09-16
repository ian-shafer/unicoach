package ed.unicoach.student

import ed.unicoach.common.models.ValidationResult
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
import ed.unicoach.db.models.StudentEdit
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.UserId
import ed.unicoach.error.FieldError
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * [clock] is what the 13+ age floor of RFC 222 is judged against, defaulted to
 * [Clock.systemUTC] so every production call site is unchanged and a test can
 * pin "today" (house precedent: `SynthesisService`). UTC is the zone: at worst a
 * signup within a day of the boundary is judged by a date a few hours from the
 * student's local one, which is immaterial to a compliance floor and is not
 * worth a timezone field on the request.
 */
class StudentService(
  private val database: Database,
  private val clock: Clock = Clock.systemUTC(),
) {
  companion object {
    private const val GRADUATION_DATE_FIELD = "expectedHighSchoolGraduationDate"
    const val DATE_OF_BIRTH_FIELD = "dateOfBirth"

    /** COPPA's line: a general-audience service that never collects from an under-13 stays outside it. */
    const val MINIMUM_AGE_YEARS = 13L

    /**
     * A sanity ceiling, not a compliance rule: past it the date is a typo, not a
     * person. Public because `admin-web`'s operator create path bounds the same
     * column with the same window (RFC 222 section 4a exempts that path from the
     * age FLOOR only), and a second number there would be a second truth.
     */
    const val MAXIMUM_AGE_YEARS = 120L

    /**
     * The one accepted wire form, matching the `^[0-9]{4}-[0-9]{2}-[0-9]{2}$`
     * pattern `api-specs/openapi.yaml` publishes. Checked BEFORE parsing, on the
     * `PartialDate.parse` precedent: `LocalDate.parse` uses `SignStyle.EXCEEDS_PAD`
     * and so also accepts signed expanded years such as `+02026-07-10`, which the
     * published contract rejects.
     */
    private val CANONICAL_DATE_OF_BIRTH = Regex("""^\d{4}-\d{2}-\d{2}$""")

    private fun graduationDateError(): FieldError = FieldError(GRADUATION_DATE_FIELD, "Invalid graduation date format")

    private fun dateOfBirthError(message: String): FieldError = FieldError(DATE_OF_BIRTH_FIELD, message)
  }

  /**
   * The one gate every signup path passes through. `POST /api/v1/students` is
   * the only way a `students` row is created, and it runs after email/password,
   * Google and Apple alike -- neither identity provider can supply a birthdate,
   * so asking here covers all three with one check and no auth-route change.
   *
   * [dateOfBirthIso] is nullable because an older installed client omits the
   * field entirely; that is a `ValidationFailure` naming `dateOfBirth`, not a
   * deserialization error. A refusal writes NOTHING: the floor exists to keep a
   * under-13 child's personal information out of the database, so the rejected
   * date is neither stored nor logged.
   */
  suspend fun createStudent(
    userId: UserId,
    graduationDateIso: String,
    dateOfBirthIso: String?,
  ): Result<CreateStudentResult> {
    val graduationDate = (PartialDate.parse(graduationDateIso) as? ValidationResult.Valid)?.value
    val mappedDateOfBirth = mapDateOfBirth(dateOfBirthIso)

    // Both answers are judged before either is reported, so a request with two
    // bad fields is told about both at once (precedent: RegistrationValidator).
    val fieldErrors =
      listOfNotNull(
        if (graduationDate == null) graduationDateError() else null,
        (mappedDateOfBirth as? DateOfBirthResult.Invalid)?.let { dateOfBirthError(it.message) },
      )
    if (fieldErrors.isNotEmpty()) {
      // A 400 outranks the 403: a malformed request was never judged on its merits.
      return Result.success(CreateStudentResult.ValidationFailure(fieldErrors))
    }

    // Nothing is left to report on either field, so a date of birth that is still
    // not Accepted is the 13+ floor and nothing else. The `graduationDate == null`
    // arm is unreachable -- a null one was reported as a field error above -- and
    // is written only so the insert below sees a non-null date; it is NOT a rule
    // that a bad graduation date answers 403.
    if (mappedDateOfBirth !is DateOfBirthResult.Accepted || graduationDate == null) {
      return Result.success(CreateStudentResult.UnderMinimumAge)
    }

    return try {
      database.withConnection { session ->
        val result = StudentsDao.create(session, NewStudent(userId, graduationDate, mappedDateOfBirth.value))
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

  /**
   * The outcome of reading one submitted date of birth: a usable date, a
   * malformed or nonsensical one (400), or a well-formed date below the floor
   * (403). Kept as a sealed local type so the three answers cannot be collapsed
   * into a boolean, which is exactly the distinction RFC 222 turns on.
   */
  private sealed interface DateOfBirthResult {
    data class Accepted(
      val value: LocalDate,
    ) : DateOfBirthResult

    data class Invalid(
      val message: String,
    ) : DateOfBirthResult

    data object UnderMinimumAge : DateOfBirthResult
  }

  private fun mapDateOfBirth(dateOfBirthIso: String?): DateOfBirthResult {
    if (dateOfBirthIso.isNullOrBlank()) {
      return DateOfBirthResult.Invalid("Date of birth is required")
    }
    if (!CANONICAL_DATE_OF_BIRTH.matches(dateOfBirthIso)) {
      return DateOfBirthResult.Invalid("Invalid date of birth format")
    }

    val dateOfBirth =
      try {
        LocalDate.parse(dateOfBirthIso)
      } catch (_: DateTimeParseException) {
        return DateOfBirthResult.Invalid("Invalid date of birth format")
      }

    val today = LocalDate.now(clock)
    if (dateOfBirth.isAfter(today)) {
      return DateOfBirthResult.Invalid("Date of birth cannot be in the future")
    }
    if (dateOfBirth.isBefore(today.minusYears(MAXIMUM_AGE_YEARS))) {
      return DateOfBirthResult.Invalid("Date of birth is not a plausible date")
    }

    return if (isUnderMinimumAge(dateOfBirth, today)) {
      DateOfBirthResult.UnderMinimumAge
    } else {
      DateOfBirthResult.Accepted(dateOfBirth)
    }
  }

  /**
   * The compliance floor, and nothing else: the one rule this slice exists for,
   * named so it can be read and called without the field-shape checks around it.
   * `<=`, not `<` -- a student whose thirteenth birthday is TODAY is accepted.
   */
  private fun isUnderMinimumAge(
    dateOfBirth: LocalDate,
    today: LocalDate,
  ): Boolean = dateOfBirth.isAfter(today.minusYears(MINIMUM_AGE_YEARS))

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
    expectedVersion: Int,
    graduationDateIso: String,
  ): Result<UpdateStudentResult> {
    val parsed = PartialDate.parse(graduationDateIso)
    if (parsed !is ValidationResult.Valid) {
      return Result.success(UpdateStudentResult.ValidationFailure(listOf(graduationDateError())))
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
        if (existing.version != expectedVersion) {
          return@withConnection Result.success(UpdateStudentResult.VersionConflict)
        }

        val updateResult =
          StudentsDao.update(
            session,
            StudentEdit(
              id = existing.id,
              version = existing.version,
              expectedHighSchoolGraduationDate = parsed.value,
            ),
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

        StudentsDao.delete(session, student.id, student.version).getOrThrow()
        UsersDao.delete(session, user.id, user.version).getOrThrow()
        SessionsDao.revokeByTokenHash(session, currentTokenHash).getOrThrow()

        Result.success(DeleteStudentResult.Success)
      }
    } catch (e: Exception) {
      Result.failure(e)
    }
}
