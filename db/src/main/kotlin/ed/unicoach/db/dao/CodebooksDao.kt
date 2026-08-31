package ed.unicoach.db.dao

import ed.unicoach.db.models.NewAdmissionTestPolicy
import ed.unicoach.db.models.NewAthleticAssociation
import ed.unicoach.db.models.NewCarnegieBasicClass
import ed.unicoach.db.models.NewCarnegieSizeSetting
import ed.unicoach.db.models.NewCipCode
import ed.unicoach.db.models.NewCodebookSource
import ed.unicoach.db.models.NewFootballConference
import ed.unicoach.db.models.NewIpedsRegion
import ed.unicoach.db.models.NewNcesLocale
import ed.unicoach.db.models.NewReligiousAffiliation
import ed.unicoach.db.models.NewUsState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.sql.PreparedStatement
import java.sql.SQLException

/**
 * The ten published-codebook reference tables plus `codebook_sources`
 * (RFC 147), created by `0060.create-codebook-reference-tables.sql`.
 *
 * Stateless `object`, one [SqlSession] per call, transaction boundaries owned by
 * the caller — the [CollegesDao]/[CollegeIpedsDao] shape. Every row write is an
 * upsert-if-changed through the shared [upsertDetectingChange] primitive, so
 * re-loading an unchanged codebook writes nothing and advances no `updated_at`;
 * the one exception is [upsertSource], whose whole payload includes the load
 * time and is therefore rewritten every run.
 *
 * SQL identifiers are never caller data. The two identifier-parameterised reads
 * ([storedRows], [storedCodeCounts]) take a member of the closed [CodebookTable]
 * enum or of the closed [CODE_COLUMNS] set — anything else is rejected here
 * rather than reaching SQL.
 */
object CodebooksDao {
  // ---------------------------------------------------------------------------
  // Row writes — one per reference table, in load order (us_states' FK needs
  // ipeds_regions to exist first).
  // ---------------------------------------------------------------------------

  fun upsertIpedsRegion(
    session: SqlSession,
    row: NewIpedsRegion,
  ): Result<UpsertOutcome> =
    session.upsertDetectingChange(
      table = CodebookTable.IPEDS_REGIONS.tableName,
      keyColumns = linkedMapOf("slug" to { stmt: PreparedStatement, i: Int -> stmt.setString(i, row.slug) }),
      columns =
        linkedMapOf<String, Bind>(
          "code" to { stmt, i -> stmt.setInt(i, row.code) },
          "name" to { stmt, i -> stmt.setString(i, row.name) },
          "label_raw" to { stmt, i -> stmt.setString(i, row.labelRaw) },
        ),
      mapError = ::mapCodebookWriteError,
    )

  fun upsertUsState(
    session: SqlSession,
    row: NewUsState,
  ): Result<UpsertOutcome> =
    session.upsertDetectingChange(
      table = CodebookTable.US_STATES.tableName,
      keyColumns = linkedMapOf("usps_code" to { stmt: PreparedStatement, i: Int -> stmt.setString(i, row.uspsCode) }),
      columns =
        linkedMapOf<String, Bind>(
          "name" to { stmt, i -> stmt.setString(i, row.name) },
          "jurisdiction_kind" to { stmt, i -> stmt.setString(i, row.jurisdictionKind.value) },
          "ipeds_region" to { stmt, i -> stmt.setString(i, row.ipedsRegion) },
        ),
      mapError = ::mapCodebookWriteError,
    )

  fun upsertNcesLocale(
    session: SqlSession,
    row: NewNcesLocale,
  ): Result<UpsertOutcome> =
    session.upsertDetectingChange(
      table = CodebookTable.NCES_LOCALES.tableName,
      keyColumns = linkedMapOf("slug" to { stmt: PreparedStatement, i: Int -> stmt.setString(i, row.slug) }),
      columns =
        linkedMapOf<String, Bind>(
          "code" to { stmt, i -> stmt.setInt(i, row.code) },
          "type" to { stmt, i -> stmt.setString(i, row.type) },
          "detail" to { stmt, i -> stmt.setString(i, row.detail) },
          "name" to { stmt, i -> stmt.setString(i, row.name) },
          "label_raw" to { stmt, i -> stmt.setString(i, row.labelRaw) },
        ),
      mapError = ::mapCodebookWriteError,
    )

