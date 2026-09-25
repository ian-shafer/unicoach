package ed.unicoach.coaching.testing

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ACT's composite-rule page is named by a `const` in `:service` and cited by a
 * cell in a `:db` seed file, and the two must be byte-identical or the ACT
 * combination silently becomes a refusal. This is what binds them (RFC 239 D-H
 * guardrail 3).
 *
 * THE DRIFT DEGRADES TO A PLAUSIBLE ANSWER, WHICH IS WHY IT NEEDS A PIN. A
 * trailing slash typed by the next curator, a `http`/`https` change, a tracking
 * parameter -- any of them leaves `AutomaticAwardService.ACT_COMPOSITE_RULE_URL`
 * matching no `external_documents` row, so every student's ACT combination
 * answers `act_rule_not_cited`: a named, reasonable-looking refusal that nobody
 * would read as a defect. Nothing else couples the two halves; `grep` for the
 * constant finds this module and its own unit tests only.
 *
 * The house already solved this coupling one corpus along --
 * `CdsLinkCheckSidecarPinTest` pins `bin/cds-link-sweep`'s `OUTPUT_NAME` to
 * `CdsSeedLoader.LINK_STATUS_FILE` because "the checker would write a file the
 * loader never reads" -- and this is the same shape with worse failure
 * behaviour.
 *
 * IT READS THE COMMITTED FILE AND NOT THE DATABASE, deliberately: the corpus is
 * the thing under review, an ingest run is what puts it in a database, and a
 * test that needed one could not fail until somebody ran the loader.
 */
class AutomaticAwardActCitationPinTest {
  /**
   * Cross-module `File("../db/seed/...")`: Gradle runs tests with the module
   * directory as the working directory, so the repo root is one level up. The
   * `:service` suites already read the tree this way
   * (`SystemPromptCatalogTest`, `ForbiddenCostArithmeticTest`).
   */
  private val sidecar = File("../db/seed/automatic-awards/link-status.csv")

  @Test
  fun `the committed sidecar is where this test says it is`() {
    // The control. Every assertion below is a lookup in this file's rows, and a
    // lookup in an EMPTY file would fail as "the url is missing" -- which is the
    // drift this test is about, and would be the wrong diagnosis. Said first, by
    // name.
    assertTrue(sidecar.isFile, "the committed link-status sidecar is not at [${sidecar.absolutePath}]")
    assertEquals(
      "url,url_status,url_checked_on",
      sidecar.readLines().first().trim(),
      "read a file that is not db/seed/automatic-awards/link-status.csv",
    )
  }

  @Test
  fun `the page the ACT combination is licensed by is a row of the committed corpus`() {
    val cited = rows().map { it.first }
    assertTrue(
      AutomaticAwardService.ACT_COMPOSITE_RULE_URL in cited,
      "AutomaticAwardService.ACT_COMPOSITE_RULE_URL ([${AutomaticAwardService.ACT_COMPOSITE_RULE_URL}]) is in no " +
        "db/seed/automatic-awards/link-status.csv row, so every ACT combination would answer " +
        "act_rule_not_cited: the service would refuse arithmetic the corpus means to license, and silently. " +
        "The urls the corpus does carry are [$cited]",
    )
  }

  @Test
  fun `the committed row is one we asked about and FOUND, which is what the service requires`() {
    val status = rows().single { it.first == AutomaticAwardService.ACT_COMPOSITE_RULE_URL }.second
    assertEquals(
      "found",
      status,
      "the service licenses the ACT combination on url_status = found and refuses every other value, so a corpus " +
        "committing [$status] for ACT's own rule page would turn the combination off for every student",
    )
  }

  /** The sidecar's rows as (url, url_status). Split on commas because no cell of this file may contain one. */
  private fun rows(): List<Pair<String, String>> =
    sidecar
      .readLines()
      .drop(1)
      .filter { it.isNotBlank() }
      .map { line ->
        val cells = line.split(",")
        assertEquals(3, cells.size, "a link-status row is three cells: [$line]")
        cells[0].trim() to cells[1].trim()
      }
}
