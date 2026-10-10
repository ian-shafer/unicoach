package ed.unicoach.coaching.testing

import ed.unicoach.coaching.CoachingTestDb
import ed.unicoach.coaching.HighSchoolYear
import ed.unicoach.coaching.Residency
import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.NationalMeritAward
import ed.unicoach.db.models.NationalMeritAwardId
import ed.unicoach.db.models.NationalMeritAwardKind
import ed.unicoach.db.models.NationalMeritClaim
import ed.unicoach.db.models.QualifyingStanding
import ed.unicoach.db.models.ResidencyBasis
import ed.unicoach.db.models.StudentId
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The National Merit read (RFC 243): which of the seven timing arms a student is
 * in, the base rate that any money has to be said with, and which of the five
 * answers each school on her list gets.
 *
 * THE ARM TABLE IS THE SUBJECT, over (year of school x today x cycle row). Every
 * test pins an INSTANT and never a zone -- the service applies `COACH_ZONE`
 * itself, which is the seam RFC 186's first attempt got wrong -- and every date
 * asserted is a date a seeded row publishes.
 *
 * D22 IS OBEYED HERE AND NOT ASSUMED: the table carries a senior, a student past
 * graduation, a student with an empty college list and a student with no state on
 * file, so nothing in this suite is one junior in one state with one list.
 */
class NationalMeritServiceTest {
  @BeforeEach
  fun setUp() {
    NationalMeritTestDb.reset()
  }

  // ---------------------------------------------------------------------------
  // The arm table: year of school x today x cycle row
  // ---------------------------------------------------------------------------

  /**
   * THE ANSWER THE SLICE'S PROACTIVE MOMENT EXISTS FOR: a junior whose one
   * qualifying sitting is weeks away, with the window's own dates and the fact
   * that her school runs it.
   */
  @Test
  fun `a junior before the window is told the window is ahead, with the dates her publisher printed`() =
    runTest {
      val student = NationalMeritTestDb.createStudent(NationalMeritTestDb.JUNIOR_CLASS)
      NationalMeritTestDb.seedCycle(NationalMeritTestDb.datedCycle())

      val reading = readFor(student, NationalMeritTestDb.NOON_EASTERN_2028_09_01)

      assertEquals(HighSchoolYear.JUNIOR, reading.yearOfSchool, "the class of 2030 is in grade 11 in September 2028")
      val ahead = assertIs<CycleTiming.WindowAhead>(reading.timing)
      assertEquals(NationalMeritTestDb.FIXTURE_CYCLE_YEAR, ahead.cycle.competitionYear, "her cycle is her own class year")
      assertEquals(
        NationalMeritTestDb.WINDOW_OPENS_ON,
        assertIs<ed.unicoach.db.models.CycleWindow.Dated>(ahead.cycle.window).opensOn,
        "the window is the publisher's own dated one",
      )
      assertEquals(11, ahead.cycle.qualifyingGrade, "the qualifying grade is the ROW's and not an 11 typed in the service")
    }

  /**
   * INSIDE THE WINDOW, ON BOTH OF ITS OWN DAYS. The window is inclusive at both
   * ends, so the day it opens and the day it closes are both `window_open`: a
   * `>=`/`>` slip would close it a day early on the one sitting that cannot be
   * retaken.
   */
  @Test
  fun `the day the window opens and the day it closes both answer window_open`() =
    runTest {
      val student = NationalMeritTestDb.createStudent(NationalMeritTestDb.JUNIOR_CLASS)
      NationalMeritTestDb.seedCycle(NationalMeritTestDb.datedCycle())

      assertIs<CycleTiming.WindowOpen>(
        readFor(student, NationalMeritTestDb.NOON_EASTERN_2028_10_01).timing,
        "the window opens ON October 1, so October 1 is inside it",
      )
      assertIs<CycleTiming.WindowOpen>(
        readFor(student, NationalMeritTestDb.NOON_EASTERN_2028_10_30).timing,
        "and it closes ON October 30, so October 30 is still inside it",
      )
    }

  /**
   * THE DAY AFTER THE WINDOW, WHICH IS THE ANSWER THE BRIEF CORRECTED IN WRITING:
   * the letter route is open, and "miss it and it is gone forever" is false.
   */
  @Test
  fun `the day after the window closes answers alternate_entry_open, with the route and its own deadline`() =
    runTest {
      val student = NationalMeritTestDb.createStudent(NationalMeritTestDb.JUNIOR_CLASS)
      NationalMeritTestDb.seedCycle(NationalMeritTestDb.datedCycle())

      val open = assertIs<CycleTiming.AlternateEntryOpen>(readFor(student, NationalMeritTestDb.NOON_EASTERN_2028_10_31).timing)
      assertEquals(NationalMeritTestDb.ALTERNATE_ENTRY_DEADLINE, open.deadline, "the route's own deadline, not the window's")
      assertEquals(
        NationalMeritTestDb.ALTERNATE_ENTRY_CONTACT,
        open.alternateEntry.contact,
        "the route rides with somewhere to send the letter, or it is not a route",
      )
    }

  /** THE DEADLINE DAY IS STILL IN TIME, and the morning after is not: received or postmarked BY April 1. */
  @Test
  fun `the alternate-entry deadline day is still open, and the morning after it the cycle is closed`() =
    runTest {
      val student = NationalMeritTestDb.createStudent(NationalMeritTestDb.JUNIOR_CLASS)
      NationalMeritTestDb.seedCycle(NationalMeritTestDb.datedCycle())

      assertIs<CycleTiming.AlternateEntryOpen>(
        readFor(student, NationalMeritTestDb.NOON_EASTERN_2029_04_01).timing,
        "a letter received ON April 1 is in time, so April 1 is not past the deadline",
      )
      assertIs<CycleTiming.CycleClosed>(
        readFor(student, NationalMeritTestDb.NOON_EASTERN_2029_04_02).timing,
        "and April 2 is, which is the first day there is no route left to offer",
      )
    }

