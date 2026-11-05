package ed.unicoach.web

import ed.unicoach.coaching.collegelist.CollegeListService
import ed.unicoach.coaching.collegelist.LivingPlanUpdate
import ed.unicoach.coaching.collegelist.UpdateEntryResult
import ed.unicoach.db.models.CollegeListEntry
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.db.models.LivingArrangement
import ed.unicoach.db.models.StudentId
import ed.unicoach.web.CollegeListTestSupport.getAs
import ed.unicoach.web.auth.CSRF_HEADER
import ed.unicoach.web.render.ADD_A_COLLEGE
import ed.unicoach.web.render.CANCEL_LABEL
import ed.unicoach.web.render.COLLEGE_COLUMN
import ed.unicoach.web.render.CSRF_FIELD
import ed.unicoach.web.render.DATA_LABEL
import ed.unicoach.web.render.DATA_TABLE_CLASS
import ed.unicoach.web.render.FROM_YOUR_CONVERSATIONS
import ed.unicoach.web.render.INVALID_REASONS_MESSAGE
import ed.unicoach.web.render.MOVED_NOTICE
import ed.unicoach.web.render.MY_COLLEGES_TITLE
import ed.unicoach.web.render.NOT_SAVED_MESSAGE
import ed.unicoach.web.render.NOT_SAVED_YET
import ed.unicoach.web.render.NO_COLLEGES_YET
import ed.unicoach.web.render.REASONS_LABEL
import ed.unicoach.web.render.REASONS_PARAM
import ed.unicoach.web.render.REASONS_PLACEHOLDER
import ed.unicoach.web.render.REMOVE_COLLEGE_DETAIL
import ed.unicoach.web.render.REMOVE_COLLEGE_HEADING
import ed.unicoach.web.render.REMOVE_COLLEGE_LABEL
import ed.unicoach.web.render.SAVED_RECEIPT
import ed.unicoach.web.render.SAVE_LABEL
import ed.unicoach.web.render.STATUS_LABEL
import ed.unicoach.web.render.STATUS_PARAM
import ed.unicoach.web.render.VERSION_PARAM
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
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * My colleges (RFC 271): the list on `/colleges`, one entry's edit page and
 * save, and its remove confirmation and remove — over the real database and
 * the real services, asserting what was rendered, where the caller was sent,
 * and which rows and session drafts exist.
 */
class CollegeListRoutingTest {
  @BeforeEach
  fun reset() = WebAuthTestSupport.resetDatabase()

  // --- 1, 2. the door and the empty list -----------------------------------------------

  @Test
  fun `a student's header carries My colleges, and an empty list says so with no table`() {
    testApplication {
      application { testPublicWebModule() }
      val (_, token) = CollegeListTestSupport.signedInStudent()

      val response = getAs("/colleges", token)

      assertEquals(HttpStatusCode.OK, response.status)
      val page = Jsoup.parse(response.bodyAsText())
      assertEquals(MY_COLLEGES_TITLE, page.selectFirst(".site-nav a[href=/colleges]")?.text())
      assertEquals(MY_COLLEGES_TITLE, page.selectFirst("main h1")?.text())
      assertTrue(page.selectFirst("main")!!.text().contains(NO_COLLEGES_YET))
      assertEquals(ADD_A_COLLEGE, page.selectFirst("main a[href=/colleges/add]")?.text())
      assertNull(page.selectFirst("main table"), "an empty list rendered a table")
    }
  }

  // --- 4. the list ----------------------------------------------------------------------

