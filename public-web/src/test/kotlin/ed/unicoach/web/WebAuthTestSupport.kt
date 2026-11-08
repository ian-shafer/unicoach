package ed.unicoach.web

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigValueFactory
import ed.unicoach.auth.AuthService
import ed.unicoach.auth.AuthenticatedSession
import ed.unicoach.auth.EmailVerificationConfig
import ed.unicoach.auth.EmailVerificationService
import ed.unicoach.auth.LoginResult
import ed.unicoach.auth.PasswordResetConfig
import ed.unicoach.auth.PasswordResetService
import ed.unicoach.auth.SessionMetadataService
import ed.unicoach.auth.SessionMinter
import ed.unicoach.chat.ChatProvider
import ed.unicoach.chat.LogOnlyChatProvider
import ed.unicoach.coaching.CoachingConfig
import ed.unicoach.coaching.CoachingService
import ed.unicoach.coaching.LlmCallLog
import ed.unicoach.coaching.budget.BudgetConfig
import ed.unicoach.coaching.budget.BudgetService
import ed.unicoach.coaching.collegelist.CollegeListService
import ed.unicoach.coaching.extraction.ExtractionConfig
import ed.unicoach.coaching.extraction.ExtractionScheduler
import ed.unicoach.coaching.moneyprofile.MoneyProfileService
import ed.unicoach.college.CollegeSearchService
import ed.unicoach.common.config.AppConfig
import ed.unicoach.common.models.EmailAddress
import ed.unicoach.common.models.ValidationResult
import ed.unicoach.common.money.Nanodollars
import ed.unicoach.common.util.UsDate
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.LlmCallRowFixture
import ed.unicoach.db.dao.RawSql
import ed.unicoach.db.dao.StudentRowFixture
import ed.unicoach.db.dao.SubscriptionsDao
import ed.unicoach.db.dao.UsersDao
import ed.unicoach.db.models.NewUser
import ed.unicoach.db.models.PasswordHash
import ed.unicoach.db.models.PersonName
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.models.SubscriptionStatus
import ed.unicoach.db.models.TokenHash
import ed.unicoach.db.models.User
import ed.unicoach.db.models.UserId
import ed.unicoach.family.LinkEndedNotice
import ed.unicoach.family.LinkRequestedNotice
import ed.unicoach.family.SupporterInviteConfig
import ed.unicoach.family.SupporterInviteService
import ed.unicoach.family.SupporterLinkRequestService
import ed.unicoach.family.SupporterService
import ed.unicoach.queue.QueueService
import ed.unicoach.student.AccountRoleService
import ed.unicoach.student.StudentService
import ed.unicoach.subscriptions.SubscriptionPlans
import ed.unicoach.util.Argon2Hasher
import ed.unicoach.util.TokenGenerator
import ed.unicoach.vocabulary.VocabularyService
import ed.unicoach.web.auth.CsrfToken
import ed.unicoach.web.render.CSRF_FIELD
import ed.unicoach.web.render.SsoButtons
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.http.parseServerSetCookieHeader
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.runBlocking
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Shared scaffolding for `public-web`'s RFC 224 suites: the real test database,
 * a real [AuthService], and the REAL parsed configuration.
 *
 * "Real config" is load-bearing rather than tidiness. The slice's headline claim
 * is that a session minted by `rest-server` and a session minted by the web are
 * the same row behind the same cookie, and that claim is made by CONFIGURATION —
 * the shared `session.conf` block — not by any Kotlin type. A suite that invented
 * its own cookie name and its own origin would pass while the packaged config
 * said something else entirely.
 *
 * The `AuthService` is the production one, wired exactly as `Application.kt`
 * wires it, so these suites exercise the real login, registration, logout and
 * change-email paths rather than a fake that agrees with the test.
 */
object WebAuthTestSupport {
  /** The SERVER's own list, splatted — not a copy of it. See [PUBLIC_WEB_CONF_RESOURCES]. */
  val config = AppConfig.load(*PUBLIC_WEB_CONF_RESOURCES).getOrThrow()

  private val dbConfig = DatabaseConfig.from(config).getOrThrow()

