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
import ed.unicoach.db.models.College
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
  ): LoadResult {
    withContext(ioDispatcher) {
      assertRequiredColumns(institution, REQUIRED_INSTITUTION_COLUMNS)
      assertRequiredColumns(fields, REQUIRED_FIELDS_COLUMNS)
    }
    return LoadResult(
      colleges = phase("institutions", committedPhases) { loadInstitutions(institution.file) },
      programs = phase("fields", committedPhases) { loadFields(fields.file) },
    )
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
            count.loaded++
            val preVersion = preVersions[newCollege.ipedsUnitId]
            val postVersion = result.getOrThrow().version
            when {
              preVersion == null -> count.inserted++
              postVersion > preVersion -> count.changed++
              else -> count.unchanged++
            }
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
    val nonNullBefore: Map<String, Int>,
    val nonNullAfter: Map<String, Int>,
    val buildId: java.util.UUID,
    val ipeds: IpedsReport? = null,
    /**
     * The CDS seed load's outcome (RFC 148), `null` when the group was not
     * supplied — the same omit-vs-zero distinction [ipeds] draws. The caller
     * renders it; this run only records it.
     */
    val cds: CdsSeedLoader.LoadResult? = null,
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
        }
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
   * OMITS the IPEDS keys entirely rather than writing them as zeros. Its four
   * headers are asserted here, BEFORE the first Scorecard phase, so a bad IPEDS
   * header cannot corrupt a run that has already written Scorecard rows: all
   * seven files are header-asserted up front.
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
    cds: CdsSources? = null,
  ): IngestReport {
    val startedAt = Instant.now()
    // The IPEDS half of the run is ONE nullable value: the source group and the
    // loader that reads it exist together or not at all, so no call site has to
    // reconcile two nullables that a single condition decided.
    val ipedsRun = ipeds?.let { IpedsRun(it, IpedsLoader(database, ioDispatcher)) }
    val aliasEntries = withContext(ioDispatcher) { parseAliases(aliasesFile.file) }
    ipedsRun?.assertHeaders()
    // Beside the IPEDS assertion, not inside the cds phase: all TEN files are
    // header-asserted before the first phase commits, so a renamed column in a
    // seed file can never be discovered after institutions, fields, aliases and
    // the two IPEDS phases have already written rows (RFC 148 D10).
    cds?.let { sources -> withContext(ioDispatcher) { cdsSeedLoader.assertHeaders(sources) } }
    val sources =
      withContext(ioDispatcher) {
        listOf(digest(institution), digest(fields), digest(aliasesFile)) +
          (ipedsRun?.sources?.files?.map { digest(it) } ?: emptyList()) +
          (cds?.files?.map { digest(it) } ?: emptyList())
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
    val scorecard = loadScorecard(institution, fields, committedPhases)
    val aliasResult = phase("aliases", committedPhases) { applyAliases(aliasEntries) }
    val ipedsReport =
      ipedsStart?.let { (run, ipedsNonNullBefore) ->
        IpedsReport(
          surveyYear = run.sources.surveyYear,
          attributes = phase("ipeds", committedPhases) { run.loadAttributes() },
          census = phase("programs-census", committedPhases) { run.loadProgramsCensus() },
          nonNullBefore = ipedsNonNullBefore,
          nonNullAfter = ipedsNonNullCounts(),
        )
      }
    // The CDS seed is a row phase like the others, and it runs BEFORE
    // `name-words` and `provenance` so its counts are provenance rather than a
    // number written after the row that should have carried it (RFC 148 D10).
    // It brings its own single transaction, so a fatal here rolls its three
    // tables back as a unit and — like every other phase failure — reaches no
    // build row at all.
    val cdsResult =
      cds?.let { sources ->
        phase("cds", committedPhases) {
          cdsSeedLoader.load(sources.meritAid.file, sources.admissionFactors.file, sources.deadlines.file)
        }
      }
    // Phase 2 of the two-phase ingest (RFC 146): rows first, derived state
    // second, never per-row triggers. It runs after EVERY row phase — the
    // aliases it splits words from, and the IPEDS phases beside them — and
    // before provenance, because its row count is provenance.
    val nameWords = phase("name-words", committedPhases) { rebuildNameWords() }
    val nonNullAfter = nonNullCounts()
    val finishedAt = Instant.now()

    val buildId =
      phase("provenance", committedPhases) {
        insertBuildRow(
          startedAt = startedAt,
          finishedAt = finishedAt,
          sources = sources,
          rowsIngested = rowsIngestedJson(scorecard.colleges, scorecard.programs, aliasResult, ipedsReport, cdsResult),
          changeSummary =
            changeSummaryJson(nonNullBefore, nonNullAfter, scorecard.colleges.changed + aliasResult.applied, ipedsReport),
          indexRows = nameWords,
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
      nonNullBefore = nonNullBefore,
      nonNullAfter = nonNullAfter,
      buildId = buildId,
      ipeds = ipedsReport,
      cds = cdsResult,
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
    indexRows: Int,
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
            indexRows = indexRows,
            changeSummary = changeSummary,
            methodVersion = METHOD_VERSION,
          ),
        )
      }.getOrThrow()

  /**
   * The IPEDS half of one run: the all-or-nothing source group and the loader
   * that reads it, bound together so [ingest] holds ONE nullable value instead
   * of a nullable group and a nullable loader it has to keep in step.
   */
  private class IpedsRun(
    val sources: IpedsSources,
    private val loader: IpedsLoader,
  ) {
    suspend fun assertHeaders() = loader.assertHeaders(sources)

    suspend fun loadAttributes(): IpedsLoadResult = loader.loadAttributes(sources)

    suspend fun loadProgramsCensus(): CensusLoadResult = loader.loadProgramsCensus(sources)
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
    cds: CdsSeedLoader.LoadResult?,
  ): JsonObject =
    buildJsonObject {
      putJsonObject("colleges") {
        put("seen", colleges.seen)
        put("inserted", colleges.inserted)
        put("changed", colleges.changed)
        put("unchanged", colleges.unchanged)
        put("skipped", colleges.skipsByReason.values.sum())
        // The RFC promises the skip TAXONOMY, not just its total: a run that
        // skipped 200 rows for a missing UNITID and one for a bad CONTROL is a
        // different event from the reverse, and the totals cannot tell them
        // apart. Reasons with no occurrences are omitted, never written as 0.
        putJsonObject("skips_by_reason") {
          for ((kind, count) in skipsByKind(colleges.skipsByReason)) put(kind, count)
        }
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
          put("seen", attributes.seen)
          put("inserted", attributes.inserted)
          put("changed", attributes.changed)
          put("unchanged", attributes.unchanged)
          put("skipped", attributes.skipped)
          put("unmatched_ipeds_unit_ids", attributes.unmatchedIpedsUnitIds)
          putJsonObject("skips_by_reason") {
            for ((kind, count) in skipsByKind(attributes.skipsByReason)) put(kind, count)
          }
        }
        putJsonObject("programs_census") {
          put("survey_year", report.surveyYear)
          put("seen", census.seen)
          // Rows the documented bachelor's-first-major filter kept. seen minus
          // selected is a deliberate exclusion, not a loss, so it is reported
          // as its own number rather than as a skip.
          put("selected", census.selected)
          put("inserted", census.inserted)
          put("changed", census.changed)
          put("unchanged", census.unchanged)
          put("skipped", census.skipped)
          put("unmatched_ipeds_unit_ids", census.unmatchedIpedsUnitIds)
          putJsonObject("skips_by_reason") {
            for ((kind, count) in skipsByKind(census.skipsByReason)) put(kind, count)
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
        }
      }
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

    // Net-price selection is keyed on control: public (control=1) reads the
    // *_PUB column, all else *_PRIV; both blank => null. All net-price columns
    // are EXCLUDED from mechanism-A coercion -- negatives are valid (0022), and
    // the low-income bands go negative most often (aid exceeding cost). The
    // Scorecard PrivacySuppressed/NULL sentinels fall out as null via the
    // toIntOrNull path in intOrNull.
    fun readControlKeyed(base: String): Int? =
      if (control == 1) {
        intOrNull(record, "$base$SUFFIX_PUBLIC")
      } else {
        intOrNull(record, "$base$SUFFIX_PRIVATE")
      }

    val netPricePerYearUsd = readControlKeyed(COL_NET_PRICE_BASE)

    // Income-band net prices (RFC 133): the five household income brackets,
    // read from NPT41_PUB, NPT42_PUB, NPT43_PUB, NPT44_PUB, NPT45_PUB or
    // NPT41_PRIV, NPT42_PRIV, NPT43_PRIV, NPT44_PRIV, NPT45_PRIV per control
    // (full names spelled out so a grep for any column finds this read). The
    // band domain is fixed by the Scorecard schema; anything outside
    // [INCOME_BANDS] is a programming error, failed loudly rather than read as
    // a fabricated column name.
    fun readBandPrice(band: Int): Int? {
      require(band in INCOME_BANDS) { "income band must be in [$INCOME_BANDS], got [$band]" }
      return readControlKeyed("$COL_NET_PRICE_BASE$band")
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
        costOfAttendancePerYearUsd =
          intInDomainOrNull(
            record,
            COL_COSTT4_A,
            0,
            Int.MAX_VALUE,
            "cost_of_attendance_per_year_usd",
            coercions,
          ),
        netPricePerYearUsd = netPricePerYearUsd,
        netPricePerYearIncomeQ1Usd = readBandPrice(1),
        netPricePerYearIncomeQ2Usd = readBandPrice(2),
        netPricePerYearIncomeQ3Usd = readBandPrice(3),
        netPricePerYearIncomeQ4Usd = readBandPrice(4),
        netPricePerYearIncomeQ5Usd = readBandPrice(5),
        tuitionAndFeesInStatePerYearUsd =
          intInDomainOrNull(
            record,
            COL_TUITIONFEE_IN,
            0,
            Int.MAX_VALUE,
            "tuition_and_fees_in_state_per_year_usd",
            coercions,
          ),
        tuitionAndFeesOutOfStatePerYearUsd =
          intInDomainOrNull(record, COL_TUITIONFEE_OUT, 0, Int.MAX_VALUE, "tuition_and_fees_out_of_state_per_year_usd", coercions),
        completionRate150pct4yrShare =
          doubleInDomainOrNull(
            record,
            COL_C150_4,
            RATE_MIN,
            RATE_MAX,
            "completion_rate_150pct_4yr_share",
            coercions,
          ),
        medianEarnings10yAfterEntryUsd =
          intInDomainOrNull(record, COL_MD_EARN_WNE_P10, 0, Int.MAX_VALUE, "median_earnings_10y_after_entry_usd", coercions),
        // median_debt_at_completion_usd is a loan amount: genuinely nonneg, so mechanism A applies
        // like the sibling money fields.
        medianDebtAtCompletionUsd =
          intInDomainOrNull(
            record,
            COL_GRAD_DEBT_MDN,
            0,
            Int.MAX_VALUE,
            "median_debt_at_completion_usd",
            coercions,
          ),
        pellShare = doubleInDomainOrNull(record, COL_PCTPELL, RATE_MIN, RATE_MAX, "pell_share", coercions),
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
     */
    const val METHOD_VERSION = 4

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

    // -------------------------------------------------------------------------
    // CSV column names — the single source of truth (RFC 139): the row mappers
    // read through these constants and the REQUIRED_* assertion lists are
    // BUILT from them below, so adding a read means adding one constant here.
    // [stringOrNull]'s isMapped check backstops the wiring: a read of a column
    // absent from the asserted header fails loudly (see the coverage test in
    // CollegeScorecardLoaderTest).
    // -------------------------------------------------------------------------
    private const val COL_UNITID = "UNITID"
    private const val COL_OPEID = "OPEID"
    private const val COL_INSTNM = "INSTNM"
    private const val COL_CITY = "CITY"
    private const val COL_STABBR = "STABBR"
    private const val COL_REGION = "REGION"
    private const val COL_LOCALE = "LOCALE"
    private const val COL_LATITUDE = "LATITUDE"
    private const val COL_LONGITUDE = "LONGITUDE"
    private const val COL_CONTROL = "CONTROL"
    private const val COL_UGDS = "UGDS"
    private const val COL_ADM_RATE = "ADM_RATE"
    private const val COL_SAT_AVG = "SAT_AVG"
    private const val COL_COSTT4_A = "COSTT4_A"
    private const val COL_TUITIONFEE_IN = "TUITIONFEE_IN"
    private const val COL_TUITIONFEE_OUT = "TUITIONFEE_OUT"
    private const val COL_C150_4 = "C150_4"
    private const val COL_MD_EARN_WNE_P10 = "MD_EARN_WNE_P10"
    private const val COL_GRAD_DEBT_MDN = "GRAD_DEBT_MDN"
    private const val COL_PCTPELL = "PCTPELL"
    private const val COL_INSTURL = "INSTURL"

    /** Control-keyed column suffixes: public institutions read `_PUB`, all else `_PRIV`. */
    private const val SUFFIX_PUBLIC = "_PUB"
    private const val SUFFIX_PRIVATE = "_PRIV"

    /** The control-keyed net-price column bases: overall `NPT4` plus the five income bands. */
    private const val COL_NET_PRICE_BASE = "NPT4"
    private val NET_PRICE_BASES = listOf(COL_NET_PRICE_BASE) + INCOME_BANDS.map { "$COL_NET_PRICE_BASE$it" }

    private const val COL_CIPCODE = "CIPCODE"
    private const val COL_CIPDESC = "CIPDESC"
    private const val COL_CREDLEV = "CREDLEV"

    /**
     * Every institution-file column [mapInstitution] reads — required to EXIST
     * in the header (cells may still be blank). Derived from the constants
     * above (the same names the mapper reads through), so the mapper and this
     * list cannot drift without editing one place.
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
      ) + NET_PRICE_BASES.flatMap { listOf("$it$SUFFIX_PUBLIC", "$it$SUFFIX_PRIVATE") }

    /** Every field-of-study column [mapField] reads; derived like [REQUIRED_INSTITUTION_COLUMNS]. */
    internal val REQUIRED_FIELDS_COLUMNS = listOf(COL_UNITID, COL_CIPCODE, COL_CIPDESC, COL_CREDLEV)

    /**
     * The nullable curated columns whose non-null counts the change summary
     * tracks (RFC 139) — every Scorecard-sourced optional metric on `colleges`.
     */
    internal val NON_NULL_SUMMARY_COLUMNS =
      listOf(
        "opeid",
        "region",
        "locale",
        "latitude",
        "longitude",
        "undergrad_enrollment_headcount",
        "admission_rate_share",
        "sat_average_equivalent_score",
        "cost_of_attendance_per_year_usd",
        "net_price_per_year_usd",
        "net_price_per_year_income_q1_usd",
        "net_price_per_year_income_q2_usd",
        "net_price_per_year_income_q3_usd",
        "net_price_per_year_income_q4_usd",
        "net_price_per_year_income_q5_usd",
        "tuition_and_fees_in_state_per_year_usd",
        "tuition_and_fees_out_of_state_per_year_usd",
        "completion_rate_150pct_4yr_share",
        "median_earnings_10y_after_entry_usd",
        "median_debt_at_completion_usd",
        "pell_share",
        "website",
      )

    // Optional-metric domains, mirrored from the 0015 CHECKs (the DB CHECK is the
    // backstop; this duplication is intentional defense-in-depth). Required-field
    // domains (CONTROL) and key-field domains (CREDENTIAL_LEVEL) are pre-filters,
    // not coercions.
    private const val REGION_MIN = 0
    private const val REGION_MAX = 9
    private const val LOCALE_MIN = 11
    private const val LOCALE_MAX = 43
    private const val RATE_MIN = 0.0
    private const val RATE_MAX = 1.0
    private const val CREDENTIAL_LEVEL_MIN = 1
    private const val CREDENTIAL_LEVEL_MAX = 8
  }
}
