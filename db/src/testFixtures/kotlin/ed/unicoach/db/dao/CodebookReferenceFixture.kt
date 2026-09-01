package ed.unicoach.db.dao

import ed.unicoach.db.models.JurisdictionKind
import ed.unicoach.db.models.NewIpedsRegion
import ed.unicoach.db.models.NewNcesLocale
import ed.unicoach.db.models.NewUsState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.sql.Connection
import java.sql.PreparedStatement

/**
 * The published codebook reference rows that `colleges` now REQUIRES: the
 * `us_states` and `nces_locales` vocabularies its `state` and `locale` columns
 * point at (0067), plus the `ipeds_regions` rows `us_states.ipeds_region`
 * points at in turn.
 *
 * It lives in `:db`'s test fixtures, not in one suite, because 0067 turned "the
 * codebooks phase runs first" into a hard precondition of writing ANY college
 * row, and every module that inserts a college in a test — `:db`, `:college`,
 * `:service`, `:admin-web` — needs the same three tables non-empty. Sixteen
 * private copies of the same 51 states is sixteen things to keep in step.
 *
 * THE ROWS ARE READ FROM `db/data/codebooks.json`, the very file the ingest
 * loads, and never typed here. A hand-typed copy is a SECOND codebook: a
 * retired locale, a renamed jurisdiction or a state moved between regions would
 * leave every foreign-key test green against a vocabulary production can no
 * longer store, which is the exact failure these foreign keys exist to make
 * impossible. Deriving them means the fixture cannot drift; it can only fail to
 * parse, loudly, at the file the generator writes.
 *
 * ONE deliberate omission, and it is load-bearing: [OMITTED_REGION] — the
 * `other-us-jurisdictions` region (code 9) — and the eight territory/COFA
 * states that belong to it are FILTERED OUT. `colleges.region` has no foreign
 * key (it keeps its 0..9 range check), so region 9 is the one codebook code a
 * test can still store WITHOUT a matching reference row, which is what
 * `CollegeSearchIndexRebuildTest` needs to prove that an unexplained code goes
 * NULL rather than dropping the college. Seeding all ten regions would silently
 * turn that assertion into a tautology. No test uses a territory postal code;
 * if one ever does, drop the filter and give the rebuild suite a different
 * probe.
 *
 * Nothing else is seeded. This is the reference data the `colleges` CONSTRAINTS
 * need and no more: Carnegie classes, affiliations, athletic associations, test
 * policies and CIP codes hang off `college_ipeds` / `college_programs_census`
 * with no foreign key, so a suite that wants them seeds the miniature it
 * actually asserts on (`SearchIndexFixture`) or loads the real committed
 * codebook (`:college`'s `CodebookFixture`).
 *
 * [seed] is IDEMPOTENT (`ON CONFLICT DO NOTHING`) and writes three small
 * batches, so a suite can call it after every `TRUNCATE` without thinking about cost or
 * about whether an earlier suite already ran. It deliberately does NOT go
 * through `CodebooksDao`: those writes carry provenance and change-detection
 * that a fixture has no business faking, and a raw insert keeps the fixture
 * readable as data.
 */
object CodebookReferenceFixture {
  /** The region left unseeded on purpose, and the region its excluded states belong to. */
  const val OMITTED_REGION: String = "other-us-jurisdictions"

  /**
   * The committed codebook, found by walking up from the test's working
   * directory (the module dir under Gradle) rather than assuming a fixed depth
   * — the `CodebookFixture` precedent. Named if absent, rather than a bare
   * NoSuchElementException out of a field initializer.
   */
  val COMMITTED_FILE: File =
    generateSequence(File(".").absoluteFile) { it.parentFile }
      .map { File(it, "db/data/codebooks.json") }
      .firstOrNull { it.isFile }
      ?: error("db/data/codebooks.json was not found above [${File(".").absolutePath}]")

  private val codebook: JsonObject by lazy {
    kotlinx.serialization.json.Json
      .parseToJsonElement(COMMITTED_FILE.readText()) as JsonObject
  }

