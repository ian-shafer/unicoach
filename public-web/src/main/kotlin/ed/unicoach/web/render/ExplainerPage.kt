package ed.unicoach.web.render

import ed.unicoach.common.util.UsDate
import ed.unicoach.common.util.requireCitedPageUrl
import ed.unicoach.db.models.ArchivedCitation
import ed.unicoach.db.models.SourceUrlStatus
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import kotlinx.html.FlowContent
import kotlinx.html.TBODY
import kotlinx.html.THEAD
import kotlinx.html.TR
import kotlinx.html.ThScope
import kotlinx.html.a
import kotlinx.html.caption
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.sup
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import kotlinx.html.ul
import java.time.LocalDate

// The explainer surface (RFC 204): a family-facing page that argues from other
// companies' published pages, so the page's own honesty is a STRUCTURAL
// property rather than a convention.
//
// The content is a typed value, not Markdown and not hand-written HTML. That is
// the whole point: `Claim` cannot exist without an `ExplainerSource`, so a
// load-bearing sentence with no URL and no fetch date DOES NOT COMPILE. A test
// can then walk the value and prove what a rendered string can never prove.
//
// This file is the only place that knows HTML. Page content lives in its own
// file (`WhoPaysPage.kt`), so a copy edit never touches the renderer.

/** A published page we read, and the date we read it. */
data class ExplainerSource(
  val publisher: String,
  val title: String,
  val url: String,
  val fetchedOn: LocalDate,
) {
  init {
    // The bar lives in the TYPE, not in one page's test, so the second
    // explainer cannot be written to a lower standard than the first.
    require(publisher.isNotBlank()) { "a source with no publisher: [$url]" }
    require(title.isNotBlank()) { "a source with no page title: [$url]" }
    // ABSOLUTE, AND NOT https-ONLY (RFC 238 tier 2). The rule has one home in
    // `:common` ([requireCitedPageUrl]); an https-only bar here silently
    // DELETED a served state from `/fafsa`, because four Tennessee rows cite
    // `http://collegefortn.org/...` and the store accepts http by an explicit
    // decision. A page's citation bar cannot be stricter than the corpus it
    // renders: the row does not fail loudly, it disappears.
    requireCitedPageUrl(url, "a source")
  }
}

/**
 * One block of page body. Every block carries [text]; what differs is whether
 * that text is allowed to make a claim about somebody else.
 */
sealed interface ExplainerBlock {
  val text: String

  /** Connective prose in unicoach's own voice. Names no third party. */
  data class Prose(
    override val text: String,
  ) : ExplainerBlock

  /** A load-bearing claim. Cannot exist without a source: RFC 204 D14(a) is the type. */
  data class Claim(
    override val text: String,
    val source: ExplainerSource,
  ) : ExplainerBlock

  /**
   * Prose about unicoach that ends in a link to one of OUR OWN pages.
   *
   * ADDED to the RFC's two-case model, deliberately and narrowly, because D-E
   * requires the `/privacy` and `/terms` claims about us to be checkable by
   * following a link, and neither `Prose` (no link) nor `Claim` (a fetched
   * third-party URL and a fetch date, which an internal page has neither of)
   * can carry an internal one honestly. [target] is a CLOSED set of our own
   * pages, so this case cannot smuggle an outbound link into the body prose —
   * that is a compile error, not a test failure — and the third-party sweep
   * still demands `Claim` for any named company.
   *
   * It is deliberately NOT merged into `Claim` under a shared "citation" type.
   * The whole strength of the D-B sweep is that a named company may appear only
   * inside a `Claim`, and a `Claim` is worth that trust only because its source
   * is a page belonging to the company it is about. A claim citing `/privacy`
   * would satisfy the sweep while citing nothing about anyone.
   */
  data class SelfCited(
    override val text: String,
    val target: SitePath,
    val linkText: String,
  ) : ExplainerBlock

