package ed.unicoach.web

import ed.unicoach.coaching.aid.FafsaDeadline
import ed.unicoach.coaching.aid.FafsaDeadlineWindow
import ed.unicoach.coaching.aid.FafsaDeadlines
import ed.unicoach.coaching.aid.FafsaUnreadableAgencies
import ed.unicoach.coaching.aid.MissingJurisdiction
import ed.unicoach.coaching.aid.NoDeadlinesReason
import ed.unicoach.coaching.aid.StateDeadlines
import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.models.AidDeadlineEvent
import ed.unicoach.db.models.ArchivedCitation
import ed.unicoach.db.models.DeadlineJurisdiction
import ed.unicoach.db.models.PublishedDeadline
import ed.unicoach.db.models.SourceUrlStatus
import ed.unicoach.web.fafsa.FafsaDeadlineSource
import java.time.LocalDate

/**
 * A hand-written fake [FafsaDeadlineSource] (a real class, not a mock), so the
 * `/fafsa` page's rendering tests need neither a seeded corpus nor a driven
 * clock.
 *
 * There are FOUR answers a family can be served — the resolved one and the
 * service's three refusal arms — plus the read that faults. Every one of them
 * is `FafsaDeadlineService`'s own decision, proven against real Postgres in its
 * own suite; re-driving those decisions through a page test would assert the
 * same rules twice, in the worse place. What the PAGE owes is that it renders
 * each answer honestly, and that is what a hand-built value lets a test ask.
 */
class FakeFafsaDeadlineSource(
  private val answer: Result<FafsaDeadlines> = Result.success(resolvedFixture()),
) : FafsaDeadlineSource {
  override suspend fun get(): Result<FafsaDeadlines> = answer

  companion object {
    /** A source that refuses with [reason], the way the service refuses. */
    fun refusing(reason: NoDeadlinesReason): FakeFafsaDeadlineSource =
      FakeFafsaDeadlineSource(
        Result.success(FafsaDeadlines.NoDeadlines(heldAwardYears = emptyList(), reason = reason)),
      )

    /** A source whose read failed — the branded 503 arm, never a fourth refusal. */
    fun faulting(failure: Throwable): FakeFafsaDeadlineSource = FakeFafsaDeadlineSource(Result.failure(failure))
  }
}

/** The cycle the fixtures below are labelled with, unless a case says otherwise. */
val FIXTURE_AWARD_YEAR: AcademicYear = AcademicYear(2026)

/** The day the fixture rows were read. One value, so an assertion can name the rendered date without a second one. */
val FIXTURE_CHECKED_ON: LocalDate = LocalDate.parse("2026-09-14")

/** The day the fixture answer is "today". */
val FIXTURE_AS_OF: LocalDate = LocalDate.parse("2026-09-25")

/** The day the fixture window expires: a year after the rows were read, which is what a live row carries. */
val FIXTURE_EXPIRES_ON: LocalDate = FIXTURE_CHECKED_ON.plusYears(1)

/**
 * An agency page served over PLAIN HTTP, as four rows of the landed corpus are
 * (`http://collegefortn.org/...`).
 *
 * A FIXTURE THAT CARRIES ONE, because every fixture url here was https and the
 * page's own citation guard demanded https: the suite was green while a served
 * state was being dropped from every table on the real corpus.
 */
val FIXTURE_HTTP_SOURCE_URL: String = "http://aid.example.gov/deadlines"

/** One served row, with every field a case might vary exposed and the rest defaulted. */
fun deadlineFixture(
  program: String = "State Grant",
  event: AidDeadlineEvent = AidDeadlineEvent.FINAL_DEADLINE,
  deadline: PublishedDeadline = PublishedDeadline.OnDate(LocalDate.parse("2027-03-02"), "March 2, 2027, 11:59 p.m."),
  publisher: String = "A State Aid Commission",
  sourceUrl: String = "https://aid.example.gov/deadlines",
  sourceUrlStatus: SourceUrlStatus = SourceUrlStatus.FOUND,
  archiveUrl: String? = null,
  archiveUrlStatus: SourceUrlStatus = SourceUrlStatus.FOUND,
  awardYear: AcademicYear = FIXTURE_AWARD_YEAR,
): FafsaDeadline =
  FafsaDeadline(
    awardYear = awardYear,
    program = program,
    event = event,
    deadline = deadline,
    publisher = publisher,
    sourceUrl = sourceUrl,
    sourceUrlStatus = sourceUrlStatus,
    // The pair is ONE value, so a fixture cannot build the row the store cannot
    // return: an address with no verdict about it.
    archive = archiveUrl?.let { ArchivedCitation(it, archiveUrlStatus) },
    checkedOn = FIXTURE_CHECKED_ON,
  )

/** One served state and its rows. */
fun stateFixture(
  uspsCode: String,
  name: String,
  region: String,
  deadlines: List<FafsaDeadline> = listOf(deadlineFixture()),
): StateDeadlines =
  StateDeadlines(
    jurisdiction = jurisdictionOf(uspsCode),
    name = name,
    region = region,
    deadlines = deadlines,
  )

/** One jurisdiction we hold nothing for, carrying the agency the landed corpus records for it. */
fun missingFixture(
  uspsCode: String,
  name: String,
  region: String,
): MissingJurisdiction {
  val jurisdiction = jurisdictionOf(uspsCode)
  return MissingJurisdiction(
    jurisdiction = jurisdiction,
    name = name,
    region = region,
    agency = FafsaUnreadableAgencies.of(jurisdiction),
  )
}

/** The typed jurisdiction for a USPS code, through the same membership rule a served row goes through. */
fun jurisdictionOf(uspsCode: String): DeadlineJurisdiction.State =
  DeadlineJurisdiction.State.of(uspsCode)
    ?: error("[$uspsCode] is not a published USPS code, so no fixture can carry it")

/** A resolved answer, defaulted to the smallest shape a page test can read. */
fun resolvedFixture(
  federal: List<FafsaDeadline> = listOf(deadlineFixture(program = "Federal student aid", publisher = "Federal Student Aid")),
  states: List<StateDeadlines> = listOf(stateFixture("CA", "California", "Far West")),
  missing: List<MissingJurisdiction> = emptyList(),
  asOf: LocalDate = FIXTURE_AS_OF,
  expiresOn: LocalDate = FIXTURE_EXPIRES_ON,
): FafsaDeadlines.Resolved =
  FafsaDeadlines.Resolved(
    asOf = asOf,
    filingYear = FIXTURE_AWARD_YEAR,
    window =
      FafsaDeadlineWindow(
        cycles = (federal + states.flatMap { it.deadlines }).map { it.awardYear }.distinct().sorted(),
        expiresOn = expiresOn,
      ),
    federal = federal,
    states = states,
    missingJurisdictions = missing,
  )