  val publicWebConfig: PublicWebConfig = PublicWebConfig.from(config).getOrThrow()

  /** The one origin the same-origin guard compares against; the value the server really reads. */
  val urlBase: String get() = publicWebConfig.origin.url

  val sessionConfig get() = publicWebConfig.session

  val database = Database(dbConfig)

  private val argon2Hasher = Argon2Hasher()
  private val tokenGenerator = TokenGenerator()

  val emailVerificationService =
    EmailVerificationService(
      database,
      QueueService(database),
      tokenGenerator,
      EmailVerificationConfig.from(config).getOrThrow(),
    )

  /**
   * The two provider blocks, read as the server reads them (RFC 275). The test
   * environment selects the `stub` verifier for both (`.env.dev`'s
   * `GOOGLE_AUTH_PROVIDER`/`APPLE_AUTH_PROVIDER`), so a suite signs in with a
   * `stub:sub=…;email=…` token through the real `loginWithSso`.
   */
  internal val signInProviders: WebSignInProviders = WebSignInProviders.read(config)

  /**
   * The buttons the test configuration turns on — none, unless the environment
   * sets a web ID. A suite about the buttons passes its own [SsoButtons] with
   * placeholder IDs; one about their absence passes [SsoButtons.NONE].
   */
  val ssoButtons: SsoButtons get() = signInProviders.buttons(publicWebConfig.origin)

  /** Buttons for both providers, with placeholder web IDs: what a suite about the provider doors renders. */
  val bothSsoButtons: SsoButtons
    get() = SsoButtons.of(TEST_GOOGLE_WEB_CLIENT_ID, TEST_APPLE_SERVICES_ID, publicWebConfig.origin)

  const val TEST_GOOGLE_WEB_CLIENT_ID = "test-web-client.apps.googleusercontent.com"

  const val TEST_APPLE_SERVICES_ID = "coach.uni.web.test"

  val authService =
    AuthService(
      database,
      argon2Hasher,
      tokenGenerator,
      emailVerificationService,
      signInProviders.googleVerifier,
      signInProviders.appleVerifier,
      // A lambda, not a bound reference: the request service is declared below.
      { session, userId -> linkRequestService.releaseOnVerified(session, userId) },
    )

  /** The production reset service, wired as `Application.kt` wires it. */
  val passwordResetService =
    PasswordResetService(
      database,
      QueueService(database),
      tokenGenerator,
      argon2Hasher,
      SessionMinter(tokenGenerator),
      PasswordResetConfig.from(config).getOrThrow(),
    ) { session, userId -> linkRequestService.releaseOnVerified(session, userId) }

  val supporterService = SupporterService(database, LinkEndedNotice(QueueService(database)))

  val supporterInviteService =
    SupporterInviteService(
      database,
      SupporterInviteConfig.from(config).getOrThrow(),
      TokenGenerator(),
      supporterService,
    )

  /** The production request service (RFC 254), wired as `Application.kt` wires it. */
  val linkRequestService = SupporterLinkRequestService(database, supporterService, LinkRequestedNotice(QueueService(database)))

  /** The production metadata writer the draft rail writes through (RFC 265). */
  val sessionMetadataService = SessionMetadataService(database)

  /**
   * The production metadata writer over a database that has gone away: a real
   * pool on the real configuration, closed, so every write fails the way an
   * outage fails it rather than the way a fake chooses to.
   */
  fun unreachableSessionMetadataService(): SessionMetadataService = SessionMetadataService(Database(dbConfig).also(Database::close))

  /**
   * A real pool on the real configuration whose every transaction is
   * read-only (`default_transaction_read_only`): reads succeed and every write
   * fails the way Postgres refuses it, so a page can render around a write that
   * did not happen (RFC 270). The caller closes it: every checkout shares the
   * cluster's connection budget.
   */
  fun readOnlyDatabase(): Database {
    val url = "${dbConfig.jdbcUrl}?options=-c%20default_transaction_read_only%3Don"
    return Database(DatabaseConfig.from(config.withValue("database.jdbcUrl", ConfigValueFactory.fromAnyRef(url))).getOrThrow())
  }

