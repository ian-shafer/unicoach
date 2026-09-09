package ed.unicoach.coaching.costs

import ed.unicoach.coaching.costs.canonical.MoneySourceCopy
import ed.unicoach.db.models.MoneySource
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RFC 177 D7, asserted against the SOURCE: no literal publisher name appears in
 * any string that attributes a money figure.
 *
 * The defect was structural. `MoneySource` -- the code that says who published a
 * cell -- appeared in ZERO files under `service/src/main` or
 * `public-web/src/main`, because `CollegeFigures` dropped it at two lines. With
 * nothing above the domain layer able to know who published a number, every
 * surface that wanted to say it typed the answer by hand, and the hand-typed
 * answer drifted off the data: one constant named the College Scorecard for
 * every cost figure while the loader ranks both IPEDS surveys above it.
 *
 * A payload test cannot see this class of defect either -- a wrong attribution
 * arrives looking perfectly ordinary -- so the ban is checked where it would be
 * typed. [MoneySourceCopy] is the one exception, because it is the one home of
 * these words; the compile-time half of the same rule is its `when` with no
 * `else`.
 *
 * SCOPED TO MONEY-FIGURE ATTRIBUTION, deliberately (D7). Taken literally, "no
 * hard-coded publisher name" is 122 hits, most of them doc comments and true
 * statements about corpora that are not canonical money rows -- a school's own
 * Common Data Set, the Federal Student Aid form names. Those are out of scope
 * and are left alone; what is banned is a publisher of a MONEY FIGURE, named in
 * a live string.
 */
class MoneyAttributionNamesNoPublisherTest {
  /**
   * The two MODULE TREES the rule is stated over, never a package inside them.
   *
   * A package list is a guess at where a publisher's name would be typed, and
   * the guess goes stale the moment a file moves or a sibling surface starts
   * composing money copy: `coaching/fitlens` already renders a net-price note,
   * and under a package-scoped walk it was unpoliced with nothing saying so.
   */
  private val scannedDirectories = listOf(File("src/main"), File("../public-web/src/main"))

  /**
   * The publishers of canonical MONEY rows, as a family would hear them named --
   * DERIVED from the one home of those words, plus the short forms that home
   * does not spell.
   *
   * Derived, because a hand-typed list is exactly the shape this test exists to
   * ban: add a fifth [MoneySource] and `MoneySourceCopy.labelOf` fails to
   * compile until it is spoken, so its name enters this ban list in the same
   * edit -- while a literal list would stay green while the new publisher's name
   * was typed into a live attribution string.
   *
   * [ALIASES] are the fragments a hand-typed attribution actually uses, which
   * no full label contains as written ("Scorecard" alone, "IPEDS" alone). They
   * are listed rather than derived because they are not names anything
   * publishes; they are how people abbreviate them.
   *
   * `common_data_set` is deliberately EXCLUDED: it is a publisher too, but every
   * CDS sentence on these surfaces is already derived per school through
   * `CdsCitation.citedAs`, and the merit/borrowing sentence that names it is a
   * true statement about a corpus this seam does not carry.
   */
  private val publisherNames =
    MoneySource.entries
      .filterNot { it == MoneySource.COMMON_DATA_SET }
      .map { MoneySourceCopy.labelOf(it) } + ALIASES

  /** The one home of these words (D1), and so the one file that may contain them. */
  private val seam = "MoneySourceCopy.kt"