  @Test
  fun `the list shows each entry in creation order, the reasons' first line, and labels every data cell`() {
    testApplication {
      application { testPublicWebModule() }
      val (studentId, token) = CollegeListTestSupport.signedInStudent()
      val word = CollegeListTestSupport.uniqueWord()
      val (first, second) = CollegeListTestSupport.seedColleges(listOf("$word Beta College", "$word Alpha College"))
      val older = CollegeListTestSupport.addEntry(studentId, first, reasons = "  Great labs\nAnd a second line\n")
      val newer = CollegeListTestSupport.addEntry(studentId, second)

      val page = Jsoup.parse(getAs("/colleges", token).bodyAsText())

      val table = assertNotNull(page.selectFirst("main table"))
      assertTrue(table.hasClass(DATA_TABLE_CLASS), "the list is not the shared data table")
      assertEquals(listOf(COLLEGE_COLUMN, STATUS_LABEL, REASONS_LABEL, ""), table.select("thead th").map { it.text() })
      val rows = table.select("tbody tr")
      assertEquals(listOf(older.id.asString, newer.id.asString), rows.map { it.id() }, "not in creation order")
      val cells = rows.first()!!.select("td")
      assertEquals(listOf("$word Beta College", "Considering", "Great labs"), cells.take(3).map { it.text() })
      assertEquals("/colleges/${older.id.asString}", cells[0].selectFirst("a")?.attr("href"))
      assertEquals(REMOVE_COLLEGE_LABEL, cells[3].text())
      assertEquals("/colleges/${older.id.asString}/remove", cells[3].selectFirst("a")?.attr("href"))
      assertEquals("", rows[1].select("td")[2].text(), "an entry with no reasons shows some")
      for (row in rows) {
        assertEquals(
          listOf(COLLEGE_COLUMN, STATUS_LABEL, REASONS_LABEL),
          row.select("td").take(3).map { it.attr(DATA_LABEL) },
          "a data cell carries no phone label",
        )
      }
    }
  }

  // --- 8. save --------------------------------------------------------------------------

  @Test
  fun `saving status and reasons stores both, bumps the version, keeps the living plan, and says Saved`() {
    testApplication {
      application { testPublicWebModule() }
      val (studentId, token) = CollegeListTestSupport.signedInStudent()
      val entry = entryWithLivingPlan(studentId)

      val response = save(token, entry, "applying", "Strong engineering")

      assertEquals(HttpStatusCode.SeeOther, response.status)
      assertEquals("/colleges?saved=${entry.id.asString}#${entry.id.asString}", response.headers[HttpHeaders.Location])
      val stored = CollegeListTestSupport.storedEntry(studentId, entry.id)
      assertEquals(CollegeListEntryStatus.APPLYING, stored.status)
      assertEquals("Strong engineering", stored.reasons)
      assertEquals(entry.version + 1, stored.version)
      assertEquals(LivingArrangement.WITH_FAMILY, stored.livingPlan, "the save touched the living plan")

      val row = Jsoup.parse(getAs("/colleges?saved=${entry.id.asString}", token).bodyAsText()).getElementById(entry.id.asString)!!
      assertEquals(SAVED_RECEIPT, row.selectFirst(".college-receipt")?.text())
      assertTrue(row.text().contains("Applying"))
    }
  }

  @Test
  fun `blank and whitespace-only reasons clear the stored reasons`() {
    testApplication {
      application { testPublicWebModule() }
      val (studentId, token) = CollegeListTestSupport.signedInStudent()
      val collegeId = CollegeListTestSupport.seedCollege("${CollegeListTestSupport.uniqueWord()} College")
      val entry = CollegeListTestSupport.addEntry(studentId, collegeId, reasons = "Was here")

      for (blank in listOf("", "  \n\t ")) {
        val current = CollegeListTestSupport.storedEntry(studentId, entry.id)
        runBlocking { setReasons(studentId, current, "Was here") }
        val before = CollegeListTestSupport.storedEntry(studentId, entry.id)

        assertEquals(HttpStatusCode.SeeOther, save(token, before, "considering", blank).status, "[$blank]")

        assertNull(CollegeListTestSupport.storedEntry(studentId, entry.id).reasons, "[$blank] did not clear the reasons")
      }
    }
  }

