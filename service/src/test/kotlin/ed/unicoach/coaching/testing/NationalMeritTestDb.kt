package ed.unicoach.coaching.testing

import ed.unicoach.coaching.CoachingTestDb
import ed.unicoach.coaching.admissions.AdmissionsTestDb
import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.dao.NationalMeritFixture
import ed.unicoach.db.dao.StudentRowFixture
import ed.unicoach.db.models.AlternateEntry
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CycleWindow
import ed.unicoach.db.models.NationalMeritAwardKind
import ed.unicoach.db.models.NationalMeritFunnel
import ed.unicoach.db.models.NewNationalMeritAward
import ed.unicoach.db.models.NewNationalMeritCycle
import ed.unicoach.db.models.QualifyingStanding
import ed.unicoach.db.models.ResidencyBasis
import ed.unicoach.db.models.StudentId
import java.time.Instant
import java.time.InstantSource
import java.time.LocalDate

/**
 * The National Merit suites' own fixture (RFC 243): the cycles and awards these
 * tests choose, a student whose CLASS is the subject, and a service and a tool
 * with their clocks pinned.
 *
 * ONE HOME, on [AutomaticAwardTestDb]'s rule, because two suites need it --
 * [NationalMeritServiceTest] and [NationalMeritChatToolTest] -- and a seeder
 * inside one of them becomes that suite's private thing.
 *
 * IT NEVER TOUCHES COMPETITION YEARS 2028 OR 2029, and that is not tidiness.
 * Those two are the COMMITTED corpus, seeded by migration (Route A), and
 * `NationalMeritCycleSeedTest` in `:db` asserts the rows the migration put there
 * -- from the database, not from a file. An upsert here on either year would
 * overwrite a committed row with a fixture's, and the failure would surface in
 * another module's suite. So every cycle this fixture writes is a year the corpus
 * does not hold ([FIXTURE_CYCLE_YEAR] and its neighbours), the committed rows are
 * left standing, and nothing here deletes a cycle.
 *
 * THE CONSEQUENCE IS WORTH STATING: the committed 2028 row publishes a funnel, so
 * the base rate is read from whichever LIVE year publishes the most recent one --
 * which is why [FIXTURE_CYCLE_YEAR] carries a funnel and is a later competition
 * than 2028. A suite asserting the base-rate year asserts THIS year, and the
 * assertion holds whatever the committed corpus is re-read to say.
 *
 * EVERY ROW GOES THROUGH THE REAL WRITE PATH -- [NationalMeritFixture]'s builders
 * over the two DAOs' own upserts -- so a fixture cannot write a row the read path
 * could not have read, and every CHECK in the schema applies to this suite exactly
 * as it applies to the ingest loader.
 */
internal object NationalMeritTestDb {
  val sqlSession get() = AdmissionsTestDb.sqlSession

  /**
   * The real money-profile service, for the tool's constructor.
   *
   * IT IS NEVER THE THING UNDER TEST HERE: every suite overrides the tool's
   * `readProfile` seam, which is the door the two landed residency tools already
   * take, so this exists so that nothing has to pass a null service to reach it.
   */
  val moneyProfileService: ed.unicoach.coaching.moneyprofile.MoneyProfileService by lazy {
    ed.unicoach.coaching.moneyprofile
      .MoneyProfileService(AdmissionsTestDb.database)
  }

  /**
   * Truncates what these suites touch, each reset NAMED by its caller rather than
   * hidden inside a `seed` verb ([NationalMeritFixture]'s own stated rule).
   *
   * The AWARDS are emptied and the CYCLES are not. Awards are Route B -- absent
   * from every test database until an ingest -- so this suite owns every row of
   * them; cycles are Route A and belong to the migration (see this object's KDoc).
   */
  fun reset() {
    // The shared connection, in autocommit. Every write below goes through it
    // while the READ under test opens its own connection from the pool, so a
    // suite that inherited an open transaction would seed rows the service could
    // not see (the `TestScoreServiceTest` precedent).
    CoachingTestDb.connection.autoCommit = true
    AdmissionsTestDb.reset()
    // `residency_basis` is a FOREIGN KEY into the money vocabulary (RFC 158 D4),
    // so a non-empty `residency_bases` is a WRITE PRECONDITION for this corpus
    // and not tidiness -- and another suite on this shared database truncates it.
    ed.unicoach.db.dao.MoneyVocabularyFixture
      .seed(sqlSession)
    NationalMeritFixture.deleteAllAwards(sqlSession)
    deleteFixtureCycles()
  }

