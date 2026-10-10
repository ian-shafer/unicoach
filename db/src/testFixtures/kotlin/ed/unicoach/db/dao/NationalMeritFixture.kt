package ed.unicoach.db.dao

import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.models.AlternateEntry
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CommendedCutoffBasis
import ed.unicoach.db.models.CyclePublication
import ed.unicoach.db.models.CycleWindow
import ed.unicoach.db.models.NationalMeritAwardKind
import ed.unicoach.db.models.NationalMeritFunnel
import ed.unicoach.db.models.NewNationalMeritAward
import ed.unicoach.db.models.NewNationalMeritCycle
import ed.unicoach.db.models.NmscOwnAward
import ed.unicoach.db.models.QualifyingStanding
import ed.unicoach.db.models.ResidencyBasis
import ed.unicoach.db.models.SelectionIndexScale
import ed.unicoach.db.models.SemifinalistCutoffBasis
import java.time.LocalDate

/**
 * `national_merit_cycles` and `college_national_merit_awards` rows (RFC 243)
 * for suites that write their own.
 *
 * IT READS NO COMMITTED CORPUS, unlike [AutomaticAwardFixture]. The two corpora
 * this slice commits reach the database by two different routes -- the cycles
 * by a GENERATED seed migration (Route A), the awards by the ingest loader
 * (Route B) -- and each route's own guard reads its own file. A reader here
 * would be a third opinion about what is on disk.
 *
 * EVERY FIGURE BELOW IS A FIXTURE'S, NOT A PUBLISHER'S. They are shaped like
 * the real corpus -- an October window two calendar years before the
 * competition, an April alternate-entry deadline, a funnel that narrows -- so
 * that a row built here is inside every CHECK, and a case that wants a bad row
 * overrides the one field it is about. Nothing here may be quoted as fact: the
 * committed corpus under `db/seed/national-merit/` is the only home for what
 * NMSC and College Board actually publish.
 *
 * `colleges` IS A PRECONDITION for every award write: `college_id` foreign-keys
 * it, so a caller creates its colleges first.
 */
object NationalMeritFixture {
  /**
   * One cycle row, with the defaults a suite does not care about already
   * filled: a dated competition with a complete funnel, an alternate entry
   * route, and two publishers.
   *
   * Every field is overridable because every field is somebody's subject -- a
   * suite about the undated cycle passes [CycleWindow.Described] and stays
   * readable, which is the whole reason this is a builder and not sixteen
   * positional arguments at each call site.
   */
  fun cycle(
    competitionYear: Int = DEFAULT_COMPETITION_YEAR,
    qualifyingGrade: Int = 11,
    window: CycleWindow =
      CycleWindow.Dated(
        opensOn = DEFAULT_WINDOW_OPENS_ON,
        closesOn = DEFAULT_WINDOW_CLOSES_ON,
        asPublished = "The test is given in school in October of the junior year.",
      ),
    alternateEntry: AlternateEntry? =
      AlternateEntry(
        deadline = DEFAULT_ALTERNATE_ENTRY_DEADLINE,
        contact = "ae@example.invalid",
        asPublished = "A student who misses the test writes to us by April 1.",
      ),
    selectionIndex: SelectionIndexScale = SelectionIndexScale(48, 228),
    semifinalistCutoff: SemifinalistCutoffBasis = SemifinalistCutoffBasis.STATE_REPRESENTATIONAL,
    commendedCutoff: CommendedCutoffBasis = CommendedCutoffBasis.NATIONAL,
    cutoffAsPublished: String = "Qualifying scores vary from state to state and from year to year.",
    scoreVisibilityAsPublished: String = "Scores are not reported to colleges.",
    funnel: NationalMeritFunnel = funnel(),
    nmscAward: NmscOwnAward? = NmscOwnAward(2_500, "A single payment of $2,500."),
    sponsorCapAsPublished: String? = "A sponsor college award is capped per year.",
    sponsor: CyclePublication =
      CyclePublication(publisher = DEFAULT_SPONSOR, sourceUrl = DEFAULT_SOURCE_URL, archiveUrl = null),
    administrator: CyclePublication =
      CyclePublication(
        publisher = DEFAULT_ADMINISTRATOR,
        sourceUrl = DEFAULT_ADMINISTRATOR_SOURCE_URL,
        archiveUrl = null,
      ),
    checkedOn: LocalDate = DEFAULT_CHECKED_ON,
    expiresOn: LocalDate = DEFAULT_CYCLE_EXPIRES_ON,
  ): NewNationalMeritCycle =
    NewNationalMeritCycle(
      competitionYear = competitionYear,
      qualifyingGrade = qualifyingGrade,
      window = window,
      alternateEntry = alternateEntry,
      selectionIndex = selectionIndex,
      semifinalistCutoff = semifinalistCutoff,
      commendedCutoff = commendedCutoff,
      cutoffAsPublished = cutoffAsPublished,
      scoreVisibilityAsPublished = scoreVisibilityAsPublished,
      funnel = funnel,
      nmscAward = nmscAward,
      sponsorCapAsPublished = sponsorCapAsPublished,
      sponsor = sponsor,
      administrator = administrator,
      checkedOn = checkedOn,
      expiresOn = expiresOn,
    )

