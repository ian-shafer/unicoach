package ed.unicoach.db

import org.junit.jupiter.api.Test
import java.io.File
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The guard on RFC 227's date seam: **one place formats a date a human reads**
 * (brief 0010 D21).
 *
 * It is a NEW file, in a module that is NOT the seam's home, because this
 * brief's standing rule is that the artifact under change cannot be its own
 * witness: a guard inside the file being rewritten gets edited in good faith by
 * the person most likely to break it, because rewriting that file IS the task.
 * It sits beside [PartialDateStorageHasOneReaderTest] (RFC 209), the tree's
 * other cross-module source sweep, and follows its construction exactly.
 *
 * ## What it refuses, and why these three checks close the hole together
 *
 * A month name in Kotlin can only come from one of three places, so all three
 * are refused outside the permitted map:
 *
 * 1. **A date pattern** -- `DateTimeFormatter`, `ofPattern`.
 * 2. **Asking Java for the name** -- `TextStyle`, `getDisplayName`.
 * 3. **Typing it** -- a string literal that IS a month name, full or
 *    abbreviated.
 *
 * The third is the one a hurried future run will want to delete, and it is the
 * one that matters: without it the seam is bypassed by writing
 * `listOf("Jan", "Feb", ...)` instead of calling a formatter, and checks 1 and 2
 * see nothing. Keep all three.
 *
 * ## What it reads
 *
 * EVERY module's main source set, with the module list DERIVED from
 * `settings.gradle.kts` rather than restated here: a hand-written list names the
 * modules that existed the day it was written, and this claim is about all of
 * them. Test source sets are out of scope -- a test may legitimately spell a
 * date to assert one -- and so is `db/schema`, which is not Kotlin.
 *
 * Comments and string literals are separated from code before anything is
 * matched, by [scan] below. That is not fussiness: `"January"` appears inside a
 * KDoc in `:service` describing a school that published a month and no day, and
 * a naive `grep` fails on it; and a naive comment strip that cut at the first
 * `//` would cut inside a `"https://..."` literal and hide whatever followed on
 * that line.
 */
class DateRenderingHasOneSeamTest {
  /** The repository root, reached from the `:db` module directory Gradle runs this test in. */
  private val repoRoot = File("..")

  /**
   * Tokens that build or read a date's human shape. Matched against CODE only,
   * never against a comment or a string literal.
   */
  private val bannedTokens = listOf("DateTimeFormatter", "ofPattern", "TextStyle", "getDisplayName")

  /**
   * Every month name a person writes, full and abbreviated, taken from
   * `java.time` rather than typed out -- a typed list would be the very thing
   * this test refuses.
   */
  private val monthNames: Set<String> =
    Month.entries
      .flatMap { listOf(it.getDisplayName(TextStyle.FULL, Locale.US), it.getDisplayName(TextStyle.SHORT, Locale.US)) }
      .toSet()

  /**
   * The files permitted to name those tokens, each with WHY, so a fifth cannot
   * join by resembling the first four. Note what is NOT here: nothing in
   * `:service`'s coaching copy, nothing in `:public-web`, nothing in `:db`'s
   * models. The moment one of them needs a date pattern again, the seam has been
   * bypassed and this test is the thing that says so.
   */
  private val permitted =
    mapOf(
      "common/src/main/kotlin/ed/unicoach/common/util/UsDate.kt" to
        "the seam itself -- the one place a date gets its human shape",
      "college/src/main/kotlin/ed/unicoach/college/IpedsLoader.kt" to
        "CLOSED_AT_FORMAT PARSES a federal feed's dates; it renders nothing to anyone",
      "service/src/main/kotlin/ed/unicoach/coaching/CoachClock.kt" to
        "COACH_ZONE_LABEL names a TIMEZONE, not a date",
      "admin-web/src/main/kotlin/ed/unicoach/admin/render/CellRender.kt" to
        "TITLE_FORMAT is ISO_OFFSET_DATE_TIME, the machine-readable title attribute, and is not read as prose",
    )

  /**
   * Every declared module's main Kotlin source set, derived from the build file.
   */
  private val scannedRoots: Map<String, File> =
    Regex("""include\("([^"]+)"\)""")
      .findAll(File(repoRoot, "settings.gradle.kts").readText())
      .associate { it.groupValues[1] to File(repoRoot, "${it.groupValues[1]}/src/main/kotlin") }

