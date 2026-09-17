package ed.unicoach.web

import ed.unicoach.web.common.http.CONTENT_SECURITY_POLICY
import ed.unicoach.web.common.http.CONTENT_SECURITY_POLICY_HEADER
import ed.unicoach.web.render.WHO_PAYS_PATH
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.plugin
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.PathSegmentOptionalParameterRouteSelector
import io.ktor.server.routing.PathSegmentParameterRouteSelector
import io.ktor.server.routing.PathSegmentTailcardRouteSelector
import io.ktor.server.routing.PathSegmentWildcardRouteSelector
import io.ktor.server.routing.RootRouteSelector
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.TrailingSlashRouteSelector
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The served-bytes half of RFC 225 D-G.
 *
 * This reads what a BROWSER receives. It fetches every public route, extracts
 * every script, style element, inline event handler and `style=` attribute from
 * the response HTML, and evaluates each one against the ACTUAL
 * [CONTENT_SECURITY_POLICY] string — the same string the response header
 * carries, read from the header and not from a copy.
 *
 * A CSP violation is invisible server-side: the page renders, the status is
 * 200, and the browser silently refuses to run the script. There is no browser
 * in this repo's gate, so this evaluator stands in for one.
 *
 * The three claims about the served pages are three TESTS — the pages answer,
 * the extractor reads them, and nothing they carry is blocked — because a
 * single method that asserted all three reported only the first one to go, and
 * the three fail for different reasons and are fixed in different places.
 *
 * It carries a NEGATIVE SELF-TEST, because an evaluator that approves
 * everything and an application that violates nothing produce the same green
 * run.
 *
 * Nothing here formats a decision into English. A verdict carries the
 * directive, the source the policy would need and the resource as VALUES, and
 * the sentences are built only inside assertion messages — the self-test used
 * to ask `reason.contains("inline script")`, so a reworded sentence passed
 * silently while nothing was being checked.
 */
class CspConformanceTest {
  /**
   * The precondition the two tests below read from: every public route answers
   * with the status it is listed with, and carries the application's OWN
   * policy. A route that started 404ing with a policy on it would otherwise
   * look conformant — it carries nothing at all.
   */
  @Test
  fun `every public page answers with its status and the application's own policy`() =
    testApplication {
      application { testPublicWebModule() }

      forEachPublicPage { label, expectedStatus, response ->
        assertEquals(expectedStatus, response.status, "[$label] did not answer with [$expectedStatus]")
        assertEquals(
          CONTENT_SECURITY_POLICY,
          policyOf(label, response),
          "[$label] carries a policy that is not the application's",
        )
      }
    }

  /** The claim itself: nothing a public page serves would be refused by the policy it serves it with. */
  @Test
  fun `no public page carries a resource its own policy blocks`() =
    testApplication {
      application { testPublicWebModule() }

      val findings = mutableListOf<String>()
      forEachPublicPage { label, _, response ->
        val policy = policyOf(label, response)
        findings +=
          resourcesOf(response.bodyAsText()).mapNotNull { resource ->
            (verdictFor(policy, resource) as? CspVerdict.Blocked)?.let { "[$label] ${it.describe()}" }
          }
      }

      assertEquals(emptyList(), findings, "a served page carries a resource the policy blocks")
    }

  /**
   * The test above passes trivially for a page the extractor read nothing from,
   * so what it read is asserted PER PAGE — an aggregate floor lets one silent
   * page hide behind the others.
   *
   * Asserted BY KIND rather than as a count: every page in this shell links the
   * stylesheet and loads the one module, and saying that is both stricter than
   * "at least two" (which two scripts and no stylesheet satisfied) and self-
   * explaining, so no number has to be kept in step with a comment.
   */
  @Test
  fun `the extractor reads both a script and a stylesheet on every public page`() =
    testApplication {
      application { testPublicWebModule() }

      var pagesSeen = 0
      forEachPublicPage { label, _, response ->
        val resources = resourcesOf(response.bodyAsText())
        assertTrue(
          resources.any { it.kind == Kind.SCRIPT },
          "the extractor read no script on [$label]: it found [${resources.map { it.describe() }}]",
        )
        assertTrue(
          resources.any { it.kind == Kind.STYLE },
          "the extractor read no stylesheet on [$label]: it found [${resources.map { it.describe() }}]",
        )
        pagesSeen += 1
      }

      // The loop body is the whole test, so a walk that visited nothing would
      // assert nothing and still pass.
      assertTrue(pagesSeen > 0, "no public page was fetched at all: the walk over the public routes read nothing")
    }