  fun upsertCarnegieBasicClass(
    session: SqlSession,
    row: NewCarnegieBasicClass,
  ): Result<UpsertOutcome> =
    session.upsertDetectingChange(
      table = CodebookTable.CARNEGIE_2021_BASIC_CLASSES.tableName,
      keyColumns = linkedMapOf("slug" to { stmt: PreparedStatement, i: Int -> stmt.setString(i, row.slug) }),
      columns =
        linkedMapOf<String, Bind>(
          "code" to { stmt, i -> stmt.setInt(i, row.code) },
          "degree_level" to { stmt, i -> stmt.setStringOrNull(i, row.degreeLevel) },
          "qualifier" to { stmt, i -> stmt.setStringOrNull(i, row.qualifier) },
          "name" to { stmt, i -> stmt.setString(i, row.name) },
          "label_raw" to { stmt, i -> stmt.setString(i, row.labelRaw) },
        ),
      mapError = ::mapCodebookWriteError,
    )

  fun upsertCarnegieSizeSetting(
    session: SqlSession,
    row: NewCarnegieSizeSetting,
  ): Result<UpsertOutcome> =
    session.upsertDetectingChange(
      table = CodebookTable.CARNEGIE_2021_SIZE_SETTINGS.tableName,
      keyColumns = linkedMapOf("slug" to { stmt: PreparedStatement, i: Int -> stmt.setString(i, row.slug) }),
      columns =
        linkedMapOf<String, Bind>(
          "code" to { stmt, i -> stmt.setInt(i, row.code) },
          "years" to { stmt, i -> stmt.setIntOrNull(i, row.years) },
          "size" to { stmt, i -> stmt.setStringOrNull(i, row.size) },
          "residential_character" to { stmt, i -> stmt.setStringOrNull(i, row.residentialCharacter) },
          "name" to { stmt, i -> stmt.setString(i, row.name) },
          "label_raw" to { stmt, i -> stmt.setString(i, row.labelRaw) },
        ),
      mapError = ::mapCodebookWriteError,
    )

  fun upsertReligiousAffiliation(
    session: SqlSession,
    row: NewReligiousAffiliation,
  ): Result<UpsertOutcome> =
    session.upsertDetectingChange(
      table = CodebookTable.RELIGIOUS_AFFILIATIONS.tableName,
      keyColumns = linkedMapOf("slug" to { stmt: PreparedStatement, i: Int -> stmt.setString(i, row.slug) }),
      columns =
        linkedMapOf<String, Bind>(
          "code" to { stmt, i -> stmt.setInt(i, row.code) },
          "name" to { stmt, i -> stmt.setString(i, row.name) },
          "label_raw" to { stmt, i -> stmt.setString(i, row.labelRaw) },
        ),
      mapError = ::mapCodebookWriteError,
    )

  fun upsertAthleticAssociation(
    session: SqlSession,
    row: NewAthleticAssociation,
  ): Result<UpsertOutcome> =
    session.upsertDetectingChange(
      table = CodebookTable.ATHLETIC_ASSOCIATIONS.tableName,
      keyColumns = linkedMapOf("slug" to { stmt: PreparedStatement, i: Int -> stmt.setString(i, row.slug) }),
      columns =
        linkedMapOf<String, Bind>(
          "code" to { stmt, i -> stmt.setInt(i, row.code) },
          "source_variable" to { stmt, i -> stmt.setString(i, row.sourceVariable) },
          "name" to { stmt, i -> stmt.setString(i, row.name) },
          "label_raw" to { stmt, i -> stmt.setString(i, row.labelRaw) },
        ),
      mapError = ::mapCodebookWriteError,
    )

  fun upsertFootballConference(
    session: SqlSession,
    row: NewFootballConference,
  ): Result<UpsertOutcome> =
    session.upsertDetectingChange(
      table = CodebookTable.FOOTBALL_CONFERENCES.tableName,
      keyColumns = linkedMapOf("slug" to { stmt: PreparedStatement, i: Int -> stmt.setString(i, row.slug) }),
      columns =
        linkedMapOf<String, Bind>(
          "code" to { stmt, i -> stmt.setInt(i, row.code) },
          "name" to { stmt, i -> stmt.setString(i, row.name) },
          "label_raw" to { stmt, i -> stmt.setString(i, row.labelRaw) },
        ),
      mapError = ::mapCodebookWriteError,
    )