  /** A funnel that narrows at every stage, for a suite that is not about the funnel. */
  fun funnel(
    testTakers: Int? = 3_500_000,
    metEntryRequirements: Int? = 1_200_000,
    highScorersScreened: Int? = 50_000,
    commended: Int? = 34_000,
    semifinalists: Int? = 16_000,
    finalists: Int? = 15_000,
    winners: Int? = 6_700,
    asPublished: String = "About 1.2 million entrants; some 15,000 reach Finalist.",
  ): NationalMeritFunnel =
    NationalMeritFunnel(
      testTakers = testTakers,
      metEntryRequirements = metEntryRequirements,
      highScorersScreened = highScorersScreened,
      commended = commended,
      semifinalists = semifinalists,
      finalists = finalists,
      winners = winners,
      asPublished = asPublished,
    )

  /**
   * One award row, with the defaults a suite does not care about already
   * filled: a school that pays Finalists automatically for Fall 2027 entry,
   * with no residency axis.
   *
   * A `none_published` row is [absence] rather than an override of this, for
   * the reason the schema states in four CHECKs: the two kinds of row share
   * almost no cells, and a builder that could produce a half-crossed one would
   * make the fixture the place those CHECKs are decided.
   */
  fun award(
    collegeId: CollegeId,
    entryYear: AcademicYear = AcademicYear(DEFAULT_ENTRY_YEAR),
    residencyBasis: ResidencyBasis = ResidencyBasis.NOT_APPLICABLE,
    awardKind: NationalMeritAwardKind = NationalMeritAwardKind.AUTOMATIC,
    qualifyingStanding: QualifyingStanding? = QualifyingStanding.FINALIST,
    awardName: String? = DEFAULT_AWARD_NAME,
    awardPerYearUsd: Int? = 20_000,
    awardTotalUsd: Int? = 80_000,
    awardYears: Int? = 4,
    awardAsPublished: String? = "Finalists receive tuition, housing and a stipend.",
    firstChoiceRequired: Boolean? = true,
    firstChoiceDeadline: LocalDate? = DEFAULT_FIRST_CHOICE_DEADLINE,
    firstChoiceAsPublished: String? = "Name us your first choice with NMSC by May 1.",
    applyDeadline: LocalDate? = DEFAULT_APPLY_DEADLINE,
    applyDeadlineAsPublished: String? = "Apply for admission by December 1.",
    publisher: String = DEFAULT_PUBLISHER,
    sourceUrl: String = DEFAULT_AWARD_SOURCE_URL,
    archiveUrl: String? = null,
    checkedOn: LocalDate = DEFAULT_CHECKED_ON,
    expiresOn: LocalDate = DEFAULT_AWARD_EXPIRES_ON,
  ): NewNationalMeritAward =
    NewNationalMeritAward(
      collegeId = collegeId,
      entryYear = entryYear,
      residencyBasis = residencyBasis,
      awardKind = awardKind,
      qualifyingStanding = qualifyingStanding,
      awardName = awardName,
      awardPerYearUsd = awardPerYearUsd,
      awardTotalUsd = awardTotalUsd,
      awardYears = awardYears,
      awardAsPublished = awardAsPublished,
      absenceAsPublished = null,
      firstChoiceRequired = firstChoiceRequired,
      firstChoiceDeadline = firstChoiceDeadline,
      firstChoiceAsPublished = firstChoiceAsPublished,
      applyDeadline = applyDeadline,
      applyDeadlineAsPublished = applyDeadlineAsPublished,
      publisher = publisher,
      sourceUrl = sourceUrl,
      archiveUrl = archiveUrl,
      checkedOn = checkedOn,
      expiresOn = expiresOn,
    )

