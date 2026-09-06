package ed.unicoach.coaching.aid

import ed.unicoach.coaching.CoachingTestDb
import ed.unicoach.coaching.moneyprofile.FieldUpdate
import ed.unicoach.coaching.moneyprofile.MoneyProfileService
import ed.unicoach.coaching.moneyprofile.MoneyProfileUpdate
import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.DependencyStatus
import ed.unicoach.db.models.StudentId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The chat-free half of RFC 159: the composition rules over the
 * migration-seeded `policy_parameters` store. The seed itself is the fixture
 * -- `bin/test` re-migrates before the suite runs, and the one test that
 * writes the table rolls its transaction back -- so the launch honesty
 * property (Pell current, loans a year behind) is proven against the real
 * rows.
 */
class FederalAidPolicyServiceTest {
  @BeforeEach
  fun resetDatabase() {
    CoachingTestDb.truncate("money_profiles", "students", "users")
  }

  /** A clock inside award year 2026-27 (July 1, 2026 - June 30, 2027), the run's real season. */
  private val inAy2026 = Clock.fixed(Instant.parse("2026-09-03T12:00:00Z"), ZoneOffset.UTC)

  private val service = FederalAidPolicyService(CoachingTestDb.database, inAy2026)

  private fun createStudent(): StudentId = CoachingTestDb.createStudent("aid")

  private fun read(student: StudentId): FederalAidPolicy = runBlocking { service.getForStudent(student).getOrThrow() }

  @Test
  fun `pell resolves to the current award year and the loan topics to the prior one, from the real seed`() {
    val policy = read(createStudent())

    assertEquals("2026-27", policy.currentAwardYear.label)

    val pell = assertNotNull(policy.pell)
    assertEquals("2026-27", pell.awardYear.label)
    assertEquals(AwardYearCurrency.Current, pell.currency, "a current-year topic carries no prior-year sentence")

    // Vol 8 of the 2026-27 Handbook is unpublished, so the loan topics are a
    // year behind AND say so -- the stale-award-year criterion exercised by
    // real data on day one (RFC 159 D-G). The sentence itself is coach copy
    // rendered at the tool edge; here the FACT is asserted.
    val undergrad = assertNotNull(policy.undergradLoans)
    assertEquals("2025-26", undergrad.awardYear.label)
    assertIs<AwardYearCurrency.PriorYear>(undergrad.currency)

    val grad = assertNotNull(policy.gradLoans)
    assertEquals("2025-26", grad.awardYear.label)
    assertIs<AwardYearCurrency.PriorYear>(grad.currency)
  }

  @Test
  fun `the seeded figures arrive grouped and complete, exactly as verified`() {
    val policy = read(createStudent())

    val pell = assertNotNull(policy.pell)
    assertEquals(7395, pell.maxAwardUsd)
    assertEquals(740, pell.minAwardUsd)
    assertEquals(-1500, pell.saiFloor)
    assertEquals(225, pell.dependentSingleParentPct)
    assertEquals(175, pell.dependentNonSingleParentPct)
    assertTrue(pell.sources.isNotEmpty() && pell.sources.all { it.url.startsWith("https://fsapartners.ed.gov/") })

    val undergrad = assertNotNull(policy.undergradLoans)
    assertEquals(
      listOf(
        AnnualLoanLimit(YearLevel.FIRST_YEAR, 5500, 3500),
        AnnualLoanLimit(YearLevel.SECOND_YEAR, 6500, 4500),
        AnnualLoanLimit(YearLevel.THIRD_YEAR_AND_BEYOND, 7500, 5500),
      ),
      undergrad.dependent.annual,
    )
    assertEquals(31000, undergrad.dependent.aggregateTotalUsd)
    assertEquals(23000, undergrad.dependent.aggregateSubsidizedMaxUsd)
    assertEquals(
      listOf(
        AnnualLoanLimit(YearLevel.FIRST_YEAR, 9500, 3500),
        AnnualLoanLimit(YearLevel.SECOND_YEAR, 10500, 4500),
        AnnualLoanLimit(YearLevel.THIRD_YEAR_AND_BEYOND, 12500, 5500),
      ),
      undergrad.independent.annual,
    )
    assertEquals(57500, undergrad.independent.aggregateTotalUsd)
    assertEquals(23000, undergrad.independent.aggregateSubsidizedMaxUsd)
    assertEquals(
      listOf(PolicySource("Federal Student Aid Handbook, 2025-2026, Volume 8 Chapter 4", undergrad.sources.single().url)),
      undergrad.sources,
    )

    val grad = assertNotNull(policy.gradLoans)
    assertEquals(20500, grad.annualUnsubsidizedUsd)
    assertEquals(138500, grad.aggregateTotalUsd)
    assertEquals(65500, grad.aggregateSubsidizedMaxUsd)
  }

