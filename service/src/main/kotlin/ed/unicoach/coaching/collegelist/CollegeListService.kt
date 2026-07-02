package ed.unicoach.coaching.collegelist

import ed.unicoach.db.Database
import ed.unicoach.db.dao.CollegeListEntriesDao
import ed.unicoach.db.dao.CollegeListEntrySupportDao
import ed.unicoach.db.dao.ConcurrentModificationException
import ed.unicoach.db.dao.ConstraintViolationException
import ed.unicoach.db.dao.NotFoundException
import ed.unicoach.db.dao.ObservationsDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeListEntryEdit
import ed.unicoach.db.models.CollegeListEntryId
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.db.models.NewCollegeListEntry
import ed.unicoach.db.models.Observation
import ed.unicoach.db.models.ObservationId
import ed.unicoach.db.models.StudentId

/**
 * Same shape as [ed.unicoach.student.StudentService]: one [Database]-backed
 * class, [Result]<outcome> per operation, OCC conflicts surfaced as a named
 * outcome rather than an exception (RFC 91). Every success outcome carries the
 * entry's supporting observations alongside it, so the REST route handler
 * never has to reach past this service into the DAO/Database layer itself.
 */
class CollegeListService(
  private val database: Database,
) {
  suspend fun addToList(
    studentId: StudentId,
    collegeId: CollegeId,
    status: CollegeListEntryStatus,
    reasons: String?,
    observationIds: List<ObservationId>,
  ): Result<AddToListResult> =
    try {
      database.withConnection { session ->
        val citationCheck = verifyCitationsOwnedByStudent(session, studentId, observationIds)
        if (citationCheck != null) {
          return@withConnection Result.success(AddToListResult.ObservationNotFound(citationCheck))
        }

        val createResult =
          CollegeListEntriesDao.create(
            session,
            NewCollegeListEntry(studentId, collegeId, status, reasons),
          )
        if (createResult.isFailure) {
          return@withConnection mapCreateOutcome(createResult.exceptionOrNull()!!)
        }
        val entry = createResult.getOrThrow()

        for (observationId in observationIds) {
          CollegeListEntrySupportDao.link(session, entry.id, observationId).getOrThrow()
        }

        val supportingObservations = supportingObservationsFor(session, entry.id)
        Result.success(AddToListResult.Success(entry, supportingObservations))
      }
    } catch (e: Exception) {
      Result.failure(e)
    }

  suspend fun listForStudent(studentId: StudentId): Result<List<CollegeListEntryWithSupport>> =
    try {
      database.withConnection { session ->
        CollegeListEntriesDao.listActiveByStudent(session, studentId).map { entries ->
          entries.map { entry -> CollegeListEntryWithSupport(entry, supportingObservationsFor(session, entry.id)) }
        }
      }
    } catch (e: Exception) {
      Result.failure(e)
    }

  suspend fun getForStudent(
    studentId: StudentId,
    entryId: CollegeListEntryId,
  ): Result<GetEntryResult> =
    try {
      database.withConnection { session ->
        val result = CollegeListEntriesDao.findByIdAndStudent(session, entryId, studentId)
        if (result.isSuccess) {
          val entry = result.getOrThrow()
          Result.success(GetEntryResult.Found(entry, supportingObservationsFor(session, entry.id)))
        } else if (result.exceptionOrNull() is NotFoundException) {
          Result.success(GetEntryResult.NotFound)
        } else {
          Result.failure(result.exceptionOrNull()!!)
        }
      }
    } catch (e: Exception) {
      Result.failure(e)
    }

  suspend fun updateEntry(
    studentId: StudentId,
    entryId: CollegeListEntryId,
    expectedVersion: Int,
    status: CollegeListEntryStatus,
    reasons: String?,
    addObservationIds: List<ObservationId>,
  ): Result<UpdateEntryResult> =
    try {
      database.withConnection { session ->
        val existingResult = CollegeListEntriesDao.findByIdAndStudent(session, entryId, studentId)
        if (existingResult.isFailure) {
          return@withConnection if (existingResult.exceptionOrNull() is NotFoundException) {
            Result.success(UpdateEntryResult.NotFound)
          } else {
            Result.failure(existingResult.exceptionOrNull()!!)
          }
        }
        val existing = existingResult.getOrThrow()
        if (existing.version != expectedVersion) {
          return@withConnection Result.success(UpdateEntryResult.VersionConflict)
        }

        val citationCheck = verifyCitationsOwnedByStudent(session, studentId, addObservationIds)
        if (citationCheck != null) {
          return@withConnection Result.success(UpdateEntryResult.ObservationNotFound(citationCheck))
        }

        val updateResult =
          CollegeListEntriesDao.update(
            session,
            CollegeListEntryEdit(
              id = existing.id,
              version = existing.version,
              status = status,
              reasons = reasons,
            ),
          )
        if (updateResult.isFailure) {
          return@withConnection mapUpdateOutcome(updateResult.exceptionOrNull()!!)
        }
        val updated = updateResult.getOrThrow()

        for (observationId in addObservationIds) {
          CollegeListEntrySupportDao.link(session, updated.id, observationId).getOrThrow()
        }

        val supportingObservations = supportingObservationsFor(session, updated.id)
        Result.success(UpdateEntryResult.Success(updated, supportingObservations))
      }
    } catch (e: Exception) {
      Result.failure(e)
    }

  suspend fun removeFromList(
    studentId: StudentId,
    entryId: CollegeListEntryId,
    expectedVersion: Int,
  ): Result<RemoveEntryResult> =
    try {
      database.withConnection { session ->
        val existingResult = CollegeListEntriesDao.findByIdAndStudent(session, entryId, studentId)
        if (existingResult.isFailure) {
          return@withConnection if (existingResult.exceptionOrNull() is NotFoundException) {
            Result.success(RemoveEntryResult.NotFound)
          } else {
            Result.failure(existingResult.exceptionOrNull()!!)
          }
        }
        val existing = existingResult.getOrThrow()
        if (existing.version != expectedVersion) {
          return@withConnection Result.success(RemoveEntryResult.VersionConflict)
        }

        val deleteResult = CollegeListEntriesDao.delete(session, existing.id, existing.version)
        if (deleteResult.isFailure) {
          return@withConnection mapDeleteOutcome(deleteResult.exceptionOrNull()!!)
        }

        Result.success(RemoveEntryResult.Success(deleteResult.getOrThrow()))
      }
    } catch (e: Exception) {
      Result.failure(e)
    }

  // ---------------------------------------------------------------------------
  // SQLException -> outcome translation
  // ---------------------------------------------------------------------------

  /**
   * Translates a failed [CollegeListEntriesDao.create]. A [ConstraintViolationException]
   * is either the active-uniqueness violation (-> [AddToListResult.AlreadyOnList])
   * or the `reasons` length/non-empty CHECK (-> [AddToListResult.InvalidReasons]),
   * discriminated by [ConstraintViolationException.constraint] -- the two used to
   * collapse into the same outcome, which wrongly reported an invalid `reasons`
   * value as "already on the list."
   */
  private fun mapCreateOutcome(error: Throwable): Result<AddToListResult> =
    when {
      error is ConstraintViolationException && error.isReasonsCheck() -> Result.success(AddToListResult.InvalidReasons)
      error is ConstraintViolationException -> Result.success(AddToListResult.AlreadyOnList)
      error is NotFoundException -> Result.success(AddToListResult.CollegeNotFound)
      else -> Result.failure(error)
    }

  /** Translates a failed [CollegeListEntriesDao.update]; see [mapCreateOutcome] for the CHECK-vs-uniqueness split. */
  private fun mapUpdateOutcome(error: Throwable): Result<UpdateEntryResult> =
    when {
      error is ConstraintViolationException && error.isReasonsCheck() -> Result.success(UpdateEntryResult.InvalidReasons)
      error is ConcurrentModificationException -> Result.success(UpdateEntryResult.VersionConflict)
      error is NotFoundException -> Result.success(UpdateEntryResult.NotFound)
      else -> Result.failure(error)
    }

  /** Translates a failed [CollegeListEntriesDao.delete]. */
  private fun mapDeleteOutcome(error: Throwable): Result<RemoveEntryResult> =
    when (error) {
      is ConcurrentModificationException -> Result.success(RemoveEntryResult.VersionConflict)
      is NotFoundException -> Result.success(RemoveEntryResult.NotFound)
      else -> Result.failure(error)
    }

  /** Whether this violation is the `reasons` length/non-empty CHECK, rather than the active-uniqueness index. */
  private fun ConstraintViolationException.isReasonsCheck(): Boolean =
    constraint == "college_list_entries_reasons_length_check" || constraint == "college_list_entries_reasons_not_empty_check"

  // ---------------------------------------------------------------------------
  // Supporting-observations projection
  // ---------------------------------------------------------------------------

  /** The observations backing [entryId], for folding into a success outcome. */
  private fun supportingObservationsFor(
    session: SqlSession,
    entryId: CollegeListEntryId,
  ): List<Observation> = CollegeListEntrySupportDao.listObservationsForEntry(session, entryId).getOrThrow()

  // ---------------------------------------------------------------------------
  // Citation ownership
  // ---------------------------------------------------------------------------

  /**
   * Verifies every cited observation exists and is owned by [studentId]. Returns
   * the first offending id (absent, or owned by a different student -- identical
   * wire treatment, so the endpoint never confirms or denies another student's
   * observation exists), or null when every citation is valid. A genuine
   * infrastructure failure while looking up an observation propagates (caught by
   * the caller's outer try/catch as a real `Result.failure`) rather than being
   * collapsed into "not found."
   */
  private fun verifyCitationsOwnedByStudent(
    session: SqlSession,
    studentId: StudentId,
    observationIds: List<ObservationId>,
  ): ObservationId? {
    for (observationId in observationIds) {
      val observationResult = ObservationsDao.findById(session, observationId)
      val observation = observationResult.getOrElse { e -> if (e is NotFoundException) null else throw e }
      if (observation == null || observation.studentId != studentId) {
        return observationId
      }
    }
    return null
  }
}
