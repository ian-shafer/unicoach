package ed.unicoach.web

import ed.unicoach.db.models.SupporterRelationship
import ed.unicoach.web.common.http.CACHE_CONTROL_NO_STORE
import ed.unicoach.web.common.http.REFERRER_POLICY_HEADER
import ed.unicoach.web.common.http.REFERRER_POLICY_NO_REFERRER
import ed.unicoach.web.common.http.ROBOTS_NOINDEX
import ed.unicoach.web.common.http.X_ROBOTS_TAG_HEADER
import ed.unicoach.web.render.INVITE_HEADING
import ed.unicoach.web.render.INVITE_OWN_LINK_NOTICE
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The `GET/POST /invite` contract (RFC 232): value before ask, one uniform
 * refusal for every dead token, and atomic accept for signed-in and registering
 * recipients.
 */
class InviteRoutingTest {
  @BeforeEach
  fun resetDatabase() {
    WebAuthTestSupport.resetDatabase()
  }

  private fun mintStudentInvite(relationship: SupporterRelationship = SupporterRelationship.PARENT): String {
    val studentUser = WebAuthTestSupport.seedUser("student-${WebAuthTestSupport.uniqueEmail()}", name = "Student Alex")
    WebAuthTestSupport.seedStudent(studentUser)
    return runBlocking {
      WebAuthTestSupport.supporterInviteService
        .create(studentUser.id, relationship)
        .getOrThrow()
        .rawToken
    }
  }

  /**
   * A SUPPORTER-minted invite: the inviter has no `students` row, so
   * `SupporterInviteService` resolves them to the supporter endpoint and the
   * page renders the other half of [invitationSentence].
   */
  private fun mintSupporterInvite(relationship: SupporterRelationship = SupporterRelationship.PARENT): String {
    val supporterUser = WebAuthTestSupport.seedUser("sup-${WebAuthTestSupport.uniqueEmail()}", name = "Jane Parent")
    return runBlocking {
      WebAuthTestSupport.supporterInviteService
        .create(supporterUser.id, relationship)
        .getOrThrow()
        .rawToken
    }
  }

  @Test
  fun `a live invite renders the ceiling with no sign-in required`() =
    testApplication {
      application {
        testPublicWebModule()
      }
      val token = mintStudentInvite()

      val response = client.get("/invite?token=$token")

      assertEquals(HttpStatusCode.OK, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains(INVITE_HEADING), "missing invite heading marker")
      assertTrue(body.contains("Student Alex"), "missing inviter name")
      assertTrue(body.contains("What connecting lets you do"), "missing the ceiling statement")
      assertTrue(body.contains("What stays private"), "missing the privacy half of the ceiling")
      assertTrue(body.contains("Create an account to accept"), "missing the signed-out register door")
      assertTrue(body.contains("Sign in to accept"), "missing the signed-out sign-in door")
      assertTrue(!body.contains("name=\"email\""), "a signed-out visitor must not be asked for credentials")
    }

  /**
   * The word nobody stated (RFC 240 decision 4). Both doors mint `supporter`
   * when no relationship was named, so the sentence that word renders has to be
   * true of a parent and of anyone else. The named words are untouched, which is
   * the half a single-label implementation would quietly break.
   */
  @Test
  fun `an unstated relationship reads as parent or supporter and a named one keeps its own word`() =
    testApplication {
      application {
        testPublicWebModule()
      }
      val unstated = mintStudentInvite(SupporterRelationship.SUPPORTER)
      val named = mintStudentInvite(SupporterRelationship.GRANDPARENT)

      val unstatedBody = client.get("/invite?token=$unstated").bodyAsText()
      val namedBody = client.get("/invite?token=$named").bodyAsText()

      assertTrue(
        unstatedBody.contains("invited you to connect on unicoach as their parent or supporter."),
        "an unstated relationship must read as parent or supporter: [$unstatedBody]",
      )
      assertTrue(
        namedBody.contains("invited you to connect on unicoach as their grandparent."),
        "a stated relationship keeps its own word: [$namedBody]",
      )
    }

