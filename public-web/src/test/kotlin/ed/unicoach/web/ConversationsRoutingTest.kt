package ed.unicoach.web

import ed.unicoach.coaching.COACH_ZONE
import ed.unicoach.coaching.ConvoUpdate
import ed.unicoach.coaching.DeleteConvoResult
import ed.unicoach.coaching.GetConvoResult
import ed.unicoach.coaching.PostTurnResult
import ed.unicoach.coaching.StartConvoResult
import ed.unicoach.coaching.UpdateConvoResult
import ed.unicoach.common.models.ValidationResult
import ed.unicoach.common.util.UsDate
import ed.unicoach.db.dao.ConvosDao
import ed.unicoach.db.models.ArchiveScope
import ed.unicoach.db.models.Convo
import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.ConvoName
import ed.unicoach.db.models.ConvoParty
import ed.unicoach.db.models.NewConvo
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.db.models.StudentId
import ed.unicoach.web.CollegeListTestSupport.getAs
import ed.unicoach.web.render.CANCEL_LABEL
import ed.unicoach.web.render.CSRF_FIELD
import ed.unicoach.web.render.DELETED_RECEIPT
import ed.unicoach.web.render.DELETE_CONVERSATION_HEADING
import ed.unicoach.web.render.NEW_CONVERSATION
import ed.unicoach.web.render.NO_CONVERSATIONS_YET
import ed.unicoach.web.render.START_A_CONVERSATION
import ed.unicoach.web.render.START_FOR_COACHING
import ed.unicoach.web.render.YOUR_CONVERSATIONS_TITLE
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.BeforeEach
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Your conversations (RFC 272): the list on `/conversations`, archive, and the
 * delete confirmation and delete — over the real database and the real coach,
 * asserting what was rendered, where the caller was sent, and what the service
 * reads back afterwards.
 */
class ConversationsRoutingTest {
  @BeforeEach
  fun reset() {
    WebAuthTestSupport.resetDatabase()
  }

  private val coach get() = WebAuthTestSupport.defaultCoachingService

  // --- the list -------------------------------------------------------------------------

  @Test
  fun `the list shows the student's unarchived conversations, most recently used first, each opening in the chat`() {
    testApplication {
      application { testPublicWebModule() }
      val (studentId, token) = CollegeListTestSupport.signedInStudent()
      val essays = startConvo(studentId, "Essay ideas")
      val money = startConvo(studentId, "Paying for it")
      postTurn(studentId, essays.id)
      val quiet = createConvoWithoutTurns(studentId, "Not started")
      val archived = startConvo(studentId, "Archived one")
      archive(studentId, archived.id)
      val deleted = startConvo(studentId, "Deleted one")
      delete(studentId, deleted.id)
      val (stranger, _) = CollegeListTestSupport.signedInStudent()
      startConvo(stranger, "Someone else's")

      val response = getAs("/conversations", token)

      assertEquals(HttpStatusCode.OK, response.status)
      val page = Jsoup.parse(response.bodyAsText())
      assertEquals(YOUR_CONVERSATIONS_TITLE, page.selectFirst("main h1")?.text())
      assertEquals(NEW_CONVERSATION, page.selectFirst("main a[href=/chat]")?.text())
      val cards = page.select("main .conversation-list li")
      assertEquals(
        listOf("Essay ideas", "Paying for it", "Not started"),
        cards.map { it.selectFirst("a")!!.text() },
        "not the unarchived conversations, most recent activity first",
      )
      assertEquals(
        listOf(essays.id, money.id, quiet.id).map { "/chat/${it.asString}" },
        cards.map { it.selectFirst("a")!!.attr("href") },
      )
      assertEquals(listOf(essays.id, money.id, quiet.id).map(::lastUsedOn), cards.map { it.selectFirst(".conversation-date")!!.text() })
    }
  }

  @Test
  fun `every card carries an Archive form with the session's token and a Delete link to the confirmation`() {
    testApplication {
      application { testPublicWebModule() }
      val (studentId, token) = CollegeListTestSupport.signedInStudent()
      val convo = startConvo(studentId, "Essay ideas")

      val card = assertNotNull(Jsoup.parse(getAs("/conversations", token).bodyAsText()).selectFirst("main .conversation-list li"))

      val form = assertNotNull(card.selectFirst("form"))
      assertEquals("/conversations/${convo.id.asString}/archive", form.attr("action"))
      assertEquals("post", form.attr("method"))
      assertEquals(WebAuthTestSupport.csrfTokenFor(token), form.selectFirst("input[type=hidden][name=$CSRF_FIELD]")?.`val`())
      assertEquals("Archive", form.selectFirst("button[type=submit]")?.text())
      assertEquals(
        "/conversations/${convo.id.asString}/delete",
        card.select("a").firstOrNull { it.text() == "Delete" }?.attr("href"),
      )
    }
  }

