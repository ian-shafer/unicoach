package ed.unicoach.web

import ed.unicoach.coaching.moneyprofile.GetMoneyProfileResult
import ed.unicoach.coaching.moneyprofile.MoneyProfileService
import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.DependencyStatus
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.LivingArrangement
import ed.unicoach.db.models.MoneyProfile
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.User
import ed.unicoach.db.session.MoneyProfileDraft
import ed.unicoach.web.auth.CSRF_HEADER
import ed.unicoach.web.render.CHOOSE_AN_ANSWER_MESSAGE
import ed.unicoach.web.render.CSRF_FIELD
import ed.unicoach.web.render.DECLINED_ANSWER
import ed.unicoach.web.render.DECLINE_LABEL
import ed.unicoach.web.render.DEPENDENCY_CAPTION
import ed.unicoach.web.render.DETAILS_ACTION_PARAM
import ed.unicoach.web.render.DETAILS_FIELD_PARAM
import ed.unicoach.web.render.INCOME_CAPTION
import ed.unicoach.web.render.LIVING_PLAN_CAPTION
import ed.unicoach.web.render.NOT_ANSWERED_YET
import ed.unicoach.web.render.NOT_SAVED_MESSAGE
import ed.unicoach.web.render.NOT_SAVED_YET
import ed.unicoach.web.render.REMOVE_LABEL
import ed.unicoach.web.render.SAVED_RECEIPT
import ed.unicoach.web.render.SAVE_LABEL
import ed.unicoach.web.render.STATE_CAPTION
import ed.unicoach.web.render.YOUR_DETAILS_INTRO
import ed.unicoach.web.render.YOUR_DETAILS_OPTIONAL_LINE
import ed.unicoach.web.render.YOUR_DETAILS_TITLE
import io.ktor.client.request.get
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
import org.jsoup.nodes.Element
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Your details (RFC 270): the four money answers on `/account/details`, each
 * given, declined or removed on its own — over the real database and the real
 * services, asserting what was rendered, where the caller was sent, and which
 * rows and session drafts exist.
 */
class YourDetailsRoutingTest {
  @BeforeEach
  fun reset() = WebAuthTestSupport.resetDatabase()

  /** Each field's control name, the pick a test saves, and that pick's option words. */
  private data class Answer(
    val field: String,
    val pick: String,
    val words: String,
  )

  private val income = Answer("income_band", IncomeBand.K48_TO_75K.value, IncomeBand.K48_TO_75K.bracket)
  private val residency = Answer("residency_state", "OR", "Oregon")
  private val livingPlan = Answer("living_plan", LivingArrangement.WITH_FAMILY.value, "Living at home with family")
  private val dependency = Answer("dependency", DependencyStatus.INDEPENDENT.value, "I will file as independent")
  private val answers = listOf(income, residency, livingPlan, dependency)

  // --- 1, 2. the door and the page ---------------------------------------------------

  @Test
  fun `the page renders the four fields, the intro, each caption and a CSRF field in every form`() {
    testApplication {
      application { testPublicWebModule() }
      val (_, token) = signedInStudent()

      val response = getAs("/account/details", token)

      assertEquals(HttpStatusCode.OK, response.status)
      val page = Jsoup.parse(response.bodyAsText())
      assertEquals(YOUR_DETAILS_TITLE, page.selectFirst("main h1")?.text())
      val text = page.selectFirst("main")!!.text()
      for (line in listOf(
        YOUR_DETAILS_INTRO,
        YOUR_DETAILS_OPTIONAL_LINE,
        INCOME_CAPTION,
        STATE_CAPTION,
        LIVING_PLAN_CAPTION,
        DEPENDENCY_CAPTION,
      )) {
        assertTrue(text.contains(line), "the page does not say [$line]")
      }
      assertEquals(
        answers.map { it.field },
        page.select("main fieldset select").map { it.attr("name") },
        "the four fields, in page order",
      )
      val forms = page.select("main form")
      assertTrue(forms.isNotEmpty())
      for (form in forms) {
        assertNotNull(form.selectFirst("input[type=hidden][name=$CSRF_FIELD]"), "a form carries no CSRF field: [$form]")
        assertEquals("/account/details", form.attr("action"))
        assertEquals("post", form.attr("method"))
      }
    }
  }