  /**
   * The OTHER sentence the relationship word feeds — the supporter-minted
   * invite, which no test asserted while one label served both (RFC 240).
   *
   * Here the inviter is NAMED one apposition away, so the unstated word must be
   * a single word: "Jane — your parent or supporter —" offers the student two
   * readings of somebody they already know. A stated word is unchanged, which
   * is the half a one-word-fits-both edit would break.
   */
  @Test
  fun `a supporter-minted invite names the inviter in one word`() =
    testApplication {
      application {
        testPublicWebModule()
      }
      val unstated = mintSupporterInvite(SupporterRelationship.SUPPORTER)
      val named = mintSupporterInvite(SupporterRelationship.GRANDPARENT)

      val unstatedBody = client.get("/invite?token=$unstated").bodyAsText()
      val namedBody = client.get("/invite?token=$named").bodyAsText()

      assertTrue(
        unstatedBody.contains("— your supporter — invited you to connect on unicoach."),
        "an unstated relationship reads as ONE word in the apposition: [$unstatedBody]",
      )
      assertFalse(
        unstatedBody.contains("your parent or supporter"),
        "the recipient's own-label wording must not leak into the inviter apposition: [$unstatedBody]",
      )
      assertTrue(
        namedBody.contains("— your grandparent — invited you to connect on unicoach."),
        "a stated relationship keeps its own word on this sentence too: [$namedBody]",
      )
    }

  /**
   * All SIX dead-token states RFC 232 names, not the three a request can reach
   * by accident. Expired, consumed and revoked are the ones that prove there is
   * no oracle: each was a LIVE invite, and an implementation that rendered a
   * distinguishable body — or a 200 — for any of them would otherwise ship green.
   */
  @Test
  fun `every dead token state renders one identical branded 404`() =
    testApplication {
      application {
        testPublicWebModule()
      }
      val expired = mintStudentInvite().also { WebAuthTestSupport.expireInvite(it) }
      val revoked = mintStudentInvite().also { WebAuthTestSupport.revokeInvite(it) }
      val consumed = mintStudentInvite().also { WebAuthTestSupport.consumeInvite(it) }

      // LABELLED, and all six checked: an anonymous list halted on the first
      // miss, so an implementation serving a 200 for `consumed` reported "a dead
      // token is a 404" and left the remaining states unattempted — the operator
      // learning neither how many broke nor which.
      val cases =
        mapOf(
          "unknown" to client.get("/invite?token=never-minted"),
          "blank" to client.get("/invite?token="),
          "absent" to client.get("/invite"),
          "expired" to client.get("/invite?token=$expired"),
          "revoked" to client.get("/invite?token=$revoked"),
          "consumed" to client.get("/invite?token=$consumed"),
        )

      val wrongStatus = cases.filterValues { it.status != HttpStatusCode.NotFound }
      assertTrue(
        wrongStatus.isEmpty(),
        "these token states are not a 404: ${wrongStatus.mapValues { it.value.status }}",
      )

      val bodies = cases.mapValues { it.value.bodyAsText() }
      val branded = bodies.getValue("unknown")
      assertTrue(branded.contains("404 Not Found"), "missing the branded 404 marker")
      val distinguishable = bodies.filterValues { it != branded }.keys
      assertTrue(distinguishable.isEmpty(), "these token states render a distinguishable body: $distinguishable")
    }

  /**
   * The INVITER opening their own link. `SupporterInviteService.accept` refuses a self-addressed
   * invite, and that refusal renders the branded 404 — so an accept button here
   * offers the one person who cannot use it an action that tells them their
   * live invitation is dead.
   */
  @Test
  fun `the inviter reading their own link is not offered an accept button`() =
    testApplication {
      application {
        testPublicWebModule()
      }
      val studentUser = WebAuthTestSupport.seedUser("student-${WebAuthTestSupport.uniqueEmail()}", name = "Student Alex")
      WebAuthTestSupport.seedStudent(studentUser)
      val token =
        runBlocking {
          WebAuthTestSupport.supporterInviteService
            .create(studentUser.id, SupporterRelationship.PARENT)
            .getOrThrow()
            .rawToken
        }
      val session = WebAuthTestSupport.login(studentUser.email.value)

      val response =
        client.get("/invite?token=$token") {
          header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(session))
        }