  /**
   * The VERIFIED ABSENCE row: we read this school's page on [checkedOn] and it
   * publishes no National Merit award at all.
   *
   * Every award-shaped cell is null and cannot be passed, which is what
   * `college_national_merit_awards_none_published_is_empty_check` says in SQL.
   */
  fun absence(
    collegeId: CollegeId,
    entryYear: AcademicYear = AcademicYear(DEFAULT_ENTRY_YEAR),
    residencyBasis: ResidencyBasis = ResidencyBasis.NOT_APPLICABLE,
    absenceAsPublished: String = "We do not offer a National Merit scholarship.",
    publisher: String = DEFAULT_PUBLISHER,
    sourceUrl: String = DEFAULT_AWARD_SOURCE_URL,
    archiveUrl: String? = null,
    checkedOn: LocalDate = DEFAULT_CHECKED_ON,
    expiresOn: LocalDate = DEFAULT_AWARD_EXPIRES_ON,
  ): NewNationalMeritAward =
    NewNationalMeritAward(
      collegeId = collegeId,
      entryYear = entryYear,
      residencyBasis = residencyBasis,
      awardKind = NationalMeritAwardKind.NONE_PUBLISHED,
      qualifyingStanding = null,
      awardName = null,
      awardPerYearUsd = null,
      awardTotalUsd = null,
      awardYears = null,
      awardAsPublished = null,
      absenceAsPublished = absenceAsPublished,
      firstChoiceRequired = null,
      firstChoiceDeadline = null,
      firstChoiceAsPublished = null,
      applyDeadline = null,
      applyDeadlineAsPublished = null,
      publisher = publisher,
      sourceUrl = sourceUrl,
      archiveUrl = archiveUrl,
      checkedOn = checkedOn,
      expiresOn = expiresOn,
    )

  /** Writes one cycle through the DAO's own upsert, so a fixture cannot write a row the read path could not read. */
  fun seedCycle(
    session: SqlSession,
    row: NewNationalMeritCycle,
  ) = NationalMeritCyclesDao.upsert(session, row).getOrThrow()

  /** Writes one award through the DAO's own upsert; see [seedCycle]. */
  fun seedAward(
    session: SqlSession,
    row: NewNationalMeritAward,
  ) = NationalMeritAwardsDao.upsert(session, row).getOrThrow()

  /** Empties the cycles. NAMED and called by the caller, never hidden inside a `seed` verb ([AutomaticAwardFixture]'s rule). */
  fun deleteAllCycles(session: SqlSession) {
    session.prepareStatement("DELETE FROM national_merit_cycles").use { it.executeUpdate() }
  }

  /** Empties the awards; see [deleteAllCycles]. */
  fun deleteAllAwards(session: SqlSession) {
    session.prepareStatement("DELETE FROM college_national_merit_awards").use { it.executeUpdate() }
  }

  /** The competition [cycle] describes by default: the 2028 competition, sat in October 2026. */
  const val DEFAULT_COMPETITION_YEAR = 2028

  /** The entry year [award] describes by default: Fall 2027 entry. */
  const val DEFAULT_ENTRY_YEAR = 2027

  const val DEFAULT_AWARD_NAME = "Example Merit Package"
  const val DEFAULT_PUBLISHER = "Example State University"
  const val DEFAULT_SPONSOR = "Example Merit Corporation"
  const val DEFAULT_ADMINISTRATOR = "Example Test Board"

  /** Pages a fixture row cites. `.invalid` by RFC 2606, so no suite can reach a real publisher. */
  const val DEFAULT_SOURCE_URL = "https://sponsor.example.invalid/national-merit"
  const val DEFAULT_ADMINISTRATOR_SOURCE_URL = "https://administrator.example.invalid/psat-nmsqt"
  const val DEFAULT_AWARD_SOURCE_URL = "https://example.invalid/national-merit-scholarship"

  /** The October window of the 2028 competition, two calendar years before it, as every CHECK requires. */
  val DEFAULT_WINDOW_OPENS_ON: LocalDate = LocalDate.of(2026, 10, 1)
  val DEFAULT_WINDOW_CLOSES_ON: LocalDate = LocalDate.of(2026, 10, 31)
  val DEFAULT_ALTERNATE_ENTRY_DEADLINE: LocalDate = LocalDate.of(2027, 4, 1)

  /** The day these fixture rows were "read". */
  val DEFAULT_CHECKED_ON: LocalDate = LocalDate.of(2026, 9, 25)

  /** July 31 after the cycle's last actionable day -- the shared cycle-expiry rule, on this fixture's calendar. */
  val DEFAULT_CYCLE_EXPIRES_ON: LocalDate = LocalDate.of(2027, 7, 31)
  val DEFAULT_AWARD_EXPIRES_ON: LocalDate = LocalDate.of(2027, 7, 31)

  /** Both inside the Fall-2027 application cycle the deadline CHECK bounds. */
  val DEFAULT_FIRST_CHOICE_DEADLINE: LocalDate = LocalDate.of(2027, 5, 1)
  val DEFAULT_APPLY_DEADLINE: LocalDate = LocalDate.of(2026, 12, 1)
}