  /**
   * A CYCLE PAST ITS OWN `expires_on` IS A READING WE NO LONGER BELIEVE, and
   * expiry outranks every other arm -- the rule the award half already keeps for
   * a school's row, kept here for the two pages the cycle is read from.
   *
   * She would be [CycleTiming.CycleClosed] on this day without it, so the arm is
   * the one the expiry decided and no other: the window, the letter route and
   * the deadline are all behind her AND all stale, and a payload that spoke them
   * would be quoting a page nobody has opened since.
   *
   * THE BOUNDARY IS INCLUSIVE, the `>` predicate every corpus here shares: the
   * row is believed THROUGH its expires_on.
   */
  @Test
  fun `a cycle past its own expires_on answers cycle_expired, and the day of expiry is still believed`() =
    runTest {
      val student = NationalMeritTestDb.createStudent(NationalMeritTestDb.JUNIOR_CLASS)
      NationalMeritTestDb.seedCycle(NationalMeritTestDb.datedCycle())

      assertIs<CycleTiming.CycleClosed>(
        readFor(student, NationalMeritTestDb.NOON_EASTERN_2029_07_31).timing,
        "ON expires_on the reading still stands, so the ordinary arm is the answer",
      )
      val expired =
        assertIs<CycleTiming.CycleExpired>(
          readFor(student, NationalMeritTestDb.NOON_EASTERN_2029_08_01).timing,
          "and the morning after it, our reading of her cycle is the answer rather than its dates",
        )
      assertEquals(
        NationalMeritTestDb.CYCLE_EXPIRES_ON,
        expired.expiredOn,
        "the arm names the day we stopped believing it, so the answer can say when",
      )
    }

  /**
   * A WINDOW WITH NO ALTERNATE ENTRY ROUTE closes the cycle the day after it
   * shuts. The route is the cycle's own, and a cycle whose publisher describes
   * none has nothing for the coach to offer -- which is a different sentence from
   * "the route has closed".
   */
  @Test
  fun `a cycle publishing no alternate entry route is closed the day after its window`() =
    runTest {
      val student = NationalMeritTestDb.createStudent(NationalMeritTestDb.JUNIOR_CLASS)
      NationalMeritTestDb.seedCycle(NationalMeritTestDb.datedCycle(alternateEntry = null))

      val closed = assertIs<CycleTiming.CycleClosed>(readFor(student, NationalMeritTestDb.NOON_EASTERN_2028_10_31).timing)
      assertNull(closed.cycle.alternateEntry, "there is no route on this cycle at all, which is why it is closed")
    }

  /**
   * A LETTER ROUTE WITH NO PUBLISHED DEADLINE IS NOT A CLOSED CYCLE. The
   * publisher describes the door and dates nothing, so the deadline is on the
   * publisher's own page -- and brief 0010 correction 1 is the whole reason
   * "miss it and it is gone forever" is false. Folding this into
   * [CycleTiming.CycleClosed] would make it true again for exactly the cycle the
   * correction was written about.
   *
   * PROVED BY REVERTING THE ARM: drop the `AlternateEntryUndated` return from
   * `datedTimingOf` and this fails on the arm.
   */
  @Test
  fun `a window closed over an UNDATED letter route is the undated arm and never cycle_closed`() =
    runTest {
      val student = NationalMeritTestDb.createStudent(NationalMeritTestDb.JUNIOR_CLASS)
      NationalMeritTestDb.seedCycle(
        NationalMeritTestDb.datedCycle(alternateEntry = NationalMeritTestDb.undatedAlternateEntry()),
      )

      val undated =
        assertIs<CycleTiming.AlternateEntryUndated>(
          readFor(student, NationalMeritTestDb.NOON_EASTERN_2028_10_31).timing,
          "the window has closed and a route with no date is a door we cannot call shut",
        )

      assertEquals(
        NationalMeritTestDb.UNDATED_ALTERNATE_ENTRY_AS_PUBLISHED,
        undated.alternateEntry.asPublished,
        "the route rides non-null, so the publisher's own words are there to send her to",
      )
      assertNull(undated.alternateEntry.deadline, "and we hold no day for it, which is the whole arm")
      assertIs<CycleTiming.AlternateEntryUndated>(
        readFor(student, NationalMeritTestDb.NOON_EASTERN_2029_04_02).timing,
        "it does not close on the DATED route's deadline either: that date is not this route's",
      )
    }

  /**
   * A SOPHOMORE ON A CYCLE NOBODY HAS DATED -- the normal shape two years out,
   * measured rather than assumed. The window is ahead, in the publisher's own
   * words, and NO date exists to name.
   */
  @Test
  fun `a sophomore whose cycle is described and not dated gets window_ahead and no date at all`() =
    runTest {
      val student = NationalMeritTestDb.createStudent(NationalMeritTestDb.SOPHOMORE_CLASS)
      NationalMeritTestDb.seedCycle(NationalMeritTestDb.describedCycle())

      val reading = readFor(student, NationalMeritTestDb.NOON_EASTERN_2028_09_01)

      assertEquals(HighSchoolYear.SOPHOMORE, reading.yearOfSchool)
      val ahead = assertIs<CycleTiming.WindowAhead>(reading.timing)
      val window = assertIs<ed.unicoach.db.models.CycleWindow.Described>(ahead.cycle.window)
      assertEquals(
        NationalMeritTestDb.WINDOW_AS_PUBLISHED,
        window.asPublished,
        "the words are the fact, and nothing derives a date from them",
      )
    }