  @Test
  fun `an empty list says so and offers a conversation in the chat`() {
    testApplication {
      application { testPublicWebModule() }
      val (_, token) = CollegeListTestSupport.signedInStudent()

      val page = Jsoup.parse(getAs("/conversations", token).bodyAsText())

      val main = page.selectFirst("main")!!
      assertTrue(main.text().contains(NO_CONVERSATIONS_YET))
      assertTrue(main.text().contains(START_FOR_COACHING))
      assertEquals(START_A_CONVERSATION, main.selectFirst("a[href=/chat]")?.text())
      assertNull(main.selectFirst(".conversation-list"), "an empty list rendered cards")
    }
  }

  // --- archive --------------------------------------------------------------------------

  @Test
  fun `archive archives the conversation and lands on the list, which names it and no longer lists it`() {
    testApplication {
      application { testPublicWebModule() }
      val (studentId, token) = CollegeListTestSupport.signedInStudent()
      val convo = startConvo(studentId, "Essay ideas")
      val kept = startConvo(studentId, "Paying for it")

      val response = formPost("/conversations/${convo.id.asString}/archive", sessionToken = token, client = noFollowClient())

      assertEquals(HttpStatusCode.SeeOther, response.status)
      val location = assertNotNull(response.headers[HttpHeaders.Location])
      assertEquals("/conversations?archived=${convo.id.asString}", location)
      assertNotNull(owned(studentId, convo.id).archivedAt, "the conversation was not archived")
      assertNull(owned(studentId, kept.id).archivedAt, "another conversation was archived")

      val page = Jsoup.parse(getAs(location, token).bodyAsText())
      assertEquals(listOf("Archived “Essay ideas”."), receipts(page))
      assertEquals(listOf("Paying for it"), cardNames(page))
    }
  }

  // --- delete ---------------------------------------------------------------------------

  @Test
  fun `the delete confirmation names the conversation, says what is lost and kept, and posts to its own path`() {
    testApplication {
      application { testPublicWebModule() }
      val (studentId, token) = CollegeListTestSupport.signedInStudent()
      val convo = startConvo(studentId, "Essay ideas")
      val path = "/conversations/${convo.id.asString}/delete"

      val response = getAs(path, token)

      assertEquals(HttpStatusCode.OK, response.status)
      val main = Jsoup.parse(response.bodyAsText()).selectFirst("main")!!
      assertEquals(DELETE_CONVERSATION_HEADING, main.selectFirst("h1")?.text())
      assertEquals(
        listOf(
          "Essay ideas",
          "You won't be able to open it or read it again, here or in the iPhone app. This can't be undone.",
          "What your coach has already learned from it is kept. Your college list and Your details don't change.",
        ),
        main.select("section > p").map { it.text() },
      )
      val form = assertNotNull(main.selectFirst("form"))
      assertEquals(path, form.attr("action"))
      assertEquals("post", form.attr("method"))
      assertNotNull(form.selectFirst("input[type=hidden][name=$CSRF_FIELD]"))
      assertEquals("Delete", form.selectFirst("button[type=submit]")?.text())
      assertEquals("/conversations", form.select("a").firstOrNull { it.text() == CANCEL_LABEL }?.attr("href"))
      assertEquals(convo, stored(convo.id), "the confirmation changed the conversation")
    }
  }

  @Test
  fun `delete soft-deletes the conversation and lands on the list, which says it was deleted`() {
    testApplication {
      application { testPublicWebModule() }
      val (studentId, token) = CollegeListTestSupport.signedInStudent()
      val convo = startConvo(studentId, "Essay ideas")
      val kept = startConvo(studentId, "Paying for it")

      val response = formPost("/conversations/${convo.id.asString}/delete", sessionToken = token, client = noFollowClient())

      assertEquals(HttpStatusCode.SeeOther, response.status)
      val location = assertNotNull(response.headers[HttpHeaders.Location])
      assertEquals("/conversations?deleted=1", location)
      assertEquals(GetConvoResult.NotFound, runBlocking { coach.getConvo(studentId, convo.id).getOrThrow() })
      assertNotNull(stored(convo.id).deletedAt, "the row was not soft-deleted")
      assertEquals(kept, stored(kept.id), "another conversation was changed")

      val page = Jsoup.parse(getAs(location, token).bodyAsText())
      assertEquals(listOf(DELETED_RECEIPT), receipts(page))
      assertEquals(listOf("Paying for it"), cardNames(page))
    }
  }

