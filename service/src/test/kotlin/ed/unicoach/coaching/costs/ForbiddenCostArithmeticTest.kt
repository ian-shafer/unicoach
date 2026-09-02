package ed.unicoach.coaching.costs

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RFC 149 D-F rule 2, asserted against the SOURCE rather than against a payload:
 * nothing in the cost package computes a net price minus tuition, or a net price
 * minus a component, or one component minus another.
 *
 * The rule is not a stylistic one. A net price is cost of attendance minus the
 * average grant and scholarship aid a school gave -- aid that applies to the
 * WHOLE price. Subtracting a component from it produces a number that looks like
 * "what housing really costs after aid" and is not that, or anything else. It is
 * the single most tempting wrong arithmetic in this domain, which is why it is
 * checked at the one place it could ever be written.
 *
 * A payload test could not see this: the defect is a number that arrives looking
 * perfectly ordinary. The prompt half of the same rule is asserted in
 * [ed.unicoach.coaching.SystemPromptCatalogTest], over the seeded v9 body.
 *
 * The scan is textual and therefore approximate in one direction only: it can
 * report a subtraction that is innocent, which a reviewer resolves by rewriting
 * the line or naming it here. It cannot MISS one written in the obvious way, and
 * that asymmetry is the point.
 */
class ForbiddenCostArithmeticTest {
  private val sourceDirectory = File("src/main/kotlin/ed/unicoach/coaching/costs")

  private val sources: List<File>
    get() = sourceDirectory.listFiles { f: File -> f.name.endsWith(".kt") }?.sortedBy { it.name } ?: emptyList()

  /**
   * A line that subtracts one MONEY-bearing expression from another, ignoring
   * comments (which must be free to SAY "never subtract", and do).
   *
   * The OPERANDS are anchored on the money vocabulary ([MONEY_OPERAND]), on
   * either side of the minus. That anchoring is what keeps the hyphenated
   * English this domain speaks -- `in-state`, `out-of-state`, `first-time` --
   * out of the scan: prose cannot match, because neither side of its hyphen is a
   * money identifier. So nothing has to be blanked, and every line is judged
   * exactly as the compiler will read it.
   *
   * That last point is the whole of RFC 157's tier-2 fix here. A scan that
   * silenced the prose by blanking whole double-quoted literals also blanked
   * their `${"$"}{...}` bodies, which are CODE -- so a real money subtraction
   * written inside a string template was invisible, the one shape this test's
   * own docstring says ktlint does not police. It was wrong on raw strings
   * (`\` escapes nothing there) and on a `'"'` char literal too. Anchoring the
   * operands deletes that hand-rolled lexer instead of patching it.
   *
   * The end-of-line alternative in the first branch is the WRAPPED form, and it
   * is not a nicety: Kotlin continues an expression whose line ends in a binary
   * operator, and the formatter writes exactly that once the identifiers are
   * long -- which, in this package, they are. A pattern needing an operand on the
   * same line would be blind to the shape ktlint produces for the very fields
   * this rule is about.
   *
   * The minus needs NO whitespace around it, deliberately (RFC 157). `a-b` is a
   * real subtraction the compiler accepts, and leaning on ktlint's
   * `spacing-around-operators` to keep it unwritable would put the guard's
   * coverage in a tool this test cannot see.
   */
  private val subtraction =
    Regex(
      """$MONEY_OPERAND[A-Za-z0-9_.)\]]*\s*-\s*([A-Za-z_(]|${"$"})""" +
        """|[A-Za-z0-9_)\]]\s*-\s*[A-Za-z0-9_.(]*$MONEY_OPERAND""",
    )

  private fun codeLines(file: File): List<Pair<Int, String>> =
    file
      .readLines()
      .mapIndexed { i, line -> (i + 1) to line }
      .filterNot { (_, line) ->
        val trimmed = line.trim()
        trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
      }

  @Test
  fun `the cost package is actually being read`() {
    // The whole test is a scan over files; a wrong working directory or a moved
    // package would silently scan nothing and pass. This is the guard against
    // that, and it names what it expects to find.
    assertTrue(sourceDirectory.isDirectory, "expected the cost sources at [${sourceDirectory.absolutePath}]")
    val names = sources.map { it.name }.toSet()
    assertTrue(
      names.containsAll(setOf("CollegeCostService.kt", "CollegeCostChatTool.kt", "CostBreakdown.kt", "CostField.kt")),
      "the scan must cover the whole cost package, found [$names]",
    )
  }

