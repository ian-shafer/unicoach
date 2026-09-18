package ed.unicoach.coaching.testing

import ed.unicoach.coaching.coachToday
import ed.unicoach.common.models.ValidationError
import ed.unicoach.db.Database
import ed.unicoach.db.dao.AdmissionTestScoreDeclinesDao
import ed.unicoach.db.dao.AdmissionTestScoresDao
import ed.unicoach.db.dao.CorruptPersistedValueException
import ed.unicoach.db.dao.NotFoundException
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.AdmissionTestScore
import ed.unicoach.db.models.AdmissionTestScoreComponent
import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.NewAdmissionTestScore
import ed.unicoach.db.models.PartialDate
import ed.unicoach.db.models.PartialDateColumns
import ed.unicoach.db.models.StudentId
import kotlinx.coroutines.CancellationException
import java.time.InstantSource

/**
 * One live score, resolved to what it actually IS (RFC 207): the number has
 * passed its component's published scale, and the sitting is a [PartialDate].
 *
 * The DAO row carries the raw [AdmissionTestScore]; this type is the boundary
 * at which a corrupt row is REFUSED rather than rendered. The schema's CHECKs
 * already make the malformed case unreachable, so a null here is corpus
 * corruption, surfaced exactly as a corrupt read always is -- named, with the
 * cell -- never a score silently skipped and never a number rendered un-gated.
 */
data class RenderedTestScore(
  val component: AdmissionTestScoreComponent,
  val score: ReportedScore,
  val sitting: PartialDate,
)

/**
 * What the student has told us about her own scores: the derived answer status
 * (brief 0010 D24) and the live scores, newest sitting first.
 */
data class TestScores(
  val status: AnswerStatus,
  val scores: List<RenderedTestScore>,
)

/** One write the tool asked for, parsed: record a score, forget one, or decline the whole question. */
sealed interface TestScoreWrite {
  data class Record(
    val component: AdmissionTestScoreComponent,
    val score: Int,
    val sitting: PartialDate,
  ) : TestScoreWrite

  data class Remove(
    val component: AdmissionTestScoreComponent,
    val sitting: PartialDate,
  ) : TestScoreWrite

  data object Decline : TestScoreWrite
}

sealed interface WriteTestScoresResult {
  data class Success(
    val scores: TestScores,
  ) : WriteTestScoresResult

  /** A write the student may fix and retry: an impossible number, a future sitting, or a remove of nothing. */
  data class Rejected(
    val reason: String,
  ) : WriteTestScoresResult

  data object StudentNotFound : WriteTestScoresResult
}

/**
 * Domain layer over the two score tables (RFC 207), on [MoneyProfileService]'s
 * shape: one [Database]-backed class, `Result<sealed outcome>` per operation,
 * no HTTP/Ktor imports, transaction boundaries owned by this class.
 *
 * The status is NEVER stored (brief 0010 D24): [AnswerStatus.of] derives it
 * from the live refusal's `created_at` and the existence of a live score row,
 * so "answered beside zero rows" is unrepresentable.
 *
 * The clock is an [InstantSource] and not a [java.time.Clock], for the reason
 * [ed.unicoach.coaching.coachToday] states: this service supplies the ZONE, so
 * a type that carries one would advertise a zone it discards. `LocalDate.now`
 * appears nowhere in this file.
 */