  /**
   * A table of published data, EVERY ROW CARRYING ITS OWN CITATION.
   *
   * ADDED for RFC 238, whose centre is a table of FAFSA dates for all 51
   * jurisdictions. It is a case of its own rather than prose because the guard
   * that makes [Claim] worth trusting has to apply PER ROW: a row that cannot
   * name where it came from does not construct, so half a table cannot quietly
   * become unsourced while the block still carries one source.
   *
   * ITS ROWS DO NOT JOIN THE PAGE'S FOOTNOTES, and that is a decision and not
   * an omission. The numbered list at the foot is the documents the page's
   * PROSE cites; eighty-odd agency pages would drown a list of fourteen, two
   * rows of one agency read on different days would fault the one-reading-per-
   * url rule, and a family scanning for its own state wants the agency's name
   * beside its own row rather than a number to go and look up.
   *
   * [text] is the caption, so the page-level walks that read a block's text
   * read something true about this one.
   */
  @ConsistentCopyVisibility
  data class CitedTable private constructor(
    val caption: String,
    /** The data columns, in order. Every row carries exactly this many cells. */
    val columns: List<TableColumn>,
    /** The column each row's own citation is printed in. */
    val sourceColumn: TableColumn,
    val rows: List<CitedRow>,
  ) : ExplainerBlock {
    override val text: String get() = caption

    init {
      require(caption.isNotBlank()) { "a cited table must carry a caption" }
      require(columns.isNotEmpty()) { "a cited table with no columns: [$caption]" }
      // EVERY RENDERED HEADER, the citation column included. A header is a
      // `<th>` AND a stacked cell's `data-label`, so two columns of one name
      // label two different values identically on a phone -- and a citation
      // column spelled like a data column makes the two indistinguishable to
      // anything reading the rendered cells back. [TableColumn] already refuses
      // a blank one.
      val headers = headers()
      require(headers.distinct().size == headers.size) {
        "a cited table names one header twice: [$caption] [$headers]"
      }
      // An EMPTY table is a heading over nothing. RFC 238's rule for a table it
      // has no rows for is that the table is ABSENT, not empty, so the absence
      // is decided by the page rather than rendered as a blank promise.
      require(rows.isNotEmpty()) { "a cited table with no rows: [$caption]" }
      rows.forEach { row ->
        require(row.cells.size == columns.size) {
          "a row of [$caption] carries [${row.cells.size}] cells for [${columns.size}] columns: [${row.cells}]"
        }
      }
    }

    /** Every header this table renders, in render order: the data columns, then the citation column. */
    fun headers(): List<String> = (columns + sourceColumn).map { it.header }

    /** The cells of the columns UNICOACH authored -- our own words, and no publisher's. */
    fun authoredCells(): List<String> =
      columns
        .withIndex()
        .filter { (_, column) -> column.authored }
        .flatMap { (index, _) -> rows.map { it.cells[index] } }

    companion object {
      /**
       * The one way to build a table: its columns and its rows are COPIED, so
       * the guards above are about state this value owns. `List` is a read-only
       * VIEW and not an immutable value -- a caller holding the `MutableList` it
       * passed could otherwise shorten it after the arity guard had passed, and
       * [authoredCells] indexes those cells by position.
       */
      operator fun invoke(
        caption: String,
        columns: List<TableColumn>,
        sourceColumn: TableColumn,
        rows: List<CitedRow>,
      ): CitedTable = CitedTable(caption, columns.toList(), sourceColumn, rows.toList())
    }
  }
}

/**
 * One column of a [ExplainerBlock.CitedTable]: the header it prints, and whose
 * words the cells under it are.
 *
 * A COLUMN IS A VALUE, NOT THE TEXT OF ITS HEADER. The set of columns unicoach
 * authored used to be declared by MATCHING header strings, which made column
 * identity display copy: rewording a header either moved the page's
 * unsourced-prose exemption to another column or faulted the table at render --
 * a branded 503 earned by a copy edit. Here the exemption travels with the
 * column, so a header is only ever words a family reads.
 */