  /**
   * THE scan: every line of [file] that subtracts one money-bearing expression
   * from another. Both the sweep and its positive control run this one function,
   * so the control cannot pass on a copy of a pipeline the sweep no longer uses.
   */
  private fun offendingLines(file: File): List<Pair<Int, String>> = codeLines(file).filter { (_, line) -> isForbiddenArithmetic(line) }

  /**
   * The rule itself: a subtraction with a MONEY operand on one side of it, on a
   * line of code -- the whole line, templates included, because a `${"$"}{...}`
   * body is code that happens to sit inside a literal.
   */
  private fun isForbiddenArithmetic(code: String): Boolean = subtraction.containsMatchIn(code)

  @Test
  fun `no cost source subtracts a price from a price`() {
    val offenders =
      sources.flatMap { file ->
        offendingLines(file).map { (number, line) -> "${file.name}:$number: ${line.trim()}" }
      }
    assertEquals(
      emptyList(),
      offenders,
      "aid applies to the whole price, never to one part of it: a net price minus a tuition or a component " +
        "is a number with no meaning. Rewrite the line rather than relaxing this test.",
    )
  }

  @Test
  fun `the scan reacts to the arithmetic it forbids`() {
    // Positive control, run through the REAL scan rather than a restatement of
    // it: a codeLines that stopped stripping comments, or a sources that found
    // no files, would otherwise leave the assertion above vacuous and this file
    // a decoration. The control file carries six shapes -- an ordinary
    // subtraction, a subtraction the formatter WRAPPED across two lines (the
    // shape ktlint produces for identifiers this long), a comment that forbids
    // the arithmetic in words, hyphenated English inside a string literal, a
    // subtraction written with NO space around its minus, which the compiler
    // accepts and this scan must therefore still see, and a subtraction written
    // INSIDE a string template, which the scan missed while it blanked literals
    // (RFC 157 tier 2).
    val control = File.createTempFile("forbidden-arithmetic-control", ".kt")
    control.deleteOnExit()
    control.writeText(
      """
      val gap = cost.netPrice.amount - college.tuitionAndFeesInStatePerYearUsd
      // never subtract net_price from a component
      val wrapped =
        cost.netPricePerYearIncomeQ1Usd -
          college.housingAndFoodOnCampusPerYearUsd
      val copy = "this school publishes its in-state tuition and fees for out-of-state families"
      val tight = cost.netPrice.amount-college.booksAndSuppliesPerYearUsd
      val templated = "the gap is ${'$'}{cost.netPrice.amount - college.booksAndSuppliesPerYearUsd} per year"
      """.trimIndent() + "\n",
    )

    val hits = offendingLines(control).map { it.first }
    assertEquals(
      listOf(1, 4, 7, 8),
      hits,
      "the scan must see the plain subtraction on line 1, the wrapped one on line 4, the unspaced one on " +
        "line 7 and the one written inside a string template on line 8, must NOT fire on the comment on " +
        "line 2 that states the rule in words, and must NOT fire on the hyphenated English of line 6, which " +
        "names two money words inside a string literal and subtracts nothing",
    )
  }

  private companion object {
    /**
     * The identifiers that make a subtraction a MONEY subtraction. Derived from
     * the wire vocabulary where possible, so a renamed field cannot quietly
     * fall out of the scan; the Kotlin-side property names are listed beside
     * them because the code says `netPrice`, not `net_price`.
     */
    val MONEY_TOKENS: Set<String> =
      CostField.entries.map { it.wireName }.toSet() +
        setOf(
          "netPrice",
          "amount",
          "amountUsd",
          "PerYearUsd",
          "totalPerYearUsd",
          "Tuition",
          "tuition",
        )

    /**
     * The same vocabulary as ONE regex alternation, so the pattern can anchor
     * its operands on it rather than asking a separate "does this line mention
     * money" question about a line the pattern has already read.
     *
     * Escaped, because a wire name is data: a token that ever contained a regex
     * metacharacter must not quietly become one.
     */
    val MONEY_OPERAND: String = MONEY_TOKENS.joinToString("|", prefix = "(?:", postfix = ")") { Regex.escape(it) }
  }
}
