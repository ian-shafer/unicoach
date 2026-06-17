package ed.unicoach.admin.auth

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
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdminAuthTest {
  @BeforeTest
  fun reset() = AdminTestSupport.resetDatabase()

  private fun ApplicationTestBuilder.noRedirectClient() = createClient { followRedirects = false }

  @Test
  fun `unauthenticated request to a gated route redirects to login`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val response = noRedirectClient().get("/user")
      assertEquals(HttpStatusCode.Found, response.status)
      assertEquals("/login", response.headers[HttpHeaders.Location])
    }

  @Test
  fun `non-admin session is forbidden on a gated route`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val email = AdminTestSupport.uniqueEmail()
      AdminTestSupport.seedUser(email, isAdmin = false)
      val token = AdminTestSupport.login(email, "Password123!")

      val response =
        noRedirectClient().get("/user") {
          header(HttpHeaders.Cookie, AdminTestSupport.cookieHeader(token))
        }
      assertEquals(HttpStatusCode.Forbidden, response.status)
    }

  @Test
  fun `admin session reaches dashboard and top-level lists`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val email = AdminTestSupport.uniqueEmail()
      AdminTestSupport.seedUser(email, isAdmin = true)
      val token = AdminTestSupport.login(email, "Password123!")
      val cookie = AdminTestSupport.cookieHeader(token)

      listOf("/", "/user", "/session").forEach { path ->
        val response = noRedirectClient().get(path) { header(HttpHeaders.Cookie, cookie) }
        assertEquals(HttpStatusCode.OK, response.status, "Expected 200 on $path")
      }
    }

  @Test
  fun `login with valid admin credentials sets cookie and redirects then logout clears it`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val email = AdminTestSupport.uniqueEmail()
      AdminTestSupport.seedUser(email, isAdmin = true)
      val client = noRedirectClient()

      val loginResponse =
        client.submitForm(
          url = "/login",
          formParameters =
            parameters {
              append("email", email)
              append("password", "Password123!")
            },
        )
      assertEquals(HttpStatusCode.Found, loginResponse.status)
      assertEquals("/", loginResponse.headers[HttpHeaders.Location])
      val setCookie = loginResponse.headers[HttpHeaders.SetCookie]
      assertTrue(setCookie != null && setCookie.contains(AdminTestSupport.adminConfig.cookieName), "Expected session cookie")

      val token = AdminTestSupport.login(email, "Password123!")
      val cookie = AdminTestSupport.cookieHeader(token)
      val logoutResponse =
        client.submitForm(url = "/logout", formParameters = parameters {}) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.Found, logoutResponse.status)

      // The revoked token no longer resolves: a gated request redirects to login.
      val afterLogout = client.get("/user") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.Found, afterLogout.status)
      assertEquals("/login", afterLogout.headers[HttpHeaders.Location])
    }

  @Test
  fun `login failure re-renders the form with a generic message and sets no cookie`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val email = AdminTestSupport.uniqueEmail()
      AdminTestSupport.seedUser(email, isAdmin = true)
      val client = noRedirectClient()

      val wrongPassword =
        client.submitForm(
          url = "/login",
          formParameters =
            parameters {
              append("email", email)
              append("password", "WrongPassword!")
            },
        )
      assertEquals(HttpStatusCode.Unauthorized, wrongPassword.status)
      assertTrue(wrongPassword.bodyAsText().contains("invalid email or password"))
      assertNull(wrongPassword.headers[HttpHeaders.SetCookie])

      val unknownEmail =
        client.submitForm(
          url = "/login",
          formParameters =
            parameters {
              append("email", AdminTestSupport.uniqueEmail())
              append("password", "Password123!")
            },
        )
      assertEquals(HttpStatusCode.Unauthorized, unknownEmail.status)
      assertTrue(unknownEmail.bodyAsText().contains("invalid email or password"))
      assertNull(unknownEmail.headers[HttpHeaders.SetCookie])
    }
}