data class TableColumn(
  val header: String,
  /**
   * True where UNICOACH wrote the cells under this header rather than reading
   * them from the publisher.
   *
   * A table's exemption from the page's unsourced-prose sweep is argued for the
   * publisher's own data, printed beside the page it came from. A column of ours
   * -- a label of our own over somebody's date -- is not that, so
   * [ExplainerBlock.CitedTable.authoredCells] hands those cells to the sweep
   * with the rest of our copy. False for a column of the publisher's own words.
   */
  val authored: Boolean = false,
) {
  init {
    require(header.isNotBlank()) { "a table column must carry a header" }
  }
}

/**
 * One row of a [ExplainerBlock.CitedTable]: its cells, and the page it came
 * from.
 *
 * [source] is a non-nullable positional parameter for [ExplainerBlock.Claim]'s
 * reason: an uncited row is an arity error rather than a test failure.
 */
@ConsistentCopyVisibility
data class CitedRow private constructor(
  /** The rendered cells, in column order. COPIED at construction, for [ExplainerBlock.CitedTable]'s reason. */
  val cells: List<String>,
  val source: ExplainerSource,
  /**
   * What the link sweep last answered for [source]'s address (RFC 200).
   *
   * THE VERDICT AND NOT A RENDERING OF IT. Whether a verdict earns an anchor is
   * [SourceUrlStatus.rendersAsAnchor]'s rule, asked where the anchor is drawn:
   * a row carrying a Boolean would have spent that rule at the boundary and
   * thrown the reason away, so nothing downstream could say WHY a link is
   * absent -- "the page answered 404" and "we never asked" arrive here as one
   * `false`.
   */
  val sourceUrlStatus: SourceUrlStatus,
  /**
   * The archived address this row was read THROUGH, with the sweep's verdict on
   * THAT address -- ONE value, exactly as the store returns it
   * ([ArchivedCitation]). Null for a row that was read through no archive.
   *
   * Held as a url alone, three domain states collapsed into one nullable
   * string: never archived, archived and alive, archived and a grave. The
   * address rule is [ArchivedCitation]'s own guard, so this row states none of
   * it.
   */
  val archive: ArchivedCitation? = null,
) {
  init {
    require(cells.isNotEmpty()) { "a cited row with no cells: [${source.url}]" }
    require(cells.none { it.isBlank() }) { "a cited row carries a blank cell: [${source.url}] [$cells]" }
  }

  companion object {
    /**
     * The one way to build a row: the cells are COPIED, so no caller keeps a
     * handle that can blank or shorten them once the guards above have passed.
     */
    operator fun invoke(
      cells: List<String>,
      source: ExplainerSource,
      sourceUrlStatus: SourceUrlStatus,
      archive: ArchivedCitation? = null,
    ): CitedRow = CitedRow(cells.toList(), source, sourceUrlStatus, archive)
  }
}

/**
 * The pages of this site an explainer may cite for a claim about unicoach.
 *
 * Closed on purpose: an explainer cites our own pages to make a claim about US
 * checkable, so the destination must be a page we publish. A third party is
 * cited through [ExplainerSource], with a fetch date.
 */
enum class SitePath(
  val value: String,
) {
  PRIVACY("/privacy"),
  TERMS("/terms"),
}

/** One headed run of blocks. */
data class ExplainerSection(
  val heading: String,
  val blocks: List<ExplainerBlock>,
)

/**
 * A whole explainer page. [asOf] is printed under the title, because every
 * figure quoted below sits on somebody else's pricing page and will drift.
 */
data class ExplainerPage(
  val title: String,
  val standfirst: String,
  /**
   * The day the page's dated content was read, printed under the title -- and
   * NULL for a page that has no dated content to stand behind.
   *
   * Nullable since RFC 238, whose `/fafsa` page renders on the deadline
   * service's three refusal arms. A refusal carries no date at all, by design,
   * so the honest rendering is no "As of" line rather than today's date
   * printed over a table that is not there -- which is exactly the sentence a
   * refusal exists to withhold.
   */
  val asOf: LocalDate?,
  val sections: List<ExplainerSection>,
  /**
   * Every company this page speaks about by name — the subject of
   * [thirdPartyLeaks]. It sits on the PAGE, not in one page's content file, so
   * the next explainer inherits the guard along with the renderer.
   */
  val namedThirdParties: Set<String>,
)