  /**
   * A JUNIOR ON A CYCLE NOBODY HAS DATED. `window_open` and
   * `alternate_entry_open` are both a comparison with today, so an undated row
   * cannot answer either: the arm that names a window without dating it is
   * `window_ahead`.
   */
  @Test
  fun `a junior whose own cycle is undated gets window_ahead, because no arm here can compare an undated window with today`() =
    runTest {
      val student = NationalMeritTestDb.createStudent(NationalMeritTestDb.JUNIOR_CLASS)
      NationalMeritTestDb.seedCycle(NationalMeritTestDb.describedCycle(NationalMeritTestDb.FIXTURE_CYCLE_YEAR))

      val reading = readFor(student, NationalMeritTestDb.NOON_EASTERN_2028_10_15)

      assertEquals(HighSchoolYear.JUNIOR, reading.yearOfSchool)
      assertIs<CycleTiming.WindowAhead>(reading.timing)
    }

  /** A SENIOR: her qualifying year has gone, and the cycle is closed whatever its row says. */
  @Test
  fun `a senior past her own qualifying year gets cycle_closed`() =
    runTest {
      val student = NationalMeritTestDb.createStudent(NationalMeritTestDb.SENIOR_CLASS)
      NationalMeritTestDb.seedCycle(NationalMeritTestDb.describedCycle())

      val reading = readFor(student, NationalMeritTestDb.NOON_EASTERN_2030_09_01)

      assertEquals(HighSchoolYear.SENIOR, reading.yearOfSchool)
      val closed = assertIs<CycleTiming.CycleClosed>(reading.timing)
      assertEquals(NationalMeritTestDb.SENIOR_CLASS, closed.cycle.competitionYear, "it is still HER cycle, and it is over")
    }

  /**
   * PAST GRADUATION, which is a state [HighSchoolYear] already models and the
   * scope refusal this brief needs (D3). The row is hers and the answer is
   * closed, without reading a date at all.
   */
  @Test
  fun `a student past her expected graduation gets cycle_closed, and the row is still hers`() =
    runTest {
      val student = NationalMeritTestDb.createStudent(NationalMeritTestDb.PAST_GRADUATION_CLASS)
      NationalMeritTestDb.seedCycle(NationalMeritTestDb.datedCycle())

      val reading = readFor(student, NationalMeritTestDb.NOON_EASTERN_2030_09_01)

      assertEquals(HighSchoolYear.PAST_GRADUATION, reading.yearOfSchool)
      val closed = assertIs<CycleTiming.CycleClosed>(reading.timing)
      assertEquals(NationalMeritTestDb.PAST_GRADUATION_CLASS, closed.cycle.competitionYear)
    }

  /**
   * A CLASS WE HOLD NO ROW FOR answers `not_curated` and names no date. It is a
   * gap in OUR data: the competition runs for her year, and a corpus covering the
   * cycles their publishers have described cannot cover a year nobody has
   * described yet.
   */
  @Test
  fun `a class we hold no cycle row for answers not_curated, carries no cycle and names her own year`() =
    runTest {
      val student = NationalMeritTestDb.createStudent(NationalMeritTestDb.UNCURATED_CLASS)
      NationalMeritTestDb.seedCycle(NationalMeritTestDb.datedCycle())

      val reading = readFor(student, NationalMeritTestDb.NOON_EASTERN_2030_09_01)

      val notCurated = assertIs<CycleTiming.NotCurated>(reading.timing)
      assertEquals(
        NationalMeritTestDb.UNCURATED_CLASS,
        notCurated.graduationYear,
        "the arm names the year we hold nothing for, so the answer can say which year that is",
      )
    }

  /**
   * AN EMPTY COLLEGE LIST STILL GETS THE WHOLE CYCLE HALF (D22, and Route A's
   * own reason): "what this test is and when it is" is seeded by migration and
   * can never wait on a list or on an import.
   *
   * AND IT STILL STATES THE BASE RATE, which is the half that was wrong: the
   * cycle object speaks NMSC's own award, so the counterweight to it cannot
   * depend on whether a school on her list happens to be priced. This is the
   * junior the proactive PSAT nudge fires for -- a junior in her lead window
   * with no college list yet -- so it is the first payload this feature ever
   * sends.
   */
  @Test
  fun `a student with an empty college list still gets her cycle, the base rate, and no school answer at all`() =
    runTest {
      val student = NationalMeritTestDb.createStudent(NationalMeritTestDb.JUNIOR_CLASS)
      NationalMeritTestDb.seedCycle(NationalMeritTestDb.datedCycle())

      val reading = readFor(student, NationalMeritTestDb.NOON_EASTERN_2028_09_01)

      assertIs<CycleTiming.WindowAhead>(reading.timing)
      assertEquals(emptyList(), reading.schools, "she has no schools, so there is no school to answer for")
      assertEquals(emptyList(), reading.unknownCollegeIds, "and she asked for none, so none is unknown")
      val baseRate =
        assertNotNull(
          reading.baseRate,
          "the corpus publishes a funnel, so this reading states it: no priced package is needed for the " +
            "counterweight to the cycle's own award to be stated",
        )
      assertEquals(
        NationalMeritTestDb.FIXTURE_CYCLE_YEAR,
        baseRate.competitionYear,
        "and it is the cycle's own year, not hers unless they match",
      )
    }