  /**
   * Deletes the cycles THIS fixture writes, by year, and no others.
   *
   * A DELETE BY YEAR AND NEVER A TRUNCATE: the committed 2028 and 2029 rows are
   * another module's subject (see the class KDoc), and a truncate here would take
   * them with it. Listing the years this fixture can write is what keeps the two
   * corpora apart.
   */
  fun deleteFixtureCycles() {
    CoachingTestDb.connection
      .prepareStatement("DELETE FROM national_merit_cycles WHERE competition_year = ANY (?)")
      .use { stmt ->
        stmt.setArray(1, CoachingTestDb.connection.createArrayOf("integer", FIXTURE_CYCLE_YEARS.toTypedArray()))
        stmt.executeUpdate()
      }
  }

  /** A student whose stored class year is [graduationYear] -- the year of school IS the subject of these suites. */
  fun createStudent(graduationYear: Int): StudentId {
    val student = AdmissionsTestDb.createStudent()
    StudentRowFixture.updateGraduation(
      CoachingTestDb.connection,
      student.value,
      StudentRowFixture.graduationYear(graduationYear),
    )
    return student
  }

  /**
   * A listed college in one call, WITH ITS OWN STATE, which
   * [AdmissionsTestDb.seedListedCollege] does not forward.
   *
   * The state is not decoration here: it is one of the two facts the residency
   * comparison is made of ([ResidencyScope]), and Auburn -- an Alabama school
   * paying Alabama residents only -- is the case the comparison exists for.
   */
  fun seedListedCollege(
    student: StudentId,
    name: String,
    state: String = "AL",
  ): CollegeId = AdmissionsTestDb.seedCollege(name = name, state = state).also { AdmissionsTestDb.addToCollegeList(student, it) }

  /**
   * THE DATED CYCLE these suites read: [FIXTURE_CYCLE_YEAR], its window in the
   * October two calendar years before it, an alternate-entry deadline the
   * following April, and a full funnel.
   *
   * Shaped like the real 2028 cycle and belonging to a year the corpus does not
   * hold, so every arm is exercised against the real schema without touching a
   * committed row.
   */
  fun datedCycle(
    competitionYear: Int = FIXTURE_CYCLE_YEAR,
    opensOn: LocalDate = WINDOW_OPENS_ON,
    closesOn: LocalDate = WINDOW_CLOSES_ON,
    alternateEntry: AlternateEntry? = datedAlternateEntry(),
    funnel: NationalMeritFunnel = NationalMeritFixture.funnel(),
  ): NewNationalMeritCycle =
    NationalMeritFixture.cycle(
      competitionYear = competitionYear,
      window =
        CycleWindow.Dated(
          opensOn = opensOn,
          closesOn = closesOn,
          asPublished = WINDOW_AS_PUBLISHED,
        ),
      alternateEntry = alternateEntry,
      funnel = funnel,
      checkedOn = CYCLE_CHECKED_ON,
      expiresOn = CYCLE_EXPIRES_ON,
    )

  /** The letter route as its publisher DATES it: a deadline, a contact and the words all three. */
  fun datedAlternateEntry(deadline: LocalDate = ALTERNATE_ENTRY_DEADLINE): AlternateEntry =
    AlternateEntry(
      deadline = deadline,
      contact = ALTERNATE_ENTRY_CONTACT,
      asPublished = DATED_ALTERNATE_ENTRY_AS_PUBLISHED,
    )

  /**
   * The letter route its publisher DESCRIBES and does not date -- the third
   * shape `national_merit_cycles_alternate_entry_trio_check` admits, and the one
   * a closed-cycle answer would swallow.
   */
  fun undatedAlternateEntry(): AlternateEntry =
    AlternateEntry(
      deadline = null,
      contact = ALTERNATE_ENTRY_CONTACT,
      asPublished = UNDATED_ALTERNATE_ENTRY_AS_PUBLISHED,
    )

