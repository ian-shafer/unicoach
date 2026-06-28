package ed.unicoach.admin.resources

import ed.unicoach.admin.AdminTestSupport
import ed.unicoach.db.dao.UsersDao
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.db.models.UserId
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
      val listBody = list.bodyAsText()
      val href = firstUserDetailHref(listBody)

      // A typed-id leak would produce "/user/UserId(value=...)"; assert the raw UUID segment.
      assertEquals("/user/${target.id.value}", href, "List link must use the raw id, not the value-class toString()")

      // Uniform cell rendering (RFC 79 + RFC 83): users.id is FieldType.UUID, so
      // the id cell renders the compacted value (ellipsis + tail in a titled span,
      // plus a click-to-copy button carrying the full value) followed by the
      // ref-link glyph. The value text must NOT be wrapped in its own <a>; the full
      // id stays reachable via the title, the copy button, and the glyph href.
      val fullId = target.id.value.toString()
      assertTrue(
        listBody.contains("<span title=\"$fullId\">…${fullId.takeLast(8)}</span>"),
        "The id cell must render the compacted value in a titled span",
      )
      assertTrue(
        listBody.contains("class=\"id-copy\" data-full=\"$fullId\""),
        "The id cell must render a copy button carrying the full id",
      )
      assertTrue(
        listBody.contains("<a href=\"/user/$fullId\" class=\"id-link\">🔗</a>"),
        "The id cell must render the glyph link after the compacted value",
      )
      assertFalse(
        Regex("""<a href="/user/${target.id.value}"(?![^>]*class="id-link")""").containsMatchIn(listBody),
        "No <a> may wrap the id value text; navigation is the glyph alone",
      )

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
  fun `user detail page surfaces the embedded student's coaching-memory panels`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val user = AdminTestSupport.seedUser(AdminTestSupport.uniqueEmail())
      val student = AdminTestSupport.seedStudent(user.id)
      val convo = AdminTestSupport.seedConvo(student.id)
      val req = AdminTestSupport.seedConvoRequest(convo.id)
      val claim = AdminTestSupport.seedClaim(student.id)
      val run = AdminTestSupport.seedExtractionRun(student.id, convo.id, req.id)

      val body = client().get("/user/${user.id.value}") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertTrue(body.contains("/claim/${claim.id.value}"), "User page must link to the student's claim")
      assertTrue(body.contains("/extraction-run/${run.id.value}"), "User page must link to the student's extraction run")
    }

  @Test
  fun `user detail renders isAdmin as a bool glyph and the id row carries a link glyph`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val target = AdminTestSupport.seedUser(AdminTestSupport.uniqueEmail(), name = "Glyph User", isAdmin = true)

      val detail = client().get("/user/${target.id.value}") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, detail.status)
      val body = detail.bodyAsText()

      // isAdmin renders as the configured true glyph in bool-true, never the literal "true".
      assertTrue(body.contains("bool-true"), "Admin row must render the bool-true glyph")
      assertFalse(
        Regex("""Admin</th>\s*<td>\s*true""").containsMatchIn(body),
        "isAdmin must not render the literal 'true'",
      )

      // The id field row carries a link glyph hyperlinking to its own detail page.
      assertTrue(
        body.contains("href=\"/user/${target.id.value}\""),
        "The id field row must link to the user's own detail page via the glyph",
      )
      assertTrue(body.contains("🔗"), "The id field row must carry the link glyph")
    }

  @Test
  fun `a blank ref cell renders the value with no glyph link`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      // An anonymous session (null userId) yields a blank "User ID" cell whose field
      // carries refSlug = "user". On the session detail page that blank ref cell must
      // render no glyph link (blank value -> renderRefLink emits nothing), exercising
      // the blank-ref-value path on a real rendered row.
      val sessionId =
        runBlocking {
          AdminTestSupport.database
            .withConnection { s ->
              ed.unicoach.db.dao.SessionsDao
                .create(
                  s,
                  ed.unicoach.db.models.NewSession(
                    userId = null,
                    tokenHash =
                      ed.unicoach.db.models.TokenHash
                        .fromRawToken(
                          java.util.UUID
                            .randomUUID()
                            .toString(),
                        ),
                    userAgent = "test",
                    initialIp = "127.0.0.1",
                    metadata = null,
                    expiration = java.time.Duration.ofSeconds(3600),
                  ),
                )
            }.getOrThrow()
            .id.value
            .toString()
        }

      val body = client().get("/session/$sessionId") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      // The blank User ID cell must not produce a /user/ glyph link.
      assertFalse(
        Regex("""User ID</th>\s*<td>[^<]*<a""").containsMatchIn(body),
        "A blank ref cell must render no glyph link",
      )
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

  @Test
  fun `unverified active user shows both verification actions enabled`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val target = AdminTestSupport.seedUser(AdminTestSupport.uniqueEmail(), name = "Unverified User")

      val detail = client().get("/user/${target.id.value}") { header(HttpHeaders.Cookie, cookie) }
      assertEquals(HttpStatusCode.OK, detail.status)
      val body = detail.bodyAsText()
      assertTrue(body.contains("Mark email verified"), "Detail must offer the mark-verified action")
      assertTrue(body.contains("Send verification email"), "Detail must offer the send-verification action")
      assertFalse(body.contains("disabled"), "Both action buttons must render enabled")
      assertFalse(body.contains("Email already verified."), "No already-verified title for an unverified user")
    }

  @Test
  fun `mark-verified stamps the column and flips the buttons to disabled`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val target = AdminTestSupport.seedUser(AdminTestSupport.uniqueEmail(), name = "To Verify")

      val verify =
        client().submitForm(url = "/user/${target.id.value}/verify-email", formParameters = parameters {}) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.Found, verify.status)
      assertEquals("/user/${target.id.value}", verify.headers[HttpHeaders.Location])

      val detail = client().get("/user/${target.id.value}") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      // The "Email Verified" row now carries a non-empty timestamp.
      assertTrue(
        Regex("""Email Verified</th>\s*<td>\S+""").containsMatchIn(detail),
        "Email Verified row must show a non-empty timestamp",
      )
      assertTrue(detail.contains("disabled"), "Both buttons must render disabled once verified")
      assertTrue(detail.contains("Email already verified."), "Disabled buttons carry the already-verified title")

      // The version-history panel carries the new "Email Verified" column, and the
      // post-verification history row shows the stamped timestamp (audit trail).
      assertTrue(
        detail.contains("<th>Email Verified</th>"),
        "Version history panel must include the Email Verified column",
      )
      val reloaded = findUser(target.id)
      assertTrue(reloaded.emailVerifiedAt != null, "emailVerifiedAt must be stamped in the DB")
      assertTrue(
        detail.contains(reloaded.emailVerifiedAt.toString()),
        "Version history row must show the verification timestamp",
      )
    }

  @Test
  fun `mark-verified is idempotent and bumps the version exactly once`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val target = AdminTestSupport.seedUser(AdminTestSupport.uniqueEmail(), name = "Idempotent Verify")

      val first =
        client().submitForm(url = "/user/${target.id.value}/verify-email", formParameters = parameters {}) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.Found, first.status)
      val second =
        client().submitForm(url = "/user/${target.id.value}/verify-email", formParameters = parameters {}) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.Found, second.status)

      val reloaded = findUser(target.id)
      assertEquals(target.version + 1, reloaded.version, "Version must bump exactly once across two verify POSTs")
    }

  @Test
  fun `already-verified user renders disabled buttons with the title`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val target = AdminTestSupport.seedUser(AdminTestSupport.uniqueEmail(), name = "Pre Verified")
      runBlocking {
        AdminTestSupport.database
          .withConnection { session -> UsersDao.markEmailVerified(session, target.id) }
          .getOrThrow()
      }

      val detail = client().get("/user/${target.id.value}") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertTrue(detail.contains("disabled"), "Verified user's buttons must render disabled")
      assertTrue(detail.contains("Email already verified."), "Disabled buttons carry the already-verified title")
    }

  @Test
  fun `soft-deleted user renders disabled verification buttons with the deleted title`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val target = AdminTestSupport.seedUser(AdminTestSupport.uniqueEmail(), name = "Deleted For Verify")

      client().submitForm(url = "/user/${target.id.value}/delete", formParameters = parameters {}) {
        header(HttpHeaders.Cookie, cookie)
      }

      val detail = client().get("/user/${target.id.value}") { header(HttpHeaders.Cookie, cookie) }.bodyAsText()
      assertTrue(detail.contains("disabled"), "Soft-deleted user's buttons must render disabled")
      assertTrue(detail.contains("User is deleted."), "Disabled buttons carry the deleted title")
    }

  @Test
  fun `send-verification-email issues an unconsumed token`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val target = AdminTestSupport.seedUser(AdminTestSupport.uniqueEmail(), name = "Send Token")

      val sent =
        client().submitForm(url = "/user/${target.id.value}/send-verification-email", formParameters = parameters {}) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.Found, sent.status)
      assertEquals("/user/${target.id.value}", sent.headers[HttpHeaders.Location])
      assertEquals(1, unconsumedTokenCount(target.id), "A fresh unconsumed verification token must exist")
    }

  @Test
  fun `send-verification-email on a verified user is a no-op success`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val target = AdminTestSupport.seedUser(AdminTestSupport.uniqueEmail(), name = "Verified No Token")
      runBlocking {
        AdminTestSupport.database
          .withConnection { session -> UsersDao.markEmailVerified(session, target.id) }
          .getOrThrow()
      }

      val sent =
        client().submitForm(url = "/user/${target.id.value}/send-verification-email", formParameters = parameters {}) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.Found, sent.status)
      assertEquals(0, unconsumedTokenCount(target.id), "An already-verified user gets no token (resend short-circuits)")
    }

  @Test
  fun `forged verification POSTs on a soft-deleted user return 404`() =
    testApplication {
      application { with(AdminTestSupport) { installTestAdminModule() } }
      val cookie = adminCookie()
      val target = AdminTestSupport.seedUser(AdminTestSupport.uniqueEmail(), name = "Deleted Forged")

      client().submitForm(url = "/user/${target.id.value}/delete", formParameters = parameters {}) {
        header(HttpHeaders.Cookie, cookie)
      }

      val verify =
        client().submitForm(url = "/user/${target.id.value}/verify-email", formParameters = parameters {}) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.NotFound, verify.status, "verify-email on a soft-deleted user must 404")

      val send =
        client().submitForm(url = "/user/${target.id.value}/send-verification-email", formParameters = parameters {}) {
          header(HttpHeaders.Cookie, cookie)
        }
      assertEquals(HttpStatusCode.NotFound, send.status, "send-verification-email on a soft-deleted user must 404")
    }
}

/** Reloads a user (including soft-deleted) directly from the test DB. */
private fun findUser(id: UserId) =
  runBlocking {
    AdminTestSupport.database
      .withConnection { session -> UsersDao.findById(session, id, SoftDeleteScope.ALL) }
      .getOrThrow()
  }

/** Counts unconsumed verification_tokens rows for a user via a direct test-DB query. */
private fun unconsumedTokenCount(id: UserId): Int {
  val dbConfig =
    ed.unicoach.db.DatabaseConfig
      .from(AdminTestSupport.config)
      .getOrThrow()
  val sql = "SELECT COUNT(*) FROM verification_tokens WHERE user_id = ? AND consumed_at IS NULL"
  return java.sql.DriverManager
    .getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
    .use { conn ->
      conn.prepareStatement(sql).use { stmt ->
        stmt.setObject(1, id.value)
        stmt.executeQuery().use { rs ->
          rs.next()
          rs.getInt(1)
        }
      }
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

private fun ed.unicoach.db.models.User.authMethodHashOrEmpty(): String = passwordHash?.value ?: "no-hash-sentinel"
