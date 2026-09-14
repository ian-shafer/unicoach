package ed.unicoach.db

import ed.unicoach.common.models.ValidationResult
import ed.unicoach.db.models.PartialDate
import ed.unicoach.db.models.PartialDateColumns
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.time.Year
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The guard on RFC 209's partial-date storage, and it lives OUTSIDE every file
 * that RFC rewrites.
 *
 * Brief 0010's standing rule, learned the hard way: a guard inside the artifact
 * under rewrite is not a guard. It gets edited in good faith by the person most
 * likely to break it, because rewriting that file IS the task. So the claim
 * "`students` stores a date and a precision, and only its DAO ever sees the
 * pair" is asserted here, in a file whose only reason to exist is to assert it.
 *
 * Three halves, because one is not enough.
 *
 * 1. **The schema half.** `information_schema.columns` for `students` and
 *    `students_versions` carries the two new columns and none of the three old
 *    ones.
 * 2. **The source half.** No main source set outside `StudentsDao.kt` names
 *    either storage column or the `date_precision` domain. The module list is
 *    DERIVED from `settings.gradle.kts` (the `PublisherMoneyColumnsAreGoneTest`
 *    precedent), so a module added later joins the sweep automatically and a
 *    sweep that read nothing fails rather than passing vacuously.
 * 3. **The precision claim itself.** A stored `2026-01-01` at year precision
 *    reads back as a year and can never be rendered as a month or a day; a
 *    stored date that disagrees with its precision is REFUSED, not shortened.
 */