/** A passage unicoach wrote itself, and where on the page it came from. */
data class UnsourcedPassage(
  val origin: String,
  val text: String,
)

/** A named company spoken where no source stands behind the sentence. */
data class ThirdPartyLeak(
  val company: String,
  val origin: String,
  val excerpt: String,
)

/** The heading of the generated sources list. */
private const val SOURCES_HEADING = "Sources"

/** How a table row says when we read the page it cites. */
private const val AS_WE_READ_IT = "as we read it on"

/**
 * How the masthead opens the line that says when the page's dated content was
 * read.
 *
 * INTERNAL, because the RENDERED TEXT is the contract a test may assert: a page
 * that prints no such line proves it by the absence of this sentence, not by
 * the absence of a CSS class it does not own.
 */
internal const val AS_OF_PREFIX = "As of "

/** What a row's archived address is called, where the agency's own page opens for no client we have. */
private const val ARCHIVE_LINK_TEXT = "archived copy"

/**
 * How much of an offending passage a leak report quotes back: enough to find
 * the sentence on the page, not so much that a failure prints a paragraph.
 */
private const val LEAK_EXCERPT_LENGTH = 80

/** Every [ExplainerBlock] on the page, in reading order. The one walk both the renderer and the tests use. */
fun ExplainerPage.blocks(): List<ExplainerBlock> = sections.flatMap { it.blocks }

/**
 * The sources of this page, deduplicated by URL, in FIRST-REFERENCE order.
 *
 * Derived, never typed: the sources list at the foot and the `[n]` markers in
 * the body are two renderings of this one list, so an orphan in either
 * direction is not expressible. One URL may carry only ONE reading — a second
 * one with a different title or fetch date would make the printed date belong
 * to a reading the reader cannot see, so it is refused rather than deduplicated
 * away.
 */
fun ExplainerPage.sourcesInReferenceOrder(): List<ExplainerSource> =
  blocks()
    .flatMap { it.referencedSources() }
    .also { cited ->
      cited.groupBy { it.url }.forEach { (url, readings) ->
        require(readings.distinct().size == 1) { "one URL cited with two different readings: [$url]" }
      }
    }.distinct()

/**
 * The sources of [this] block that belong in the PAGE-LEVEL reference list.
 *
 * The `when` is EXHAUSTIVE and replaces a `filterIsInstance<Claim>()`, which
 * was the one silent trap in this file: a case carrying a source that the
 * filter did not name never reached the marker map, and `ReferenceMarkers.get`
 * then threw at RENDER time — a branded 503 for a page that compiled clean. A
 * fifth block case is now a compile error here, and answering it is a decision.
 *
 * A [ExplainerBlock.CitedTable] answers `emptyList()` DELIBERATELY: its rows
 * cite themselves in the row (the case's own KDoc argues why), so the page's
 * numbered list stays the documents its prose cites.
 */
private fun ExplainerBlock.referencedSources(): List<ExplainerSource> =
  when (this) {
    is ExplainerBlock.Claim -> listOf(source)
    is ExplainerBlock.Prose -> emptyList()
    is ExplainerBlock.SelfCited -> emptyList()
    is ExplainerBlock.CitedTable -> emptyList()
  }

/**
 * The `[n]` markers of a page, keyed by source URL and carrying their brackets.
 *
 * Built ONCE per render and read by both the body and the sources list, so the
 * two cannot disagree about a number.
 */
@JvmInline
value class ReferenceMarkers(
  private val byUrl: Map<String, String>,
) {
  /**
   * The marker for [source].
   *
   * A source absent from this map is not a marker of `[0]`; it is a source no
   * claim on this page cites, which [sourcesInReferenceOrder] makes
   * unconstructable. So it faults rather than printing a reference to nothing.
   */
  operator fun get(source: ExplainerSource): String = byUrl.getValue(source.url)
}

