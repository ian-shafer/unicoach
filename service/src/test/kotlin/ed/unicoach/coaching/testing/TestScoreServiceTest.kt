package ed.unicoach.coaching.testing

import ed.unicoach.coaching.CoachingTestDb
import ed.unicoach.db.models.AdmissionTestScoreComponent
import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.PartialDate
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.InstantSource
import java.time.LocalDate
import java.time.Month
import java.time.Year
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The write path and the derived status (RFC 207), asserted against the
 * database: record -> ANSWERED, decline -> DECLINED, a record over a decline is
 * a mind changed, a decline over rows wins (D24), and a remove of the only
 * score returns the answer to UNANSWERED. The clock is pinned through an
 * [InstantSource], and no test calls `LocalDate.now`.
 */
class TestScoreServiceTest {
  private val today = Instant.parse("2026-09-14T15:00:00Z")

  private fun service(): TestScoreService = TestScoreService(CoachingTestDb.database, InstantSource.fixed(today))

  private fun sitting(
    year: Int,
    month: Month,
    day: Int,
  ): PartialDate = PartialDate.FullDate(LocalDate.of(year, month, day))

  @BeforeEach
  fun resetDatabase() {
    CoachingTestDb.connection.autoCommit = true
    CoachingTestDb.truncate("students", "users", "admission_test_scores", "admission_test_score_declines")
  }

  private suspend fun record(
    service: TestScoreService,
    studentId: ed.unicoach.db.models.StudentId,
    component: AdmissionTestScoreComponent = AdmissionTestScoreComponent.SAT_TOTAL,
    score: Int = 1340,
    sitting: PartialDate = PartialDate.YearAndMonth(Year.of(2026), Month.MARCH),
  ) = service.write(studentId, TestScoreWrite.Record(component, score, sitting))

  @Test
  fun `recording a score derives answered and echoes it`() =
    runBlocking {
      val student = CoachingTestDb.createStudent("scores")
      val write = record(service(), student).getOrThrow()
      assertIs<WriteTestScoresResult.Success>(write)
      assertEquals(AnswerStatus.ANSWERED, write.scores.status)
      assertEquals(1, write.scores.scores.size)
      assertEquals(
        1340,
        write.scores.scores
          .single()
          .score.score,
      )

      val read = service().getForStudent(student).getOrThrow()
      assertEquals(AnswerStatus.ANSWERED, read.status)
    }

  @Test
  fun `declining derives declined with no scores`() =
    runBlocking {
      val student = CoachingTestDb.createStudent("scores")
      val write = service().write(student, TestScoreWrite.Decline).getOrThrow()
      assertIs<WriteTestScoresResult.Success>(write)
      assertEquals(AnswerStatus.DECLINED, write.scores.status)
      assertTrue(write.scores.scores.isEmpty())
    }

  @Test
  fun `recording after a decline is a mind changed and withdraws the refusal`() =
    runBlocking {
      val student = CoachingTestDb.createStudent("scores")
      val svc = service()
      svc.write(student, TestScoreWrite.Decline).getOrThrow()
      val after = record(svc, student).getOrThrow()
      assertIs<WriteTestScoresResult.Success>(after)
      assertEquals(AnswerStatus.ANSWERED, after.scores.status, "recording over a decline is a mind changed, not a contradiction")
    }

  @Test
  fun `a refusal wins over rows`() =
    runBlocking {
      val student = CoachingTestDb.createStudent("scores")
      val svc = service()
      record(svc, student).getOrThrow()
      val declined = svc.write(student, TestScoreWrite.Decline).getOrThrow()
      assertIs<WriteTestScoresResult.Success>(declined)
      assertEquals(AnswerStatus.DECLINED, declined.scores.status, "D24: a refusal wins, even beside live rows")
      assertEquals(1, declined.scores.scores.size, "the rows remain hers; they are not erased by a decline")
    }

  @Test
  fun `removing the only score returns the answer to unanswered`() =
    runBlocking {
      val student = CoachingTestDb.createStudent("scores")
      val svc = service()
      record(svc, student).getOrThrow()
      val removed =
        svc
          .write(
            student,
            TestScoreWrite.Remove(AdmissionTestScoreComponent.SAT_TOTAL, PartialDate.YearAndMonth(Year.of(2026), Month.MARCH)),
          ).getOrThrow()
      assertIs<WriteTestScoresResult.Success>(removed)
      assertEquals(AnswerStatus.UNANSWERED, removed.scores.status)
      assertTrue(removed.scores.scores.isEmpty())
    }

  @Test
  fun `removing a score that is not there is rejected, not a silent success`() =
    runBlocking {
      val student = CoachingTestDb.createStudent("scores")
      val out =
        service()
          .write(
            student,
            TestScoreWrite.Remove(AdmissionTestScoreComponent.SAT_TOTAL, PartialDate.YearAndMonth(Year.of(2026), Month.MARCH)),
          ).getOrThrow()
      assertIs<WriteTestScoresResult.Rejected>(out)
      assertTrue(out.reason.contains("no stored score"))
    }

  @Test
  fun `a sitting whose period start is in the future is rejected`() =
    runBlocking {
      val student = CoachingTestDb.createStudent("scores")
      val out = record(service(), student, sitting = PartialDate.FullDate(LocalDate.of(2026, 10, 3))).getOrThrow()
      assertIs<WriteTestScoresResult.Rejected>(out)
      assertTrue(out.reason.contains("future"))
    }

  @Test
  fun `a year-precision sitting is accepted mid-year because the month is not known`() =
    runBlocking {
      val student = CoachingTestDb.createStudent("scores")
      val out = record(service(), student, sitting = PartialDate.YearOnly(Year.of(2026))).getOrThrow()
      assertIs<WriteTestScoresResult.Success>(out)
      assertEquals(AnswerStatus.ANSWERED, out.scores.status)
    }

  @Test
  fun `a number off the published scale is rejected`() =
    runBlocking {
      val student = CoachingTestDb.createStudent("scores")
      val out = record(service(), student, score = 1337).getOrThrow()
      assertIs<WriteTestScoresResult.Rejected>(out)
      assertTrue(out.reason.contains("not a possible SAT total score"))
    }
}
