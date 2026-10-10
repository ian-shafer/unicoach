package ed.unicoach.db.models

import java.time.LocalDate

/**
 * HOW the Semifinalist bar is set
 * (`national_merit_cycles.semifinalist_cutoff_basis`, RFC 243, brief 0010
 * correction 4 and D9) -- never WHAT it is.
 *
 * Our OWN enumeration, so the column is `TEXT` + `CHECK IN (...)` and this is
 * the one Kotlin home for it (CLAUDE.md, the [AutomaticAwardKind] precedent).
 * ONE MEMBER, because the stage has exactly one published basis.
 *
 * SEPARATE FROM [CommendedCutoffBasis] BECAUSE THE TWO COLUMNS ARE TWO CLOSED
 * VOCABULARIES, each with its own CHECK admitting one value: a national number
 * is never a student's own bar, and one shared type let the two bars be
 * assigned each other's basis with nothing to refuse it -- which would have the
 * coach call the Commended bar her state's.
 *
 * THERE IS NO CUTOFF NUMBER ANYWHERE BESIDE THIS. NMSC publishes none in
 * advance and says outright that the qualifying score varies by state and by
 * year, so what we hold is the basis plus NMSC's own sentence saying it is not
 * published.
 */
enum class SemifinalistCutoffBasis(
  val value: String,
) {
  /**
   * Semifinalist standing, allocated so that each state's share of
   * Semifinalists matches its share of graduating seniors. A student's bar is
   * therefore HER state's, and no national figure is hers.
   */
  STATE_REPRESENTATIONAL("state_representational"),
  ;

  companion object {
    /** The stored code back to the member, or null -- a miss is row corruption for the caller to locate, never a guess. */
    fun fromValue(value: String): SemifinalistCutoffBasis? = entries.find { it.value == value }
  }
}

/**
 * HOW the Commended bar is set
 * (`national_merit_cycles.commended_cutoff_basis`, RFC 243) -- never WHAT it
 * is. See [SemifinalistCutoffBasis] for why these are two types and for the
 * reason no cutoff number is stored at all.
 */
enum class CommendedCutoffBasis(
  val value: String,
) {
  /** Commended standing, set by one national number across every state. */
  NATIONAL("national"),
  ;

  companion object {
    /** The stored code back to the member, or null -- a miss is row corruption for the caller to locate, never a guess. */
    fun fromValue(value: String): CommendedCutoffBasis? = entries.find { it.value == value }
  }
}

/**
 * The in-school testing window of one competition cycle, as its publisher
 * expresses it.
 *
 * ONE VALUE AND NOT A DATE PAIR BESIDE A NULLABLE QUOTE, for the reason
 * [LastSittingConsidered] and [SchoolDayTestChoice] state one corpus along: the
 * schema pairs the two dates (`national_merit_cycles_window_pair_check`), so
 * half a window is a state the database refuses and Kotlin must not be able to
 * write down.
 *
 * [Described] IS THE NORMAL CASE, not a degraded one. Measured during
 * implementation: no publisher has dated any cycle after 2028, and NMSC names
 * the next one in a single sentence. A cycle two years out therefore carries
 * "October of the junior year" and no dates at all, and the coach answering a
 * sophomore says exactly that much rather than inventing a day.
 */
sealed interface CycleWindow {
  /** The publisher's own words for the window, on EVERY arm. Never empty; the schema refuses a blank quote. */
  val asPublished: String

  /** The publisher has dated the window: these are the days it opens and closes, inclusive. */
  data class Dated(
    val opensOn: LocalDate,
    val closesOn: LocalDate,
    override val asPublished: String,
  ) : CycleWindow {
    init {
      // Mirrored from `national_merit_cycles_window_ordered_check` for
      // `NewFafsaFilingDeadline`'s reason: leaving a stated invariant only to
      // the database enforces it in the weaker place, and a window built in a
      // fixture never reaches a CHECK until it is written.
      require(!opensOn.isAfter(closesOn)) {
        "window opens [$opensOn] after it closes [$closesOn] " +
          "(national_merit_cycles_window_ordered_check)"
      }
    }
  }

  /**
   * The publisher describes the window and has not dated it. The words are the
   * fact; nothing here or above may turn them into a date.
   */
  data class Described(
    override val asPublished: String,
  ) : CycleWindow
}