  @Test
  fun `the July 1 boundary decides the current award year, and a future-published year is never served as current`() {
    // June 30, 2026: still award year 2025-26. The 2026-27 Pell figures are
    // already in the store (GEN-26-01 published in January), but next year's
    // policy is not this year's fact -- pell serves 2025-26 as CURRENT.
    val lastDayOfAy2025 =
      FederalAidPolicyService(
        CoachingTestDb.database,
        Clock.fixed(Instant.parse("2026-06-30T23:59:59Z"), ZoneOffset.UTC),
      )
    assertEquals(AcademicYear(2025), lastDayOfAy2025.currentAwardYear())
    val juneRead = runBlocking { lastDayOfAy2025.getForStudent(createStudent()).getOrThrow() }
    val junePell = assertNotNull(juneRead.pell)
    assertEquals("2025-26", junePell.awardYear.label)
    assertEquals(AwardYearCurrency.Current, junePell.currency)
    val juneLoans = assertNotNull(juneRead.undergradLoans)
    assertEquals(AwardYearCurrency.Current, juneLoans.currency, "in AY 2025-26 the 2025 loan seed IS current")

    // July 1, 2026: award year 2026-27 begins; pell rolls forward, loans fall behind.
    val firstDayOfAy2026 =
      FederalAidPolicyService(
        CoachingTestDb.database,
        Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC),
      )
    assertEquals(AcademicYear(2026), firstDayOfAy2026.currentAwardYear())
    val julyRead = runBlocking { firstDayOfAy2026.getForStudent(createStudent()).getOrThrow() }
    assertEquals("2026-27", assertNotNull(julyRead.pell).awardYear.label)
    assertIs<AwardYearCurrency.PriorYear>(assertNotNull(julyRead.undergradLoans).currency)
  }

  @Test
  fun `a partially seeded newer award year never eclipses the last complete one`() {
    // The realistic sequence: FSA publishes the Pell max/min DCL months before
    // the AVG chapters, so a 2027 seed can hold pell_max_award_usd alone for a
    // while. The Pell topic must keep serving the 2026 complete year -- not
    // resolve to 2027, find 2 of 5 parameters, and vanish. The bogus row is
    // written inside a rolled-back transaction (PolicyParametersDaoTest's
    // pattern), so the migration-seeded table is left exactly as it was.
    val student = createStudent()
    val inAy2027 = FederalAidPolicyService(CoachingTestDb.database, Clock.fixed(Instant.parse("2027-09-03T12:00:00Z"), ZoneOffset.UTC))
    CoachingTestDb.connection.autoCommit = false
    try {
      CoachingTestDb.connection.createStatement().use { stmt ->
        stmt.execute(
          "INSERT INTO policy_parameters (award_year, parameter, value, source_name, source_url) " +
            "VALUES (2027, 'pell_max_award_usd', 7500, 'DCL GEN-27-01', 'https://fsapartners.ed.gov/x')",
        )
      }
      val policy = inAy2027.readInSession(CoachingTestDb.sqlSession, student)
      val pell = assertNotNull(policy.pell, "a partial newer year must fall back, never delete the topic")
      assertEquals("2026-27", pell.awardYear.label, "the latest COMPLETE year serves, not the partial 2027 seed")
      assertEquals(7395, pell.maxAwardUsd)
      assertIs<AwardYearCurrency.PriorYear>(pell.currency, "the fallback year is behind AY 2027-28 and must say so")
    } finally {
      CoachingTestDb.connection.rollback()
      CoachingTestDb.connection.autoCommit = true
    }
  }

  @Test
  fun `the dependency echo is unanswered before any write, and carries the answer or the decline after one`() {
    val student = createStudent()
    assertEquals(DependencyAnswer(AnswerStatus.UNANSWERED, null), read(student).dependency)

    // Through the REAL write path, so the echo is proven against the same
    // rows the update_money_profile tool writes.
    val profiles = MoneyProfileService(CoachingTestDb.database)
    runBlocking {
      profiles.upsert(student, MoneyProfileUpdate(dependency = FieldUpdate.Set(DependencyStatus.DEPENDENT))).getOrThrow()
    }
    assertEquals(DependencyAnswer(AnswerStatus.ANSWERED, DependencyStatus.DEPENDENT), read(student).dependency)

    runBlocking { profiles.upsert(student, MoneyProfileUpdate(dependency = FieldUpdate.Decline)).getOrThrow() }
    assertEquals(
      DependencyAnswer(AnswerStatus.DECLINED, null),
      read(student).dependency,
      "a declined field can never smuggle a stale value to a consumer",
    )
  }

  @Test
  fun `the whole read costs two statements, whatever the store holds`() {
    val student = createStudent()
    val counting = CoachingTestDb.CountingSession()
    service.readInSession(counting, student)
    assertEquals(
      2,
      counting.prepared.size,
      "one statement for the policy store and one for the money profile, never one per parameter " +
        "or per group: [${counting.prepared}]",
    )
  }
}