  @Test
  fun `the edit page renders the four statuses, the stored values and the version, and a form with a CSRF field`() {
    testApplication {
      application { testPublicWebModule() }
      val (studentId, token) = CollegeListTestSupport.signedInStudent()
      val word = CollegeListTestSupport.uniqueWord()
      val entry = CollegeListTestSupport.addEntry(studentId, CollegeListTestSupport.seedCollege("$word College"), reasons = "Near home")

      val page = Jsoup.parse(getAs("/colleges/${entry.id.asString}", token).bodyAsText())

      assertEquals("$word College", page.selectFirst("main h1")?.text())
      val form = assertNotNull(page.selectFirst("main form"))
      assertEquals("/colleges/${entry.id.asString}", form.attr("action"))
      assertNotNull(form.selectFirst("input[type=hidden][name=$CSRF_FIELD]"))
      assertEquals(entry.version.toString(), form.selectFirst("input[name=$VERSION_PARAM]")?.attr("value"))
      assertEquals(STATUS_LABEL, form.selectFirst("fieldset legend")?.text())
      assertEquals(
        listOf("Considering", "Applying", "Admitted", "Rejected"),
        form.select("input[name=$STATUS_PARAM]").map { radio -> form.selectFirst("label[for=${radio.id()}]")!!.text() },
      )
      assertEquals("considering", checkedStatus(page))
      val reasons = assertNotNull(form.selectFirst("textarea[name=$REASONS_PARAM]"))
      assertEquals("Near home", reasons.`val`())
      assertEquals(REASONS_PLACEHOLDER, reasons.attr("placeholder"))
      assertEquals("2048", reasons.attr("maxlength"))
      assertEquals(REASONS_LABEL, form.selectFirst("label[for=${reasons.id()}]")?.text())
      assertEquals(SAVE_LABEL, form.selectFirst("button")?.text())
      assertEquals(
        "/colleges/${entry.id.asString}/remove",
        form.select("a").firstOrNull { it.text() == REMOVE_COLLEGE_LABEL }?.attr("href"),
      )
      assertFalse(page.text().contains(NOT_SAVED_YET), "an undrafted entry says it is not saved")
      assertNotNull(page.selectFirst("main a[href=/colleges]"), "no way back to the list")
    }
  }

  // --- 9, 10. a moved list --------------------------------------------------------------

  @Test
  fun `a stale save writes nothing, keeps the typing, and is offered again over the current version`() {
    testApplication {
      application { testPublicWebModule() }
      val (studentId, token) = CollegeListTestSupport.signedInStudent()
      val collegeId = CollegeListTestSupport.seedCollege("${CollegeListTestSupport.uniqueWord()} College")
      val read = CollegeListTestSupport.addEntry(studentId, collegeId, reasons = "First")
      // Another device saves after this page was read.
      val elsewhere = runBlocking { setReasons(studentId, read, "Changed on the phone") }

      val response = save(token, read, "admitted", "Typed on the laptop")

      assertEquals(HttpStatusCode.SeeOther, response.status)
      assertEquals("/colleges?moved=1", response.headers[HttpHeaders.Location])
      assertEquals(elsewhere, CollegeListTestSupport.storedEntry(studentId, read.id), "a stale save wrote")
      val list = Jsoup.parse(getAs("/colleges?moved=1", token).bodyAsText())
      assertEquals(MOVED_NOTICE, list.selectFirst("main .notice")?.text())
      val draft = assertNotNull(CollegeListTestSupport.entryDraftOf(token), "the typing was not kept")
      assertEquals(Triple(read.id.asString, "admitted", "Typed on the laptop"), Triple(draft.entryId, draft.status, draft.reasons))

      val reopened = Jsoup.parse(getAs("/colleges/${read.id.asString}", token).bodyAsText())
      assertEquals("admitted", checkedStatus(reopened))
      assertEquals("Typed on the laptop", reopened.selectFirst("textarea[name=$REASONS_PARAM]")?.`val`())
      assertTrue(reopened.text().contains(NOT_SAVED_YET), "the drafted edit does not say it is not saved")
      assertEquals(
        elsewhere.version.toString(),
        reopened.selectFirst("input[name=$VERSION_PARAM]")?.attr("value"),
        "not the CURRENT version",
      )

      val resaved = save(token, elsewhere, "admitted", "Typed on the laptop")

      assertEquals(HttpStatusCode.SeeOther, resaved.status)
      assertEquals("/colleges?saved=${read.id.asString}#${read.id.asString}", resaved.headers[HttpHeaders.Location])
      assertEquals(CollegeListEntryStatus.ADMITTED, CollegeListTestSupport.storedEntry(studentId, read.id).status)
      assertNull(CollegeListTestSupport.entryDraftOf(token), "the saved edit is still drafted")
    }
  }

  @Test
  fun `a save or remove of an entry removed elsewhere lands on the moved notice`() {
    testApplication {
      application { testPublicWebModule() }
      val (studentId, token) = CollegeListTestSupport.signedInStudent()
      val collegeId = CollegeListTestSupport.seedCollege("${CollegeListTestSupport.uniqueWord()} College")
      val entry = CollegeListTestSupport.addEntry(studentId, collegeId)
      runBlocking { WebAuthTestSupport.collegeListService.removeFromList(studentId, entry.id, entry.version).getOrThrow() }

      val saved = save(token, entry, "applying", "Too late")
      val removed = remove(token, entry, entry.version.toString())

      for (response in listOf(saved, removed)) {
        assertEquals(HttpStatusCode.SeeOther, response.status)
        assertEquals("/colleges?moved=1", response.headers[HttpHeaders.Location])
      }
    }
  }