/**
 * The alternate entry route (RFC 243, brief 0010 correction 1): a student who
 * misses the one qualifying sitting through illness or emergency writes to
 * NMSC, and that letter has its own deadline.
 *
 * THIS TYPE IS WHY "miss it and it is gone forever" IS FALSE, which the brief
 * corrected in writing before the slice was designed. A coach that cannot see
 * this row tells a family a door is shut that is open.
 *
 * NULL FOR THE WHOLE VALUE means the publisher describes no route for this
 * cycle -- which is different from a route with no date. [asPublished] is NOT
 * nullable, mirroring `national_merit_cycles_alternate_entry_trio_check`: a
 * deadline or an address we cannot show the publisher's words for is a claim we
 * made ourselves.
 */
data class AlternateEntry(
  /** The day the letter must be received or postmarked by, where the publisher dates it. */
  val deadline: LocalDate?,
  /** Where the letter goes -- an address or an email, as the publisher prints it. */
  val contact: String?,
  /** The publisher's own sentences for the route. Never empty. */
  val asPublished: String,
)

/**
 * The Selection Index scale -- what KIND of number the qualifying score is, so
 * a family reading "212" knows what it is out of.
 *
 * A value and not two ints, so the ordering rule
 * (`national_merit_cycles_selection_index_ordered_check`) has one home.
 * IT IS NOT A CUTOFF and nothing may present it as one.
 */
data class SelectionIndexScale(
  val low: Int,
  val high: Int,
) {
  init {
    require(low < high) {
      "selection index low [$low] is not below high [$high] " +
        "(national_merit_cycles_selection_index_ordered_check)"
    }
  }
}

/**
 * NMSC's OWN award for one cycle, and its cap on a sponsor college's award
 * (RFC 243, brief 0010 correction 3).
 *
 * IT IS HERE SO THAT "the big numbers are the university's, not NMSC's" IS A
 * STORED FACT rather than a sentence the model remembers. NMSC's own award is
 * $2,500 once; the packages that run to six figures belong to named
 * universities and live in `college_national_merit_awards`.
 *
 * [amountUsd] is nullable and [asPublished] is not, mirroring
 * `national_merit_cycles_nmsc_award_pair_check`: a publisher may describe its
 * award without pricing it, and a price with no sentence beside it is our
 * figure rather than theirs.
 */
data class NmscOwnAward(
  /** Whole dollars, once (0059: the unit is in the name). Null where the page describes the award without pricing it. */
  val amountUsd: Int?,
  /** NMSC's own sentence for its award. Never empty. */
  val asPublished: String,
)

/**
 * The published funnel of one competition cycle, stage by stage, and the
 * publisher's own words for it (RFC 243, brief 0010 correction 4).
 *
 * THE BASE RATE IS NOT A STORED COLUMN. It is derived from two of these at read
 * time and labelled as derived ([finalistShareOfEntrants]), which is the schema
 * convention for anything computable from stored columns -- and correction 4
 * exists because the six-figure package numbers are the memorable part and the
 * ~1% is the part a family needs in the same breath.
 *
 * EVERY STAGE IS NULLABLE because NMSC publishes some stages and rounds others,
 * and a cycle it has not yet run has none of them.
 */
