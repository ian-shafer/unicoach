package ed.unicoach.web.render

import ed.unicoach.coaching.aid.FafsaDeadline
import ed.unicoach.coaching.aid.FafsaDeadlines
import ed.unicoach.coaching.aid.FafsaProcessCopy
import ed.unicoach.coaching.aid.FafsaProcessCopy.FafsaProcessTopic
import ed.unicoach.coaching.aid.FafsaProcessCopy.FafsaRule
import ed.unicoach.coaching.aid.FafsaUnreadableAgencies
import ed.unicoach.coaching.aid.MissingJurisdiction
import ed.unicoach.coaching.aid.NoDeadlinesReason
import ed.unicoach.coaching.aid.StateDeadlines
import ed.unicoach.db.models.AidDeadlineEvent
import ed.unicoach.web.fafsa.FafsaDeadlineSource
import io.ktor.server.application.ApplicationCall
import org.slf4j.LoggerFactory
import java.time.LocalDate

// RFC 238 -- `/fafsa`, the public explainer a family reaches with no account.
//
// This file is CONTENT plus the ONE transform that turns the deadline service's
// answer into table rows. The model and the renderer are in `ExplainerPage.kt`,
// so a copy edit never touches HTML.
//
// Five rules govern every sentence below, and a reviewer should hold the file
// to them:
//
//  - NOT ONE RULE IS RETYPED HERE. Every claim about the FAFSA process is a
//    `FafsaRule` read from `FafsaProcessCopy` -- the same corpus the coach
//    speaks from -- so the page and the coach cannot tell a family two
//    different stories (brief 0012 D4). All thirty go on the page, because a
//    subset would need its own defensible boundary and would create exactly
//    that gap.
//  - NOT ONE DATE IS COMPUTED HERE. `FafsaDeadlineService` decides the live
//    set, the cycle per jurisdiction and the three refusal arms; this page
//    renders what it returns and derives nothing.
//  - NO FIGURE OF OUR OWN. No coverage count, no Pell maximum, no "families
//    left money unclaimed". A hardcoded number rots and a runtime one is a
//    sentence a family cannot act on. Figures inside a cited rule are the
//    source's, not ours, and a test asserts the difference.
//  - NOTHING ABOUT THE 2024 ROLLOUT (brief 0012 D21, which names coach copy;
//    the same reasoning binds the page). Those findings are measurements of one
//    moment, not standing rules.
//  - A DEAD LINK IS NEVER RENDERED AS A LINK (RFC 200). The status rides on
//    every row from the store; only "the page answered 404" drops the anchor.

/** `GET` path of this page. Routing, the site header and the tests all read it from here. */
const val FAFSA_PATH = "/fafsa"

/**
 * What this page calls itself, in the one place both the page value and the
 * site header read it from.
 *
 * It is a constant rather than a property of the page value because the page
 * value is BUILT PER REQUEST from the deadline read, and the shared header has
 * no deadline read to build one from.
 */
const val FAFSA_TITLE = "The FAFSA"

/**
 * What this file drops a row over, rather than faulting the whole page.
 *
 * A seeded cell we cannot print is an operator's problem and never a family's:
 * the page loses one row and says so here, where the person who can fix the
 * corpus will read it.
 */
private val LOG = LoggerFactory.getLogger("ed.unicoach.web.render.FafsaPage")

/**
 * A published page, as the explainer model cites one -- for a process rule and
 * for an agency we could not read alike, because both cite the same
 * [FafsaProcessCopy.FafsaDocument].
 */
private fun sourceOf(
  document: FafsaProcessCopy.FafsaDocument,
  fetchedOn: LocalDate,
): ExplainerSource =
  ExplainerSource(
    publisher = document.publisher,
    title = document.title,
    url = document.url,
    fetchedOn = fetchedOn,
  )

/** Every rule of one topic, in the corpus's own order, each carrying its own document. */
private fun claimsOf(topic: FafsaProcessTopic): List<ExplainerBlock> =
  FafsaProcessCopy.rulesOf(topic).map { rule -> ExplainerBlock.Claim(rule.statement, sourceOf(rule.document, rule.checkedOn)) }

/**
 * THE FREE SECTION COMES FIRST, before any instruction about filing (D6).
 *
 * The statutory rules are the corpus's. The sentence beside them is about US,
 * so it is `Prose`: there is no third-party page about unicoach to cite, and a
 * `Claim` citing one of our own pages would satisfy the citation sweep while
 * citing nothing about anyone.
 */
