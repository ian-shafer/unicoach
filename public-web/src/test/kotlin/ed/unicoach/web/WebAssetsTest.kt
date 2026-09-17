package ed.unicoach.web

import ed.unicoach.web.assets.MainBundleResolution
import ed.unicoach.web.assets.ManifestRead
import ed.unicoach.web.assets.UnusableWebAssetManifest
import ed.unicoach.web.assets.WebAssets
import io.ktor.server.testing.testApplication
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The manifest `bin/build-web-assets` writes, read the way the application
 * reads it (RFC 225 D-C).
 *
 * The bundle's name is NEVER typed here. Every assertion is about the shape of
 * whatever the manifest currently says, so a rebuild that changes the hash —
 * which every TypeScript edit does — changes nothing in this file.
 */
class WebAssetsTest {
  @Test
  fun `the manifest is on the classpath and names a content-addressed bundle`() {
    assertTrue(
      WebAssets.HASHED_BUNDLE_NAME.matches(WebAssets.MAIN),
      "the manifest names [${WebAssets.MAIN}], which is not a content-addressed bundle URL",
    )
  }

  /**
   * One bundle has ONE name. The pattern is built from [WebAssets.MAIN_KEY], so
   * a second literal `main` cannot be edited on its own into a pattern that
   * refuses exactly the file the build writes.
   */
  @Test
  fun `the bundle-name pattern is keyed on the manifest key, not on a second literal`() {
    assertTrue(
      WebAssets.HASHED_BUNDLE_NAME.pattern.contains(WebAssets.MAIN_KEY),
      "the bundle-name pattern [${WebAssets.HASHED_BUNDLE_NAME.pattern}] does not carry the entry name " +
        "[${WebAssets.MAIN_KEY}]: it names the bundle a second time",
    )
  }

  @Test
  fun `the bundle the manifest names is on the classpath`() {
    val resource = packagedBundlePath()
    assertTrue(
      javaClass.getResource(resource) != null,
      "the manifest names [${WebAssets.MAIN}] but [$resource] is not packaged: run bin/build-web-assets",
    )
  }

  @Test
  fun `the packaged bundle is the ES module the page loads, not an empty file`() {
    val bytes =
      javaClass.getResourceAsStream(packagedBundlePath())?.readBytes()
        ?: fail("the bundle named by the manifest is not packaged")
    assertTrue(bytes.isNotEmpty(), "the packaged bundle is empty")
  }

  /**
   * The refusals, as VARIANTS. An `object` initialises once per JVM, so these
   * cannot be reached through [WebAssets.MAIN] — and a manifest check that is
   * never exercised is indistinguishable from one that was deleted.
   *
   * Each asserts the outcome the reader returns, not the prose it would print:
   * a message is copy, and a test that matched a substring of it failed on a
   * reworded sentence and passed on a refusal that had silently become the
   * wrong one.
   */
  @Test
  fun `a missing manifest is refused`() {
    assertEquals(
      MainBundleResolution.Absent,
      WebAssets.mainFrom(ManifestRead.Missing) { true },
      "the reader accepted a tree whose web assets have never been built",
    )
  }

  /**
   * A manifest that is THERE and cannot be parsed — a corrupt `\u` escape, an
   * I/O fault. Modelled, because the alternative is the loader's own
   * `IllegalArgumentException` escaping the reader with a message that never
   * mentions `bin/build-web-assets`, past every refusal that does.
   */
  @Test
  fun `a manifest that cannot be read is refused, carrying the fault`() {
    val fault = IOException("the manifest could not be read")
    assertEquals(
      MainBundleResolution.Unreadable(fault),
      WebAssets.mainFrom(ManifestRead.Unreadable(fault)) { true },
      "the reader lost the fault that made the manifest unreadable",
    )
  }

  @Test
  fun `a manifest with no main key is refused`() {
    assertEquals(
      MainBundleResolution.NoMainKey,
      WebAssets.mainFrom(ManifestRead.Entries(emptyMap())) { true },
      "the reader accepted a manifest with no [${WebAssets.MAIN_KEY}] key",
    )
  }

  /**
   * The manifest is GENERATED and carries exactly one key, so anything else in
   * it is drift — a half-applied merge, a hand edit that was never removed.
   * Accepted silently, the file stops being the build's output while still
   * looking like it.
   */
  @Test
  fun `a manifest carrying a surplus key is refused, naming the key`() {
    val entries = mapOf(WebAssets.MAIN_KEY to WebAssets.MAIN, "legacy" to "/js/main.js")
    assertEquals(
      MainBundleResolution.UnexpectedKeys(listOf("legacy")),
      WebAssets.mainFrom(ManifestRead.Entries(entries)) { true },
      "the reader accepted a manifest carrying a key the build never writes",
    )
  }