  @Test
  fun `with no profile row every field is unanswered, offers a decline and no remove`() {
    testApplication {
      application { testPublicWebModule() }
      val (_, token) = signedInStudent()

      val page = Jsoup.parse(getAs("/account/details", token).bodyAsText())

      for (answer in answers) {
        val field = fieldsetOf(page, answer.field)
        assertEquals(NOT_ANSWERED_YET, answerLine(field), "[${answer.field}]")
        assertEquals(listOf(DECLINE_LABEL), actionButtons(field), "[${answer.field}]")
        assertEquals("", selectedValue(field, answer.field), "[${answer.field}] does not start on Select")
      }
      assertEquals(0, WebAuthTestSupport.count("SELECT count(*) FROM money_profiles"), "a GET wrote a row")
    }
  }

  // --- 3. the first-session test -------------------------------------------------------

  @Test
  fun `saving income alone stores it, redirects back to it, and leaves the other three unanswered`() {
    testApplication {
      application { testPublicWebModule() }
      val (studentId, token) = signedInStudent()

      val response = save(token, income)

      assertEquals(HttpStatusCode.SeeOther, response.status)
      assertEquals("/account/details?saved=income_band#income_band", response.headers[HttpHeaders.Location])
      val profile = profileOf(studentId)
      assertEquals(IncomeBand.K48_TO_75K, profile.incomeBand)
      assertEquals(AnswerStatus.ANSWERED, profile.incomeBandStatus)
      assertEquals(AnswerStatus.UNANSWERED, profile.residencyStatus)
      assertEquals(AnswerStatus.UNANSWERED, profile.livingPlanStatus)
      assertEquals(AnswerStatus.UNANSWERED, profile.dependencyStatus)

      val page = Jsoup.parse(getAs("/account/details?saved=income_band", token).bodyAsText())
      val incomeField = fieldsetOf(page, income.field)
      assertEquals(income.words, answerLine(incomeField))
      assertEquals(SAVED_RECEIPT, incomeField.selectFirst(".details-receipt")?.text())
      assertEquals(income.pick, selectedValue(incomeField, income.field), "the select does not show the stored answer")
      assertEquals(listOf(DECLINE_LABEL, REMOVE_LABEL), actionButtons(incomeField))
      for (other in answers - income) {
        val field = fieldsetOf(page, other.field)
        assertEquals(NOT_ANSWERED_YET, answerLine(field), "[${other.field}]")
        assertNull(field.selectFirst(".details-receipt"), "[${other.field}] carries a receipt it did not earn")
      }
    }
  }

  // --- 4, 5. each field, each action --------------------------------------------------

  @Test
  fun `each field saves, declines and removes on its own, leaving the other three as they were`() {
    testApplication {
      application { testPublicWebModule() }
      val (studentId, token) = signedInStudent()
      for (answer in answers) save(token, answer)
      val allAnswered = profileOf(studentId)
      for (answer in answers) assertEquals(Stored.Answered(answer.pick), stored(allAnswered, answer.field), "[${answer.field}] save")
      val answeredPage = Jsoup.parse(getAs("/account/details", token).bodyAsText())
      for (answer in answers) {
        assertEquals(
          answer.words,
          answerLine(fieldsetOf(answeredPage, answer.field)),
          "[${answer.field}]'s answer line",
        )
      }

      for (answer in answers) {
        assertEquals(HttpStatusCode.SeeOther, post(token, answer.field, "decline").status)
        val declined = profileOf(studentId)
        assertEquals(Stored.Declined, stored(declined, answer.field), "[${answer.field}] decline")
        assertOthersUnchanged(allAnswered, declined, answer)

        assertEquals(HttpStatusCode.SeeOther, post(token, answer.field, "remove").status)
        val removed = profileOf(studentId)
        assertEquals(Stored.Unanswered, stored(removed, answer.field), "[${answer.field}] remove")
        assertOthersUnchanged(allAnswered, removed, answer)

        save(token, answer)
      }
    }
  }

  @Test
  fun `a declined field reads as declined and offers only removal`() {
    testApplication {
      application { testPublicWebModule() }
      val (_, token) = signedInStudent()
      post(token, residency.field, "decline")

      val field = fieldsetOf(Jsoup.parse(getAs("/account/details", token).bodyAsText()), residency.field)

      assertEquals(DECLINED_ANSWER, answerLine(field))
      assertEquals(listOf(REMOVE_LABEL), actionButtons(field))
      assertEquals("", selectedValue(field, residency.field))
    }
  }

