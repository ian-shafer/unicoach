package ed.unicoach.rest

import ed.unicoach.rest.FamilyRoutes.Companion.NON_ID_PATH_VALUES
import ed.unicoach.rest.routing.LINK_ID_PARAM
import ed.unicoach.rest.routing.STUDENT_ID_PARAM
import ed.unicoach.rest.routing.SUBJECT_COLLEGE_LIST_PATH
import ed.unicoach.web.common.routing.PathSegment
import ed.unicoach.web.common.routing.RegisteredRoute
import ed.unicoach.web.common.routing.parameterName
import ed.unicoach.web.common.routing.registeredRoutes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.plugin
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The subject seam's boundary (RFC 250), checked against what the server
 * actually SERVES: the route tree is walked at runtime from `RoutingRoot`, the
 * `CspConformanceTest` precedent, so a route added anywhere is in this sweep
 * with no edit here.
 *
 * Three claims. A route that names a student is a GET. Each such route
 * answers a linked adult and refuses everyone else with one body. And every
 * write route, called by a linked adult with the student's id in every path id
 * and an empty JSON body, refuses the CALLER — 401, 403, 404 or 409 — unless it
 * is named as writing the caller's OWN account or as taking no session. A 400
 * does not count as a refusal: it is answered from the body, before anyone asks
 * who is calling. So a new write that answers anything else, 2xx or 400, fails
 * until it refuses the caller before reading the body or is named, which forces
 * a decision. Every id the family really owns (the student's list entry, the
 * link) is filled with its real value, so a write that skipped its ownership
 * check would find a real row rather than answer 404 for a made-up one. The
 * student's data — every parameterless read under `/students/me`, from the
 * served tree — is then read back unchanged.
 */
class SubjectSeamRouteSweepTest {
  companion object {
    private lateinit var family: FamilyRoutes
    private lateinit var registered: List<RegisteredRoute>

    /**
     * The writes a linked parent may make, because each writes the CALLER's own
     * account and never another person's: auth, becoming a student or a
     * supporter, updating the caller's own student row, the invite doors,
     * subscriptions, and the supporter's own disconnect. Any answer is allowed.
     */
    private val OWN_ACCOUNT_WRITES: Set<Pair<HttpMethod, String>> =
      setOf(
        HttpMethod.Post to "/api/v1/auth/register",
        HttpMethod.Post to "/api/v1/auth/login",
        HttpMethod.Post to "/api/v1/auth/google",
        HttpMethod.Post to "/api/v1/auth/apple",
        HttpMethod.Post to "/api/v1/auth/logout",
        HttpMethod.Post to "/api/v1/auth/verify-email",
        HttpMethod.Post to "/api/v1/auth/resend-verification",
        HttpMethod.Post to "/api/v1/auth/change-email",
        HttpMethod.Post to "/api/v1/students",
        HttpMethod.Patch to "/api/v1/students/me",
        HttpMethod.Post to "/api/v1/supporters",
        HttpMethod.Post to "/api/v1/supporter-invites",
        HttpMethod.Post to "/api/v1/supporter-invites/accept",
        HttpMethod.Post to "/api/v1/subscriptions/verify",
        HttpMethod.Delete to "/api/v1/supporters/me/students/{linkId}",
      )

    /**
     * The answers that refuse the CALLER. A 400 is not one: the sweep's body is
     * `{}`, so a 400 is answered from the body before anyone asks who is calling.
     */
    private val CALLER_REFUSALS =
      setOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden, HttpStatusCode.NotFound, HttpStatusCode.Conflict)

    /** Writes that take no session, so there is no caller to refuse: Apple's notifications are authenticated by their signed body. */
    private val UNAUTHENTICATED_WRITES: Set<Pair<HttpMethod, String>> =
      setOf(HttpMethod.Post to "/api/v1/subscriptions/apple-notifications")

    /**
     * Writes that end the CALLER'S account, named here and never called by the
     * sweep: closing the adult's account ends its links by design (RFC 252), so
     * calling it would change the student's list of connected adults and end the
     * session every later write is made through. `AccountCloseRoutingTest` owns it.
     */
    private val ACCOUNT_ENDING_WRITES: Set<Pair<HttpMethod, String>> =
      setOf(HttpMethod.Delete to "/api/v1/auth/me")

    private val STUDENTS = listOf(PathSegment.Constant("api"), PathSegment.Constant("v1"), PathSegment.Constant("students"))

    /** The segments a route spelling its student the seam's way starts with. */
    private val SEAM_PREFIX = STUDENTS + PathSegment.Parameter(STUDENT_ID_PARAM)

