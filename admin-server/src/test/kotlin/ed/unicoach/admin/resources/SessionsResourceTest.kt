package ed.unicoach.admin.resources

import ed.unicoach.admin.AdminTestSupport
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionsResourceTest {
  @BeforeTest
  fun reset() = AdminTestSupport.resetDatabase()

  private fun ApplicationTestBuilder.client() = createClient { followRedirects = false }

  private fun adminCookie(): String {
    val email = AdminTestSupport.uniqueEmail()
    AdminTestSupport.seedUser(email, isAdmin = true)
    return AdminTestSupport.cookieHeader(AdminTestSupport.login(email, "Password123!"))
  }

  /** Seed a user with an active session and return (userId, sessionId). */
  private fun seedUserWithSession(): Pair<String, String> {
    val email = AdminTestSupport.uniqueEmail()
    val user = AdminTestSupport.seedUser(email)
    val token = AdminTestSupport.login(email, "Password123!")
    val sessionId =
      runBlocking {
        AdminTestSupport.database
          .withConnection { s ->
            ed.unicoach.db.dao.SessionsDao
              .findByTokenHash(
                s,
                ed.unicoach.db.models.TokenHash
                  .fromRawToken(token),
              )
          }.getOrThrow()
          .id.value
          .toString()
      }
    return user.id.value.toString() to sessionId
  }

  @Test
  fun `session list and detail link to the owner and never render the token hash`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val (userId, sessionId) = seedUserWithSession()

      val list = client().get("/session") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, list.status)
      assertFalse(list.bodyAsText().contains("Token Hash") && list.bodyAsText().contains("\\x"), "Raw token must not render")

      val detail = client().get("/session/$sessionId") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, detail.status)
      val body = detail.bodyAsText()
      assertTrue(body.contains("redacted"), "token_hash should be redacted")
      assertTrue(body.contains("/user/$userId"), "Detail links back to the owning user")
    }

  @Test
  fun `deleting a session physically removes it`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val (_, sessionId) = seedUserWithSession()

      val deleted =
        client().submitForm(url = "/session/$sessionId/delete", formParameters = parameters {}) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.Found, deleted.status)

      val afterDelete = client().get("/session/$sessionId") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.NotFound, afterDelete.status)
    }

  @Test
  fun `a users session has-many row links to the canonical session detail path`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val (userId, sessionId) = seedUserWithSession()

      val userPage = client().get("/user/$userId") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertTrue(
        userPage.contains("/session/$sessionId"),
        "The has-many session row must link to the canonical /session/{id} path",
      )
      assertFalse(
        userPage.contains("/user/$userId/session/"),
        "There must be no nested session detail path",
      )
    }
}
