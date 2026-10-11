package ed.unicoach.family

import ed.unicoach.coaching.SharingPermissionService
import ed.unicoach.coaching.collegelist.CollegeListService
import ed.unicoach.coaching.collegelist.SharedCollegeListEntry
import ed.unicoach.coaching.costs.CollegeCostProfile
import ed.unicoach.coaching.costs.CollegeCostService
import ed.unicoach.db.Database
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.SupporterStudentLinksDao
import ed.unicoach.db.models.SharingPermission
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.UserId
import kotlinx.coroutines.CancellationException

/**
 * What a read through [SubjectReadService] answers: the projection, or one
 * [NotFound] for every refusal.
 */
sealed interface SubjectRead<out T> {
  data class Found<T>(
    val value: T,
  ) : SubjectRead<T>

  /**
   * No such student, never linked, severed, a closed adult, a closed student —
   * all one value, so a caller cannot confirm that a student exists.
   */
  data object NotFound : SubjectRead<Nothing>
}

/**
 * The cost picture a linked adult sees: which of two it is, as a type, so
 * "the student has not shared what they want to study" is never read as "the
 * student has not answered" — the [CollegeCostProfile.programReturn] null that
 * already means the latter. The profile sits on each variant and not on this
 * interface, so a consumer reaches it only through a `when` that names which.
 */
sealed interface SharedCostPicture {
  /** The student granted [SharingPermission.INTENDED_SUBJECTS]: their own picture, program return and all. */
  data class Full(
    val profile: CollegeCostProfile,
  ) : SharedCostPicture

  /**
   * No grant: the student's own picture with the program return dropped here,
   * on construction, so this variant cannot carry one.
   *
   * Each field a linked adult may see is named, not copied: a new field on the
   * profile fails to compile here until someone decides whether it is derived
   * from what the student wants to study.
   */
  class SubjectsWithheld(
    studentsOwn: CollegeCostProfile,
  ) : SharedCostPicture {
    val profile: CollegeCostProfile =
      CollegeCostProfile(
        colleges = studentsOwn.colleges,
        unknownCollegeIds = studentsOwn.unknownCollegeIds,
        moneyProfile = studentsOwn.moneyProfile,
        comparisonBasis = studentsOwn.comparisonBasis,
        programReturn = null,
      )
  }
}

/**
 * The subject seam (RFC 250): the ONLY service that accepts a student id from
 * somebody who is not that student.
 *
 * Every other service method that takes a [StudentId] gets it from the REST
 * layer's `resolveStudent(user)`, which is the caller's OWN student, and a
 * parent account has none. Here the student is named by the caller, so each
 * call checks a live link first ([SupporterStudentLinksDao.findLiveForUser])
 * and reads on the SAME session — what was authorised is what is read, the
 * Family Cost Report's rule. Nothing is cached, so a severed link refuses the
 * very next call.
 *
 * Both methods are reads and both hand back projections. There is no write
 * here, and there must not be one: a linked adult views a student's data and
 * never changes it.
 */
class SubjectReadService(
  private val database: Database,
  private val collegeLists: CollegeListService,
  private val costs: CollegeCostService,
  private val sharingPermissions: SharingPermissionService,
) {
  /** The student's active list without their reasons or conversation quotes. */
  suspend fun collegeList(
    viewer: UserId,
    studentId: StudentId,
  ): Result<SubjectRead<List<SharedCollegeListEntry>>> = readLinked(viewer, studentId, collegeLists::listShared)

  /**
   * The derived cost picture — the computation the Family Cost Report shows a
   * parent. What the student wants to study is withheld unless they granted
   * [SharingPermission.INTENDED_SUBJECTS], so the program returns keyed by
   * those subjects reach the adult only on a grant.
   */
  suspend fun costPicture(
    viewer: UserId,
    studentId: StudentId,
  ): Result<SubjectRead<SharedCostPicture>> = readLinked(viewer, studentId, ::sharedCostPicture)

  private fun sharedCostPicture(
    session: SqlSession,
    studentId: StudentId,
  ): SharedCostPicture {
    val profile = costs.readInSession(session, studentId)
    return if (sharingPermissions.isGranted(session, studentId, SharingPermission.INTENDED_SUBJECTS)) {
      SharedCostPicture.Full(profile)
    } else {
      SharedCostPicture.SubjectsWithheld(profile)
    }
  }

  private suspend fun <T> readLinked(
    viewer: UserId,
    studentId: StudentId,
    read: (SqlSession, StudentId) -> T,
  ): Result<SubjectRead<T>> =
    try {
      Result.success(
        database.withConnection { session ->
          SupporterStudentLinksDao.findLiveForUser(session, viewer, studentId).getOrThrow()
            ?: return@withConnection SubjectRead.NotFound
          SubjectRead.Found(read(session, studentId))
        },
      )
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Result.failure(e)
    }
}