  /**
   * The list above is a hand copy of the routing table, so a page added later
   * would be silently unevaluated — the exact failure this test exists to stop.
   *
   * This walks Ktor's OWN routing tree and requires every registered `GET` node
   * that carries a handler to be either evaluated by the tests above or named
   * in [EXCLUDED_GET_ROUTES] with a reason. A new page fails HERE, by name, the
   * first time it is registered.
   */
  @Test
  fun `every registered GET route is evaluated or named as an exclusion`() =
    testApplication {
      var registered = emptyList<RegisteredRoute>()
      application {
        testPublicWebModule()
        registered = registeredGetRoutes(plugin(RoutingRoot) as RoutingNode)
      }

      // The application block runs when the server starts, i.e. on the first call.
      client.get("/healthz")

      assertTrue(registered.isNotEmpty(), "the routing walk found no GET routes at all: it is not reading the tree")

      val accounted = EVALUATED_GET_ROUTES + EXCLUDED_GET_ROUTES.keys
      assertEquals(
        emptyList(),
        registered.filterNot { accounted.contains(it) },
        "a registered GET route is neither evaluated by this test nor a named exclusion",
      )
      assertEquals(
        emptyList(),
        accounted.filterNot { registered.contains(it) }.sortedBy { it.path },
        "this test names a GET route the application does not register: the list has gone stale",
      )
    }

  /**
   * The script the rail added, SEEN in the served bytes — the positive control
   * for the tests above. Without it, an extractor that silently stopped finding
   * scripts would report "nothing blocked" for a page full of inline ones.
   */
  @Test
  fun `the served home page loads exactly one external module script`() =
    testApplication {
      application { testPublicWebModule() }

      val scripts = resourcesOf(client.get("/").bodyAsText()).filter { it.kind == Kind.SCRIPT }
      assertEquals(1, scripts.size, "expected exactly one script on the home page, found [${scripts.map { it.describe() }}]")
      assertTrue(
        scripts.single() is PageResource.External,
        "the one script on the home page is not an external file: [${scripts.single().describe()}]",
      )
    }

  /**
   * The self-test. A synthetic page carrying the things the policy exists to
   * refuse must be reported blocked, one verdict each, and each verdict is
   * asserted as a VARIANT — the resource it names and the source the policy
   * lacks — rather than by searching a sentence for a phrase.
   *
   * The page also carries a SINGLE-QUOTED handler and an UNQUOTED `src`,
   * because the extractor's attribute regexes once read double-quoted values
   * only: every other quoting form HTML allows was dropped unread, and a
   * dropped resource is reported as a clean page.
   */
  @Test
  fun `the evaluator blocks an inline script, an inline style and an inline handler`() {
    val synthetic =
      """
      <html>
        <head><style>body { color: red }</style></head>
        <body style="color: red">
          <script>alert(1)</script>
          <button onclick="alert(2)">go</button>
          <button onclick='alert(3)'>go</button>
          <script src="https://cdn.example.com/x.js"></script>
          <script src=https://cdn.example.com/y.js></script>
        </body>
      </html>
      """.trimIndent()

    val blocked = blockedResourcesIn(synthetic)

    // Seven: the inline <style>, the style= attribute, the inline <script>, the
    // two onclick handlers, and the two cross-origin scripts — which are
    // blocked because this policy allows same-origin files and nothing else, so
    // the missing source is the ORIGIN rather than a keyword.
    assertEquals(
      7,
      blocked.size,
      "the evaluator approved something this policy forbids; it reported [${blocked.map { it.describe() }}]",
    )
    assertEquals(
      listOf("alert(1)", "alert(2)", "alert(3)"),
      blocked.mapNotNull { (it.resource as? PageResource.Inline)?.code?.trim() }.filter { it.startsWith("alert") },
      "an inline script or event handler was approved: [${blocked.map { it.describe() }}]",
    )
    assertTrue(
      blocked.any { it.resource == PageResource.Inline(Kind.STYLE, "body { color: red }") },
      "the inline <style> element was approved: [${blocked.map { it.describe() }}]",
    )
    assertTrue(
      blocked.any { it.resource == PageResource.Inline(Kind.STYLE, "color: red") },
      "the inline style attribute was approved: [${blocked.map { it.describe() }}]",
    )
    assertEquals(
      2,
      blocked.count { it.missingSource == MissingSource.Origin("https://cdn.example.com") },
      "a cross-origin script was approved, or its origin was not read: [${blocked.map { it.describe() }}]",
    )
  }