/** The one place the reference notation is written. */
private fun formatMarker(index: Int): String = "[${index + 1}]"

private fun ExplainerPage.referenceMarkers(): ReferenceMarkers =
  ReferenceMarkers(
    sourcesInReferenceOrder()
      .withIndex()
      .associate { (index, source) -> source.url to formatMarker(index) },
  )

/**
 * The strings in this block that unicoach wrote itself.
 *
 * The `when` is EXHAUSTIVE on purpose. A fourth block case carrying
 * author-written text would otherwise be swept up by a `!is Claim` filter and
 * have its own fields silently ignored — it would escape both guards below with
 * no compile error. Here it cannot be added without a decision.
 */
private fun ExplainerBlock.unsourcedPassages(): List<UnsourcedPassage> =
  when (this) {
    // A claim's words are the source's, and the source is printed beside them.
    is ExplainerBlock.Claim -> {
      emptyList()
    }

    is ExplainerBlock.Prose -> {
      listOf(UnsourcedPassage("a Prose block", text))
    }

    is ExplainerBlock.SelfCited -> {
      listOf(
        UnsourcedPassage("a SelfCited block", text),
        UnsourcedPassage("a self-citation link", linkText),
      )
    }

    // A TABLE'S PUBLISHED CELLS ARE SOURCED DATA, exactly as a claim's words
    // are, and every row names the page it was read from beside itself. That is
    // the `Claim` arm applied per row, and it is what keeps the page's own prose
    // guard — no digit, no percentage, no quotation mark in unicoach's own
    // copy — writable on a page carrying ninety-odd published dates. Sweeping
    // those cells would not find a leak; it would only make the guard
    // unwritable and so delete it.
    //
    // THE EXEMPTION STOPS THERE. The caption, the column headers and the cells
    // of the columns the table DECLARES as ours are unicoach's own words, and
    // are swept exactly as a `Prose` sentence is: a table is otherwise the one
    // place on the page a later copy edit could put a figure, a percentage or
    // an unattributed quotation with nothing to catch it.
    is ExplainerBlock.CitedTable -> {
      listOf(UnsourcedPassage("a table caption", caption)) +
        headers().map { UnsourcedPassage("a header of [$caption]", it) } +
        authoredCells().map { cell -> UnsourcedPassage("an authored cell of [$caption]", cell) }
    }
  }

private fun ExplainerSection.unsourcedPassages(): List<UnsourcedPassage> =
  listOf(UnsourcedPassage("a section heading", heading)) + blocks.flatMap { it.unsourcedPassages() }

/**
 * Every string on the page unicoach wrote itself, paired with where it came
 * from: the title, the standfirst, each section heading, each non-`Claim` block
 * and the link text of a self-citation.
 *
 * This is the set with NO source behind it, which is what makes it the subject
 * of both [thirdPartyLeaks] and the page's own no-figures guard.
 */
fun ExplainerPage.unsourcedPassages(): List<UnsourcedPassage> =
  listOf(
    UnsourcedPassage("the page title", title),
    UnsourcedPassage("the standfirst", standfirst),
  ) + sections.flatMap { it.unsourcedPassages() }

/**
 * Every place a [ExplainerPage.namedThirdParties] company is spoken with no
 * source behind it.
 *
 * Empty is the only acceptable answer, and a test says so. The walk counts the
 * BLOCK TYPE rather than a call spelling, so the same sentence written a
 * different way cannot slip past it.
 */
fun ExplainerPage.thirdPartyLeaks(): List<ThirdPartyLeak> =
  unsourcedPassages().flatMap { unsourced ->
    namedThirdParties
      .filter { company -> unsourced.text.contains(company) }
      .map { company -> ThirdPartyLeak(company, unsourced.origin, unsourced.text.take(LEAK_EXCERPT_LENGTH)) }
  }