  // --- 11, 12. refused and failed saves -------------------------------------------------

  @Test
  fun `over-long reasons are refused at 400 with the message, nothing written, the typing drafted`() {
    testApplication {
      application { testPublicWebModule() }
      val (studentId, token) = CollegeListTestSupport.signedInStudent()
      val entry =
        CollegeListTestSupport.addEntry(
          studentId,
          CollegeListTestSupport.seedCollege("${CollegeListTestSupport.uniqueWord()} College"),
        )
      val long = "x".repeat(2049)

      val response = save(token, entry, "applying", long)

      assertEquals(HttpStatusCode.BadRequest, response.status)
      val page = Jsoup.parse(response.bodyAsText())
      assertEquals(listOf(INVALID_REASONS_MESSAGE), page.select(".field-error li").map { it.text() })
      assertEquals("applying", checkedStatus(page))
      assertEquals(long, page.selectFirst("textarea[name=$REASONS_PARAM]")?.`val`())
      assertEquals(entry, CollegeListTestSupport.storedEntry(studentId, entry.id), "a refused save wrote")
      assertEquals(long, CollegeListTestSupport.entryDraftOf(token)?.reasons, "the typing was not drafted")
    }
  }

  @Test
  fun `a browser's CRLF newlines are read as LF, so reasons at the ceiling save and are stored with LF`() {
    testApplication {
      application { testPublicWebModule() }
      val (studentId, token) = CollegeListTestSupport.signedInStudent()
      val entry =
        CollegeListTestSupport.addEntry(
          studentId,
          CollegeListTestSupport.seedCollege("${CollegeListTestSupport.uniqueWord()} College"),
        )
      // The box's maxlength counts the newline as one character; the browser
      // posts it as two. 1000 + CRLF + 1047 is 2049 posted, 2048 counted.
      val head = "a".repeat(1000)
      val tail = "b".repeat(CollegeListService.MAX_REASONS_LENGTH - head.length - 1)
      val posted = "$head\r\n$tail"
      assertEquals(CollegeListService.MAX_REASONS_LENGTH + 1, posted.length)

      val response = save(token, entry, "applying", posted)

      assertEquals(HttpStatusCode.SeeOther, response.status)
      assertEquals("$head\n$tail", CollegeListTestSupport.storedEntry(studentId, entry.id).reasons)
    }
  }

  @Test
  fun `a save the database refuses is said at 503 and the typing drafted`() {
    val (studentId, token) = CollegeListTestSupport.signedInStudent()
    val entry =
      CollegeListTestSupport.addEntry(
        studentId,
        CollegeListTestSupport.seedCollege("${CollegeListTestSupport.uniqueWord()} College"),
      )
    val readOnly = WebAuthTestSupport.readOnlyDatabase()
    try {
      testApplication {
        application { testPublicWebModule(collegeListService = CollegeListService(readOnly)) }

        val response = save(token, entry, "rejected", "Did not get in")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val page = Jsoup.parse(response.bodyAsText())
        assertEquals(NOT_SAVED_MESSAGE, page.selectFirst("main .form-error")?.text())
        assertEquals("rejected", checkedStatus(page))
        assertEquals("Did not get in", page.selectFirst("textarea[name=$REASONS_PARAM]")?.`val`())
      }
    } finally {
      readOnly.close()
    }
    assertEquals(entry, CollegeListTestSupport.storedEntry(studentId, entry.id), "the read-only database wrote")
    val draft = assertNotNull(CollegeListTestSupport.entryDraftOf(token))
    assertEquals("rejected" to "Did not get in", draft.status to draft.reasons)
  }

  // --- 13. remove -----------------------------------------------------------------------