  /**
   * The other direction of the same self-test: a page carrying the shapes this
   * shell actually renders is approved. An evaluator that blocked everything
   * would satisfy the test above and would make the conformance test above IT
   * pass for the wrong reason — there would simply be no real page it approved.
   */
  @Test
  fun `the evaluator approves an external same-origin script and stylesheet`() {
    val shaped =
      """
      <html>
        <head>
          <link rel="stylesheet" href="/site.css">
          <script type="module" src="/js/main-0123456789ab.js"></script>
        </head>
        <body><p>text</p></body>
      </html>
      """.trimIndent()

    val resources = resourcesOf(shaped)
    assertEquals(
      listOf(
        PageResource.External(Kind.SCRIPT, "/js/main-0123456789ab.js"),
        PageResource.External(Kind.STYLE, "/site.css"),
      ),
      resources,
      "the extractor did not read the two resources this shell renders",
    )
    assertEquals(
      emptyList(),
      resources.filter { verdictFor(CONTENT_SECURITY_POLICY, it) is CspVerdict.Blocked },
      "the evaluator blocks the shapes this shell renders",
    )
  }

  /**
   * An element the extractor cannot read a URL out of is REPORTED, not dropped.
   *
   * Dropping it is the worst failure this file can have: the page that could
   * not be read and the page that carries nothing produce the same green run,
   * so a rewritten shell could stop being evaluated without one test going red.
   */
  @Test
  fun `the evaluator blocks a script and a stylesheet link whose URL it cannot read`() {
    val unreadable =
      """
      <html>
        <head><link rel="stylesheet"></head>
        <body><script></script></body>
      </html>
      """.trimIndent()

    val blocked = blockedResourcesIn(unreadable)

    assertEquals(
      2,
      blocked.size,
      "an element with no readable URL was dropped instead of reported: [${blocked.map { it.describe() }}]",
    )
    assertEquals(
      listOf(Kind.SCRIPT, Kind.STYLE),
      blocked
        .map { it.resource }
        .filterIsInstance<PageResource.Unreadable>()
        .map { it.kind }
        .sorted(),
      "the unreadable script and stylesheet link were not both reported: [${blocked.map { it.describe() }}]",
    )
    assertTrue(
      blocked.all { it.missingSource == MissingSource.Unnameable },
      "an unreadable element was graded against a source, which it cannot be: [${blocked.map { it.describe() }}]",
    )
  }

  /**
   * `'self'` approves a same-origin PATH. An empty `src` and a relative one are
   * neither: the first is the state RFC 225 D-G's sealed pair was meant to
   * remove — `External(url = "")` missed the absolute-URL test and landed on
   * the `'self'` arm — and the second is a shape this shell never renders and
   * this evaluator does not model. Both are refused rather than approved.
   */
  @Test
  fun `the evaluator blocks an external resource with a blank or relative URL`() {
    val loose =
      """
      <html>
        <head><link rel="stylesheet" href=""></head>
        <body><script src="main.js"></script></body>
      </html>
      """.trimIndent()

    val blocked = blockedResourcesIn(loose)

    assertEquals(
      listOf(
        PageResource.External(Kind.SCRIPT, "main.js"),
        PageResource.External(Kind.STYLE, ""),
      ),
      blocked.map { it.resource },
      "a blank or relative URL was approved by 'self'",
    )
    assertTrue(
      blocked.all { it.missingSource == MissingSource.Unnameable },
      "a URL this evaluator cannot grade was graded against a source: [${blocked.map { it.describe() }}]",
    )
  }

  /**
   * A browser honours the FIRST copy of a repeated directive and ignores the
   * rest. This evaluator does not model that, so it REFUSES such a policy
   * rather than grading the page against a directive no browser reads — the one
   * thing a stand-in for a browser may not do is quietly pick an answer.
   */
  @Test
  fun `the evaluator refuses a policy that repeats a directive`() {
    val repeated = "default-src 'self'; script-src 'self'; script-src 'unsafe-inline'"

    val failure =
      assertFailsWith<IllegalArgumentException>("a policy with two script-src directives was graded instead of refused") {
        verdictFor(repeated, PageResource.Inline(Kind.SCRIPT, "alert(1)"))
      }

    assertTrue(
      failure.message?.contains("script-src") == true,
      "the refusal does not name the repeated directive: [${failure.message}]",
    )
  }