  // --- ownership ------------------------------------------------------------------------

  @Test
  fun `another student's, an unknown and a non-UUID conversation are the 404 on every id route, nothing changed`() {
    testApplication {
      application { testPublicWebModule() }
      val (owner, _) = CollegeListTestSupport.signedInStudent()
      val theirs = startConvo(owner, "Theirs")
      val (_, token) = CollegeListTestSupport.signedInStudent()

      for (id in listOf(theirs.id.asString, "00000000-0000-4000-8000-000000000000", "not-a-uuid", "1-2-3-4-5")) {
        val confirm = getAs("/conversations/$id/delete", token)
        assertEquals(HttpStatusCode.NotFound, confirm.status, "GET delete [$id]")
        assertTrue(confirm.bodyAsText().contains("404 Not Found"), "GET delete [$id] is not the branded 404")
        for (action in listOf("archive", "delete")) {
          val response = formPost("/conversations/$id/$action", sessionToken = token, client = noFollowClient())
          assertEquals(HttpStatusCode.NotFound, response.status, "POST $action [$id]")
          assertTrue(response.bodyAsText().contains("404 Not Found"), "POST $action [$id] is not the branded 404")
        }
      }

      assertEquals(theirs, stored(theirs.id), "another student's conversation was changed")
    }
  }

  // --- who reaches it -------------------------------------------------------------------

  @Test
  fun `a supporter is sent to their account, and an account with no role to onboarding, from every route, nothing changed`() {
    testApplication {
      application { testPublicWebModule() }
      val (studentId, _) = CollegeListTestSupport.signedInStudent()
      val convo = startConvo(studentId, "Essay ideas")
      val supporter = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail())
      runBlocking { WebAuthTestSupport.supporterService.ensure(supporter.id).getOrThrow() }
      val noRole = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail())
      val id = convo.id.asString

      for ((user, home) in listOf(supporter to "/account", noRole to "/get-started")) {
        val token = WebAuthTestSupport.login(user.email.value)
        for (path in listOf("/conversations", "/conversations/$id/delete")) {
          val response = getAs(path, token)
          assertEquals(HttpStatusCode.Found, response.status, "GET [$path] as [$home]")
          assertEquals(home, response.headers[HttpHeaders.Location], "GET [$path]")
        }
        for (path in listOf("/conversations/$id/archive", "/conversations/$id/delete")) {
          val response = formPost(path, sessionToken = token, client = noFollowClient())
          assertEquals(HttpStatusCode.SeeOther, response.status, "POST [$path] as [$home]")
          assertEquals(home, response.headers[HttpHeaders.Location], "POST [$path]")
        }
      }

