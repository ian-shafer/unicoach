package ed.unicoach.college

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The sidecar's NAME and HEADER are one fact written in two languages, pinned
 * here (RFC 200).
 *
 * `bin/cds-link-sweep` writes `link-status.csv`; [CdsSeedLoader] asserts
 * its header exactly as it asserts the four seed headers. Neither half can see
 * the other, and until this test existed the only coupling was the COMMITTED
 * artifact -- which is produced by the script and checked by the loader. That
 * couples them in one direction only, and it is the wrong one: a rename in the
 * Kotlin list fails immediately against the committed file, while a rename in
 * the SCRIPT fails nothing until somebody runs the sweep and commits its
 * output, at which point the ingest is what breaks.
 *
 * So the script's own source is READ and its two constants compared against the
 * loader's -- the shape this repo already uses to sweep sources it does not
 * compile (`RenderPathMakesNoNetworkCallTest`).
 * A rename on EITHER side fails here, before any sweep is run.
 */
class CdsLinkCheckSidecarPinTest {
  /**
   * Cross-module `File("../bin/...")`: Gradle runs tests with the module
   * directory as the working directory, so the repo root is one level up.
   */
  private val script = File("../bin/cds-link-sweep")

  @Test
  fun `the checker's source is where this test says it is`() {
    // The control. Every assertion below is a `contains` over this text, and a
    // `contains` over an EMPTY string fails loudly -- but a missing file would
    // fail with "no such file", which reads like an environment problem rather
    // than the drift this test is about. Said first, by name.
    assertTrue(script.isFile, "the pinned checker is not at [${script.absolutePath}]")
    assertTrue(script.readText().contains("OUTPUT_HEADER = ("), "read a file that is not [bin/cds-link-sweep]")
  }

  @Test
  fun `the checker and the loader agree on the sidecar's filename`() {
    assertTrue(
      script.readText().contains("""OUTPUT_NAME = "${CdsSeedLoader.LINK_STATUS_FILE}""""),
      "bin/cds-link-sweep's OUTPUT_NAME has drifted from CdsSeedLoader.LINK_STATUS_FILE " +
        "([${CdsSeedLoader.LINK_STATUS_FILE}]): the checker would write a file the loader never reads",
    )
  }

  @Test
  fun `the checker and the loader agree on the sidecar's header, column for column and in order`() {
    // Built from the loader's list rather than re-typed, so this test cannot be
    // satisfied by a third copy of the five names. ORDER is part of the claim:
    // `assertHeader` compares the header as a sequence, so two columns swapped
    // in the script would load `checked_on` values into `source_url_status`.
    val header = CdsSeedLoader.LINK_STATUS_COLUMNS.joinToString(", ") { """"$it"""" }
    assertTrue(
      script.readText().contains("OUTPUT_HEADER = ($header)"),
      "bin/cds-link-sweep's OUTPUT_HEADER has drifted from CdsSeedLoader.LINK_STATUS_COLUMNS " +
        "(expected [OUTPUT_HEADER = ($header)])",
    )
  }

  @Test
  fun `the sidecar is resolved beside the seed, never from the process's current directory`() {
    // A BARE relative filename is the case that used to fall through:
    // `File("aid-policy.csv").parentFile` is null, and the old `?: File(".")`
    // made the sidecar whatever sat in whichever directory the operator
    // happened to launch from -- a file that decides whether a family is shown
    // a link, chosen by cwd. Resolved from the ABSOLUTE parent, there is no
    // fallback left to take.
    val bare = CdsSeedLoader.sidecarBeside(File("aid-policy.csv"))
    assertEquals(File(File("").absoluteFile, CdsSeedLoader.LINK_STATUS_FILE).path, bare.path)
    assertTrue(bare.isAbsolute, "the sidecar path must never be relative to the launcher: [$bare]")

    // And the ordinary case is unchanged: beside the seed it describes.
    val beside = CdsSeedLoader.sidecarBeside(File("/tmp/seed/cds/aid-policy.csv"))
    assertEquals(File("/tmp/seed/cds/${CdsSeedLoader.LINK_STATUS_FILE}"), beside)
  }
}