  /**
   * THE DESCRIBED CYCLE: a later competition its publisher has named in words and
   * has NOT dated, and that publishes NO funnel count at all.
   *
   * Both halves are measured rather than invented -- the committed 2029 row is
   * exactly this shape -- and both matter. An undated window is the normal state
   * of a cycle more than a year out, and a cycle with no funnel is why the base
   * rate has to be taken from another year and NAMED.
   */
  fun describedCycle(competitionYear: Int = LATER_CYCLE_YEAR): NewNationalMeritCycle =
    NationalMeritFixture.cycle(
      competitionYear = competitionYear,
      window = CycleWindow.Described(asPublished = WINDOW_AS_PUBLISHED),
      alternateEntry = null,
      funnel = EMPTY_FUNNEL,
      checkedOn = CYCLE_CHECKED_ON,
      expiresOn = CYCLE_EXPIRES_ON,
    )

  /** Writes one cycle through the DAO's own upsert. */
  fun seedCycle(row: NewNationalMeritCycle) = NationalMeritFixture.seedCycle(sqlSession, row)

  /**
   * One award row on this suite's calendar: [AWARD_ENTRY_YEAR] entry, read on
   * [AWARD_CHECKED_ON] and believed through [AWARD_EXPIRES_ON].
   *
   * It wraps [NationalMeritFixture.award] rather than replacing it, and the
   * deadlines are the reason: `college_national_merit_awards_deadlines_in_cycle_check`
   * bounds both of them by the ENTRY year, so a suite that moved the entry year
   * on the raw builder alone would write a row the database refuses -- and the
   * refusal would read as a broken test rather than as the rule it is.
   */
  fun award(
    collegeId: CollegeId,
    residencyBasis: ResidencyBasis = ResidencyBasis.NOT_APPLICABLE,
    awardKind: NationalMeritAwardKind = NationalMeritAwardKind.AUTOMATIC,
    qualifyingStanding: QualifyingStanding? = QualifyingStanding.FINALIST,
    awardPerYearUsd: Int? = AWARD_PER_YEAR_USD,
    awardTotalUsd: Int? = AWARD_TOTAL_USD,
    awardYears: Int? = 4,
    awardAsPublished: String? = AWARD_AS_PUBLISHED,
    publisher: String = DEFAULT_PUBLISHER,
    checkedOn: LocalDate = AWARD_CHECKED_ON,
    expiresOn: LocalDate = AWARD_EXPIRES_ON,
  ): NewNationalMeritAward =
    NationalMeritFixture.award(
      collegeId = collegeId,
      entryYear = AcademicYear(AWARD_ENTRY_YEAR),
      residencyBasis = residencyBasis,
      awardKind = awardKind,
      qualifyingStanding = qualifyingStanding,
      awardPerYearUsd = awardPerYearUsd,
      awardTotalUsd = awardTotalUsd,
      awardYears = awardYears,
      awardAsPublished = awardAsPublished,
      firstChoiceDeadline = FIRST_CHOICE_DEADLINE,
      applyDeadline = APPLY_DEADLINE,
      publisher = publisher,
      checkedOn = checkedOn,
      expiresOn = expiresOn,
    )

  /**
   * THE WORDS-ONLY PACKAGE: a school that publishes what it pays in a sentence
   * and no figure at all.
   *
   * HALF THE SEEDED SCHOOLS ARE THIS SHAPE -- measured during curation, eight of
   * sixteen -- and they are the TOP awards. A suite whose every paying school
   * carried an integer would let a renderer that ranks, totals or compares money
   * pass every test here.
   */
  fun wordsOnlyAward(
    collegeId: CollegeId,
    publisher: String = WORDS_ONLY_PUBLISHER,
  ): NewNationalMeritAward =
    award(
      collegeId = collegeId,
      awardPerYearUsd = null,
      awardTotalUsd = null,
      awardYears = null,
      awardAsPublished = WORDS_ONLY_AS_PUBLISHED,
      publisher = publisher,
    )

