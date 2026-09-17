package ed.unicoach.web.render

import ed.unicoach.common.util.UsDate
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import kotlinx.html.FlowContent
import kotlinx.html.a
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.sup
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
    require(url.startsWith("https://")) { "a source is not an absolute https URL: [$url]" }
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
  val asOf: LocalDate,
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
    .filterIsInstance<ExplainerBlock.Claim>()
    .map { it.source }
    .also { cited ->
      cited.groupBy { it.url }.forEach { (url, readings) ->
        require(readings.distinct().size == 1) { "one URL cited with two different readings: [$url]" }
      }
    }.distinct()

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

/** Renders [page] through the shared [siteLayout], like every other public-web page. */
suspend fun ApplicationCall.respondExplainerPage(
  page: ExplainerPage,
  nav: ShellNav = ShellNav.SignedOut,
) {
  respondHtml {
    siteLayout(page.title, nav = nav) {
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
  p("guide-as-of") { +"As of ${UsDate.of(page.asOf)}" }
  p("guide-standfirst") { +page.standfirst }
}

private fun FlowContent.renderSection(
  markers: ReferenceMarkers,
  pageSection: ExplainerSection,
) {
  h2 { +pageSection.heading }
  pageSection.blocks.forEach { block -> renderBlock(markers, block) }
}

/** One block: its text, then whatever stands behind that text. */
private fun FlowContent.renderBlock(
  markers: ReferenceMarkers,
  block: ExplainerBlock,
) {
  p {
    +block.text
    renderCitation(markers, block)
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
        a(href = source.url) {
          rel = "nofollow noopener"
          +source.url
        }
        +". Fetched ${UsDate.of(source.fetchedOn)}."
      }
    }
  }
}
