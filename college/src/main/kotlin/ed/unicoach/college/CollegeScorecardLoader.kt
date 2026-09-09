package ed.unicoach.college

import ed.unicoach.college.CsvIngestSupport.assertRequiredColumns
import ed.unicoach.college.CsvIngestSupport.describe
import ed.unicoach.college.CsvIngestSupport.digest
import ed.unicoach.college.CsvIngestSupport.doubleInDomainOrNull
import ed.unicoach.college.CsvIngestSupport.doubleOrNull
import ed.unicoach.college.CsvIngestSupport.intInDomainOrNull
import ed.unicoach.college.CsvIngestSupport.intOrNull
import ed.unicoach.college.CsvIngestSupport.phase
import ed.unicoach.college.CsvIngestSupport.recordUpsertFailure
import ed.unicoach.college.CsvIngestSupport.stringOrNull
import ed.unicoach.college.CsvIngestSupport.upsertWithSavepoint
import ed.unicoach.db.Database
import ed.unicoach.db.dao.CollegeIpedsDao
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.dao.DaoException
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.UpsertOutcome
import ed.unicoach.db.models.College
import ed.unicoach.db.models.MoneySource
import ed.unicoach.db.models.NewCollege
import ed.unicoach.db.models.NewCollegeIndexBuild
import ed.unicoach.db.models.NewCollegeProgram
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.apache.commons.csv.CSVRecord
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant

/**
 * Re-runnable ingester for a version-pinned College Scorecard CSV pair (RFC 67):
 * the institution-level file and the field-of-study file. It upserts on the
 * natural keys (`ipeds_unit_id`; `(college_id, cip_code, credential_level)`) so a
 * re-run re-applies the same snapshot with no duplicates.
 *
 * The load is best-effort over the dataset, not all-or-nothing: a row missing a
 * required field, or whose upsert fails with any [DaoException] (a CHECK
 * violation from dirty source data, or a transient fault — both are swallowed
 * identically because the per-row blast radius is one row), is logged with a
 * bracketed warning and skipped so one corrupt line never loses the rest.
 */