  /** The VERIFIED ABSENCE row: we read this school's page and it publishes no National Merit award at all. */
  fun absence(
    collegeId: CollegeId,
    publisher: String = ABSENCE_PUBLISHER,
  ): NewNationalMeritAward =
    NationalMeritFixture.absence(
      collegeId = collegeId,
      entryYear = AcademicYear(AWARD_ENTRY_YEAR),
      absenceAsPublished = ABSENCE_AS_PUBLISHED,
      publisher = publisher,
      checkedOn = AWARD_CHECKED_ON,
      expiresOn = AWARD_EXPIRES_ON,
    )

  /** Writes one award row through the DAO's own upsert. */
  fun seedAward(row: NewNationalMeritAward) = NationalMeritFixture.seedAward(sqlSession, row)

  /**
   * The service with today pinned. The pinned value is an INSTANT and carries no
   * zone at all (the [AutomaticAwardTestDb] precedent): the service applies
   * `COACH_ZONE` itself, which is the seam RFC 186's first attempt got wrong.
   */
  fun serviceAt(instant: String): NationalMeritService =
    NationalMeritService(AdmissionsTestDb.database, InstantSource.fixed(Instant.parse(instant)))

  /** The years this fixture is allowed to write, and the ONLY ones [deleteFixtureCycles] removes. */
  val FIXTURE_CYCLE_YEARS: List<Int> get() = listOf(FIXTURE_CYCLE_YEAR, LATER_CYCLE_YEAR)

  /**
   * The dated cycle these suites read: the 2030 competition, whose one qualifying
   * sitting is the October of 2028.
   *
   * LATER THAN THE COMMITTED CORPUS's cycles ON PURPOSE (see the class KDoc): it
   * is the most recent year publishing a funnel, so the base rate these suites
   * assert is this fixture's and never the committed corpus's.
   */
  const val FIXTURE_CYCLE_YEAR = 2030

  /** The cycle after it, described and undated -- the sophomore's answer. */
  const val LATER_CYCLE_YEAR = 2031

  /** The window of [FIXTURE_CYCLE_YEAR]: October of the junior year, dated, inclusive at both ends. */
  val WINDOW_OPENS_ON: LocalDate = LocalDate.of(2028, 10, 1)
  val WINDOW_CLOSES_ON: LocalDate = LocalDate.of(2028, 10, 30)

  /** The letter route's own deadline, the April after the sitting. */
  val ALTERNATE_ENTRY_DEADLINE: LocalDate = LocalDate.of(2029, 4, 1)

  const val ALTERNATE_ENTRY_CONTACT = "ae@example.invalid"

  /** The dated route's own words, which name the day the publisher printed. */
  const val DATED_ALTERNATE_ENTRY_AS_PUBLISHED =
    "A student who misses the sitting writes to us, received or postmarked by April 1."

  /** And the undated route's: a door the publisher describes and gives no day for. */
  const val UNDATED_ALTERNATE_ENTRY_AS_PUBLISHED =
    "A student who misses the sitting because of illness or an emergency should write to us as soon as possible."

  const val WINDOW_AS_PUBLISHED = "The test is given in school in October of the junior year."

  /** A funnel with no stage published at all -- the shape of a cycle its sponsor has not run. */
  val EMPTY_FUNNEL: NationalMeritFunnel =
    NationalMeritFixture.funnel(
      testTakers = null,
      metEntryRequirements = null,
      highScorersScreened = null,
      commended = null,
      semifinalists = null,
      finalists = null,
      winners = null,
      asPublished = "Figures for this competition have not been published.",
    )

  val CYCLE_CHECKED_ON: LocalDate = LocalDate.of(2028, 9, 1)
  val CYCLE_EXPIRES_ON: LocalDate = LocalDate.of(2029, 7, 31)

  /** The entry year the award rows are published against, and the deadlines that CHECK bounds by it. */
  const val AWARD_ENTRY_YEAR = 2030
  val FIRST_CHOICE_DEADLINE: LocalDate = LocalDate.of(2030, 5, 1)
  val APPLY_DEADLINE: LocalDate = LocalDate.of(2029, 12, 1)
  val AWARD_CHECKED_ON: LocalDate = LocalDate.of(2028, 9, 1)
  val AWARD_EXPIRES_ON: LocalDate = LocalDate.of(2029, 7, 31)

