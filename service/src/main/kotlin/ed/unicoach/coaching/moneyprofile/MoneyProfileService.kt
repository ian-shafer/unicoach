package ed.unicoach.coaching.moneyprofile

import ed.unicoach.db.Database
import ed.unicoach.db.dao.MoneyProfilesDao
import ed.unicoach.db.dao.NotFoundException
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.MoneyProfile
import ed.unicoach.db.models.MoneyProfileUpsert
import ed.unicoach.db.models.StudentId

/**
 * One tri-state field update inside a [MoneyProfileUpdate]: set a value,
 * decline the field, or clear it back to unanswered. A field with no update is
 * simply absent from the [MoneyProfileUpdate], so a partial write never
 * touches the other field.
 */
sealed interface FieldUpdate<out T> {
  data class Set<T>(
    val value: T,
  ) : FieldUpdate<T>

  data object Decline : FieldUpdate<Nothing>

  data object Clear : FieldUpdate<Nothing>
}

/**
 * A create-or-update write against a student's money profile (RFC 134): any
 * subset of fields, each as value-or-declined-or-clear. Both writers (REST PUT
 * and the `update_money_profile` chat tool) reduce to this shape, so the
 * tri-state write semantics have exactly one implementation.
 */
data class MoneyProfileUpdate(
  val income: FieldUpdate<IncomeBand>? = null,
  val residency: FieldUpdate<String>? = null,
)

sealed interface GetMoneyProfileResult {
  data class Found(
    val profile: MoneyProfile,
  ) : GetMoneyProfileResult

  data object NotFound : GetMoneyProfileResult
}

sealed interface UpsertMoneyProfileResult {
  data class Success(
    val profile: MoneyProfile,
  ) : UpsertMoneyProfileResult

  data object StudentNotFound : UpsertMoneyProfileResult
}

/**
 * Domain layer over [MoneyProfilesDao] (RFC 134), same shape as
 * [ed.unicoach.coaching.collegelist.CollegeListService]: one [Database]-backed
 * class, `Result<sealed outcome>` per operation, no HTTP/Ktor imports.
 *
 * [upsert] is idempotent create-or-update, delegated to
 * [MoneyProfilesDao.upsertForStudent] as one atomic `INSERT ... ON CONFLICT
 * DO UPDATE`: the first write creates the student's single active row, later
 * writes are plain versioned updates (history preserves the answer -> decline
 * -> re-answer trail), and two concurrent first writes cannot race into a
 * uniqueness error. There is no caller-supplied OCC version: each field
 * update is an absolute statement of that field's new state, and untouched
 * fields are kept by the DAO's apply-or-keep column semantics.
 */
class MoneyProfileService(
  private val database: Database,
) {
  suspend fun getForStudent(studentId: StudentId): Result<GetMoneyProfileResult> =
    try {
      database.withConnection { session ->
        val result = MoneyProfilesDao.findActiveByStudent(session, studentId)
        when {
          result.isSuccess -> Result.success(GetMoneyProfileResult.Found(result.getOrThrow()))
          result.exceptionOrNull() is NotFoundException -> Result.success(GetMoneyProfileResult.NotFound)
          else -> Result.failure(result.exceptionOrNull()!!)
        }
      }
    } catch (e: Exception) {
      Result.failure(e)
    }

  suspend fun upsert(
    studentId: StudentId,
    update: MoneyProfileUpdate,
  ): Result<UpsertMoneyProfileResult> =
    try {
      database.withConnection { session ->
        val writeResult =
          MoneyProfilesDao.upsertForStudent(
            session,
            MoneyProfileUpsert(
              studentId = studentId,
              income = update.income?.let(::mapFieldUpdate),
              residency = update.residency?.let(::mapFieldUpdate),
            ),
          )
        when {
          writeResult.isSuccess -> Result.success(UpsertMoneyProfileResult.Success(writeResult.getOrThrow()))
          writeResult.exceptionOrNull() is NotFoundException -> Result.success(UpsertMoneyProfileResult.StudentNotFound)
          else -> Result.failure(writeResult.exceptionOrNull()!!)
        }
      }
    } catch (e: Exception) {
      Result.failure(e)
    }

  /** Folds one [FieldUpdate] into the sealed per-field state the DAO persists — the tri-state fold's single home. */
  private fun <T> mapFieldUpdate(update: FieldUpdate<T>): MoneyProfileUpsert.FieldWrite<T> =
    when (update) {
      is FieldUpdate.Set -> MoneyProfileUpsert.FieldWrite.Answer(update.value)
      FieldUpdate.Decline -> MoneyProfileUpsert.FieldWrite.Declined
      FieldUpdate.Clear -> MoneyProfileUpsert.FieldWrite.Cleared
    }

  companion object {
    /**
     * The closed vocabulary residency may take: the College Scorecard `STABBR`
     * domain (the source of our college data, RFC 133) — the 50 states, DC,
     * and the USPS territory / freely-associated-state codes (AS, FM, GU, MH,
     * MP, PR, PW, VI). Membership here, not a two-letter shape, is the
     * boundary; the schema's `^[A-Z]{2}$` CHECK stays as the coarser DB-level
     * backstop.
     */
    private val USPS_STATE_CODES =
      (
        "AL AK AZ AR CA CO CT DE FL GA HI ID IL IN IA KS KY LA ME MD " +
          "MA MI MN MS MO MT NE NV NH NJ NM NY NC ND OH OK OR PA RI SC " +
          "SD TN TX UT VT VA WA WV WI WY DC AS FM GU MH MP PR PW VI"
      ).split(" ").toSet()

    /**
     * The normalized USPS residency-state code, or null when [raw] is not a
     * member of [USPS_STATE_CODES]. The single home for the rule every writer
     * enforces (trim, uppercase, membership); each surface keeps only its own
     * error wording.
     */
    fun parseResidencyState(raw: String): String? = raw.trim().uppercase().takeIf { it in USPS_STATE_CODES }
  }
}