  /** The production student service onboarding creates students through (RFC 268). */
  val studentService = StudentService(database)

  /** The production role read the onboarding wall and the chat's student gate share (RFC 268). */
  val accountRoleService = AccountRoleService(database)

  /** The production money-profile writer the student form's optional answers go through (RFC 268). */
  val moneyProfileService = MoneyProfileService(database)

  /** The production vocabularies the student form's state and income options come from (RFC 268). */
  val vocabularyService = VocabularyService(database)

  /** The production college list My colleges reads and writes (RFC 271). */
  val collegeListService = CollegeListService(database)

  /** The production name search My colleges adds from (RFC 271). */
  val collegeSearchService = CollegeSearchService(database)

  /** The packaged plan box (RFC 110), the one the budget read prices a subscription period from. */
  val subscriptionPlans: SubscriptionPlans = SubscriptionPlans.from(config).getOrThrow()

  /**
   * The budget read (RFC 273 §4), wired as `Application.kt` wires it — the
   * packaged budget config and plans over the test database. The coach's gate
   * and the Settings meter read ONE of these in production, so a suite that
   * turns the knob builds one here and hands it to both.
   *
   * [freeAllowanceUsd] is the one budget knob a suite turns, and `0.00` is the
   * exhausted student.
   */
  fun budgetService(freeAllowanceUsd: String? = null): BudgetService {
    val budgetConfig =
      BudgetConfig
        .from(
          freeAllowanceUsd
            ?.let { ConfigFactory.parseString("budget.freeAllowanceUsd = $it").withFallback(config) }
            ?: config,
        ).getOrThrow()
    return BudgetService(database, budgetConfig, subscriptionPlans)
  }

  /** The budget read every suite gets unless it is about the allowance: the packaged allowance. */
  val defaultBudgetService: BudgetService by lazy { budgetService() }

  /**
   * The budget read over a database that has gone away: a real pool on the
   * real configuration, closed, so the read fails the way an outage fails it
   * — what Settings renders around (RFC 273 §2).
   */
  fun unreachableBudgetService(): BudgetService =
    BudgetService(Database(dbConfig).also(Database::close), BudgetConfig.from(config).getOrThrow(), subscriptionPlans)

  /**
   * The student's coach (RFC 262), wired as `Application.kt` wires it — the
   * packaged coaching and extraction config, the real call log and the real
   * extraction scheduler — over [provider] instead of a network one, gated by
   * [budgetService].
   *
   * A suite about a specific reply passes a provider over a
   * `ScriptedAnthropicTransport`; a suite about the allowance passes the
   * [WebAuthTestSupport.budgetService] it also hands the module.
   */
  fun coachingService(
    provider: ChatProvider = LogOnlyChatProvider(),
    budgetService: BudgetService = defaultBudgetService,
  ): CoachingService =
    CoachingService(
      database,
      LlmCallLog(provider, database),
      CoachingConfig.from(config).getOrThrow(),
      budgetService,
      ExtractionScheduler(QueueService(database), ExtractionConfig.from(config).getOrThrow()),
    )

  /** The coach every suite gets unless it is about the coach: the log-only provider. */
  val defaultCoachingService: CoachingService by lazy { coachingService() }

  /** The one plan the packaged config prices: what a seeded subscription is for. */
  const val SUBSCRIBED_PRODUCT_ID = "coach.uni.UnicoachiOS.monthly10"

  /**
   * What a period of [productId] buys, read from the packaged plans — so a
   * suite that spends "the whole period" spends the number the gate meters
   * against, not a figure typed beside it.
   */
  fun periodBudget(productId: String = SUBSCRIBED_PRODUCT_ID): Nanodollars =
    requireNotNull(subscriptionPlans.periodBudget(productId)) { "the packaged plans price no [$productId]" }

  /** A period end to seed, and the reset text a page must render for it. */
  class SeededPeriodEnd(
    val instant: Instant,
    val resetDate: String,
  )

