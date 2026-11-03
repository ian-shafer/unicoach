package ed.unicoach.web

import ed.unicoach.coaching.moneyprofile.GetMoneyProfileResult
import ed.unicoach.db.dao.SupportersDao
import ed.unicoach.db.models.AnswerStatus
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.Student
import ed.unicoach.db.models.User
import ed.unicoach.family.EnsureSupporterOutcome
import ed.unicoach.student.StudentService
import ed.unicoach.web.render.BIRTH_DAY_FIELD
import ed.unicoach.web.render.BIRTH_MONTH_FIELD
import ed.unicoach.web.render.BIRTH_YEAR_FIELD
import ed.unicoach.web.render.CREATE_PROFILE_LABEL
import ed.unicoach.web.render.CSRF_FIELD
import ed.unicoach.web.render.DATE_OF_BIRTH_REQUIRED_MESSAGE
import ed.unicoach.web.render.DAY_WITHOUT_MONTH_MESSAGE
import ed.unicoach.web.render.EMAIL_FIELD
import ed.unicoach.web.render.GRADUATION_DAY_FIELD
import ed.unicoach.web.render.GRADUATION_MONTH_FIELD
import ed.unicoach.web.render.GRADUATION_YEAR_FIELD
import ed.unicoach.web.render.INCOME_BAND_FIELD
import ed.unicoach.web.render.NOT_ANSWERED_LABEL
import ed.unicoach.web.render.RESIDENCY_STATE_FIELD
import ed.unicoach.web.render.ROLE_CHOICE_QUESTION
import ed.unicoach.web.render.STUDENT_CHOICE_LABEL
import ed.unicoach.web.render.SUPPORTER_CHOICE_LABEL
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.junit.jupiter.api.BeforeEach
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Web onboarding (RFC 268): the wall in front of the chat and the account
 * pages, the role choice, the supporter path and the student form on the
 * session-draft rail — over the real database and the real services, asserting
 * what was rendered, where the caller was sent and which rows exist.
 */
class GetStartedRoutingTest {
  @BeforeEach
  fun reset() = WebAuthTestSupport.resetDatabase()

  private val thisYear = LocalDate.now(ZoneOffset.UTC).year

  /** A graduation year inside the form's window, and nowhere else in a request. */
  private val graduationYear = (thisYear + 2).toString()

  /** A 13+ date of birth whose parts appear nowhere else in a request. */
  private val adultBirth = Triple("1999", "7", "10")

  /** One day short of thirteen, judged on the UTC "today" `StudentService` reads. */
  private val underThirteen: LocalDate = LocalDate.now(ZoneOffset.UTC).minusYears(13).plusDays(1)

  // --- 1. the wall ------------------------------------------------------------------