  /** An id that is not on her list is REPORTED rather than dropped, and never answered as a school with no award. */
  @Test
  fun `an off-list college id is reported and never answered`() =
    runTest {
      val student = NationalMeritTestDb.createStudent(NationalMeritTestDb.JUNIOR_CLASS)
      val stranger = NationalMeritTestDb.createStudent(NationalMeritTestDb.JUNIOR_CLASS)
      NationalMeritTestDb.seedCycle(NationalMeritTestDb.datedCycle())
      val notHers = NationalMeritTestDb.seedListedCollege(stranger, "Somebody Else's College")
      NationalMeritTestDb.seedAward(NationalMeritTestDb.award(notHers))

      val reading = readFor(student, NationalMeritTestDb.NOON_EASTERN_2028_09_01, collegeIds = listOf(notHers))

      assertEquals(emptyList(), reading.schools, "another student's school is not read onto hers")
      assertEquals(listOf(notHers), reading.unknownCollegeIds)
    }

  // ---------------------------------------------------------------------------
  // The base-rate contract
  // ---------------------------------------------------------------------------

  /**
   * THE BASE RATE IS TAKEN FROM THE MOST RECENT CYCLE THAT PUBLISHES ONE, AND IT
   * NAMES THAT YEAR.
   *
   * A sophomore's own cycle publishes no funnel at all -- measured, not assumed:
   * the publisher runs a funnel only for the competition in progress -- so her
   * answer carries the later dated cycle's figures, said as that cycle's. Copying
   * them into her year would be the fabrication this whole corpus exists to
   * prevent.
   */
  @Test
  fun `the base rate comes from the most recent cycle that publishes one, and names that competition year`() =
    runTest {
      val student = NationalMeritTestDb.createStudent(NationalMeritTestDb.SOPHOMORE_CLASS)
      NationalMeritTestDb.seedCycle(NationalMeritTestDb.datedCycle())
      NationalMeritTestDb.seedCycle(NationalMeritTestDb.describedCycle())
      val college = NationalMeritTestDb.seedListedCollege(student, "Base Rate College")
      NationalMeritTestDb.seedAward(NationalMeritTestDb.award(college))

      val reading = readFor(student, NationalMeritTestDb.NOON_EASTERN_2028_09_01)

      val ahead = assertIs<CycleTiming.WindowAhead>(reading.timing)
      assertEquals(
        NationalMeritTestDb.SOPHOMORE_CLASS,
        ahead.cycle.competitionYear,
        "her own cycle is the described one, and it publishes no funnel",
      )
      assertNull(ahead.cycle.funnel.finalistShareOfEntrants, "which is the measured shape this case exists for")
      val baseRate = assertNotNull(reading.baseRate, "so the base rate is taken from another year, and never left out")
      assertEquals(
        NationalMeritTestDb.FIXTURE_CYCLE_YEAR,
        baseRate.competitionYear,
        "the year NAMED is the year that published the figures, not hers",
      )
      assertEquals(
        NationalMeritTestDb.datedCycle().funnel.finalists,
        baseRate.funnel.finalists,
        "and the funnel is that cycle's own, stage for stage",
      )
    }

  /**
   * MONEY AND THE BASE RATE ARE ONE VALUE. Every priced package carries the base
   * rate it must be spoken with, and the reading's head base rate is the SAME
   * value those packages were built from -- so the two cannot disagree.
   */
  @Test
  fun `every priced package carries the base rate, and the reading states that same base rate at its head`() =
    runTest {
      val student = NationalMeritTestDb.createStudent(NationalMeritTestDb.JUNIOR_CLASS)
      NationalMeritTestDb.seedCycle(NationalMeritTestDb.datedCycle())
      val college = NationalMeritTestDb.seedListedCollege(student, "Priced College")
      NationalMeritTestDb.seedAward(NationalMeritTestDb.award(college))

      val reading = readFor(student, NationalMeritTestDb.NOON_EASTERN_2028_09_01)

      val automatic = assertIs<NationalMeritSchoolAnswer.Automatic>(reading.schools.single())
      val priced = assertIs<PublishedPackage.Priced>(automatic.published)
      assertEquals(NationalMeritTestDb.AWARD_PER_YEAR_USD, priced.perYearUsd, "the school's own figure")
      assertEquals(
        NationalMeritTestDb.FIXTURE_CYCLE_YEAR,
        priced.baseRate.competitionYear,
        "and the base rate rides INSIDE the price, because a figure without it is a lottery ticket",
      )
      assertEquals(
        priced.baseRate,
        assertNotNull(reading.baseRate),
        "the head base rate is the same value the package carries -- one read of the corpus, handed to both",
      )
    }

  /**
   * A PACKAGE PRICED ONLY IN WORDS IS A PRICED PACKAGE, and it carries the base
   * rate exactly as a figure does.
   *
   * HALF THE PAYING SCHOOLS IN THIS CORPUS ARE THIS SHAPE and they are the TOP
   * awards. A words-only package treated as "nothing to price" would be the $0
   * ranking RFC 239's review found shipping.
   */
  @Test
  fun `a school that publishes its package in words and no figure is priced, and carries the base rate too`() =
    runTest {
      val student = NationalMeritTestDb.createStudent(NationalMeritTestDb.JUNIOR_CLASS)
      NationalMeritTestDb.seedCycle(NationalMeritTestDb.datedCycle())
      val college = NationalMeritTestDb.seedListedCollege(student, "Words College")
      NationalMeritTestDb.seedAward(NationalMeritTestDb.wordsOnlyAward(college))

      val automatic =
        assertIs<NationalMeritSchoolAnswer.Automatic>(
          readFor(student, NationalMeritTestDb.NOON_EASTERN_2028_09_01).schools.single(),
        )
      val priced = assertIs<PublishedPackage.Priced>(automatic.published)

      assertNull(priced.perYearUsd, "this school publishes no figure at all")
      assertNull(priced.totalUsd)
      assertEquals(NationalMeritTestDb.WORDS_ONLY_AS_PUBLISHED, priced.asPublished, "its own words ARE the price")
      assertEquals(NationalMeritTestDb.FIXTURE_CYCLE_YEAR, priced.baseRate.competitionYear)
    }