  /**
   * A period end 20 days out, at 03:00Z. At that hour New York is still on the
   * previous calendar day (22:00 EST or 23:00 EDT, year round), so a page or
   * notice rendering the UTC date fails at any hour of any day — and the row
   * stays current, which a fixed future instant would stop being one day. The
   * expected text is the previous day through `UsDate`, with no production
   * zone call in the test: `COACH_ZONE` is the thing under test (RFC 273 §3),
   * not a tool for stating the answer.
   */
  fun seededPeriodEnd(): SeededPeriodEnd {
    val utcDate = LocalDate.now(ZoneOffset.UTC).plusDays(20)
    return SeededPeriodEnd(
      instant = utcDate.atTime(3, 0).toInstant(ZoneOffset.UTC),
      resetDate = UsDate.of(utcDate.minusDays(1)),
    )
  }

  /**
   * A current, entitling `subscriptions` row for [userId]'s ACCOUNT (RFC 273):
   * [productId] over a period that covers now, so the budget read takes the
   * subscription branch. [periodEnd] is the reset point Settings renders.
   */
  fun seedSubscription(
    userId: UserId,
    periodEnd: Instant = Instant.now().plus(20, ChronoUnit.DAYS),
    productId: String = SUBSCRIBED_PRODUCT_ID,
  ) {
    runBlocking {
      database.withConnection { session ->
        SubscriptionsDao
          .upsert(
            session,
            userId,
            originalTransactionId = "web-${userId.value}",
            productId = productId,
            status = SubscriptionStatus.ACTIVE,
            periodStart = Instant.now().minus(10, ChronoUnit.DAYS),
            periodEnd = periodEnd,
          ).getOrThrow()
      }
    }
  }

  /**
   * One completed, costed coaching call of [cost] attributed to [studentId]
   * through a synthesis run (the cheapest single-call owner), timestamped now —
   * inside any current subscription period. The ledger `BudgetService` meters
   * (`account_llm_cost`) re-keys it onto the student's account.
   */
  fun seedSpend(
    studentId: StudentId,
    cost: Nanodollars,
  ) {
    withConnection { connection ->
      val requestId = LlmCallRowFixture.insert(connection, cost.value)
      // Unique per row: `system_prompts` is the migration-seeded catalog and is
      // never truncated, so a fixed (name, version) would collide on the second test.
      val promptId = UUID.randomUUID()
      RawSql.execute(
        connection,
        "INSERT INTO system_prompts (id, name, version, body) VALUES (?, 'extraction', ?, 'distill')",
        promptId,
        "v-$promptId",
      )
      RawSql.execute(
        connection,
        "INSERT INTO synthesis_runs (student_id, outcome, system_prompt_id, llm_request_id) VALUES (?, 'applied', ?, ?)",
        studentId.value,
        promptId,
        requestId,
      )
    }
  }

  /** Truncates users (cascading to sessions) and the job queue, for an isolated test. */
  fun resetDatabase() {
    withConnection { connection -> RawSql.execute(connection, "TRUNCATE TABLE users, jobs CASCADE") }
  }

  fun uniqueEmail(): String = "web-auth-${UUID.randomUUID()}@example.com"

  /**
   * Seeds a `students` row for [user] through RFC 209's ONE fixture, so the
   * decomposed graduation columns and the RFC 222 date of birth are stated where
   * they are owned. The owning user must already exist.
   *
   * A hand-written INSERT here was the 34th copy of a statement RFC 209 exists
   * to have exactly one of; the dependency it avoided is one line that
   * `:admin-web`, `:rest-server`, `:service`, `:college` and `:db` have each
   * already paid.
   */
  fun seedStudent(user: User): StudentId {
    val studentId = UUID.randomUUID()
    withConnection { connection ->
      StudentRowFixture.insert(connection, studentId, user.id.value)
    }
    return StudentId(studentId)
  }

  /**
   * A verified user who has onboarded as a student (RFC 268): what a suite
   * about a page behind the onboarding wall — `/account`, `/chat` — signs in
   * as, when the role is not what it is about.
   */
  fun seedStudentUser(email: String): User = seedUser(email).also(::seedStudent)