private val theFafsaIsFree =
  ExplainerSection(
    heading = "The FAFSA is free",
    blocks =
      claimsOf(FafsaProcessTopic.THE_FORM_IS_FREE) +
        ExplainerBlock.Prose(
          "unicoach never fills the form in for you, never submits it, and never asks you for your " +
            "StudentAid.gov login or any other password. This page is here so you can do it yourself, on " +
            "time, knowing what it asks.",
        ),
  )

private val whatTheFormAsks =
  ExplainerSection(
    heading = "What the form asks now",
    blocks = claimsOf(FafsaProcessTopic.WHO_FILES),
  )

private val divorcedOrSeparatedParents =
  ExplainerSection(
    heading = "If your parents are divorced or separated",
    blocks = claimsOf(FafsaProcessTopic.DIVORCED_OR_SEPARATED_PARENTS),
  )

private val contributorWithoutAnSsn =
  ExplainerSection(
    heading = "If a parent or a spouse has no Social Security number",
    blocks = claimsOf(FafsaProcessTopic.CONTRIBUTOR_WITHOUT_AN_SSN),
  )

private val dependency =
  ExplainerSection(
    heading = "Whether you count as dependent",
    blocks = claimsOf(FafsaProcessTopic.DEPENDENCY),
  )

private val afterYouSubmit =
  ExplainerSection(
    heading = "After you submit",
    blocks = claimsOf(FafsaProcessTopic.AFTER_YOU_SUBMIT),
  )

/** The heading over the dates, on every arm -- there is always something to say about when it is due. */
private const val WHEN_IT_IS_DUE = "When it is due"

/**
 * The column a row names its jurisdiction in.
 *
 * Named once, because two tables carry it (the states, and the ones we could
 * not read) and the test that asks whether all fifty-one reached a table ROW
 * reads the rendered cells of exactly that column.
 */
internal val JURISDICTION_COLUMN = TableColumn("Jurisdiction")

/**
 * The column every row names its own filing year in. Named once, for
 * [JURISDICTION_COLUMN]'s reason: two filing years are open at once, so the test
 * that asks whether each row carries its OWN reads these cells.
 */
internal val FILING_YEAR_COLUMN = TableColumn("Filing year")

/** The program the date belongs to, in the agency's own name for it. */
private val PROGRAM_COLUMN = TableColumn("Program")

/**
 * The column that says WHAT KIND of date a row carries.
 *
 * THE ONE COLUMN OF THESE TABLES WHOSE CELLS UNICOACH WRITES ([eventLabel]), so
 * it declares itself as ours and the page's unsourced-prose sweep reads those
 * cells instead of exempting them with the agency's own. The declaration is the
 * COLUMN's, not a set of header strings held beside it: a reworded header cannot
 * move it.
 */
private val EVENT_COLUMN = TableColumn("What this date is", authored = true)

/** The agency's own sentence about the date, verbatim. */
private val AS_PUBLISHED_COLUMN = TableColumn("What the agency says")

/** The columns a state table carries, in order. The source column is named apart, because the renderer prints it. */
private val STATE_COLUMNS =
  listOf(JURISDICTION_COLUMN, PROGRAM_COLUMN, EVENT_COLUMN, AS_PUBLISHED_COLUMN, FILING_YEAR_COLUMN)

/** The federal table names no jurisdiction, because every row of it is the same one. */
private val FEDERAL_COLUMNS = listOf(PROGRAM_COLUMN, EVENT_COLUMN, AS_PUBLISHED_COLUMN, FILING_YEAR_COLUMN)

/** The column every row prints its own citation in. */
private val SOURCE_COLUMN = TableColumn("Where we read it")

/**
 * The columns of the table for the jurisdictions whose own page we could not
 * read.
 *
 * ONE COLUMN, the jurisdiction, beside the page we tried. What WE can tell you
 * about them is one sentence that is the same for every row, and it is unicoach
 * speaking rather than an agency: it belongs in [UNREADABLE_SENTENCE] above the
 * table, where the prose sweep reads it, and not in fifty-one cells the sweep
 * is documented never to look at.
 */
private val UNREADABLE_COLUMNS = listOf(JURISDICTION_COLUMN)