  @Test
  fun `only the date seam builds a date's human shape`() {
    val presentRoots = scannedRoots.filterValues { it.isDirectory }
    assertTrue(
      presentRoots.size >= 15,
      "expected every declared module to have a Kotlin main source set, saw ${presentRoots.keys}",
    )

    val sources =
      presentRoots.values
        .flatMap { root -> root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") } }
        .distinctBy { it.absolutePath }
        .sortedBy { it.absolutePath }
    // Anti-vacuity: a wrong root sweeps nothing and passes every assertion.
    assertTrue(sources.size > 200, "the sweep read only ${sources.size} files; it is not reading the source tree")

    val scanned = sources.associate { it.relativeTo(repoRoot).invariantSeparatorsPath to scan(it.readText()) }

    val tokenOffenders =
      scanned
        .filterKeys { it !in permitted }
        .mapValues { (_, source) -> bannedTokens.filter { it in source.code } }
        .filterValues { it.isNotEmpty() }
    assertEquals(
      emptyMap(),
      tokenOffenders,
      "a date's human shape is built in ONE place (brief 0010 D21, RFC 227): use ed.unicoach.common.util.UsDate. " +
        "Offenders: [$tokenOffenders]. The permitted files, each for a stated reason, are [${permitted.keys}].",
    )

    val literalOffenders =
      scanned
        .filterKeys { it !in permitted }
        .mapValues { (_, source) -> source.literals.filter { it in monthNames }.distinct() }
        .filterValues { it.isNotEmpty() }
    assertEquals(
      emptyMap(),
      literalOffenders,
      "a month name written as text bypasses the seam exactly as a date pattern does (RFC 227): [$literalOffenders]",
    )
  }

  @Test
  fun `every permit is still earning its place`() {
    // A permit for a file that no longer names anything banned is a stale
    // permit, and a stale permit is this test asserting about a file that moved.
    val stale =
      permitted.keys
        .associateWith { path ->
          val file = File(repoRoot, path)
          when {
            !file.isFile -> "the file does not exist"
            bannedTokens.none { it in scan(file.readText()).code } -> "the file names none of $bannedTokens"
            else -> null
          }
        }.filterValues { it != null }
    assertEquals(emptyMap(), stale, "stale permits: [$stale]; delete the permit or fix the path")
  }

  @Test
  fun `the scanner separates code from comments and strings`() {
    // The scanner is the load-bearing part of this guard, so it is tested
    // directly rather than trusted. Each case below is a real shape from this
    // tree: a month name inside a KDoc, a URL literal that contains `//`, and a
    // raw string.
    val scanned =
      scan(
        """
        /** A school that published "January" with no day. */
        val url = "https://example.org/a" // trailing note with ofPattern in it
        val raw = ${"\"\"\""}TextStyle inside a raw string${"\"\"\""}
        val real = DateTimeFormatter.ofPattern("MMM")
        """.trimIndent(),
      )
    assertTrue("DateTimeFormatter" in scanned.code, "real code must survive the scan")
    assertEquals(
      1,
      Regex("ofPattern").findAll(scanned.code).count(),
      "the `ofPattern` inside a trailing comment must not be read as code: [${scanned.code}]",
    )
    assertTrue("TextStyle" !in scanned.code, "a raw string's contents are not code")
    assertTrue(scanned.literals.none { it == "January" }, "a month name inside a COMMENT is not a string literal")
    assertTrue("January" !in scanned.code, "a KDoc's contents are not code either")
    assertTrue("MMM" in scanned.literals, "a real string literal is collected")
  }

  /** A Kotlin source split into the code outside comments and strings, and the string literals. */
  private data class ScannedSource(
    val code: String,
    val literals: List<String>,
  )

  /**
   * Walk the source once, tracking whether we are in code, a line comment, a
   * block comment, a string, a raw string or a character literal.
   *
   * Deliberately simple and deliberately NOT a Kotlin parser: it does not
   * evaluate string templates, and a `$` interpolation's contents are treated as
   * string rather than code. That is conservative in the safe direction for
   * checks 1 and 2 (an interpolated call is still spelled somewhere in code) and
   * exact for check 3.
   */
  private fun scan(text: String): ScannedSource {
    val code = StringBuilder()
    val literals = mutableListOf<String>()
    val current = StringBuilder()
    var i = 0
    while (i < text.length) {
      when {
        text.startsWith("//", i) -> {
          val end = text.indexOf('\n', i).let { if (it == -1) text.length else it }
          code.append('\n')
          i = end
        }

        text.startsWith("/*", i) -> {
          val end = text.indexOf("*/", i + 2).let { if (it == -1) text.length else it + 2 }
          code.append('\n')
          i = end
        }

        text.startsWith("\"\"\"", i) -> {
          val end = text.indexOf("\"\"\"", i + 3).let { if (it == -1) text.length else it }
          literals += text.substring(i + 3, end)
          i = if (end == text.length) end else end + 3
        }

        text[i] == '"' -> {
          current.setLength(0)
          var j = i + 1
          while (j < text.length && text[j] != '"') {
            if (text[j] == '\\' && j + 1 < text.length) {
              current.append(text[j + 1])
              j += 2
            } else {
              current.append(text[j])
              j++
            }
          }
          literals += current.toString()
          i = if (j < text.length) j + 1 else j
        }

        text[i] == '\'' -> {
          var j = i + 1
          while (j < text.length && text[j] != '\'') j += if (text[j] == '\\') 2 else 1
          i = if (j < text.length) j + 1 else j
        }

        else -> {
          code.append(text[i])
          i++
        }
      }
    }
    return ScannedSource(code.toString(), literals)
  }
}
