package ed.unicoach.college

import ed.unicoach.common.config.AppConfig
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.CodebookReferenceFixture
import ed.unicoach.db.dao.SqlSession
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import java.io.File

/**
 * Shared DB-test scaffolding for the college ingest suites: opens one pooled
 * [Database] for the class, truncates the two Scorecard tables and the two RFC
 * 144 IPEDS tables before each test, and offers the fixture/session/count helpers both suites need. Concrete
 * suites supply only their fixtures and assertions.
 */
abstract class CollegeScorecardTestBase {
  companion object {
    private lateinit var database: Database

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config = AppConfig.load("common.conf", "db.conf").getOrThrow()
      val dbConfig = DatabaseConfig.from(config).getOrThrow()
      database = Database(dbConfig)
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::database.isInitialized) database.close()
    }
  }

  protected val database: Database get() = Companion.database

  protected fun fixture(name: String): File {
    val url = requireNotNull(this::class.java.classLoader.getResource(name)) { "missing fixture [$name]" }
    return File(url.toURI())
  }

  /**
   * Whether [resetDatabase] puts the `us_states` / `nces_locales` /
   * `ipeds_regions` rows back after truncating them, for the suites that insert
   * a college WITHOUT running a `codebooks` phase — which is all of them but
   * one, and the reason this defaults to true rather than being pasted into six
   * `@BeforeEach` hooks.
   *
   * `CodebookLoaderTest` overrides it to false: it asserts that the committed
   * codebook loads as 59 `us_states` INSERTS on a first load, so a base class
   * that pre-seeded 51 of them would break the one suite whose subject is the
   * loader. It seeds explicitly in the one test that needs the rows.
   */
  protected open val seedsCodebookReference: Boolean get() = true

  @BeforeEach
  fun resetDatabase() =
    runBlocking {
      database.withConnection { session ->
        session
          .prepareStatement(
            // The RFC 147 codebook tables are truncated here too: they are
            // reference data every suite in this hierarchy can load, and a
            // leftover domain would make the next test's per-domain counts and
            // unknown-code report read the previous test's rows. Order matters
            // to nothing (CASCADE), but us_states' FK onto ipeds_regions means
            // they must go in ONE statement, as they do.
            // `college_search_index` is not named: it is ON DELETE CASCADE from
            // `colleges`, so truncating colleges takes it. `subjects` IS named
            // — nothing cascades to it, and a leftover taxonomy would silently
            // populate the next test's `subject_slugs`.
            "TRUNCATE TABLE colleges, college_programs, college_ipeds, college_programs_census, " +
              "subjects, ipeds_regions, us_states, nces_locales, carnegie_2021_basic_classes, " +
              "carnegie_2021_size_settings, religious_affiliations, athletic_associations, " +
              "football_conferences, admission_test_policies, cip_codes, codebook_sources CASCADE",
          ).use { it.execute() }
        if (seedsCodebookReference) CodebookReferenceFixture.seed(session)
      }
      Unit
    }

  /**
   * Puts the `us_states` / `nces_locales` / `ipeds_regions` rows back that
   * [resetDatabase] just truncated, for a suite that inserts a college WITHOUT
   * running a `codebooks` phase. [resetDatabase] calls it for every suite that
   * leaves [seedsCodebookReference] true; the one that does not calls it here.
   */
  protected fun seedCodebookReference() = withSession { session -> CodebookReferenceFixture.seed(session) }

  protected fun <T> withSession(block: (SqlSession) -> T): T = runBlocking { database.withConnection(block) }

  protected fun count(
    session: SqlSession,
    table: String,
  ): Int =
    session.prepareStatement("SELECT count(*) FROM $table").use { stmt ->
      stmt.executeQuery().use { rs ->
        rs.next()
        rs.getInt(1)
      }
    }
}