/** The column those rows link what we tried in. */
private val TRIED_COLUMN = TableColumn("The page we tried")

/**
 * What kind of date a row carries, in a family's words.
 *
 * An EXHAUSTIVE `when` over `:db`'s vocabulary, so a fifth event fails this
 * file's compile rather than printing its wire code at a parent. The
 * distinction is the one families lose money on: missing a priority date is the
 * end of being first, not the end of filing.
 */
private fun eventLabel(event: AidDeadlineEvent): String =
  when (event) {
    AidDeadlineEvent.FORM_OPENS -> "The day the form opens"
    AidDeadlineEvent.PRIORITY_DEADLINE -> "File by this day to be considered first"
    AidDeadlineEvent.FINAL_DEADLINE -> "The last day to file"
    AidDeadlineEvent.CORRECTIONS_DEADLINE -> "The last day to fix a filed form"
  }

/**
 * The cells every row of both deadline tables carries, in column order --
 * everything except the jurisdiction, which only the state tables name.
 */
private fun publishedCells(deadline: FafsaDeadline): List<String> =
  listOf(
    deadline.program,
    eventLabel(deadline.event),
    // THE AGENCY'S OWN SENTENCE, verbatim, on every arm -- including the
    // one that also holds a parsed day. A family shown only our reading
    // would be attributing our reading to the agency, and a stored day
    // cannot hold "11:59 p.m. Pacific", which is the thing the state
    // actually enforces.
    deadline.deadline.deadlineAsPublished,
    deadline.awardYear.label,
  )

/**
 * A served row's own citation.
 *
 * The corpus stores no TITLE for an agency's page -- a deadline row carries the
 * publisher, the url and the day we read it -- so the citation names what that
 * page was read FOR. The rendered row prints the publisher and the date; the
 * title is here because the type asks every citation to say what it is.
 */
private fun citationOf(deadline: FafsaDeadline): ExplainerSource =
  ExplainerSource(
    publisher = deadline.publisher,
    title = deadline.program,
    url = deadline.sourceUrl,
    fetchedOn = deadline.checkedOn,
  )

/**
 * WHAT ONE PAGE BUILD COULD NOT PRINT, and what the family is told about it.
 *
 * A DROPPED ROW IS A MISSING JURISDICTION, and this page already has a voice for
 * one. Logged alone, the loss reached the operator and nobody else: a state whose
 * only row drops falls out of the table, out of `missingJurisdictions` and out of
 * [unrecordedGaps] at once, so the page would claim the fifty states and DC
 * while printing fifty.
 */
private class DroppedJurisdictions {
  private val names = linkedSetOf<String>()

  /** Records that [jurisdiction] lost a row. Insertion-ordered and de-duplicated: a state with two drops is named once. */
  fun drop(jurisdiction: String) {
    names += jurisdiction
  }

  /** What the family is told, or NOTHING at all where every row printed. */
  fun sentence(): ExplainerBlock? =
    names
      .takeIf { it.isNotEmpty() }
      ?.let { dropped ->
        ExplainerBlock.Prose(
          "We hold dates for " + dropped.joinToString(", ") +
            " that we could not print here. Ask the agency that publishes them, and the financial aid " +
            "office at each college on your list.",
        )
      }
}

/**
 * One served row as one table row, or NOTHING where the store handed us a cell
 * no page can print.
 *
 * A blank cell is a corpus defect, and the honest answer is this state's row
 * missing rather than every state's page missing: the row's guards fault at
 * RENDER, so an unguarded construction here is a branded 503 for all fifty-one
 * jurisdictions over one harvested field.
 *
 * IT ASKS THE PRINTABLE QUESTION AND DOES NOT CATCH THE ANSWER. Deciding by
 * catching [IllegalArgumentException] made a formatted sentence the control
 * flow: the caller could not say WHICH cell was blank, and any other guard in
 * the block -- ours, not the corpus's -- read as "an unprintable row". The
 * narrow `catch` that remains is for a guard this function does not know about,
 * and it logs the THROWABLE, which is the half that says which one fired.
 */