  @Test
  fun `the confirmation removes the entry, clears its draft, and a stale one keeps the row`() {
    testApplication {
      application { testPublicWebModule() }
      val (studentId, token) = CollegeListTestSupport.signedInStudent()
      val word = CollegeListTestSupport.uniqueWord()
      val entry = CollegeListTestSupport.addEntry(studentId, CollegeListTestSupport.seedCollege("$word College"))

      val confirm = getAs("/colleges/${entry.id.asString}/remove", token)

      assertEquals(HttpStatusCode.OK, confirm.status)
      val page = Jsoup.parse(confirm.bodyAsText())
      assertEquals(REMOVE_COLLEGE_HEADING, page.selectFirst("main h1")?.text())
      val text = page.selectFirst("main")!!.text()
      assertTrue(text.contains("$word College") && text.contains(REMOVE_COLLEGE_DETAIL), "the confirmation copy is missing")
      val form = assertNotNull(page.selectFirst("main form"))
      assertEquals("/colleges/${entry.id.asString}/remove", form.attr("action"))
      assertEquals(entry.version.toString(), form.selectFirst("input[name=$VERSION_PARAM]")?.attr("value"))
      assertEquals(REMOVE_COLLEGE_LABEL, form.selectFirst("button")?.text())
      assertEquals("/colleges/${entry.id.asString}", page.select("main a").firstOrNull { it.text() == CANCEL_LABEL }?.attr("href"))

      // A stale version first: the row stays.
      val stale = remove(token, entry, (entry.version + 7).toString())
      assertEquals("/colleges?moved=1", stale.headers[HttpHeaders.Location])
      assertNull(CollegeListTestSupport.storedEntry(studentId, entry.id).deletedAt, "a stale remove removed the row")

      // A drafted edit of this entry, then the remove.
      setStale(studentId, entry)
      save(token, entry, "applying", "Drafted")
      assertNotNull(CollegeListTestSupport.entryDraftOf(token))
      val current = CollegeListTestSupport.storedEntry(studentId, entry.id)

      val removed = remove(token, current, current.version.toString())

      assertEquals(HttpStatusCode.SeeOther, removed.status)
      assertEquals("/colleges", removed.headers[HttpHeaders.Location])
      assertNotNull(CollegeListTestSupport.storedEntry(studentId, entry.id).deletedAt, "the row was not soft-deleted")
      assertNull(CollegeListTestSupport.entryDraftOf(token), "the removed entry's draft was kept")
      assertEquals(0, CollegeListTestSupport.entriesOf(studentId).size)
    }
  }

  @Test
  fun `removing one entry leaves another entry's draft`() {
    testApplication {
      application { testPublicWebModule() }
      val (studentId, token) = CollegeListTestSupport.signedInStudent()
      val word = CollegeListTestSupport.uniqueWord()
      val (a, b) = CollegeListTestSupport.seedColleges(listOf("$word A College", "$word B College"))
      val drafted = CollegeListTestSupport.addEntry(studentId, a)
      val other = CollegeListTestSupport.addEntry(studentId, b)
      setStale(studentId, drafted)
      save(token, drafted, "applying", "Drafted")

      assertEquals("/colleges", remove(token, other, other.version.toString()).headers[HttpHeaders.Location])

      assertEquals(drafted.id.asString, CollegeListTestSupport.entryDraftOf(token)?.entryId, "another entry's remove dropped this draft")
    }
  }

  @Test
  fun `a remove the database refuses is said at 503 on the confirmation, the row kept`() {
    val (studentId, token) = CollegeListTestSupport.signedInStudent()
    val entry =
      CollegeListTestSupport.addEntry(
        studentId,
        CollegeListTestSupport.seedCollege("${CollegeListTestSupport.uniqueWord()} College"),
      )
    val readOnly = WebAuthTestSupport.readOnlyDatabase()
    try {
      testApplication {
        application { testPublicWebModule(collegeListService = CollegeListService(readOnly)) }

        val response = remove(token, entry, entry.version.toString())

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val page = Jsoup.parse(response.bodyAsText())
        assertEquals(REMOVE_COLLEGE_HEADING, page.selectFirst("main h1")?.text())
        assertEquals(NOT_SAVED_MESSAGE, page.selectFirst("main .form-error")?.text())
      }
    } finally {
      readOnly.close()
    }
    assertNull(CollegeListTestSupport.storedEntry(studentId, entry.id).deletedAt)
  }

  // --- 14. refused postings -------------------------------------------------------------