  /**
   * Every public page, fetched once, handed to [action]. Written once because
   * three tests walk the same set and three copies of the walk would be free to
   * visit different pages — the one thing a coverage guard may not do is check
   * a smaller set than it says.
   */
  private suspend fun io.ktor.server.testing.ApplicationTestBuilder.forEachPublicPage(
    action: suspend (label: String, expectedStatus: HttpStatusCode, response: HttpResponse) -> Unit,
  ) {
    for (page in PUBLIC_ROUTES) {
      action(page.url, page.expectedStatus, client.get(page.url))
    }

    // The outcome page. A POST, so it cannot be reached by a URL in the list
    // above, and the only page here that is rendered from a form submission —
    // the shape most likely to grow an inline handler nobody sees. It is a
    // LABEL here and not a [PublicPage]: that record's `path` is a route path
    // the routing walk below compares against, and "POST /verify-email" is not
    // one.
    action(
      "POST /verify-email",
      HttpStatusCode.OK,
      client.post("/verify-email") {
        setBody(FormDataContent(Parameters.build { append("token", NOT_A_REAL_TOKEN) }))
      },
    )
  }

  /** Every blocked verdict on a synthetic page, in extraction order: what the self-tests assert about. */
  private fun blockedResourcesIn(html: String): List<CspVerdict.Blocked> =
    resourcesOf(html).mapNotNull { verdictFor(CONTENT_SECURITY_POLICY, it) as? CspVerdict.Blocked }

  /**
   * Display only, called from assertion messages and from nothing that decides.
   *
   * The prose lives HERE, at the edge, rather than in the extractor: built at
   * construction it became the only record of what was found, and the tests
   * read it back with `String.contains`.
   */
  private fun PageResource.describe(): String =
    when (this) {
      is PageResource.Inline -> "an inline ${kind.name.lowercase()}: [${code.trim()}]"
      is PageResource.External -> "a ${kind.name.lowercase()} from [$url]"
      is PageResource.Unreadable -> "a ${kind.name.lowercase()} element with no readable URL: [${markup.trim()}]"
    }

  /** Display only: the same verdict the assertions match on, as the sentence a person reads. */
  private fun CspVerdict.Blocked.describe(): String =
    "[$directive] carries no ${missingSource.describe()}, so the browser blocks ${resource.describe()}"

  /** Display only. */
  private fun MissingSource.describe(): String =
    when (this) {
      MissingSource.UnsafeInline -> "'unsafe-inline'"
      MissingSource.Self -> "'self'"
      is MissingSource.Origin -> "source for [$origin]"
      MissingSource.Unnameable -> "source that could allow a URL it cannot grade"
    }

  private enum class Kind {
    SCRIPT,
    STYLE,
  }

  /**
   * One thing in the page the policy has an opinion about, as the VALUES the
   * extractor read — never as a sentence about them.
   *
   * Three CASES rather than one record with an `inline` flag and a nullable
   * URL: that shape made `inline = false, url = null` constructible, and the
   * evaluator then graded the empty string as a same-origin path, which `'self'`
   * APPROVES. The state that produced a silent approval is no longer a state.
   */
  private sealed interface PageResource {
    val kind: Kind

    /** Code or style written INTO the page: what `'unsafe-inline'` would be needed for. */
    data class Inline(
      override val kind: Kind,
      val code: String,
    ) : PageResource

    /** A file the page fetches. A URL is not optional here; that is what makes it external. */
    data class External(
      override val kind: Kind,
      val url: String,
    ) : PageResource

    /**
     * An element the policy governs whose URL the extractor could not read —
     * [markup] is the element as it was served.
     *
     * It exists so that "could not read" is an OUTCOME rather than a `continue`.
     * A dropped element makes a page the extractor cannot read indistinguishable
     * from a page that carries nothing, and this whole file is a claim about
     * pages it has read.
     */
    data class Unreadable(
      override val kind: Kind,
      val markup: String,
    ) : PageResource
  }

  /**
   * The source [CONTENT_SECURITY_POLICY] would have to carry for a blocked
   * resource to load. Typed, because it is the one fact a reader of a failure
   * wants and it used to exist only inside an English sentence.
   */
  private sealed interface MissingSource {
    /** Inline code needs this keyword, and this policy carries it nowhere. */
    data object UnsafeInline : MissingSource