private fun citedRowOrNull(
  deadline: FafsaDeadline,
  jurisdiction: String,
  cells: List<String>,
  dropped: DroppedJurisdictions,
): CitedRow? {
  val blank = cells.withIndex().filter { (_, cell) -> cell.isBlank() }.map { it.index }
  if (cells.isEmpty() || blank.isNotEmpty()) {
    dropped.drop(jurisdiction)
    LOG.warn(
      "dropping an unprintable /fafsa row for [{}]: program=[{}] event=[{}] awardYear=[{}] source=[{}] " +
        "blank cells at positions {} of [{}]",
      jurisdiction,
      deadline.program,
      deadline.event.value,
      deadline.awardYear.label,
      deadline.sourceUrl,
      blank,
      cells.size,
    )
    return null
  }
  return try {
    CitedRow(
      cells = cells,
      source = citationOf(deadline),
      sourceUrlStatus = deadline.sourceUrlStatus,
      archive = deadline.archive,
    )
  } catch (refusal: IllegalArgumentException) {
    dropped.drop(jurisdiction)
    LOG.warn(
      "dropping a /fafsa row for [{}] that a citation or row guard refused: program=[{}] source=[{}]",
      jurisdiction,
      deadline.program,
      deadline.sourceUrl,
      refusal,
    )
    null
  }
}

/**
 * What a dropped FEDERAL row is called to the family where its own program name
 * is the cell that was blank -- which is the commonest reason such a row drops,
 * so the program cannot be trusted to name it.
 */
private const val FEDERAL_ROWS = "federal student aid"

/**
 * One federal row: [FEDERAL_COLUMNS] exactly, because every row of that table is
 * the same jurisdiction -- so a dropped one is named to the family by its
 * PROGRAM, which is the only thing that tells two federal rows apart, and by
 * [FEDERAL_ROWS] where the program is the blank cell that dropped it.
 */
private fun federalRowOf(
  deadline: FafsaDeadline,
  dropped: DroppedJurisdictions,
): CitedRow? = citedRowOrNull(deadline, deadline.program.ifBlank { FEDERAL_ROWS }, publishedCells(deadline), dropped)

/**
 * One state row: [STATE_COLUMNS] exactly.
 *
 * The state names ITSELF in the row, because a row of this table has to be
 * readable on its own once it stacks into a card on a phone -- and because a
 * builder per table is what stops one nullable cell producing two row widths
 * from one function, which the table can only catch at render.
 */
private fun stateRowOf(
  deadline: FafsaDeadline,
  jurisdictionName: String,
  dropped: DroppedJurisdictions,
): CitedRow? = citedRowOrNull(deadline, jurisdictionName, listOf(jurisdictionName) + publishedCells(deadline), dropped)

/** The federal rows, as their own table above the states. */
private fun federalTable(
  federal: List<FafsaDeadline>,
  dropped: DroppedJurisdictions,
): ExplainerBlock.CitedTable? =
  federal
    .mapNotNull { deadline -> federalRowOf(deadline, dropped) }
    .takeIf { it.isNotEmpty() }
    ?.let { rows ->
      ExplainerBlock.CitedTable(
        caption = "The federal dates",
        columns = FEDERAL_COLUMNS,
        sourceColumn = SOURCE_COLUMN,
        rows = rows,
      )
    }

/**
 * One table per region, in the order the service already sorted the states
 * into: region, then state name.
 *
 * The grouping key is the region NAME the service served, never a second
 * vocabulary of our own (gate-2 D10), and a region with no served state simply
 * has no table rather than an empty one.
 */
private fun regionTables(
  states: List<StateDeadlines>,
  dropped: DroppedJurisdictions,
): List<ExplainerBlock.CitedTable> =
  states
    .groupBy { it.region }
    .mapNotNull { (region, statesOfRegion) ->
      statesOfRegion
        // A state with several programs contributes several rows, each naming
        // the state again, because a row of this table has to be readable on
        // its own once it stacks into a card on a phone.
        .flatMap { state -> state.deadlines.mapNotNull { stateRowOf(it, state.name, dropped) } }
        // NO ROWS, NO TABLE -- the answer [federalTable] and [unreadableTable]
        // already give. A served state carrying no row is a shape the service's
        // type permits, and an empty table is one the row guard refuses at
        // RENDER, which would be a 503 for a page the service resolved.
        .takeIf { it.isNotEmpty() }
        ?.let { rows ->
          ExplainerBlock.CitedTable(
            caption = region,
            columns = STATE_COLUMNS,
            sourceColumn = SOURCE_COLUMN,
            rows = rows,
          )
        }
    }