  @Test
  fun `the living-plan and dependency options and answers read the web's own captions`() {
    testApplication {
      application { testPublicWebModule() }
      val (_, token) = signedInStudent()

      val blank = Jsoup.parse(getAs("/account/details", token).bodyAsText())
      assertEquals(
        listOf("Living on campus", "Renting off campus", "Living at home with family"),
        blank.select("select[name=living_plan] option").drop(1).map { it.text() },
      )
      assertEquals(
        listOf("I will be a dependent on the FAFSA", "I will file as independent"),
        blank.select("select[name=dependency] option").drop(1).map { it.text() },
      )
      assertEquals(listOf("Select", "Select", "Select", "Select"), blank.select("main select").map { it.child(0).text() })

      save(token, livingPlan)
      save(token, dependency)
      val answered = Jsoup.parse(getAs("/account/details", token).bodyAsText())
      assertEquals(livingPlan.words, answerLine(fieldsetOf(answered, livingPlan.field)))
      assertEquals(dependency.words, answerLine(fieldsetOf(answered, dependency.field)))
    }
  }

  // --- 6, 7. refused postings ---------------------------------------------------------

  @Test
  fun `a posting that is not this page's is refused at 400 with nothing written and no draft`() {
    testApplication {
      application { testPublicWebModule() }
      val (_, token) = signedInStudent()
      val before = WebAuthTestSupport.resolveSession(token)!!.session

      val refused =
        listOf(
          "unknown field" to
            parameters {
              append(DETAILS_FIELD_PARAM, "favourite_colour")
              append(DETAILS_ACTION_PARAM, "decline")
            },
          "unknown action" to
            parameters {
              append(DETAILS_FIELD_PARAM, income.field)
              append(DETAILS_ACTION_PARAM, "shout")
            },
          "income no option carries" to saveForm(income.field, "a_lot"),
          "state no option carries" to saveForm(residency.field, "ZZ"),
          "plan no option carries" to saveForm(livingPlan.field, "boat"),
          "dependency no option carries" to saveForm(dependency.field, "both"),
          "save with no pick control" to
            parameters {
              append(DETAILS_FIELD_PARAM, income.field)
              append(DETAILS_ACTION_PARAM, "save")
            },
          "doubled pick" to
            parameters {
              append(DETAILS_FIELD_PARAM, income.field)
              append(DETAILS_ACTION_PARAM, "save")
              append(income.field, income.pick)
              append(income.field, income.pick)
            },
          "doubled field" to
            parameters {
              append(DETAILS_FIELD_PARAM, income.field)
              append(DETAILS_FIELD_PARAM, residency.field)
              append(DETAILS_ACTION_PARAM, "decline")
            },
          "no action" to parameters { append(DETAILS_FIELD_PARAM, income.field) },
        )
      for ((what, form) in refused) {
        val response = formPost("/account/details", form, sessionToken = token, client = noFollowClient())
        assertEquals(HttpStatusCode.BadRequest, response.status, "[$what]")
        assertEquals(YOUR_DETAILS_TITLE, Jsoup.parse(response.bodyAsText()).selectFirst("main h1")?.text(), "[$what] is not the page")
      }
      // A body that is not a form at all, carrying its token in the header so
      // the route, not the CSRF check, is what answers it.
      val notAForm =
        noFollowClient().post("/account/details") {
          header(HttpHeaders.Origin, WebAuthTestSupport.urlBase)
          header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(token))
          header(CSRF_HEADER, WebAuthTestSupport.csrfTokenFor(token)!!)
          setBody(TextContent("{}", ContentType.Application.Json))
        }
      assertEquals(HttpStatusCode.BadRequest, notAForm.status, "[not a form]")

      assertEquals(0, WebAuthTestSupport.count("SELECT count(*) FROM money_profiles"), "a refused posting wrote a row")
      assertEquals(before, WebAuthTestSupport.resolveSession(token)!!.session, "a refused posting wrote a draft")
    }
  }

  @Test
  fun `saving the blank option is refused under that field, with nothing written`() {
    testApplication {
      application { testPublicWebModule() }
      val (_, token) = signedInStudent()
      val before = WebAuthTestSupport.resolveSession(token)!!.session

      val response = formPost("/account/details", saveForm(livingPlan.field, ""), sessionToken = token, client = noFollowClient())

      assertEquals(HttpStatusCode.BadRequest, response.status)
      val page = Jsoup.parse(response.bodyAsText())
      assertEquals(listOf(CHOOSE_AN_ANSWER_MESSAGE), fieldsetOf(page, livingPlan.field).select(".field-error li").map { it.text() })
      for (other in answers - livingPlan) {
        assertTrue(fieldsetOf(page, other.field).select(".field-error").isEmpty(), "[${other.field}] carries the blank-save message")
      }
      assertEquals(0, WebAuthTestSupport.count("SELECT count(*) FROM money_profiles"))
      assertEquals(before, WebAuthTestSupport.resolveSession(token)!!.session, "a blank save wrote a draft")
    }
  }

  // --- 8, 9, 13. the write fault and the draft ----------------------------------------

  @Test
  fun `a failed save is said under the field, kept in the draft, offered again, and emptied by the next good save`() {
    val (studentId, token) = signedInStudent()
    val readOnly = WebAuthTestSupport.readOnlyDatabase()
    try {
      testApplication {
        application { testPublicWebModule(moneyProfileService = MoneyProfileService(readOnly)) }

        val response = save(token, income)

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val failed = Jsoup.parse(response.bodyAsText())
        assertEquals(listOf(NOT_SAVED_MESSAGE), fieldsetOf(failed, income.field).select(".field-error li").map { it.text() })
        assertEquals(income.pick, selectedValue(fieldsetOf(failed, income.field), income.field), "the failed pick is not still chosen")
        assertEquals(income.pick, draftOf(token)?.incomeBand, "the failed pick was not kept in the draft")

        val reopened = fieldsetOf(Jsoup.parse(getAs("/account/details", token).bodyAsText()), income.field)
        assertEquals(income.pick, selectedValue(reopened, income.field), "the draft does not preselect the pick")
        assertTrue(reopened.text().contains(NOT_SAVED_YET), "the drafted field does not say it is not saved")
        assertEquals(listOf(SAVE_LABEL), pickForm(reopened).select("button").map { it.text() })
        assertEquals(NOT_ANSWERED_YET, answerLine(reopened), "the answer line claims a pick that was never stored")
      }
      assertEquals(GetMoneyProfileResult.NotFound, profileResult(studentId), "the read-only database wrote a row")

      testApplication {
        application { testPublicWebModule() }

        assertEquals(HttpStatusCode.SeeOther, save(token, income).status)

        assertEquals(IncomeBand.K48_TO_75K, profileOf(studentId).incomeBand)
        assertNull(draftOf(token), "the saved field is still drafted")
        val stored = WebAuthTestSupport.storedSessionMetadata(user(token).id.value).single()
        assertFalse(stored.contains("drafts"), "an empty drafts message was left behind: [$stored]")
        val page = Jsoup.parse(getAs("/account/details", token).bodyAsText())
        assertTrue(page.select("form[data-autosave] button").isEmpty(), "a pick form offers Save with nothing drafted")
      }
    } finally {
      readOnly.close()
    }
  }

  @Test
  fun `a failed decline is said under the field and drafts nothing`() {
    val (_, token) = signedInStudent()
    val readOnly = WebAuthTestSupport.readOnlyDatabase()
    try {
      testApplication {
        application { testPublicWebModule(moneyProfileService = MoneyProfileService(readOnly)) }
        val before = WebAuthTestSupport.resolveSession(token)!!.session

        val response = post(token, dependency.field, "decline")

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        val field = fieldsetOf(Jsoup.parse(response.bodyAsText()), dependency.field)
        assertEquals(listOf(NOT_SAVED_MESSAGE), field.select(".field-error li").map { it.text() })
        assertEquals(before, WebAuthTestSupport.resolveSession(token)!!.session, "a failed decline wrote a draft")
      }
    } finally {
      readOnly.close()
    }
  }

  @Test
  fun `saving one of two drafted fields leaves the other drafted`() {
    val (studentId, token) = signedInStudent()
    val readOnly = WebAuthTestSupport.readOnlyDatabase()
    try {
      testApplication {
        application { testPublicWebModule(moneyProfileService = MoneyProfileService(readOnly)) }
        save(token, income)
        save(token, livingPlan)
      }
    } finally {
      readOnly.close()
    }
    val drafted = assertNotNull(draftOf(token))
    assertEquals(income.pick to livingPlan.pick, drafted.incomeBand to drafted.livingPlan, "both failed picks are not drafted")

    testApplication {
      application { testPublicWebModule() }

      assertEquals(HttpStatusCode.SeeOther, save(token, income).status)

      assertEquals(IncomeBand.K48_TO_75K, profileOf(studentId).incomeBand)
      val left = assertNotNull(draftOf(token), "saving one field dropped the other's draft")
      assertEquals("" to livingPlan.pick, left.incomeBand to left.livingPlan)
      val page = Jsoup.parse(getAs("/account/details", token).bodyAsText())
      assertEquals(listOf(SAVE_LABEL), pickForm(fieldsetOf(page, livingPlan.field)).select("button").map { it.text() })
      assertTrue(pickForm(fieldsetOf(page, income.field)).select("button").isEmpty())
    }
  }

  @Test
  fun `every pick form is the script's, posts save for its own field, and has no button undrafted`() {
    testApplication {
      application { testPublicWebModule() }
      val (_, token) = signedInStudent()

      val page = Jsoup.parse(getAs("/account/details", token).bodyAsText())

      val pickForms = page.select("main form[data-autosave]")
      assertEquals(answers.map { it.field }, pickForms.map { it.selectFirst("select")?.attr("name") })
      for ((form, answer) in pickForms.zip(answers)) {
        assertEquals(answer.field, form.selectFirst("input[type=hidden][name=$DETAILS_FIELD_PARAM]")?.attr("value"))
        assertEquals("save", form.selectFirst("input[type=hidden][name=$DETAILS_ACTION_PARAM]")?.attr("value"))
        assertTrue(form.select("button").isEmpty(), "[${answer.field}]'s pick form has a button with nothing drafted")
      }
    }
  }

  // --- 10, 11, 12. who reaches it, and the receipt ------------------------------------

  @Test
  fun `a supporter is sent to their account by both methods, with nothing written`() {
    testApplication {
      application { testPublicWebModule() }
      val user = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail())
      runBlocking { WebAuthTestSupport.supporterService.ensure(user.id).getOrThrow() }
      val token = WebAuthTestSupport.login(user.email.value)
      val before = WebAuthTestSupport.resolveSession(token)!!.session

      val get = getAs("/account/details", token)
      assertEquals(HttpStatusCode.Found, get.status)
      assertEquals("/account", get.headers[HttpHeaders.Location])
      val post = save(token, income)
      assertEquals(HttpStatusCode.SeeOther, post.status)
      assertEquals("/account", post.headers[HttpHeaders.Location])

      assertEquals(0, WebAuthTestSupport.count("SELECT count(*) FROM money_profiles"))
      assertEquals(before, WebAuthTestSupport.resolveSession(token)!!.session)
    }
  }

  @Test
  fun `an account with no role is sent to onboarding`() {
    testApplication {
      application { testPublicWebModule() }
      val user = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail())
      val token = WebAuthTestSupport.login(user.email.value)

      val get = getAs("/account/details", token)
      assertEquals(HttpStatusCode.Found, get.status)
      assertEquals("/get-started", get.headers[HttpHeaders.Location])
      val post = save(token, income)
      assertEquals(HttpStatusCode.SeeOther, post.status)
      assertEquals("/get-started", post.headers[HttpHeaders.Location])
    }
  }

  @Test
  fun `a POST without the session's token is refused and writes nothing`() {
    testApplication {
      application { testPublicWebModule() }
      val (_, token) = signedInStudent()

      for (csrf in listOf(null, "not-the-token")) {
        val response = formPost("/account/details", saveForm(income.field, income.pick), token, noFollowClient(), csrf = csrf)
        assertEquals(HttpStatusCode.Forbidden, response.status, "csrf [$csrf]")
      }

      assertEquals(0, WebAuthTestSupport.count("SELECT count(*) FROM money_profiles"))
    }
  }

  @Test
  fun `an unknown saved value renders no receipt`() {
    testApplication {
      application { testPublicWebModule() }
      val (_, token) = signedInStudent()
      save(token, income)

      for (query in listOf("saved=favourite_colour", "saved=", "saved=%3Cb%3Ehi%3C%2Fb%3E")) {
        val response = getAs("/account/details?$query", token)
        assertEquals(HttpStatusCode.OK, response.status, "[$query]")
        val page = Jsoup.parse(response.bodyAsText())
        assertTrue(page.select(".details-receipt").isEmpty(), "[$query] rendered a receipt")
        assertFalse(page.text().contains(SAVED_RECEIPT), "[$query] said [$SAVED_RECEIPT]")
      }
    }
  }

  // --- helpers -----------------------------------------------------------------------

  /** A stored answer as the assertions compare it. */
  private sealed interface Stored {
    data object Unanswered : Stored

    data object Declined : Stored

    data class Answered(
      val value: String,
    ) : Stored
  }

  private fun stored(
    profile: MoneyProfile,
    field: String,
  ): Stored {
    val (status, value) =
      when (field) {
        income.field -> profile.incomeBandStatus to profile.incomeBand?.value
        residency.field -> profile.residencyStatus to profile.residencyState
        livingPlan.field -> profile.livingPlanStatus to profile.livingPlan?.value
        dependency.field -> profile.dependencyStatus to profile.dependency?.value
        else -> error("no field [$field]")
      }
    return when (status) {
      AnswerStatus.UNANSWERED -> Stored.Unanswered
      AnswerStatus.DECLINED -> Stored.Declined
      AnswerStatus.ANSWERED -> Stored.Answered(assertNotNull(value))
      AnswerStatus.UNDECIDED -> error("[$field] undecided")
    }
  }

  private fun assertOthersUnchanged(
    expected: MoneyProfile,
    actual: MoneyProfile,
    changed: Answer,
  ) {
    for (other in answers - changed) {
      assertEquals(stored(expected, other.field), stored(actual, other.field), "writing [${changed.field}] changed [${other.field}]")
    }
  }

  /** A student who has onboarded, signed in: their student id and session token. */
  private fun signedInStudent(): Pair<StudentId, String> {
    val user = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail())
    val studentId = WebAuthTestSupport.seedStudent(user)
    return studentId to WebAuthTestSupport.login(user.email.value)
  }

  private fun user(token: String): User = WebAuthTestSupport.resolveSession(token)!!.user

  private suspend fun ApplicationTestBuilder.getAs(
    path: String,
    token: String,
  ): HttpResponse = noFollowClient().get(path) { header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(token)) }

  private fun saveForm(
    field: String,
    pick: String,
  ): Parameters =
    parameters {
      append(DETAILS_FIELD_PARAM, field)
      append(DETAILS_ACTION_PARAM, "save")
      append(field, pick)
    }

  private suspend fun ApplicationTestBuilder.save(
    token: String,
    answer: Answer,
  ): HttpResponse = formPost("/account/details", saveForm(answer.field, answer.pick), sessionToken = token, client = noFollowClient())

  /** A one-button form's posting: [action] on [field], no pick. */
  private suspend fun ApplicationTestBuilder.post(
    token: String,
    field: String,
    action: String,
  ): HttpResponse =
    formPost(
      "/account/details",
      parameters {
        append(DETAILS_FIELD_PARAM, field)
        append(DETAILS_ACTION_PARAM, action)
      },
      sessionToken = token,
      client = noFollowClient(),
    )

  private fun profileResult(studentId: StudentId): GetMoneyProfileResult =
    runBlocking { WebAuthTestSupport.moneyProfileService.getForStudent(studentId).getOrThrow() }

  private fun profileOf(studentId: StudentId): MoneyProfile = assertIs<GetMoneyProfileResult.Found>(profileResult(studentId)).profile

  /** The Your details draft the session behind [token] holds, or `null`. */
  private fun draftOf(token: String): MoneyProfileDraft? {
    val drafts =
      WebAuthTestSupport
        .resolveSession(token)!!
        .session.metadata.drafts
    return if (drafts.hasMoneyProfile()) drafts.moneyProfile else null
  }

  /** The fieldset holding [field]'s select. */
  private fun fieldsetOf(
    page: Document,
    field: String,
  ): Element = assertNotNull(page.selectFirst("main fieldset:has(select[name=$field])"), "no fieldset for [$field]")

  private fun pickForm(fieldset: Element): Element = assertNotNull(fieldset.selectFirst("form[data-autosave]"))

  private fun answerLine(fieldset: Element): String? = fieldset.selectFirst(".details-answer")?.ownText()

  /** The one-button forms' labels, in order. */
  private fun actionButtons(fieldset: Element): List<String> = fieldset.select(".details-actions button").map { it.text() }

  private fun selectedValue(
    fieldset: Element,
    name: String,
  ): String = fieldset.selectFirst("select[name=$name] option[selected]")?.attr("value") ?: ""
}