class PartialDateStorageHasOneReaderTest {
  companion object {
    private lateinit var connection: Connection

    /** The three columns RFC 209 dropped, named one per line so a reintroduction has to delete a line. */
    val DROPPED_COLUMNS =
      listOf(
        "expected_high_school_graduation_year",
        "expected_high_school_graduation_month",
        "expected_high_school_graduation_day",
      )

    /** The two columns that replaced them. */
    val STORAGE_COLUMNS =
      listOf(
        "expected_high_school_graduation_on",
        "expected_high_school_graduation_precision",
      )

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf")
          .getOrThrow()
      val dbConfig = DatabaseConfig.from(config).getOrThrow()
      connection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::connection.isInitialized && !connection.isClosed) connection.close()
    }
  }

  private fun columnsOf(table: String): Set<String> =
    connection
      .prepareStatement(
        "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ?",
      ).use { stmt ->
        stmt.setString(1, table)
        stmt.executeQuery().use { rs ->
          val names = mutableSetOf<String>()
          while (rs.next()) names += rs.getString("column_name")
          names
        }
      }

  @Test
  fun `students and students_versions store a period start and a precision and nothing decomposed`() {
    // Both tables are read before anything is asserted: asserting inside a loop
    // would abort on the first offender, so a survivor on `students` would hide
    // a survivor on `students_versions`.
    val tables = listOf("students", "students_versions").associateWith { columnsOf(it) }

    // Anti-vacuity: a typo in a table name reads an empty set and passes every
    // assertion below, so both tables are held to a column neither RFC touches.
    val missingTables = tables.filterValues { "user_id" !in it }.keys
    assertEquals(
      emptySet(),
      missingTables,
      "[$missingTables] must exist and carry `user_id` for this assertion to mean anything",
    )

    val survivors =
      tables
        .mapValues { (_, columns) -> DROPPED_COLUMNS.filter { it in columns } }
        .filterValues { it.isNotEmpty() }
    assertEquals(
      emptyMap(),
      survivors,
      "decomposed graduation columns survive: [$survivors]; RFC 209 drops them from BOTH sides",
    )

    val absent =
      tables
        .mapValues { (_, columns) -> STORAGE_COLUMNS.filter { it !in columns } }
        .filterValues { it.isNotEmpty() }
    assertEquals(
      emptyMap(),
      absent,
      "the storage pair is missing: [$absent]; both tables carry both columns or the DAO cannot map either",
    )
  }

  /** The repository root, reached from the `:db` module directory Gradle runs this test in. */
  private val repoRoot = File("..")

  /**
   * EVERY module's main source set, DERIVED from `settings.gradle.kts` rather
   * than restated here: a hand-written list names the modules that existed the
   * day it was written, and this guard's claim is about all of them.
   *
   * `db/schema` is outside every root BY CONSTRUCTION — the migrations
   * legitimately name these columns forever — and so are test source sets,
   * which may write a row in raw SQL through `StudentRowFixture`.
   */
  private val scannedRoots: Map<String, File> =
    Regex("""include\("([^"]+)"\)""")
      .findAll(File(repoRoot, "settings.gradle.kts").readText())
      .associate { it.groupValues[1] to File(repoRoot, "${it.groupValues[1]}/src/main/kotlin") }

  /**
   * The ONE file allowed to name the two COLUMNS: the DAO that encodes and
   * decodes them. Keyed on the repo-relative path, not the file name, so a
   * second `StudentsDao.kt` in another module could not inherit this permit.
   */
  private val permittedReader = "db/src/main/kotlin/ed/unicoach/db/dao/StudentsDao.kt"

  /**
   * The DOMAIN's name is a separate question from the columns', and has three
   * legitimate homes rather than one. Each is listed with WHY, so a fourth
   * cannot join by being similar to these.
   *
   * Note what is NOT here: nothing in `:service`, `:rest-server`, `:admin-web`
   * or any other module. The moment one of them needs the word, the pair has
   * escaped the data layer and this test is the thing that says so.
   */
  private val permittedDomainReaders =
    mapOf(
      permittedReader to "the DAO that reads and writes the column",
      "db/src/main/kotlin/ed/unicoach/db/models/DatePrecision.kt" to
        "the domain's one Kotlin enum -- it exists to mirror it, and its KDoc says so",
      "db/src/main/kotlin/ed/unicoach/db/models/PartialDateColumns.kt" to
        "the codec, whose KDoc names the domain and the CHECK it mirrors",
    )

  @Test
  fun `only the students DAO names the storage pair or the precision domain`() {
    val presentRoots = scannedRoots.filterValues { it.isDirectory }
    assertTrue(
      presentRoots.size >= 15,
      "expected every declared module to have a Kotlin main source set, saw ${presentRoots.keys}",
    )

    val sources =
      presentRoots.values
        .flatMap { root -> root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") } }
        .distinctBy { it.absolutePath }
        .sortedBy { it.absolutePath }
    // Anti-vacuity again: a wrong root would sweep nothing and pass.
    assertTrue(sources.size > 200, "the sweep read only ${sources.size} files; it is not reading the source tree")

    val texts = sources.associate { it.relativeTo(repoRoot).invariantSeparatorsPath to it.readText() }

    // The COLUMNS: one permitted file, and the whole point of the rule.
    val columnOffenders =
      texts
        .filterKeys { it != permittedReader }
        .mapValues { (_, text) -> STORAGE_COLUMNS.filter { it in text } }
        .filterValues { it.isNotEmpty() }
    assertEquals(
      emptyMap(),
      columnOffenders,
      "the (date, precision) pair must not escape the DAO (brief 0010 D23); found: [$columnOffenders]",
    )

    // The DOMAIN's name: three permitted files, each for a stated reason.
    val domainOffenders =
      texts
        .filterKeys { it !in permittedDomainReaders }
        .filterValues { "date_precision" in it }
        .keys
    assertEquals(
      emptySet(),
      domainOffenders,
      "only [${permittedDomainReaders.keys}] may name the `date_precision` domain; found: [$domainOffenders]",
    )

    // And the permitted reader really does name them -- otherwise the permit is
    // stale and this whole test is asserting about a file that moved.
    val reader = File(repoRoot, permittedReader).readText()
    assertEquals(
      emptyList(),
      STORAGE_COLUMNS.filter { it !in reader },
      "$permittedReader no longer names the storage columns; the permit is stale",
    )
  }

  @Test
  fun `a year-precision row reads back as a year and a disagreeing row is refused`() {
    val yearOnly = PartialDateColumns.decode(LocalDate.of(2026, 1, 1), "year")
    assertEquals(
      ValidationResult.Valid(PartialDate.YearOnly(Year.of(2026))),
      yearOnly,
      "a period-start date at year precision is a year, with no month to leak",
    )

    assertTrue(
      PartialDateColumns.decode(LocalDate.of(2026, 3, 5), "year") is ValidationResult.Invalid,
      "a date that is not the start of its own year is corruption, not a year",
    )
    assertTrue(
      PartialDateColumns.decode(LocalDate.of(2026, 3, 5), "month") is ValidationResult.Invalid,
      "a date that is not the first of its own month is corruption, not a month",
    )
    assertTrue(
      PartialDateColumns.decode(LocalDate.of(2026, 1, 1), "decade") is ValidationResult.Invalid,
      "a precision outside the domain's vocabulary is corruption",
    )
  }
}