  /**
   * WITH NO BASE RATE THERE IS NO PRICE AT ALL -- not the figures, and not the
   * publisher's own words, because the words ARE the price.
   *
   * IT IS A UNIT TEST OF THE ONE BUILDER, because Route A makes this unreachable
   * from a migrated database by construction: the cycles are seeded by migration,
   * so a database with no funnel anywhere is a state `bin/test` cannot produce.
   * The contract still has to hold, and this is where it is held.
   */
  @Test
  fun `with no base rate a price is withheld, the figures and the publisher's own words alike`() =
    runTest {
      val student = NationalMeritTestDb.createStudent(NationalMeritTestDb.JUNIOR_CLASS)
      NationalMeritTestDb.seedCycle(NationalMeritTestDb.datedCycle())
      val college = NationalMeritTestDb.seedListedCollege(student, "Withheld College")
      NationalMeritTestDb.seedAward(NationalMeritTestDb.award(college))
      val paying =
        assertIs<NationalMeritSchoolAnswer.Automatic>(
          readFor(student, NationalMeritTestDb.NOON_EASTERN_2028_09_01).schools.single(),
        ).claim

      val baseRate = PublishedBaseRate(NationalMeritTestDb.FIXTURE_CYCLE_YEAR, NationalMeritTestDb.datedCycle().funnel)
      assertIs<PublishedPackage.Priced>(
        PublishedPackage.of(paying, baseRate),
        "with a base rate, the school's price is stated",
      )
      assertEquals(
        PublishedPackage.Withheld,
        PublishedPackage.of(paying, baseRate = null),
        "and with none there is no price object at all -- ${paying.asPublished} is withheld with the figures",
      )
    }

  // ---------------------------------------------------------------------------
  // The five per-school answers
  // ---------------------------------------------------------------------------

  /** A school that pays a standing outright, and one that merely CONSIDERS it -- the costliest confusion this corpus can make. */
  @Test
  fun `automatic and competitive are different answers, and the competitive one is never the automatic one`() =
    runTest {
      val student = NationalMeritTestDb.createStudent(NationalMeritTestDb.JUNIOR_CLASS)
      NationalMeritTestDb.seedCycle(NationalMeritTestDb.datedCycle())
      val pays = NationalMeritTestDb.seedListedCollege(student, "Pays College")
      val considers = NationalMeritTestDb.seedListedCollege(student, "Considers College")
      NationalMeritTestDb.seedAward(NationalMeritTestDb.award(pays))
      NationalMeritTestDb.seedAward(NationalMeritTestDb.award(considers, awardKind = NationalMeritAwardKind.COMPETITIVE))

      val answers = readFor(student, NationalMeritTestDb.NOON_EASTERN_2028_09_01).schools.associateBy { it.collegeId }

      assertIs<NationalMeritSchoolAnswer.Automatic>(answers.getValue(pays))
      val competitive = assertIs<NationalMeritSchoolAnswer.Competitive>(answers.getValue(considers))
      assertEquals(QualifyingStanding.FINALIST, competitive.standing, "the standing it considers is still named")
      assertIs<PublishedPackage.Priced>(competitive.published, "and the figures are the school's own, with the base rate beside them")
    }

  /** A verified absence, a school we never read, and the two are never one answer. */
  @Test
  fun `none_published is a verified absence with its own citation, and not_curated is a school we never read`() =
    runTest {
      val student = NationalMeritTestDb.createStudent(NationalMeritTestDb.JUNIOR_CLASS)
      NationalMeritTestDb.seedCycle(NationalMeritTestDb.datedCycle())
      val read = NationalMeritTestDb.seedListedCollege(student, "Read College")
      val unread = NationalMeritTestDb.seedListedCollege(student, "Unread College")
      NationalMeritTestDb.seedAward(NationalMeritTestDb.absence(read))

      val answers = readFor(student, NationalMeritTestDb.NOON_EASTERN_2028_09_01).schools.associateBy { it.collegeId }

      val absence = assertIs<NationalMeritSchoolAnswer.NonePublished>(answers.getValue(read))
      assertEquals(
        NationalMeritTestDb.ABSENCE_AS_PUBLISHED,
        absence.claim.asPublished,
        "the sentence that PROVES the absence, which is what makes it a citation rather than silence",
      )
      assertEquals(NationalMeritTestDb.AWARD_CHECKED_ON, absence.award.checkedOn, "and the day a person read it")
      assertIs<NationalMeritSchoolAnswer.NotCurated>(answers.getValue(unread))
    }

  /**
   * EXPIRY IS DECIDED WITH THE SAME `>` PREDICATE THE LANDED CORPORA SHARE: a row
   * is believed THROUGH its `expires_on`. And an expired row states no price at
   * all.
   */
  @Test
  fun `a row is believed through its expires_on and expired the morning after, with no price on the expired arm`() =
    runTest {
      val student = NationalMeritTestDb.createStudent(NationalMeritTestDb.JUNIOR_CLASS)
      NationalMeritTestDb.seedCycle(NationalMeritTestDb.datedCycle())
      val college = NationalMeritTestDb.seedListedCollege(student, "Expiring College")
      NationalMeritTestDb.seedAward(NationalMeritTestDb.award(college))

      assertIs<NationalMeritSchoolAnswer.Automatic>(
        readFor(student, NationalMeritTestDb.NOON_EASTERN_2029_07_31).schools.single(),
        "the last day the reading is believed is a day it still answers",
      )

      val expired =
        assertIs<NationalMeritSchoolAnswer.Expired>(
          readFor(student, NationalMeritTestDb.NOON_EASTERN_2029_08_01).schools.single(),
          "and the morning after, it is stale",
        )
      assertEquals(NationalMeritTestDb.AWARD_EXPIRES_ON, expired.expiredOn, "the arm names the day we stopped believing it")
      assertEquals(
        emptyList(),
        packagesOf(expired),
        "an expired answer has no PublishedPackage at all, so there is nowhere for a stale figure to ride",
      )
    }