    /** The caller's own student, which a parameterless read under it is a read of. */
    private val OWN_STUDENT = STUDENTS + PathSegment.Constant("me")

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      family = FamilyRoutes.start()
      registered = registeredRoutes(family.server.application.plugin(RoutingRoot) as RoutingNode)
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::family.isInitialized) family.close()
    }
  }

  /**
   * A route names a student when a parameter sits straight after
   * `/api/v1/students/`, whatever it is called, or when it binds the seam's
   * parameter anywhere.
   */
  private val RegisteredRoute.namesAStudent: Boolean
    get() =
      (segments.take(STUDENTS.size) == STUDENTS && segments.getOrNull(STUDENTS.size)?.parameterName != null) ||
        STUDENT_ID_PARAM in parameterNames

  private val subjectRoutes get() = registered.filter { it.namesAStudent }

  private val registeredNames get() = registered.map { it.named }

  /** Every parameterless read of the caller's own student, from the served tree: what a linked adult's write could have changed. */
  private val studentReads get() =
    registered
      .filter { it.method == HttpMethod.Get && it.parameterNames.isEmpty() && it.segments.take(OWN_STUDENT.size) == OWN_STUDENT }
      .map { it.path }

  /** Every path parameter name a served route binds. */
  private val servedParameters get() = registered.flatMap { it.parameterNames }.toSet()

  @Test
  fun `every route that names a student spells it the seam's way`() {
    assertEquals(
      emptyList(),
      subjectRoutes.filterNot { it.segments.take(SEAM_PREFIX.size) == SEAM_PREFIX },
      "a student route under another parameter name escapes the seam's checks",
    )
  }

  @Test
  fun `every route that names a student is a GET`() {
    assertTrue(
      (HttpMethod.Get to SUBJECT_COLLEGE_LIST_PATH) in registeredNames,
      "the walk must find the seam's own route, or it is not reading the tree: $registered",
    )
    assertEquals(emptyList(), subjectRoutes.filter { it.method != HttpMethod.Get }, "a write that takes a subject")
  }

  @Test
  fun `every route that names a student answers a linked adult and refuses an unlinked or severed one identically`() =
    runBlocking {
      for (route in subjectRoutes) {
        val linked = family.family()
        val path = family.fill(route, linked.studentId)

        assertEquals(HttpStatusCode.OK, family.get(path, linked.parent.cookie).status, "[$route] for a linked adult")

        val unlinked = family.get(path, family.family().parent.cookie)
        family.sever(linked)
        val severed = family.get(path, linked.parent.cookie)

        assertEquals(HttpStatusCode.NotFound, unlinked.status, "[$route] for an unlinked adult")
        assertEquals(HttpStatusCode.NotFound, severed.status, "[$route] for a severed adult")
        assertEquals(unlinked.bodyAsText(), severed.bodyAsText(), "[$route] refuses with one body")
      }
    }

  /** Everything the student owns that a linked adult's write could reach, read by the student, status and body. */
  private suspend fun studentData(student: FamilyRoutes.Account): List<String> =
    studentReads.map { path ->
      val response = family.get(path, student.cookie)
      assertEquals(HttpStatusCode.OK, response.status, "[$path] must answer the student, or comparing it proves nothing")
      "$path ${response.status.value} ${response.bodyAsText()}"
    }

  @Test
  fun `no write route lets a linked adult change the student's data`() =
    runBlocking {
      assertEquals(
        emptyList(),
        (OWN_ACCOUNT_WRITES + UNAUTHENTICATED_WRITES + ACCOUNT_ENDING_WRITES).filterNot { it in registeredNames },
        "a named write the server does not register: the list has gone stale",
      )

      val linked = family.family()
      val entryId = family.addToList(linked.student, family.seedCollege("Sweep College"), "My own reasons")
      // So every one of the student's reads has something to answer with.
      family.answerMoneyProfile(linked.student)
      // "entryId": the college-list entry routes' path parameter (CollegeListRoutes).
      val ownedIds = mapOf("entryId" to entryId, LINK_ID_PARAM to linked.linkId)
      assertEquals(
        emptySet(),
        NON_ID_PATH_VALUES.keys + ownedIds.keys - servedParameters,
        "a filled path parameter no served route names: the fill has gone stale",
      )
      val before = studentData(linked.student)

      val answers = sweepWrites(linked, ownedIds)
      val after = studentData(linked.student)

      // Both reported together: a write that answered 2xx AND changed the
      // student's data must not hide the change behind the status failure.
      assertAll(
        {
          assertEquals(
            emptyMap(),
            answers.filter { (route, status) ->
              status !in CALLER_REFUSALS && route.named !in OWN_ACCOUNT_WRITES && route.named !in UNAUTHENTICATED_WRITES
            },
            "a write route did not refuse a linked adult as a caller (401, 403, 404 or 409): refuse the caller before " +
              "reading the body, or name the route in OWN_ACCOUNT_WRITES, UNAUTHENTICATED_WRITES or " +
              "ACCOUNT_ENDING_WRITES with its reason",
          )
        },
        { assertEquals(before, after, "a linked adult's writes changed the student's data") },
      )
    }

  /** Each write route's status when [linked]'s parent calls it with an empty body, on a fresh session per call. */
  private suspend fun sweepWrites(
    linked: FamilyRoutes.Family,
    ownedIds: Map<String, String>,
  ): Map<RegisteredRoute, HttpStatusCode> =
    registered.filter { it.method != HttpMethod.Get && it.named !in ACCOUNT_ENDING_WRITES }.associateWith { route ->
      // The adult's own-account writes keep made-up ids: with the real link
      // id, the adult's own disconnect would end the link every later write
      // is meant to be made through.
      val ids = if (route.named in OWN_ACCOUNT_WRITES) emptyMap() else ownedIds
      // A fresh session per call, so logging out ends only that one.
      family.sendEmptyBody(route.method, family.fill(route, linked.studentId, ids), family.login(linked.parent)).status
    }
}