  fun upsertAdmissionTestPolicy(
    session: SqlSession,
    row: NewAdmissionTestPolicy,
  ): Result<UpsertOutcome> =
    session.upsertDetectingChange(
      table = CodebookTable.ADMISSION_TEST_POLICIES.tableName,
      keyColumns = linkedMapOf("slug" to { stmt: PreparedStatement, i: Int -> stmt.setString(i, row.slug) }),
      columns =
        linkedMapOf<String, Bind>(
          "code" to { stmt, i -> stmt.setInt(i, row.code) },
          "name" to { stmt, i -> stmt.setString(i, row.name) },
          "label_raw" to { stmt, i -> stmt.setString(i, row.labelRaw) },
        ),
      mapError = ::mapCodebookWriteError,
    )

  fun upsertCipCode(
    session: SqlSession,
    row: NewCipCode,
  ): Result<UpsertOutcome> =
    session.upsertDetectingChange(
      table = CodebookTable.CIP_CODES.tableName,
      keyColumns = linkedMapOf("code" to { stmt: PreparedStatement, i: Int -> stmt.setString(i, row.code) }),
      columns =
        linkedMapOf<String, Bind>(
          "title" to { stmt, i -> stmt.setString(i, row.title) },
          "label_raw" to { stmt, i -> stmt.setString(i, row.labelRaw) },
        ),
      mapError = ::mapCodebookWriteError,
    )

  /**
   * The codebook write-path SQLSTATE mapping. `23503` can only be
   * `us_states.ipeds_region` naming a region that is not there — a load-order
   * defect, not a caller's bad key — so the message says exactly that instead of
   * a constant sentence; `23505`/`23514` (a duplicate code, a value outside the
   * `slug` domain or one of the parsed-column CHECKs) keep the violated
   * constraint name, which is what a loader reports.
   */
  private fun mapCodebookWriteError(e: SQLException): Exception =
    mapReferenceWriteError(
      e,
      "Referenced codebook row not found: a us_states row names an ipeds_regions slug that does not exist",
    )

  // ---------------------------------------------------------------------------
  // Provenance
  // ---------------------------------------------------------------------------

  /**
   * Writes one domain's `codebook_sources` row, ALWAYS: unlike the reference
   * rows this is not change-suppressed, because `loaded_at` — when this domain
   * was last loaded — is part of what the row records, and a suppressed write
   * would leave it stale while claiming to be provenance.
   *
   * `null_sentinels` is bound as ONE jsonb parameter and expanded to
   * `smallint[]` by Postgres rather than built client-side with
   * `Connection.createArrayOf`, the [CollegeIpedsDao.upsert] precedent: the
   * [SqlSession] boundary deliberately withholds the pooled connection.
   */
  fun upsertSource(
    session: SqlSession,
    row: NewCodebookSource,
  ): Result<Unit> =
    session.mutateReturning(
      """
      INSERT INTO codebook_sources (
        domain, source, source_file, source_sha256, source_vintage_year, null_sentinels, loaded_at
      )
      VALUES (?, ?, ?, ?, ?, ARRAY(SELECT jsonb_array_elements_text(?::jsonb))::smallint[], NOW())
      ON CONFLICT (domain) DO UPDATE SET
        source = EXCLUDED.source,
        source_file = EXCLUDED.source_file,
        source_sha256 = EXCLUDED.source_sha256,
        source_vintage_year = EXCLUDED.source_vintage_year,
        null_sentinels = EXCLUDED.null_sentinels,
        loaded_at = EXCLUDED.loaded_at
      RETURNING domain
      """.trimIndent(),
      bind = { stmt ->
        stmt.setString(1, row.domain)
        stmt.setString(2, row.source)
        stmt.setString(3, row.sourceFile)
        stmt.setString(4, row.sourceSha256)
        stmt.setInt(5, row.sourceVintageYear)
        stmt.setString(6, JsonArray(row.nullSentinels.map { JsonPrimitive(it) }).toString())
      },
      map = { },
      mapError = ::mapCodebookWriteError,
    )

  /**
   * Every domain's STORED artifact digest, keyed by domain — read BEFORE the
   * load rewrites it. This is the drift guard's left-hand side: comparing it to
   * the digest the incoming file declares says whether the codebook now being
   * loaded came from a different artifact than the rows already in the table.
   */
  fun storedSourceDigests(session: SqlSession): Result<Map<String, String>> =
    session
      .queryList(
        "SELECT domain, source_sha256 FROM codebook_sources",
        bind = {},
        map = { rs -> rs.getString("domain") to rs.getString("source_sha256") },
      ).map { it.toMap() }

  // ---------------------------------------------------------------------------
  // Reads over the reference tables themselves
  // ---------------------------------------------------------------------------