  // ---------------------------------------------------------------------------
  // Residency
  // ---------------------------------------------------------------------------

  /**
   * AN OUT-OF-STATE STUDENT IS NEVER SHOWN AN IN-STATE-ONLY ROW AS HERS. Auburn
   * is the measured case: it cut a $44,000 award to Alabama residents only.
   *
   * THE ROW STILL RIDES, LABELLED. "This award exists and is not yours" is a
   * different answer from "this school publishes nothing", and dropping the row
   * would collapse them.
   */
  @Test
  fun `an in-state-only row is served to an out-of-state family as differing from the state on file`() =
    runTest {
      val student = NationalMeritTestDb.createStudent(NationalMeritTestDb.JUNIOR_CLASS)
      NationalMeritTestDb.seedCycle(NationalMeritTestDb.datedCycle())
      val college = NationalMeritTestDb.seedListedCollege(student, "Residents Only University", state = "AL")
      NationalMeritTestDb.seedAward(NationalMeritTestDb.award(college, residencyBasis = ResidencyBasis.IN_STATE))

      val hers =
        assertIs<NationalMeritSchoolAnswer.Read>(
          readFor(student, NationalMeritTestDb.NOON_EASTERN_2028_09_01, residency = Residency.OnFile("AL")).schools.single(),
        )
      assertEquals(ResidencyScope.MATCHES_STATE_ON_FILE, hers.residencyScope, "she lives in the school's own state")

      val notHers =
        assertIs<NationalMeritSchoolAnswer.Read>(
          readFor(student, NationalMeritTestDb.NOON_EASTERN_2028_09_01, residency = Residency.OnFile("OR")).schools.single(),
        )
      assertEquals(
        ResidencyScope.DIFFERS_FROM_STATE_ON_FILE,
        notHers.residencyScope,
        "an Alabama-residents-only award is not an Oregon family's, and the answer says so rather than dropping it",
      )
      assertEquals(ResidencyBasis.IN_STATE, notHers.award.residencyBasis, "and the row is still there, labelled with whose it is")
    }

  /**
   * A STUDENT WITH NO MONEY PROFILE IS ANSWERED (D22): there is no comparison to
   * make, which is a NAMED state and never "the row is not hers".
   */
  @Test
  fun `with no state on file the comparison is a named absence, and the out-of-state row is still served`() =
    runTest {
      val student = NationalMeritTestDb.createStudent(NationalMeritTestDb.JUNIOR_CLASS)
      NationalMeritTestDb.seedCycle(NationalMeritTestDb.datedCycle())
      val college = NationalMeritTestDb.seedListedCollege(student, "Non Resident University", state = "AL")
      NationalMeritTestDb.seedAward(NationalMeritTestDb.award(college, residencyBasis = ResidencyBasis.OUT_OF_STATE))

      val answer =
        assertIs<NationalMeritSchoolAnswer.Read>(
          readFor(student, NationalMeritTestDb.NOON_EASTERN_2028_09_01, residency = Residency.NotAsked).schools.single(),
        )

      assertEquals(ResidencyScope.NO_STATE_ON_FILE, answer.residencyScope)
      assertIs<PublishedPackage.Priced>(
        assertIs<NationalMeritSchoolAnswer.Automatic>(answer).published,
        "nothing is withheld for a missing state: the school's published package is a fact about the school",
      )
    }

  /** An out-of-state row IS the resident's non-matching one, and a school with one package for everyone is scoped by neither. */
  @Test
  fun `an out-of-state row differs for a family in the school's own state, and a single-package school is not residency scoped`() =
    runTest {
      val student = NationalMeritTestDb.createStudent(NationalMeritTestDb.JUNIOR_CLASS)
      NationalMeritTestDb.seedCycle(NationalMeritTestDb.datedCycle())
      val nonResident = NationalMeritTestDb.seedListedCollege(student, "Out Of State University", state = "AL")
      val everyone = NationalMeritTestDb.seedListedCollege(student, "One Package University", state = "AL")
      NationalMeritTestDb.seedAward(NationalMeritTestDb.award(nonResident, residencyBasis = ResidencyBasis.OUT_OF_STATE))
      NationalMeritTestDb.seedAward(NationalMeritTestDb.award(everyone, residencyBasis = ResidencyBasis.NOT_APPLICABLE))

      val answers =
        readFor(student, NationalMeritTestDb.NOON_EASTERN_2028_09_01, residency = Residency.OnFile("AL"))
          .schools
          .associateBy { it.collegeId }

      assertEquals(
        ResidencyScope.DIFFERS_FROM_STATE_ON_FILE,
        assertIs<NationalMeritSchoolAnswer.Read>(answers.getValue(nonResident)).residencyScope,
        "a non-resident award is not a resident's",
      )
      assertEquals(
        ResidencyScope.NOT_RESIDENCY_SCOPED,
        assertIs<NationalMeritSchoolAnswer.Read>(answers.getValue(everyone)).residencyScope,
        "and a school with one package for everyone is not scoped at all, which is a real answer",
      )
    }