class CollegeScorecardLoader(
  private val database: Database,
  // Blocking file IO (sha256 streaming, header parsing, the aliases read) runs
  // on this dispatcher, never a caller's coroutine thread — the [Database]
  // constructor-injection pattern, overridable in tests.
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
  // The CDS seed half of the run, injected on the [ioDispatcher] precedent
  // above rather than constructed mid-`ingest`: the phase ordering D10 promises
  // is then observable from a test that hands in a loader, instead of only from
  // three real CSVs and a hidden table.
  private val cdsSeedLoader: CdsSeedLoader = CdsSeedLoader(database),
  // The IPEDS SFA staging half (RFC 162), injected like every other phase
  // loader rather than constructed mid-`ingest`: whether the phase RUNS is
  // decided by the nullable `sfa` group, never by whether a loader exists.
  private val sfaLoader: CollegeSfaLoader = CollegeSfaLoader(database, ioDispatcher),
  // The published-codebook half (RFC 147), injected for the same reason as the
  // CDS loader above: the phase ordering is then observable from a test that
  // hands in a loader.
  private val codebookLoader: CodebookLoader = CodebookLoader(database, ioDispatcher),
  // The authored subject taxonomy (RFC 150 D49), injected for the same reason:
  // it loads in its OWN `subjects` phase placed immediately after `codebooks`,
  // because it is validated against exactly the `cip_codes` rows that phase
  // wrote and must not be able to run against a stale CIP vocabulary. That
  // ordering is observable from a test.
  private val subjectLoader: SubjectLoader = SubjectLoader(database, ioDispatcher),
  // The authored money vocabulary (RFC 158 D6), injected like the taxonomy:
  // its `money-vocabulary` phase runs after `subjects`, and P2 makes it a
  // write precondition of the `canonical-money` fill below.
  private val moneyVocabularyLoader: MoneyVocabularyLoader = MoneyVocabularyLoader(database, ioDispatcher),
  // The canonical money fill (RFC 158, P12): a derived-phase-2 rebuild that
  // re-parses the institution CSV with the status-preserving readers, after
  // `search-index` and before `provenance`.
  private val canonicalMoneyLoader: CanonicalMoneyLoader = CanonicalMoneyLoader(database),
  // The IC_AY staging half of the IPEDS group (RFC 161), injected like its six
  // siblings above rather than constructed mid-`ingest`: a loader built inside
  // the run is one no test can substitute, and this one is the write
  // precondition of the canonical fill two phases later.
  private val ipedsChargesLoader: IpedsChargesLoader = IpedsChargesLoader(database, ioDispatcher),
) {
  private val logger = LoggerFactory.getLogger(CollegeScorecardLoader::class.java)

  /**
   * A curated-aliases file whose shape or contents are unusable (RFC 139): a
   * malformed/unknown-key entry, a wrong primitive type, or a duplicate
   * `ipeds_unit_id`. The typed sibling of [MissingSourceColumnsException], and the
   * same contract: thrown before any write, mapped to a non-zero exit by
   * [IngestApplication]. [entryIndex] and [entry] name the offending element
   * when the failure is per-entry; [duplicateIpedsUnitIds] carries every repeated
   * `ipeds_unit_id` when it is the duplicate check.
   */
  class InvalidAliasFileException(
    val fileName: String,
    val detail: String,
    val entryIndex: Int? = null,
    val entry: String? = null,
    val duplicateIpedsUnitIds: List<Int> = emptyList(),
    cause: Throwable? = null,
  ) : RuntimeException(
      "curated aliases file [$fileName] is invalid" +
        (entryIndex?.let { " at entry [$it]" } ?: "") +
        ": $detail" +
        (entry?.let { "; offending entry [$it]" } ?: "") +
        "; nothing was written",
      cause,
    )

  /**
   * The `colleges` codebook reference tables are empty, so no college row can
   * be written (migration 0067). Thrown BEFORE the `institutions` phase writes
   * anything, mapped to a non-zero exit by [IngestApplication].
   *
   * Distinct from the argv refusal of a missing `--codebooks`: that one is a
   * caller who supplied no codebook FILE, this one is a database with no
   * codebook ROWS. Both are actionable and they are not actionable in the same
   * way, so they do not share a message.
   *
   * The message does NOT tell the operator to run the codebooks phase: that
   * phase runs FIRST, `--codebooks` is required at the entry point, and by the
   * time this is thrown it has already run and COMMITTED. What it states is what
   * actually happened — the tables are empty AFTER that load — which points at
   * the codebook FILE ([codebookPath]) rather than at the run. It is thrown
   * outside `phase(...)`, so it carries [committedPhases] itself, the way the
   * PARTIAL INGEST report does; nothing bubbles it for free.
   */
  class EmptyCodebookReferenceTablesException(
    val emptyTables: List<String>,
    val codebookPath: String?,
    val committedPhases: List<String>,
  ) : RuntimeException(emptyReferenceTablesMessage(emptyTables, codebookPath, committedPhases))

  /**
   * The alias-application tally (RFC 139): entries seen, applied, unchanged,
   * and the `ipeds_unit_id`s that matched no college. The unmatched ids are carried
   * by VALUE, not counted: "3 entries were dead" is unactionable, "entries
   * 100654, 166027, 240444 were dead" is the answer, and it survives into the
   * provenance row rather than only into an ephemeral log line.
   */
  data class AliasResult(
    val entries: Int,
    val applied: Int,
    val unchanged: Int,
    val unknownIpedsUnitIds: List<Int>,
  )

  /**
   * Per-file outcome for the institution CSV: the RFC 139 upsert split
   * (inserted / changed / unchanged; `loaded` is their sum by construction),
   * plus the structured breakdown of every skipped row (by [SkipReason]) and
   * every optional cell coerced to NULL (mechanism A, by column name).
   */
  data class CollegeLoadResult(
    val seen: Int,
    val inserted: Int,
    val changed: Int,
    val unchanged: Int,
    val skipsByReason: Map<SkipReason, Int>,
    val fieldsCoercedToNull: Map<String, Int>,
  ) {
    /** Rows that reached the DB — the three upsert outcomes, summed. */
    val loaded: Int get() = inserted + changed + unchanged
    val transientSkips: Int get() = skipsByReason[SkipReason.Transient] ?: 0
  }

  /** Per-file outcome for the field-of-study CSV (no version split: `college_programs` is unversioned). */
  data class ProgramLoadResult(
    val seen: Int,
    val upserted: Int,
    val skipsByReason: Map<SkipReason, Int>,
    val fieldsCoercedToNull: Map<String, Int>,
  ) {
    val transientSkips: Int get() = skipsByReason[SkipReason.Transient] ?: 0
  }

  /**
   * The [load] aggregate: both per-file outcomes, whole. The flat accessors
   * are derived views over the two real results (nothing is defaulted or
   * half-populated); `transientSkips`/`permanentSkips` let a caller tell
   * "retry the ingest" from "this row is permanently corrupt".
   */
  data class LoadResult(
    val colleges: CollegeLoadResult,
    val programs: ProgramLoadResult,
  ) {
    val collegesLoaded: Int get() = colleges.loaded
    val programsLoaded: Int get() = programs.upserted
    val skipsByReason: Map<SkipReason, Int> get() = mergeCounts(colleges.skipsByReason, programs.skipsByReason)
    val fieldsCoercedToNull: Map<String, Int> get() = mergeCounts(colleges.fieldsCoercedToNull, programs.fieldsCoercedToNull)
    val transientSkips: Int get() = skipsByReason[SkipReason.Transient] ?: 0
    val permanentSkips: Int get() = skipsByReason.filterKeys { it != SkipReason.Transient }.values.sum()
  }

  /** The one [LoadCount]→result mapping for the institution file. */
  private fun LoadCount.toCollegesResult(): CollegeLoadResult =
    CollegeLoadResult(
      seen = seen,
      inserted = inserted,
      changed = changed,
      unchanged = unchanged,
      skipsByReason = skipsByReason.toMap(),
      fieldsCoercedToNull = fieldsCoercedToNull.toMap(),
    )

  /** The one [LoadCount]→result mapping for the field-of-study file. */
  private fun LoadCount.toProgramsResult(): ProgramLoadResult =
    ProgramLoadResult(
      seen = seen,
      upserted = loaded,
      skipsByReason = skipsByReason.toMap(),
      fieldsCoercedToNull = fieldsCoercedToNull.toMap(),
    )

  /**
   * Loads [institutionCsv] then [fieldsCsv] — [loadScorecard] without the RFC
   * 139 provenance wrapper (the legacy RFC 67 path, kept for tests and direct
   * loads).
   */
  suspend fun load(
    institutionCsv: File,
    fieldsCsv: File,
  ): LoadResult =
    loadScorecard(
      SourceFile(institutionCsv, institutionCsv.path),
      SourceFile(fieldsCsv, fieldsCsv.path),
      committedPhases = mutableListOf(),
      codebookPath = null,
    )

  /**
   * The one per-file load primitive both [load] and [ingest] run: header
   * assertions for both files (FATAL before any write — a column this loader
   * reads that is absent from the CSV would otherwise silently load as NULL
   * across the whole table, RFC 139), then institutions, then fields.
   * Institutions load first so a program can resolve its owning college by
   * `UNITID`; a program referencing an unknown institution is skipped with a
   * warning.
   */
  private suspend fun loadScorecard(
    institution: SourceFile,
    fields: SourceFile,
    committedPhases: MutableList<String>,
    // The codebook this run loaded, or null for the legacy no-codebook call
    // above. Carried only so the precondition below can NAME the file whose
    // load left the reference tables empty.
    codebookPath: String?,
  ): LoadResult {
    withContext(ioDispatcher) {
      assertRequiredColumns(institution, REQUIRED_INSTITUTION_COLUMNS)
      assertRequiredColumns(fields, REQUIRED_FIELDS_COLUMNS)
    }
    assertCodebookReferenceTablesLoaded(codebookPath, committedPhases)
    return LoadResult(
      colleges = phase("institutions", committedPhases) { loadInstitutions(institution.file) },
      programs = phase("fields", committedPhases) { loadFields(fields.file) },
    )
  }

  /**
   * The one check that stands between an empty codebook and a wall of raw
   * foreign-key violations.
   *
   * Migration 0067 pointed `colleges.state` at `us_states (usps_code)` and
   * `colleges.locale` at `nces_locales (code)`, which makes a loaded codebook a
   * PRECONDITION of writing any college row rather than merely the phase that
   * runs first. Left to the database, that precondition is reported once per
   * row, from the middle of the `institutions` phase, as
   * `colleges_state_codebook_fkey`, over a transaction that has already written
   * thousands of colleges — a message that names a constraint and not a
   * remedy.
   *
   * So it is checked HERE: two `EXISTS` reads, before the phase writes anything
   * at all, naming the empty tables and the remedy. It is deliberately NOT the
   * same failure as a missing `--codebooks` argument, which
   * [IngestApplication] refuses at argv parse: that one says a CALLER passed no
   * codebook, this one says the DATABASE has none — which happens to a migrated
   * database that has never been ingested, and to a test that truncated the
   * reference tables.
   */
  private suspend fun assertCodebookReferenceTablesLoaded(
    codebookPath: String?,
    committedPhases: List<String>,
  ) {
    val empty =
      database.withConnection { session ->
        REFERENCE_TABLES.filter { table ->
          session.prepareStatement("SELECT NOT EXISTS (SELECT 1 FROM $table)").use { stmt ->
            stmt.executeQuery().use { rs ->
              rs.next()
              rs.getBoolean(1)
            }
          }
        }
      }
    if (empty.isNotEmpty()) {
      throw EmptyCodebookReferenceTablesException(empty, codebookPath, committedPhases.toList())
    }
  }

  private suspend fun loadInstitutions(file: File): CollegeLoadResult =
    database.withConnection { session ->
      val count = LoadCount()
      // Pre-load versions keyed by ipeds_unit_id so each upsert outcome can be split
      // into inserted (absent before) / changed (version advanced) / unchanged
      // (version held) for the provenance build row (RFC 139). ~6k rows.
      val preVersions = CollegesDao.currentVersionsByIpedsUnitId(session).getOrThrow()
      parseCsv(file).use { records ->
        for (record in records) {
          count.seen++
          val mapped =
            when (val mapResult = mapInstitution(record)) {
              is MapResult.Skipped -> {
                count.recordSkip(mapResult.reason)
                continue
              }

              is MapResult.Mapped -> {
                mapResult
              }
            }
          val newCollege = mapped.value
          // Coercions are tallied for every mapped row (mechanism A nulled a cell
          // and did not skip), independent of the later upsert outcome.
          count.recordCoercions(mapped.coercions)
          val result = upsertWithSavepoint(session) { CollegesDao.upsert(session, newCollege) }
          if (result.isFailure) {
            val error = result.exceptionOrNull()
            recordUpsertFailure(count, error, "institution", "ipeds_unit_id", newCollege.ipedsUnitId, record.recordNumber)
          } else {
            // `colleges` is VERSIONED, so the three-way outcome is read off
            // the version rather than reported by the DAO -- decided here, then
            // tallied by the one shared accumulator every other loader uses.
            val preVersion = preVersions[newCollege.ipedsUnitId]
            val postVersion = result.getOrThrow().version
            count.recordOutcome(
              when {
                preVersion == null -> UpsertOutcome.INSERTED
                postVersion > preVersion -> UpsertOutcome.CHANGED
                else -> UpsertOutcome.UNCHANGED
              },
            )
          }
        }
      }
      logSummary("institutions", count)
      count.toCollegesResult()
    }

  private suspend fun loadFields(file: File): ProgramLoadResult =
    database.withConnection { session ->
      val count = LoadCount()
      parseCsv(file).use { records ->
        for (record in records) {
          count.seen++
          // mapField is pure CSV validation (sentinels, missing/key fields, the
          // CREDLEV domain). The owning-college resolution is a DB read, kept in
          // the loop: it runs before the savepoint, so the transaction is
          // unaborted and the read is always valid.
          val fields =
            when (val mapResult = mapField(record)) {
              is MapResult.Skipped -> {
                count.recordSkip(mapResult.reason)
                continue
              }

              is MapResult.Mapped -> {
                mapResult.value
              }
            }

          val college = resolveCollege(count, session, fields, record) ?: continue

          val program =
            NewCollegeProgram(
              collegeId = college.id,
              cipCode = fields.cipCode,
              cipTitle = fields.cipTitle,
              credentialLevel = fields.credentialLevel,
            )
          val result = upsertWithSavepoint(session) { CollegesDao.upsertProgram(session, program) }
          if (result.isFailure) {
            val error = result.exceptionOrNull()
            recordUpsertFailure(count, error, "program", "cip_code", program.cipCode, record.recordNumber)
          } else {
            count.loaded++
          }
        }
      }
      logSummary("fields", count)
      count.toProgramsResult()
    }

  // ---------------------------------------------------------------------------
  // Full ingest run (RFC 139): header assertions, provenance digests, the
  // Scorecard load, curated aliases, the per-column change summary, and the
  // college_index_build row — everything bin/ingest-colleges does past argv.
  // Lives here rather than in IngestApplication's main() so the whole run is
  // testable; main() stays a thin argv/exit-code shell.
  // ---------------------------------------------------------------------------

  /**
   * The IPEDS half of one [ingest] run (RFC 144), present only when the
   * optional all-or-nothing IPEDS group was supplied. ONE grouped value rather
   * than five nullable slots on [IngestReport]: "the group was omitted" is then
   * a single fact, and no reader can hold an attributes result without its
   * survey year, its census sibling, or its non-null snapshots.
   */
  data class IpedsReport(
    val surveyYear: Int,
    val attributes: IpedsLoadResult,
    val census: CensusLoadResult,
    /** The RFC 161 `ipeds-charges` staging phase, part of the same all-or-nothing group. */
    val charges: IpedsChargesLoadResult,
    val nonNullBefore: Map<String, Int>,
    val nonNullAfter: Map<String, Int>,
  )

  /**
   * The structured outcome of one successful [ingest] run. [ipeds] is nullable
   * on purpose (RFC 144's omit-vs-zero discipline): `null` means the optional
   * IPEDS group was not supplied, which is a different fact from "it was
   * supplied and loaded nothing".
   */
  data class IngestReport(
    val startedAt: Instant,
    val finishedAt: Instant,
    val sources: List<SourceDigest>,
    val colleges: CollegeLoadResult,
    val programs: ProgramLoadResult,
    val aliases: AliasResult,
    /** Rows the `name-words` phase wrote to `college_name_words` (RFC 146). */
    val nameWords: Int,
    /** Rows the `search-index` phase wrote to `college_search_index` (RFC 150). */
    val searchIndex: Int,
    val nonNullBefore: Map<String, Int>,
    val nonNullAfter: Map<String, Int>,
    val buildId: java.util.UUID,
    val ipeds: IpedsReport? = null,
    /**
     * The SFA staging load's outcome (RFC 162), `null` when that group was not
     * supplied -- the same omit-vs-zero distinction [ipeds] draws.
     */
    val sfa: SfaLoadResult? = null,
    /**
     * The CDS seed load's outcome (RFC 148), `null` when the group was not
     * supplied — the same omit-vs-zero distinction [ipeds] draws. The caller
     * renders it; this run only records it.
     */
    val cds: CdsSeedLoader.LoadResult? = null,
    /**
     * The published-codebook load (RFC 147) and D46's unknown-code report, both
     * `null` when no codebook source was supplied — the same omit-vs-zero
     * distinction [ipeds] and [cds] draw.
     */
    val codebooks: CodebookLoader.LoadResult? = null,
    /**
     * The authored subject taxonomy load (RFC 150), `null` when no
     * `--subjects` source was supplied — the same omit-vs-zero distinction
     * every other optional group draws.
     */
    val subjects: SubjectLoader.LoadResult? = null,
    /**
     * The authored money vocabulary load (RFC 158), `null` when no
     * `--money-vocabulary` source was supplied -- the same omit-vs-zero
     * distinction the other authored inputs draw.
     */
    val moneyVocabulary: MoneyVocabularyLoader.LoadResult? = null,
    /**
     * The `canonical-money` rebuild (RFC 158, P12). NOT nullable: the phase
     * runs in every ingest -- the loaded vocabulary TABLES are its
     * precondition, not this run's vocabulary file.
     */
    val canonicalMoney: CanonicalMoneyLoader.FillResult,
    val unknownCodes: CodebookLoader.UnknownCodeReport? = null,
  ) {
    /**
     * Version bumps this run caused: changed Scorecard rows plus applied alias
     * entries. The IPEDS phases are NOT a third term — they write only the two
     * unversioned RFC 144 reference tables, never `colleges`.
     */
    val versionBumps: Int get() = colleges.changed + aliases.applied

    /**
     * The human summary `bin/ingest-colleges` prints (RFC 139). An unchanged
     * snapshot re-ingest prints `0 changed` loudly — the silent no-op becomes a
     * visible no-op.
     */
    fun humanSummary(): String =
      buildString {
        // Sub-second precision on purpose: whole seconds floor, so every fast
        // run printed "0s" — "nothing happened" in the very line RFC 139 added
        // to make a no-op visible.
        val elapsedMillis =
          java.time.Duration
            .between(startedAt, finishedAt)
            .toMillis()
        appendLine("ingest complete in %.1fs".format(elapsedMillis / MILLIS_PER_SECOND))
        val collegesSkipped = colleges.skipsByReason.values.sum()
        appendLine(
          "colleges: ${colleges.seen} seen — ${colleges.inserted} inserted, " +
            "${colleges.changed} changed, ${colleges.unchanged} unchanged, " +
            "$collegesSkipped skipped ($versionBumps version bumps incl. aliases)",
        )
        val programsSkipped = programs.skipsByReason.values.sum()
        appendLine(
          "programs: ${programs.seen} seen — ${programs.upserted} upserted, " +
            "$programsSkipped skipped (${summarizeSkips(programs.skipsByReason)})",
        )
        val unknownIds =
          if (aliases.unknownIpedsUnitIds.isEmpty()) "" else " ${aliases.unknownIpedsUnitIds}"
        appendLine(
          "aliases:  ${aliases.entries} entries — ${aliases.applied} applied, " +
            "${aliases.unchanged} unchanged, ${aliases.unknownIpedsUnitIds.size} unknown ipeds_unit_id$unknownIds",
        )
        appendLine("name words: $nameWords rows")
        val deltas =
          nonNullBefore.keys.joinToString(", ") { column ->
            "$column ${nonNullBefore[column]}→${nonNullAfter[column]}"
          }
        appendLine("non-null deltas: $deltas")
        // The IPEDS lines appear only when the optional group was supplied: a
        // Scorecard-only run must not print a fabricated "0 ipeds rows".
        ipeds?.let { report ->
          val attributes = report.attributes
          val census = report.census
          appendLine(
            "ipeds:    ${attributes.seen} seen — ${attributes.inserted} inserted, " +
              "${attributes.changed} changed, ${attributes.unchanged} unchanged, " +
              "${attributes.skipped} skipped (${attributes.unmatchedIpedsUnitIds} unmatched ipeds_unit_id, " +
              "survey year ${report.surveyYear})",
          )
          appendLine(
            "programs-census: ${census.seen} seen — ${census.selected} bachelor's first majors, " +
              "${census.inserted} inserted, ${census.changed} changed, ${census.unchanged} unchanged, " +
              "${census.skipped} skipped (${census.unmatchedIpedsUnitIds} unmatched ipeds_unit_id)",
          )
          val charges = report.charges
          // Records and ROWS are different units and are both named: one IC_AY
          // record stages exactly 48 charge rows, and IC_AY covers only the
          // academic-year charge reporters (3,825 of ~6,100 institutions), so
          // the unmatched count is COVERAGE and must not read as data loss.
          appendLine(
            "ipeds-charges: [${charges.seen}] records seen — [${charges.loaded}] rows " +
              "([${charges.inserted}] inserted, [${charges.changed}] changed, " +
              "[${charges.unchanged}] unchanged), [${charges.pruned}] rows pruned, " +
              "[${charges.skipped}] records skipped " +
              "([${charges.unmatchedIpedsUnitIds}] not in IC_AY's academic-year universe), " +
              // Row failures are a DIFFERENT unit from record skips and are
              // never folded into them: one institution whose 48 rows all fail
              // is 48 row failures, not 48 skipped records.
              "[${charges.rowFailures}] row upserts failed, " +
              // Published codes at the edge; the tally itself is typed.
              "flags [${charges.cellsByFlag.mapKeys { it.key.code }}]",
          )
        }
        // Stale staging is reported only when there IS some: a fill with no
        // drift must not print a reassuring "0" nobody reads (RFC 161).
        if (canonicalMoney.ipedsChargesIgnoredRows > 0) {
          appendLine(
            "canonical-money: [${canonicalMoney.ipedsChargesIgnoredRows}] staged IC_AY row(s) passed " +
              "over as stale [${canonicalMoney.ipedsChargesIgnored.mapKeys { it.key.slug }}] — " +
              "re-run with --ic-ay to prune them",
          )
        }
        // The SFA line, under the same rule: printed only when that group was
        // supplied (RFC 162).
        sfa?.let { result ->
          appendLine(
            "sfa:      [${result.seen}] records seen — [${result.institutionsLoaded}] institutions, " +
              "[${result.cellsWritten}] staged cells, [${result.skipped}] records skipped " +
              "([${result.unmatchedIpedsUnitIds}] not in the colleges universe)",
          )
          // The two published-number losses, printed only when there ARE some
          // (the omit-vs-zero rule the lines above follow). A number that left
          // the system must not be visible only in the log of the loader that
          // dropped it: this is the summary an operator actually reads.
          if (result.valuesDroppedUnderNotApplicable.isNotEmpty() || result.valuesUnreadable.isNotEmpty()) {
            appendLine(
              "          published values dropped: " +
                "[${result.valuesDroppedUnderNotApplicable.values.sum()}] under a not-applicable flag " +
                "[${result.valuesDroppedUnderNotApplicable}], " +
                "[${result.valuesUnreadable.values.sum()}] unreadable [${result.valuesUnreadable}]",
            )
          }
        }
        // Printed only when a codebook source was supplied: a run without one
        // must not print a fabricated "0 domains" (the IPEDS omit-vs-zero rule).
        codebooks?.let {
          appendLine(it.render())
          // The vocabulary the chat tools speak is read ONCE, at process wiring
          // time (RFC 147, `Codebook.load`). An operator who has just loaded the
          // codebooks into a live deployment has not changed what the running
          // rest-server and queue-worker say, and nothing else would tell them.
          appendLine(
            "  NOTE: rest-server and queue-worker read the codebook vocabulary once at startup — " +
              "restart them to pick up this load",
          )
        }
        unknownCodes?.let { appendLine(it.render()) }
        val sourceLine =
          sources.joinToString(", ") { s ->
            "${s.fileName} sha256=${s.sha256.take(SUMMARY_SHA_PREFIX_CHARS)}… (${s.bytes} bytes)"
          }
        appendLine("sources: $sourceLine")
        append("build row: $buildId")
      }

    private fun summarizeSkips(skips: Map<SkipReason, Int>): String =
      if (skips.isEmpty()) "none" else skips.entries.joinToString(", ") { (reason, n) -> "$reason=$n" }
  }

  /**
   * One full ingest run: parse the curated aliases (fatal on a malformed shape
   * or duplicate `ipeds_unit_id`, before any write), assert every source header,
   * digest each source (sha256 + bytes), snapshot per-column non-null counts,
   * run the Scorecard phases, apply the aliases, run the optional IPEDS phases,
   * load the optional CDS seed (RFC 148), rebuild the derived
   * `college_name_words` table (RFC 146), re-snapshot, and finish by inserting
   * the one `college_index_build` provenance row ([METHOD_VERSION]). A failure
   * anywhere throws out of here — success paths only reach the build row. The file phase (parse, digests) runs on
   * [ioDispatcher].
   *
   * [ipeds] is the optional, all-or-nothing IPEDS group (gate-2 D19): given
   * `null` the run behaves exactly as RFC 139's did, and the provenance row
   * OMITS the IPEDS keys entirely rather than writing them as zeros. Its five
   * headers are asserted here, BEFORE the first Scorecard phase, so a bad IPEDS
   * header cannot corrupt a run that has already written Scorecard rows: all
   * eight files are header-asserted up front.
   *
   * [cds] is the optional CDS seed group (RFC 148, D10), and it runs INSIDE the
   * run — before the provenance phase — rather than after it, which is the
   * whole point: RFC 140's load committed after the build row was written, so
   * by construction no build row could ever mention CDS. Its three files are
   * digested beside the others and its counts join `rows_ingested`; omitted, it
   * leaves both keys absent rather than zero, like the IPEDS group. The load
   * keeps its own single transaction ([CdsSeedLoader.load]), so a fatal rolls
   * all three CDS tables back as a unit, and — running before provenance — a
   * failed load still writes no build row.
   */
  suspend fun ingest(
    institution: SourceFile,
    fields: SourceFile,
    aliasesFile: SourceFile,
    ipeds: IpedsSources? = null,
    sfa: SfaSources? = null,
    cds: CdsSources? = null,
    codebooks: SourceFile? = null,
    subjects: SourceFile? = null,
    moneyVocabulary: SourceFile? = null,
  ): IngestReport {
    val startedAt = Instant.now()
    // The IPEDS half of the run is ONE nullable value: the source group and the
    // loader that reads it exist together or not at all, so no call site has to
    // reconcile two nullables that a single condition decided.
    val ipedsRun =
      ipeds?.let { IpedsRun(it, IpedsLoader(database, ioDispatcher), ipedsChargesLoader) }
    val aliasEntries = withContext(ioDispatcher) { parseAliases(aliasesFile.file) }
    // Parsed up front beside the aliases, for the same reason: the codebook is
    // generated repo data, so a malformed one is a review error that must abort
    // before the first phase commits — never after five phases have written rows.
    val parsedCodebooks = codebooks?.let { source -> withContext(ioDispatcher) { codebookLoader.parse(source) } }
    // The taxonomy's FILE half is parsed here beside the codebook, for the same
    // reason and with the same contract: it is authored repo data, so a typo is
    // a review error that must abort before the first phase commits. No
    // `withContext` here, unlike the line above: `SubjectLoader.parse` makes the
    // IO switch itself, so no caller has to know it touches a disk. Its
    // DATABASE half — every prefix matches a real `cip_codes` row — cannot run
    // this early, because the vocabulary it checks against is written by the
    // very phase it loads in (RFC 150 D49).
    val parsedSubjects = subjects?.let { source -> subjectLoader.parse(source) }
    // The money vocabulary's whole validation is file-side (shape + the
    // both-ways enum agreement, RFC 158), so it ALL runs here, before the
    // first phase commits -- authored repo data, the subjects contract.
    val parsedMoneyVocabulary = moneyVocabulary?.let { source -> moneyVocabularyLoader.parse(source) }
    ipedsRun?.assertHeaders()
    // Beside the IPEDS assertion, before the first phase commits: every SFA
    // column the staging loader reads -- each variable AND its computed X twin
    // -- must exist, so a publisher rename is a startup fatal rather than a
    // table-wide column of blanks.
    sfa?.let { sources -> sfaLoader.assertHeaders(sources) }
    // Beside the IPEDS assertion, not inside the cds phase: all TWELVE files are
    // header-asserted before the first phase commits, so a renamed column in a
    // seed file can never be discovered after institutions, fields, aliases and
    // the two IPEDS phases have already written rows (RFC 148 D10).
    cds?.let { sources -> withContext(ioDispatcher) { cdsSeedLoader.assertHeaders(sources) } }
    val sources =
      withContext(ioDispatcher) {
        listOf(digest(institution), digest(fields), digest(aliasesFile)) +
          (ipedsRun?.sources?.files?.map { digest(it) } ?: emptyList()) +
          (sfa?.let { listOf(digest(it.survey)) } ?: emptyList()) +
          (cds?.files?.map { digest(it) } ?: emptyList()) +
          (codebooks?.let { listOf(digest(it)) } ?: emptyList()) +
          (subjects?.let { listOf(digest(it)) } ?: emptyList()) +
          (moneyVocabulary?.let { listOf(digest(it)) } ?: emptyList())
      }

    // Each phase below is its own transaction, so a failure in a later one
    // cannot roll back an earlier one. The tracker turns that into a LOUD
    // report ([PartialIngestException]) naming exactly what committed, instead
    // of a bare stack trace over a partially applied snapshot.
    val committedPhases = mutableListOf<String>()
    val nonNullBefore = nonNullCounts()
    // The IPEDS before-snapshot is bound to the run rather than kept as a sixth
    // nullable: it exists exactly when the group does, so the report below is
    // built from one non-null value and needs no `!!`.
    val ipedsStart = ipedsRun?.let { run -> run to ipedsNonNullCounts() }
    // The codebooks phase runs BEFORE the attribute phases: the reference
    // vocabulary the coded columns are read through should be in place before
    // the run fills them, not after. It is reference data with no dependency on
    // `colleges`, so nothing about it needs the row phases to have run — the ONE
    // part that does, D46's unknown-code report, is deliberately not in this
    // phase but after every row phase, below.
    val codebookResult =
      parsedCodebooks?.let { parsed ->
        phase("codebooks", committedPhases) { codebookLoader.load(parsed) }
      }
    // The taxonomy is its OWN phase, and deliberately NOT a step inside
    // `codebooks` as RFC 150 D49 drafted it: a step would make
    // `CodebookLoader.load` know the taxonomy exists in order to share its
    // transaction. It still runs IMMEDIATELY after `codebooks`, so it is
    // validated against the `cip_codes` rows that phase just wrote and can
    // never run against a stale CIP vocabulary.
    //
    // What the separate phase gives up is the shared rollback: `codebooks` has
    // already committed by the time this runs, so a dead prefix leaves the
    // codebook load in place. That is acceptable because the failure is FATAL
    // either way and reaches no build row, which is what the run is judged by.
    val subjectResult =
      parsedSubjects?.let { parsed ->
        phase("subjects", committedPhases) {
          subjectLoader.load(subjects?.file?.path ?: "subjects.json", parsed)
        }
      }
    // The money vocabulary is its own phase after `subjects` (RFC 158): five
    // authored reference tables the `canonical-money` fill below foreign-keys
    // into (P2's write precondition). Nothing here reads the codebooks or the
    // taxonomy; the position just keeps every authored-reference load together
    // and ahead of the row phases.
    // The sources whose canonical rows THIS run rebuilds: the fill's own,
    // always, plus the Common Data Set exactly when the CDS seed was supplied.
    // A vocabulary retirement may clear those rows and no others -- deleting a
    // source's rows that nothing in this run writes back is silent data loss
    // (the `cds` group is optional; `--money-vocabulary` is not).
    val rebuiltSources =
      CanonicalMoneyLoader.ORDERED_SOURCES + listOfNotNull(MoneySource.COMMON_DATA_SET.takeIf { cds != null })
    val moneyVocabularyResult =
      parsedMoneyVocabulary?.let { parsed ->
        phase("money-vocabulary", committedPhases) {
          moneyVocabularyLoader.load(
            moneyVocabulary?.file?.path ?: "money-vocabulary.json",
            parsed,
            rebuiltSources,
          )
        }
      }
    val scorecard = loadScorecard(institution, fields, committedPhases, codebooks?.file?.path)
    val aliasResult = phase("aliases", committedPhases) { applyAliases(aliasEntries) }
    val ipedsReport =
      ipedsStart?.let { (run, ipedsNonNullBefore) ->
        IpedsReport(
          surveyYear = run.sources.surveyYear,
          attributes = phase("ipeds", committedPhases) { run.loadAttributes() },
          census = phase("programs-census", committedPhases) { run.loadProgramsCensus() },
          // A row phase like its two siblings, and it must precede
          // `canonical-money` below: an ingest-loaded staging table is that
          // fill's WRITE PRECONDITION (RFC 161), exactly as the money
          // vocabulary is.
          charges = phase("ipeds-charges", committedPhases) { run.loadCharges() },
          nonNullBefore = ipedsNonNullBefore,
          nonNullAfter = ipedsNonNullCounts(),
        )
      }
    // The SFA staging phase (RFC 162) is a ROW phase, beside the two IPEDS
    // ones and before every derived phase: the `canonical-money` fill below
    // READS the rows it stages, so it must have committed by then. Given no
    // SFA group the phase does not run at all AND the fill is handed no group,
    // so it reads nothing -- the omit-vs-zero rule the other groups follow.
    // (`college_sfa` is only ever emptied by this phase, so "the fill finds an
    // empty staging table" would be false: an earlier run's file would still
    // be standing there. What stops it being read is the argument, not the
    // table.)
    val sfaResult =
      sfa?.let { sources ->
        phase("sfa", committedPhases) {
          when (val outcome = sfaLoader.load(sources)) {
            is SfaLoadOutcome.Staged -> {
              outcome.result
            }

            // A vocabulary change in the source: the phase decides this input
            // state is fatal, on a typed value the loader returned -- and it
            // stays typed. An `error(...)` here would flatten three named
            // fields into one sentence a caller can only regex, one line after
            // the loader took care to return them structured.
            is SfaLoadOutcome.UnknownImputationFlags -> {
              throw UnknownImputationFlagException(outcome.flags)
            }
          }
        }
      }
    // The CDS seed is a row phase like the others, and it runs BEFORE
    // `name-words` and `provenance` so its counts are provenance rather than a
    // number written after the row that should have carried it (RFC 148 D10).
    // It brings its own single transaction, so a fatal here rolls its four
    // files back as a unit and — like every other phase failure — reaches no
    // build row at all.
    val cdsResult =
      cds?.let { sources ->
        phase("cds", committedPhases) {
          cdsSeedLoader.load(
            sources.meritAid.file,
            sources.admissionFactors.file,
            sources.deadlines.file,
            sources.aidPolicy.file,
          )
        }
      }
    // Phase 2 of the two-phase ingest (RFC 146): rows first, derived state
    // second, never per-row triggers. It runs after EVERY row phase — the
    // aliases it splits words from, and the IPEDS phases beside them — and
    // before provenance, because its row count is provenance.
    val nameWords = phase("name-words", committedPhases) { rebuildNameWords() }
    // The second derived rebuild of phase 2 (RFC 158, P12): a wholesale
    // DELETE + re-fill of `price_figures` and `cohort_money_stats` from a
    // re-parse of the pinned institution CSV with the status-preserving
    // readers, in its own transaction, after every row phase and before
    // `provenance` -- because its row counts and per-status breakdown ARE
    // provenance (P11). It runs whether or not this run supplied the
    // vocabulary file: the vocabulary TABLES are the precondition (P2), and
    // an empty one fails the fill loudly at the foreign keys.
    //
    // It runs BEFORE `search-index`, and that order is now load-bearing rather
    // than incidental (RFC 169): the index materialises each college's
    // published on-campus total by summing `price_figures`, so an index built
    // ahead of the canonical fill would carry the PREVIOUS run's prices, and on
    // a first ingest would carry none at all -- a search silently ranking a
    // whole corpus as "no published price". The reproducibility pin in
    // `CollegeScorecardIngestTest` is what would catch a swap back: with the
    // old order, two identical ingests produced two different indexes.
    val canonicalMoney = phase("canonical-money", committedPhases) { canonicalMoneyLoader.fill(institution.file, sfa) }
    // The third derived rebuild of phase 2 (RFC 150 D47), between
    // `canonical-money` and the read-only unknown-code report: it reads
    // `colleges`, `college_ipeds`, `college_programs_census`, `subjects` and now
    // `price_figures` after EVERY row phase and the canonical money fill have
    // committed, and before `provenance`, because its row count IS provenance.
    val searchIndex = phase("search-index", committedPhases) { rebuildSearchIndex() }
    // D46's report, and the reason it is here rather than inside the codebooks
    // phase: it counts the codes stored in `colleges`/`college_ipeds`/
    // `college_programs_census`, so it must read the snapshot THIS run just
    // loaded. It writes nothing and never fails the run — an unexplained code
    // is a finding, not a defect (`colleges.locale`'s range check stays a range
    // check until RFC 148).
    //
    // "Never fails the run" is ENFORCED here, not merely intended: the report is
    // read-only, runs outside `phase(...)`, and runs after every phase has
    // committed, so a transient DB fault inside it would otherwise exit the
    // ingest non-zero on a run that fully succeeded — and without the
    // PartialIngestException that would explain it. A failed report is a WARN
    // and a missing report, which is what a finding-not-a-defect deserves.
    val unknownCodes =
      codebookResult?.let {
        runCatching { codebookLoader.reportUnknownCodes() }
          .onFailure { error ->
            logger.warn(
              "Codebook coverage report failed [{}]: the ingest itself is complete and committed",
              error.toString(),
            )
          }.getOrNull()
      }
    val nonNullAfter = nonNullCounts()
    val finishedAt = Instant.now()

    val buildId =
      phase("provenance", committedPhases) {
        insertBuildRow(
          startedAt = startedAt,
          finishedAt = finishedAt,
          sources = sources,
          rowsIngested =
            rowsIngestedJson(scorecard.colleges, scorecard.programs, aliasResult, ipedsReport, sfaResult, cdsResult),
          changeSummary =
            changeSummaryJson(nonNullBefore, nonNullAfter, scorecard.colleges.changed + aliasResult.applied, ipedsReport),
          nameWordsRows = nameWords,
          searchIndexRows = searchIndex,
          canonicalMoney = canonicalMoney,
          cds = cdsResult,
        )
      }

    return IngestReport(
      startedAt = startedAt,
      finishedAt = finishedAt,
      sources = sources,
      colleges = scorecard.colleges,
      programs = scorecard.programs,
      aliases = aliasResult,
      nameWords = nameWords,
      searchIndex = searchIndex,
      nonNullBefore = nonNullBefore,
      nonNullAfter = nonNullAfter,
      buildId = buildId,
      ipeds = ipedsReport,
      sfa = sfaResult,
      cds = cdsResult,
      codebooks = codebookResult,
      subjects = subjectResult,
      moneyVocabulary = moneyVocabularyResult,
      canonicalMoney = canonicalMoney,
      unknownCodes = unknownCodes,
    )
  }

  /**
   * The one `college_index_build` row of a run ([METHOD_VERSION]), written in
   * its own transaction like every other phase. Extracted so the provenance
   * step in [ingest] reads at the same altitude as `aliases`, `cds` and
   * `name-words`: the payloads are built by their own named functions, and this
   * only writes them.
   */
  private suspend fun insertBuildRow(
    startedAt: Instant,
    finishedAt: Instant,
    sources: List<SourceDigest>,
    rowsIngested: JsonObject,
    changeSummary: JsonObject,
    nameWordsRows: Int,
    searchIndexRows: Int,
    canonicalMoney: CanonicalMoneyLoader.FillResult,
    cds: CdsSeedLoader.LoadResult?,
  ): java.util.UUID =
    database
      .withConnection { session ->
        CollegesDao.insertIndexBuild(
          session,
          NewCollegeIndexBuild(
            startedAt = startedAt,
            finishedAt = finishedAt,
            sources = sourcesJson(sources),
            rowsIngested = rowsIngested,
            nameWordsRows = nameWordsRows,
            searchIndexRows = searchIndexRows,
            priceFigureRows = canonicalMoney.priceFigureRows,
            cohortMoneyStatRows = canonicalMoney.cohortMoneyStatRows,
            cohortPopulationCountRows = canonicalMoney.cohortPopulationCountRows,
            aidFormRequirementRows = cds?.aidPolicy?.aidFormRequirements,
            canonicalMoneySummary = canonicalMoneySummaryJson(canonicalMoney),
            changeSummary = changeSummary,
            methodVersion = METHOD_VERSION,
          ),
        )
      }.getOrThrow()

  /**
   * The per-status breakdown per canonical table (RFC 158, P11). Keys are OUR
   * vocabulary slugs -- table names at the top, status slugs inside -- so
   * risk 6.6's frozen-key seam does not widen; statuses with no rows are
   * omitted, never written as 0 (the skips_by_reason discipline).
   */
  private fun canonicalMoneySummaryJson(canonicalMoney: CanonicalMoneyLoader.FillResult): JsonObject =
    buildJsonObject {
      putJsonObject("price_figures") {
        for ((slug, n) in canonicalMoney.priceFigureStatusCounts.mapKeys { it.key.value }.toSortedMap()) put(slug, n)
      }
      putJsonObject("cohort_money_stats") {
        for ((slug, n) in canonicalMoney.cohortMoneyStatStatusCounts.mapKeys { it.key.value }.toSortedMap()) put(slug, n)
      }
      putJsonObject("cohort_population_counts") {
        for ((slug, n) in canonicalMoney.cohortPopulationCountStatusCounts.mapKeys { it.key.value }.toSortedMap()) put(slug, n)
      }
      // The upstream-wins split (RFC 161), under the same rules as the status
      // blocks above: OUR source slugs as keys, and a source that won no rows
      // is OMITTED rather than written as 0 -- "the Scorecard filled nothing"
      // and "the Scorecard was not consulted" are different facts.
      putJsonObject("price_figures_by_source") {
        for ((slug, n) in canonicalMoney.priceFigureSourceCounts.mapKeys { it.key.value }.toSortedMap()) put(slug, n)
      }
      // The SFA source's own contribution to the fill (RFC 162), beside the
      // per-table breakdown rather than inside it: "how many staged cells this
      // fill read, over how many colleges" is a fact about the SOURCE, and a
      // zero here on a run whose staging table is empty is the honest answer,
      // not an omission.
      putJsonObject("sources") {
        putJsonObject(MoneySource.IPEDS_SFA.value) {
          put("cells_read", canonicalMoney.sfaCellsRead)
          put("colleges", canonicalMoney.sfaCollegesMatched)
        }
      }
      // Staged IC_AY rows the fill could not map and passed over (RFC 161).
      // Same omit-vs-zero rule: no drift writes an empty object. NESTED, like
      // the status and source blocks above: the drift AXIS is the outer key and
      // the offending value the inner one, so a consumer reads
      // `{"charge_variable": {"CHG2AT": 1}}` instead of splitting a packed key
      // on `=`. The enums render to their slugs here, at the edge, and nowhere
      // earlier.
      putJsonObject("ipeds_charges_ignored") {
        for ((drift, counts) in canonicalMoney.ipedsChargesIgnored) {
          putJsonObject(drift.slug) {
            for ((value, n) in counts) put(value, n)
          }
        }
      }
    }

  /**
   * The IPEDS half of one run: the all-or-nothing source group and the loader
   * that reads it, bound together so [ingest] holds ONE nullable value instead
   * of a nullable group and a nullable loader it has to keep in step.
   */
  private class IpedsRun(
    val sources: IpedsSources,
    private val loader: IpedsLoader,
    private val chargesLoader: IpedsChargesLoader,
  ) {
    suspend fun assertHeaders() {
      loader.assertHeaders(sources)
      // The survey year travels with the file: IC_AY's `0`-`3` suffix is a
      // POSITION in the file's own window, so a year the vocabulary does not
      // decode must be refused here, before any phase commits (RFC 161).
      chargesLoader.assertHeaders(sources.icAy, sources.surveyYear)
    }

    suspend fun loadAttributes(): IpedsLoadResult = loader.loadAttributes(sources)

    suspend fun loadProgramsCensus(): CensusLoadResult = loader.loadProgramsCensus(sources)

    suspend fun loadCharges(): IpedsChargesLoadResult = chargesLoader.load(sources.icAy, sources.surveyYear)
  }

  /** One curated alias entry from db/data/college-aliases.json. */
  internal data class AliasEntry(
    val ipedsUnitId: Int,
    val aliases: List<String>,
  )

  /**
   * Parses the curated aliases JSON (`[{ "ipeds_unit_id": N, "aliases": [...] }]`).
   * The file is repo data, not external source data — a malformed shape is a
   * programming/review error and fails the run loudly (unlike an unknown
   * `ipeds_unit_id`, which is counted and reported, never fatal).
   *
   * Every layer is verified rather than cast through: the root must be an
   * array, each element an object carrying EXACTLY [ALIAS_ENTRY_KEYS] (a
   * mistyped `"alises"` is rejected, never silently dropped), `ipeds_unit_id` an
   * integer, and every alias a JSON string. Each failure — including a
   * kotlinx parse error on the file itself — is raised as an
   * [InvalidAliasFileException] naming the file and, where the fault is
   * per-entry, the entry index and the offending element verbatim.
   */
  internal fun parseAliases(file: File): List<AliasEntry> {
    val root =
      try {
        Json.parseToJsonElement(file.readText())
      } catch (e: SerializationException) {
        throw InvalidAliasFileException(file.path, "not valid JSON (${e.message})", cause = e)
      }
    val array = root as? JsonArray ?: throw InvalidAliasFileException(file.path, "the top level must be a JSON array")
    val entries = array.mapIndexed { index, element -> parseAliasEntry(file, index, element) }
    // A duplicate ipeds_unit_id is FATAL like the header assertion: applying both
    // entries would mean last-writer-wins by file order — a silent editing
    // mistake in curated repo data, aborted before anything is written.
    val duplicates =
      entries
        .groupingBy { it.ipedsUnitId }
        .eachCount()
        .filterValues { it > 1 }
        .keys
        .sorted()
    if (duplicates.isNotEmpty()) {
      throw InvalidAliasFileException(
        file.path,
        "duplicate ipeds_unit_id entr" + (if (duplicates.size == 1) "y" else "ies") + " $duplicates",
        duplicateIpedsUnitIds = duplicates,
      )
    }
    return entries
  }

  /** One entry of the curated aliases array, verified layer by layer (see [parseAliases]). */
  private fun parseAliasEntry(
    file: File,
    index: Int,
    element: JsonElement,
  ): AliasEntry {
    fun invalid(detail: String): Nothing =
      throw InvalidAliasFileException(file.path, detail, entryIndex = index, entry = element.toString())

    val obj = element as? JsonObject ?: invalid("an entry must be a JSON object")
    if (obj.keys != ALIAS_ENTRY_KEYS) {
      val unknown = obj.keys - ALIAS_ENTRY_KEYS
      val missing = ALIAS_ENTRY_KEYS - obj.keys
      invalid(
        "an entry's keys must be exactly ${ALIAS_ENTRY_KEYS.sorted()}" +
          (if (unknown.isEmpty()) "" else "; unknown key(s) ${unknown.sorted()}") +
          (if (missing.isEmpty()) "" else "; missing key(s) ${missing.sorted()}"),
      )
    }
    val ipedsUnitId =
      (obj.getValue("ipeds_unit_id") as? JsonPrimitive)
        // kotlinx models `1` and `"1"` as the same JsonPrimitive class, differing
        // only in isString. Rejecting the quoted form keeps the curated file
        // honestly typed: `"ipeds_unit_id": "110100"` is a mistake we refuse loudly
        // rather than coerce, since a typo'd quote would otherwise sail through
        // toIntOrNull and silently alias the wrong school.
        ?.takeIf { !it.isString }
        ?.content
        ?.toIntOrNull()
        ?: invalid("ipeds_unit_id must be a JSON integer")
    val aliasArray = obj.getValue("aliases") as? JsonArray ?: invalid("aliases must be a JSON array")
    val aliases =
      aliasArray.map { alias ->
        val primitive = alias as? JsonPrimitive
        if (primitive == null || !primitive.isString) invalid("every alias must be a JSON string; got [$alias]")
        primitive.content
      }
    return AliasEntry(ipedsUnitId = ipedsUnitId, aliases = aliases)
  }

  /**
   * Applies the curated alias entries after the Scorecard upsert phase, change-
   * suppressed per row ([CollegesDao.updateAliases]): an unchanged alias set
   * writes nothing and bumps nothing. Unknown `ipeds_unit_id`s are counted and
   * reported, never fatal; a real DB failure aborts the run (no build row).
   */
  private suspend fun applyAliases(entries: List<AliasEntry>): AliasResult =
    database.withConnection { session ->
      var applied = 0
      var unchanged = 0
      val unknown = mutableListOf<Int>()
      for (entry in entries) {
        val outcome =
          CollegesDao.updateAliases(session, entry.ipedsUnitId, entry.aliases).getOrElse { error ->
            // The DAO's message is generic by design; the entry that was being
            // applied is the context a fixer needs, so it travels with the throw.
            throw IllegalStateException(
              "applying curated aliases failed for [ipeds_unit_id=${entry.ipedsUnitId}] " +
                "[aliases=${entry.aliases}] ${describe(error)}",
              error,
            )
          }
        when (outcome) {
          CollegesDao.AliasUpdateOutcome.APPLIED -> {
            applied++
          }

          CollegesDao.AliasUpdateOutcome.UNCHANGED -> {
            unchanged++
          }

          CollegesDao.AliasUpdateOutcome.UNKNOWN_IPEDS_UNIT_ID -> {
            unknown += entry.ipedsUnitId
            logger.warn("alias entry references an unknown [ipeds_unit_id={}]; skipped", entry.ipedsUnitId)
          }
        }
      }
      AliasResult(
        entries = entries.size,
        applied = applied,
        unchanged = unchanged,
        unknownIpedsUnitIds = unknown.toList(),
      )
    }

  /**
   * Rebuilds the derived `college_name_words` table in its own transaction
   * ([CollegesDao.rebuildNameWords]) and returns the rows written — the
   * `name-words` phase (RFC 146). Wholesale, so a name or alias that changed
   * this run and one that was deleted are both handled by construction.
   */
  private suspend fun rebuildNameWords(): Int =
    database.withConnection { session ->
      CollegesDao.rebuildNameWords(session).getOrThrow()
    }

  /**
   * Rebuilds the derived `college_search_index` in its own transaction
   * ([CollegesDao.rebuildSearchIndex]) and returns the rows written — the
   * `search-index` phase (RFC 150). Wholesale for [rebuildNameWords]'s reason:
   * a college that changed this run, one that was deleted, and a subject
   * taxonomy that was edited are all handled by construction.
   */
  private suspend fun rebuildSearchIndex(): Int =
    database.withConnection { session ->
      val rows = CollegesDao.rebuildSearchIndex(session).getOrThrow()
      // RFC 147 D46's rule applied to the one code the rebuild maps SILENTLY:
      // an `HD.SECTOR` value InstitutionSector does not name becomes a NULL
      // `sector`, which keeps the college searchable and reports the gap
      // nowhere. Counted and said out loud here, where the phase runs. Its
      // sibling, an unmapped `control`, is a hard failure inside the DAO —
      // there is no honest NULL to degrade to.
      val unmappedSectors = CollegesDao.unmappedSectorCodes(session).getOrThrow()
      if (unmappedSectors.isNotEmpty()) {
        logger.warn(
          "search-index: college_ipeds.sector carries {} code(s) InstitutionSector does not name: {}; " +
            "those colleges are indexed with a NULL sector and are NOT excluded as administrative units",
          unmappedSectors.size,
          unmappedSectors.entries.joinToString(", ") { (code, n) -> "[$code] on $n row(s)" },
        )
      }
      rows
    }

  /** Non-null counts for every nullable curated column on `colleges` (the change-summary axis). */
  private suspend fun nonNullCounts(): Map<String, Int> =
    database.withConnection { session ->
      CollegesDao.nonNullCounts(session, NON_NULL_SUMMARY_COLUMNS).getOrThrow()
    }

  /** The same axis over `college_ipeds` (RFC 144); measured only when the IPEDS group was supplied. */
  private suspend fun ipedsNonNullCounts(): Map<String, Int> =
    database.withConnection { session ->
      CollegeIpedsDao.nonNullCounts(session, IpedsLoader.NON_NULL_SUMMARY_COLUMNS).getOrThrow()
    }

  // JSON payload builders for the build row (kotlinx JSON DSL). They return
  // structured JSON; the DAO serializes it at the JDBC edge (`?::jsonb`).

  private fun sourcesJson(sources: List<SourceDigest>): JsonArray =
    buildJsonArray {
      for (s in sources) {
        addJsonObject {
          put("file", s.fileName)
          put("sha256", s.sha256)
          put("bytes", s.bytes)
          put("source_arg", s.sourceArg)
        }
      }
    }

  /** Aggregates payload-carrying [SkipReason]s into their [SkipReason.kind] buckets, sorted for a stable row. */
  private fun skipsByKind(skips: Map<SkipReason, Int>): List<Pair<String, Int>> =
    skips.entries
      .groupBy({ it.key.kind }, { it.value })
      .map { (kind, counts) -> kind to counts.sum() }
      .sortedBy { it.first }

  private fun rowsIngestedJson(
    colleges: CollegeLoadResult,
    programs: ProgramLoadResult,
    aliases: AliasResult,
    ipeds: IpedsReport?,
    sfa: SfaLoadResult?,
    cds: CdsSeedLoader.LoadResult?,
  ): JsonObject =
    buildJsonObject {
      putJsonObject("colleges") {
        putPhaseCounts(colleges.seen, colleges.inserted, colleges.changed, colleges.unchanged, colleges.skipsByReason)
      }
      putJsonObject("programs") {
        put("seen", programs.seen)
        put("upserted", programs.upserted)
        put("skipped", programs.skipsByReason.values.sum())
        putJsonObject("skips_by_reason") {
          for ((kind, count) in skipsByKind(programs.skipsByReason)) put(kind, count)
        }
      }
      putJsonObject("aliases") {
        put("entries", aliases.entries)
        put("applied", aliases.applied)
        put("unchanged", aliases.unchanged)
        // The ids themselves, not a count: which entries were dead is the
        // question this row is read to answer.
        putJsonArray("unknown_ipeds_unit_id") { aliases.unknownIpedsUnitIds.forEach { add(it) } }
      }
      // Omit-vs-zero (RFC 144), the same discipline as skips_by_reason above:
      // an ABSENT key means the IPEDS group was not supplied, a PRESENT key
      // with zeros means it was supplied and changed nothing. Writing zeros for
      // a run that never read an IPEDS file would report a measurement nobody
      // took.
      ipeds?.let { report ->
        val attributes = report.attributes
        val census = report.census
        putJsonObject("ipeds") {
          put("survey_year", report.surveyYear)
          putPhaseCounts(
            attributes.seen,
            attributes.inserted,
            attributes.changed,
            attributes.unchanged,
            attributes.skipsByReason,
          )
          put("unmatched_ipeds_unit_ids", attributes.unmatchedIpedsUnitIds)
        }
        putJsonObject("ipeds_charges") {
          put("survey_year", report.surveyYear)
          // `seen` counts IC_AY RECORDS; the four row counts count staged
          // charge ROWS. One record makes 48 of them, so the two units
          // are named apart rather than fused into one ambiguous number.
          putPhaseCounts(
            report.charges.seen,
            report.charges.inserted,
            report.charges.changed,
            report.charges.unchanged,
            report.charges.skipsByReason,
          )
          put("rows", report.charges.loaded)
          // Rows this file no longer carries, deleted: a departed institution
          // or a superseded survey-year window (RFC 161).
          put("pruned", report.charges.pruned)
          put("unmatched_ipeds_unit_ids", report.charges.unmatchedIpedsUnitIds)
          // A per-ROW upsert failure is its own loss axis: `skipped` counts
          // records, this counts charge rows, and one is 48 of the other.
          put("row_failures", report.charges.rowFailures)
          putJsonObject("row_failures_by_reason") {
            for ((kind, count) in skipsByKind(report.charges.rowFailuresByReason)) put(kind, count)
          }
          // The raw published X-code distribution over every loaded cell. A
          // code with no cells is OMITTED, never written as 0 -- the same
          // omit-vs-zero rule the group itself follows.
          putJsonObject("cells_by_flag") {
            for ((flag, count) in report.charges.cellsByFlag) put(flag.code, count)
          }
        }
        putJsonObject("programs_census") {
          put("survey_year", report.surveyYear)
          putPhaseCounts(census.seen, census.inserted, census.changed, census.unchanged, census.skipsByReason)
          // Rows the documented bachelor's-first-major filter kept. seen minus
          // selected is a deliberate exclusion, not a loss, so it is reported
          // as its own number rather than as a skip.
          put("selected", census.selected)
          put("unmatched_ipeds_unit_ids", census.unmatchedIpedsUnitIds)
        }
      }
      // The SFA staging load (RFC 162), under the same omit-vs-zero rule.
      sfa?.let { result ->
        putJsonObject("sfa") {
          put("seen", result.seen)
          put("institutions", result.institutionsLoaded)
          put("cells", result.cellsWritten)
          put("skipped", result.skipped)
          put("unmatched_ipeds_unit_ids", result.unmatchedIpedsUnitIds)
          putJsonObject("skips_by_reason") {
            for ((kind, count) in skipsByKind(result.skipsByReason)) put(kind, count)
          }
          // The two published-number loss classes, by variable. Provenance is
          // where a loss that is not a skipped ROW has to be recoverable from:
          // a run whose log has rotated away is otherwise a run in which a
          // number silently left the system.
          putJsonObject("values_dropped_under_not_applicable") {
            for ((variable, n) in result.valuesDroppedUnderNotApplicable.toSortedMap()) put(variable, n)
          }
          putJsonObject("values_unreadable") {
            for ((variable, n) in result.valuesUnreadable.toSortedMap()) put(variable, n)
          }
        }
      }
      // The CDS seed (RFC 148), under the same omit-vs-zero rule: no `cds` key
      // at all means the seed was not part of this run, which is a different
      // fact from a run that loaded it and changed nothing.
      cds?.let { result ->
        putJsonObject("cds") {
          for ((table, summary) in result.tableSummaries) {
            putJsonObject(table.wireKey) { putCdsTable(summary) }
          }
          putJsonObject(CdsSeedLoader.Table.AID_POLICY.wireKey) { putCdsAidPolicy(result.aidPolicy) }
        }
      }
    }

  /**
   * The five counts every row phase reports, plus the skip taxonomy under
   * them: the block `colleges`, `ipeds`, `ipeds_charges` and `programs_census`
   * each used to spell out by hand. Written once, on the JSON builder, exactly
   * as [putCdsTable] is -- a phase that added a count to its own copy was the
   * only thing keeping the four blocks from being read the same way.
   *
   * Reasons with no occurrences are OMITTED, never written as 0: a run that
   * skipped 200 rows for a missing UNITID and one for a bad CONTROL is a
   * different event from the reverse, and the totals cannot tell them apart.
   * Keys a phase does not share -- `survey_year`, `selected`, `pruned`, the
   * unmatched ids -- stay at the call site, because they are not counts every
   * phase has.
   */
  private fun JsonObjectBuilder.putPhaseCounts(
    seen: Int,
    inserted: Int,
    changed: Int,
    unchanged: Int,
    skips: Map<SkipReason, Int>,
  ) {
    put("seen", seen)
    put("inserted", inserted)
    put("changed", changed)
    put("unchanged", unchanged)
    put("skipped", skips.values.sum())
    putJsonObject("skips_by_reason") {
      for ((kind, count) in skipsByKind(skips)) put(kind, count)
    }
  }

  /**
   * The aid-policy rebuild, per DESTINATION table (RFC 170). It has no
   * upserted/changed/unchanged split to report, because it is not an upsert:
   * the Common Data Set's share of three canonical tables is deleted and
   * rebuilt in one transaction, so the row count IS the disposition.
   */
  private fun JsonObjectBuilder.putCdsAidPolicy(summary: CdsSeedLoader.AidPolicySummary) {
    put("cohort_money_stat_rows", summary.cohortMoneyStats)
    put("cohort_population_count_rows", summary.cohortPopulationCounts)
    put("aid_form_requirement_rows", summary.aidFormRequirements)
    put("skipped", summary.skipped)
    putJsonArray("unmatched_ipeds_unit_ids") { summary.unmatchedIpedsUnitIds.forEach { add(it) } }
  }

  /**
   * One CDS table's counts. The unmatched UNITIDs are written as their
   * IDENTITIES, not a count: which seed schools our snapshot lacks is the
   * question this row is read to answer, exactly as `unknown_ipeds_unit_id` is for
   * aliases.
   */
  private fun JsonObjectBuilder.putCdsTable(summary: CdsSeedLoader.TableSummary) {
    put("upserted", summary.upserted)
    put("changed", summary.changed)
    put("unchanged", summary.unchanged)
    put("skipped", summary.skipped)
    putJsonArray("unmatched_ipeds_unit_ids") { summary.unmatchedIpedsUnitIds.forEach { add(it) } }
  }

  private fun changeSummaryJson(
    before: Map<String, Int>,
    after: Map<String, Int>,
    versionBumps: Int,
    ipeds: IpedsReport?,
  ): JsonObject =
    buildJsonObject {
      putJsonObject("non_null") {
        for (column in NON_NULL_SUMMARY_COLUMNS) {
          putJsonObject(column) {
            // A count this run did not measure is OMITTED, never written as 0:
            // a provenance row must not report an absent measurement as a real
            // one. Both maps are keyed by NON_NULL_SUMMARY_COLUMNS today, so an
            // omission means the snapshot itself was short.
            before[column]?.let { put("before", it) }
            after[column]?.let { put("after", it) }
          }
        }
        // The `colleges` columns sit at the top of `non_null` for RFC 139
        // compatibility; the IPEDS axis is nested under its own table name (RFC
        // 144) and is absent entirely when the group was not supplied.
        ipeds?.let { report ->
          putJsonObject("college_ipeds") {
            for (column in IpedsLoader.NON_NULL_SUMMARY_COLUMNS) {
              putJsonObject(column) {
                report.nonNullBefore[column]?.let { put("before", it) }
                report.nonNullAfter[column]?.let { put("after", it) }
              }
            }
          }
        }
      }
      put("version_bumps", versionBumps)
    }

  /**
   * Resolves the owning college for a field-of-study row. [CollegesDao.findByIpedsUnitId]
   * returns success(null) for the genuine "no owning college" case and failure(e)
   * only for a real DB fault — both are absorbed here, returning null to signal
   * "skip this row": a DB fault is classified + logged like any upsert failure
   * (never mislabeled NoCollegeForIpedsUnitId), a missing college is the counted
   * [SkipReason.NoCollegeForIpedsUnitId]. The read runs before the savepoint, so the
   * transaction is unaborted and the read is always valid.
   */
  private fun resolveCollege(
    count: LoadCount,
    session: SqlSession,
    fields: ProgramFields,
    record: CSVRecord,
  ): College? {
    val lookup = CollegesDao.findByIpedsUnitId(session, fields.ipedsUnitId)
    if (lookup.isFailure) {
      recordUpsertFailure(count, lookup.exceptionOrNull(), "program", "ipeds_unit_id", fields.ipedsUnitId, record.recordNumber)
      return null
    }
    val college = lookup.getOrThrow()
    if (college == null) {
      count.recordSkip(SkipReason.NoCollegeForIpedsUnitId)
      logger.debug(
        "Skipping program row [line={}]: no college for [ipeds_unit_id={}]",
        record.recordNumber,
        fields.ipedsUnitId,
      )
      return null
    }
    return college
  }

  private fun logSummary(
    file: String,
    count: LoadCount,
  ) {
    logger.info(
      "Load summary [{}]: [loaded={}] [skips={}] [coercions={}]",
      file,
      count.loaded,
      count.skipsByReason,
      count.fieldsCoercedToNull,
    )
  }

  // ---------------------------------------------------------------------------
  // Row mapping — PURE: validate one CSV row to a [MapResult]. A mapper neither
  // reads the DB nor mutates the [LoadCount] accumulator; it returns the value to
  // upsert (plus the cells it coerced to NULL) or the precise skip reason, and the
  // load loop folds that into the accumulator. Skip/coercion DEBUG logs still fire
  // here, once per dropped row / coerced cell, so the audit trail is unchanged.
  // ---------------------------------------------------------------------------

  private fun mapInstitution(record: CSVRecord): MapResult<NewCollege> {
    val ipedsUnitId = intOrNull(record, COL_UNITID)
    val name = stringOrNull(record, COL_INSTNM)
    val city = stringOrNull(record, COL_CITY)
    val state = stringOrNull(record, COL_STABBR)
    val control = intOrNull(record, COL_CONTROL)

    if (ipedsUnitId == null || name == null || city == null || state == null || control == null) {
      val missing =
        buildList {
          if (ipedsUnitId == null) add("ipeds_unit_id")
          if (name == null) add("name")
          if (city == null) add("city")
          if (state == null) add("state")
          if (control == null) add("control")
        }
      logger.debug(
        "Skipping institution row [line={}]: missing required field {} " +
          "[ipeds_unit_id={}] [name={}] [city={}] [state={}] [control={}]",
        record.recordNumber,
        missing,
        ipedsUnitId,
        name,
        city,
        state,
        control,
      )
      return MapResult.Skipped(SkipReason.MissingRequiredField(missing))
    }

    val coercions = mutableMapOf<String, Int>()
    val college =
      NewCollege(
        ipedsUnitId = ipedsUnitId,
        // OPEID is the 8-digit OPE ID (column 2). The loader historically read the
        // nonexistent OPEID8, so opeid always loaded null.
        opeid = stringOrNull(record, COL_OPEID),
        name = name,
        city = city,
        state = state,
        region = intInDomainOrNull(record, COL_REGION, REGION_MIN, REGION_MAX, "region", coercions),
        locale = intInDomainOrNull(record, COL_LOCALE, LOCALE_MIN, LOCALE_MAX, "locale", coercions),
        latitude = doubleOrNull(record, COL_LATITUDE),
        longitude = doubleOrNull(record, COL_LONGITUDE),
        control = control,
        undergradEnrollmentHeadcount =
          intInDomainOrNull(record, COL_UGDS, 0, Int.MAX_VALUE, "undergrad_enrollment_headcount", coercions),
        admissionRateShare = doubleInDomainOrNull(record, COL_ADM_RATE, RATE_MIN, RATE_MAX, "admission_rate_share", coercions),
        satAverageEquivalentScore = intInDomainOrNull(record, COL_SAT_AVG, 0, Int.MAX_VALUE, "sat_average_equivalent_score", coercions),
        completionRate150pct4yrShare =
          doubleInDomainOrNull(
            record,
            COL_C150_4,
            RATE_MIN,
            RATE_MAX,
            "completion_rate_150pct_4yr_share",
            coercions,
          ),
        website = stringOrNull(record, COL_INSTURL),
      )
    return MapResult.Mapped(college, coercions)
  }

  /** The validated key/required columns of a field-of-study row, resolved to a
   * [NewCollegeProgram] by the load loop once its owning college is found. */
  private data class ProgramFields(
    val ipedsUnitId: Int,
    val cipCode: String,
    val cipTitle: String,
    val credentialLevel: Int,
  )

  private fun mapField(record: CSVRecord): MapResult<ProgramFields> {
    // Mechanism B: known source sentinels in required/key columns are skipped
    // under their own precise bucket before any DB work, never silently.
    if (stringOrNull(record, COL_UNITID) == "NA") {
      logger.debug("Skipping program row [line={}]: UNITID=NA sentinel", record.recordNumber)
      return MapResult.Skipped(SkipReason.IpedsUnitIdNa)
    }

    val ipedsUnitId = intOrNull(record, COL_UNITID)
    val cipCode = stringOrNull(record, COL_CIPCODE)
    val cipTitle = stringOrNull(record, COL_CIPDESC)
    val credentialLevel = intOrNull(record, COL_CREDLEV)

    if (ipedsUnitId == null || cipCode == null || cipTitle == null || credentialLevel == null) {
      val missing =
        buildList {
          if (ipedsUnitId == null) add("ipeds_unit_id")
          if (cipCode == null) add("cip_code")
          if (cipTitle == null) add("cip_title")
          if (credentialLevel == null) add("credential_level")
        }
      logger.debug(
        "Skipping program row [line={}]: missing required field {} " +
          "[ipeds_unit_id={}] [cip_code={}] [cip_title={}] [credential_level={}]",
        record.recordNumber,
        missing,
        ipedsUnitId,
        cipCode,
        cipTitle,
        credentialLevel,
      )
      return MapResult.Skipped(SkipReason.MissingRequiredField(missing))
    }

    // Mechanism B: CREDLEV=99 ("Non-Credential Program") and any other
    // out-of-domain credential level. credential_level is NOT NULL and part of
    // the upsert key, so it cannot be nulled; pre-filter to a named bucket
    // instead of a generic constraint reject + savepoint round-trip.
    if (credentialLevel !in CREDENTIAL_LEVEL_MIN..CREDENTIAL_LEVEL_MAX) {
      logger.debug(
        "Skipping program row [line={}]: credential_level [{}] out of domain [ipeds_unit_id={}]",
        record.recordNumber,
        credentialLevel,
        ipedsUnitId,
      )
      return MapResult.Skipped(SkipReason.CredentialLevelOutOfDomain)
    }

    return MapResult.Mapped(
      ProgramFields(
        ipedsUnitId = ipedsUnitId,
        cipCode = cipCode,
        cipTitle = cipTitle,
        credentialLevel = credentialLevel,
      ),
      emptyMap(),
    )
  }

  companion object {
    /**
     * `college_index_build.method_version` for this derivation logic
     * (1 = RFC 139, 2 = RFC 144's IPEDS source family, 3 = RFC 146's derived
     * `college_name_words` rebuild, 4 = RFC 148's CDS seed inside the run).
     * Bumped whenever the derivation logic changes, so a build row says which
     * one produced it.
     *
     * RFC 148's prose says "3" because it was written against RFC 144's 2; RFC
     * 146 took 3 first, so the CDS bump is 4 — the number is a sequence, not a
     * literal from the RFC.
     *
     * 5 = RFC 150's derived `college_search_index` rebuild and its subject
     * taxonomy: the derivation is new, so a build row from this ingest is not
     * comparable to one from the last.
     *
     * 6 = RFC 158's canonical money store: the `money-vocabulary` phase and
     * the derived `canonical-money` fill of `price_figures` /
     * `cohort_money_stats`, with their row counts and per-status summary on
     * the build row.
     *
     * 7 = RFC 161's IPEDS IC_AY charges and RFC 162's IPEDS SFA: the
     * `ipeds-charges` and `sfa` staging phases, and TWO more canonical money
     * sources ahead of the Scorecard in `CanonicalMoneyLoader.ORDERED_SOURCES`,
     * plus the new `cohort_population_counts` table. The derivation changes --
     * the same college's `tuition_and_fees` / `in_state` figure can now come
     * from a different publisher with a different number, and a net price can
     * now be the publisher's own rather than the Scorecard's copy of it -- so a
     * build row from this ingest is not comparable to one from the last.
     *
     * 8 = RFC 169's published-price ruler on the search index. The phase ORDER
     * changed -- `canonical-money` now runs before `search-index` -- and the
     * index gained four columns derived from `price_figures`, so the same
     * snapshot produces a different index than version 7 did.
     *
     * 9 = RFC 176's drop of the eighteen publisher money columns from
     * `colleges` (migration `0094`). The institution phase no longer parses or
     * writes any money, and `change_summary`'s `non_null` axis therefore falls
     * from 28 keys to 10. The log is append-only and untouched by the
     * migration, so a version-8 row keeps its 28 keys; this number is what
     * makes a 10-key row a DIFFERENT derivation rather than a truncated one.
     */
    const val METHOD_VERSION = 9

    /** The exact key set one curated alias entry may carry — a surplus key is a typo, never surplus data. */
    private val ALIAS_ENTRY_KEYS = setOf("ipeds_unit_id", "aliases")

    /** Hex characters of each source sha256 shown in the human summary; the full digest is in the build row. */
    private const val SUMMARY_SHA_PREFIX_CHARS = 12

    /** Millis→seconds at the one display boundary that prints an elapsed [java.time.Duration]. */
    private const val MILLIS_PER_SECOND = 1000.0

    private fun <K> mergeCounts(
      a: Map<K, Int>,
      b: Map<K, Int>,
    ): Map<K, Int> {
      val merged = a.toMutableMap()
      for ((key, count) in b) merged.merge(key, count, Int::plus)
      return merged
    }

    // The Scorecard's five NPT4n household-income brackets (RFC 133):
    // 1 = $0-30k, 2 = $30,001-48k, 3 = $48,001-75k, 4 = $75,001-110k, 5 = $110k+.
    private val INCOME_BANDS = 1..5

    /**
     * The two reference tables `colleges` foreign-keys into (0067), checked for
     * emptiness before the `institutions` phase writes. Not every codebook
     * table: only these two constrain a `colleges` row, and a check that
     * demanded the other eight would fail an ingest that is perfectly able to
     * succeed.
     *
     * A hand-kept copy of a fact the SCHEMA owns, so a later migration could add
     * a third codebook foreign key and leave it with no precondition — silently
     * restoring the wall of raw FK violations this check exists to replace.
     * `internal` because `CollegeScorecardIngestTest` asserts it against
     * `pg_constraint` for exactly that reason.
     */
    internal val REFERENCE_TABLES = listOf("us_states", "nces_locales")

    // -------------------------------------------------------------------------
    // CSV column names — the single source of truth (RFC 139): the row mappers
    // read through these constants and the REQUIRED_* assertion lists are
    // BUILT from them below, so adding a read means adding one constant here.
    // The names the canonical-money fill also reads are DERIVED from the
    // shared [ScorecardInstitutionColumns] home (RFC 158), so the two
    // re-parses of the same pinned file cannot drift; the rest are this
    // loader's own. [stringOrNull]'s isMapped check backstops the wiring: a
    // read of a column absent from the asserted header fails loudly (see the
    // coverage test in CollegeScorecardLoaderTest).
    // -------------------------------------------------------------------------

    private const val COL_UNITID = ScorecardInstitutionColumns.UNITID
    private const val COL_OPEID = "OPEID"
    private const val COL_INSTNM = "INSTNM"
    private const val COL_CITY = "CITY"
    private const val COL_STABBR = "STABBR"
    private const val COL_REGION = "REGION"
    private const val COL_LOCALE = "LOCALE"
    private const val COL_LATITUDE = "LATITUDE"
    private const val COL_LONGITUDE = "LONGITUDE"
    private const val COL_CONTROL = ScorecardInstitutionColumns.CONTROL
    private const val COL_UGDS = "UGDS"
    private const val COL_ADM_RATE = "ADM_RATE"
    private const val COL_SAT_AVG = "SAT_AVG"
    private const val COL_COSTT4_A = ScorecardInstitutionColumns.COSTT4_A
    private const val COL_TUITIONFEE_IN = ScorecardInstitutionColumns.TUITIONFEE_IN
    private const val COL_TUITIONFEE_OUT = ScorecardInstitutionColumns.TUITIONFEE_OUT
    private const val COL_C150_4 = "C150_4"
    private const val COL_MD_EARN_WNE_P10 = ScorecardInstitutionColumns.MD_EARN_WNE_P10
    private const val COL_GRAD_DEBT_MDN = ScorecardInstitutionColumns.GRAD_DEBT_MDN
    private const val COL_PCTPELL = ScorecardInstitutionColumns.PCTPELL
    private const val COL_INSTURL = "INSTURL"

    // The six published cost components (RFC 149). Six, not seven: the
    // Scorecard publishes no ROOMBOARD_FAM, so a student living at home has no
    // housing-and-food allowance to read.
    private const val COL_ROOMBOARD_ON = ScorecardInstitutionColumns.ROOMBOARD_ON
    private const val COL_ROOMBOARD_OFF = ScorecardInstitutionColumns.ROOMBOARD_OFF
    private const val COL_BOOKSUPPLY = ScorecardInstitutionColumns.BOOKSUPPLY
    private const val COL_OTHEREXPENSE_ON = ScorecardInstitutionColumns.OTHEREXPENSE_ON
    private const val COL_OTHEREXPENSE_OFF = ScorecardInstitutionColumns.OTHEREXPENSE_OFF
    private const val COL_OTHEREXPENSE_FAM = ScorecardInstitutionColumns.OTHEREXPENSE_FAM

    /** Aliases onto [ScorecardInstitutionColumns], which documents and owns these. */
    private const val SUFFIX_PUBLIC = ScorecardInstitutionColumns.SUFFIX_PUBLIC
    private const val SUFFIX_PRIVATE = ScorecardInstitutionColumns.SUFFIX_PRIVATE

    /** The control-keyed net-price column bases: overall `NPT4` plus the five income bands. */
    private const val COL_NET_PRICE_BASE = ScorecardInstitutionColumns.NET_PRICE_BASE
    private val NET_PRICE_BASES = listOf(COL_NET_PRICE_BASE) + INCOME_BANDS.map { "$COL_NET_PRICE_BASE$it" }

    private const val COL_CIPCODE = "CIPCODE"
    private const val COL_CIPDESC = "CIPDESC"
    private const val COL_CREDLEV = "CREDLEV"

    /**
     * Every institution-file column THIS RUN reads — required to EXIST in the
     * header (cells may still be blank). Derived from the constants above, so
     * the reads and this list cannot drift without editing one place.
     *
     * "This run", not "[mapInstitution]", since RFC 176: the money columns
     * (`COSTT4_A`, both `TUITIONFEE_*`, the six component columns, `PCTPELL`,
     * `GRAD_DEBT_MDN`, `MD_EARN_WNE_P10` and the `NPT4*` family) are no longer
     * read into `NewCollege` — they are read by [CanonicalMoneyLoader], which
     * re-parses THIS SAME FILE in the `canonical-money` phase. This is the one
     * header assertion the run makes on that file, so the names stay: dropping
     * them would move a missing-column failure from one loud check before any
     * write to a silent hole in the canonical fill. These are CSV variable
     * names, never `colleges` column names.
     */
    internal val REQUIRED_INSTITUTION_COLUMNS =
      listOf(
        COL_UNITID,
        COL_OPEID,
        COL_INSTNM,
        COL_CITY,
        COL_STABBR,
        COL_REGION,
        COL_LOCALE,
        COL_LATITUDE,
        COL_LONGITUDE,
        COL_CONTROL,
        COL_UGDS,
        COL_ADM_RATE,
        COL_SAT_AVG,
        COL_COSTT4_A,
        COL_TUITIONFEE_IN,
        COL_TUITIONFEE_OUT,
        COL_C150_4,
        COL_MD_EARN_WNE_P10,
        COL_GRAD_DEBT_MDN,
        COL_PCTPELL,
        COL_INSTURL,
        COL_ROOMBOARD_ON,
        COL_ROOMBOARD_OFF,
        COL_BOOKSUPPLY,
        COL_OTHEREXPENSE_ON,
        COL_OTHEREXPENSE_OFF,
        COL_OTHEREXPENSE_FAM,
      ) + NET_PRICE_BASES.flatMap { listOf("$it$SUFFIX_PUBLIC", "$it$SUFFIX_PRIVATE") }

    /** Every field-of-study column [mapField] reads; derived like [REQUIRED_INSTITUTION_COLUMNS]. */
    internal val REQUIRED_FIELDS_COLUMNS = listOf(COL_UNITID, COL_CIPCODE, COL_CIPDESC, COL_CREDLEV)

    /**
     * The nullable curated columns whose non-null counts the change summary
     * tracks (RFC 139) — every Scorecard-sourced optional metric still ON
     * `colleges`.
     *
     * NOT a second list: it IS [CollegesDao.NON_NULL_COUNTABLE_COLUMNS], the
     * allowlist `nonNullCounts` validates against, on the [IpedsLoader]
     * precedent — so the summary can never ask for a column the DAO would
     * reject, and a countable column added to one side cannot be silently
     * uncounted by the other.
     *
     * Ten keys, not twenty-eight: RFC 176 dropped the eighteen money names
     * with the columns themselves (migration `0094`), and deriving is what
     * made that ONE edit. The money axis of the provenance is
     * `canonical_money_summary` instead, written by the canonical fill from
     * the same CSV.
     *
     * `college_index_build.change_summary` is append-only and the migration
     * does nothing to it, so a build row written before this change keeps its
     * twenty-eight keys and stays readable. [METHOD_VERSION] 9 is what tells
     * the two row shapes apart.
     */
    internal val NON_NULL_SUMMARY_COLUMNS: List<String> = CollegesDao.NON_NULL_COUNTABLE_COLUMNS.toList()

    // Optional-metric domains, mirrored from the 0015 CHECKs (the DB CHECK is the
    // backstop; this duplication is intentional defense-in-depth). Required-field
    // domains (CONTROL) and key-field domains (CREDENTIAL_LEVEL) are pre-filters,
    // not coercions.
    private const val REGION_MIN = 0
    private const val REGION_MAX = 9
    private const val LOCALE_MIN = 11
    private const val LOCALE_MAX = 43
    private const val RATE_MIN = ScorecardInstitutionColumns.RATE_MIN
    private const val RATE_MAX = ScorecardInstitutionColumns.RATE_MAX

    private const val CREDENTIAL_LEVEL_MIN = 1
    private const val CREDENTIAL_LEVEL_MAX = 8
  }
}

