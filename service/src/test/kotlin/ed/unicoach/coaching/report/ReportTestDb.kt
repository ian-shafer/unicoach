package ed.unicoach.coaching.report

import com.typesafe.config.ConfigFactory
import ed.unicoach.coaching.CoachingTestDb
import ed.unicoach.common.config.TOKEN_QUERY_PARAM
import ed.unicoach.db.Database
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.StudentId
import java.net.URI

/**
 * The Family Cost Report tests' own fixture, shared by
 * [CostReportShareServiceTest] and [CostReportChatToolsTest]: the one way this
 * feature builds a service, and the truncation list its two suites need. The
 * connection, session and student plumbing they share with every other
 * student-scoped coaching suite live in [CoachingTestDb], on the
 * [ed.unicoach.coaching.costs.CostsTestDb] precedent.
 *
 * Both suites used to carry their own `@BeforeAll` connection, their own
 * `createStudent`, and a byte-identical `serviceWith`. Two copies of a wiring
 * step is two chances for one suite to test a service the other does not.
 */
object ReportTestDb {
  const val SHARE_URL_BASE = "https://app.unicoach.test/report"

  /** At least [ShareTokenSecret.MIN_LENGTH] characters, because a shorter one is now refused at construction. */
  const val SHARE_TOKEN_SECRET = "test-share-token-secret-long-enough-to-be-a-key"

  val database: Database get() = CoachingTestDb.database

  val sqlSession: SqlSession get() = CoachingTestDb.sqlSession

  /** Truncates every table the share path touches; each suite calls this from `@BeforeEach`. */
  fun reset() {
    CoachingTestDb.truncate("cost_report_shares", "students", "users")
  }

  fun createStudent(label: String = "report"): StudentId = CoachingTestDb.createStudent(label)

  /**
   * A service keyed on [secret]; a null secret is the unconfigured deployment.
   *
   * The deriver is built the way the composition root builds it — from the
   * config's own [ShareTokenSecret] — so a suite cannot hand the service a
   * collaborator production would not have given it.
   */
  fun serviceWith(secret: String? = SHARE_TOKEN_SECRET): CostReportShareService {
    // The VALUES are handed to the config library, never spliced between two
    // quote characters: a secret containing a `"` or a `\` used to yield a
    // document that failed to parse, or parsed to a different secret than the
    // caller asked for — the one shape a rotation test reaches for first.
    // `parseMap` reads its keys as paths, so the block shape is preserved.
    val values =
      buildMap<String, Any> {
        put("costReport.shareUrlBase", SHARE_URL_BASE)
        secret?.let { put("costReport.shareTokenSecret", it) }
      }
    val config = CostReportConfig.from(ConfigFactory.parseMap(values)).getOrThrow()
    return CostReportShareService(database, config, config.secret?.let(::ShareTokenDeriver))
  }

  /**
   * The raw token out of a minted link — the only place it exists.
   *
   * Read through [URI], and REFUSED when the link carries no token: the previous
   * `substringAfter` returned the whole url when the marker was absent, so a
   * change to the link shape would have handed every assertion a string that
   * merely looked like a token instead of failing the test.
   */
  fun tokenOf(url: String): String {
    val query = requireNotNull(URI(url).query) { "a minted link carries its token in a query: [$url]" }
    val token =
      query
        .split("&")
        .firstNotNullOfOrNull { it.substringAfter("$TOKEN_QUERY_PARAM=", "").ifEmpty { null } }
    return requireNotNull(token) { "no [$TOKEN_QUERY_PARAM] parameter in [$url]" }
  }
}