/**
 * What we can tell a family about the jurisdictions whose own page we could not
 * read (D9), said ONCE, in unicoach's own voice.
 *
 * IT IS A `Prose` BLOCK AND NOT A CELL. A table cell is exempt from the page's
 * unsourced-prose sweep because a cell is the agency's own data, printed beside
 * the page it was read from. This sentence is ours: in a cell it would sit in
 * the one place the sweep is documented never to look, so a later edit of it
 * could carry a figure, a percentage or an unattributed quotation with nothing
 * to catch it. Above the table it is swept with the rest of our copy -- and it
 * stops being the same sentence repeated in every row of a table whose rows
 * differ only in which state they name.
 */
private const val UNREADABLE_SENTENCE =
  "For the jurisdictions below we opened the agency's own page and could not get a deadline out of it. We " +
    "claim no date for them: ask that agency, and ask the financial aid office at each college on your list."

/**
 * The jurisdictions we hold nothing for, and the page we tried (D9).
 *
 * The row names the agency and links what we tried, which is what D9 asks the
 * page to carry: no date is claimed for these, and the citation is evidence for
 * "here is the page we tried" rather than for a deadline. A gap with no
 * recorded agency gets the shorter sentence of [unrecordedGaps] and no row here
 * -- a named agency we did not actually try would be worse than saying less.
 */
private fun unreadableTable(rows: List<CitedRow>): ExplainerBlock.CitedTable? =
  rows
    .takeIf { it.isNotEmpty() }
    ?.let { gapRows ->
      ExplainerBlock.CitedTable(
        caption = "The ones we could not read",
        // The one column is the jurisdiction's own name, which is the
        // codebook's word and not ours, so it declares itself unauthored.
        columns = UNREADABLE_COLUMNS,
        sourceColumn = TRIED_COLUMN,
        rows = gapRows,
      )
    }

/**
 * One gap as one cited row, or NOTHING where the codebook handed us a name no
 * page can print.
 *
 * THE SAME CONTRACT THE DEADLINE ROWS HAVE. This loop built its rows unguarded,
 * so one blank `us_states.name` threw out of the responder and rendered the
 * branded 503 for all fifty-one jurisdictions -- the exact harm the sibling
 * builder exists to prevent, on the path whose cells are LESS controlled.
 */
private fun unreadableRowOrNull(
  gap: MissingJurisdiction,
  agency: FafsaUnreadableAgencies.UnreadableAgency,
): CitedRow? =
  try {
    CitedRow(
      cells = listOf(gap.name),
      source = sourceOf(agency.document, agency.checkedOn),
      sourceUrlStatus = agency.urlStatus,
    )
  } catch (refusal: IllegalArgumentException) {
    LOG.warn(
      "dropping an unprintable /fafsa gap row for [{}] cited to [{}]",
      gap.jurisdiction.uspsCode,
      agency.url,
      refusal,
    )
    null
  }

/**
 * The gaps split by what the page can say about each: the rows of the cited
 * table, and the jurisdictions it can only name.
 *
 * ONE SPLIT, so a gap cannot fall out of both halves. A gap with no recorded
 * agency and a gap whose row would not construct are the same answer to a
 * family -- we hold nothing, and we cannot name the page we tried.
 */
private data class SplitGaps(
  val rows: List<CitedRow>,
  val named: List<String>,
)

private fun splitGaps(missing: List<MissingJurisdiction>): SplitGaps {
  val rows = mutableListOf<CitedRow>()
  val named = mutableListOf<String>()
  missing.forEach { gap ->
    val row = gap.agency?.let { agency -> unreadableRowOrNull(gap, agency) }
    if (row == null) named += gap.name else rows += row
  }
  return SplitGaps(rows = rows, named = named)
}

/** Our sentence, then the table it is about -- or nothing at all, when there is no such jurisdiction. */
private fun unreadableBlocks(gaps: SplitGaps): List<ExplainerBlock> =
  unreadableTable(gaps.rows)
    ?.let { table -> listOf(ExplainerBlock.Prose(UNREADABLE_SENTENCE), table) }
    ?: emptyList()

/** A jurisdiction we hold nothing for and can name no page we tried for -- named, never silently dropped. */
private fun unrecordedGaps(gaps: SplitGaps): ExplainerBlock? =
  gaps.named
    .takeIf { it.isNotEmpty() }
    ?.let { names ->
      ExplainerBlock.Prose(
        "We hold no dates at all for " + names.joinToString(", ") +
          ", and no note of what we tried. Ask that state's own aid agency, and the financial aid office at " +
          "each college on your list.",
      )
    }

