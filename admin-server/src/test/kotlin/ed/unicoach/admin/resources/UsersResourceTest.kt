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

class UsersResourceTest {
  @BeforeTest
  fun reset() = AdminTestSupport.resetDatabase()

  private fun ApplicationTestBuilder.client() = createClient { followRedirects = false }

  private fun adminCookie(): String {
    val email = AdminTestSupport.uniqueEmail()
    AdminTestSupport.seedUser(email, isAdmin = true)
    return AdminTestSupport.cookieHeader(AdminTestSupport.login(email, "Password123!"))
  }

  @Test
  fun `users list and detail render with password hash redacted and absent from edit form`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val target = AdminTestSupport.seedUser(AdminTestSupport.uniqueEmail(), name = "Listed User")

      val list = client().get("/user") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, list.status)
      assertTrue(list.bodyAsText().contains("Listed User"))

      val detail = client().get("/user/${target.id.value}") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, detail.status)
      val detailBody = detail.bodyAsText()
      assertTrue(detailBody.contains("redacted"), "Password hash should be redacted in the detail view")
      assertFalse(detailBody.contains(target.authMethodHashOrEmpty()), "Raw password hash must never render")

      val edit = client().get("/user/${target.id.value}/edit") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, edit.status)
      assertFalse(edit.bodyAsText().contains("name=\"passwordHash\""), "Password hash must be absent from the edit form")
    }

  @Test
  fun `rendered list link round-trips through parseId and create-redirect lands on a real detail page`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val target = AdminTestSupport.seedUser(AdminTestSupport.uniqueEmail(), name = "Linked User")

      // 1. Render the list, extract the ACTUAL href of the user row link, follow it.
      val list = client().get("/user") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, list.status)
      val href = firstUserDetailHref(list.bodyAsText())

      // A typed-id leak would produce "/user/UserId(value=...)"; assert the raw UUID segment.
      assertEquals("/user/${target.id.value}", href, "List link must use the raw id, not the value-class toString()")

      val detail = client().get(href) { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, detail.status, "Following the rendered list link must reach the detail page, not 404")
      assertTrue(detail.bodyAsText().contains("Linked User"), "Detail page should show the linked user")

      // 2. The post-create redirect Location must point at a parseable detail URL (200, not 404).
      val email = AdminTestSupport.uniqueEmail()
      val created =
        client().submitForm(
          url = "/user",
          formParameters =
            parameters {
              append("email", email)
              append("name", "Redirect Target")
              append("password", "CreatedPass123!")
              append("isAdmin", "false")
            },
        ) { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.Found, created.status)
      val createLocation = created.headers[HttpHeaders.Location]
      assertTrue(createLocation != null, "Create must set a Location header")
      assertFalse(createLocation.contains("UserId("), "Create redirect must not contain a value-class toString()")
      val afterCreate = client().get(createLocation) { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, afterCreate.status, "Following the create redirect must reach the detail page, not 404")
    }

  @Test
  fun `update redirect Location points at a parseable detail URL`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val target = AdminTestSupport.seedUser(AdminTestSupport.uniqueEmail(), name = "Update Redirect")

      val update =
        client().submitForm(
          url = "/user/${target.id.value}",
          formParameters =
            parameters {
              append("version", target.version.toString())
              append("email", target.email.value)
              append("name", "Updated Name")
              append("isAdmin", "false")
            },
        ) { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.Found, update.status)
      val location = update.headers[HttpHeaders.Location]
      assertTrue(location != null, "Update must set a Location header")
      assertFalse(location.contains("UserId("), "Update redirect must not contain a value-class toString()")
      val followed = client().get(location) { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, followed.status, "Following the update redirect must reach the detail page, not 404")
    }

  @Test
  fun `users list paginates with next and previous links and drops the surplus row`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      // Seed > one page (51 + the admin = 52). PAGE_SIZE is 50.
      repeat(51) { AdminTestSupport.seedUser(AdminTestSupport.uniqueEmail()) }

      val page0 = client().get("/user") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertTrue(page0.contains("?offset=50"), "Expected a next link on the first page")
      assertFalse(page0.contains("Previous"), "First page has no previous link")

      val page1 = client().get("/user?offset=50") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertTrue(page1.contains("Previous"), "Second page has a previous link")
    }

  @Test
  fun `create makes a loginable user and duplicate email re-renders the form with an error`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val email = AdminTestSupport.uniqueEmail()

      val created =
        client().submitForm(
          url = "/user",
          formParameters =
            parameters {
              append("email", email)
              append("name", "Created Person")
              append("password", "CreatedPass123!")
              append("isAdmin", "false")
            },
        ) { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.Found, created.status)

      // The created user can authenticate (password hashed correctly).
      val loginResult =
        runBlocking {
          AdminTestSupport.authService
            .login(email, "CreatedPass123!", null, 3600, "test", "127.0.0.1")
            .getOrThrow()
        }
      assertTrue(loginResult is ed.unicoach.auth.LoginResult.Success, "Created user should be loginable")

      val duplicate =
        client().submitForm(
          url = "/user",
          formParameters =
            parameters {
              append("email", email)
              append("name", "Dupe")
              append("password", "AnotherPass123!")
            },
        ) { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.BadRequest, duplicate.status)
      assertTrue(duplicate.bodyAsText().contains("Email already in use"))
    }

  @Test
  fun `update bumps version and a stale version renders the conflict page`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val target = AdminTestSupport.seedUser(AdminTestSupport.uniqueEmail(), name = "Before Edit")

      val update =
        client().submitForm(
          url = "/user/${target.id.value}",
          formParameters =
            parameters {
              append("version", target.version.toString())
              append("email", target.email.value)
              append("name", "After Edit")
              append("isAdmin", "false")
            },
        ) { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.Found, update.status)

      val detail = client().get("/user/${target.id.value}") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertTrue(detail.contains("After Edit"))

      // Re-submit with the now-stale original version.
      val stale =
        client().submitForm(
          url = "/user/${target.id.value}",
          formParameters =
            parameters {
              append("version", target.version.toString())
              append("email", target.email.value)
              append("name", "Stale Edit")
              append("isAdmin", "false")
            },
        ) { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.Conflict, stale.status)
    }

  @Test
  fun `soft-delete marks the row and undelete restores it`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val target = AdminTestSupport.seedUser(AdminTestSupport.uniqueEmail(), name = "Deletable")

      val deleted =
        client().submitForm(url = "/user/${target.id.value}/delete", formParameters = parameters {}) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.Found, deleted.status)

      val afterDelete = client().get("/user/${target.id.value}") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertTrue(afterDelete.contains("deleted"), "Soft-deleted row stays visible and marked")
      assertTrue(afterDelete.contains("Undelete"), "Soft-deleted row offers undelete")

      val undeleted =
        client().submitForm(url = "/user/${target.id.value}/undelete", formParameters = parameters {}) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.Found, undeleted.status)

      val afterUndelete = client().get("/user/${target.id.value}") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertFalse(afterUndelete.contains("Undelete"), "Restored row no longer offers undelete")
    }

  @Test
  fun `granting admin via the form is reflected on the next gate evaluation`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val plainEmail = AdminTestSupport.uniqueEmail()
      val plain = AdminTestSupport.seedUser(plainEmail, isAdmin = false)
      val plainCookie = AdminTestSupport.cookieHeader(AdminTestSupport.login(plainEmail, "Password123!"))

      // Before grant: the plain user is forbidden.
      assertEquals(
        HttpStatusCode.Forbidden,
        client().get("/user") { header(HttpHeaders.Cookie, plainCookie) }.status,
      )

      // Grant admin via the edit form.
      val grant =
        client().submitForm(
          url = "/user/${plain.id.value}",
          formParameters =
            parameters {
              append("version", plain.version.toString())
              append("email", plain.email.value)
              append("name", plain.name.value)
              append("isAdmin", "true")
            },
        ) { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.Found, grant.status)

      // After grant: the same session now reaches the gated route.
      assertEquals(
        HttpStatusCode.OK,
        client().get("/user") { header(HttpHeaders.Cookie, plainCookie) }.status,
      )
    }
}

/** Extracts the href of the first `/user/{id}` detail link in the rendered list HTML. */
private fun firstUserDetailHref(html: String): String {
  val match =
    Regex("""href="(/user/[^"/]+)"""").findAll(html).firstOrNull { result ->
      val href = result.groupValues[1]
      href != "/user/new" && !href.contains("?")
    }
  return requireNotNull(match) { "No user detail link found in list HTML" }.groupValues[1]
}

private fun ed.unicoach.db.models.User.authMethodHashOrEmpty(): String =
  when (val m = authMethod) {
    is ed.unicoach.db.models.AuthMethod.Password -> m.hash.value
    is ed.unicoach.db.models.AuthMethod.Both -> m.hash.value
    is ed.unicoach.db.models.AuthMethod.SSO -> "no-hash-sentinel"
  }
