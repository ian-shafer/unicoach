package ed.unicoach.web

import ed.unicoach.coaching.collegelist.CollegeListService
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.web.CollegeListTestSupport.getAs
import ed.unicoach.web.auth.CSRF_HEADER
import ed.unicoach.web.render.ADDED_RECEIPT
import ed.unicoach.web.render.ADD_A_COLLEGE
import ed.unicoach.web.render.ALREADY_ON_LIST_MESSAGE
import ed.unicoach.web.render.BACK_TO_MY_COLLEGES
import ed.unicoach.web.render.CAPPED_NOTICE
import ed.unicoach.web.render.COLLEGE_ID_PARAM
import ed.unicoach.web.render.COLLEGE_NAME_LABEL
import ed.unicoach.web.render.COLLEGE_QUERY_PARAM
import ed.unicoach.web.render.CSRF_FIELD
import ed.unicoach.web.render.NOT_SAVED_MESSAGE
import ed.unicoach.web.render.NO_COLLEGES_MATCH
import ed.unicoach.web.render.SEARCH_LABEL
import ed.unicoach.web.render.SEARCH_PROMPT
import ed.unicoach.web.render.SEARCH_SHOWN
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.content.TextContent
import io.ktor.http.encodeURLParameter
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Add a college (RFC 271): the name search on `/colleges/add` and the add it
 * posts to `/colleges` — over the real database, the real search index and the
 * real services, asserting what was rendered, where the caller was sent, and
 * which rows exist.
 */
class CollegeSearchRoutingTest {
  @BeforeEach
  fun reset() = WebAuthTestSupport.resetDatabase()

  // --- 3. the first-session test -------------------------------------------------------

  @Test
  fun `a student searches, adds a college, and lands on it on the list`() {
    testApplication {
      application { testPublicWebModule() }
      val (studentId, token) = CollegeListTestSupport.signedInStudent()
      val word = CollegeListTestSupport.uniqueWord()
      val collegeId = CollegeListTestSupport.seedCollege("$word University")

      val search = getAs(searchPath(word), token)

      assertEquals(HttpStatusCode.OK, search.status)
      val page = Jsoup.parse(search.bodyAsText())
      assertEquals(ADD_A_COLLEGE, page.selectFirst("main h1")?.text())
      assertEquals(BACK_TO_MY_COLLEGES, page.selectFirst("main a[href=/colleges]")?.text())
      val card = assertNotNull(page.selectFirst(".college-results li"), "no result card")
      assertEquals(listOf("$word University", "Townsville, CA"), card.select("p").map { it.text() })
      val addForm = assertNotNull(card.selectFirst("form"))
      assertEquals("/colleges", addForm.attr("action"))
      assertEquals("post", addForm.attr("method"))
      assertEquals(collegeId.value.toString(), addForm.selectFirst("input[name=$COLLEGE_ID_PARAM]")?.attr("value"))
      assertEquals(word, addForm.selectFirst("input[name=$COLLEGE_QUERY_PARAM]")?.attr("value"))
      assertNotNull(addForm.selectFirst("input[type=hidden][name=$CSRF_FIELD]"))
      assertEquals("Add $word University", addForm.selectFirst("button")?.attr("aria-label"))

      val added = add(token, collegeId.value.toString(), word)

      assertEquals(HttpStatusCode.SeeOther, added.status)
      val entry = CollegeListTestSupport.entriesOf(studentId).single()
      assertEquals("/colleges?added=${entry.id.asString}#${entry.id.asString}", added.headers[HttpHeaders.Location])
      assertEquals(CollegeListEntryStatus.CONSIDERING, entry.status)
      assertEquals(null, entry.reasons)
      assertEquals(null, entry.livingPlan)

      val list = Jsoup.parse(getAs("/colleges?added=${entry.id.asString}", token).bodyAsText())
      val row = assertNotNull(list.getElementById(entry.id.asString), "no row for the added entry")
      assertTrue(row.text().contains("$word University"))
      assertTrue(row.text().contains("Considering"))
      assertEquals(ADDED_RECEIPT, row.selectFirst(".college-receipt")?.text())
    }
  }

  // --- 5. capped ------------------------------------------------------------------------

