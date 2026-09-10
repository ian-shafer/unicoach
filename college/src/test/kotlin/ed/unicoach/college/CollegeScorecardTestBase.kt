package ed.unicoach.college

import ed.unicoach.common.config.AppConfig
import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.Database
import ed.unicoach.db.DatabaseConfig
import ed.unicoach.db.dao.CodebookReferenceFixture
import ed.unicoach.db.dao.MoneyVocabularyFixture
import ed.unicoach.db.dao.SqlSession
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import java.io.File
import java.sql.ResultSet

/**
 * Shared DB-test scaffolding for the college ingest suites: opens one pooled
 * [Database] for the class, truncates the two Scorecard tables and the two RFC
 * 144 IPEDS tables before each test, and offers the fixture/session/count helpers both suites need. Concrete
 * suites supply only their fixtures and assertions.
 */
abstract class CollegeScorecardTestBase {
  companion object {
    /**
     * The IPEDS survey year the committed IC_AY fixtures transcribe -- the
     * `IC2023_AY.csv` window `IpedsChargeVocabulary` decodes. Named once here
     * rather than typed as a bare `2023` beside each fixture list.
     */
    const val IC_AY_FIXTURE_SURVEY_YEAR = 2023

    /**
     * How many institutions the committed IC_AY fixture transcribes: Austin
     * Community College District (222992), UC San Diego (110680), Harvard
     * (166027) and Florida State College at Jacksonville (133702).
     *
     * Named once, because every staged-row count in the charges suites is a
     * multiple of it: RFC 183 added the fourth institution -- the real college
     * whose in-district and in-state tuitions merged -- and a suite that had
     * typed `3` in nine places had to be re-read nine times to say so.
     */
    const val IC_AY_FIXTURE_RECORDS = 4

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

  /**
   * Whether [resetDatabase] puts the five RFC 158 money-vocabulary tables
   * back after truncating them -- [seedsCodebookReference]'s reasoning
   * applied to the canonical money store: P2 made a loaded vocabulary a write
   * precondition of every `price_figures`/`cohort_money_stats` row, and the
   * `canonical-money` phase runs in every ingest. `MoneyVocabularyLoaderTest`
   * overrides it to false: it asserts first-load INSERT counts, which a
   * pre-seeded vocabulary would turn into UNCHANGED.
   */
  protected open val seedsMoneyVocabulary: Boolean get() = true

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
            // The RFC 158 canonical money tables are truncated here too:
            // `price_figures`/`cohort_money_stats` cascade from `colleges`,
            // but the five vocabulary tables cascade from nothing, and a
            // leftover vocabulary would hide a suite that forgot its seed.
            // `college_sfa` (RFC 162) is named for the same reason `subjects`
            // is: nothing cascades to it, and a leftover staged cell would
            // give the next test's canonical fill rows for a college it never
            // seeded. `cohort_population_counts` cascades from `colleges`
            // like its money sibling, and is named beside it for symmetry.
            "TRUNCATE TABLE colleges, college_programs, college_ipeds, college_programs_census, " +
              "college_sfa, cohort_population_counts, " +
              "subjects, ipeds_regions, us_states, nces_locales, carnegie_2021_basic_classes, " +
              "carnegie_2021_size_settings, religious_affiliations, athletic_associations, " +
              "football_conferences, admission_test_policies, cip_codes, codebook_sources, " +
              "price_figures, cohort_money_stats, aid_form_requirements, source_documents, " +
              "residency_bases, arrangements, aid_forms, " +
              "figure_statuses, price_concepts, income_bands CASCADE",
          ).use { it.execute() }
        if (seedsCodebookReference) CodebookReferenceFixture.seed(session)
        if (seedsMoneyVocabulary) MoneyVocabularyFixture.seed(session)
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

  /** One input file, addressed the way every loader entry point takes it. */
  protected fun source(file: File): SourceFile = SourceFile(file, file.path)

  /**
   * The committed IC_AY corpus -- [IC_AY_FIXTURE_RECORDS] real institutions --
   * as `CollegeScorecardLoader.ingest` takes it: the same six fixtures two
   * suites had each spelled out.
   *
   * Named for the CORPUS rather than `ipedsSources`, because `IpedsIngestTest`
   * builds a different IPEDS group under that name: two all-defaulted helpers
   * of one name is a call that silently resolves to the other suite's fixtures.
   *
   * [icAy] is the one file a suite varies (the departed/flags/unknown-flag
   * variants), so it is the one parameter.
   */
  protected fun ipedsCorpusSources(icAy: String = "ipeds-ic2023-ay-fixture.csv"): IpedsSources =
    IpedsSources(
      source(fixture("ipeds-hd2023-fixture.csv")),
      source(fixture("ipeds-ic2023-fixture.csv")),
      source(fixture("ipeds-adm2023-fixture.csv")),
      source(fixture("ipeds-c2023-a-fixture.csv")),
      source(fixture(icAy)),
      IC_AY_FIXTURE_SURVEY_YEAR,
    )

  /** One `price_figures` row, addressed the way every money suite addresses it. */
  protected data class Figure(
    val amountUsd: Int?,
    val status: String,
    val source: String,
    val sourceVariable: String,
    val publisherFlag: String?,
  )

  /**
   * The one `price_figures` row at a cell address, or null when the fill wrote
   * none. Two money suites had pasted this SELECT, and the copies had already
   * drifted -- one carried `status` and `publisher_flag`, the other did not --
   * so a column added to the read reached one suite only.
   */
  protected fun figure(
    ipedsUnitId: Int,
    concept: String,
    residency: String,
    arrangement: String = "not_applicable",
    academicYear: AcademicYear,
  ): Figure? =
    query(
      "SELECT p.amount_usd, p.status, p.source, p.source_variable, p.publisher_flag " +
        "FROM price_figures p JOIN colleges g ON g.id = p.college_id " +
        "WHERE g.ipeds_unit_id = $ipedsUnitId AND p.price_concept = '$concept' " +
        "AND p.residency_basis = '$residency' AND p.arrangement = '$arrangement' " +
        "AND p.academic_year = ${academicYear.firstCalendarYear}",
    ) { rs ->
      Figure(
        amountUsd = rs.getInt(1).takeUnless { rs.wasNull() },
        status = rs.getString(2),
        source = rs.getString(3),
        sourceVariable = rs.getString(4),
        publisherFlag = rs.getString(5),
      )
    }.singleOrNull()

  /**
   * Every row of an arbitrary read-only query, mapped. Three suites had
   * pasted the same twelve lines; it belongs here beside [withSession], which
   * is the only reason those copies were identical to begin with.
   *
   * [sql] is TEST-authored SQL, never data under test: the suites assert
   * against the table the loader wrote, which is exactly what a mock of the
   * DAO could not do.
   */
  protected fun <T> query(
    sql: String,
    map: (ResultSet) -> T,
  ): List<T> =
    withSession { session ->
      session.prepareStatement(sql).use { stmt ->
        stmt.executeQuery().use { rs ->
          val rows = mutableListOf<T>()
          while (rs.next()) rows += map(rs)
          rows
        }
      }
    }

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