data class NationalMeritFunnel(
  /** Everyone who sat the test. */
  val testTakers: Int?,
  /** Those who also met the entry requirements -- the real denominator of the base rate. */
  val metEntryRequirements: Int?,
  val highScorersScreened: Int?,
  val commended: Int?,
  val semifinalists: Int?,
  val finalists: Int?,
  val winners: Int?,
  /** The publisher's own sentence for these figures. Never empty. */
  val asPublished: String,
) {
  init {
    // Mirrors `national_merit_cycles_funnel_narrows_check`, stage for stage. A
    // funnel only narrows, and typing 15,000 Semifinalists under 16,000
    // Finalists inverts the base rate the coach is required to speak.
    val narrowing =
      listOf(
        "met_entry_requirements" to (metEntryRequirements to testTakers),
        "high_scorers_screened" to (highScorersScreened to metEntryRequirements),
        "commended" to (commended to metEntryRequirements),
        "semifinalists" to (semifinalists to metEntryRequirements),
        "finalists" to (finalists to semifinalists),
        "winners" to (winners to finalists),
      )
    narrowing.forEach { (stage, pair) ->
      val (narrower, wider) = pair
      require(narrower == null || wider == null || narrower <= wider) {
        "funnel stage [$stage] is [$narrower] under a wider stage of [$wider] " +
          "(national_merit_cycles_funnel_narrows_check): a funnel only narrows"
      }
    }
  }

  /**
   * The published base rate: the share of entrants who reach Finalist, or null
   * where this cycle publishes neither stage.
   *
   * DERIVED AT READ TIME AND NEVER STORED (CLAUDE.md), and the name says which
   * two stages it is over -- [finalists] of [metEntryRequirements], not of
   * [testTakers], because entry requirements are the screen a family has
   * already passed by the time this is worth saying.
   *
   * A SHARE AND NOT A PERCENTAGE STRING: how it is spoken belongs to the layer
   * that speaks it, and this returns the number it is a share of.
   */
  val finalistShareOfEntrants: Double?
    get() {
      val entrants = metEntryRequirements ?: return null
      val reached = finalists ?: return null
      if (entrants == 0) return null
      return reached.toDouble() / entrants.toDouble()
    }
}

/**
 * ONE publisher's page for a cycle row, as a citation: who they are, the page
 * we read, and the archived address we read it THROUGH where the page does not
 * open for every client.
 *
 * A VALUE BECAUSE THERE ARE TWO OF THEM PER ROW, and that is the design.
 * Measured by the curation pass, not assumed: NMSC sponsors the competition and
 * publishes the entry rule, the alternate entry route, the funnel and the
 * cutoff sentence, while COLLEGE BOARD administers the test and is the only
 * publisher of the window dates, the Selection Index scale and the sentence
 * saying a college never receives the score. One citation column would
 * attribute four of this row's cells to the wrong publisher, which is exactly
 * the mis-citation D26 and RFC 228 exist to prevent.
 *
 * NO url COLUMN LIVES IN THE TABLE (RFC 228; the build fails for one): the page
 * and its optional mirror are foreign keys into `external_documents`, and this
 * type is what the DAO's join hands back.
 */
data class CyclePublication(
  /** Who published it, as a family would recognise them on the page. Never empty. */
  val publisher: String,
  /** The publisher's own page. A row cites something or it does not exist. */
  val sourceUrl: String,
  /** The archived address the row was read through, where the page opens for no client we have. */
  val archiveUrl: String?,
) {
  init {
    require(archiveUrl == null || archiveUrl != sourceUrl) {
      "publisher [$publisher] cites [$sourceUrl] as its own archive " +
        "(national_merit_cycles_archive_differs_check): an archived address that IS the page is the same link twice"
    }
  }
}

/**
 * One National Merit competition year as its publishers state it
 * (`national_merit_cycles`, RFC 243, slice `test/08/the-psat-is-money`).
 *
 * KEYED BY THE COMPETITION YEAR, WHICH IS THE CLASS'S GRADUATION YEAR. NMSC
 * names the 2028 competition for the class that finishes high school in 2028,
 * and that sitting is October 2026 -- so the join from a student to her cycle is
 * exactly her stored `expected_high_school_graduation_year` and nothing
 * inferred. The year is a plain [Int] and deliberately NOT [ed.unicoach.common.util.AcademicYear]: that
 * domain means "the academic year beginning in", so borrowing it would make
 * 2028 mean 2028-29 here and Fall 2028 in `college_national_merit_awards` next
 * door.
 *
 * HAND-TYPED REFERENCE DATA (brief 0010 D19), ROUTE A: keyed by a year and
 * nothing else, so a migration seeds it -- and must, because `bin/test` does
 * `db-reset` and never ingests. "What this test is and when it is" can never
 * depend on an import having run.
 *
 * THERE IS NO CUTOFF ON THIS TYPE. See [SemifinalistCutoffBasis].
 *
 * EXPIRY IS NOT DECIDED HERE, exactly as [AutomaticAward] states one corpus
 * along: this layer returns rows and their dates, and the service that wants
 * today's answer says so. A cycle is LIVE while its [competitionYear] is still
 * ahead of the class now graduating -- keyed on the YEAR and not on its dates,
 * because the undated cycle is real and a liveness rule over dates would never
 * look at it.
 */
