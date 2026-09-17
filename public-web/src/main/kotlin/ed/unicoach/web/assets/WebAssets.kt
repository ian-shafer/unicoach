package ed.unicoach.web.assets

import java.io.IOException
import java.util.Properties

/**
 * Every state `/web-assets.properties` can be in, as a VALUE (RFC 225).
 *
 * The refusals are predictable outcomes of reading a build artifact, not system
 * faults: a tree whose assets were never built, a corrupt file, a hand-edited
 * manifest, a stale manifest, a half-applied merge. Returned rather than thrown,
 * they are checked by the compiler at the one place that turns a refusal into a
 * startup crash, and asserted by tests as variants rather than as substrings of
 * prose.
 *
 * `internal`: this is how ONE module reads its own build artifact. Published, it
 * would be a type every future caller of [WebAssets] has to speak, for a
 * decision none of them makes. The test source set is a friend of this one, so
 * the tests still assert variants.
 */
internal sealed interface MainBundleResolution {
  /** The manifest names a content-addressed bundle that IS on the classpath. */
  data class Resolved(
    val url: String,
  ) : MainBundleResolution

  /** No manifest on the classpath: the web assets have never been built. */
  data object Absent : MainBundleResolution

  /**
   * The manifest is on the classpath but could not be LOADED: a corrupt `\u`
   * escape, an I/O fault. A state of the file like any other, so it is a variant
   * rather than an exception escaping the reader with a message that never
   * mentions the build script.
   */
  data class Unreadable(
    val cause: Throwable,
  ) : MainBundleResolution

  /** The manifest is there, but carries no `main` key. */
  data object NoMainKey : MainBundleResolution

  /**
   * The manifest carries keys beyond `main`. It is GENERATED and carries exactly
   * one, so a surplus key is drift — a half-applied merge, a hand edit — and
   * accepting it silently is how a manifest stops being the build's output.
   */
  data class UnexpectedKeys(
    val keys: List<String>,
  ) : MainBundleResolution

  /** The value is not a hashed bundle name, so it cannot be cached by name. */
  data class NotContentAddressed(
    val value: String,
  ) : MainBundleResolution

  /** The value names a bundle that is not packaged; [resourcePath] is where it was looked for. */
  data class NotPackaged(
    val value: String,
    val resourcePath: String,
  ) : MainBundleResolution
}

/**
 * The OUTCOME of going to the classpath for the manifest, as a value.
 *
 * The reading is total over this, not over "a `Properties` or null": a file that
 * is there and unparseable is a third answer, and a nullable loader type cannot
 * say it — it says it by throwing, past every refusal that names the fix.
 * `Properties` itself stops here, so nothing above [WebAssets.readManifest]
 * knows the manifest is a properties file.
 */
internal sealed interface ManifestRead {
  /** Not on the classpath at all. */
  data object Missing : ManifestRead

  /** On the classpath, and the load failed. */
  data class Unreadable(
    val cause: Throwable,
  ) : ManifestRead

  /** Loaded: exactly what the file said, as plain entries. */
  data class Entries(
    val values: Map<String, String>,
  ) : ManifestRead
}

/**
 * A manifest this build cannot use, carrying the [resolution] that says WHY
 * (RFC 225).
 *
 * The reason the refusals are a sealed type at all is that "which failure was
 * it?" must not be answerable only by matching a substring of an English
 * sentence. Throwing `IllegalStateException(message)` would put the loss back
 * one line before the crash: this exception carries the variant through the
 * throw, and the sentence is DERIVED from it.
 */
internal class UnusableWebAssetManifest(
  val resolution: MainBundleResolution,
) : IllegalStateException(manifestFailureMessage(resolution), manifestFailureCause(resolution)) {
  internal companion object {
    /** Running the build is the fix in every case, so every message ends with it. */
    const val FIX_COMMAND = "nix develop -c bin/build-web-assets"
  }
}

/**
 * The ONE place a resolution becomes prose. Exhaustive with no `else`, so a
 * variant added to [MainBundleResolution] fails to compile here rather than
 * arriving at an operator as a message that explains nothing.
 */