  @Test
  fun `more matches than the page shows are cut at twenty with the capped notice, and no paging`() {
    testApplication {
      application { testPublicWebModule() }
      val (_, token) = CollegeListTestSupport.signedInStudent()
      val many = CollegeListTestSupport.uniqueWord()
      CollegeListTestSupport.seedColleges((1..SEARCH_SHOWN + 3).map { "$many College $it" })
      val exact = CollegeListTestSupport.uniqueWord()
      CollegeListTestSupport.seedColleges((1..SEARCH_SHOWN).map { "$exact College $it" })

      val capped = Jsoup.parse(getAs(searchPath(many), token).bodyAsText())
      assertEquals(SEARCH_SHOWN, capped.select(".college-results li").size)
      assertTrue(capped.text().contains(CAPPED_NOTICE), "the capped notice is missing")
      assertNoPaging(capped)

      val full = Jsoup.parse(getAs(searchPath(exact), token).bodyAsText())
      assertEquals(SEARCH_SHOWN, full.select(".college-results li").size)
      assertFalse(full.text().contains(CAPPED_NOTICE), "exactly twenty matches said there were more")
      assertNoPaging(full)
    }
  }

  // --- 6. search states -----------------------------------------------------------------

  @Test
  fun `no query, a blank one, no match and an over-long one each read as the page says`() {
    testApplication {
      application { testPublicWebModule() }
      val (_, token) = CollegeListTestSupport.signedInStudent()

      for (path in listOf("/colleges/add", "/colleges/add?q=", "/colleges/add?q=%20%20")) {
        val response = getAs(path, token)
        assertEquals(HttpStatusCode.OK, response.status, "[$path]")
        val page = Jsoup.parse(response.bodyAsText())
        assertTrue(page.text().contains(SEARCH_PROMPT), "[$path] does not prompt")
        assertTrue(page.select(".college-results").isEmpty(), "[$path] showed results")
      }

      val none = getAs(searchPath(CollegeListTestSupport.uniqueWord()), token)
      assertEquals(HttpStatusCode.OK, none.status)
      assertTrue(Jsoup.parse(none.bodyAsText()).text().contains(NO_COLLEGES_MATCH))

      val long = getAs(searchPath("a".repeat(101)), token)
      assertEquals(HttpStatusCode.BadRequest, long.status)
      assertTrue(Jsoup.parse(long.bodyAsText()).text().contains(SEARCH_PROMPT))
    }
  }

  @Test
  fun `the search form is a GET of q, labelled, capped at the search's length, with its own button`() {
    testApplication {
      application { testPublicWebModule() }
      val (_, token) = CollegeListTestSupport.signedInStudent()

      val page = Jsoup.parse(getAs("/colleges/add?q=Reed", token).bodyAsText())

      val form = assertNotNull(page.selectFirst("main form[method=get]"))
      assertEquals("/colleges/add", form.attr("action"))
      val input = assertNotNull(form.selectFirst("input[name=$COLLEGE_QUERY_PARAM]"))
      assertEquals("100", input.attr("maxlength"))
      assertTrue(input.hasAttr("autofocus"))
      assertEquals("Reed", input.attr("value"))
      assertEquals(COLLEGE_NAME_LABEL, form.selectFirst("label[for=${input.id()}]")?.text())
      assertEquals(SEARCH_LABEL, form.selectFirst("button")?.text())
    }
  }

  // --- 7. duplicate add -----------------------------------------------------------------

  @Test
  fun `adding a college already on the list is refused at 409 over the same results, one row stored`() {
    testApplication {
      application { testPublicWebModule() }
      val (studentId, token) = CollegeListTestSupport.signedInStudent()
      val word = CollegeListTestSupport.uniqueWord()
      val collegeId = CollegeListTestSupport.seedCollege("$word College")
      assertEquals(HttpStatusCode.SeeOther, add(token, collegeId.value.toString(), word).status)

      val again = add(token, collegeId.value.toString(), word)

      assertEquals(HttpStatusCode.Conflict, again.status)
      val page = Jsoup.parse(again.bodyAsText())
      assertEquals(ALREADY_ON_LIST_MESSAGE, page.selectFirst(".notice")?.text())
      assertEquals(listOf("$word College"), page.select(".college-results li").map { it.selectFirst("p")!!.text() })
      assertEquals(word, page.selectFirst("input[name=$COLLEGE_QUERY_PARAM][type=search]")?.attr("value"))
      assertEquals(1, CollegeListTestSupport.entryCount(studentId))
    }
  }