/**
 * The message of [CollegeScorecardLoader.EmptyCodebookReferenceTablesException],
 * built here because a `RuntimeException` superclass call cannot hold a `when`.
 *
 * It states the run's actual position rather than a remedy that has already been
 * applied: which tables are empty, which codebook this run loaded (the phase runs
 * first and `--codebooks` is required, so on a real run there IS one), and which
 * phases have already COMMITTED — because this is thrown outside `phase(...)`,
 * after `codebooks` and `subjects` wrote rows, so "nothing was written" would be
 * false.
 */
private fun emptyReferenceTablesMessage(
  emptyTables: List<String>,
  codebookPath: String?,
  committedPhases: List<String>,
): String {
  val cause =
    if (codebookPath != null) {
      "they are STILL empty after this run's `codebooks` phase loaded [$codebookPath], so that file " +
        "publishes no rows for them — look at the codebook, not at the run"
    } else {
      "this run was given no codebook at all (only a direct loader call can do that; the ingest binary " +
        "requires `--codebooks`) and the database has none loaded — re-run through `bin/ingest-colleges`"
    }
  val written =
    if (committedPhases.isEmpty()) {
      "no phase has committed and nothing was written"
    } else {
      "phase(s) $committedPhases have already COMMITTED"
    }
  return "codebook reference table(s) $emptyTables are EMPTY, so no college row can be written: " +
    "`colleges.state` references `us_states (usps_code)` and `colleges.locale` references " +
    "`nces_locales (code)` (migration 0067). $cause. The `institutions` phase was NOT started, " +
    "$written, and no `college_index_build` row was written."
}