/**
 * Renders [page] through the shared [siteLayout], like every other public-web
 * page.
 *
 * [width] defaults to the prose measure every explainer rendered at before
 * RFC 238. A page whose centre is a five-column table of 51 jurisdictions asks
 * for [ShellWidth.WIDE] instead — the token the account page already uses, not
 * a new one.
 */
suspend fun ApplicationCall.respondExplainerPage(
  page: ExplainerPage,
  nav: ShellNav = ShellNav.SignedOut,
  width: ShellWidth = ShellWidth.PROSE,
) {
  respondHtml {
    siteLayout(page.title, width = width, nav = nav) {
      val markers = page.referenceMarkers()
      section("guide") {
        renderMasthead(page)
        page.sections.forEach { pageSection -> renderSection(markers, pageSection) }
        renderSources(page, markers)
      }
    }
  }
}

private fun FlowContent.renderMasthead(page: ExplainerPage) {
  h1 { +page.title }
  // RFC 204 D14(c): a visible "as of", because a competitor's pricing page drifts.
  // The shape comes from the one date seam (RFC 227, brief 0010 D21): this page
  // is read by families, not by machines.
  //
  // ABSENT, not today's date, where the page has no dated content (RFC 238):
  // the line says when what is below it was read, and a page with nothing
  // below it has nothing to say.
  page.asOf?.let { asOf -> p("guide-as-of") { +"$AS_OF_PREFIX${UsDate.of(asOf)}" } }
  p("guide-standfirst") { +page.standfirst }
}

private fun FlowContent.renderSection(
  markers: ReferenceMarkers,
  pageSection: ExplainerSection,
) {
  h2 { +pageSection.heading }
  pageSection.blocks.forEach { block -> renderBlock(markers, block) }
}

/**
 * One block: its text, then whatever stands behind that text.
 *
 * A `when` AND NOT ONE `p { }` FOR EVERY BLOCK. A `<table>` inside a `<p>` is
 * invalid HTML — the parser auto-closes the paragraph before the table, so the
 * markup a browser builds is not the markup written — and nothing in the
 * compiler says so. The three text cases keep exactly the paragraph they had.
 */
private fun FlowContent.renderBlock(
  markers: ReferenceMarkers,
  block: ExplainerBlock,
) {
  when (block) {
    is ExplainerBlock.CitedTable -> {
      renderCitedTable(block)
    }

    is ExplainerBlock.Prose, is ExplainerBlock.Claim, is ExplainerBlock.SelfCited -> {
      p {
        +block.text
        renderCitation(markers, block)
      }
    }
  }
}

/**
 * A table of published data, each row naming its own page.
 *
 * The markup is the shape the Family Cost Report's table already uses, because
 * the stylesheet's phone breakpoint is written against it: `<th scope>` for
 * every header, and a `data-label` on every data cell so a stacked row can
 * re-label its own values. A five-column, 51-row table is unreadable at 320px
 * as a table, and this is how this codebase already answers that.
 *
 * The citation is the LAST cell of the row rather than a marker into the list
 * at the foot: this page's table carries more documents than the list could
 * hold, and a family scanning for its own state wants the agency's name in
 * front of it.
 */
private fun FlowContent.renderCitedTable(cited: ExplainerBlock.CitedTable) {
  table("$DATA_TABLE_CLASS guide-table") {
    caption { +cited.caption }
    thead { citedHeaderRow(cited) }
    tbody { cited.rows.forEach { row -> citedBodyRow(cited, row) } }
  }
}

/** Every data column, then the column each row's own citation is printed in. */
private fun THEAD.citedHeaderRow(cited: ExplainerBlock.CitedTable) {
  tr { cited.headers().forEach { header -> th(scope = ThScope.col) { +header } } }
}

/** One row: its cells under their own columns, then its citation under the citation column. */
private fun TBODY.citedBodyRow(
  cited: ExplainerBlock.CitedTable,
  row: CitedRow,
) {
  tr {
    row.cells.forEachIndexed { index, cell -> labelledCell("guide-cell", cited.columns[index].header, cell) }
    td("guide-cell guide-cell-source") {
      attributes[DATA_LABEL] = cited.sourceColumn.header
      renderRowCitation(row)
    }
  }
}

