package ed.unicoach.coaching.collegelist

import ed.unicoach.db.Database
import ed.unicoach.db.dao.CollegeListEntriesDao
import ed.unicoach.db.dao.CollegeListEntrySupportDao
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.dao.ConcurrentModificationException
import ed.unicoach.db.dao.ConstraintViolationException
import ed.unicoach.db.dao.NotFoundException
import ed.unicoach.db.dao.ObservationsDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeListEntryEdit
import ed.unicoach.db.models.CollegeListEntryId
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.db.models.LivingArrangement
import ed.unicoach.db.models.NewCollegeListEntry
import ed.unicoach.db.models.Observation
import ed.unicoach.db.models.ObservationId
import ed.unicoach.db.models.StudentId
import java.time.Instant

/**
 * The student's active list with each college's name, on [session], in the
 * order the student added each school (`created_at, id`). Names come from one
 * batch read. The one home of this read: every surface that needs the named
 * active list calls it rather than re-joining the DAO and [CollegesDao].
 *
 * Throws on a failed read, so a caller's `withConnection` block rolls back and
 * a DB outage fails the turn rather than reading as an empty list.
 */
fun readActiveListInSession(
  session: SqlSession,
  studentId: StudentId,
): List<EntryWithCollegeName> {
  val entries = CollegeListEntriesDao.listActiveByStudent(session, studentId).getOrThrow()
  val names =
    CollegesDao
      .listByIds(session, entries.map { it.collegeId })
      .getOrThrow()
      .associate { it.id to it.name }
  return entries.map { entry ->
    // FK-guaranteed present; a miss is corruption worth naming.
    val name =
      checkNotNull(names[entry.collegeId]) {
        "college [${entry.collegeId.value}] missing for active list entry [${entry.id.value}]"
      }
    EntryWithCollegeName(entry, name)
  }
}

/**
 * One entry of a student's list as a linked adult sees it (RFC 250): the
 * school, where the student stands with it, and the school's own living plan.
 *
 * No `reasons`, no supporting observations, no entry id and no version. The
 * first two are the student's own words from their coach conversations; the
 * last two only serve writes, and an adult has none.
 */
data class SharedCollegeListEntry(
  val collegeId: CollegeId,
  val collegeName: String,
  val status: CollegeListEntryStatus,
  val livingPlan: LivingArrangement?,
  val updatedAt: Instant,
)

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
    /**
     * The per-college living-plan override (RFC 152 D2a). `null` is "no
     * override": this school uses the family's usual plan from the money
     * profile. Not defaulted, so every caller states which it means.
     */
    livingPlan: LivingArrangement?,
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
            NewCollegeListEntry(studentId, collegeId, status, reasons, livingPlan),
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

  /**
   * The active list with each entry's college display name resolved in the
   * same session. The chat tool's read (RFC 136): the tool renders names into
   * its echo without reaching past this service. Names come from one batch
   * read ([CollegesDao.listByIds] projected to a map) — the RFC 136/137
   * reconciliation point: both surfaces converge on the batch form rather
   * than a per-entry `findById`.
   */
  suspend fun listActiveWithNames(studentId: StudentId): Result<List<EntryWithCollegeName>> =
    try {
      Result.success(database.withConnection { session -> readActiveListInSession(session, studentId) })
    } catch (e: Exception) {
      Result.failure(e)
    }

  /**
   * The active list as a linked adult may see it (RFC 250), read on the
   * CALLER's session so the link check and the read are one snapshot.
   *
   * It loads no observations and selects no `reasons` into the result: both
   * are the student's own words from their coach conversations, and an adult
   * reading the list sees which schools and where they stand, not why.
   *
   * Throws on a failed read, like `CollegeCostService.readInSession`: inside
   * the caller's `withConnection` block a throw is what rolls the session back.
   */
  fun listShared(
    session: SqlSession,
    studentId: StudentId,
  ): List<SharedCollegeListEntry> =
    readActiveListInSession(session, studentId).map { (entry, collegeName) ->
      SharedCollegeListEntry(
        collegeId = entry.collegeId,
        collegeName = collegeName,
        status = entry.status,
        livingPlan = entry.livingPlan,
        updatedAt = entry.updatedAt,
      )
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
    /**
     * What this call asks of the per-college living-plan override (RFC 152 D2a,
     * RFC 164). Three states, not two: [LivingPlanUpdate.Set] writes a value,
     * [LivingPlanUpdate.Clear] writes `null` -- "no override, use the family's
     * usual plan" -- and [LivingPlanUpdate.Keep] leaves the stored value alone,
     * resolved here against the row already read for the OCC check.
     *
     * Non-nullable on purpose. "Leave it alone" and "drop it back to the usual
     * plan" are two different writes onto the same nullable column, and a bare
     * `null` cannot tell them apart; making [LivingPlanUpdate.Keep] a distinct
     * value forces every caller to say which one it means.
     */
    livingPlan: LivingPlanUpdate,
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

        // [existing] is the row already read for the OCC check above, so Keep
        // resolves against it without a second read.
        val livingPlanToStore = livingPlan.resolveAgainst(existing.livingPlan)

        val updateResult =
          CollegeListEntriesDao.update(
            session,
            CollegeListEntryEdit(
              id = existing.id,
              version = existing.version,
              status = status,
              reasons = reasons,
              livingPlan = livingPlanToStore,
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

  /**
   * The display names of the given college [ids], keyed by id (RFC 137). One
   * batch read ([CollegesDao.listByIds] projected to a map) rather than a
   * per-entry `findById`, so REST enrichment of a whole list is one query. An
   * id that resolves to no row is simply absent from the map — under
   * `ON DELETE RESTRICT` that is a broken FK invariant the caller treats as a
   * server error, never a state to paper over. Strictly additive: no existing
   * outcome changes (the RFC 136 conflict-surface rule).
   */
  suspend fun listNames(ids: List<CollegeId>): Result<Map<CollegeId, String>> =
    try {
      database.withConnection { session ->
        CollegesDao.listByIds(session, ids).map { rows -> rows.associate { it.id to it.name } }
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