/**
 * WHAT THIS PAGE DOES NOT COVER (D13), named here so the test that asserts the
 * sentence reaches a family reads the sentence itself rather than keeping a
 * second copy of it.
 *
 * It is the page's only statement about Puerto Rico, Guam and the rest: with no
 * test against this constant, a copy edit could delete the page's own bound and
 * leave a family reading the table as the whole country.
 */
internal const val TERRITORIES_SENTENCE =
  "This covers the fifty states and the District of Columbia. We have not gathered deadlines for Puerto " +
    "Rico, Guam, the US Virgin Islands or the other territories, so we say nothing about them here rather " +
    "than leave you to guess."

/** The sentences that bound the table: the two open years, the territories, and the colleges' own dates. */
private val TABLE_BOUNDS =
  listOf(
    ExplainerBlock.Prose(
      "Two filing years are open at once for part of the year, so the same state can publish two sets of " +
        "dates. Every row above says which year it belongs to; read that before you read the date.",
    ),
    ExplainerBlock.Prose(TERRITORIES_SENTENCE),
    ExplainerBlock.Prose(
      "Colleges set their own priority dates for their own aid, and those are often earlier than a state's. " +
        "They are not here. Ask the financial aid office at each college on your list for theirs.",
    ),
  )

/** The sentence that says why the state dates matter more than the federal one, above the tables. */
private const val DEADLINE_LEAD =
  "The federal deadline is late. The deadlines that decide whether you get your own state's money are " +
    "usually much earlier, and they are the ones families miss. Each row below is the agency's own " +
    "wording, with the page we read it from and the day we read it."

/**
 * The table section, or NOTHING where not one row survived.
 *
 * NULL IS A BRANCH THIS SECTION OWES. Both table builders may answer nothing --
 * that is their documented right -- and the three sentences that bound the table
 * all speak about "every row above". With no table at all the page printed an
 * "As of" date and "Each row below is the agency's own wording" over no rows,
 * which is the one thing a refusal exists to withhold.
 */
private fun deadlineSection(resolved: FafsaDeadlines.Resolved): ExplainerSection? {
  val dropped = DroppedJurisdictions()
  val tables = listOfNotNull(federalTable(resolved.federal, dropped)) + regionTables(resolved.states, dropped)
  if (tables.isEmpty()) {
    LOG.warn("every /fafsa deadline row was unprintable for a resolved answer asOf=[{}]", resolved.asOf)
    return null
  }
  val gaps = splitGaps(resolved.missingJurisdictions)
  return ExplainerSection(
    heading = WHEN_IT_IS_DUE,
    blocks =
      listOf(ExplainerBlock.Prose(DEADLINE_LEAD)) + tables +
        unreadableBlocks(gaps) +
        listOfNotNull(unrecordedGaps(gaps), dropped.sentence()) + TABLE_BOUNDS,
  )
}

/**
 * WHY there is no table, in a family's words.
 *
 * An exhaustive `when`, so a fourth reason is a compile error rather than a
 * page that renders a blank where a sentence should be. None of the three says
 * anything about seeds, corpora or award cycles: what the operator has to fix
 * is already in the service's own log, and it is not a family's problem.
 *
 * INTERNAL, so a test asks WHICH sentence an arm says rather than finding the
 * block by a five-word prefix of the page's own copy -- a lookup key nothing
 * declared, and one a copy edit to any arm would have broken.
 */
internal fun fafsaRefusalSentence(reason: NoDeadlinesReason): String =
  when (reason) {
    NoDeadlinesReason.EMPTY -> {
      "We are not showing the dates right now, because we do not hold any."
    }

    NoDeadlinesReason.SUPERSEDED_CYCLE -> {
      "We are not showing the dates right now: the ones we hold are for a year that has already been filed, " +
        "and last year's deadline is not this year's."
    }

    NoDeadlinesReason.EXPIRED -> {
      "We are not showing the dates right now: the ones we hold have not been checked recently enough for us " +
        "to stand behind them."
    }
  }

