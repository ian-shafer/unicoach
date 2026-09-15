package ed.unicoach.web

import ed.unicoach.web.common.http.CONTENT_SECURITY_POLICY
import ed.unicoach.web.common.http.CONTENT_SECURITY_POLICY_HEADER
import ed.unicoach.web.render.ShellWidth
import ed.unicoach.web.render.siteLayout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.html.html
import kotlinx.html.stream.createHTML
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MECHANISM tests for the web shell (RFC 210).
 *
 * Brief 0013 D9 is explicit that no automated test may claim the site LOOKS
 * right at a given width — that claim is the D19 device matrix, run by a human
 * and reported by name. So every assertion here pins something a SERVED BYTE
 * can answer: which media queries the stylesheet declares, that no layout
 * container carries a fixed pixel width, that the measure is a custom property,
 * that the policy header rides every kind of response, and that no page carries
 * markup its own policy would refuse. A mechanism being present is a necessary
 * condition for the layout being right; it is not evidence that it is.
 *
 * The stylesheet is fetched THROUGH the static mount rather than read off disk,
 * so what is asserted is what a browser receives — a file that stopped being
 * served would pass a disk read and fail here.
 */
class ShellMechanismTest {
  /**
   * The rendered pages' one responsive precondition: without the viewport meta
   * a phone renders the page at ~980px and scales it down, and every fluid rule
   * in the stylesheet is spent on a viewport that does not exist. Already true
   * before RFC 210; pinned so it cannot be lost.
   */
  @Test
  fun `mechanism - a rendered page declares the device-width viewport`() =
    testApplication {
      application { testPublicWebModule() }

      val body = client.get("/").bodyAsText()
      assertTrue(body.contains("""name="viewport""""), "no viewport meta: a phone would render the page at desktop width")
      assertTrue(
        body.contains("width=device-width, initial-scale=1"),
        "viewport meta present but not device-width/initial-scale=1",
      )
    }

  /** Both breakpoints, and only through the mount, so this reads what a browser reads. */
  @Test
  fun `mechanism - the served stylesheet declares the 640px and 1024px media queries`() =
    testApplication {
      application { testPublicWebModule() }

      val css = fetchServedStylesheet()
      assertTrue(
        Regex("""@media[^{]*${NARROW_BREAKPOINT_PX}px""").containsMatchIn(css),
        "the ${NARROW_BREAKPOINT_PX}px breakpoint is gone: the cost report table would stay a five-column grid on a phone",
      )
      assertTrue(
        Regex("""@media[^{]*${WIDE_BREAKPOINT_PX}px""").containsMatchIn(css),
        "the ${WIDE_BREAKPOINT_PX}px breakpoint is gone: the laptop/desktop step-up is unreachable",
      )
    }

  /**
   * Fluid-first, stated as a refutable property. A fixed width on any of the
   * four containers is exactly the regression this slice exists to remove, and
   * it is invisible in a review of a diff that only adds a media query.
   *
   * The other road to the same fixed column — a pinned layout TOKEN — is the
   * test below. They are two methods because they fail for two different
   * reasons and the fix differs, so a single method would report "the layout is
   * fixed" without saying which half went.
   */
  @Test
  fun `mechanism - no layout container declares an absolute width directly`() =
    testApplication {
      application { testPublicWebModule() }

      val css = fetchServedStylesheet()
      for (selector in listOf("main", ".site-header", ".site-nav", ".site-footer")) {
        val bodies = declarationBlocksFor(css, selector)
        // An empty list would pass the loop below without asserting anything —
        // a renamed selector must fail here, not silently stop being checked.
        assertTrue(bodies.isNotEmpty(), "no rule for [$selector] in the served stylesheet")
        for (declarations in bodies) {
          val fixedWidth =
            declarations.firstOrNull { declaration ->
              declaration.property in SIZING_PROPERTIES && ABSOLUTE_LENGTH.containsMatchIn(declaration.value)
            }
          assertEquals(
            null,
            fixedWidth,
            "[$selector] declares [${fixedWidth?.property}: ${fixedWidth?.value}], an absolute width: the layout is no longer fluid",
          )
        }
      }
    }

  /**
   * The road to a fixed column the sizing properties cannot see: `main` reads
   * its width from `--measure` and its padding from the gutters, so
   * `--measure: 760px` anywhere in the stylesheet pins the page just as hard as
   * `max-width: 760px` on `main` would -- while every sizing property stays a
   * `var(...)` and the test above stays green. The tokens are therefore checked
   * WHEREVER they are declared: `:root`, the wide block that re-states them,
   * and any rule a later slice sets them from.
   *
   * ANY absolute unit, not the `px` spelling alone: `--measure: 48pc` is the
   * same fixed column typed differently, and a guard that names one unit only
   * documents which fixed layouts its author would accept. `--tap-target` is
   * deliberately not in this set -- 44px is a physical floor for a finger and is
   * meant to be absolute.
   */
  @Test
  fun `mechanism - no layout token declares an absolute value`() =
    testApplication {
      application { testPublicWebModule() }

      val css = fetchServedStylesheet()
      val tokenDeclarations =
        allDeclarationBlocks(css)
          .flatten()
          .filter { declaration -> declaration.property in LAYOUT_TOKENS }
      assertTrue(
        tokenDeclarations.isNotEmpty(),
        "no declaration of any of [$LAYOUT_TOKENS] in the served stylesheet: the layout tokens were renamed and this guard stopped checking anything",
      )
      val fixedToken = tokenDeclarations.firstOrNull { ABSOLUTE_LENGTH.containsMatchIn(it.value) }
      assertEquals(
        null,
        fixedToken,
        "the layout token [${fixedToken?.property}] declares the absolute value [${fixedToken?.value}]: " +
          "it reaches main through var(), and the layout is no longer fluid",
      )
    }

  /**
   * Every guard in this file parses LEAF blocks -- a selector, a brace, a body
   * with no brace in it -- which is true of this stylesheet and is exactly the
   * kind of thing that stops being true without anyone noticing. A nested rule
   * would not be parsed by any of them, so it would drop out of the width
   * guard, the token guard and the tap-target guard at once, all three still
   * green. Native CSS nesting is a live browser feature, so this is a thing a
   * later slice can reach for. At-rules (`@media`) nest by design and are the
   * one exception.
   */
  @Test
  fun `mechanism - the served stylesheet nests no rule inside a rule`() =
    testApplication {
      application { testPublicWebModule() }

      assertEquals(
        emptyList(),
        nestingSelectors(fetchServedStylesheet()),
        "a rule nests another rule: every CSS guard in this file parses leaf blocks and would stop seeing those declarations",
      )
    }

  /**
   * The measure is a variable so a wide page (the chat, the college list) is
   * selected by a body class rather than by editing `main`. Both halves are
   * asserted: declaring the property and never consuming it would leave the
   * seam a decoration.
   */
  @Test
  fun `mechanism - the measure is a custom property that main consumes`() =
    testApplication {
      application { testPublicWebModule() }

      val css = fetchServedStylesheet()
      val root = declarationBlocksFor(css, ":root")
      assertTrue(
        root.any { declarations -> declarations.any { it.property == "--measure" } },
        "--measure is not declared in :root: a page cannot widen itself without editing main",
      )
      val mainConsumesIt = declares(css, selector = "main", property = "max-width", valueFragment = "var(--measure")
      assertTrue(mainConsumesIt, "main does not read --measure, so setting it on a body class does nothing")
    }

  /**
   * The chrome class names are a CONTRACT between `Layout.kt` and `site.css`,
   * and nothing else in the tree joins the two halves: rename `site-nav` on one
   * side and the header stops wrapping and the links stop being thumb-sized,
   * with every other test in this file still green. Both directions are read
   * from what is SERVED -- the rendered markup and the stylesheet a browser
   * fetches -- so the join is asserted where it actually has to hold.
   */
  @Test
  fun `mechanism - every chrome class the markup renders is styled by the served stylesheet`() =
    testApplication {
      application { testPublicWebModule() }

      val markup = client.get("/").bodyAsText()
      // Comments STRIPPED, and load-bearing: site.css's own section headers
      // name `.site-header`, `.site-nav` and `.site-footer` in prose, so a
      // search over the raw file finds the class whether or not a rule for it
      // still exists — the deletion this test is here to catch.
      val css = stripBlockComments(fetchServedStylesheet())
      for (chromeClass in listOf("site-header", "site-brand", "site-nav", "site-footer")) {
        assertTrue(
          Regex("""class="[^"]*\b$chromeClass\b""").containsMatchIn(markup),
          "the rendered page carries no [$chromeClass] class: the markup half of the chrome contract was renamed",
        )
        assertTrue(
          Regex("""\.$chromeClass\b""").containsMatchIn(css),
          "the served stylesheet has no rule naming [.$chromeClass]: the class the markup renders is unstyled",
        )
      }
    }

  /**
   * The tap-target floor, stated ONCE for the brand, the nav links and the
   * footer links. One shared rule cannot drift into three different answers --
   * and it is also one edit away from covering none of them, so each selector
   * is named here, and the floor's own token is checked against the 44px a
   * finger needs rather than being trusted because it has the right name.
   */
  @Test
  fun `mechanism - the served stylesheet floors every chrome link at the tap target`() =
    testApplication {
      application { testPublicWebModule() }

      val css = fetchServedStylesheet()
      val floor =
        declarationBlocksFor(css, ":root")
          .flatten()
          .firstOrNull { it.property == "--tap-target" }
      assertEquals(TAP_TARGET, floor?.value, "--tap-target is not the [$TAP_TARGET] floor the chrome rules are sized from")
      for (selector in listOf(".site-header .site-brand", ".site-header .site-nav a", ".site-footer a")) {
        val floored = declares(css, selector, property = "min-height", valueFragment = "var(--tap-target")
        assertTrue(floored, "[$selector] declares no min-height of var(--tap-target): that link is no longer thumb-sized")
      }
    }

  /** The ordinary case: a rendered page. */
  @Test
  fun `mechanism - the content-security policy rides a rendered page`() =
    testApplication {
      application { testPublicWebModule() }

      assertPolicyRides("/", HttpStatusCode.OK, "a rendered page")
    }

  /**
   * The static mount answers from its own response path, so a policy installed
   * on the page routes would leave every asset uncovered.
   */
  @Test
  fun `mechanism - the content-security policy rides a static asset`() =
    testApplication {
      application { testPublicWebModule() }

      assertPolicyRides("/site.css", HttpStatusCode.OK, "a static asset")
    }

  /**
   * The one a route-scoped install would have missed: the 404 is rendered by
   * `StatusPages` for a path that matched no route at all, and an unmatched
   * path is the first thing a stranger asks for.
   */
  @Test
  fun `mechanism - the content-security policy rides the 404`() =
    testApplication {
      application { testPublicWebModule() }

      assertPolicyRides("/no-such-page", HttpStatusCode.NotFound, "the branded 404")
    }

  /**
   * A source-level sweep, so a page that its OWN policy would break fails the
   * build rather than the browser. The policy carries no `'unsafe-inline'`, so
   * an inline `<script>`, an inline event handler, a `style=` attribute or a
   * `kotlinx.html` `unsafe { }` raw-HTML block is a page that renders wrong for
   * a real visitor and right for every test that only reads status codes.
   *
   * Kotlin comments are stripped first — this prose would otherwise trip the
   * sweep on itself — and the file count is asserted, because a sweep that
   * silently found no files passes forever.
   */
  @Test
  fun `mechanism - no rendered page carries inline script or style that its own policy forbids`() {
    val sources = walkRenderSources()
    assertTrue(
      sources.size >= MINIMUM_RENDER_SOURCES,
      "the render sweep found only [${sources.size}] sources, fewer than [$MINIMUM_RENDER_SOURCES]: it is looking in the wrong place",
    )
    // The floor alone is held up by whatever the directory currently holds, so
    // a page LEAVING the package is masked by a page arriving. Naming a few
    // long-lived pages makes the sweep say which files it actually read.
    val swept = sources.map { it.name }.toSet()
    assertEquals(
      emptySet(),
      REQUIRED_RENDER_SOURCES - swept,
      "the render sweep did not read these pages: it is pointed at the wrong directory, or they left the render package unswept",
    )
    val offences =
      sources.flatMap { source ->
        val code = stripKotlinComments(source.readText())
        INLINE_CODE_PATTERNS
          .filter { (_, pattern) -> pattern.containsMatchIn(code) }
          .map { (name, _) -> "[${source.name}]: [$name]" }
      }
    assertEquals(emptyList(), offences, "inline script/style in the render package; the CSP has no 'unsafe-inline'")
  }

  /**
   * The [ShellWidth] seam's default renders nothing AT ALL, not an empty
   * attribute: [ShellWidth.PROSE] carries a null class, and every page written
   * before RFC 210 must keep rendering the bytes it did.
   */
  @Test
  fun `mechanism - the body carries no class attribute at the default shell width`() =
    testApplication {
      application { testPublicWebModule() }

      val body = client.get("/").bodyAsText()
      assertTrue(body.contains("<body>"), "the default layout no longer renders a bare <body>")
    }

  /**
   * The other half of the same seam: the default asserted above would also hold
   * if [ShellWidth] were accepted and then dropped on the floor. `web/02`
   * selects the wide measure by naming a member here, so the member's class has
   * to reach the rendered `<body>` and not merely the function signature.
   */
  @Test
  fun `mechanism - a supplied shell width reaches the rendered body element`() {
    val html = createHTML().html { siteLayout(pageTitle = "Test", width = ShellWidth.WIDE) { } }

    assertTrue(
      Regex("""<body[^>]*\bclass="[^"]*\b${ShellWidth.WIDE.bodyClass}\b""").containsMatchIn(html),
      "the shell width did not reach <body>: naming ShellWidth.WIDE cannot select the wide measure. Rendered: [$html]",
    )
  }

  /**
   * The loop the enum closes: a [ShellWidth] member is a promise that the
   * stylesheet has a rule for it, and nothing but this test joins the two
   * halves. A member added by `web/02` with no matching rule in `site.css`
   * would render a class that selects nothing — the page comes out at the
   * default width, and every other test here stays green.
   *
   * The members are ITERATED, not listed, so the guard covers a member written
   * after this test was.
   */
  @Test
  fun `mechanism - every shell width's body class is styled by the served stylesheet`() =
    testApplication {
      application { testPublicWebModule() }

      // Stripped for the same reason as the chrome-class test above: prose in
      // site.css naming a class would satisfy this search with no rule behind
      // it, which is exactly the missing rule being looked for.
      val css = stripBlockComments(fetchServedStylesheet())
      for (width in ShellWidth.entries) {
        val bodyClass = width.bodyClass ?: continue
        assertTrue(
          Regex("""\.$bodyClass\b""").containsMatchIn(css),
          "the shell width [$width] renders class [$bodyClass] and the served stylesheet has no rule naming it: that width selects nothing",
        )
      }
    }

  /**
   * One response, asserted twice: the status, so a route that stopped answering
   * cannot pass by 404ing with a policy on it, and then the policy itself. The
   * three call sites differ only in which RESPONSE PATH they exercise -- page,
   * static mount, `StatusPages` -- and that is why they stay three tests: a
   * single parameterised one would report "the policy is missing" without
   * saying which of the three paths lost it.
   */
  private suspend fun io.ktor.server.testing.ApplicationTestBuilder.assertPolicyRides(
    path: String,
    expectedStatus: HttpStatusCode,
    responsePathDescription: String,
  ) {
    val response = client.get(path)
    assertEquals(expectedStatus, response.status, "[$path] did not answer with [$expectedStatus]")
    assertEquals(
      CONTENT_SECURITY_POLICY,
      response.headers[CONTENT_SECURITY_POLICY_HEADER],
      "no CSP on [$responsePathDescription]",
    )
  }

  /** The stylesheet as a browser receives it, not as it sits on disk. */
  private suspend fun io.ktor.server.testing.ApplicationTestBuilder.fetchServedStylesheet(): String {
    val response = client.get("/site.css")
    assertEquals(HttpStatusCode.OK, response.status, "/site.css is not served")
    return response.bodyAsText()
  }

  /**
   * One `property: value` pair from a CSS block, NAMED. It was a bare
   * `Pair<String, String>` read positionally, which made a failure message read
   * `(max-width, 760px)` and every read of `.second` a fact the reader had to
   * hold in their head.
   */
  private data class Declaration(
    val property: String,
    val value: String,
  )

  private companion object {
    /** Every property that can pin a container to a fixed size. */
    val SIZING_PROPERTIES = setOf("width", "max-width", "min-width")

    /**
     * The custom properties the page column's width and side padding are read
     * FROM. A pixel value here is a fixed layout arriving by the back door, so
     * they are checked with the same force as the properties above.
     */
    val LAYOUT_TOKENS = setOf("--measure", "--gutter-x", "--gutter-y")

    /**
     * ANY absolute CSS length, not just the `px` spelling.
     *
     * `max-width: 760pt` and `--measure: 48pc` pin the column exactly as hard
     * as `760px` does, and a guard that names one unit is a guard whose author
     * chose which fixed layouts to permit. The set is CSS's own list of
     * absolute units — px, pt, pc, in, cm, mm, Q — and every relative unit
     * (`rem`, `em`, `%`, `vw`, `ch`) is deliberately absent, because a layout
     * expressed in those is the fluid one this slice is defending.
     *
     * Two details of the shape, because both decide what the guard can SEE.
     *
     * The lookbehind stops the pattern firing on the tail of a longer token —
     * the `2in` inside an identifier like `var(--step2in)` — which would
     * report a fixed width that is not there. It also excludes a preceding
     * `.`, and that half used to let a real fixed width straight through:
     * `--measure: .5in` is legal CSS, the leading point is not optional
     * decoration, and with only `\d+(\.\d+)?` to match with, the digit the
     * pattern started at was preceded by `.` and the whole declaration went
     * unseen. The `\.\d+` alternative matches from the POINT, where the
     * lookbehind sees the separator before it rather than the point itself.
     *
     * The `\s*` is slack, not grammar: CSS allows no space between a number
     * and its unit, so a browser would never honour `760 px`. It is here
     * because it can only WIDEN the guard — a stylesheet that has been through
     * a formatter or a generator that split the two cannot use that to hide a
     * fixed width — and a guard is the one place to be wrong in that direction.
     */
    val ABSOLUTE_LENGTH = Regex("""(?i)(?<![\w.])(\d+(\.\d+)?|\.\d+)\s*(px|pt|pc|in|cm|mm|q)\b""")

    /** The two breakpoints, named once for both the pattern and its message. */
    const val NARROW_BREAKPOINT_PX = 640
    const val WIDE_BREAKPOINT_PX = 1024

    /** The finger floor (Apple/Material), asserted against `--tap-target`. */
    const val TAP_TARGET = "44px"

    /**
     * The fewest Kotlin sources the render sweep may see and still be believed.
     * A sweep that silently found nothing reports an empty offender list, which
     * is indistinguishable from a clean tree.
     *
     * The package holds 9 files today, so the floor is one below it rather than
     * equal to it: a page RETIRED by a later slice is a legitimate edit and must
     * not fail this test, while a sweep pointed at the wrong directory — which
     * sees none — still fails. The floor is not what says WHICH pages were read;
     * [REQUIRED_RENDER_SOURCES] below is, and it is the guard that catches a
     * single page quietly leaving.
     */
    const val MINIMUM_RENDER_SOURCES = 8

    /**
     * Page files the sweep must actually have READ. The count alone can be held
     * up by files arriving as pages leave — a page moved out of the render
     * package would stop being swept without the floor ever dropping — so a
     * couple of long-lived pages are named. They are not an exhaustive list and
     * are not meant to be; they are the ones whose absence means the sweep is
     * pointed somewhere else.
     */
    val REQUIRED_RENDER_SOURCES = setOf("Layout.kt", "HomePage.kt", "CostReportPage.kt")

    /**
     * ONE leaf declaration block: a selector list, an opening brace, a body
     * with no brace in it. Written once because both CSS readers here parse the
     * same stylesheet and two copies of one regex drift apart at the first fix
     * — which for a guard means the two guards silently start reading different
     * CSS while both stay green.
     */
    val CSS_BLOCK = Regex("""([^{}]+)\{([^{}]*)}""")

    /** Every leaf block in [css] as (selector list, declarations). */
    fun leafBlocks(css: String): List<Pair<String, List<Declaration>>> =
      CSS_BLOCK
        .findAll(stripBlockComments(css))
        .map { match -> match.groupValues[1].trim() to declarationsOf(match.groupValues[2]) }
        .toList()

    /** Every leaf declaration block in [css], whatever its selector. */
    fun allDeclarationBlocks(css: String): List<List<Declaration>> = leafBlocks(css).map { (_, declarations) -> declarations }

    /**
     * Every selector in [css] that opens a block containing ANOTHER block, an
     * at-rule (`@media`, `@supports`) excepted — those are supposed to nest.
     *
     * [CSS_BLOCK] matches leaf blocks only, so a nested rule is not parsed by
     * any guard here and silently escapes all of them. This turns that
     * assumption into a fact about the served bytes: if nesting ever appears,
     * a test fails rather than a guard quietly stopping.
     */
    fun nestingSelectors(css: String): List<String> {
      val open = ArrayDeque<String>()
      val offenders = mutableListOf<String>()
      val prelude = StringBuilder()
      for (character in stripBlockComments(css)) {
        when (character) {
          '{' -> {
            open.lastOrNull()?.let { parent -> if (!parent.startsWith("@")) offenders += parent }
            open.addLast(prelude.toString().trim())
            prelude.clear()
          }

          '}' -> {
            open.removeLastOrNull()
            prelude.clear()
          }

          else -> {
            prelude.append(character)
          }
        }
      }
      return offenders.distinct()
    }

    /**
     * Does [selector] declare [property] with a value containing [valueFragment]
     * anywhere in the stylesheet? The same nested search — every block for the
     * selector, every declaration in it — was written out at three call sites,
     * where the three were free to disagree about what "declares" means.
     */
    fun declares(
      css: String,
      selector: String,
      property: String,
      valueFragment: String,
    ): Boolean =
      declarationBlocksFor(css, selector).any { declarations ->
        declarations.any { it.property == property && it.value.contains(valueFragment) }
      }

    /**
     * Each pattern names ONE way to put code the policy forbids into a page.
     * They match the mechanism, not one spelling of one call: the `style`
     * attribute and the `<style>` ELEMENT however each is written, any `script`
     * element, any inline event attribute WHATEVER the event is called, and the
     * raw-HTML escape hatch that could carry all three.
     *
     * The event pattern is deliberately open-ended. It was a denylist of eight
     * event names, which let `onmouseover=`, `onkeydown=` and `onpointerdown=`
     * straight through a sweep whose whole job is catching them; a guard that
     * can be walked past by choosing a different event is worse than none,
     * because it reads as protection.
     */
    val INLINE_CODE_PATTERNS =
      listOf(
        "inline <script> in a string literal" to Regex("""(?i)<\s*script"""),
        "a kotlinx.html script element" to Regex("""\bscript\s*[({]"""),
        "an inline <style> element" to Regex("""(?i)<\s*style|\bstyle\s*[({]"""),
        "a style attribute" to Regex("""(?i)\bstyle\s*=|attributes\s*\[\s*"style"\s*]"""),
        // Three spellings of the same attribute: the kotlinx.html property
        // (`onClick = `), the raw attribute map, and a handler written inside a
        // tag in a string literal.
        "an inline event handler" to
          Regex("""\bon[A-Z][A-Za-z]*\s*=|(?i)attributes\s*\[\s*"on[a-z]+"\s*]|(?i)<[^>\n]*\son[a-z]+\s*="""),
        "a raw-HTML unsafe block" to Regex("""\bunsafe\s*[({]"""),
      )

    /**
     * The system property [RENDER_SOURCE_DIRECTORY_PROPERTY] is passed by
     * `public-web/build.gradle.kts`. The path is NOT resolved from the working
     * directory the test JVM inherits: that directory belongs to Gradle, a
     * change to it moves this sweep silently, and a sweep that reads the wrong
     * place reports no offences exactly as a clean tree does.
     */
    const val RENDER_SOURCE_DIRECTORY_PROPERTY = "unicoach.web.renderSourceDirectory"

    /**
     * Every Kotlin source under the render package, RECURSIVELY.
     *
     * `walkTopDown`, not `listFiles`, for the reason the `:service` precedent
     * ([ed.unicoach.coaching.costs.ForbiddenCostArithmeticTest]) already learnt:
     * a page added by a later web slice in a SUB-package would be invisible to a
     * flat read, and invisible without failing anything -- the sweep would still
     * assert an empty offender list. Both the missing property and the missing
     * directory are errors rather than an empty sweep.
     */
    fun walkRenderSources(): List<File> {
      val renderPackagePath =
        System.getProperty(RENDER_SOURCE_DIRECTORY_PROPERTY)
          ?: error(
            "system property [$RENDER_SOURCE_DIRECTORY_PROPERTY] is not set: " +
              "public-web/build.gradle.kts must pass the render package directory to the test JVM",
          )
      val directory = File(renderPackagePath)
      if (!directory.isDirectory) {
        error("the render package [$renderPackagePath] (from [$RENDER_SOURCE_DIRECTORY_PROPERTY]) is not a directory")
      }
      return directory
        .walkTopDown()
        .filter { file -> file.isFile && file.name.endsWith(".kt") }
        .sortedBy { file -> file.absolutePath }
        .toList()
    }

    /**
     * A `/* ... */` block comment, removed. Written ONCE because both readers
     * here strip them -- the Kotlin sweep, so it reads code and not prose about
     * code, and the CSS parser, so a commented-out rule is not parsed as a live
     * one -- and two copies of one regex drift apart at the first fix.
     */
    fun stripBlockComments(source: String): String = source.replace(BLOCK_COMMENT, "")

    val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)

    /**
     * A Kotlin line comment that OWNS its line — nothing before it but
     * whitespace.
     *
     * The naive `//[^\n]*` was wrong, and not harmlessly: it cannot tell a
     * comment from a `//` inside a STRING LITERAL, so it truncated every line
     * holding a `https://` URL and every line whose literal contained a slash
     * pair. A truncated line is code the sweep never reads, so an offence
     * sitting on such a line would be reported clean — the failure mode a guard
     * must not have. Restricting the match to a whole-line comment cannot
     * truncate anything, because a line that BEGINS with `//` has no code on it.
     * The residue is a trailing `// comment` after code, which is prose the
     * sweep may read and at worst reports one offence too many — the safe
     * direction for a guard to be wrong in.
     */
    val WHOLE_LINE_COMMENT = Regex("""(?m)^[ \t]*//[^\n]*$""")

    /** Block and whole-line comments, removed so the sweep reads CODE and not prose about code. */
    fun stripKotlinComments(source: String): String = stripBlockComments(source).replace(WHOLE_LINE_COMMENT, "")

    /**
     * Every declaration block in [css] whose selector list names [selector]
     * exactly, at any nesting depth — so a rule inside a media block is checked
     * on the same footing as a top-level one. Leaf blocks only, which is what
     * `mechanism - the served stylesheet nests no rule inside a rule` proves
     * this stylesheet has below `@media`.
     */
    fun declarationBlocksFor(
      css: String,
      selector: String,
    ): List<List<Declaration>> =
      leafBlocks(css)
        .filter { (selectors, _) -> selectors.split(",").any { it.trim() == selector } }
        .map { (_, declarations) -> declarations }

    /**
     * [Declaration]s, lower-cased property, from one block body.
     *
     * A `;`-segment with no colon used to be DROPPED, so a mangled declaration
     * disappeared from the parse and every guard reading it reported clean on a
     * stylesheet nobody had checked. It is an error instead: this parser is the
     * eyes of four guards, and the one thing it may not do is quietly see less.
     */
    fun declarationsOf(blockBody: String): List<Declaration> =
      blockBody
        .split(";")
        .filter { it.isNotBlank() }
        .map { declaration ->
          val separator = declaration.indexOf(':')
          if (separator <= 0) {
            error("unparsable CSS declaration [${declaration.trim()}]: the stylesheet guards cannot read it, so they cannot guard it")
          }
          Declaration(
            property = declaration.take(separator).trim().lowercase(),
            value = declaration.substring(separator + 1).trim(),
          )
        }
  }
}