private fun manifestFailureMessage(resolution: MainBundleResolution): String {
  val detail =
    when (resolution) {
      // Not reachable from [WebAssets.bundleUrlOf], which throws only on a
      // refusal. Spelled out rather than lumped into an `else`, because the
      // `else` is what would swallow the NEXT variant.
      is MainBundleResolution.Resolved -> {
        "[${WebAssets.MANIFEST_RESOURCE}] resolved [${resolution.url}], which is not a failure"
      }

      MainBundleResolution.Absent -> {
        "[${WebAssets.MANIFEST_RESOURCE}] is not on the classpath: the web assets have never been built"
      }

      is MainBundleResolution.Unreadable -> {
        "[${WebAssets.MANIFEST_RESOURCE}] is on the classpath but could not be read: [${resolution.cause}]"
      }

      MainBundleResolution.NoMainKey -> {
        "[${WebAssets.MANIFEST_RESOURCE}] carries no [${WebAssets.MAIN_KEY}] key"
      }

      is MainBundleResolution.UnexpectedKeys -> {
        "[${WebAssets.MANIFEST_RESOURCE}] carries [${resolution.keys.joinToString()}] beyond " +
          "[${WebAssets.MAIN_KEY}]: it is generated, and carries exactly one key"
      }

      is MainBundleResolution.NotContentAddressed -> {
        "[${WebAssets.MANIFEST_RESOURCE}] names [${resolution.value}], which is not a content-addressed bundle name"
      }

      is MainBundleResolution.NotPackaged -> {
        "[${WebAssets.MANIFEST_RESOURCE}] names [${resolution.value}], but [${resolution.resourcePath}] is not " +
          "on the classpath: the manifest and the built bundle disagree"
      }
    }
  return "$detail. Run: ${UnusableWebAssetManifest.FIX_COMMAND}"
}

/**
 * The underlying fault, kept as the exception's CAUSE. Only one refusal has one:
 * a corrupt manifest, whose `Malformed \uxxxx encoding` is the detail nobody can
 * reconstruct from the sentence above.
 */
private fun manifestFailureCause(resolution: MainBundleResolution): Throwable? = (resolution as? MainBundleResolution.Unreadable)?.cause

/**
 * The one name of the built client-side bundle (RFC 225).
 *
 * `bin/build-web-assets` writes `main-<hash>.js` under the static resources and
 * records its URL in `/web-assets.properties` on the classpath — OUTSIDE
 * `static/`, so the manifest itself is never served. This object reads that
 * file once, at class-init, and every page renders [MAIN].
 *
 * The point is that NO string in Kotlin names the artifact. A literal
 * `/js/main-0123456789ab.js` in a layout or a test is a name that can drift
 * from the file on disk, and nothing would fail: the page would render a
 * `<script src>` for a 404, and every test that reads status codes would stay
 * green.
 *
 * Every way the manifest can be unusable is checked at STARTUP and not at the
 * first page view: a missing manifest, an unreadable one, a missing or
 * malformed `main` key, a surplus key, and a value naming a resource that is not
 * on the classpath. An `object` initialises lazily, so `publicWebModule` READS
 * [MAIN] while it installs — that touch is what makes "at startup" true for the
 * daemon and for every `testApplication` boot alike. Each message names
 * `bin/build-web-assets`, because running it is the fix in every case.
 */
object WebAssets {
  /** The manifest, on the classpath, outside `static/` so the mount cannot serve it. */
  internal const val MANIFEST_RESOURCE = "/web-assets.properties"

  /** The one key the manifest must carry, and the entry name the bundle is built from. */
  internal const val MAIN_KEY = "main"

  /**
   * The classpath package the static mount serves at the site root. ONE name:
   * `Routing.kt` mounts it and [staticResourcePathOf] resolves against it, so
   * the mount and the packaging check cannot come to disagree about where the
   * bundle lives.
   */
  internal const val STATIC_RESOURCE_PACKAGE = "static"

  /**
   * The directory under [STATIC_RESOURCE_PACKAGE] that `bin/build-web-assets`
   * writes the bundle into and the mount serves it from. Named for the same
   * reason the package is, and read by [HASHED_BUNDLE_NAME]: spelled a second
   * time, the pattern could be renamed on its own and would then refuse exactly
   * the file the build writes. `bin/build-web-assets`' `BUNDLE_DIRECTORY` is the
   * shell half of this one decision.
   */
  internal const val BUNDLE_DIRECTORY = "js"

  /**
   * How many hex characters of the bundle's own sha256 its name carries. This
   * one number decides collision resistance for every asset URL the site
   * serves, and `bin/build-web-assets`' own `BUNDLE_HASH_HEX_LENGTH` — the same
   * name, fed to `cut` — is the shell half of the same decision. Written as a literal inside the pattern it
   * could be raised on one side only, and the validator would then refuse every
   * bundle the build writes.
   */
  internal const val BUNDLE_HASH_HEX_LENGTH = 12

  /**
   * The shape `bin/build-web-assets` produces: the entry name, then
   * [BUNDLE_HASH_HEX_LENGTH] hex characters of the bundle's own sha256. Asserted, so
   * a hand-edited manifest naming an un-hashed file fails at startup rather than
   * serving a name that cannot be cached.
   *
   * Every part is a NAME — [BUNDLE_DIRECTORY], [MAIN_KEY], [BUNDLE_HASH_HEX_LENGTH]
   * — rather than a literal: one bundle has one directory, one entry name and
   * one hash length, and a pattern that spelled any of them again could be
   * changed on its own.
   */
  internal val HASHED_BUNDLE_NAME =
    Regex(
      "^/${Regex.escape(BUNDLE_DIRECTORY)}/${Regex.escape(MAIN_KEY)}-[0-9a-f]{$BUNDLE_HASH_HEX_LENGTH}\\.js$",
    )