  /** What the priced school publishes -- a figure a year AND a four-year total, the shape Oklahoma State's page takes. */
  const val AWARD_PER_YEAR_USD = 20_000
  const val AWARD_TOTAL_USD = 80_000
  const val AWARD_AS_PUBLISHED = "Finalists receive an award of $20,000 a year for four years."

  const val DEFAULT_PUBLISHER = "Example State University"
  const val WORDS_ONLY_PUBLISHER = "Example Words University"
  const val ABSENCE_PUBLISHER = "Example Silent University"

  /** A package priced in the publisher's own words and in no number at all. */
  const val WORDS_ONLY_AS_PUBLISHED = "Finalists receive the full cost of tuition and required fees, for four years."

  /** The sentence that PROVES an absence -- which is a different answer from a page that never mentions the award. */
  const val ABSENCE_AS_PUBLISHED = "We no longer participate in offering college-sponsored National Merit scholarships."

  /**
   * Noon Eastern on the day this corpus's rows were read, in the September before
   * the window opens: the junior year is running and the window is still ahead.
   */
  const val NOON_EASTERN_2028_09_01 = "2028-09-01T16:00:00Z"

  /** Noon Eastern in the middle of October, inside a dated window and past the opening of an undated one. */
  const val NOON_EASTERN_2028_10_15 = "2028-10-15T16:00:00Z"

  /** Noon Eastern on the day the window opens, and on the day it closes -- both inclusive. */
  const val NOON_EASTERN_2028_10_01 = "2028-10-01T16:00:00Z"
  const val NOON_EASTERN_2028_10_30 = "2028-10-30T16:00:00Z"

  /** The morning after it closes, which is the first day the letter route is the answer. */
  const val NOON_EASTERN_2028_10_31 = "2028-10-31T16:00:00Z"

  /** The letter route's own deadline, and the morning after it. */
  const val NOON_EASTERN_2029_04_01 = "2029-04-01T16:00:00Z"
  const val NOON_EASTERN_2029_04_02 = "2029-04-02T16:00:00Z"

  /** The day the award rows expire, and the morning after -- the LAST day the corpus is believed and the first it is not. */
  const val NOON_EASTERN_2029_07_31 = "2029-07-31T16:00:00Z"
  const val NOON_EASTERN_2029_08_01 = "2029-08-01T16:00:00Z"

  /**
   * Noon Eastern two years on, in the September after [PAST_GRADUATION_CLASS]
   * graduated: the clock the two out-of-range years of school are read on.
   */
  const val NOON_EASTERN_2030_09_01 = "2030-09-01T16:00:00Z"

  /**
   * The class that is a JUNIOR on every clock from [NOON_EASTERN_2028_09_01]
   * through the following June: the academic year ends in 2029, and grade 11 is
   * one year short of graduating.
   */
  const val JUNIOR_CLASS = FIXTURE_CYCLE_YEAR

  /** A sophomore on those same clocks -- her own cycle is the described one. */
  const val SOPHOMORE_CLASS = LATER_CYCLE_YEAR

  /**
   * A SENIOR two years later, on [NOON_EASTERN_2030_09_01]: the academic year
   * ends in 2031, so the class of 2031 is in grade 12 and her own qualifying
   * sitting -- the described cycle's -- is behind her.
   *
   * A FIXTURE YEAR AND NOT A COMMITTED ONE. A senior on the September 2028 clock
   * would be the class of 2029, which is a committed row this fixture must not
   * write; moving the CLOCK instead keeps both out-of-range students on cycles
   * this suite owns.
   */
  const val SENIOR_CLASS = LATER_CYCLE_YEAR

  /** Past graduation on that same later clock: the class whose June has gone, whose cycle is the dated one. */
  const val PAST_GRADUATION_CLASS = FIXTURE_CYCLE_YEAR

  /** A class no cycle row exists for, in either corpus -- the `not_curated` arm's own student. */
  const val UNCURATED_CLASS = 2035
}
