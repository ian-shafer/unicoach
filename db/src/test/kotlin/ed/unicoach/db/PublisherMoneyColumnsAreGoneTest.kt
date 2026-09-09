package ed.unicoach.db

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Reader count zero on the publisher money shape (RFC 176 D9), proved in two
 * halves because one is not enough.
 *
 * 1. **The schema half.** `information_schema.columns` for `colleges` and for
 *    `colleges_versions` names none of the eighteen. Migration `0094` dropped
 *    both sides (D5), so this is the whole claim about the database.
 * 2. **The source half.** No main source set reads or writes one of the
 *    eighteen AS A `colleges` COLUMN.
 *
 * Both name all eighteen columns explicitly, so the next person to reintroduce
 * one has to delete a line that says why it went.
 *
 * ## Why the source half is an allowlist and not "the name appears nowhere"
 *
 * Eleven of the eighteen NAMES are still spoken by this codebase, correctly,
 * about something else:
 *
 * - `net_price_per_year_usd` is ALSO a `college_search_index` column — the
 *   default price ruler (`PriceRulerSql.NET_PRICE_VALUE_COLUMN`), which RFC 176
 *   D3 keeps and re-sources rather than retires;
 * - `pell_share` is ALSO a `money_measures` value and a search wire key;
 * - the two tuition tiers, the six cost components, the median debt and the
 *   median earnings are ALSO `CostField.wireName`s — canonical CONCEPT names
 *   since RFC 166, which is why `CostField` needed no change in this RFC.
 *
 * A test asserting the strings appear nowhere would therefore be unsatisfiable
 * without breaking the wire contract, the index schema and the canonical
 * vocabulary — so it would have been weakened or deleted the first time it
 * fired, which is the fate of every guard that cries wolf. Instead every
 * surviving occurrence is listed HERE with the namespace it belongs to, and
 * anything else fails. A line that puts one of the eighteen back on `colleges`
 * is not in the list, so it fails; and the list is the record of which other
 * namespaces reuse the words.
 */