  /**
   * A MONEY-PROFILE READ THAT FAULTED IS NOT A FAMILY WITH NO STATE ON FILE, and
   * this is the worst thing this read could get wrong: residency decides whether
   * an in-state package is hers at all, so a transient fault spoken as a fact
   * about her is us inventing the one answer we did not have.
   *
   * PROVED BY REVERTING THE FIX: map [Residency.Cause.READ_FAILED] back to
   * `NO_STATE_ON_FILE` in `ResidencyScope.scopeOfUnusable` and the first
   * assertion goes red by name.
   */
  @Test
  fun `a residency read that FAULTED is its own scope and never a state we hold none of`() =
    runTest {
      val student = NationalMeritTestDb.createStudent(NationalMeritTestDb.JUNIOR_CLASS)
      NationalMeritTestDb.seedCycle(NationalMeritTestDb.datedCycle())
      val college = NationalMeritTestDb.seedListedCollege(student, "Residents Only University", state = "AL")
      NationalMeritTestDb.seedAward(NationalMeritTestDb.award(college, residencyBasis = ResidencyBasis.IN_STATE))

      val faulted =
        assertIs<NationalMeritSchoolAnswer.Read>(
          readFor(
            student,
            NationalMeritTestDb.NOON_EASTERN_2028_09_01,
            residency = Residency.Unavailable(Residency.Cause.READ_FAILED),
          ).schools.single(),
        )

      assertEquals(
        ResidencyScope.STATE_UNAVAILABLE,
        faulted.residencyScope,
        "we could not read her state, so the comparison says so -- it does not say we hold none",
      )
      assertNotEquals(
        ResidencyScope.NO_STATE_ON_FILE,
        faulted.residencyScope,
        "which is the arm this fix exists to keep apart from a family nobody has asked",
      )
      assertIs<PublishedPackage.Priced>(
        assertIs<NationalMeritSchoolAnswer.Automatic>(faulted).published,
        "and nothing is withheld for it: the school's published package is a fact about the school",
      )
    }

  /**
   * A DECLINE IS AN ABSENCE AND WE HOLD IT. They were asked and chose not to
   * say, so "we hold no state" is TRUE of them -- and the payload never restates
   * the decline either, because repeating a closed topic back on every package
   * is re-raising it in all but name (`ResidencyOffer.noteFor`).
   *
   * A CORRUPT PROFILE IS NOT THAT. A stored row that says ANSWERED while holding
   * no usable state is OUR corruption, so it is the unavailable arm with the
   * fault.
   */
  @Test
  fun `a decline is the named absence and a corrupt profile is the unavailable arm`() =
    runTest {
      val student = NationalMeritTestDb.createStudent(NationalMeritTestDb.JUNIOR_CLASS)
      NationalMeritTestDb.seedCycle(NationalMeritTestDb.datedCycle())
      val college = NationalMeritTestDb.seedListedCollege(student, "Residents Only University", state = "AL")
      NationalMeritTestDb.seedAward(NationalMeritTestDb.award(college, residencyBasis = ResidencyBasis.IN_STATE))

      val declined =
        assertIs<NationalMeritSchoolAnswer.Read>(
          readFor(
            student,
            NationalMeritTestDb.NOON_EASTERN_2028_09_01,
            residency = Residency.Unavailable(Residency.Cause.DECLINED),
          ).schools.single(),
        )
      val corrupt =
        assertIs<NationalMeritSchoolAnswer.Read>(
          readFor(
            student,
            NationalMeritTestDb.NOON_EASTERN_2028_09_01,
            residency = Residency.Unavailable(Residency.Cause.CORRUPT_PROFILE),
          ).schools.single(),
        )

      assertEquals(ResidencyScope.NO_STATE_ON_FILE, declined.residencyScope, "they told us nothing, and that is theirs to decide")
      assertEquals(
        ResidencyScope.STATE_UNAVAILABLE,
        corrupt.residencyScope,
        "a stored state we cannot use is OUR fault and not an absence we may speak of as theirs",
      )
    }

  /**
   * A CORRUPT BASIS IS REFUSED WITH THE ROW NAMED. The refusal is typed and
   * located: an operator handed a basis code and no id is sent to a corpus of
   * thousands, and a caller can tell a corrupt row from a read that failed.
   *
   * The row is built in memory because the CHECK constraint refuses to store
   * one, which is why this is a refusal rather than a branch.
   */
  @Test
  fun `a corrupt residency basis is refused with the row located and the basis typed`() {
    val award = corruptAward()

    val refusal =
      assertFailsWith<CorruptAwardResidencyBasisException> {
        ResidencyScope.of(award, "AL", Residency.OnFile("AL"))
      }

    assertEquals(ResidencyBasis.IN_DISTRICT, refusal.basis, "the basis rides as a value a caller can switch on")
    assertEquals(award, refusal.award, "and so does the row, which is what an operator has to go and fix")
    val message = assertNotNull(refusal.message)
    assertTrue(award.id.value.toString() in message, "the sentence LOCATES the row by id: [$message]")
    assertTrue(award.collegeId.value.toString() in message, "and by college: [$message]")
    assertTrue(award.entryYear.label in message, "and by entry year: [$message]")

    // THE RENDERER'S OWN GUARD, asserted beside this one rather than in the wire
    // suite: it is the same corruption and the same refusal, and the row the
    // database will not store is built in one place.
    val labelRefusal =
      assertFailsWith<CorruptAwardResidencyBasisException> {
        NationalMeritChatTool.residencyLabelOf(award)
      }
    assertEquals(award, labelRefusal.award, "the same row, refused the same way one layer along")
  }