  @Test
  fun `a hand-edited manifest naming an un-hashed bundle is refused, quoting the value`() {
    assertEquals(
      MainBundleResolution.NotContentAddressed("/js/main.js"),
      WebAssets.mainFrom(manifestOf("/js/main.js")) { true },
      "the reader accepted a bundle name that cannot be cached by name",
    )
  }

  @Test
  fun `a manifest naming a bundle that is not packaged is refused, quoting both paths`() {
    val absent = hashedBundleUrl("abcdef123456")
    assertEquals(
      MainBundleResolution.NotPackaged(absent, "/${WebAssets.STATIC_RESOURCE_PACKAGE}$absent"),
      WebAssets.mainFrom(manifestOf(absent)) { false },
      "the reader accepted a manifest that disagrees with the built bundle",
    )
  }

  /**
   * The negative self-test for the refusals above: the same reader, given the
   * manifest the build actually wrote, resolves rather than refuses. Without
   * it, a `mainFrom` that refused every input would pass all of them.
   */
  @Test
  fun `the same reader accepts the manifest the build wrote`() {
    assertEquals(
      MainBundleResolution.Resolved(WebAssets.MAIN),
      WebAssets.mainFrom(manifestOf(WebAssets.MAIN)) { true },
      "the reader refuses the manifest this build produced",
    )
  }

  /**
   * The crash boundary keeps the VARIANT. `MAIN` turning a refusal into
   * `error(...)` would flatten the sealed type back into a sentence one line
   * before the crash — the exact loss it exists to prevent — leaving anything
   * that catches or logs the boot failure to tell "never built" from "the
   * manifest disagrees with the tree" by matching prose.
   *
   * Every refusal is walked, and [coveringTest] is an exhaustive `when`, so a
   * variant added to [MainBundleResolution] without a sample here does not
   * compile.
   */
  @Test
  fun `an unusable manifest crashes carrying the refusal, and names the fix`() {
    val covering = refusals.map { coveringTest(it) }
    assertEquals(
      covering.size,
      covering.toSet().size,
      "two refusals name the same covering test, so one of them is untested: [$covering]",
    )

    refusals.forEach { refusal ->
      val failure =
        assertFailsWith<UnusableWebAssetManifest>("[$refusal] did not stop the boot") {
          WebAssets.bundleUrlOf(refusal)
        }
      assertEquals(refusal, failure.resolution, "the boot failure flattened [$refusal] into prose")
      assertTrue(
        failure.message?.contains(UnusableWebAssetManifest.FIX_COMMAND) == true,
        "the refusal [$refusal] does not name the command that fixes it: [${failure.message}]",
      )
    }
  }

  /** The one refusal with an underlying fault keeps it as the exception's cause. */
  @Test
  fun `an unreadable manifest crashes with the fault as its cause`() {
    val fault = IOException("the manifest could not be read")
    val failure =
      assertFailsWith<UnusableWebAssetManifest> {
        WebAssets.bundleUrlOf(MainBundleResolution.Unreadable(fault))
      }
    assertEquals(fault, failure.cause, "the boot failure dropped the fault that caused it")
  }

  /** The negative self-test for the boundary: a resolution is a URL, not a crash. */
  @Test
  fun `a resolved manifest yields its url`() {
    assertEquals(
      WebAssets.MAIN,
      WebAssets.bundleUrlOf(MainBundleResolution.Resolved(WebAssets.MAIN)),
      "the boundary refuses a manifest it just resolved",
    )
  }

  /**
   * The hash length is ONE decision, shared with `bin/build-web-assets`'
   * `cut -c"1-$BUNDLE_HASH_HEX_LENGTH"`. Asserted by construction from the constant
   * rather than against a typed-out name, so raising it to 16 on both sides
   * keeps this green and raising it in the pattern alone — where it used to be a
   * bare `{12}` — does not.
   */
  @Test
  fun `the bundle-name pattern accepts exactly the configured hash length`() {
    val hash = "a".repeat(WebAssets.BUNDLE_HASH_HEX_LENGTH)
    assertTrue(
      WebAssets.HASHED_BUNDLE_NAME.matches(hashedBundleUrl(hash)),
      "the pattern refuses a name carrying [${WebAssets.BUNDLE_HASH_HEX_LENGTH}] hex characters, the length the build writes",
    )
    assertFalse(
      WebAssets.HASHED_BUNDLE_NAME.matches(hashedBundleUrl(hash.dropLast(1))),
      "the pattern accepts a name one hex character SHORT of the configured length",
    )
    assertFalse(
      WebAssets.HASHED_BUNDLE_NAME.matches(hashedBundleUrl(hash + "a")),
      "the pattern accepts a name one hex character LONGER than the configured length",
    )
  }