class PublisherMoneyColumnsAreGoneTest {
  companion object {
    private lateinit var connection: Connection

    /**
     * The modules `settings.gradle.kts` declared when `0094` landed, BY NAME.
     *
     * A SET, not a count. A module added later joins the sweep automatically
     * and needs no edit here; a module that leaves the build has to be deleted
     * from this set, which is where that decision belongs. A count could say
     * neither thing: `>=` is an underflow check, and a module ADDED would hide
     * a module REMOVED, which is exactly the hole this guard must not have.
     */
    val MODULES_AT_0094 =
      setOf(
        "common",
        "web-common",
        "service",
        "rest-server",
        "db",
        "queue",
        "queue-worker",
        "cron",
        "net",
        "auth",
        "email",
        "chat",
        "appstore",
        "admin-web",
        "public-web",
        "college",
        "fixture",
      )

    /**
     * The eighteen publisher-shaped money columns migration `0094` dropped from
     * `colleges` and `colleges_versions` (RFC 176). Written out one per line,
     * never derived, so the list itself is the record.
     */
    val DROPPED_COLUMNS =
      listOf(
        "cost_of_attendance_per_year_usd",
        "net_price_per_year_usd",
        "net_price_per_year_income_q1_usd",
        "net_price_per_year_income_q2_usd",
        "net_price_per_year_income_q3_usd",
        "net_price_per_year_income_q4_usd",
        "net_price_per_year_income_q5_usd",
        "tuition_and_fees_in_state_per_year_usd",
        "tuition_and_fees_out_of_state_per_year_usd",
        "median_earnings_10y_after_entry_usd",
        "median_debt_at_completion_usd",
        "housing_and_food_on_campus_per_year_usd",
        "housing_and_food_off_campus_per_year_usd",
        "books_and_supplies_per_year_usd",
        "other_expenses_on_campus_per_year_usd",
        "other_expenses_off_campus_per_year_usd",
        "other_expenses_with_family_per_year_usd",
        "pell_share",
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
  fun `colleges and colleges_versions carry none of the eighteen money columns`() {
    assertEquals(18, DROPPED_COLUMNS.size, "the list must name all eighteen")
    // BOTH tables are read before anything is asserted. Asserting inside the
    // loop would abort on the first offender, so a survivor on `colleges`
    // would hide a survivor on `colleges_versions` and the operator would
    // learn the second half only after fixing the first -- the same halt this
    // file argues against 90 lines below.
    val tables = listOf("colleges", "colleges_versions").associateWith { columnsOf(it) }
    // Anti-vacuity: a typo in a table name would read an empty set and pass
    // every assertion below, so both tables are held to their identity column.
    val missingTables = tables.filterValues { "ipeds_unit_id" !in it }.keys
    assertEquals(
      emptySet(),
      missingTables,
      "[$missingTables] must exist and carry `ipeds_unit_id` for this assertion to mean anything, " +
        "saw [${tables.mapValues { it.value.size }}] columns per table",
    )
    val survivors =
      tables
        .mapValues { (_, columns) -> DROPPED_COLUMNS.filter { it in columns } }
        .filterValues { it.isNotEmpty() }
    assertEquals(
      emptyMap(),
      survivors,
      "publisher money columns survive: [$survivors]; migration 0094 drops them from BOTH sides (RFC 176 D5)",
    )
  }

  /** The repository root, reached from the `:db` module directory Gradle runs this test in. */
  private val repoRoot = File("..")

  /**
   * EVERY module's main source set, DERIVED from `settings.gradle.kts` -- the
   * one artifact that owns the module list -- rather than restated here.
   *
   * A hand-written list names the modules that existed the day it was written.
   * This guard's whole claim is that the reader count is ZERO, and migration
   * `0094` cites it as the justification for an irreversible drop, so a module
   * it forgets -- `public-web` renders the cost report page -- is a hole in
   * the proof and not a smaller sweep. Every root is asserted to EXIST below,
   * so a module with no Kotlin main source set fails loudly rather than
   * dropping out silently.
   *
   * `db/schema` (the migrations) is outside every one of them BY CONSTRUCTION: the
   * migrations legitimately name these columns forever — the ones that added
   * them, the ones that renamed them, and `0094` itself, which drops them by
   * name and records the cutoff.
   */
  private val scannedRoots: Map<String, File> =
    Regex("""include\("([^"]+)"\)""")
      .findAll(File(repoRoot, "settings.gradle.kts").readText())
      .associate { it.groupValues[1] to File(repoRoot, "${it.groupValues[1]}/src/main/kotlin") }

  private val sources: List<File>
    get() =
      scannedRoots.values
        .flatMap { root -> root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") } }
        .distinctBy { it.absolutePath }
        .sortedBy { it.absolutePath }

  /**
   * A swept file's identity: its path relative to the repository root, with
   * `/` separators on every platform.
   *
   * The allowlist below is keyed on this and not on `file.name`, because an
   * exemption is granted to a FILE. Keyed by name, a second `CostField.kt` in
   * another module would inherit the first one's permit and its occurrences
   * would merge into the same count before the drift guard ever read them.
   */
  private fun File.repoPath(): String = relativeTo(repoRoot).invariantSeparatorsPath

  /**
   * The permit one allowlisted file holds: how many occurrences of the eighteen
   * names it may carry, and WHY they are not `colleges` columns.
   *
   * Two named fields rather than a `Pair`, so neither the allowlist above nor
   * the drift check below can read the count as the reason.
   */
  private data class PermittedOccurrences(
    val count: Int,
    val reason: String,
  )

  /**
   * One allowlisted file's count disagreeing with its permit: what it was
   * PERMITTED against what the scan FOUND.
   *
   * Named halves, because the two are both `Int` and a failure message must say
   * which way the count moved without the reader trusting an argument order.
   */
  private data class OccurrenceDrift(
    val permitted: Int,
    val found: Int,
  )

  /**
   * Every occurrence of one of the eighteen NAMES that is not a `colleges`
   * column, keyed by the REPO-RELATIVE PATH of the file that holds it, with the
   * count and the reason.
   *
   * The key is a path and not a bare file name because the permit is granted to
   * one file: a same-named file in another module must not inherit the licence,
   * and two files' counts must not merge into one entry before the drift check
   * reads them.
   *
   * A count, not a line number: a line number would go stale on any edit above
   * it and turn this guard into a chore. A count still fails when an occurrence
   * is ADDED, which is the direction that matters — and the failure message
   * prints the `column:line` keys actually found, so the moved line is named.
   */
  private val permittedOccurrencesByFile: Map<String, PermittedOccurrences> =
    mapOf(
      // `college_search_index.net_price_per_year_usd` -- the default price
      // ruler (RFC 176 D3 keeps it and re-sources it from `cohort_money_stats`),
      // its rebuild, its percentile ladder, and the KDoc that says the schema
      // name stays.
      "db/src/main/kotlin/ed/unicoach/db/dao/CollegesDao.kt" to
        PermittedOccurrences(
          17,
          "fifteen college_search_index.net_price_per_year_usd -- the ruler column, its rebuild and its " +
            "percentile ladder -- plus two `pell_share` in the KDoc of PELL_SHARE_COLUMN, which says why the " +
            "lateral's alias is deliberately NOT one of the eighteen names",
        ),
      "db/src/main/kotlin/ed/unicoach/db/dao/PriceRulerSql.kt" to
        PermittedOccurrences(1, "NET_PRICE_VALUE_COLUMN -- the college_search_index column the net ruler reads"),
      "db/src/main/kotlin/ed/unicoach/db/models/PriceRuler.kt" to
        PermittedOccurrences(1, "a KDoc saying the wire key is NOT the column name"),
      // Search's own wire vocabulary (RFC 133/142), fed from `cohort_money_stats`.
      "college/src/main/kotlin/ed/unicoach/college/CollegeMatchRow.kt" to
        PermittedOccurrences(3, "search wire keys, fed from cohort_money_stats"),
      "college/src/main/kotlin/ed/unicoach/college/CollegeSearchTool.kt" to
        PermittedOccurrences(1, "the tool schema's description of a wire key"),
      // The canonical vocabulary and the cost wire names (RFC 166): concept
      // names that merely LOOK like the old columns.
      "db/src/main/kotlin/ed/unicoach/db/models/MoneyMeasure.kt" to
        PermittedOccurrences(1, "the `pell_share` money measure"),
      "service/src/main/kotlin/ed/unicoach/coaching/costs/CostField.kt" to
        PermittedOccurrences(10, "canonical concept wire names, addressed through CollegeFigures.figureAddress"),
      "service/src/main/kotlin/ed/unicoach/coaching/costs/canonical/CollegeFigures.kt" to
        PermittedOccurrences(2, "comments naming the `pell_share` measure"),
    )

  @Test
  fun `the source scan is actually reading the main source sets`() {
    // Anti-vacuity on the DERIVATION itself: a regex that matched nothing
    // would sweep no file and pass every assertion about offenders. Named
    // modules, not a count: a count is an underflow check, so a module ADDED
    // would cover for a module REMOVED.
    val swept = scannedRoots.keys
    assertEquals(
      emptySet(),
      MODULES_AT_0094 - swept,
      "every module settings.gradle.kts declared when 0094 landed must still be swept, " +
        "missing [${MODULES_AT_0094 - swept}]; the sweep reached [$swept]",
    )
    // Every missing source set at once: asserting inside the loop would let the
    // first absent root hide all the others.
    val missingRoots = scannedRoots.filterValues { !it.isDirectory }.mapValues { it.value.absolutePath }
    assertEquals(emptyMap(), missingRoots, "expected a main source set at each of [$missingRoots]")
    val names = sources.map { it.name }.toSet()
    // Named files, not a count: a count goes green again the moment an
    // unrelated file lands. `CollegesDao.kt` is the file that held every one of
    // the eighteen, so the scan is worthless if it does not reach it.
    val required =
      setOf("CollegesDao.kt", "College.kt", "NewCollege.kt", "CollegeScorecardLoader.kt", "CollegesResource.kt")
    assertEquals(
      emptySet(),
      required - names,
      "the scan must reach the files that carried the publisher money shape, " +
        "but did not reach [${required - names}]; " +
        "it swept [${sources.size}] files under [${scannedRoots.values.map { it.absolutePath }}]",
    )
    assertTrue(
      sources.none { it.absolutePath.contains("/db/schema/") },
      "db/schema is excluded by construction: the scan reads .kt files under src/main/kotlin only",
    )
  }

  /**
   * Every occurrence of one of the eighteen NAMES in the swept sources, keyed
   * by the file's REPO-RELATIVE PATH and then by `column:line`.
   *
   * The scan is done ONCE and read by both tests below. They ask two different
   * questions of it -- "is there an offender?" and "did an allowlisted file's
   * count move?" -- and they are two `@Test`s for exactly that reason: as one
   * method the first `assertEquals` aborted the body, so a NEW offender also
   * stopped the drift guard from ever running.
   */
  private val occurrences: Map<String, Map<String, Int>> by lazy {
    val counts = mutableMapOf<String, MutableMap<String, Int>>()
    for (file in sources) {
      for ((number, line) in file.readLines().withIndex()) {
        for (column in DROPPED_COLUMNS) {
          if (!containsWord(line, column)) continue
          counts.getOrPut(file.repoPath()) { mutableMapOf() }.merge("$column:${number + 1}", 1, Int::plus)
        }
      }
    }
    counts
  }

  @Test
  fun `no main source names one of the eighteen as a colleges column`() {
    val offenders =
      occurrences
        .filterKeys { it !in permittedOccurrencesByFile }
        .mapValues { (_, found) -> found.keys.sorted() }
    assertEquals(
      emptyMap(),
      offenders,
      "a main source names a dropped publisher money column: [$offenders]. " +
        "The eighteen left `colleges` in migration 0094 (RFC 176); money is read from " +
        "`price_figures` / `cohort_money_stats`. If the occurrence is a DIFFERENT namespace " +
        "-- the search-index ruler column, a wire key, a CostField concept name -- add it to " +
        "`permittedOccurrencesByFile` with its reason.",
    )
  }

  @Test
  fun `every allowlisted file still carries exactly the occurrences it was permitted`() {
    // The allowlist is held to its own counts, in BOTH directions: a new
    // occurrence in an allowlisted file must still be justified, and an entry
    // whose occurrences all went away must be deleted rather than left as a
    // permit nobody needs.
    val allowed = occurrences.filterKeys { it in permittedOccurrencesByFile }.mapValues { it.value.size }
    val drift =
      permittedOccurrencesByFile
        .mapValues { (file, permit) -> OccurrenceDrift(permit.count, allowed[file] ?: 0) }
        .filter { (_, drift) -> drift.permitted != drift.found }
    // The payload carries the occurrences it already holds, not two integers:
    // the `column:line` set names the line that moved, so the failure does not
    // start with a grep.
    val seen = drift.keys.associateWith { file -> occurrences[file].orEmpty().keys.sorted() }
    assertEquals(
      emptyMap(),
      drift,
      "an allowlisted file's occurrence count moved: [$drift]; " +
        "the occurrences actually found there: [$seen]. Each is `column:line`; justify a new one in " +
        "`permittedOccurrencesByFile` with its namespace, or delete the entry if its occurrences went away.",
    )
  }

  /**
   * Each column as a WHOLE-IDENTIFIER matcher, compiled once and reused for
   * every line of every swept file.
   *
   * The boundary matters: `sticker_cost_of_attendance_per_year_usd` is
   * `CostField`'s wire name and contains `cost_of_attendance_per_year_usd`, so
   * a bare `contains` would report the first as an occurrence of the second
   * column. The rule is stated by the platform rather than re-derived as index
   * arithmetic: the lookarounds spell the identifier alphabet explicitly, so
   * they keep their meaning even if a future entry in [DROPPED_COLUMNS] begins
   * or ends with a character `\b` would treat as a boundary.
   */
  private val wordMatchers: Map<String, Regex> =
    DROPPED_COLUMNS.associateWith {
      Regex("""(?<![A-Za-z0-9_])${Regex.escape(it)}(?![A-Za-z0-9_])""")
    }

  /** Whether [line] names [column] as a WHOLE identifier. */
  private fun containsWord(
    line: String,
    column: String,
  ): Boolean = wordMatchers.getValue(column).containsMatchIn(line)
}
