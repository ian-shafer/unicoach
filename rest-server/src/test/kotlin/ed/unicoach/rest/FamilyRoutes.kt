package ed.unicoach.rest

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.CodebookReferenceFixture
import ed.unicoach.db.models.SharingPermission
import ed.unicoach.rest.models.AcceptSupporterInviteRequest
import ed.unicoach.rest.models.CreateCollegeListEntryRequest
import ed.unicoach.rest.models.CreateSupporterInviteRequest
import ed.unicoach.rest.models.LoginRequest
import ed.unicoach.rest.models.UpdateMoneyProfileRequest
import ed.unicoach.web.common.routing.RegisteredRoute
import ed.unicoach.web.common.routing.parameterName
import ed.unicoach.web.common.routing.spelling
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.server.engine.EmbeddedServer
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The family fixtures the subject-seam suites (RFC 250) share, on the
 * [SupporterRoutingTest] shape: verified accounts made through the doors
 * (E2eSupport's register-and-verify steps), links minted through the INVITE
 * doors (the only way one is minted in production), and raw JDBC for the
 * closures no door makes yet.
 *
 * It owns what those fixtures stand on — a server on a free port, a client and
 * a test-DB connection — so a suite holds one value: [start] it in
 * `@BeforeAll` and [close] it in `@AfterAll`.
 */
class FamilyRoutes private constructor(
  val server: EmbeddedServer<*, *>,
  private val client: HttpClient,
  private val db: Connection,
) : AutoCloseable {
  private val mapper = jacksonObjectMapper()
  private val port = server.boundPort()

  /** A registered, verified account: its address for a fresh login, and one live session. */
  data class Account(
    val email: String,
    val cookie: String,
  )

  /** A student account and a parent account joined by one live link. */
  data class Family(
    val student: Account,
    val parent: Account,
    val linkId: String,
    val studentId: String,
  )

  fun url(path: String) = "http://localhost:$port$path"

  override fun close() {
    server.stop(1000, 5000)
    client.close()
    db.close()
  }

  /**
   * [route]'s path with each parameter filled: a non-id parameter gets a value
   * of its own shape ([NON_ID_PATH_VALUES]), an id named in [ownedIds] gets that
   * real id, and every other id gets [studentId].
   */
  fun fill(
    route: RegisteredRoute,
    studentId: String,
    ownedIds: Map<String, String> = emptyMap(),
  ): String =
    route.segments.joinToString(separator = "/", prefix = "/") { segment ->
      when (val name = segment.parameterName) {
        null -> segment.spelling
        else -> NON_ID_PATH_VALUES[name] ?: ownedIds[name] ?: studentId
      }
    }

  /** A verified account with no student profile and no supporter row. */
  suspend fun registerVerified(name: String): Account {
    val email = "family${UUID.randomUUID()}@company.com"
    val response = registerUser(client, url(""), email, name)
    markEmailVerified(db, email)
    return Account(email, sessionCookie(response))
  }

  /** A verified account WITH a student profile. */
  suspend fun registerStudent(name: String): Account {
    val account = registerVerified(name)
    client
      .post(url("/api/v1/students")) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        header(HttpHeaders.Cookie, account.cookie)
        setBody(mapper.writeValueAsString(createStudentBody()))
      }.assertStatus(HttpStatusCode.Created)
    return account
  }

  /** A NEW session for [account], so a door that ends one session ends only this one. */
  suspend fun login(account: Account): String {
    val response =
      client
        .post(url("/api/v1/auth/login")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          setBody(mapper.writeValueAsString(LoginRequest(account.email, E2E_PASSWORD)))
        }.assertStatus(HttpStatusCode.OK)
    return sessionCookie(response)
  }

  /**
   * A fresh student and parent, linked through the invite doors. The student id
   * is read off the parent's own roster, which is where a parent client gets it.
   */
  suspend fun family(): Family {
    val student = registerStudent("Ava Okafor")
    val parent = registerVerified("Parent Jane")
    val linkId = acceptInvite(parent, mintParentInvite(student))
    return Family(student, parent, linkId, studentIdOnRoster(parent, linkId))
  }

  /** The student mints a parent invite through their own door; the token it carries. */
  private suspend fun mintParentInvite(student: Account): String {
    val body =
      client
        .post(url("/api/v1/supporter-invites")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, student.cookie)
          setBody(mapper.writeValueAsString(CreateSupporterInviteRequest(relationship = "parent")))
        }.assertStatus(HttpStatusCode.Created)
        .bodyAsText()
    val inviteUrl = field(mapper.readTree(body), "inviteUrl", body).asText()
    return assertNotNull(Url(inviteUrl).parameters["token"], "the invite url [$inviteUrl] carries no token")
  }

  /** [parent] accepts [token]; the new link's id. */
  private suspend fun acceptInvite(
    parent: Account,
    token: String,
  ): String {
    val body =
      client
        .post(url("/api/v1/supporter-invites/accept")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, parent.cookie)
          setBody(mapper.writeValueAsString(AcceptSupporterInviteRequest(token)))
        }.assertStatus(HttpStatusCode.OK)
        .bodyAsText()
    return field(mapper.readTree(body), "linkId", body).asText()
  }

  /** The student id on [parent]'s own roster for [linkId], which is where a parent client gets it. */
  private suspend fun studentIdOnRoster(
    parent: Account,
    linkId: String,
  ): String {
    val body = get("/api/v1/supporters/me", parent.cookie).assertStatus(HttpStatusCode.OK).bodyAsText()
    val entry =
      assertNotNull(
        field(mapper.readTree(body), "students", body).singleOrNull { it.get("linkId")?.asText() == linkId },
        "link [$linkId] is not on the parent's roster: [$body]",
      )
    return field(entry, "studentId", body).asText()
  }

  /** A college row the student's own door can add; the codebook rows it foreign-keys into are seeded first. */
  fun seedCollege(name: String): UUID {
    val id = UUID.randomUUID()
    CodebookReferenceFixture.seed(db)
    db
      .prepareStatement(
        """
        INSERT INTO colleges (id, ipeds_unit_id, name, city, state, control)
        VALUES (?, ?, ?, 'Townsville', 'CA', 1)
        """.trimIndent(),
      ).use { stmt ->
        stmt.setObject(1, id)
        stmt.setInt(2, (id.leastSignificantBits and 0x3FFFFFFF).toInt())
        stmt.setString(3, name)
        stmt.executeUpdate()
      }
    return id
  }

  /** The student adds [collegeId] through their own door, with [reasons] in their own words; the new entry's id. */
  suspend fun addToList(
    student: Account,
    collegeId: UUID,
    reasons: String,
  ): String {
    val body =
      client
        .post(url("/api/v1/students/me/college-list")) {
          header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
          header(HttpHeaders.Cookie, student.cookie)
          setBody(mapper.writeValueAsString(CreateCollegeListEntryRequest(collegeId, "applying", reasons, "on_campus")))
        }.assertStatus(HttpStatusCode.Created)
        .bodyAsText()
    return field(field(mapper.readTree(body), "entry", body), "id", body).asText()
  }

  /** The student answers their money profile through their own door, so it exists to be read. */
  suspend fun answerMoneyProfile(student: Account) {
    client
      .put(url("/api/v1/students/me/money-profile")) {
        header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
        header(HttpHeaders.Cookie, student.cookie)
        setBody(mapper.writeValueAsString(UpdateMoneyProfileRequest(incomeBand = "48k_to_75k", residencyState = "CA")))
      }.assertStatus(HttpStatusCode.OK)
  }

  suspend fun get(
    path: String,
    cookie: String?,
  ): HttpResponse = client.get(url(path)) { if (cookie != null) header(HttpHeaders.Cookie, cookie) }

  /** A [method] call on [path] with an empty JSON body, as [cookie]. */
  suspend fun sendEmptyBody(
    method: HttpMethod,
    path: String,
    cookie: String,
  ): HttpResponse =
    client.request(url(path)) {
      this.method = method
      header(HttpHeaders.Cookie, cookie)
      header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
      setBody("{}")
    }

  /** The student ends the link from their own door. */
  suspend fun sever(family: Family) {
    client
      .delete(url("/api/v1/students/me/supporters/${family.linkId}")) { header(HttpHeaders.Cookie, family.student.cookie) }
      .assertStatus(HttpStatusCode.NoContent)
  }

  /** Closes [account]'s `supporters` row, as `family/09` eventually will; no door does yet. */
  fun closeSupporterRow(account: Account) =
    update(
      "UPDATE supporters SET deleted_at = NOW(), version = version + 1 WHERE user_id = (SELECT id FROM users WHERE email = ?)",
      account,
    )

  /** Closes [account]'s `students` row ALONE, leaving the `users` row live. */
  fun closeStudentRow(account: Account) =
    update("UPDATE students SET deleted_at = NOW(), version = version + 1 WHERE user_id = (SELECT id FROM users WHERE email = ?)", account)

  /** Closes [account]'s `users` row ALONE, leaving any `students` or `supporters` row live. */
  fun closeUser(account: Account) = update("UPDATE users SET deleted_at = NOW(), version = version + 1 WHERE email = ?", account)

  private fun update(
    sql: String,
    account: Account,
  ) {
    db.prepareStatement(sql).use { stmt ->
      stmt.setString(1, account.email)
      assertEquals(1, stmt.executeUpdate(), "the closure must hit exactly one row for [${account.email}]: [$sql]")
    }
  }

  /** This response, after asserting its status with the request and the body that explains a miss. */
  private suspend fun HttpResponse.assertStatus(expected: HttpStatusCode): HttpResponse =
    also { assertEquals(expected, status, "[${call.request.method.value} ${call.request.url}] answered: [${bodyAsText()}]") }

  /** [node]'s [name] field, failing with the whole [body] when the response does not carry it. */
  private fun field(
    node: JsonNode,
    name: String,
    body: String,
  ): JsonNode = assertNotNull(node.get(name), "the response carries no [$name]: [$body]")

  companion object {
    /** The path parameters that are not ids, each with a value of its own shape for [fill]. */
    val NON_ID_PATH_VALUES = mapOf("permission" to SharingPermission.INTENDED_SUBJECTS.value)

    /** A server on a free port, a client, and a test-DB connection. */
    fun start(): FamilyRoutes {
      val server = startServer(wait = false, port = 0)
      val dbConfig = DatabaseConfig.from(AppConfig.load("common.conf", "db.conf").getOrThrow()).getOrThrow()
      return FamilyRoutes(
        server,
        HttpClient(CIO),
        DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: ""),
      )
    }
  }
}
