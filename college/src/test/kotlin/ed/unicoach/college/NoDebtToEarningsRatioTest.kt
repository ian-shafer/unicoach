package ed.unicoach.college

import ed.unicoach.db.models.ProgramMoneyMeasure
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WE NEVER COMPUTE A DEBT-TO-EARNINGS RATIO (brief 0011 D27, RFC 218).
 *
 * The earnings column and the two debt columns are different cohorts, from
 * different publishers, in different dollar bases -- Treasury AY2017-18+AY2018-19
 * adjusted to 2024 dollars against NSLDS AY2018-19+AY2019-20 with no stated
 * dollar year. A ratio over them is a figure nobody published, and the publisher's
 * own ten-year monthly payment is stored precisely so nobody needs to invent one.
 *
 * COUNTED BY THE ATTRIBUTE, NOT BY ONE CALL SPELLING. The two token families are
 * DERIVED -- from [ProgramMoneyMeasure] and from
 * [ScorecardFieldOfStudyColumns] -- so renaming a helper, or adding a fourth
 * measure, cannot walk past this guard. It is a whole-repo sweep of production
 * Kotlin, not a sweep of this module.
 */
class NoDebtToEarningsRatioTest {
  /** The repo root, found by walking UP for the settings file (the seed-directory idiom). */
  private val repoRoot: File =
    requireNotNull(
      generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile },
    ) { "no settings.gradle.kts on the walk UP from [${File(".").absoluteFile}]" }

  /** Every production Kotlin source in the repo -- never a test, never a build output. */
  private val productionSources: List<File> =
    repoRoot
      .walkTopDown()
      .onEnter { it.name != "build" && it.name != ".git" }
      .filter { it.isFile && it.extension == "kt" }
      .filter { it.path.contains("/src/main/kotlin/") }
      .toList()

  /**
   * The ATTRIBUTE each family is recognised by, not a list of call spellings:
   * the stem the repo's own names are built from. `EARN_MDN_4YR`,
   * `median_earnings_4yr_after_entry`, `MEDIAN_EARNINGS_4YR_AFTER_ENTRY` and a
   * hand-rolled `medianEarnings4Yr` all carry `earn`, so renaming a helper
   * cannot walk past this guard -- which a set of literal identifiers could
   * not claim, because `medianDebtAtCompletion` is in none of them.
   *
   * The stems are ASSERTED against the derived names below, so a fourth measure
   * whose name does not carry its family's stem fails a test rather than
   * silently escaping the sweep.
   */
  private val earningsStem = "earn"
  private val debtStem = "debt"

  /** Every name this store gives each family, derived from the two objects that own them. */
  private fun namesOf(stem: String): Set<String> =
    buildSet {
      for (measure in ProgramMoneyMeasure.entries) {
        if (measure.value.contains(stem)) {
          add(measure.value)
          add(measure.name)
        }
      }
      for (variable in ScorecardFieldOfStudyColumns.VARIABLES) {
        if (variable.sourceVariable.lowercase().contains(stem)) add(variable.sourceVariable)
      }
    }

  private val earningsNames = namesOf(earningsStem)
  private val debtNames = namesOf(debtStem)

  /** A division in which BOTH families appear -- whatever the two operands are called. */
  private fun dividesOneFamilyByTheOther(line: String): Boolean {
    // A KDoc line is prose, and this rule is about code. The paragraph that
    // EXPLAINS the rule names both families and would otherwise be its own
    // first offender.
    if (line.trimStart().startsWith("*") || line.trimStart().startsWith("/*")) return false
    val code = line.substringBefore("//").lowercase()
    if (!code.contains("/")) return false
    return code.contains(earningsStem) && code.contains(debtStem)
  }

  @Test
  fun `no production Kotlin divides an earnings figure by a debt figure`() {
    val offenders =
      productionSources.flatMap { file ->
        file
          .readLines()
          .withIndex()
          .filter { (_, line) -> dividesOneFamilyByTheOther(line) }
          .map { (index, line) -> "${file.relativeTo(repoRoot)}:${index + 1}: ${line.trim()}" }
      }
    assertEquals(
      emptyList(),
      offenders,
      "a debt-to-earnings ratio is a figure nobody published (brief 0011 D27): " +
        "the two are different cohorts, different publishers and different dollar bases",
    )
  }

  @Test
  fun `no production Kotlin names such a ratio at all`() {
    // A name is the other half of the same defect: a `debtToEarnings` value
    // computed by any route is the figure this rule refuses, however it was
    // reached.
    val naming = Regex("""(debt[_a-z]*to[_a-z]*earn|earn[_a-z]*to[_a-z]*debt)""", RegexOption.IGNORE_CASE)
    val offenders =
      productionSources.flatMap { file ->
        file
          .readLines()
          .withIndex()
          .filter { (_, line) ->
            val trimmed = line.trimStart()
            !trimmed.startsWith("*") &&
              !trimmed.startsWith("/*") &&
              naming.containsMatchIn(line.substringBefore("//"))
          }.map { (index, line) -> "${file.relativeTo(repoRoot)}:${index + 1}: ${line.trim()}" }
      }
    assertEquals(emptyList(), offenders, "no such figure is computed, stored or named")
  }

  @Test
  fun `the sweep reads real files and both rules can fire`() {
    // Without this, both guards above pass exactly as well over an empty list --
    // the failure mode of every corpus sweep.
    assertTrue(productionSources.size > 100, "the sweep read only ${productionSources.size} production sources")
    assertTrue(
      productionSources.any { it.name == "ProgramMoneyLoader.kt" },
      "the sweep must reach the loader this rule is about",
    )
    // The stems ARE the attribute, so every name this store gives either family
    // must carry its own stem -- otherwise the sweep silently stops covering it.
    assertEquals(
      setOf("median_earnings_4yr_after_entry", "MEDIAN_EARNINGS_4YR_AFTER_ENTRY", "EARN_MDN_4YR"),
      earningsNames,
    )
    assertTrue(debtNames.size >= 4, debtNames.toString())
    for (name in earningsNames) assertTrue(name.lowercase().contains(earningsStem), name)
    for (name in debtNames) assertTrue(name.lowercase().contains(debtStem), name)
    assertEquals(
      ProgramMoneyMeasure.entries.size,
      ProgramMoneyMeasure.entries.count { it.value.contains(earningsStem) || it.value.contains(debtStem) },
      "a measure carrying neither stem would be invisible to this sweep",
    )
    // Positive controls, in every spelling the rule is meant to catch --
    // including the hand-rolled camelCase name a literal identifier list misses.
    assertTrue(dividesOneFamilyByTheOther("val ratio = medianDebtAtCompletion / MEDIAN_EARNINGS_4YR_AFTER_ENTRY"))
    assertTrue(dividesOneFamilyByTheOther("val x = EARN_MDN_4YR / DEBT_ALL_STGP_EVAL_MDN"))
    assertTrue(dividesOneFamilyByTheOther("val r = programEarningsFigure / programDebtFigure"))
    // And it does NOT fire on a division that touches only one family, nor on a
    // comment: a guard that fires on everything is not read.
    assertTrue(!dividesOneFamilyByTheOther("val perMonth = DEBT_ALL_STGP_EVAL_MDN / 120"))
    assertTrue(!dividesOneFamilyByTheOther("// EARN_MDN_4YR / DEBT_ALL_STGP_EVAL_MDN is never computed"))
  }
}