  /**
   * Every stored row of [table] as its natural key plus its published [code]
   * (null for the two tables whose key IS the code). The loader diffs this
   * against the incoming file to find rows the publisher dropped, and needs the
   * code as well as the key because the columns that REFER to a codebook store
   * the code, not the slug.
   */
  fun storedRows(
    session: SqlSession,
    table: CodebookTable,
  ): Result<List<StoredCodebookRow>> {
    val codeSelect = if (table.hasCodeColumn) "code" else "NULL::smallint AS code"
    return session.queryList(
      "SELECT ${table.keyColumn} AS key, $codeSelect FROM ${table.tableName}",
      bind = {},
      map = { rs -> StoredCodebookRow(key = rs.getString("key"), code = rs.getIntOrNull("code")) },
    )
  }

  /** Deletes one row of [table] by its natural key, returning the affected-row count. */
  fun deleteRow(
    session: SqlSession,
    table: CodebookTable,
    key: String,
  ): Result<Int> =
    session.execute("DELETE FROM ${table.tableName} WHERE ${table.keyColumn} = ?") { stmt ->
      stmt.setString(1, key)
    }

  /**
   * Every `ipeds_regions` row, in published-code order — the read the boundary's
   * codebook lookup is built from (RFC 147 D45).
   *
   * It returns [NewIpedsRegion], the same shape the write path takes, because
   * the table has no surrogate key and no column the reader is not entitled to:
   * a separate read type would be the identical five fields under a second name.
   */
  fun ipedsRegions(session: SqlSession): Result<List<NewIpedsRegion>> =
    session.queryList(
      "SELECT slug, code, name, label_raw FROM ipeds_regions ORDER BY code",
      bind = {},
      map = { rs ->
        NewIpedsRegion(
          slug = rs.getString("slug"),
          code = rs.getInt("code"),
          name = rs.getString("name"),
          labelRaw = rs.getString("label_raw"),
        )
      },
    )

  /** Every `nces_locales` row, in published-code order. See [ipedsRegions]. */
  fun ncesLocales(session: SqlSession): Result<List<NewNcesLocale>> =
    session.queryList(
      "SELECT slug, code, type, detail, name, label_raw FROM nces_locales ORDER BY code",
      bind = {},
      map = { rs ->
        NewNcesLocale(
          slug = rs.getString("slug"),
          code = rs.getInt("code"),
          type = rs.getString("type"),
          detail = rs.getString("detail"),
          name = rs.getString("name"),
          labelRaw = rs.getString("label_raw"),
        )
      },
    )

  /** The row count of [table], for the loader's per-domain report. */
  fun rowCount(
    session: SqlSession,
    table: CodebookTable,
  ): Result<Int> =
    session.queryOne(
      "SELECT count(*) AS n FROM ${table.tableName}",
      bind = {},
      map = { rs -> rs.getInt("n") },
    )

  // ---------------------------------------------------------------------------
  // Reads over the columns that STORE codes (D46)
  // ---------------------------------------------------------------------------

  /**
   * Every distinct value stored in [column], as text, with how many rows carry
   * it. NULLs are excluded: absence is not an unknown code.
   *
   * Text, not Int, because the codebooks are keyed by three different things —
   * a smallint code, a two-letter postal code, a six-digit CIP string — and one
   * comparison type keeps the caller from needing three near-identical reports.
   * A `smallint[]` column ([CodeColumn.isArray]) is unnested first, so an
   * institution belonging to three associations contributes three values.
   */
  fun storedCodeCounts(
    session: SqlSession,
    column: CodeColumn,
  ): Result<Map<String, Int>> {
    require(column in CODE_COLUMNS) {
      "storedCodeCounts: unknown code column [$column]; allowed: $CODE_COLUMNS"
    }
    val value = if (column.isArray) "unnest(${column.column})" else column.column
    return session
      .queryList(
        """
        SELECT value::text AS value, count(*) AS n
        FROM (SELECT $value AS value FROM ${column.table}) AS stored
        WHERE value IS NOT NULL
        GROUP BY value
        """.trimIndent(),
        bind = {},
        map = { rs -> rs.getString("value") to rs.getInt("n") },
      ).map { it.toMap() }
  }

  /**
   * The closed set of columns that store a published code (RFC 144/0015's raw-code
   * columns). SQL has no identifier binding, so this set IS the boundary:
   * anything outside it never becomes SQL text. It is also the loader's
   * reference map — the columns a codebook row can be REFERENCED by, and so the
   * columns both the delete refusal and the unknown-code report consult.
   */
  val CODE_COLUMNS: Set<CodeColumn> = CodeColumns.ALL
}