data class NationalMeritCycle(
  /** NMSC's own name for the cycle, and the student's graduation year. */
  val competitionYear: Int,
  /** The only sitting that qualifies -- 11 in every row we hold. Stored, so the coach never speaks it from memory. */
  val qualifyingGrade: Int,
  val window: CycleWindow,
  /** The letter route for a missed sitting, or null where the publisher describes none. */
  val alternateEntry: AlternateEntry?,
  val selectionIndex: SelectionIndexScale,
  /** HOW Semifinalist standing is allocated. Never what the score is. */
  val semifinalistCutoff: SemifinalistCutoffBasis,
  /** HOW Commended standing is set. Never what the score is. */
  val commendedCutoff: CommendedCutoffBasis,
  /** NMSC's own sentence saying the qualifying score is not published in advance. This is the answer to "what do I need". */
  val cutoffAsPublished: String,
  /** The administrator's own sentence saying a college never receives the score. Quoted, because it is what makes this a money event. */
  val scoreVisibilityAsPublished: String,
  val funnel: NationalMeritFunnel,
  /** NMSC's own award, and null where this cycle's page prices none. */
  val nmscAward: NmscOwnAward?,
  /** NMSC's cap on a sponsor college's award, in its own words. Null where the page does not state one. */
  val sponsorCapAsPublished: String?,
  /** NMSC: the competition's sponsor, and the publisher of everything but the window dates and the scale. */
  val sponsor: CyclePublication,
  /** College Board: the test's administrator, and the only publisher of the window dates, the scale and the score-visibility sentence. */
  val administrator: CyclePublication,
  val checkedOn: LocalDate,
  val expiresOn: LocalDate,
) {
  init {
    // Mirrors `national_merit_cycles_two_publishers_check`. The sponsor and the
    // administrator are different publishers, and a row citing one page for both
    // is a row that lost half its provenance -- four of its cells would then be
    // attributed to whoever the other column names.
    require(administrator.publisher != sponsor.publisher) {
      "cycle [$competitionYear] names [${sponsor.publisher}] as both sponsor and administrator " +
        "(national_merit_cycles_two_publishers_check)"
    }
    require(administrator.sourceUrl != sponsor.sourceUrl) {
      "cycle [$competitionYear] cites one page [${sponsor.sourceUrl}] for both publishers " +
        "(national_merit_cycles_two_publishers_check)"
    }
    // Mirrors `national_merit_cycles_expiry_after_checked_check`: the day we
    // stop believing the page, never the day a deadline falls, and never the
    // day we read it.
    require(expiresOn.isAfter(checkedOn)) {
      "cycle [$competitionYear] expires [$expiresOn] on or before the day it was read [$checkedOn] " +
        "(national_merit_cycles_expiry_after_checked_check)"
    }
  }
}

/**
 * A cycle row to write. Separate from [NationalMeritCycle] for the reason every
 * other `New*` model in this package is separate -- here, only that the write
 * path names its citations by URL and the read path hands back the urls the
 * join resolved.
 *
 * PRODUCTION ROWS DO NOT ARRIVE THROUGH THIS TYPE. Route A seeds the corpus
 * from a generated, committed migration, so this exists for the test fixtures
 * and for this DAO's own suite -- the [NewAdmissionTestStateProgramme]
 * precedent, which is the same route one corpus along.
 */
data class NewNationalMeritCycle(
  val competitionYear: Int,
  val qualifyingGrade: Int,
  val window: CycleWindow,
  val alternateEntry: AlternateEntry?,
  val selectionIndex: SelectionIndexScale,
  val semifinalistCutoff: SemifinalistCutoffBasis,
  val commendedCutoff: CommendedCutoffBasis,
  val cutoffAsPublished: String,
  val scoreVisibilityAsPublished: String,
  val funnel: NationalMeritFunnel,
  val nmscAward: NmscOwnAward?,
  val sponsorCapAsPublished: String?,
  val sponsor: CyclePublication,
  val administrator: CyclePublication,
  val checkedOn: LocalDate,
  val expiresOn: LocalDate,
)