  /** The nine IPEDS regions the file publishes, minus [OMITTED_REGION]. */
  val REGIONS: List<NewIpedsRegion> by lazy {
    codes("ipeds_region")
      .map { row ->
        NewIpedsRegion(
          slug = row.text("slug"),
          code = row.text("code").toInt(),
          name = row.text("name"),
          labelRaw = row.text("label_raw"),
        )
      }.filter { it.slug != OMITTED_REGION }
  }

  /** The 51 non-territory jurisdictions: everything the file publishes outside [OMITTED_REGION]. */
  val STATES: List<NewUsState> by lazy {
    codes("us_states")
      .map { row ->
        NewUsState(
          uspsCode = row.text("code"),
          name = row.text("name"),
          jurisdictionKind =
            requireNotNull(JurisdictionKind.fromValue(row.text("jurisdiction_kind"))) {
              "codebooks.json publishes jurisdiction_kind [${row.text("jurisdiction_kind")}], " +
                "which JurisdictionKind does not name"
            },
          ipedsRegion = row.text("ipeds_region"),
        )
      }.filter { it.ipedsRegion != OMITTED_REGION }
  }

  /** All twelve published NCES locales. */
  val LOCALES: List<NewNcesLocale> by lazy {
    codes("nces_locale").map { row ->
      NewNcesLocale(
        slug = row.text("slug"),
        code = row.text("code").toInt(),
        type = row.text("type"),
        detail = row.text("detail"),
        name = row.text("name"),
        labelRaw = row.text("label_raw"),
      )
    }
  }

  /**
   * Inserts every row of [REGIONS], [STATES] and [LOCALES] that is not already
   * present. Safe to call before each test and safe to call twice.
   */
  fun seed(session: SqlSession) {
    insertAll(
      session,
      "INSERT INTO ipeds_regions (slug, code, name, label_raw) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
      REGIONS,
    ) { stmt, row ->
      stmt.setString(1, row.slug)
      stmt.setInt(2, row.code)
      stmt.setString(3, row.name)
      stmt.setString(4, row.labelRaw)
    }
    insertAll(
      session,
      "INSERT INTO us_states (usps_code, name, jurisdiction_kind, ipeds_region) VALUES (?, ?, ?, ?) " +
        "ON CONFLICT DO NOTHING",
      STATES,
    ) { stmt, row ->
      stmt.setString(1, row.uspsCode)
      stmt.setString(2, row.name)
      stmt.setString(3, row.jurisdictionKind.value)
      stmt.setString(4, row.ipedsRegion)
    }
    insertAll(
      session,
      "INSERT INTO nces_locales (slug, code, type, detail, name, label_raw) VALUES (?, ?, ?, ?, ?, ?) " +
        "ON CONFLICT DO NOTHING",
      LOCALES,
    ) { stmt, row ->
      stmt.setString(1, row.slug)
      stmt.setInt(2, row.code)
      stmt.setString(3, row.type)
      stmt.setString(4, row.detail)
      stmt.setString(5, row.name)
      stmt.setString(6, row.labelRaw)
    }
  }

  /**
   * [seed] for a suite that holds a raw JDBC [Connection] rather than a
   * [SqlSession] — `:rest-server`'s two routing suites and `:admin-web`'s
   * support object. The adapter is ONE line, which is why it was hand-copied at
   * three call sites; one line pasted three times is still three places to fix
   * when the fixture grows a second method.
   */
  fun seed(connection: Connection) =
    seed(
      object : SqlSession {
        override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
      },
    )

  /** One batched, conflict-tolerant insert — the same loop for all three tables. */
  private fun <T> insertAll(
    session: SqlSession,
    sql: String,
    rows: List<T>,
    bind: (PreparedStatement, T) -> Unit,
  ) {
    session.prepareStatement(sql).use { stmt ->
      for (row in rows) {
        bind(stmt, row)
        stmt.addBatch()
      }
      stmt.executeBatch()
    }
  }

  /** The `codes` array of one codebook domain. */
  private fun codes(domain: String): List<JsonObject> =
    ((codebook[domain] as JsonObject).getValue("codes") as JsonArray).map { it as JsonObject }

  /** One required scalar of a codebook row, as text (the codes are ints or postal strings). */
  private fun JsonObject.text(key: String): String =
    requireNotNull(this[key]) { "codebooks.json row is missing [$key]: $this" }.jsonPrimitive.content
}