/** The table section's replacement on a refusal: the table is ABSENT, not empty, and it says where to ask. */
private fun refusalSection(reason: NoDeadlinesReason): ExplainerSection =
  ExplainerSection(
    heading = WHEN_IT_IS_DUE,
    blocks =
      listOf(
        ExplainerBlock.Prose(fafsaRefusalSentence(reason)),
        ExplainerBlock.Prose(
          "Your own state's aid agency publishes its deadline, and the financial aid office at each college on " +
            "your list will know it. Ask them rather than trusting a date we are not sure of. Everything else " +
            "on this page is the law and the form itself, and does not change with the date.",
        ),
      ),
  )

/**
 * Every organisation this page speaks about by name, for the leak sweep.
 *
 * unicoach itself is the one exemption, as it is on the other explainer: a
 * claim about our own business has no third-party page to cite. The state
 * agencies are not here because they are never spoken outside a table row,
 * where each one names itself beside its own data.
 */
val FAFSA_NAMED_THIRD_PARTIES: Set<String> =
  setOf(
    "Federal Student Aid",
    "Department of Education",
  )

private const val FAFSA_STANDFIRST =
  "The FAFSA is free, and the deadlines that decide whether you get your own state's money are earlier than " +
    "the federal one. This page holds the federal dates and every state's, in each agency's own words, with " +
    "the page we read and the day we read it."

/**
 * The page, built from one deadline read.
 *
 * A FUNCTION AND NOT A CONSTANT, because the centre of this page is data: the
 * table is the service's answer, and the three refusal arms are pages in their
 * own right rather than an error state. Everything around the table is fixed
 * and is shared by all four.
 */
fun fafsaPage(deadlines: FafsaDeadlines): ExplainerPage {
  val answer = answerOf(deadlines)
  return ExplainerPage(
    title = FAFSA_TITLE,
    standfirst = FAFSA_STANDFIRST,
    asOf = answer.asOf,
    sections =
      listOf(
        theFafsaIsFree,
        whatTheFormAsks,
        answer.section,
        divorcedOrSeparatedParents,
        contributorWithoutAnSsn,
        dependency,
        afterYouSubmit,
      ),
    namedThirdParties = FAFSA_NAMED_THIRD_PARTIES,
  )
}

/** What the deadline read decides: the date the page stands behind, and the section that carries it. */
private data class DeadlineAnswer(
  val asOf: LocalDate?,
  val section: ExplainerSection,
)

/**
 * The date and the section, decided TOGETHER by ONE exhaustive `when`.
 *
 * The date used to be read with an `as?` cast beside an exhaustive `when` on the
 * same value -- a catch-all over a sealed type, which is exactly what the `when`
 * two lines below it existed to prevent: a third `FafsaDeadlines` case was
 * answered there by silence. Decided here, the date and the section can never
 * disagree about which arm fired.
 */
private fun answerOf(deadlines: FafsaDeadlines): DeadlineAnswer =
  when (deadlines) {
    is FafsaDeadlines.Resolved -> {
      deadlineSection(deadlines)
        ?.let { section -> DeadlineAnswer(deadlines.asOf, section) }
        // RESOLVED, AND NOTHING SURVIVED TO PRINT. The page's own answer for
        // "we have nothing to show you" is the refusal, and NO date: the rows
        // exist but we could not print one, so standing behind a reading date
        // would be standing behind a table that is not there.
        ?: DeadlineAnswer(null, refusalSection(NoDeadlinesReason.EMPTY))
    }

    // A refusal carries no date, so the page prints no "As of" line on one.
    is FafsaDeadlines.NoDeadlines -> {
      DeadlineAnswer(null, refusalSection(deadlines.reason))
    }
  }

/**
 * `GET /fafsa`. No account, no cookie, no form, nothing collected.
 *
 * A FAILED READ IS RETHROWN, so `StatusPages` renders the branded 503. The page
 * has three arms for "we have no dates to show you" and every one of them is a
 * statement the service made deliberately; a database fault is not one of them,
 * and dressing it as one would tell a family something we do not know.
 */
suspend fun ApplicationCall.respondFafsaPage(
  deadlines: FafsaDeadlineSource,
  nav: ShellNav = ShellNav.SignedOut,
) {
  val answer = deadlines.get().getOrThrow()
  // WIDE, the token the account page already uses: a six-column table of
  // fifty-one jurisdictions at the prose measure is unreadable.
  respondExplainerPage(fafsaPage(answer), nav, width = ShellWidth.WIDE)
}