  /**
   * Seeds a user directly through the DAO.
   *
   * [verified] defaults to true because most suites are about something other
   * than verification; the gate suite passes false and asserts the hold screen.
   */
  fun seedUser(
    email: String,
    password: String = DEFAULT_PASSWORD,
    name: String = "Web Test User",
    verified: Boolean = true,
  ): User {
    val hash = runBlocking { argon2Hasher.hash(password) }
    val created = insertUser(email, name, (PasswordHash.create(hash) as ValidationResult.Valid).value)

    if (!verified) return created

    // Verified through SQL rather than a DAO because nothing in the tree marks
    // a user verified except consuming a token, and seeding a token to burn it
    // would make every suite depend on the verification flow it is not testing.
    execute("UPDATE users SET email_verified_at = NOW(), version = version + 1 WHERE id = ?::uuid") { statement ->
      statement.setString(1, created.id.value.toString())
    }
    return created.copy(emailVerifiedAt = Instant.now())
  }

  /**
   * The one `NewUser` construction both seed helpers use.
   *
   * Private and shared because the DAO call and the three `ValidationResult`
   * casts are the part that changes when `NewUser` gains a required field, and
   * two copies meant two edits — the second of which is easy to miss, because
   * the suite that needs it runs one assertion.
   */
  private fun insertUser(
    email: String,
    name: String,
    passwordHash: PasswordHash?,
  ): User =
    runBlocking {
      database
        .withConnection { session ->
          UsersDao.create(
            session,
            NewUser(
              email = (EmailAddress.create(email) as ValidationResult.Valid).value,
              name = (PersonName.create(name) as ValidationResult.Valid).value,
              displayName = null,
              passwordHash = passwordHash,
            ),
          )
        }.getOrThrow()
    }

  /**
   * A user with NO password hash — an SSO-only account.
   *
   * It exists for one assertion: `LoginResult.PasswordNotSet` is one of the four
   * failure branches the sign-in form must not distinguish, and it is the only
   * one that cannot be produced by typing something wrong.
   */
  fun seedPasswordlessUser(
    email: String,
    name: String = "SSO Only User",
  ): User = insertUser(email, name, passwordHash = null)

  /**
   * A session minted the way `rest-server` mints one: the same [AuthService],
   * through the same `login`. Used by the session-sharing test, which is the
   * slice's headline claim — the cookie this returns must resolve on a web page.
   */
  fun login(
    email: String,
    password: String = DEFAULT_PASSWORD,
  ): String =
    runBlocking {
      val result =
        authService
          .login(
            email = email,
            password = password,
            oldCookieToken = null,
            sessionExpiration = sessionConfig.expiration,
            userAgent = "test",
            initialIp = "127.0.0.1",
          ).getOrThrow()
      (result as LoginResult.Success).token
    }

  /** The `Cookie` request header carrying [token] under the configured cookie name. */
  fun cookieHeader(token: String): String = "${sessionConfig.cookieName}=$token"

  /**
   * The raw session token out of a response's `Set-Cookie` header — exactly the
   * value the browser will send back on the next request.
   *
   * Parsed from the rendered header rather than taken from any server-side
   * variable, because the claim being tested is about what the BROWSER was
   * handed. Ktor's own `Set-Cookie` parser is used rather than splitting on `;`,
   * because the value's encoding is part of the header's grammar: a hand-split
   * returns the ENCODED text and the decoding bug then reports itself as a
   * session that does not resolve.
   */
  fun tokenFromSetCookie(setCookie: String): String {
    val cookie = parseServerSetCookieHeader(setCookie)
    check(cookie.name == sessionConfig.cookieName) {
      "the response set no [${sessionConfig.cookieName}] cookie; got [$setCookie]"
    }
    return cookie.value
  }

  /**
   * [token] resolved through the production [AuthService] — the SAME call the
   * iOS app's requests make through `rest-server`'s gate, hashed the way
   * `resolveWebCaller` hashes it.
   */
  fun resolveSession(token: String): AuthenticatedSession? =
    runBlocking {
      authService.resolveSession(TokenHash.fromRawToken(token)).getOrThrow()
    }