      assertEquals(convo, stored(convo.id), "a caller with no student changed the conversation")
    }
  }

  @Test
  fun `a signed-out caller is sent to sign-in, and a POST without the session's token is refused, nothing changed`() {
    testApplication {
      application { testPublicWebModule() }
      val (studentId, token) = CollegeListTestSupport.signedInStudent()
      val convo = startConvo(studentId, "Essay ideas")
      val id = convo.id.asString
      val posts = listOf("/conversations/$id/archive", "/conversations/$id/delete")

      for (path in listOf("/conversations", "/conversations/$id/delete")) {
        val response = noFollowClient().get(path)
        assertEquals(HttpStatusCode.Found, response.status, "GET [$path]")
        assertEquals("/sign-in", response.headers[HttpHeaders.Location], "GET [$path]")
      }
      for (path in posts) {
        val response = formPost(path, client = noFollowClient())
        assertEquals(HttpStatusCode.SeeOther, response.status, "POST [$path]")
        assertEquals("/sign-in", response.headers[HttpHeaders.Location], "POST [$path]")
      }
      for (csrf in listOf(null, "not-the-token")) {
        for (path in posts) {
          val response = formPost(path, sessionToken = token, client = noFollowClient(), csrf = csrf)
          assertEquals(HttpStatusCode.Forbidden, response.status, "POST [$path] csrf [$csrf]")
        }
      }

      assertEquals(convo, stored(convo.id), "a refused POST changed the conversation")
    }
  }

  // --- query keys -----------------------------------------------------------------------

  @Test
  fun `a receipt key that names no archived conversation of the student's renders no receipt`() {
    testApplication {
      application { testPublicWebModule() }
      val (studentId, token) = CollegeListTestSupport.signedInStudent()
      val unarchived = startConvo(studentId, "Still here")
      val (stranger, _) = CollegeListTestSupport.signedInStudent()
      val theirs = startConvo(stranger, "Theirs")
      archive(stranger, theirs.id)

      for (query in listOf(
        "archived=not-a-uuid",
        "archived=%3Cb%3Ehi%3C%2Fb%3E",
        "archived=",
        "archived=00000000-0000-4000-8000-000000000000",
        "archived=${theirs.id.asString}",
        "archived=${unarchived.id.asString}",
        "deleted=2",
        "deleted=true",
        "deleted=",
        "deleted=1&deleted=1",
      )) {
        val response = getAs("/conversations?$query", token)
        assertEquals(HttpStatusCode.OK, response.status, "[$query]")
        val page = Jsoup.parse(response.bodyAsText())
        assertEquals(emptyList(), receipts(page), "[$query] rendered a receipt")
        assertTrue(!page.text().contains("<b>"), "[$query] reached the page")
        assertTrue(!page.text().contains("Theirs"), "[$query] named another student's conversation")
      }
    }
  }

  // --- helpers -------------------------------------------------------------------------

  /**
   * A conversation of [studentId]'s named [name], started through the
   * production coach, its first reply run to completion: its row as stored
   * then, which a refused request must leave exactly as it is.
   */
  private fun startConvo(
    studentId: StudentId,
    name: String,
  ): Convo {
    val started = runBlocking { assertIs<StartConvoResult.Started>(coach.startConvo(studentId, "Hello", name).getOrThrow()) }
    runBlocking { started.reply.toList() }
    return stored(started.convo.id)
  }

  /** A second turn on [convoId], run to completion: what makes it the most recently used. */
  private fun postTurn(
    studentId: StudentId,
    convoId: ConvoId,
  ) {
    runBlocking {
      assertIs<PostTurnResult.Started>(coach.postTurn(studentId, convoId, "And another thing").getOrThrow()).reply.toList()
    }
  }

  /** A conversation with no turns at all, so it has no last activity and the list falls back to when it last changed. */
  private fun createConvoWithoutTurns(
    studentId: StudentId,
    name: String,
  ): Convo =
    runBlocking {
      val convoName = assertIs<ValidationResult.Valid<ConvoName>>(ConvoName.create(name)).value
      WebAuthTestSupport.database
        .withConnection { session -> ConvosDao.create(session, NewConvo(studentId, convoName, ConvoParty.Student)) }
        .getOrThrow()
    }

  /** Archives [convoId] as the iPhone app does, through the service. */
  private fun archive(
    studentId: StudentId,
    convoId: ConvoId,
  ) {
    runBlocking { assertIs<UpdateConvoResult.Success>(coach.updateConvo(studentId, convoId, ConvoUpdate(archived = true)).getOrThrow()) }
  }

  /** Deletes [convoId] as the iPhone app does, through the service. */
  private fun delete(
    studentId: StudentId,
    convoId: ConvoId,
  ) {
    runBlocking { assertEquals(DeleteConvoResult.Success, coach.deleteConvo(studentId, convoId).getOrThrow()) }
  }

  /** [convoId] as the service reads it for [studentId], which must own it. */
  private fun owned(
    studentId: StudentId,
    convoId: ConvoId,
  ): Convo = runBlocking { assertIs<GetConvoResult.Found>(coach.getConvo(studentId, convoId).getOrThrow()).listing.convo }

  /** [convoId]'s row, deleted or not: what a refused request must leave exactly as it was. */
  private fun stored(convoId: ConvoId): Convo =
    runBlocking {
      WebAuthTestSupport.database
        .withConnection { session ->
          ConvosDao.findById(session, convoId, SoftDeleteScope.ALL)
        }.getOrThrow()
    }

  /** The day the list should show for [convoId]: its last activity, or its last change, as a date in the coach's zone. */
  private fun lastUsedOn(convoId: ConvoId): String =
    runBlocking {
      val listing = coach.listConvos(ownerOf(convoId), ArchiveScope.ALL).getOrThrow().single { it.convo.id == convoId }
      UsDate.of(LocalDate.ofInstant(listing.lastActivityAt ?: listing.convo.updatedAt, COACH_ZONE))
    }

  private fun ownerOf(convoId: ConvoId): StudentId = stored(convoId).studentId

  /** The receipts above the list, in order. */
  private fun receipts(page: Document): List<String> = page.select("main .notice").map { it.text() }

  /** The names on the list's cards, in order. */
  private fun cardNames(page: Document): List<String> = page.select("main .conversation-list li > a").map { it.text() }
}