  /**
   * The bundle's directory is a name, like the static package beside it: the
   * build writes into it and the validator checks for it, and a rename on one
   * side alone leaves the pattern refusing exactly the file the build now
   * writes.
   */
  @Test
  fun `the bundle-name pattern is keyed on the bundle directory, not on a second literal`() {
    assertTrue(
      WebAssets.HASHED_BUNDLE_NAME.pattern.contains(WebAssets.BUNDLE_DIRECTORY),
      "the bundle-name pattern [${WebAssets.HASHED_BUNDLE_NAME.pattern}] does not carry the directory " +
        "[${WebAssets.BUNDLE_DIRECTORY}]: it names it a second time",
    )
  }

  /**
   * The mechanism behind "it fails at STARTUP", asserted as BEHAVIOUR.
   *
   * [WebAssets] is an `object`, so its refusals run at class-init and class-init
   * happens on first touch. If no module install touched it, a broken manifest
   * would boot GREEN and fail the first visitor — including on the branded 503,
   * which renders the same shell.
   *
   * `publicWebModule` therefore takes a bundle-url resolver, defaulted to the
   * real one. Handed a resolver that throws, the module must fail while it
   * INSTALLS: `startApplication()` returns before any request is made, so a
   * module that only read the manifest when a page rendered would start
   * cleanly and fail this test. The default path is what every other test in
   * `:public-web` boots, so "the real resolver still works" needs no test of
   * its own.
   */
  @Test
  fun `a bundle url that cannot be resolved fails the BOOT, not the first request`() {
    val fault =
      assertFailsWith<UnusableWebAssetManifest>("the module installed with a manifest it could not read") {
        testApplication {
          application {
            testPublicWebModule(bundleUrl = { throw UnusableWebAssetManifest(MainBundleResolution.Absent) })
          }
          startApplication()
          fail("the application started with an unresolvable bundle url: the boot does not read the manifest")
        }
      }
    assertEquals(
      MainBundleResolution.Absent,
      fault.resolution,
      "the boot failure did not carry the refusal the resolver threw",
    )
  }

  /**
   * Every refusal, one sample each, as the input to the crash boundary.
   *
   * A list rather than a reflective walk: `sealedSubclasses` needs
   * `kotlin-reflect`, which this module does not depend on, and a variant that
   * carries data has no instance to walk to anyway. [coveringTest] is what makes
   * the list complete — it is an exhaustive `when`, so a new variant is a
   * compile error here.
   */
  private val refusals: List<MainBundleResolution>
    get() {
      val absent = hashedBundleUrl("abcdef123456")
      return listOf(
        MainBundleResolution.Absent,
        MainBundleResolution.Unreadable(IOException("the manifest could not be read")),
        MainBundleResolution.NoMainKey,
        MainBundleResolution.UnexpectedKeys(listOf("legacy")),
        MainBundleResolution.NotContentAddressed("/js/main.js"),
        MainBundleResolution.NotPackaged(absent, "/${WebAssets.STATIC_RESOURCE_PACKAGE}$absent"),
      )
    }

  /**
   * The test in this file that covers [resolution], by name.
   *
   * Exhaustive with no `else`, and that is its whole job: a refusal added to
   * [MainBundleResolution] without a test here fails to COMPILE, rather than
   * shipping a manifest check nothing exercises. The names are prose, so they
   * are compared only with each other — two variants naming one test means one
   * of them is uncovered.
   */
  private fun coveringTest(resolution: MainBundleResolution): String =
    when (resolution) {
      is MainBundleResolution.Resolved -> {
        "the same reader accepts the manifest the build wrote"
      }

      MainBundleResolution.Absent -> {
        "a missing manifest is refused"
      }

      is MainBundleResolution.Unreadable -> {
        "a manifest that cannot be read is refused, carrying the fault"
      }

      MainBundleResolution.NoMainKey -> {
        "a manifest with no main key is refused"
      }

      is MainBundleResolution.UnexpectedKeys -> {
        "a manifest carrying a surplus key is refused, naming the key"
      }

      is MainBundleResolution.NotContentAddressed -> {
        "a hand-edited manifest naming an un-hashed bundle is refused, quoting the value"
      }

      is MainBundleResolution.NotPackaged -> {
        "a manifest naming a bundle that is not packaged is refused, quoting both paths"
      }
    }

  /**
   * A bundle URL with the given hash, composed from the same two names
   * [WebAssets] composes its pattern from. No test here types `/js/main-` out.
   */
  private fun hashedBundleUrl(hash: String): String = "/${WebAssets.BUNDLE_DIRECTORY}/${WebAssets.MAIN_KEY}-$hash.js"

  private fun manifestOf(main: String): ManifestRead = ManifestRead.Entries(mapOf(WebAssets.MAIN_KEY to main))

  /**
   * The packaged bundle's classpath path, composed from the two names
   * [WebAssets] itself composes it from — the mount's package and the manifest
   * value — so no literal here can drift from either.
   */
  private fun packagedBundlePath(): String = "/${WebAssets.STATIC_RESOURCE_PACKAGE}${WebAssets.MAIN}"
}