  /** A school publishing a row per standing is several answers for one college, and no rule of one is read onto the other. */
  @Test
  fun `a school publishing two standings answers twice for one college`() =
    runTest {
      val student = NationalMeritTestDb.createStudent(NationalMeritTestDb.JUNIOR_CLASS)
      NationalMeritTestDb.seedCycle(NationalMeritTestDb.datedCycle())
      val college = NationalMeritTestDb.seedListedCollege(student, "Two Standings University")
      NationalMeritTestDb.seedAward(NationalMeritTestDb.award(college, qualifyingStanding = QualifyingStanding.FINALIST))
      NationalMeritTestDb.seedAward(
        NationalMeritTestDb.award(
          college,
          qualifyingStanding = QualifyingStanding.SEMIFINALIST,
          awardPerYearUsd = 5_000,
          awardTotalUsd = null,
          awardYears = null,
        ),
      )

      val answers = readFor(student, NationalMeritTestDb.NOON_EASTERN_2028_09_01).schools

      assertEquals(2, answers.size, "one row per standing, and one answer per row")
      assertEquals(
        setOf(QualifyingStanding.FINALIST, QualifyingStanding.SEMIFINALIST),
        answers.map { assertIs<NationalMeritSchoolAnswer.Automatic>(it).standing }.toSet(),
      )
      assertTrue(answers.all { it.collegeId == college }, "both are this one school's")
    }

  // ---------------------------------------------------------------------------
  // The statement budget
  // ---------------------------------------------------------------------------

  /**
   * THE STATEMENT BUDGET, ABSOLUTE AND NOT MERELY RELATIVE (the
   * [AutomaticAwardServiceTest] precedent, and RFC 187's lesson that a count
   * stated only in prose is a claim no test can contradict).
   *
   * TEN COLLEGES COST WHAT ONE COLLEGE COSTS, and the absolute number is asserted
   * beside it.
   */
  @Test
  fun `the whole read costs the stated number of statements, for one college and for ten`() =
    runTest {
      val one = NationalMeritTestDb.createStudent(NationalMeritTestDb.JUNIOR_CLASS)
      val ten = NationalMeritTestDb.createStudent(NationalMeritTestDb.JUNIOR_CLASS)
      NationalMeritTestDb.seedCycle(NationalMeritTestDb.datedCycle())
      seedPackages(one, count = 1)
      seedPackages(ten, count = 10)

      val forOne = CoachingTestDb.CountingSession()
      val forTen = CoachingTestDb.CountingSession()
      val service = NationalMeritTestDb.serviceAt(NationalMeritTestDb.NOON_EASTERN_2028_09_01)
      assertEquals(1, service.readInSession(forOne, one, null, Residency.NotAsked).schools.size)
      assertEquals(10, service.readInSession(forTen, ten, null, Residency.NotAsked).schools.size)

      assertEquals(
        NationalMeritService.READ_STATEMENTS,
        forOne.prepared.size,
        "the whole read is [${NationalMeritService.READ_STATEMENTS}] statements -- her students row, every cycle, " +
          "the selection's two, and this corpus's award rows. It prepared [${forOne.prepared}]; if that is correct, " +
          "say so in readInSession's KDoc and in READ_STATEMENTS in the same commit",
      )
      assertEquals(
        forOne.prepared.size,
        forTen.prepared.size,
        "ten colleges must cost what one college costs: [${forTen.prepared}]",
      )
    }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private suspend fun readFor(
    student: StudentId,
    instant: String,
    collegeIds: List<CollegeId>? = null,
    residency: Residency = Residency.NotAsked,
  ): NationalMeritReading =
    NationalMeritTestDb
      .serviceAt(instant)
      .getForStudent(student, collegeIds, residency)
      .getOrThrow()

  /**
   * A row on a basis `college_national_merit_awards_residency_basis_check` does
   * not admit, built in MEMORY because the database refuses to hold one -- which
   * is the whole reason the read refuses it rather than describing it.
   */
  private fun corruptAward(): NationalMeritAward =
    NationalMeritAward(
      id = NationalMeritAwardId(UUID.randomUUID()),
      collegeId = CollegeId(UUID.randomUUID()),
      entryYear = AcademicYear(NationalMeritTestDb.AWARD_ENTRY_YEAR),
      residencyBasis = ResidencyBasis.IN_DISTRICT,
      claim = NationalMeritClaim.NonePublished(NationalMeritTestDb.ABSENCE_AS_PUBLISHED),
      publisher = NationalMeritTestDb.DEFAULT_PUBLISHER,
      sourceUrl = "https://example.invalid/national-merit",
      archiveUrl = null,
      checkedOn = LocalDate.of(2028, 9, 1),
      expiresOn = LocalDate.of(2029, 7, 31),
    )

  /** Every [PublishedPackage] one answer carries -- empty on the three arms that carry none, which is the assertion. */
  private fun packagesOf(answer: NationalMeritSchoolAnswer): List<PublishedPackage> =
    when (answer) {
      is NationalMeritSchoolAnswer.Automatic -> listOf(answer.published)

      is NationalMeritSchoolAnswer.Competitive -> listOf(answer.published)

      is NationalMeritSchoolAnswer.NonePublished,
      is NationalMeritSchoolAnswer.Expired,
      is NationalMeritSchoolAnswer.NotCurated,
      -> emptyList()
    }

  /** [count] listed schools, each with one paying package -- the statement-budget test's input. */
  private fun seedPackages(
    student: StudentId,
    count: Int,
  ) {
    repeat(count) { index ->
      val college = NationalMeritTestDb.seedListedCollege(student, "Budget College $index")
      NationalMeritTestDb.seedAward(NationalMeritTestDb.award(college))
    }
  }
}