  /**
   * The CSRF token the session behind [token] carries, as its pages render it
   * (RFC 265), or `null` when [token] resolves no session — the value a page's
   * `_csrf` field would hold, read from the row rather than scraped from a page,
   * so a suite about a route does not depend on another route rendering.
   */
  fun csrfTokenFor(token: String): String? = resolveSession(token)?.let { CsrfToken.of(it.session.metadata)?.value }

  /**
   * The stored `sessions.metadata` of every session [userId] holds, as the JSON
   * text in the column — what a "nothing about the attempt was kept" claim is
   * about (RFC 268), read from the row rather than through the proto.
   */
  fun storedSessionMetadata(userId: UUID): List<String> =
    withConnection { connection ->
      RawSql.list(connection, "SELECT coalesce(metadata::text, '') FROM sessions WHERE user_id = ?", userId) { it.getString(1) }
    }

  /** The stored name of the one user at [email] — what a provider sign-in named the account (RFC 275). */
  fun storedUserName(email: String): String =
    withConnection { connection -> RawSql.single(connection, "SELECT name FROM users WHERE email = ?", email) { it.getString(1) } }

  /** The `login_method` of each live session [userId] holds, oldest first (RFC 275). */
  fun liveLoginMethods(userId: UUID): List<String> =
    withConnection { connection ->
      RawSql.list(
        connection,
        "SELECT login_method FROM sessions WHERE user_id = ? AND is_revoked = false ORDER BY created_at",
        userId,
      ) { it.getString(1) }
    }

  /** How many `sessions` rows exist at all — the D12 honesty test's whole assertion. */
  fun sessionRowCount(): Int = count("SELECT count(*) FROM sessions")

  /** How many LIVE sessions the given user has. */
  fun liveSessionCount(userId: UUID): Int =
    count(
      "SELECT count(*) FROM sessions WHERE user_id = '$userId'::uuid AND is_revoked = false AND expires_at > NOW()",
    )

  /**
   * How many whole seconds from now the single live session row expires.
   *
   * Read as a NUMBER rather than compared to a literal timestamp, because the
   * assertion is that the row and the cookie's `Max-Age` agree — and the cookie
   * can only say a number of seconds.
   */
  fun soleSessionLifetimeSeconds(): Long =
    soleLong(
      "SELECT extract(epoch FROM (expires_at - NOW()))::bigint FROM sessions WHERE is_revoked = false",
    )

  /** How many queued jobs of [type] exist — registration must enqueue exactly one SEND_EMAIL. */
  fun jobCount(type: String): Int = count("SELECT count(*) FROM jobs WHERE job_type = '$type'")

  /** How many supporter-student links exist at all (RFC 232 routing tests). */
  fun supporterLinkCount(): Int = count("SELECT count(*) FROM supporter_student_links")

  /** How many supporter invites have been consumed (RFC 232 routing tests). */
  fun consumedInviteCount(): Int = count("SELECT count(*) FROM supporter_invites WHERE consumed_at IS NOT NULL")

  /**
   * Drives the invite behind [rawToken] into a DEAD state, by its hash.
   *
   * RFC 232 promises ONE refusal body for six states, and three of them —
   * expired, consumed, revoked — cannot be reached through any door the suite
   * can drive: expiry is 14 days away, and nothing in the product revokes. So
   * the suite writes the state and then asks the route, which is the only way
   * the uniformity claim can be tested at all.
   *
   * By hash, never by id, because the hash is the only handle a caller holding
   * the raw token has — the same lookup the route itself makes.
   */
  fun expireInvite(rawToken: String) = setInviteState(rawToken, "expires_at = NOW() - INTERVAL '1 day'")

  fun revokeInvite(rawToken: String) = setInviteState(rawToken, "revoked_at = NOW()")

  fun consumeInvite(rawToken: String) = setInviteState(rawToken, "consumed_at = NOW()")

  private fun setInviteState(
    rawToken: String,
    assignment: String,
  ) {
    execute("UPDATE supporter_invites SET $assignment WHERE token_hash = ?") { statement ->
      statement.setBytes(1, TokenHash.fromRawToken(rawToken).value)
    }
  }

