package ed.unicoach.web

import ed.unicoach.web.assets.WebAssets
import ed.unicoach.web.common.http.CONTENT_SECURITY_POLICY
import ed.unicoach.web.common.http.CONTENT_SECURITY_POLICY_HEADER
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StaticAssetsTest {
  @Test
  fun `site css is served from the static asset mount with text css content type`() =
    testApplication {
      application { testPublicWebModule() }

      val response = client.get("/site.css")
      assertEquals(HttpStatusCode.OK, response.status)
      assertTrue(
        response.headers[HttpHeaders.ContentType]
          .orEmpty()
          .startsWith(ContentType.Text.CSS.toString()),
        "expected text/css content type for /site.css",
      )
    }

  /**
   * The RFC 225 bundle, fetched at the URL the manifest gives — the path is
   * READ from [WebAssets] and never typed, so a rebuild that changes the hash
   * cannot make this test wrong about what is served.
   */
  @Test
  fun `the built bundle is served from the static mount as javascript`() =
    testApplication {
      application { testPublicWebModule() }

      val response = client.get(WebAssets.MAIN)
      assertEquals(HttpStatusCode.OK, response.status, "[${WebAssets.MAIN}] is not served")
      assertTrue(
        response.headers[HttpHeaders.ContentType]
          .orEmpty()
          .lowercase()
          .contains("javascript"),
        "expected a JavaScript content type for [${WebAssets.MAIN}], got [${response.headers[HttpHeaders.ContentType]}]",
      )
    }

  /**
   * The policy rides the bundle exactly as it rides the stylesheet. A script is
   * the asset a policy most has to cover, and the static mount answers from its
   * own response path.
   */
  @Test
  fun `the built bundle carries the content-security policy`() =
    testApplication {
      application { testPublicWebModule() }

      val response = client.get(WebAssets.MAIN)
      assertEquals(
        CONTENT_SECURITY_POLICY,
        response.headers[CONTENT_SECURITY_POLICY_HEADER],
        "no CSP on the served bundle",
      )
    }

  /**
   * The year-long immutable cache (RFC 225 D-K). The value is typed HERE rather
   * than read from the production constant: a test that asserts a constant
   * equals itself passes after the constant is changed to `max-age=0`, and the
   * promise this header makes to every browser that already holds the response
   * cannot be withdrawn.
   */
  @Test
  fun `the hashed bundle is served as cacheable forever`() =
    testApplication {
      application { testPublicWebModule() }

      val response = client.get(WebAssets.MAIN)
      assertEquals(HttpStatusCode.OK, response.status, "[${WebAssets.MAIN}] is not served")
      assertEquals(
        "public, max-age=31536000, immutable",
        response.headers[HttpHeaders.CacheControl],
        "the content-addressed bundle is not cacheable forever, so every visit re-fetches it",
      )
    }

  /**
   * The other half of the claim, and the one that makes it safe: the header is
   * for content-addressed names ONLY. `/site.css` is a stable name whose bytes
   * change in place, so an immutable year on it would pin last week's stylesheet
   * onto returning visitors with no way to recall it.
   */
  @Test
  fun `site css is not cached forever, because its name is not content-addressed`() =
    testApplication {
      application { testPublicWebModule() }

      val response = client.get("/site.css")
      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals(
        null,
        response.headers[HttpHeaders.CacheControl],
        "[/site.css] carries a Cache-Control it must not: its name is stable, so its bytes change in place",
      )
    }

  /**
   * The content-addressed name is the cache key, so a name that is NOT the
   * current bundle must not answer. If a stale bundle were still served, a
   * browser holding an old page would keep running last week's code and nothing
   * here would notice.
   */
  @Test
  fun `a bundle name that is not the current one is not served`() =
    testApplication {
      application { testPublicWebModule() }

      val response = client.get("/js/main-000000000000.js")
      assertEquals(
        HttpStatusCode.NotFound,
        response.status,
        "a bundle name that is not the built one answered [${response.status}]",
      )
    }

  /**
   * The manifest is on the CLASSPATH but outside `static/`, so the mount must
   * not serve it. It names nothing secret; it is simply not a page, and an
   * asset mount that answers for paths outside its own root is a bug worth
   * catching here rather than in a later slice that puts something else there.
   */
  @Test
  fun `the manifest itself is not served`() =
    testApplication {
      application { testPublicWebModule() }

      assertEquals(
        HttpStatusCode.NotFound,
        client.get(WebAssets.MANIFEST_RESOURCE).status,
        "the web asset manifest is served to the public",
      )
    }
}
