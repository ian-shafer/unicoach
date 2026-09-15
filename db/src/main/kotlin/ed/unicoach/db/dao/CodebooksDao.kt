package ed.unicoach.db.dao

import ed.unicoach.db.models.JurisdictionKind
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
import ed.unicoach.db.models.NewSubject
import ed.unicoach.db.models.NewUsState
import ed.unicoach.db.models.SubjectStatus
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
      VALUES (?, ?, ?, ?, ?, $TEXT_ARRAY_PARAM::smallint[], NOW())
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
        jsonbArrayBinder(row.nullSentinels.map { it.toString() })(stmt, 6)
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

  /**
   * Every slug of [table], in published-code order — the closed WORD LIST a
   * boundary advertises to a model and validates a word against (RFC 150 D54).
   *
   * ONE generic read over the existing [CodebookTable] identifier allowlist,
   * not six typed row readers. After RFC 150 D61 the derived search index
   * stores the SLUG, so what the serving path needs from a codebook is the
   * vocabulary, not the code: nothing on that path resolves a word to a number
   * any more. The two tables whose key is not a slug ([CodebookTable.US_STATES],
   * [CodebookTable.CIP_CODES]) are refused rather than silently returning
   * postal or CIP codes dressed as slugs.
   */
  fun slugs(
    session: SqlSession,
    table: CodebookTable,
  ): Result<List<String>> {
    require(table.keyColumn == "slug") {
      "slugs: [${table.tableName}] is not keyed by a slug (its key is [${table.keyColumn}])"
    }
    // Every slug-keyed table carries a published `code` today, so in practice
    // this is published-code order, as the doc above says. The fallback is for a
    // future slug-keyed table with no published code — slug order is then the
    // only stable one — and it keeps the interpolated identifier a column the
    // table actually has.
    val order = if (table.hasCodeColumn) "code" else table.keyColumn
    return session.queryList(
      "SELECT ${table.keyColumn} AS slug FROM ${table.tableName} ORDER BY $order",
      bind = {},
      map = { rs -> rs.getString("slug") },
    )
  }

  /**
   * Every published `us_states.usps_code`, in postal-code order — the closed
   * vocabulary the `states` filter is resolved against (RFC 150).
   *
   * Its own read rather than a [slugs] call, because [CodebookTable.US_STATES]
   * is one of the two tables `slugs` deliberately refuses: its key is a postal
   * code, not a slug, and returning it as one would be the dressing-up that
   * function exists to prevent.
   */
  fun usStateCodes(session: SqlSession): Result<List<String>> =
    session.queryList(
      "SELECT ${CodebookTable.US_STATES.keyColumn} AS code FROM ${CodebookTable.US_STATES.tableName} ORDER BY 1",
      bind = {},
      map = { rs -> rs.getString("code") },
    )

  /**
   * Every published `us_states` row — postal code, name and jurisdiction kind —
   * in NAME order, the order a person reads a list of states in (RFC 165).
   *
   * Beside [usStateCodes] rather than replacing it: that read is RFC 150's
   * filter vocabulary and needs codes alone, while a served vocabulary needs the
   * words a UI shows and the classification that stops it calling Palau a state.
   * Ordering differs for the same reason — postal-code order is a machine's
   * order, name order is display order.
   */
  fun usStates(session: SqlSession): Result<List<UsState>> =
    session.queryList(
      "SELECT ${CodebookTable.US_STATES.keyColumn} AS code, name, jurisdiction_kind " +
        "FROM ${CodebookTable.US_STATES.tableName} ORDER BY name",
      bind = {},
      map = { rs ->
        val kindValue = rs.getString("jurisdiction_kind")
        UsState(
          code = rs.getString("code"),
          name = rs.getString("name"),
          // A value the CHECK admits but [JurisdictionKind] does not name means
          // the enum and the migration have diverged; say that here rather than
          // hand a caller a kind it cannot interpret.
          jurisdictionKind =
            JurisdictionKind.fromValue(kindValue)
              ?: throw SQLException("us_states row [${rs.getString("code")}] has unknown jurisdiction_kind [$kindValue]"),
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

  // ---------------------------------------------------------------------------
  // The authored subject taxonomy (RFC 150 D49)
  //
  // `subjects` is not a published codebook — it is repo-authored data — but it
  // is reference data loaded by the SAME ingest phase against the SAME
  // vocabulary, so it lives beside the tables it is validated against rather
  // than in a DAO of its own.
  // ---------------------------------------------------------------------------

  /**
   * Upserts one subject on its slug, suppressing an unchanged write exactly as
   * every codebook row does, so a re-load of an unedited `subjects.json` leaves
   * each row byte-identical, `updated_at` included.
   *
   * `status` is written as part of the row, which is how a subject that was
   * DEPRECATED and then reappears in the file goes back to
   * [SubjectStatus.ACTIVE] (D40) — by the ordinary upsert, with no separate
   * un-deprecate path to forget to call. It is also part of the
   * `IS DISTINCT FROM` guard, so a row that is already active and unchanged is
   * still not written: a drop-then-restore cycle leaves the row active with no
   * trace of a state the taxonomy no longer claims.
   *
   * Hand-written rather than routed through [upsertDetectingChange] for the
   * [CollegeIpedsDao.upsert] reason: the primitive binds every column as a bare
   * `?`, and the `cip_prefixes` array needs a value EXPRESSION —
   * [TEXT_ARRAY_PARAM] — around its parameter, fed by a [jsonbArrayBinder].
   */
  fun upsertSubject(
    session: SqlSession,
    row: NewSubject,
  ): Result<UpsertOutcome> {
    val sql =
      """
      WITH up AS (
        INSERT INTO subjects (slug, name, cip_prefixes, status)
        VALUES (?, ?, $TEXT_ARRAY_PARAM, '${SubjectStatus.ACTIVE.value}')
        ON CONFLICT (slug) DO UPDATE SET
          name = EXCLUDED.name,
          cip_prefixes = EXCLUDED.cip_prefixes,
          status = EXCLUDED.status
        WHERE (subjects.name, subjects.cip_prefixes, subjects.status)
          IS DISTINCT FROM (EXCLUDED.name, EXCLUDED.cip_prefixes, EXCLUDED.status)
        RETURNING (xmax = 0) AS inserted
      )
      SELECT inserted FROM up
      UNION ALL
      SELECT NULL::boolean WHERE NOT EXISTS (SELECT 1 FROM up)
      """.trimIndent()
    return session.mutateReturning(
      sql,
      bind = { stmt ->
        stmt.setString(1, row.slug)
        stmt.setString(2, row.name)
        jsonbArrayBinder(row.cipPrefixes)(stmt, 3)
      },
      map = { rs ->
        val inserted = rs.getBoolean("inserted")
        when {
          rs.wasNull() -> UpsertOutcome.UNCHANGED
          inserted -> UpsertOutcome.INSERTED
          else -> UpsertOutcome.CHANGED
        }
      },
      mapError = ::mapCodebookWriteError,
    )
  }

  /**
   * What one [reconcileSubjectsNotIn] did to the slugs the incoming file no
   * longer carries. TWO numbers, not one, because they are two different
   * outcomes and a loader report that says "3 removed" when one of them is still
   * in the table would be wrong.
   */
  data class SubjectReconciliation(
    /** Dropped, referenced by nobody, and therefore GONE — the reference tables stay reproducible from source. */
    val deleted: Int,
    /**
     * Dropped but referenced by at least one student, and therefore KEPT with
     * `status = 'deprecated'`. Counted whether or not this run was the one that
     * flipped the column, so a second reload of the same file reports the same
     * number rather than zero.
     */
    val deprecated: Int,
  )

  /**
   * Reconciles the stored taxonomy against the slugs the incoming file carries:
   * a dropped slug NOBODY references is deleted, a dropped slug a student
   * references is DEPRECATED (D40).
   *
   * This method used to be a bare `DELETE FROM subjects WHERE slug <> ALL (...)`,
   * and its doc comment said a dropped subject needs no reference check because
   * "nothing has a foreign key onto `subjects`". RFC 205 made that false:
   * `intended_subjects.subject_slug` references `subjects(slug)` ON DELETE
   * RESTRICT, so left alone, the first student answer would turn an edit to
   * `subjects.json` into an ingest that cannot run at all.
   *
   * **The reference count is over ALL rows, live and soft-deleted.** The foreign
   * key does not know about `deleted_at`: a student who added a subject and
   * later removed it still holds the reference, and the delete still fails with
   * `violates RESTRICT setting of foreign key constraint
   * intended_subjects_subject_slug_fkey`. Counting only live rows would issue
   * the DELETE anyway and kill the reload on a raw constraint violation —
   * exactly the failure the deprecate path exists to prevent.
   * `intended_subjects_subject_idx` is unpartitioned for this reason.
   *
   * `college_search_index.subject_slugs` is a materialised `slug[]` the very
   * next rebuild recomputes, and the phase order guarantees that rebuild happens
   * in the same run — an upsert without this reconcile would leave a dropped
   * subject matching colleges forever, which is the one way editing the file
   * could make search WORSE. A DEPRECATED subject is excluded from that rebuild
   * for the same reason, so it too stops matching.
   *
   * The keep-list is bound as one jsonb array for the same reason the write
   * binds its arrays that way.
   */
  fun reconcileSubjectsNotIn(
    session: SqlSession,
    keptSlugs: Collection<String>,
  ): Result<SubjectReconciliation> =
    droppedSubjectDependants(session, keptSlugs).mapCatching { dependants ->
      // The CLASSIFICATION stays here and is PASSED to both writes. It has to:
      // the delete's error mapper closes over the dependant counts read BEFORE
      // the write, and a count is not recoverable at all once the transaction
      // has aborted. A helper that re-read it would have nothing to read.
      val toDeprecate = dependants.filterValues { it > 0 }.keys
      val toDelete = dependants.filterValues { it == 0 }.keys
      val deleted = deleteDroppedSubjects(session, toDelete, dependants)
      deprecateDroppedSubjects(session, toDeprecate)
      SubjectReconciliation(deleted = deleted, deprecated = toDeprecate.size)
    }

  /**
   * Deletes the dropped slugs NOBODY references, returning how many rows went.
   *
   * [dependants] is the whole classification this run made before the write, not
   * just the deleted part: [mapSubjectDeleteError] closes over it so a
   * foreign-key violation can name the slug AND say how many students depend on
   * it. Neither is recoverable from the SQLSTATE, and the count is not
   * recoverable at all once the transaction has aborted — which is why it is
   * captured before the write and handed in, rather than read after.
   *
   * Throws the mapped error; the caller runs inside `mapCatching`.
   */
  private fun deleteDroppedSubjects(
    session: SqlSession,
    toDelete: Set<String>,
    dependants: Map<String, Int>,
  ): Int {
    if (toDelete.isEmpty()) return 0
    return session
      .execute("DELETE FROM subjects WHERE slug = ANY ($TEXT_ARRAY_PARAM)") { stmt ->
        jsonbArrayBinder(toDelete)(stmt, 1)
      }.getOrElse { error -> throw mapSubjectDeleteError(error, dependants) }
  }

  /**
   * Marks the dropped slugs a student DOES reference as `deprecated` (D40) —
   * kept, so the answer that references them survives, and offered to nobody.
   *
   * Throws on failure; the caller runs inside `mapCatching`.
   */
  private fun deprecateDroppedSubjects(
    session: SqlSession,
    toDeprecate: Set<String>,
  ) {
    if (toDeprecate.isEmpty()) return
    // `IS DISTINCT FROM` so a slug that is already deprecated is not rewritten:
    // a second reload of the same file must not advance `updated_at` on a row
    // nothing changed about.
    session
      .execute(
        """
        UPDATE subjects SET status = '${SubjectStatus.DEPRECATED.value}'
        WHERE slug = ANY ($TEXT_ARRAY_PARAM)
          AND status IS DISTINCT FROM '${SubjectStatus.DEPRECATED.value}'
        """.trimIndent(),
      ) { stmt -> jsonbArrayBinder(toDeprecate)(stmt, 1) }
      .getOrThrow()
  }

  /**
   * Every stored slug the incoming file no longer carries, mapped to how many
   * `intended_subjects` rows reference it — live AND soft-deleted, because that
   * is what the foreign key counts.
   */
  private fun droppedSubjectDependants(
    session: SqlSession,
    keptSlugs: Collection<String>,
  ): Result<Map<String, Int>> =
    session
      .queryList(
        """
        SELECT s.slug AS slug,
               (SELECT count(*) FROM intended_subjects i WHERE i.subject_slug = s.slug) AS dependants
        FROM subjects s
        WHERE s.slug <> ALL ($TEXT_ARRAY_PARAM)
        """.trimIndent(),
        bind = { stmt -> jsonbArrayBinder(keptSlugs)(stmt, 1) },
        map = { rs -> rs.getString("slug") to rs.getInt("dependants") },
      ).map { it.toMap() }

  /**
   * The subject-delete error path this slice has to close (D40): a `23503`
   * arriving from `subjects` is a student's answer standing in the way of a
   * taxonomy reload, and the operator needs the SLUG and the number of
   * dependants — not a bare SQL state and not [mapCodebookWriteError]'s
   * `us_states.ipeds_region` sentence, which cannot be what happened here.
   *
   * The slug is recovered by matching the server's DETAIL line
   * (`Key (slug)=(nursing) is still referenced ...`) against the slugs
   * [dependants] already names — the authoritative candidate set — rather than
   * by parsing PostgreSQL's prose; [SUBJECT_KEY_DETAIL] is only the fallback
   * for a DETAIL that names none of them. The dependant count comes from
   * [dependants] too, the classification this run already made. Both are
   * handed on as TYPED FIELDS of [SubjectStillReferencedException], which
   * builds the operator's sentence from them — a caller reading the data back
   * out of the prose would be parsing a message, which is not an interface. A
   * slug the DETAIL does not yield leaves the whole classification in the
   * sentence rather than nothing, because a reader with no slug at all has
   * nowhere to start.
   *
   * Reaching this at all means the classification and the write disagreed —
   * a student answered between the two statements — so it is a backstop, not
   * the ordinary path.
   */
  private fun mapSubjectDeleteError(
    error: Throwable,
    dependants: Map<String, Int>,
  ): Throwable {
    val sqlException = error as? SQLException ?: (error.cause as? SQLException) ?: return error
    // BOTH states, and neither is redundant. A plain foreign-key violation is
    // `23503`, but the foreign key this path exists for is ON DELETE RESTRICT,
    // and PostgreSQL raises a RESTRICT violation as its OWN state, `23001`
    // (restrict_violation):
    //   ERROR: update or delete on table "subjects" violates RESTRICT setting
    //          of foreign key constraint "intended_subjects_subject_slug_fkey"
    // Matching only `23503` made this whole named-error path DEAD CODE and
    // handed the operator the bare `DatabaseException` D40 says must not happen.
    // The pair is `FOREIGN_KEY_SQLSTATES` in DaoExceptions.kt, beside the named
    // constants that explain why `23001` is not a duplicate of `23503`.
    if (sqlException.sqlState !in FOREIGN_KEY_SQLSTATES) return error
    val detail: String? = (sqlException as? org.postgresql.util.PSQLException)?.serverErrorMessage?.detail
    // SEARCHED against the candidates this run already classified, not parsed:
    // `dependants` is the authoritative set of slugs the DELETE could have been
    // refused for, so no assumption about how PostgreSQL renders or quotes the
    // key is needed. The regex is only the fallback for a DETAIL none of the
    // known slugs appears in, and it too may yield nothing.
    val slug =
      detail?.let { line ->
        dependants.keys.sorted().firstOrNull { line.contains("($it)") }
          ?: SUBJECT_KEY_DETAIL.find(line)?.groupValues?.get(1)
      }
    // TYPED, not a sentence the caller has to grep: the slug and the counts ride
    // as fields, and the exception builds the operator's sentence from them.
    return SubjectStillReferencedException(
      slug = slug,
      dependants = dependants,
      cause = sqlException,
      constraint = (sqlException as? org.postgresql.util.PSQLException)?.serverErrorMessage?.constraint,
      detail = detail,
    )
  }

  /**
   * The LAST resort for the offending slug: `Key (slug)=(nursing) is referenced
   * from table "intended_subjects".` Only reached when the DETAIL names none of
   * the slugs this run classified, which is why the parse is not the primary
   * route — see [mapSubjectDeleteError].
   */
  private val SUBJECT_KEY_DETAIL = Regex("""Key \(slug\)=\(([^)]*)\)""")

  /**
   * Every ACTIVE `subjects` row in slug order — the taxonomy read the boundary's
   * vocabulary is built from.
   *
   * Deprecated rows are excluded, and the exclusion belongs HERE rather than at
   * each caller, because every caller of this function OFFERS what it returns:
   * the chat tool's subject vocabulary, and the picker. A subject the taxonomy
   * file no longer carries must be offered to nobody (D40) — it is kept only so
   * the student who already chose it can still read its name, and
   * [subjectNames] is the read for that.
   */
  fun subjects(session: SqlSession): Result<List<NewSubject>> =
    session.queryList(
      "SELECT slug, name, cip_prefixes FROM subjects WHERE status = ? ORDER BY slug",
      bind = { stmt -> stmt.setString(1, SubjectStatus.ACTIVE.value) },
      map = { rs ->
        NewSubject(
          slug = rs.getString("slug"),
          name = rs.getString("name"),
          cipPrefixes = rs.getStringList("cip_prefixes"),
        )
      },
    )

  /**
   * The picker's list (RFC 205 D29): ACTIVE subjects whose [nameQuery] matches,
   * in NAME order, at most [limit] of them.
   *
   * Name order, not slug order, because this list is read by a person: [subjects]
   * orders by slug because its caller builds a vocabulary a machine consumes.
   *
   * [nameQuery] is null for "the whole list" — 181 rows is a browsable list and a
   * picker that shows nothing until the student types is a worse picker. A
   * present query matches case-insensitively as a SUBSTRING of the name, and its
   * `%` and `_` are escaped, so a student typing `%` searches for a per-cent sign
   * rather than matching everything.
   *
   * It matches the published names ONLY. No synonym is stored and none is
   * invented (D15): a term that is not a subject comes back as no rows, and the
   * caller refuses it with a reason rather than silently offering something near
   * it.
   */
  fun searchSubjects(
    session: SqlSession,
    nameQuery: String?,
    limit: Int,
  ): Result<List<NewSubject>> =
    session.queryList(
      """
      SELECT slug, name, cip_prefixes FROM subjects
      WHERE status = ?
        AND (CAST(? AS TEXT) IS NULL OR name ILIKE ? ESCAPE '\')
      ORDER BY name, slug
      LIMIT ?
      """.trimIndent(),
      bind = { stmt ->
        stmt.setString(1, SubjectStatus.ACTIVE.value)
        stmt.setStringOrNull(2, nameQuery)
        stmt.setStringOrNull(3, nameQuery?.let { "%${escapeLikePattern(it)}%" })
        stmt.setInt(4, limit)
      },
      map = { rs ->
        NewSubject(
          slug = rs.getString("slug"),
          name = rs.getString("name"),
          cipPrefixes = rs.getStringList("cip_prefixes"),
        )
      },
    )

  /**
   * Slug -> name for [slugs], REGARDLESS of status — the one read that must see
   * a deprecated row.
   *
   * It resolves subjects a student has ALREADY chosen, so it offers nothing and
   * excluding a deprecated row here would blank out the label on an answer the
   * student gave in good faith. A slug with no row at all is simply absent from
   * the map.
   */
  fun subjectNames(
    session: SqlSession,
    slugs: Collection<String>,
  ): Result<Map<String, String>> =
    session
      .queryListWhereIn(
        table = "subjects",
        columns = "slug, name",
        keyColumn = "slug",
        keys = slugs,
        bindKey = { stmt, i, slug -> stmt.setString(i, slug) },
        map = { rs -> rs.getString("slug") to rs.getString("name") },
      ).map { it.toMap() }

  /**
   * The `subjects` row count, for the loader's report: ALL rows, deprecated
   * included, because the report's job is to say what is in the table. The
   * deprecated share of it rides separately in
   * [SubjectReconciliation.deprecated].
   */
  fun subjectCount(session: SqlSession): Result<Int> =
    session.queryOne("SELECT count(*) AS n FROM subjects", bind = {}, map = { rs -> rs.getInt("n") })

  /**
   * The six-digit `cip_codes` keys that start with any of [prefixes] — the read
   * [ed.unicoach.college.SubjectLoader]'s fatal validation is built on (D49).
   *
   * One statement for the whole file rather than one per prefix: a 540-prefix
   * taxonomy is 540 round trips otherwise, and the answer the loader needs is
   * per-prefix, so the prefix it matched rides back beside the code.
   */
  fun cipCodesMatchingPrefixes(
    session: SqlSession,
    prefixes: Collection<String>,
  ): Result<Map<String, Int>> =
    session
      .queryList(
        """
        SELECT p.prefix AS prefix, count(c.code) AS n
        FROM unnest($TEXT_ARRAY_PARAM) AS p(prefix)
        LEFT JOIN cip_codes c ON c.code LIKE p.prefix || '%'
        GROUP BY p.prefix
        """.trimIndent(),
        bind = { stmt -> jsonbArrayBinder(prefixes)(stmt, 1) },
        map = { rs -> rs.getString("prefix") to rs.getInt("n") },
      ).map { it.toMap() }
}

/**
 * The twelve code-storing columns, each NAMED once (RFC 147).
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

  /**
   * The DERIVED copy of `colleges.state`, and a reference in its own right since
   * migration 0067 gave it `college_search_index_state_fkey`.
   *
   * It is listed even though the index is rebuilt wholesale from `colleges`,
   * because the codebooks phase runs BEFORE that rebuild: at the moment a
   * dropped state is deleted the index still holds the STALE value, so a map
   * that named only `colleges.state` would let the delete reach the database and
   * die as a raw foreign-key error instead of the named refusal every other
   * codebook column gets.
   */
  val COLLEGE_SEARCH_INDEX_STATE = CodeColumn("college_search_index", "state")

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
      COLLEGE_SEARCH_INDEX_STATE,
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

/**
 * One published `us_states` row as a reader needs it: the postal code the write
 * paths accept, the state's name, and its authored [JurisdictionKind] (RFC 147).
 * The load-side [ed.unicoach.db.models.NewUsState] carries `ipedsRegion` too;
 * this read type deliberately does not — no reader of the vocabulary asks which
 * IPEDS region a state is in.
 */
data class UsState(
  val code: String,
  val name: String,
  val jurisdictionKind: JurisdictionKind,
)

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