  @Test
  fun `a verified account with no role reaches only onboarding, sign-out and the open pages`() {
    testApplication {
      application { testPublicWebModule() }
      val user = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail())
      val token = WebAuthTestSupport.login(user.email.value)

      for (path in listOf("/chat", "/account", "/account/email")) {
        val response = getAs(path, token)
        assertEquals(HttpStatusCode.Found, response.status, "[$path]")
        assertEquals("/get-started", response.headers[HttpHeaders.Location], "[$path]")
      }
      val changed =
        formPost("/account/email", parameters { append(EMAIL_FIELD, WebAuthTestSupport.uniqueEmail()) }, token, noFollowClient())
      assertEquals(HttpStatusCode.SeeOther, changed.status)
      assertEquals("/get-started", changed.headers[HttpHeaders.Location])
      assertEquals(user.email, WebAuthTestSupport.resolveSession(token)!!.user.email, "a walled POST changed the address")

      assertEquals(HttpStatusCode.OK, getAs("/get-started", token).status)
      assertEquals(HttpStatusCode.OK, getAs("/get-started/student", token).status)
      for (path in listOf("/terms", "/privacy", "/invite", "/report")) {
        assertNotEquals("/get-started", getAs(path, token).headers[HttpHeaders.Location], "[$path] is walled, and must not be")
      }
      val signOut = formPost("/sign-out", sessionToken = token, client = noFollowClient())
      assertEquals(HttpStatusCode.SeeOther, signOut.status)
      assertEquals("/", signOut.headers[HttpHeaders.Location])
      assertNull(WebAuthTestSupport.resolveSession(token), "sign-out did not end the session")
    }
  }

  @Test
  fun `a student and a supporter reach the account page`() {
    testApplication {
      application { testPublicWebModule() }
      val student = WebAuthTestSupport.seedStudentUser(WebAuthTestSupport.uniqueEmail())
      val supporter = supporterUser()

      for (user in listOf(student, supporter)) {
        val response = getAs("/account", WebAuthTestSupport.login(user.email.value))
        assertEquals(HttpStatusCode.OK, response.status, "[${user.name.value}]")
      }
    }
  }

  // --- 3. the role choice -----------------------------------------------------------

  @Test
  fun `the role choice offers both paths under a nav that offers only sign-out`() {
    testApplication {
      application { testPublicWebModule() }
      val user = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail(), name = "Kendall Reyes")
      val response = getAs("/get-started", WebAuthTestSupport.login(user.email.value))

      assertEquals(HttpStatusCode.OK, response.status)
      val page = Jsoup.parse(response.bodyAsText())
      assertEquals("Welcome, Kendall Reyes", page.selectFirst("main h1")?.text())
      assertEquals(ROLE_CHOICE_QUESTION, page.selectFirst("main h2")?.text())
      assertEquals(STUDENT_CHOICE_LABEL, page.selectFirst("main a[href=/get-started/student]")?.text())
      val supporterForm = assertNotNull(page.selectFirst("main form[action=/get-started/supporter][method=post]"))
      assertEquals(SUPPORTER_CHOICE_LABEL, supporterForm.selectFirst("button[type=submit]")?.text())
      assertNotNull(supporterForm.selectFirst("input[name=$CSRF_FIELD]"), "the supporter POST carries no CSRF field")

      val nav = assertNotNull(page.selectFirst("header nav"))
      assertNotNull(nav.selectFirst("form[action=/sign-out]"), "onboarding offers no way to sign out")
      assertNull(nav.selectFirst("a[href=/chat]"), "onboarding offers the chat, which sends the family straight back")
      assertNull(nav.selectFirst("a[href=/account]"), "onboarding offers the account, which sends the family straight back")
    }
  }

  @Test
  fun `the role choice sends a student to the chat and a supporter to their account`() {
    testApplication {
      application { testPublicWebModule() }
      val student = WebAuthTestSupport.seedStudentUser(WebAuthTestSupport.uniqueEmail())
      val supporter = supporterUser()

      for ((user, destination) in listOf(student to "/chat", supporter to "/account")) {
        val response = getAs("/get-started", WebAuthTestSupport.login(user.email.value))
        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals(destination, response.headers[HttpHeaders.Location])
      }
    }
  }

  // --- 4. the supporter path ---------------------------------------------------------

  @Test
  fun `choosing to support a student makes the supporter row and opens the account page`() {
    testApplication {
      application { testPublicWebModule() }
      val user = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail())
      val token = WebAuthTestSupport.login(user.email.value)

      val response = formPost("/get-started/supporter", sessionToken = token, client = noFollowClient())

      assertEquals(HttpStatusCode.SeeOther, response.status)
      assertEquals("/account", response.headers[HttpHeaders.Location])
      assertNotNull(runBlocking { WebAuthTestSupport.supporterService.find(user.id).getOrThrow() }, "no supporter row was made")
      assertEquals(HttpStatusCode.OK, getAs("/account", token).status, "the account page is still walled")
    }
  }

  @Test
  fun `a closed supporter is refused at 409 with the service's sentence and no new row`() {
    testApplication {
      application { testPublicWebModule() }
      val user = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail())
      val home =
        assertIs<EnsureSupporterOutcome.Present>(
          runBlocking { WebAuthTestSupport.supporterService.ensure(user.id).getOrThrow() },
        ).home
      runBlocking {
        WebAuthTestSupport.database.withConnection { session ->
          SupportersDao.delete(session, home.supporter.id, home.supporter.version).getOrThrow()
        }
      }
      val token = WebAuthTestSupport.login(user.email.value)

      val response = formPost("/get-started/supporter", sessionToken = token, client = noFollowClient())

      assertEquals(HttpStatusCode.Conflict, response.status)
      assertTrue(Jsoup.parse(response.bodyAsText()).text().contains(EnsureSupporterOutcome.Closed.MESSAGE))
      assertEquals(1, WebAuthTestSupport.count("SELECT count(*) FROM supporters"), "a refused mint wrote a row")
    }
  }

  // --- 5. the student form, read ----------------------------------------------------

  @Test
  fun `the student form is three birth selects, three graduation selects and the CSRF field, with no date input`() {
    testApplication {
      application { testPublicWebModule() }
      val user = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail())
      val response = getAs("/get-started/student", WebAuthTestSupport.login(user.email.value))

      assertEquals(HttpStatusCode.OK, response.status)
      val page = Jsoup.parse(response.bodyAsText())
      assertTrue(page.select("input[type=date]").isEmpty(), "the form uses a date input")
      val form = assertNotNull(page.selectFirst("main form[action=/get-started/student][method=post]"))
      for (name in listOf(
        BIRTH_YEAR_FIELD,
        BIRTH_MONTH_FIELD,
        BIRTH_DAY_FIELD,
        GRADUATION_YEAR_FIELD,
        GRADUATION_MONTH_FIELD,
        GRADUATION_DAY_FIELD,
      )) {
        val select = assertNotNull(form.selectFirst("select[name=$name]"), "no [$name] select")
        assertEquals("", select.selectFirst("option[selected]")?.attr("value") ?: "", "[$name] does not start blank")
      }
      assertNotNull(form.selectFirst("input[name=$CSRF_FIELD]"), "the form carries no CSRF field")
      for (name in listOf(RESIDENCY_STATE_FIELD, INCOME_BAND_FIELD)) {
        assertEquals(NOT_ANSWERED_LABEL, form.selectFirst("select[name=$name] option[value=\"\"]")?.text(), "[$name]'s blank option")
      }
      assertEquals(
        IncomeBand.entries.map { it.bracket },
        form.select("select[name=$INCOME_BAND_FIELD] option").drop(1).map { it.text() },
        "the income options are not the bands' own words",
      )
      assertEquals(CREATE_PROFILE_LABEL, form.selectFirst("button[type=submit]")?.text())
    }
  }

  @Test
  fun `a supporter asking for the student form is sent to their account`() {
    testApplication {
      application { testPublicWebModule() }
      val response = getAs("/get-started/student", WebAuthTestSupport.login(supporterUser().email.value))

      assertEquals(HttpStatusCode.Found, response.status)
      assertEquals("/account", response.headers[HttpHeaders.Location])
    }
  }

  // --- 6, 7, 14. the student form, accepted ------------------------------------------

  @Test
  fun `a graduation year, year and month, or full date each makes the student and opens the chat`() {
    testApplication {
      application { testPublicWebModule() }
      for ((month, day, expected) in listOf(
        Triple("", "", graduationYear),
        Triple("6", "", "$graduationYear-06"),
        Triple("6", "12", "$graduationYear-06-12"),
      )) {
        val user = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail())
        val token = WebAuthTestSupport.login(user.email.value)

        val response = submit(token, studentForm(graduation = Triple(graduationYear, month, day)))

        assertEquals(HttpStatusCode.SeeOther, response.status, "[$expected]")
        assertEquals("/chat", response.headers[HttpHeaders.Location], "[$expected]")
        val student = assertNotNull(studentOf(user), "[$expected] made no student")
        assertEquals(expected, student.expectedHighSchoolGraduationDate.toIso())
        assertEquals(LocalDate.of(1999, 7, 10), student.dateOfBirth)
        assertEquals(HttpStatusCode.OK, getAs("/chat", token).status, "[$expected] the chat is still walled")
      }
    }
  }

  @Test
  fun `both optional answers are recorded as answered`() {
    testApplication {
      application { testPublicWebModule() }
      val user = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail())

      submit(WebAuthTestSupport.login(user.email.value), studentForm(residency = "OR", income = IncomeBand.K48_TO_75K.value))

      val profile = assertIs<GetMoneyProfileResult.Found>(moneyProfileOf(assertNotNull(studentOf(user)))).profile
      assertEquals("OR", profile.residencyState)
      assertEquals(AnswerStatus.ANSWERED, profile.residencyStatus)
      assertEquals(IncomeBand.K48_TO_75K, profile.incomeBand)
      assertEquals(AnswerStatus.ANSWERED, profile.incomeBandStatus)
    }
  }

  /** The first-session path: one screen after the role choice, and the coach. */
  @Test
  fun `no optional answer writes no money profile and still opens the chat`() {
    testApplication {
      application { testPublicWebModule() }
      val user = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail())

      val response = submit(WebAuthTestSupport.login(user.email.value), studentForm())

      assertEquals("/chat", response.headers[HttpHeaders.Location])
      assertEquals(GetMoneyProfileResult.NotFound, moneyProfileOf(assertNotNull(studentOf(user))))
      assertEquals(0, WebAuthTestSupport.count("SELECT count(*) FROM money_profiles"))
    }
  }

  @Test
  fun `a successful submission clears the draft a refused one left`() {
    testApplication {
      application { testPublicWebModule() }
      val user = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail())
      val token = WebAuthTestSupport.login(user.email.value)
      submit(token, studentForm(birth = Triple("", "", "")))
      assertNotNull(draftOf(token), "the refused submission left no draft to clear")

      submit(token, studentForm())

      assertNull(draftOf(token), "the draft outlived the submission that succeeded")
    }
  }

  // --- 8-12. the student form, refused -----------------------------------------------

  @Test
  fun `a missing date of birth is refused by the form, and the draft keeps everything but the date of birth`() {
    testApplication {
      application { testPublicWebModule() }
      val user = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail())
      val token = WebAuthTestSupport.login(user.email.value)

      val response =
        submit(
          token,
          studentForm(birth = Triple("1998", "", ""), graduation = Triple(graduationYear, "5", ""), residency = "OR", income = "over_110k"),
        )

      assertEquals(HttpStatusCode.BadRequest, response.status)
      assertTrue(Jsoup.parse(response.bodyAsText()).text().contains(DATE_OF_BIRTH_REQUIRED_MESSAGE))
      assertNull(studentOf(user), "a refused form made a student")
      val draft = assertNotNull(draftOf(token), "the refused submission kept nothing")
      assertEquals(listOf(graduationYear, "5", "", "OR", "over_110k"), draft)
      assertFalse(storedMetadata(user).contains("1998"), "the session holds the birth year")

      val prefilled = Jsoup.parse(getAs("/get-started/student", token).bodyAsText())
      assertEquals(graduationYear, selectedValue(prefilled, GRADUATION_YEAR_FIELD))
      assertEquals("5", selectedValue(prefilled, GRADUATION_MONTH_FIELD))
      assertEquals("OR", selectedValue(prefilled, RESIDENCY_STATE_FIELD))
      assertEquals("over_110k", selectedValue(prefilled, INCOME_BAND_FIELD))
      assertEquals("", selectedValue(prefilled, BIRTH_YEAR_FIELD), "the date of birth came back from the session")
    }
  }

  @Test
  fun `an under-13 date of birth is refused with the service's sentence, a blank form and nothing kept`() {
    testApplication {
      application { testPublicWebModule() }
      val user = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail())
      val token = WebAuthTestSupport.login(user.email.value)
      submit(token, studentForm(birth = Triple("", "", ""), residency = "OR"))
      assertNotNull(draftOf(token), "the setup's refused submission left no draft for the floor to clear")

      val response = submit(token, studentForm(birth = birthOf(underThirteen), residency = "OR"))

      assertEquals(HttpStatusCode.Forbidden, response.status)
      val page = Jsoup.parse(response.bodyAsText())
      assertTrue(page.text().contains(StudentService.UNDER_MINIMUM_AGE_MESSAGE))
      for (name in listOf(BIRTH_YEAR_FIELD, GRADUATION_YEAR_FIELD, RESIDENCY_STATE_FIELD)) {
        assertEquals("", selectedValue(page, name), "[$name] was rendered back after the floor refused")
      }
      assertNull(studentOf(user), "an under-13 submission made a student")
      assertNull(draftOf(token), "the floor's refusal left a draft")
      val stored = storedMetadata(user)
      assertFalse(stored.contains("studentSignup"), "the session holds a signup draft: [$stored]")
      assertFalse(stored.contains(underThirteen.toString()), "the session holds the birth date: [$stored]")
    }
  }

  @Test
  fun `an under-13 date of birth with an impossible graduation date is refused for the graduation date`() {
    testApplication {
      application { testPublicWebModule() }
      val user = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail())
      val token = WebAuthTestSupport.login(user.email.value)

      val response = submit(token, studentForm(birth = birthOf(underThirteen), graduation = Triple(graduationYear, "2", "31")))

      assertEquals(HttpStatusCode.BadRequest, response.status, "a malformed request is never judged on its merits")
      val page = Jsoup.parse(response.bodyAsText())
      assertFalse(page.text().contains(StudentService.UNDER_MINIMUM_AGE_MESSAGE))
      assertTrue(
        page.select("fieldset:has(select[name=$GRADUATION_YEAR_FIELD]) .field-error li").isNotEmpty(),
        "no error at the graduation date",
      )
      assertNull(studentOf(user))
      assertEquals(listOf(graduationYear, "2", "31", "", ""), draftOf(token))
      assertFalse(storedMetadata(user).contains(underThirteen.year.toString()), "the session holds the birth year")
    }
  }

  @Test
  fun `a graduation day without a month is refused by the form and the draft is kept`() {
    testApplication {
      application { testPublicWebModule() }
      val user = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail())
      val token = WebAuthTestSupport.login(user.email.value)

      val response = submit(token, studentForm(graduation = Triple(graduationYear, "", "9")))

      assertEquals(HttpStatusCode.BadRequest, response.status)
      assertTrue(Jsoup.parse(response.bodyAsText()).text().contains(DAY_WITHOUT_MONTH_MESSAGE))
      assertNull(studentOf(user))
      assertEquals(listOf(graduationYear, "", "9", "", ""), draftOf(token))
    }
  }

  @Test
  fun `a value no option carries is not this form's submission, and nothing is written`() {
    testApplication {
      application { testPublicWebModule() }
      val user = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail())
      val token = WebAuthTestSupport.login(user.email.value)
      val before = WebAuthTestSupport.resolveSession(token)!!.session

      for (form in listOf(
        studentForm(income = "a_lot"),
        studentForm(residency = "ZZ"),
        studentForm(graduation = Triple(graduationYear, "13", "")),
        studentForm(birth = Triple("nineteen", "7", "10")),
      )) {
        assertEquals(HttpStatusCode.BadRequest, submit(token, form).status)
      }
      val missing = submit(token, parameters { append(BIRTH_YEAR_FIELD, "1999") })
      assertEquals(HttpStatusCode.BadRequest, missing.status)

      assertNull(studentOf(user))
      assertEquals(before, WebAuthTestSupport.resolveSession(token)!!.session, "a request that is not the form wrote a draft")
      assertEquals(0, WebAuthTestSupport.count("SELECT count(*) FROM money_profiles"))
    }
  }

  // --- 13. CSRF ----------------------------------------------------------------------

  @Test
  fun `both onboarding POSTs are refused without the session's token and write nothing`() {
    testApplication {
      application { testPublicWebModule() }
      val user = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail())
      val token = WebAuthTestSupport.login(user.email.value)
      val before = WebAuthTestSupport.resolveSession(token)!!.session

      for ((path, form) in listOf("/get-started/supporter" to Parameters.Empty, "/get-started/student" to studentForm())) {
        for (csrf in listOf(null, "not-the-token")) {
          val response = formPost(path, form, sessionToken = token, client = noFollowClient(), csrf = csrf)
          assertEquals(HttpStatusCode.Forbidden, response.status, "[$path] with csrf [$csrf]")
        }
      }

      assertNull(studentOf(user))
      assertNull(runBlocking { WebAuthTestSupport.supporterService.find(user.id).getOrThrow() })
      assertEquals(before, WebAuthTestSupport.resolveSession(token)!!.session)
    }
  }

  // --- helpers -----------------------------------------------------------------------

  private suspend fun ApplicationTestBuilder.getAs(
    path: String,
    token: String,
  ): HttpResponse = noFollowClient().get(path) { header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(token)) }

  private suspend fun ApplicationTestBuilder.submit(
    token: String,
    form: Parameters,
  ): HttpResponse = formPost("/get-started/student", form, sessionToken = token, client = noFollowClient())

  /** The whole student form as the page posts it: every select, `""` for a blank one. */
  private fun studentForm(
    birth: Triple<String, String, String> = adultBirth,
    graduation: Triple<String, String, String> = Triple(graduationYear, "", ""),
    residency: String = "",
    income: String = "",
  ): Parameters =
    parameters {
      append(BIRTH_YEAR_FIELD, birth.first)
      append(BIRTH_MONTH_FIELD, birth.second)
      append(BIRTH_DAY_FIELD, birth.third)
      append(GRADUATION_YEAR_FIELD, graduation.first)
      append(GRADUATION_MONTH_FIELD, graduation.second)
      append(GRADUATION_DAY_FIELD, graduation.third)
      append(RESIDENCY_STATE_FIELD, residency)
      append(INCOME_BAND_FIELD, income)
    }

  private fun birthOf(date: LocalDate) = Triple(date.year.toString(), date.monthValue.toString(), date.dayOfMonth.toString())

  /** A verified account that answered "I'm supporting a student". */
  private fun supporterUser(): User {
    val user = WebAuthTestSupport.seedUser(WebAuthTestSupport.uniqueEmail())
    runBlocking { WebAuthTestSupport.supporterService.ensure(user.id).getOrThrow() }
    return user
  }

  private fun studentOf(user: User): Student? = runBlocking { WebAuthTestSupport.studentService.getStudentForUser(user.id).getOrThrow() }

  private fun moneyProfileOf(student: Student): GetMoneyProfileResult =
    runBlocking { WebAuthTestSupport.moneyProfileService.getForStudent(student.id).getOrThrow() }

  /** The signup draft the session behind [token] holds, as its five fields in proto order, or `null`. */
  private fun draftOf(token: String): List<String>? {
    val drafts =
      WebAuthTestSupport
        .resolveSession(token)!!
        .session.metadata.drafts
    if (!drafts.hasStudentSignup()) return null
    val draft = drafts.studentSignup
    return listOf(draft.graduationYear, draft.graduationMonth, draft.graduationDay, draft.residencyState, draft.incomeBand)
  }

  /** Everything [user]'s sessions store, as the column's JSON text. */
  private fun storedMetadata(user: User): String = WebAuthTestSupport.storedSessionMetadata(user.id.value).joinToString("\n")

  /** The value of the option the page renders selected in [name]'s select; `""` for the blank one. */
  private fun selectedValue(
    page: Document,
    name: String,
  ): String = page.selectFirst("select[name=$name] option[selected]")?.attr("value") ?: ""
}