  private companion object {
    /**
     * The short forms of a federal publisher's name, which no full label
     * contains as written and which a hand-typed attribution reaches for first.
     */
    val ALIASES = listOf("College Scorecard", "Scorecard", "Department of Education", "IPEDS")

    /**
     * The ONE way a line naming a publisher for something that is not a money
     * figure leaves this ban -- a `// money-attribution-exempt: <reason>` comment
     * ON THAT LINE.
     *
     * A purpose-built token, written where the exempted code is, replacing a
     * list of incidental fragments held only here (a constant's name, a log
     * field, a phrase out of the exempted prose). Three things were wrong with
     * that: a developer reading the live string could not tell it was unpoliced,
     * any future money string that happened to mention one of the fragments
     * self-exempted, and renaming the constant or rewording the paragraph
     * silently re-armed or widened the ban. A line now states its own exemption
     * and its own reason, and nothing grants one by accident.
     *
     * Per LINE, never per file: a file exempted once would be unpoliced for
     * every line added to it afterwards.
     */
    const val EXEMPT_MARKER = "money-attribution-exempt"

    /**
     * The floor the file count must clear for the sweep to have read both module
     * trees.
     *
     * Well below what either tree actually holds, so it is not a count to
     * maintain; it is the tripwire for a walk that lost a module or stopped at a
     * package -- that failure drops the count by hundreds, not by one.
     */
    const val MIN_SCANNED_FILES = 100
  }

  private val sources: List<File>
    get() =
      scannedDirectories
        .flatMap { directory -> directory.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") } }
        .distinctBy { it.absolutePath }
        .sortedBy { it.absolutePath }

  /**
   * A LIVE line naming a publisher: neither a comment nor a line that carries
   * its own [EXEMPT_MARKER] for a fact that is not a money figure.
   *
   * Comments are out of scope by design (D7). A doc comment saying "the
   * Scorecard writes a cell per column" explains the store to a reader; it is
   * not a sentence a family is ever shown.
   */
  private fun offendingLines(file: File): List<String> =
    file
      .readLines()
      // POSITION first, so it survives every filter below: the failure must say
      // path:line, not a bare file name a reader then has to grep for.
      .mapIndexed { index, line -> "${file.path}:${index + 1}" to line.trim() }
      .filterNot { (_, line) -> line.startsWith("*") || line.startsWith("//") || line.startsWith("/*") }
      .filter { (_, line) -> publisherNames.any { line.contains(it) } }
      .filterNot { (_, line) -> line.contains(EXEMPT_MARKER) }
      .map { (position, line) -> "$position: $line" }

  @Test
  fun `no money-figure attribution names a publisher, outside the one seam that speaks them`() {
    val offenders =
      sources
        .filterNot { it.name == seam }
        .flatMap { file -> offendingLines(file) }

    assertEquals(
      emptyList(),
      offenders,
      "a publisher's name in a money-attribution string is a claim about data this code cannot see; " +
        "derive it from MoneySourceCopy instead",
    )
  }

  /**
   * The sweep must be able to FIRE, and must actually reach both modules.
   *
   * Without this the test above passes exactly as well when the scan reads
   * nothing at all -- which is how a directory that moved, or a sub-package a
   * non-recursive walk never entered, goes unpoliced in silence.
   */
  @Test
  fun `the sweep reads both modules and the pattern can fire`() {
    val names = sources.map { it.name }
    assertTrue(names.contains(seam), "the scan must reach the seam itself: [$names]")
    assertTrue(names.contains("CostReportPage.kt"), "the scan must reach the parent-facing page: [$names]")
    assertTrue(names.contains("CollegeCostChatTool.kt"), "the scan must reach the chat boundary: [$names]")
    // A money surface OUTSIDE `coaching/costs`, and the reason the walk starts
    // at the module trees: this file composes a net-price note and the earlier
    // package-scoped scan never saw it.
    assertTrue(names.contains("FitLensService.kt"), "the scan must reach money copy outside coaching/costs: [$names]")
    // Both trees, counted rather than sampled: a walk that silently stopped
    // returning one module's files would still satisfy the three names above.
    assertTrue(
      sources.size > MIN_SCANNED_FILES,
      "the scan must read both module trees whole, not a package inside them: [${sources.size}]",
    )
    val seamFile = sources.single { it.name == seam }
    assertTrue(
      offendingLines(seamFile).isNotEmpty(),
      "the pattern must match the one file that DOES name publishers, or it matches nothing anywhere",
    )
  }
}