  // --- 12. a write fault ----------------------------------------------------------------

  @Test
  fun `an add the database refuses is said over the same results at 503, nothing stored`() {
    val (studentId, token) = CollegeListTestSupport.signedInStudent()
    val word = CollegeListTestSupport.uniqueWord()
    val collegeId = CollegeListTestSupport.seedCollege("$word College")
    val readOnly = WebAuthTestSupport.readOnlyDatabase()
    try {
      testApplication {
        application { testPublicWebModule(collegeListService = CollegeListService(readOnly)) }

        val response = add(token, collegeId.value.toString(), word)

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val page = Jsoup.parse(response.bodyAsText())
        assertEquals(NOT_SAVED_MESSAGE, page.selectFirst(".form-error")?.text())
        assertEquals(1, page.select(".college-results li").size)
      }
    } finally {
      readOnly.close()
    }
    assertEquals(0, CollegeListTestSupport.entryCount(studentId))
  }

  // --- 14. refused postings -------------------------------------------------------------

  @Test
  fun `an add posting that is not the page's is refused at 400 over the posted search, nothing stored`() {
    testApplication {
      application { testPublicWebModule() }
      val (studentId, token) = CollegeListTestSupport.signedInStudent()
      val word = CollegeListTestSupport.uniqueWord()
      val collegeId = CollegeListTestSupport.seedCollege("$word College").value.toString()

      val refused =
        listOf(
          "non-UUID collegeId" to addForm("not-a-uuid", word),
          "no collegeId" to parameters { append(COLLEGE_QUERY_PARAM, word) },
          "doubled collegeId" to
            parameters {
              append(COLLEGE_ID_PARAM, collegeId)
              append(COLLEGE_ID_PARAM, collegeId)
              append(COLLEGE_QUERY_PARAM, word)
            },
          "a college that does not exist" to addForm("00000000-0000-4000-8000-000000000000", word),
        )
      for ((what, form) in refused) {
        val response = formPost("/colleges", form, sessionToken = token, client = noFollowClient())
        assertEquals(HttpStatusCode.BadRequest, response.status, "[$what]")
        val page = Jsoup.parse(response.bodyAsText())
        assertEquals(ADD_A_COLLEGE, page.selectFirst("main h1")?.text(), "[$what] is not the search page")
        assertEquals(1, page.select(".college-results li").size, "[$what] did not re-run the posted search")
        assertTrue(page.select(".form-error, .notice").isEmpty(), "[$what] said something")
      }
      val notAForm =
        noFollowClient().post("/colleges") {
          header(HttpHeaders.Origin, WebAuthTestSupport.urlBase)
          header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(token))
          header(CSRF_HEADER, WebAuthTestSupport.csrfTokenFor(token)!!)
          setBody(TextContent("{}", ContentType.Application.Json))
        }
      assertEquals(HttpStatusCode.BadRequest, notAForm.status, "[not a form]")

      assertEquals(0, CollegeListTestSupport.entryCount(studentId))
    }
  }

  // --- helpers -------------------------------------------------------------------------

  private fun searchPath(query: String): String = "/colleges/add?q=${query.encodeURLParameter()}"

  private fun addForm(
    collegeId: String,
    query: String,
  ): Parameters =
    parameters {
      append(COLLEGE_ID_PARAM, collegeId)
      append(COLLEGE_QUERY_PARAM, query)
    }

  private suspend fun ApplicationTestBuilder.add(
    token: String,
    collegeId: String,
    query: String,
  ): HttpResponse = formPost("/colleges", addForm(collegeId, query), sessionToken = token, client = noFollowClient())

  /** No link or form on the page moves through the results: the page shows its first twenty and nothing more. */
  private fun assertNoPaging(page: Document) {
    val pagers = page.select("main a[href*=page], main a[href*=offset], main input[name=page], main input[name=offset], main [rel=next]")
    assertTrue(pagers.isEmpty(), "the page offers paging: [$pagers]")
  }
}