class TestScoreService(
  private val database: Database,
  private val clock: InstantSource = InstantSource.system(),
) {
  suspend fun getForStudent(studentId: StudentId): Result<TestScores> =
    try {
      Result.success(database.withConnection { session -> readInSession(session, studentId) })
    } catch (e: CancellationException) {
      // Cancellation is the caller unwinding, not a read that failed
      // (CollegeAdmissionsService's rule).
      throw e
    } catch (e: Exception) {
      Result.failure(e)
    }

  suspend fun write(
    studentId: StudentId,
    write: TestScoreWrite,
  ): Result<WriteTestScoresResult> =
    try {
      Result.success(database.withConnection { session -> writeInSession(session, studentId, write) })
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Result.failure(e)
    }

  /**
   * The whole read on ONE session, delegated to [readTestScoresInSession] --
   * the one home of the derivation, shared with the coach context block.
   */
  internal fun readInSession(
    session: SqlSession,
    studentId: StudentId,
  ): TestScores = readTestScoresInSession(session, studentId)

  /** One write, all on the same session, returning the post-write view on every success arm. */
  internal fun writeInSession(
    session: SqlSession,
    studentId: StudentId,
    write: TestScoreWrite,
  ): WriteTestScoresResult =
    when (write) {
      is TestScoreWrite.Record -> {
        val rejected = rejectRecord(write)
        if (rejected != null) return WriteTestScoresResult.Rejected(rejected)
        // A mind changed: recording after a decline withdraws the refusal in the
        // same transaction. No live refusal is the ordinary case and is not an
        // error; any other failure is a fault and must not be swallowed.
        val withdrawn = AdmissionTestScoreDeclinesDao.withdrawLive(session, studentId)
        if (withdrawn.isFailure && withdrawn.exceptionOrNull() !is NotFoundException) {
          throw withdrawn.exceptionOrNull() ?: RuntimeException("decline withdrawal failed")
        }
        val upsert =
          AdmissionTestScoresDao.upsertForStudent(
            session,
            NewAdmissionTestScore(
              studentId = studentId,
              test = write.component.test,
              component = write.component,
              score = write.score,
              sitting = write.sitting,
            ),
          )
        if (upsert.isFailure) return mapWriteFailure(upsert.exceptionOrNull())
        WriteTestScoresResult.Success(readInSession(session, studentId))
      }

      is TestScoreWrite.Remove -> {
        val removed = AdmissionTestScoresDao.removeForStudent(session, studentId, write.component, write.sitting)
        if (removed.isFailure) {
          val e = removed.exceptionOrNull()
          if (e is NotFoundException) {
            return WriteTestScoresResult.Rejected(
              "no stored score matches ${write.component.label} sat ${write.sitting.toIso()}",
            )
          }
          return mapWriteFailure(e)
        }
        WriteTestScoresResult.Success(readInSession(session, studentId))
      }

      TestScoreWrite.Decline -> {
        val inserted = AdmissionTestScoreDeclinesDao.insertDecline(session, studentId)
        if (inserted.isFailure) return mapWriteFailure(inserted.exceptionOrNull())
        // A refusal wins over rows (D24): any live scores stay hers to use, but
        // the derived status is DECLINED and the coach does not name them.
        WriteTestScoresResult.Success(readInSession(session, studentId))
      }
    }

  /** Why a record call must not be written, or null when it may. */
  private fun rejectRecord(write: TestScoreWrite.Record): String? {
    if (ReportedScore.of(write.component, write.score) == null) {
      return "${write.score} is not a possible ${write.component.label} score " +
        "(${write.component.publishedScale.first}-${write.component.publishedScale.last}" +
        (if (write.component.step != 1) ", in steps of ${write.component.step}" else "") + ")"
    }
    // A sitting whose period START is in the future cannot have happened. A
    // year-precision "2026" stores 2026-01-01 and is therefore accepted
    // mid-year -- we do not know the month, and refusing it would throw away a
    // true answer.
    if (PartialDateColumns.periodStart(write.sitting) > clock.coachToday()) {
      return "the sitting is in the future (${write.sitting.toIso()})"
    }
    return null
  }

  /** The one failure arm that is an expected outcome rather than a fault: the owning student vanished. */
  private fun mapWriteFailure(e: Throwable?): WriteTestScoresResult =
    if (e is NotFoundException) WriteTestScoresResult.StudentNotFound else throw (e ?: RuntimeException("write failed"))
}

/**
 * The whole read of the two score tables on ONE session: the live refusal, the
 * live scores, and the derived status (brief 0010 D24). It is the one home of
 * that derivation -- both [TestScoreService.readInSession] and the coach
 * context block in `CoachingService` call it, so a third caller cannot decide
 * the status differently.
 *
 * A number off its published scale is corpus corruption and is refused, never
 * skipped: the schema's CHECK already makes it unreachable, so a null here is
 * a dropped CHECK, surfaced as a located [CorruptPersistedValueException].
 */
fun readTestScoresInSession(
  session: SqlSession,
  studentId: StudentId,
): TestScores {
  val decline =
    AdmissionTestScoreDeclinesDao.findLiveByStudent(session, studentId).getOrElse { e ->
      if (e is NotFoundException) null else throw e
    }
  val rows = AdmissionTestScoresDao.listLiveByStudent(session, studentId).getOrThrow()
  val rendered =
    rows.map { row ->
      val reported =
        ReportedScore.of(row.component, row.score)
          ?: throw CorruptPersistedValueException(
            row.score.toString(),
            ValidationError.InvalidFormat(expected = "a ${row.component.value} score in its published scale"),
            location = "admission_test_scores.score (row ${row.id.value})",
          )
      RenderedTestScore(component = row.component, score = reported, sitting = row.sitting)
    }
  return TestScores(
    status = AnswerStatus.of(declinedAt = decline?.createdAt, hasValue = rendered.isNotEmpty()),
    scores = rendered,
  )
}