      assertEquals(HttpStatusCode.OK, response.status)
      val body = response.bodyAsText()
      assertTrue(body.contains(INVITE_OWN_LINK_NOTICE), "the inviter is told it is their own link")
      assertTrue(!body.contains("Accept invitation"), "the inviter is not offered an accept they cannot make")
    }

  /**
   * The two doors `/invite` forwards a signed-out recipient to carry the RAW
   * single-use token in their URL, so they carry `/invite`'s own link-holder
   * headers: no shared cache copy, no index, no `Referer`.
   */
  @Test
  fun `the doors that carry an invite token set the link-holder privacy headers`() =
    testApplication {
      application {
        testPublicWebModule()
      }
      val token = mintStudentInvite()

      listOf("/sign-in?token=$token", "/register?token=$token").forEach { path ->
        val response = client.get(path)
        assertEquals(HttpStatusCode.OK, response.status, "$path renders")
        assertEquals(CACHE_CONTROL_NO_STORE, response.headers[HttpHeaders.CacheControl], "$path must not be stored")
        assertEquals(ROBOTS_NOINDEX, response.headers[X_ROBOTS_TAG_HEADER], "$path must not be indexed")
        assertEquals(
          REFERRER_POLICY_NO_REFERRER,
          response.headers[REFERRER_POLICY_HEADER],
          "$path must not hand the token on in a Referer",
        )
      }
    }

  @Test
  fun `a signed-in recipient accepts in one click and is linked`() =
    testApplication {
      application {
        testPublicWebModule()
      }
      val token = mintStudentInvite()
      val supporterUser = WebAuthTestSupport.seedUser("parent-${WebAuthTestSupport.uniqueEmail()}", name = "Parent Jane")
      val session = WebAuthTestSupport.login(supporterUser.email.value)

      val response =
        formPost(
          "/invite",
          form = Parameters.build { append("token", token) },
          sessionToken = session,
          client = noFollowClient(),
        )

      assertEquals(HttpStatusCode.SeeOther, response.status)
      assertEquals(
        "/account?notice=invite-accepted",
        response.headers[HttpHeaders.Location],
        "a successful accept lands on the account page with the confirmation",
      )
      assertEquals(1, WebAuthTestSupport.supporterLinkCount(), "exactly one link is created")
      assertEquals(1, WebAuthTestSupport.consumedInviteCount(), "the token is consumed exactly once")

      // A second accept on the same token is a branded 404, not a duplicate link.
      val second =
        formPost("/invite", form = Parameters.build { append("token", token) }, sessionToken = session, client = noFollowClient())
      assertEquals(HttpStatusCode.NotFound, second.status)
      assertEquals(1, WebAuthTestSupport.supporterLinkCount(), "no duplicate link is ever created")
    }

  @Test
  fun `registering with an invite token creates the link and never duplicates`() =
    testApplication {
      application {
        testPublicWebModule()
      }
      val token = mintStudentInvite()

      val response =
        formPost(
          "/register",
          form =
            Parameters.build {
              append("name", "Parent Jane")
              append("email", "jane-${WebAuthTestSupport.uniqueEmail()}")
              append("password", WebAuthTestSupport.DEFAULT_PASSWORD)
              append("token", token)
            },
          client = noFollowClient(),
        )

      assertEquals(HttpStatusCode.SeeOther, response.status)
      assertEquals(
        "/verify-email?notice=invite-accepted",
        response.headers[HttpHeaders.Location],
        "registration lands on the verification hold with the acceptance confirmation",
      )
      assertEquals(1, WebAuthTestSupport.supporterLinkCount(), "exactly one link is created")
      assertEquals(1, WebAuthTestSupport.consumedInviteCount(), "the token is consumed exactly once")
    }

  /**
   * The OTHER signed-out door. A recipient who already has an account signs in
   * with the token carried along, and the link is made — the common case, and
   * the branch nothing in this change used to execute.
   */
  @Test
  fun `signing in with an invite token creates the link`() =
    testApplication {
      application {
        testPublicWebModule()
      }
      val token = mintStudentInvite()
      val parent = WebAuthTestSupport.seedUser("parent-${WebAuthTestSupport.uniqueEmail()}", name = "Parent Jane")

      val response =
        formPost(
          "/sign-in",
          form =
            Parameters.build {
              append("email", parent.email.value)
              append("password", WebAuthTestSupport.DEFAULT_PASSWORD)
              append("token", token)
            },
          client = noFollowClient(),
        )

      assertEquals(HttpStatusCode.SeeOther, response.status)
      assertEquals("/account?notice=invite-accepted", response.headers[HttpHeaders.Location])
      assertEquals(1, WebAuthTestSupport.supporterLinkCount(), "exactly one link is created")
      assertEquals(1, WebAuthTestSupport.consumedInviteCount(), "the token is consumed exactly once")
    }

  /**
   * `SupporterInviteService.accept` returns its refusals as SUCCESSFUL values, so a door that
   * calls `getOrThrow()` without binding the outcome announces a link it never
   * made. Both doors that authenticate are asserted here, because they are the
   * two that had that defect: the family must not be told "You are now
   * connected" over a dead token.
   */
  @Test
  fun `a refused invite is never reported as accepted on either authenticating door`() =
    testApplication {
      application {
        testPublicWebModule()
      }
      val parent = WebAuthTestSupport.seedUser("parent-${WebAuthTestSupport.uniqueEmail()}", name = "Parent Jane")

      val signIn =
        formPost(
          "/sign-in",
          form =
            Parameters.build {
              append("email", parent.email.value)
              append("password", WebAuthTestSupport.DEFAULT_PASSWORD)
              append("token", "never-minted")
            },
          client = noFollowClient(),
        )
      assertEquals(HttpStatusCode.SeeOther, signIn.status)
      assertEquals("/account?notice=invite-not-accepted", signIn.headers[HttpHeaders.Location])

      val register =
        formPost(
          "/register",
          form =
            Parameters.build {
              append("name", "Parent Jo")
              append("email", "jo-${WebAuthTestSupport.uniqueEmail()}")
              append("password", WebAuthTestSupport.DEFAULT_PASSWORD)
              append("token", "never-minted")
            },
          client = noFollowClient(),
        )
      assertEquals(HttpStatusCode.SeeOther, register.status)
      assertEquals("/verify-email?notice=invite-not-accepted", register.headers[HttpHeaders.Location])

      assertEquals(0, WebAuthTestSupport.supporterLinkCount(), "a refused invite links nobody")
    }

  /**
   * A self-addressed invite is a refusal, not a fault: the inviter signs in,
   * lands on their account, and is told the invitation was not accepted —
   * in the SAME words a dead token gets, so the notice is no oracle either.
   */
  @Test
  fun `an inviter signing in with their own invite is refused, not linked`() =
    testApplication {
      application {
        testPublicWebModule()
      }
      val studentUser = WebAuthTestSupport.seedUser("student-${WebAuthTestSupport.uniqueEmail()}", name = "Student Alex")
      WebAuthTestSupport.seedStudent(studentUser)
      val token =
        runBlocking {
          WebAuthTestSupport.supporterInviteService
            .create(studentUser.id, SupporterRelationship.PARENT)
            .getOrThrow()
            .rawToken
        }

      val response =
        formPost(
          "/sign-in",
          form =
            Parameters.build {
              append("email", studentUser.email.value)
              append("password", WebAuthTestSupport.DEFAULT_PASSWORD)
              append("token", token)
            },
          client = noFollowClient(),
        )

      assertEquals(HttpStatusCode.SeeOther, response.status)
      assertEquals("/account?notice=invite-not-accepted", response.headers[HttpHeaders.Location])
      assertEquals(0, WebAuthTestSupport.supporterLinkCount(), "a self-link is never created")
      assertEquals(0, WebAuthTestSupport.consumedInviteCount(), "a refusal must not burn the token")
    }

  /**
   * A SUPPORTER-minted invite opened by someone registering anew: the fresh
   * account has no student profile, so the accept answers
   * `StudentProfileRequired` — the ORDINARY outcome of an adult inviting the
   * student, and the one refusal arm of the fixed `when` that no test executed.
   * The door must report it as a refusal, and must NOT burn the token: the
   * recipient completes onboarding and opens the same link again.
   */
  @Test
  fun `registering with a supporter-minted invite is refused and never burns the token`() =
    testApplication {
      application {
        testPublicWebModule()
      }
      val supporterUser =
        WebAuthTestSupport.seedUser("parent-${WebAuthTestSupport.uniqueEmail()}", name = "Parent Jane")
      val token =
        runBlocking {
          WebAuthTestSupport.supporterInviteService
            .create(supporterUser.id, SupporterRelationship.PARENT)
            .getOrThrow()
            .rawToken
        }

      val response =
        formPost(
          "/register",
          form =
            Parameters.build {
              append("name", "Student Alex")
              append("email", "alex-${WebAuthTestSupport.uniqueEmail()}")
              append("password", WebAuthTestSupport.DEFAULT_PASSWORD)
              append("token", token)
            },
          client = noFollowClient(),
        )

      assertEquals(HttpStatusCode.SeeOther, response.status)
      assertEquals("/verify-email?notice=invite-not-accepted", response.headers[HttpHeaders.Location])
      assertEquals(0, WebAuthTestSupport.supporterLinkCount(), "a recipient with no student profile links nobody")
      assertEquals(0, WebAuthTestSupport.consumedInviteCount(), "a refusal must not burn the token")
    }

  /**
   * A token key carrying NOTHING, and a key carrying TWO credentials, are both
   * PRESENTED invites — so the door says the invitation was not accepted rather
   * than redirecting as if none had been offered. Neither arm was executed by a
   * test, and each differs from the absent case only in the log line.
   */
  @Test
  fun `an unreadable invite token on the sign-in door is reported as not accepted`() =
    testApplication {
      application {
        testPublicWebModule()
      }
      val liveToken = mintStudentInvite()
      val parent = WebAuthTestSupport.seedUser("parent-${WebAuthTestSupport.uniqueEmail()}", name = "Parent Jane")

      val blank =
        formPost(
          "/sign-in",
          form =
            Parameters.build {
              append("email", parent.email.value)
              append("password", WebAuthTestSupport.DEFAULT_PASSWORD)
              append("token", "")
            },
          client = noFollowClient(),
        )
      assertEquals(HttpStatusCode.SeeOther, blank.status)
      assertEquals("/account?notice=invite-not-accepted", blank.headers[HttpHeaders.Location])

      val repeated =
        formPost(
          "/sign-in",
          form =
            Parameters.build {
              append("email", parent.email.value)
              append("password", WebAuthTestSupport.DEFAULT_PASSWORD)
              append("token", liveToken)
              append("token", liveToken)
            },
          client = noFollowClient(),
        )
      assertEquals(HttpStatusCode.SeeOther, repeated.status)
      assertEquals("/account?notice=invite-not-accepted", repeated.headers[HttpHeaders.Location])

      assertEquals(0, WebAuthTestSupport.supporterLinkCount(), "an unreadable token links nobody")
      assertEquals(0, WebAuthTestSupport.consumedInviteCount(), "and burns nothing")
    }
}