/**
 * One cell, carrying the header it sits under so a stacked phone card can
 * re-label its own value (`content: attr(data-label)` in `site.css`).
 */
private fun TR.labelledCell(
  classes: String,
  label: String,
  cell: String,
) {
  td(classes) {
    attributes[DATA_LABEL] = label
    +cell
  }
}

/**
 * One row's own citation: who published the page, the day we read it, and the
 * archived address where we had to read it through one.
 *
 * `rel="nofollow noopener"` and no `target`, for the reason the sources list
 * gives. A publisher whose page is GONE is printed as TEXT and not as an
 * anchor (RFC 200) — the name is still what a family needs to go and ask, and
 * a link to a grave is worse than no link.
 */
private fun FlowContent.renderRowCitation(row: CitedRow) {
  // THE ONE PREDICATE, asked here and never carried pre-answered: both
  // addresses of a row go through `SourceUrlStatus.rendersAsAnchor`, so the
  // page states no part of that rule and an archived grave is withheld by the
  // same sentence that withholds a dead source.
  if (row.sourceUrlStatus.rendersAsAnchor) {
    outboundLink(row.source.url, row.source.publisher)
  } else {
    +row.source.publisher
  }
  +", $AS_WE_READ_IT ${UsDate.of(row.source.fetchedOn)}"
  row.archive?.takeIf { it.urlStatus.rendersAsAnchor }?.let { archive ->
    +" ("
    outboundLink(archive.url, ARCHIVE_LINK_TEXT)
    +")"
  }
}

/**
 * An anchor at a page we do not control.
 *
 * `rel="nofollow noopener"` and no `target`: the page argues FROM these pages,
 * it does not endorse them, and a new tab is the reader's choice to make. ONE
 * home, because an outbound anchor written without the `rel` hands a third
 * party a referrer and nothing in the compiler says so -- only a regex sweep
 * over the rendered bytes, which is a guard and not a design.
 */
private fun FlowContent.outboundLink(
  href: String,
  text: String,
) {
  a(href = href) {
    rel = "nofollow noopener"
    +text
  }
}

/**
 * What stands behind a block.
 *
 * A claim prints a small reference marker — NOT an inline outbound link. A
 * parent reads an argument, not a field of blue; every URL is still on the
 * page, in the list at the foot, which is what D14(a) asks for.
 */
private fun FlowContent.renderCitation(
  markers: ReferenceMarkers,
  block: ExplainerBlock,
) {
  when (block) {
    is ExplainerBlock.Prose -> {
      Unit
    }

    is ExplainerBlock.Claim -> {
      sup("guide-ref") { +markers[block.source] }
    }

    is ExplainerBlock.SelfCited -> {
      +" "
      a(href = block.target.value) { +block.linkText }
    }

    // Every row already carries its own, printed beside it.
    is ExplainerBlock.CitedTable -> {
      Unit
    }
  }
}

/**
 * Every page we read, once each, with its fetch date.
 *
 * `rel="nofollow noopener"` and no `target`: the page argues FROM these pages,
 * it does not endorse them, and a new tab is the reader's choice to make. A
 * page that cites nothing prints no heading at all, rather than an empty list
 * under the word "Sources".
 */
private fun FlowContent.renderSources(
  page: ExplainerPage,
  markers: ReferenceMarkers,
) {
  val sources = page.sourcesInReferenceOrder()
  if (sources.isEmpty()) return

  h2 { +SOURCES_HEADING }
  ul("guide-sources") {
    sources.forEach { source ->
      li {
        span("guide-ref") { +markers[source] }
        +" ${source.publisher}, "
        +source.title
        +". "
        outboundLink(source.url, source.url)
        +". Fetched ${UsDate.of(source.fetchedOn)}."
      }
    }
  }
}