    /** A same-origin file needs this keyword. */
    data object Self : MissingSource

    /** A cross-origin file needs a host source naming [origin]; this policy lists no host sources at all. */
    data class Origin(
      val origin: String,
    ) : MissingSource

    /**
     * No source names it. The URL is missing, blank, or not a site-root path,
     * so there is nothing a policy could carry that would make it load — and
     * this evaluator will not guess on the approving side.
     */
    data object Unnameable : MissingSource
  }

  /**
   * What a browser enforcing a policy would do with one resource.
   *
   * It was a nullable `String?`, where `null` meant allowed — so the two valid
   * outcomes of the evaluation were "a string" and "the absence of one", and
   * every call site re-derived the meaning of the absence. [Blocked] now
   * carries the three facts the evaluation knew (which directive, which source
   * was missing, which resource) instead of a formatted sentence built from
   * them and parsed back out with `contains`.
   */
  private sealed interface CspVerdict {
    data object Allowed : CspVerdict

    data class Blocked(
      val directive: String,
      val missingSource: MissingSource,
      val resource: PageResource,
    ) : CspVerdict
  }

  /** One page this test fetches: the route path, the query it is fetched with, and the status it must answer. */
  private data class PublicPage(
    val path: String,
    val query: String = "",
    val expectedStatus: HttpStatusCode = HttpStatusCode.OK,
  ) {
    /** What the client asks for. The path and the query are SEPARATE fields, so no consumer splits a packed string. */
    val url: String get() = if (query.isEmpty()) path else "$path?$query"
  }

  /**
   * One registered route, as the routing tree describes it: a typed method and
   * a path.
   *
   * The guard used to compare Ktor's `toString()` rendering of a node
   * (`"/verify-email/(method:GET)"`), which is a foreign library's debug format
   * — undeclared anywhere, restated at every entry, and silently different if
   * Ktor ever reformats it.
   */
  private data class RegisteredRoute(
    val method: HttpMethod,
    val path: String,
  )