  /**
   * The URL of the built bundle, e.g. `/js/main-0123456789ab.js`.
   *
   * Reading it is what makes a broken manifest a failed BOOT rather than a page
   * whose script 404s.
   */
  val MAIN: String = bundleUrlOf(mainFrom(readManifest(), ::classpathResourceExists))

  /**
   * The ONE boundary where a refusal becomes a crash: the reading is total, and
   * this is the single place that decides an unusable manifest is unrecoverable.
   *
   * A function rather than the body of [MAIN] because an `object` initialises
   * once per JVM: inlined, the throw could never be exercised by a test, and an
   * unexercised crash boundary is indistinguishable from one that was deleted.
   * The refusal travels WITH the exception, so a caller or a log can tell
   * "never built" from "the manifest disagrees with the tree" without matching
   * prose.
   */
  internal fun bundleUrlOf(resolution: MainBundleResolution): String =
    when (resolution) {
      is MainBundleResolution.Resolved -> resolution.url
      else -> throw UnusableWebAssetManifest(resolution)
    }

  /**
   * The reading, as a total FUNCTION of a manifest read and a resource lookup.
   *
   * It is separated from the classpath read for one reason: an `object`
   * initialises once per JVM, so a test that only reads [MAIN] can assert that
   * a GOOD manifest works and can never assert that a bad one is refused. The
   * refusals are the part worth testing — they are what stands between a
   * hand-edited manifest and a page whose script silently 404s.
   *
   * The order of the checks is the order an operator can act on: the required
   * key first, then drift in the file, then the shape of the value, then the
   * tree it points at.
   */
  internal fun mainFrom(
    manifest: ManifestRead,
    resourceExists: (String) -> Boolean,
  ): MainBundleResolution {
    val entries =
      when (manifest) {
        ManifestRead.Missing -> return MainBundleResolution.Absent
        is ManifestRead.Unreadable -> return MainBundleResolution.Unreadable(manifest.cause)
        is ManifestRead.Entries -> manifest.values
      }

    val main = entries[MAIN_KEY] ?: return MainBundleResolution.NoMainKey

    // Sorted, so the refusal reads the same whatever order the file listed them
    // in and a test can name the offending keys.
    val surplus = entries.keys.filterNot { it == MAIN_KEY }.sorted()
    if (surplus.isNotEmpty()) return MainBundleResolution.UnexpectedKeys(surplus)

    if (!HASHED_BUNDLE_NAME.matches(main)) return MainBundleResolution.NotContentAddressed(main)

    // The manifest can name a file that is not there: a stale manifest, a
    // half-applied merge, a bundle deleted by hand. The page would then load a
    // 404 and lose every enhancement, silently.
    val resourcePath = staticResourcePathOf(main)
    if (!resourceExists(resourcePath)) return MainBundleResolution.NotPackaged(main, resourcePath)

    return MainBundleResolution.Resolved(main)
  }

  /**
   * The classpath resource behind a served URL. The static mount serves the
   * [STATIC_RESOURCE_PACKAGE] package at the site root, so `/js/x.js` is
   * `/static/js/x.js`.
   *
   * Private: its domain is a URL this object has already validated, and a
   * caller free to pass any string at all can pass this function its own
   * output. A test that wants the packaged path composes it from
   * [STATIC_RESOURCE_PACKAGE] and [MAIN], the same two names used here.
   */
  private fun staticResourcePathOf(url: String): String = "/$STATIC_RESOURCE_PACKAGE$url"

  /**
   * The classpath read, as one of three answers. `Properties.load` throws on a
   * malformed `\u` escape and on an I/O fault; left to escape, those would boot
   * with a message that never names the build script, past four refusals that
   * do.
   */
  private fun readManifest(): ManifestRead {
    val stream = javaClass.getResourceAsStream(MANIFEST_RESOURCE) ?: return ManifestRead.Missing
    return try {
      stream.use { open ->
        val properties = Properties().apply { load(open) }
        ManifestRead.Entries(properties.stringPropertyNames().associateWith { properties.getProperty(it) })
      }
    } catch (failure: IOException) {
      ManifestRead.Unreadable(failure)
    } catch (failure: IllegalArgumentException) {
      ManifestRead.Unreadable(failure)
    }
  }

  private fun classpathResourceExists(path: String): Boolean = javaClass.getResource(path) != null
}