  @Test
  fun `a save or remove posting that is not the page's is refused at 400, nothing written, no draft`() {
    testApplication {
      application { testPublicWebModule() }
      val (studentId, token) = CollegeListTestSupport.signedInStudent()
      val entry =
        CollegeListTestSupport.addEntry(
          studentId,
          CollegeListTestSupport.seedCollege("${CollegeListTestSupport.uniqueWord()} College"),
        )
      val version = entry.version.toString()
      val before = WebAuthTestSupport.resolveSession(token)!!.session

      val refusedSaves =
        listOf(
          "no version" to form(status = "applying", reasons = "x"),
          "doubled version" to form(version, version, status = "applying", reasons = "x"),
          "non-integer version" to form("three", status = "applying", reasons = "x"),
          "no status" to form(version, reasons = "x"),
          "doubled status" to
            parameters {
              append(VERSION_PARAM, version)
              append(STATUS_PARAM, "applying")
              append(STATUS_PARAM, "admitted")
              append(REASONS_PARAM, "x")
            },
          "unknown status" to form(version, status = "waitlisted", reasons = "x"),
          "no reasons" to form(version, status = "applying"),
          "doubled reasons" to
            parameters {
              append(VERSION_PARAM, version)
              append(STATUS_PARAM, "applying")
              append(REASONS_PARAM, "x")
              append(REASONS_PARAM, "y")
            },
        )
      for ((what, posted) in refusedSaves) {
        val response = formPost("/colleges/${entry.id.asString}", posted, sessionToken = token, client = noFollowClient())
        assertEquals(HttpStatusCode.BadRequest, response.status, "[$what]")
        assertEquals("considering", checkedStatus(Jsoup.parse(response.bodyAsText())), "[$what] is not the page as stored")
      }
      for ((what, posted) in listOf("no version" to Parameters.Empty, "non-integer version" to form("1.0"))) {
        val response = formPost("/colleges/${entry.id.asString}/remove", posted, sessionToken = token, client = noFollowClient())
        assertEquals(HttpStatusCode.BadRequest, response.status, "remove [$what]")
        assertEquals(REMOVE_COLLEGE_HEADING, Jsoup.parse(response.bodyAsText()).selectFirst("main h1")?.text(), "remove [$what]")
      }
      for (path in listOf("/colleges/${entry.id.asString}", "/colleges/${entry.id.asString}/remove")) {
        val notAForm =
          noFollowClient().post(path) {
            header(HttpHeaders.Origin, WebAuthTestSupport.urlBase)
            header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(token))
            header(CSRF_HEADER, WebAuthTestSupport.csrfTokenFor(token)!!)
            setBody(TextContent("{}", ContentType.Application.Json))
          }
        assertEquals(HttpStatusCode.BadRequest, notAForm.status, "[not a form] at [$path]")
      }

      assertEquals(entry, CollegeListTestSupport.storedEntry(studentId, entry.id), "a refused posting wrote")
      assertEquals(before, WebAuthTestSupport.resolveSession(token)!!.session, "a refused posting wrote a draft")
    }
  }

  // --- 15. ownership --------------------------------------------------------------------

  @Test
  fun `another student's entry and a non-UUID id are the 404 on every page, nothing written`() {
    testApplication {
      application { testPublicWebModule() }
      val (owner, _) = CollegeListTestSupport.signedInStudent()
      val theirs =
        CollegeListTestSupport.addEntry(
          owner,
          CollegeListTestSupport.seedCollege("${CollegeListTestSupport.uniqueWord()} College"),
        )
      val (_, token) = CollegeListTestSupport.signedInStudent()

      for (id in listOf(theirs.id.asString, "not-a-uuid", "1-2-3-4-5")) {
        for (path in listOf("/colleges/$id", "/colleges/$id/remove")) {
          assertEquals(HttpStatusCode.NotFound, getAs(path, token).status, "GET [$path]")
        }
      }
      for (id in listOf("not-a-uuid", "1-2-3-4-5")) {
        val saved =
          formPost("/colleges/$id", form("1", status = "applying", reasons = "x"), sessionToken = token, client = noFollowClient())
        assertEquals(HttpStatusCode.NotFound, saved.status, "POST [/colleges/$id]")
        val removed = formPost("/colleges/$id/remove", form("1"), sessionToken = token, client = noFollowClient())
        assertEquals(HttpStatusCode.NotFound, removed.status, "POST [/colleges/$id/remove]")
      }
      // An unreadable posting at another student's entry reads it to render, and finds nothing.
      val unreadable = formPost("/colleges/${theirs.id.asString}", form("x"), sessionToken = token, client = noFollowClient())
      assertEquals(HttpStatusCode.NotFound, unreadable.status)
      // A readable one reaches the service, which refuses it as not this student's: the moved list.
      for (response in listOf(
        save(token, theirs, "rejected", "Not mine"),
        remove(token, theirs, theirs.version.toString()),
      )) {
        assertEquals("/colleges?moved=1", response.headers[HttpHeaders.Location])
      }

      assertEquals(theirs, CollegeListTestSupport.storedEntry(owner, theirs.id), "another student's entry was written")
    }
  }

  // --- 16. who reaches it ---------------------------------------------------------------

  @Test
  fun `a supporter is sent to their account, and an account with no role to onboarding, from every route`() {
    testApplication {
      application { testPublicWebModule() }
      val supporter = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail())
      runBlocking { WebAuthTestSupport.supporterService.ensure(supporter.id).getOrThrow() }
      val noRole = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail())
      val anyId = "00000000-0000-4000-8000-000000000000"

      for ((user, home) in listOf(supporter to "/account", noRole to "/get-started")) {
        val token = WebAuthTestSupport.login(user.email.value)
        for (path in listOf("/colleges", "/colleges/add?q=Reed", "/colleges/$anyId", "/colleges/$anyId/remove")) {
          val response = getAs(path, token)
          assertEquals(HttpStatusCode.Found, response.status, "GET [$path] as [$home]")
          assertEquals(home, response.headers[HttpHeaders.Location], "GET [$path]")
        }
        for (path in listOf("/colleges", "/colleges/$anyId", "/colleges/$anyId/remove")) {
          val response = formPost(path, form("1", status = "applying", reasons = "x"), sessionToken = token, client = noFollowClient())
          assertEquals(HttpStatusCode.SeeOther, response.status, "POST [$path] as [$home]")
          assertEquals(home, response.headers[HttpHeaders.Location], "POST [$path]")
        }
      }
      assertEquals(0, WebAuthTestSupport.count("SELECT count(*) FROM college_list_entries"))
    }
  }

  @Test
  fun `a POST without the session's token is refused and writes nothing`() {
    testApplication {
      application { testPublicWebModule() }
      val (studentId, token) = CollegeListTestSupport.signedInStudent()
      val entry =
        CollegeListTestSupport.addEntry(
          studentId,
          CollegeListTestSupport.seedCollege("${CollegeListTestSupport.uniqueWord()} College"),
        )

      for (csrf in listOf(null, "not-the-token")) {
        val posts =
          listOf(
            "/colleges/${entry.id.asString}" to form(entry.version.toString(), status = "applying", reasons = "x"),
            "/colleges/${entry.id.asString}/remove" to form(entry.version.toString()),
            "/colleges" to parameters { append("collegeId", entry.collegeId.value.toString()) },
          )
        for ((path, posted) in posts) {
          assertEquals(
            HttpStatusCode.Forbidden,
            formPost(path, posted, token, noFollowClient(), csrf = csrf).status,
            "[$path] csrf [$csrf]",
          )
        }
      }

      assertEquals(entry, CollegeListTestSupport.storedEntry(studentId, entry.id))
      assertEquals(1, CollegeListTestSupport.entryCount(studentId))
    }
  }

  // --- 17. query keys -------------------------------------------------------------------

  @Test
  fun `an unknown saved or added id, or a moved other than 1, renders nothing`() {
    testApplication {
      application { testPublicWebModule() }
      val (studentId, token) = CollegeListTestSupport.signedInStudent()
      CollegeListTestSupport.addEntry(studentId, CollegeListTestSupport.seedCollege("${CollegeListTestSupport.uniqueWord()} College"))
      val stranger = "00000000-0000-4000-8000-000000000000"

      for (query in listOf(
        "saved=$stranger",
        "added=$stranger",
        "saved=%3Cb%3Ehi%3C%2Fb%3E",
        "added=",
        "moved=2",
        "moved=true",
        "moved=",
        "moved=1&moved=1",
      )) {
        val response = getAs("/colleges?$query", token)
        assertEquals(HttpStatusCode.OK, response.status, "[$query]")
        val page = Jsoup.parse(response.bodyAsText())
        assertTrue(page.select(".college-receipt").isEmpty(), "[$query] rendered a receipt")
        assertTrue(page.select("main .notice").isEmpty(), "[$query] rendered a notice")
        assertFalse(page.text().contains("<b>"), "[$query] reached the page")
      }
    }
  }

  // --- 18. supporting observations ------------------------------------------------------

  @Test
  fun `supporting quotes render in curly quotes, and the section is absent when there are none`() {
    testApplication {
      application { testPublicWebModule() }
      val (studentId, token) = CollegeListTestSupport.signedInStudent()
      val word = CollegeListTestSupport.uniqueWord()
      val (quoted, bare) = CollegeListTestSupport.seedColleges(listOf("$word Quoted College", "$word Bare College"))
      val quote = CollegeListTestSupport.seedQuote(studentId, "I love the coast")
      val withQuote = CollegeListTestSupport.addEntry(studentId, quoted, observationIds = listOf(quote))
      val without = CollegeListTestSupport.addEntry(studentId, bare)

      val page = Jsoup.parse(getAs("/colleges/${withQuote.id.asString}", token).bodyAsText())
      val section = assertNotNull(page.selectFirst(".college-quotes"))
      assertEquals(FROM_YOUR_CONVERSATIONS, section.selectFirst("h2")?.text())
      assertEquals(listOf("“I love the coast”"), section.select("blockquote").map { it.text() })
      assertTrue(section.select("input, textarea, select").isEmpty(), "the quotes are not read-only")

      val bareText = Jsoup.parse(getAs("/colleges/${without.id.asString}", token).bodyAsText())
      assertNull(bareText.selectFirst(".college-quotes"))
      assertFalse(bareText.text().contains(FROM_YOUR_CONVERSATIONS))
    }
  }

  // --- helpers -------------------------------------------------------------------------

  /** A save's or a remove's form; each argument given is one value of its control, absent when not given. */
  private fun form(
    vararg versions: String,
    status: String? = null,
    reasons: String? = null,
  ): Parameters =
    parameters {
      versions.forEach { append(VERSION_PARAM, it) }
      status?.let { append(STATUS_PARAM, it) }
      reasons?.let { append(REASONS_PARAM, it) }
    }

  /** The edit form's posting for [entry], at the version it carries. */
  private suspend fun ApplicationTestBuilder.save(
    token: String,
    entry: CollegeListEntry,
    status: String,
    reasons: String,
  ): HttpResponse =
    formPost(
      "/colleges/${entry.id.asString}",
      form(entry.version.toString(), status = status, reasons = reasons),
      sessionToken = token,
      client = noFollowClient(),
    )

  private suspend fun ApplicationTestBuilder.remove(
    token: String,
    entry: CollegeListEntry,
    version: String,
  ): HttpResponse = formPost("/colleges/${entry.id.asString}/remove", form(version), sessionToken = token, client = noFollowClient())

  /** The checked status radio's value. */
  private fun checkedStatus(page: Document): String? = page.selectFirst("input[name=$STATUS_PARAM][checked]")?.attr("value")

  /** An entry whose school carries a living-plan override, so a save can be shown to keep it. */
  private fun entryWithLivingPlan(studentId: StudentId): CollegeListEntry {
    val entry =
      CollegeListTestSupport.addEntry(
        studentId,
        CollegeListTestSupport.seedCollege("${CollegeListTestSupport.uniqueWord()} College"),
      )
    return runBlocking {
      val outcome =
        WebAuthTestSupport.collegeListService
          .updateEntry(
            studentId,
            entry.id,
            entry.version,
            entry.status,
            null,
            LivingPlanUpdate.Set(LivingArrangement.WITH_FAMILY),
            emptyList(),
          ).getOrThrow()
      assertIs<UpdateEntryResult.Success>(outcome).entry
    }
  }

  /** [entry] with [reasons], written as another device writes it: through the service, bumping the version. */
  private suspend fun setReasons(
    studentId: StudentId,
    entry: CollegeListEntry,
    reasons: String,
  ): CollegeListEntry {
    val outcome =
      WebAuthTestSupport.collegeListService
        .updateEntry(studentId, entry.id, entry.version, entry.status, reasons, LivingPlanUpdate.Keep, emptyList())
        .getOrThrow()
    return assertIs<UpdateEntryResult.Success>(outcome).entry
  }

  /** Moves [entry] on by one version elsewhere, so a save at its old version is stale and drafts its typing. */
  private fun setStale(
    studentId: StudentId,
    entry: CollegeListEntry,
  ) {
    runBlocking { setReasons(studentId, CollegeListTestSupport.storedEntry(studentId, entry.id), "Moved elsewhere") }
  }
}