  private companion object {
    /**
     * Every route a stranger can reach without a token, plus the 404 — which is
     * rendered by `StatusPages` from its own response path and is the first
     * page many visitors see. The tokenized report is not here: it needs a live
     * share token, and `ReportLinkContractTest` owns that route.
     */
    val PUBLIC_ROUTES =
      listOf(
        PublicPage("/"),
        PublicPage("/terms"),
        PublicPage("/privacy"),
        PublicPage(WHO_PAYS_PATH),
        // Both branches of the confirm step: no token renders the outcome page,
        // a token renders the confirm FORM, and they are different pages.
        PublicPage("/verify-email"),
        PublicPage("/verify-email", query = "token=$NOT_A_REAL_TOKEN"),
        PublicPage("/no-such-page", expectedStatus = HttpStatusCode.NotFound),
      )

    /** Never a live token: the confirm step makes no backend call, and the fake verifier refuses this one. */
    const val NOT_A_REAL_TOKEN = "not-a-real-token"

    /**
     * The registered `GET` routes the tests above actually fetch, DERIVED from
     * [PUBLIC_ROUTES] rather than copied from it.
     *
     * A hand copy is the failure this list is used to detect, one level up: a
     * path dropped from [PUBLIC_ROUTES] stopped being fetched while still being
     * counted as "evaluated", so the coverage guard stayed green with the
     * coverage gone. Derived, the two cannot disagree.
     *
     * Only the routes that ANSWER are kept: `/no-such-page` matched no route at
     * all — that is what makes it a 404 — so there is no routing node for it and
     * naming one would fail the staleness half of the guard. Two fetches of one
     * route node (`/verify-email` with and without a token) de-duplicate on the
     * path, which is a FIELD here and no longer something to parse out.
     */
    val EVALUATED_GET_ROUTES =
      PUBLIC_ROUTES
        .filter { page -> page.expectedStatus == HttpStatusCode.OK }
        .map { page -> RegisteredRoute(HttpMethod.Get, page.path) }
        .distinct()

    /** Every registered `GET` route this test does NOT evaluate, and why. */
    val EXCLUDED_GET_ROUTES =
      mapOf(
        RegisteredRoute(HttpMethod.Get, "/healthz") to "a JSON liveness probe: no HTML, so no resource for a policy to govern",
        RegisteredRoute(HttpMethod.Get, "/report") to "needs a live share token; ReportLinkContractTest owns that route",
        RegisteredRoute(HttpMethod.Get, "/{...}") to
          "the static mount's tailcard: chrome-less files under static/, not pages; StaticAssetsTest owns it",
      )

    /**
     * Every `GET` route Ktor itself has registered, as a typed method and a path.
     *
     * Read from `RoutingRoot` rather than from the source: a route added to
     * `Routing.kt` appears here with no edit to this test, which is the whole
     * point of the check that consumes it.
     */
    fun registeredGetRoutes(root: RoutingNode): List<RegisteredRoute> {
      val found = mutableListOf<RegisteredRoute>()

      fun walk(node: RoutingNode) {
        val selector = node.selector
        if (selector is HttpMethodRouteSelector && selector.method == HttpMethod.Get && node.hasHandler()) {
          found += RegisteredRoute(selector.method, pathOf(node))
        }
        node.children.forEach { child -> walk(child) }
      }

      walk(root)
      return found
    }

    /** The path a node answers on, assembled from its own chain of selectors up to the root. */
    fun pathOf(node: RoutingNode): String =
      generateSequence(node) { it.parent }
        .toList()
        .reversed()
        .mapNotNull { ancestor -> pathSegmentOf(ancestor.selector) }
        .filter { it.isNotEmpty() }
        .joinToString(separator = "/", prefix = "/")

    /**
     * Ktor's internal marker node for a static mount. It is not a public type,
     * so it cannot be named as one — and being matched by NAME is the safe
     * direction: if Ktor renames it, it stops being recognised, falls into the
     * `error` arm below, and this test says so.
     */
    const val STATIC_CONTENT_SELECTOR = "io.ktor.server.http.content.TailcardSelector"

    /**
     * The path segment [selector] contributes, or `null` when it contributes
     * none — the root, a trailing slash, the method (which this guard carries
     * TYPED, beside the path, rather than as a token inside it), and the
     * wrapper node a static mount adds.
     *
     * A selector this `when` does not know is a FAILURE rather than a skip: a
     * skipped one would silently shorten a path, and a path that collapses onto
     * an evaluated one is a route the coverage guard then counts as evaluated
     * while nobody looks at it.
     */
    fun pathSegmentOf(selector: RouteSelector): String? =
      when {
        selector is RootRouteSelector -> {
          null
        }

        selector is TrailingSlashRouteSelector -> {
          null
        }

        selector is HttpMethodRouteSelector -> {
          null
        }

        selector.javaClass.name == STATIC_CONTENT_SELECTOR -> {
          null
        }

        selector is PathSegmentConstantRouteSelector -> {
          selector.value
        }

        selector is PathSegmentParameterRouteSelector -> {
          "{${selector.name}}"
        }

        selector is PathSegmentOptionalParameterRouteSelector -> {
          "{${selector.name}?}"
        }

        selector is PathSegmentWildcardRouteSelector -> {
          "*"
        }

        selector is PathSegmentTailcardRouteSelector -> {
          "{...}"
        }

        else -> {
          error(
            "the route-coverage guard cannot spell the path of [$selector] ([${selector.javaClass.name}]): " +
              "teach it that selector, or the route it is part of is graded against the wrong path",
          )
        }
      }

    val SCRIPT_ELEMENT = Regex("""(?is)<script\b([^>]*)>(.*?)</script\s*>""")
    val STYLE_ELEMENT = Regex("""(?is)<style\b([^>]*)>(.*?)</style\s*>""")
    val STYLESHEET_LINK = Regex("""(?is)<link\b([^>]*)>""")

    /**
     * The three ways HTML lets an attribute value be written: double-quoted,
     * single-quoted, and unquoted.
     *
     * All three, because reading only the first is how this extractor silently
     * stopped seeing things — `onclick='…'` and `src=…` were not "allowed", they
     * were UNREAD, and an unread resource is reported as a clean page. The
     * quoting form is the alternative that matched, so every regex built on this
     * reads its value with [attributeValueIn].
     */
    const val ATTRIBUTE_VALUE = """("([^"]*)"|'([^']*)'|([^\s>]+))"""

    val STYLE_ATTRIBUTE = Regex("""(?i)\sstyle\s*=\s*$ATTRIBUTE_VALUE""")
    val EVENT_HANDLER_ATTRIBUTE = Regex("""(?i)\s(on[a-z]+)\s*=\s*$ATTRIBUTE_VALUE""")
    val ATTRIBUTE = Regex("""(?i)([a-z-]+)\s*=\s*$ATTRIBUTE_VALUE""")

    /** An absolute or protocol-relative URL: anything that can name an origin other than this one. */
    val ABSOLUTE_URL = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*:|^//""")

    /** The `scheme://host:port` an absolute URL names: what a host source in a policy would have to spell. */
    val URL_ORIGIN = Regex("""^(?:[a-zA-Z][a-zA-Z0-9+.-]*:)?//[^/?#]*""")

    /** The response's own policy header, which every response path must set. */
    fun policyOf(
      label: String,
      response: HttpResponse,
    ): String =
      assertNotNull(
        response.headers[CONTENT_SECURITY_POLICY_HEADER],
        "[$label] carries no [$CONTENT_SECURITY_POLICY_HEADER] header to evaluate against",
      )

    /**
     * The value of an attribute match, taken from whichever of the quoting
     * alternatives in [ATTRIBUTE_VALUE] the regex filled.
     *
     * An unmatched group is `null` here, not `""`, which is why the groups are
     * read through `groups` — `groupValues` renders both as the empty string,
     * and `value=""` is a real attribute value this evaluator must grade.
     */
    fun attributeValueIn(
      match: MatchResult,
      vararg quotingForms: Int,
    ): String =
      quotingForms.asIterable().firstNotNullOfOrNull { group -> match.groups[group]?.value }
        ?: error("[${match.value}] matched an attribute pattern but filled none of its quoting forms")

    /** The attributes of one tag, as a map. */
    fun attributesOf(tag: String): Map<String, String> =
      ATTRIBUTE
        .findAll(tag)
        .associate { match -> match.groupValues[1].lowercase() to attributeValueIn(match, 3, 4, 5) }

    /**
     * Everything in [html] the policy governs.
     *
     * Deliberately regex-based and deliberately small: the shapes being looked
     * for are the ones a policy names. A jsoup rewrite was considered and
     * declined — jsoup is in no build file and no version catalog in this repo,
     * and adding a dependency to the build is not this slice's decision. The
     * cost of the choice is that the quoting forms must be spelled out
     * ([ATTRIBUTE_VALUE]) and that anything unparseable is REPORTED
     * ([PageResource.Unreadable]) rather than skipped.
     *
     * One extractor per kind of resource, concatenated, so the list of things
     * this file claims to cover is the body of this function.
     */
    fun resourcesOf(html: String): List<PageResource> =
      scriptElementsIn(html) +
        styleElementsIn(html) +
        stylesheetLinksIn(html) +
        styleAttributesIn(html) +
        eventHandlersIn(html)

    /** A `<script>` is an inline body, an external `src`, or — empty and source-less — one this evaluator cannot read. */
    fun scriptElementsIn(html: String): List<PageResource> =
      SCRIPT_ELEMENT
        .findAll(html)
        .flatMap { match ->
          val source = attributesOf(match.groupValues[1])["src"]
          val body = match.groupValues[2]
          listOfNotNull(
            PageResource.Inline(Kind.SCRIPT, body).takeIf { body.isNotBlank() },
            source?.let { PageResource.External(Kind.SCRIPT, it) },
            PageResource.Unreadable(Kind.SCRIPT, match.value).takeIf { body.isBlank() && source == null },
          )
        }.toList()

    fun styleElementsIn(html: String): List<PageResource> =
      STYLE_ELEMENT
        .findAll(html)
        .map { match -> PageResource.Inline(Kind.STYLE, match.groupValues[2].trim()) }
        .toList()

    /**
     * A `rel="stylesheet"` link with no readable `href` is REPORTED, not
     * dropped: it used to `continue`, so a stylesheet the extractor could not
     * read counted as a page with nothing on it.
     */
    fun stylesheetLinksIn(html: String): List<PageResource> =
      STYLESHEET_LINK
        .findAll(html)
        .mapNotNull { match ->
          val attributes = attributesOf(match.groupValues[1])
          if (attributes["rel"]?.lowercase()?.contains("stylesheet") != true) {
            null
          } else {
            attributes["href"]
              ?.let { href -> PageResource.External(Kind.STYLE, href) }
              ?: PageResource.Unreadable(Kind.STYLE, match.value)
          }
        }.toList()

    fun styleAttributesIn(html: String): List<PageResource> =
      STYLE_ATTRIBUTE
        .findAll(html)
        .map { match -> PageResource.Inline(Kind.STYLE, attributeValueIn(match, 2, 3, 4)) }
        .toList()

    fun eventHandlersIn(html: String): List<PageResource> =
      EVENT_HANDLER_ATTRIBUTE
        .findAll(html)
        .map { match -> PageResource.Inline(Kind.SCRIPT, attributeValueIn(match, 3, 4, 5)) }
        .toList()

    /**
     * A policy string as `directive -> sources`, e.g. `script-src` -> `['self']`.
     *
     * A REPEATED directive is refused. A browser honours the first copy and
     * ignores the rest; a map keeps the last, so a policy that grew a second
     * `script-src` would be graded against a directive no browser reads. This
     * evaluator stands in for a browser, so where it does not model the browser
     * it says so instead of choosing.
     */
    fun directivesOf(policy: String): Map<String, List<String>> {
      val parsed =
        policy
          .split(";")
          .mapNotNull { part ->
            val tokens = part.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }
            if (tokens.isEmpty()) null else tokens.first().lowercase() to tokens.drop(1)
          }
      val repeated =
        parsed
          .groupingBy { (name, _) -> name }
          .eachCount()
          .filterValues { it > 1 }
          .keys
      require(repeated.isEmpty()) {
        "the policy repeats [${repeated.joinToString()}]: a browser reads the FIRST copy and this evaluator does not model that"
      }
      return parsed.toMap()
    }