/**
 * The eleven code-storing columns, each NAMED once (RFC 147).
 *
 * They are constants rather than a set of literals because two places need
 * them: [CodebooksDao.CODE_COLUMNS], the SQL identifier allowlist, and
 * `CodebookLoader.Domain.references`, which says which column speaks which
 * codebook. Those were separately-written literals that happened to agree, with
 * a test comparing their SIZES; naming each one gives them a single home, so a
 * column cannot exist in the allowlist and be missing from the reference map.
 *
 * `:db` cannot see `:college`, which is why the constants live on this side.
 */
object CodeColumns {
  val COLLEGES_REGION = CodeColumn("colleges", "region")
  val COLLEGES_STATE = CodeColumn("colleges", "state")
  val COLLEGES_LOCALE = CodeColumn("colleges", "locale")
  val COLLEGE_IPEDS_CARNEGIE_BASIC = CodeColumn("college_ipeds", "carnegie_basic")
  val COLLEGE_IPEDS_CARNEGIE_SIZE = CodeColumn("college_ipeds", "carnegie_size")
  val COLLEGE_IPEDS_REL_AFFIL = CodeColumn("college_ipeds", "rel_affil")

  /** `smallint[]`: one institution belongs to several associations. */
  val COLLEGE_IPEDS_ATHLETIC_ASSOC = CodeColumn("college_ipeds", "athletic_assoc", isArray = true)
  val COLLEGE_IPEDS_FOOTBALL_CONF = CodeColumn("college_ipeds", "football_conf")
  val COLLEGE_IPEDS_TEST_POLICY = CodeColumn("college_ipeds", "test_policy")
  val COLLEGE_PROGRAMS_CENSUS_CIP_CODE = CodeColumn("college_programs_census", "cip_code")
  val US_STATES_IPEDS_REGION = CodeColumn("us_states", "ipeds_region")

  val ALL: Set<CodeColumn> =
    setOf(
      COLLEGES_REGION,
      COLLEGES_STATE,
      COLLEGES_LOCALE,
      COLLEGE_IPEDS_CARNEGIE_BASIC,
      COLLEGE_IPEDS_CARNEGIE_SIZE,
      COLLEGE_IPEDS_REL_AFFIL,
      COLLEGE_IPEDS_ATHLETIC_ASSOC,
      COLLEGE_IPEDS_FOOTBALL_CONF,
      COLLEGE_IPEDS_TEST_POLICY,
      COLLEGE_PROGRAMS_CENSUS_CIP_CODE,
      US_STATES_IPEDS_REGION,
    )
}

/**
 * One reference table of the published codebook, named with the column its
 * natural key lives in. The enum IS the identifier allowlist for the two reads
 * that interpolate a table name, and its DECLARATION ORDER is load order:
 * `us_states.ipeds_region` is a real FK, so regions must be upserted before
 * states (and, symmetrically, states deleted before regions).
 */
enum class CodebookTable(
  val tableName: String,
  val keyColumn: String,
  /** False for the two tables whose natural key IS the published code. */
  val hasCodeColumn: Boolean = true,
) {
  IPEDS_REGIONS("ipeds_regions", "slug"),
  US_STATES("us_states", "usps_code", hasCodeColumn = false),
  NCES_LOCALES("nces_locales", "slug"),
  CARNEGIE_2021_BASIC_CLASSES("carnegie_2021_basic_classes", "slug"),
  CARNEGIE_2021_SIZE_SETTINGS("carnegie_2021_size_settings", "slug"),
  RELIGIOUS_AFFILIATIONS("religious_affiliations", "slug"),
  ATHLETIC_ASSOCIATIONS("athletic_associations", "slug"),
  FOOTBALL_CONFERENCES("football_conferences", "slug"),
  ADMISSION_TEST_POLICIES("admission_test_policies", "slug"),
  CIP_CODES("cip_codes", "code", hasCodeColumn = false),
}

/** One stored codebook row: its natural key and its published code (null when the key is the code). */
data class StoredCodebookRow(
  val key: String,
  val code: Int?,
)

/**
 * One column that STORES a published code — a raw-code column of `colleges`,
 * `college_ipeds` or `college_programs_census`, or the codebook's own
 * `us_states.ipeds_region` FK. Instances are DAO constants ([CodebooksDao.CODE_COLUMNS]),
 * never caller data.
 */
data class CodeColumn(
  val table: String,
  val column: String,
  /** True for `smallint[]` columns, which are unnested before counting. */
  val isArray: Boolean = false,
) {
  /** `table.column`, the name every report and refusal message prints. */
  override fun toString(): String = "$table.$column"
}