  const val DEFAULT_PASSWORD = "Password123!"

  /**
   * The ONE way this fixture reaches the test database.
   *
   * The connect/credential dance was written out five times in this file, so a
   * change to how the suite reaches the DB was five edits. Only the SQL differs
   * between the callers, so only the SQL is theirs.
   */
  private fun <T> withConnection(block: (Connection) -> T): T =
    DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "").use(block)

  /** The single `Long` column of the one row [sql] must return. */
  private fun soleLong(sql: String): Long = soleRow(sql) { it.getLong(1) }

  /**
   * The one column of the one row [sql] must return; fails loudly on none or
   * several. [read] must EXTRACT a value — the cursor it is handed is closed
   * when this returns, so a lambda that returns the `ResultSet` returns a
   * corpse.
   */
  private fun <T> soleRow(
    sql: String,
    read: (ResultSet) -> T,
  ): T = withConnection { connection -> RawSql.single(connection, sql, read = read) }

  /** How many rows [sql] counts: a `SELECT count(*)`, the one row it always returns. */
  fun count(sql: String): Int = soleLong(sql).toInt()

  private fun execute(
    sql: String,
    bind: (PreparedStatement) -> Unit,
  ) = withConnection { connection ->
    connection.prepareStatement(sql).use { statement ->
      bind(statement)
      statement.executeUpdate()
    }
  }
}

/**
 * A same-origin form POST: the shape every state-changing request in these
 * suites has.
 *
 * The `Origin` header is PART of that shape, not an extra: the same-origin guard
 * refuses a request without one, so a call site that forgets it is testing the
 * guard rather than the route it named. Five suites each hand-rolled this, and
 * one of them grew the header by hand at nine separate call sites — which is
 * what a missing helper costs the next time the request shape changes.
 *
 * [form] is `Parameters` rather than a pre-encoded string: encoding
 * `application/x-www-form-urlencoded` by hand escapes only the characters
 * whoever wrote the call site happened to type, so a value carrying `&`, `+` or
 * a non-ASCII letter posts something other than what the test names.
 * [FormDataContent] is Ktor's encoder and sets the content type itself.
 *
 * `SameOriginGuardTest` deliberately does NOT use it: that suite varies the
 * headers this one fixes.
 *
 * The session's CSRF token is part of that shape too (RFC 265): every
 * signed-in form a page renders echoes it, so a POST carrying [sessionToken]
 * carries that session's token in `_csrf` unless the call says otherwise.
 *
 * @param client the client to post with — a suite asserting WHERE a caller was
 *   sent passes [noFollowClient].
 * @param csrf the `_csrf` value to send, or `null` to send none. Defaults to
 *   the token of the session behind [sessionToken]; the CSRF suite passes an
 *   absent or wrong one.
 * @param extra anything this one request adds, such as a `User-Agent`.
 */
suspend fun ApplicationTestBuilder.formPost(
  path: String,
  form: Parameters = Parameters.Empty,
  sessionToken: String? = null,
  client: HttpClient = this.client,
  csrf: String? = sessionToken?.let(WebAuthTestSupport::csrfTokenFor),
  extra: HttpRequestBuilder.() -> Unit = {},
): HttpResponse =
  client.post(path) {
    header(HttpHeaders.Origin, WebAuthTestSupport.urlBase)
    sessionToken?.let { header(HttpHeaders.Cookie, WebAuthTestSupport.cookieHeader(it)) }
    val body =
      Parameters.build {
        appendAll(form)
        csrf?.let { append(CSRF_FIELD, it) }
      }
    setBody(FormDataContent(body))
    extra()
  }

/**
 * A client that does NOT follow redirects.
 *
 * `testApplication`'s default client follows a `GET` redirect, so an assertion
 * written against `client.get("/account")` sees the `200` of the sign-in form it
 * was sent to and cannot tell that from the account page rendering for a
 * signed-out visitor — the exact failure the gate exists to prevent, reported as
 * a pass. Every suite that asserts WHERE a caller was sent uses this.
 */
fun ApplicationTestBuilder.noFollowClient() = createClient { followRedirects = false }