    /** `script-src`/`style-src`, falling back to `default-src` as a browser does. */
    fun sourcesFor(
      policy: String,
      directive: String,
    ): List<String> {
      val directives = directivesOf(policy)
      return directives[directive] ?: directives["default-src"] ?: emptyList()
    }

    /**
     * What a browser enforcing [policy] would do with [resource].
     *
     * The rules implemented are the ones this policy uses, and NO others:
     * `'unsafe-inline'` for inline code, and `'self'` for a same-origin file.
     * An external resource is therefore graded same-origin or not, with no host
     * source list and no wildcard — this application serves one policy, that
     * policy lists neither, and an evaluator that modelled them would be
     * approving pages against a policy nobody serves. A future host-source
     * policy then fails LOUDLY here, which is the safe direction: the evaluator
     * says the cross-origin file is blocked until somebody teaches it the rule
     * the new policy actually carries. Nonces and hashes are absent for the same
     * reason.
     *
     * Every URL that is not a site-root path — blank, or relative — is refused
     * for that same reason. `'self'` approving `url = ""` is the hole the sealed
     * [PageResource] was split to close, and the empty string walked back into
     * it.
     */
    fun verdictFor(
      policy: String,
      resource: PageResource,
    ): CspVerdict {
      // An exhaustive `when`, not an `if/else`: the `else` arm graded every
      // future kind against style-src, silently and with the tests still green.
      val directive =
        when (resource.kind) {
          Kind.SCRIPT -> "script-src"
          Kind.STYLE -> "style-src"
        }
      val sources = sourcesFor(policy, directive)

      return when (resource) {
        is PageResource.Inline -> {
          if (sources.contains("'unsafe-inline'")) {
            CspVerdict.Allowed
          } else {
            CspVerdict.Blocked(directive, MissingSource.UnsafeInline, resource)
          }
        }

        is PageResource.Unreadable -> {
          CspVerdict.Blocked(directive, MissingSource.Unnameable, resource)
        }

        is PageResource.External -> {
          when {
            ABSOLUTE_URL.containsMatchIn(resource.url) -> {
              CspVerdict.Blocked(directive, MissingSource.Origin(originOf(resource.url)), resource)
            }

            !resource.url.startsWith("/") -> {
              CspVerdict.Blocked(directive, MissingSource.Unnameable, resource)
            }

            sources.contains("'self'") -> {
              CspVerdict.Allowed
            }

            else -> {
              CspVerdict.Blocked(directive, MissingSource.Self, resource)
            }
          }
        }
      }
    }

    /**
     * The origin [url] names. A URL with a scheme but no host — `data:…` — has
     * no origin to name, so the whole URL is reported: it is what a reader needs
     * to see either way.
     */
    fun originOf(url: String): String = URL_ORIGIN.find(url)?.value ?: url
  }
}
