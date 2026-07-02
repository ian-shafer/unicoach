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
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SystemPromptsResourceTest {
  @BeforeTest
  fun reset() = AdminTestSupport.resetDatabase()

  private fun ApplicationTestBuilder.client() = createClient { followRedirects = false }

  private fun adminCookie(): String {
    val email = AdminTestSupport.uniqueEmail()
    AdminTestSupport.seedUser(email, isAdmin = true)
    return AdminTestSupport.cookieHeader(AdminTestSupport.login(email, "Password123!"))
  }

  /** A unique name per test so cases never collide on (name, version). */
  private fun uniqueName(prefix: String): String = "rfc63-$prefix-${UUID.randomUUID()}"

  @Test
  fun `gate allows the list and the dashboard lists the resource in nav`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()

      val list = client().get("/system-prompt") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, list.status)

      val dashboard = client().get("/") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, dashboard.status)
      assertTrue(dashboard.bodyAsText().contains("System Prompt"), "Dashboard nav must list the topLevel resource")
      assertTrue(dashboard.bodyAsText().contains("/system-prompt"), "Dashboard must link to /system-prompt")
    }

  @Test
  fun `list omits the body but shows name and version`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val name = uniqueName("listed")
      val body = "UNIQUE_BODY_MARKER_${UUID.randomUUID()} ".repeat(50)
      AdminTestSupport.seedSystemPrompt(name, "v1", body)

      val list = client().get("/system-prompt") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertTrue(list.contains(name), "List must render the name")
      assertTrue(list.contains("v1"), "List must render the version")
      assertFalse(list.contains("UNIQUE_BODY_MARKER_"), "List must omit the body (inList = false)")
    }

  @Test
  fun `detail shows the full body and renders no edit or delete controls`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val name = uniqueName("detail")
      val bodyMarker = "DETAIL_BODY_${UUID.randomUUID()}"
      val prompt = AdminTestSupport.seedSystemPrompt(name, "v1", "$bodyMarker\nsecond line")

      val detail = client().get("/system-prompt/${prompt.id.value}") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, detail.status)
      val body = detail.bodyAsText()
      assertTrue(body.contains(bodyMarker), "Detail must render the full body")
      assertTrue(body.contains(name), "Detail must render the name")
      assertTrue(body.contains("v1"), "Detail must render the version")
      assertTrue(body.contains(prompt.createdAt.toString()), "Detail must render createdAt")

      // Assert on the control URLs, not the words Edit/Delete (the body could contain them).
      assertFalse(body.contains("/system-prompt/${prompt.id.value}/edit"), "No edit control")
      assertFalse(body.contains("/system-prompt/${prompt.id.value}/delete"), "No delete control")
      assertFalse(body.contains("/system-prompt/${prompt.id.value}/undelete"), "No undelete control")
    }

  @Test
  fun `create form renders name version and body inputs but no id or createdAt`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()

      val form = client().get("/system-prompt/new") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, form.status)
      val body = form.bodyAsText()
      assertTrue(body.contains("name=\"name\""), "Form must have a name input")
      assertTrue(body.contains("name=\"version\""), "Form must have a version input")
      assertTrue(body.contains("<textarea") && body.contains("name=\"body\""), "Form must have a body textarea")
      assertFalse(body.contains("name=\"id\""), "Form must not have an id input")
      assertFalse(body.contains("name=\"createdAt\""), "Form must not have a createdAt input")
    }

  @Test
  fun `create success redirects to the new detail page and the row is resolvable`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val name = uniqueName("create")

      val created =
        client().submitForm(
          url = "/system-prompt",
          formParameters =
            parameters {
              append("name", name)
              append("version", "v1")
              append("body", "Created body")
            },
        ) { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.Found, created.status)
      val location = created.headers[HttpHeaders.Location]
      assertTrue(location != null, "Create must set a Location header")
      assertFalse(location.contains("SystemPromptId("), "Redirect must use the raw id, not the value class")

      val detail = client().get(location) { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, detail.status, "Following the create redirect must reach a real detail page")

      val row =
        AdminTestSupport.database
          .withConnection { session ->
            ed.unicoach.db.dao.SystemPromptsDao
              .findByNameAndVersion(session, name, "v1")
          }.getOrThrow()
      assertEquals("Created body", row.body)
    }

  @Test
  fun `create with a duplicate name-version re-renders the form with a 400 and no second row`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val name = uniqueName("dup")
      val original = AdminTestSupport.seedSystemPrompt(name, "v1", "Original body")

      val duplicate =
        client().submitForm(
          url = "/system-prompt",
          formParameters =
            parameters {
              append("name", name)
              append("version", "v1")
              append("body", "Duplicate body")
            },
        ) { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.BadRequest, duplicate.status)
      assertTrue(duplicate.bodyAsText().contains("constraint"), "Form must show the constraint message")

      val row =
        AdminTestSupport.database
          .withConnection { session ->
            ed.unicoach.db.dao.SystemPromptsDao
              .findByNameAndVersion(session, name, "v1")
          }.getOrThrow()
      assertEquals(original.id, row.id, "No second row may be created; the original must still resolve")
      assertEquals("Original body", row.body)
    }

  @Test
  fun `create with a blank body re-renders the form with a 400 and creates no row`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val name = uniqueName("blank")

      val blank =
        client().submitForm(
          url = "/system-prompt",
          formParameters =
            parameters {
              append("name", name)
              append("version", "v1")
              append("body", "")
            },
        ) { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.BadRequest, blank.status)

      val result =
        AdminTestSupport.database.withConnection { session ->
          ed.unicoach.db.dao.SystemPromptsDao
            .findByNameAndVersion(session, name, "v1")
        }
      assertTrue(
        result.exceptionOrNull() is ed.unicoach.db.dao.NotFoundException,
        "No row may be created for a blank body, got $result",
      )
    }

  @Test
  fun `body trailing whitespace is preserved verbatim while name is trimmed`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val name = uniqueName("verbatim")
      val body = "prompt body\n"

      val created =
        client().submitForm(
          url = "/system-prompt",
          formParameters =
            parameters {
              append("name", "  $name  ")
              append("version", "v1")
              append("body", body)
            },
        ) { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.Found, created.status)

      // name was submitted with surrounding spaces; it must be stored trimmed.
      val row =
        AdminTestSupport.database
          .withConnection { session ->
            ed.unicoach.db.dao.SystemPromptsDao
              .findByNameAndVersion(session, name, "v1")
          }.getOrThrow()
      assertEquals(name, row.name, "name must be trimmed")
      assertEquals(body, row.body, "body must be stored verbatim with its trailing newline")
    }

  @Test
  fun `immutability routes return the not-found page for an existing prompt`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val name = uniqueName("immutable")
      val prompt = AdminTestSupport.seedSystemPrompt(name, "v1", "Immutable body")
      val id = prompt.id.value

      val edit = client().get("/system-prompt/$id/edit") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.NotFound, edit.status, "GET edit must 404 (update is null)")

      val update =
        client().submitForm(url = "/system-prompt/$id", formParameters = parameters { append("name", "x") }) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.NotFound, update.status, "POST update must 404 (update is null)")

      val delete =
        client().submitForm(url = "/system-prompt/$id/delete", formParameters = parameters {}) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.NotFound, delete.status, "POST delete must 404 (delete is null)")
    }

  @Test
  fun `a malformed id segment returns the not-found page`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()

      val malformed = client().get("/system-prompt/not-a-uuid") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.NotFound, malformed.status, "parseId must reject non-UUID segments")
    }
}
