package ed.unicoach.college

import ed.unicoach.common.util.DataSize
import ed.unicoach.db.Database
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.dao.ConstraintViolationException
import ed.unicoach.db.dao.DaoException
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.College
import ed.unicoach.db.models.NewCollege
import ed.unicoach.db.models.NewCollegeIndexBuild
import ed.unicoach.db.models.NewCollegeProgram
import ed.unicoach.error.PermanentError
import ed.unicoach.error.TransientError
import ed.unicoach.error.errorCategory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
import java.security.MessageDigest
import java.sql.SQLException
import java.time.Instant
import java.util.HexFormat

/**
 * Re-runnable ingester for a version-pinned College Scorecard CSV pair (RFC 67):
 * the institution-level file and the field-of-study file. It upserts on the
 * natural keys (`unit_id`; `(college_id, cip_code, credential_level)`) so a
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
) {
  private val logger = LoggerFactory.getLogger(CollegeScorecardLoader::class.java)

  /**
   * Why a row was skipped, bucketed precisely so the end-of-load summary reports
   * a counted reason for every dropped row — never a silent loss. The buckets are
   * the three disposition mechanisms' skip outcomes (mechanism A nulls a cell and
   * does not skip): missing required/key field, no owning college, the two known
   * field-of-study source sentinels, a constraint violation keyed by its name,
   * and a retryable transient fault.
   */
  sealed interface SkipReason {
    /** One or more required/key columns were absent or blank, named in
     * [missingFields] so the structured result carries which columns were missing
     * — not only the log. */
    data class MissingRequiredField(
      val missingFields: List<String>,
    ) : SkipReason

    data object NoCollegeForUnitId : SkipReason

    /** `UNITID=NA` field-of-study rows: non-IPEDS institutions absent from the
     * institution file (RFC 78). Detected and counted, not linked. */
    data object UnitIdNa : SkipReason

    /** `CREDLEV` outside `1..8`: the `99` "Non-Credential Program" sentinel and
     * any other out-of-domain credential level. */
    data object CredentialLevelOutOfDomain : SkipReason

    /** A DB CHECK/unique violation, keyed by the violated constraint name (null
     * when the driver did not report one). */
    data class ConstraintViolation(
      val constraint: String?,
    ) : SkipReason

    data object Transient : SkipReason

    /** An upsert failure that is neither transient, a constraint violation, nor
     * any other permanent DB error with a known mapping — including a null
     * exception or an unexpected [DaoException] subtype. Bucketed distinctly so a
     * genuinely-unnamed [ConstraintViolation] is never conflated with "we could
     * not classify this at all". */
    data object UnknownFailure : SkipReason

    /**
     * Stable, payload-independent key for provenance JSON. Two
     * [MissingRequiredField] skips naming different columns are the same KIND of
     * loss and must aggregate under one key; the columns themselves stay in the
     * log, where the detail is actionable. Exhaustive `when` over the sealed
     * interface, so a new bucket cannot silently miss a key.
     */
    val kind: String
      get() =
        when (this) {
          is MissingRequiredField -> "missing_required_field"
          NoCollegeForUnitId -> "no_college_for_unit_id"
          UnitIdNa -> "unit_id_na"
          CredentialLevelOutOfDomain -> "credential_level_out_of_domain"
          is ConstraintViolation -> "constraint_violation"
          Transient -> "transient"
          UnknownFailure -> "unknown_failure"
        }
  }

  /**
   * A source CSV/JSON header (or shape) violation: the run aborts before any
   * write, naming the [missing] columns — the "silent NULL column" failure mode
   * RFC 139 closes. Thrown by [assertRequiredColumns] from [ingest] (and
   * [load]); [IngestApplication] maps it to a non-zero exit.
   */
  class MissingSourceColumnsException(
    val fileName: String,
    val sourceArg: String,
    val missing: List<String>,
  ) : RuntimeException(
      "source file [$fileName] (from [$sourceArg]) is missing required column(s) $missing; nothing was written",
    )

  /**
   * A curated-aliases file whose shape or contents are unusable (RFC 139): a
   * malformed/unknown-key entry, a wrong primitive type, or a duplicate
   * `unit_id`. The typed sibling of [MissingSourceColumnsException], and the
   * same contract: thrown before any write, mapped to a non-zero exit by
   * [IngestApplication]. [entryIndex] and [entry] name the offending element
   * when the failure is per-entry; [duplicateUnitIds] carries every repeated
   * `unit_id` when it is the duplicate check.
   */
  class InvalidAliasFileException(
    val fileName: String,
    val detail: String,
    val entryIndex: Int? = null,
    val entry: String? = null,
    val duplicateUnitIds: List<Int> = emptyList(),
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
   * An ingest that failed AFTER one or more phases had already committed (RFC
   * 139). Each phase is its own transaction, so the earlier ones cannot be
   * rolled back; by the success-only rule there is also no
   * `college_index_build` row. Names [committedPhases] so a partially applied
   * snapshot is reported loudly instead of being inferred from a stack trace.
   * The ingest is idempotent: re-running completes it.
   */
  class PartialIngestException(
    val committedPhases: List<String>,
    cause: Throwable,
  ) : RuntimeException(
      "ingest failed after committing $committedPhases; the database holds a partially applied snapshot, " +
        "NO college_index_build row was written, and provenance was NOT recorded — re-run the ingest to complete it",
      cause,
    )

  /** One resolved ingest input: the local [file] plus the caller's original argument (path or `s3://`). */
  data class SourceFile(
    val file: File,
    val sourceArg: String,
  )

  /** Provenance for one source file: streamed sha256 + byte size (RFC 139). */
  data class SourceDigest(
    val fileName: String,
    val sha256: String,
    val bytes: Long,
    val sourceArg: String,
  )

  /**
   * The alias-application tally (RFC 139): entries seen, applied, unchanged,
   * and the `unit_id`s that matched no college. The unmatched ids are carried
   * by VALUE, not counted: "3 entries were dead" is unactionable, "entries
   * 100654, 166027, 240444 were dead" is the answer, and it survives into the
   * provenance row rather than only into an ephemeral log line.
   */
  data class AliasResult(
    val entries: Int,
    val applied: Int,
    val unchanged: Int,
    val unknownUnitIds: List<Int>,
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

  /**
   * The outcome of mapping one CSV row to a domain value, returned by the pure
   * mappers so the load loop — not the mapper — folds it into the [LoadCount]
   * accumulator. A [Mapped] carries the value to upsert plus the optional cells
   * that were coerced to NULL (by column name, count 1 each), so the loop records
   * the coercions only when the value actually reaches the DB; a [Skipped] carries
   * the precise [SkipReason] the loop tallies and the row is dropped.
   */
  private sealed interface MapResult<out T> {
    data class Mapped<T>(
      val value: T,
      val coercions: Map<String, Int>,
    ) : MapResult<T>

    data class Skipped(
      val reason: SkipReason,
    ) : MapResult<Nothing>
  }

  /** Mutable per-file accumulator, folded into the per-file result types below. */
  private class LoadCount {
    var loaded: Int = 0
    var seen: Int = 0
    var inserted: Int = 0
    var changed: Int = 0
    var unchanged: Int = 0
    val skipsByReason: MutableMap<SkipReason, Int> = mutableMapOf()
    val fieldsCoercedToNull: MutableMap<String, Int> = mutableMapOf()

    fun recordSkip(reason: SkipReason) {
      skipsByReason.merge(reason, 1, Int::plus)
    }

    fun recordCoercions(coercions: Map<String, Int>) {
      for ((column, n) in coercions) fieldsCoercedToNull.merge(column, n, Int::plus)
    }

    /** The one accumulator→result mapping for the institution file. */
    fun toCollegesResult(): CollegeLoadResult =
      CollegeLoadResult(
        seen = seen,
        inserted = inserted,
        changed = changed,
        unchanged = unchanged,
        skipsByReason = skipsByReason.toMap(),
        fieldsCoercedToNull = fieldsCoercedToNull.toMap(),
      )

    /** The one accumulator→result mapping for the field-of-study file. */
    fun toProgramsResult(): ProgramLoadResult =
      ProgramLoadResult(
        seen = seen,
        upserted = loaded,
        skipsByReason = skipsByReason.toMap(),
        fieldsCoercedToNull = fieldsCoercedToNull.toMap(),
      )
  }

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

  /**
   * Runs one committing phase, recording it in [committedPhases] on success and
   * — if anything has already committed — converting a failure into a
   * [PartialIngestException] that names what landed (RFC 139). A failure with
   * nothing committed yet propagates untouched: that is the clean
   * nothing-was-written abort.
   */
  private suspend fun <T> phase(
    name: String,
    committedPhases: MutableList<String>,
    body: suspend () -> T,
  ): T {
    val value =
      try {
        body()
      } catch (e: Exception) {
        if (committedPhases.isEmpty()) throw e
        throw PartialIngestException(committedPhases.toList(), e)
      }
    committedPhases += name
    return value
  }

  private suspend fun loadInstitutions(file: File): CollegeLoadResult =
    database.withConnection { session ->
      val count = LoadCount()
      // Pre-load versions keyed by unit_id so each upsert outcome can be split
      // into inserted (absent before) / changed (version advanced) / unchanged
      // (version held) for the provenance build row (RFC 139). ~6k rows.
      val preVersions = CollegesDao.currentVersionsByUnitId(session).getOrThrow()
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
            recordUpsertFailure(count, error, "institution", "unit_id", newCollege.unitId, record.recordNumber)
          } else {
            count.loaded++
            val preVersion = preVersions[newCollege.unitId]
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

  /** The structured outcome of one successful [ingest] run. */
  data class IngestReport(
    val startedAt: Instant,
    val finishedAt: Instant,
    val sources: List<SourceDigest>,
    val colleges: CollegeLoadResult,
    val programs: ProgramLoadResult,
    val aliases: AliasResult,
    val nonNullBefore: Map<String, Int>,
    val nonNullAfter: Map<String, Int>,
    val buildId: java.util.UUID,
  ) {
    /** Version bumps this run caused: changed Scorecard rows plus applied alias entries. */
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
          if (aliases.unknownUnitIds.isEmpty()) "" else " ${aliases.unknownUnitIds}"
        appendLine(
          "aliases:  ${aliases.entries} entries — ${aliases.applied} applied, " +
            "${aliases.unchanged} unchanged, ${aliases.unknownUnitIds.size} unknown unit_id$unknownIds",
        )
        val deltas =
          nonNullBefore.keys.joinToString(", ") { column ->
            "$column ${nonNullBefore[column]}→${nonNullAfter[column]}"
          }
        appendLine("non-null deltas: $deltas")
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
   * or duplicate `unit_id`, before any write), digest each source (sha256 +
   * bytes), snapshot per-column non-null counts on `colleges`, run the shared
   * [loadScorecard] primitive (whose header assertions are likewise fatal
   * before any write), apply the aliases, re-snapshot, and finish by inserting
   * the one `college_index_build` provenance row (`method_version = 1`). A
   * failure anywhere throws out of here — success paths only reach the build
   * row. The file phase (parse, digests) runs on [ioDispatcher].
   */
  suspend fun ingest(
    institution: SourceFile,
    fields: SourceFile,
    aliasesFile: SourceFile,
  ): IngestReport {
    val startedAt = Instant.now()
    val aliasEntries = withContext(ioDispatcher) { parseAliases(aliasesFile.file) }
    val sources = withContext(ioDispatcher) { listOf(digest(institution), digest(fields), digest(aliasesFile)) }

    // Each phase below is its own transaction, so a failure in a later one
    // cannot roll back an earlier one. The tracker turns that into a LOUD
    // report ([PartialIngestException]) naming exactly what committed, instead
    // of a bare stack trace over a partially applied snapshot.
    val committedPhases = mutableListOf<String>()
    val nonNullBefore = nonNullCounts()
    val scorecard = loadScorecard(institution, fields, committedPhases)
    val aliasResult = phase("aliases", committedPhases) { applyAliases(aliasEntries) }
    val nonNullAfter = nonNullCounts()
    val finishedAt = Instant.now()

    val buildId =
      phase("provenance", committedPhases) {
        database
          .withConnection { session ->
            CollegesDao.insertIndexBuild(
              session,
              NewCollegeIndexBuild(
                startedAt = startedAt,
                finishedAt = finishedAt,
                sources = sourcesJson(sources),
                rowsIngested = rowsIngestedJson(scorecard.colleges, scorecard.programs, aliasResult),
                indexRows = null,
                changeSummary =
                  changeSummaryJson(
                    nonNullBefore,
                    nonNullAfter,
                    scorecard.colleges.changed + aliasResult.applied,
                  ),
                methodVersion = METHOD_VERSION,
              ),
            )
          }.getOrThrow()
      }

    return IngestReport(
      startedAt = startedAt,
      finishedAt = finishedAt,
      sources = sources,
      colleges = scorecard.colleges,
      programs = scorecard.programs,
      aliases = aliasResult,
      nonNullBefore = nonNullBefore,
      nonNullAfter = nonNullAfter,
      buildId = buildId,
    )
  }

  /**
   * Asserts every column this loader reads is present in [file]'s CSV header,
   * throwing [MissingSourceColumnsException] with the missing names. Runs
   * before any write: a missing column would otherwise load as NULL across the
   * whole table, indistinguishable from suppressed data.
   */
  internal fun assertRequiredColumns(
    source: SourceFile,
    required: List<String>,
  ) {
    val header = parseCsv(source.file).use { it.headerMap.keys }
    val missing = required.filterNot { it in header }
    if (missing.isNotEmpty()) {
      // The caller's ORIGINAL argument, not a scratch basename: bin/ingest-colleges
      // downloads an s3:// source into a temp dir, so file.name alone would name a
      // file the operator never typed (RFC 139).
      throw MissingSourceColumnsException(source.file.path, source.sourceArg, missing)
    }
  }

  /** Streams [source]'s file once for its sha256 hex digest and byte count. */
  private fun digest(source: SourceFile): SourceDigest {
    val md = MessageDigest.getInstance(DIGEST_ALGORITHM)
    var bytes = 0L
    source.file.inputStream().use { input ->
      val buffer = ByteArray(DIGEST_READ_BUFFER.bytes.toInt())
      while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        md.update(buffer, 0, read)
        bytes += read
      }
    }
    // The JDK's own hex encoder (JDK 21) — no per-byte Formatter, no locale surface.
    val sha256 = HexFormat.of().formatHex(md.digest())
    return SourceDigest(fileName = source.file.name, sha256 = sha256, bytes = bytes, sourceArg = source.sourceArg)
  }

  /** One curated alias entry from db/data/college-aliases.json. */
  internal data class AliasEntry(
    val unitId: Int,
    val aliases: List<String>,
  )

  /**
   * Parses the curated aliases JSON (`[{ "unit_id": N, "aliases": [...] }]`).
   * The file is repo data, not external source data — a malformed shape is a
   * programming/review error and fails the run loudly (unlike an unknown
   * `unit_id`, which is counted and reported, never fatal).
   *
   * Every layer is verified rather than cast through: the root must be an
   * array, each element an object carrying EXACTLY [ALIAS_ENTRY_KEYS] (a
   * mistyped `"alises"` is rejected, never silently dropped), `unit_id` an
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
    // A duplicate unit_id is FATAL like the header assertion: applying both
    // entries would mean last-writer-wins by file order — a silent editing
    // mistake in curated repo data, aborted before anything is written.
    val duplicates =
      entries
        .groupingBy { it.unitId }
        .eachCount()
        .filterValues { it > 1 }
        .keys
        .sorted()
    if (duplicates.isNotEmpty()) {
      throw InvalidAliasFileException(
        file.path,
        "duplicate unit_id entr" + (if (duplicates.size == 1) "y" else "ies") + " $duplicates",
        duplicateUnitIds = duplicates,
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
    val unitId =
      (obj.getValue("unit_id") as? JsonPrimitive)
        // kotlinx models `1` and `"1"` as the same JsonPrimitive class, differing
        // only in isString. Rejecting the quoted form keeps the curated file
        // honestly typed: `"unit_id": "110100"` is a mistake we refuse loudly
        // rather than coerce, since a typo'd quote would otherwise sail through
        // toIntOrNull and silently alias the wrong school.
        ?.takeIf { !it.isString }
        ?.content
        ?.toIntOrNull()
        ?: invalid("unit_id must be a JSON integer")
    val aliasArray = obj.getValue("aliases") as? JsonArray ?: invalid("aliases must be a JSON array")
    val aliases =
      aliasArray.map { alias ->
        val primitive = alias as? JsonPrimitive
        if (primitive == null || !primitive.isString) invalid("every alias must be a JSON string; got [$alias]")
        primitive.content
      }
    return AliasEntry(unitId = unitId, aliases = aliases)
  }

  /**
   * Applies the curated alias entries after the Scorecard upsert phase, change-
   * suppressed per row ([CollegesDao.updateAliases]): an unchanged alias set
   * writes nothing and bumps nothing. Unknown `unit_id`s are counted and
   * reported, never fatal; a real DB failure aborts the run (no build row).
   */
  private suspend fun applyAliases(entries: List<AliasEntry>): AliasResult =
    database.withConnection { session ->
      var applied = 0
      var unchanged = 0
      val unknown = mutableListOf<Int>()
      for (entry in entries) {
        val outcome =
          CollegesDao.updateAliases(session, entry.unitId, entry.aliases).getOrElse { error ->
            // The DAO's message is generic by design; the entry that was being
            // applied is the context a fixer needs, so it travels with the throw.
            throw IllegalStateException(
              "applying curated aliases failed for [unit_id=${entry.unitId}] " +
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

          CollegesDao.AliasUpdateOutcome.UNKNOWN_UNIT_ID -> {
            unknown += entry.unitId
            logger.warn("alias entry references an unknown [unit_id={}]; skipped", entry.unitId)
          }
        }
      }
      AliasResult(
        entries = entries.size,
        applied = applied,
        unchanged = unchanged,
        unknownUnitIds = unknown.toList(),
      )
    }

  /** Non-null counts for every nullable curated column on `colleges` (the change-summary axis). */
  private suspend fun nonNullCounts(): Map<String, Int> =
    database.withConnection { session ->
      CollegesDao.nonNullCounts(session, NON_NULL_SUMMARY_COLUMNS).getOrThrow()
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
        putJsonArray("unknown_unit_id") { aliases.unknownUnitIds.forEach { add(it) } }
      }
    }

  private fun changeSummaryJson(
    before: Map<String, Int>,
    after: Map<String, Int>,
    versionBumps: Int,
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
      }
      put("version_bumps", versionBumps)
    }

  /**
   * Resolves the owning college for a field-of-study row. [CollegesDao.findByUnitId]
   * returns success(null) for the genuine "no owning college" case and failure(e)
   * only for a real DB fault — both are absorbed here, returning null to signal
   * "skip this row": a DB fault is classified + logged like any upsert failure
   * (never mislabeled NoCollegeForUnitId), a missing college is the counted
   * [SkipReason.NoCollegeForUnitId]. The read runs before the savepoint, so the
   * transaction is unaborted and the read is always valid.
   */
  private fun resolveCollege(
    count: LoadCount,
    session: SqlSession,
    fields: ProgramFields,
    record: CSVRecord,
  ): College? {
    val lookup = CollegesDao.findByUnitId(session, fields.unitId)
    if (lookup.isFailure) {
      recordUpsertFailure(count, lookup.exceptionOrNull(), "program", "unit_id", fields.unitId, record.recordNumber)
      return null
    }
    val college = lookup.getOrThrow()
    if (college == null) {
      count.recordSkip(SkipReason.NoCollegeForUnitId)
      logger.debug(
        "Skipping program row [line={}]: no college for [unit_id={}]",
        record.recordNumber,
        fields.unitId,
      )
      return null
    }
    return college
  }

  /**
   * Buckets a post-DB upsert failure (mechanism C): a [TransientError] is a
   * retryable [SkipReason.Transient] kept at WARN (rare); a
   * [ConstraintViolationException] is bucketed by its constraint name; any other
   * [PermanentError] is an unkeyed [SkipReason.ConstraintViolation]; a null or
   * otherwise-unclassifiable failure is [SkipReason.UnknownFailure] — never
   * silently fused into an unnamed constraint violation. The per-row line is
   * demoted to DEBUG (transient stays WARN) and carries the row's natural key,
   * line number, and the exception's constraint/detail so a drill-down shows
   * which value failed without dumping every row at WARN.
   */
  private fun recordUpsertFailure(
    count: LoadCount,
    error: Throwable?,
    kind: String,
    keyName: String,
    keyValue: Any?,
    line: Long,
  ) {
    val reason = classifyUpsertFailure(error)
    count.recordSkip(reason)
    logUpsertSkip(reason, kind, keyName, keyValue, line, error)
  }

  /**
   * Emits the per-row skip line for an upsert failure: the row's natural key and
   * line number plus the constraint/detail pulled from a
   * [ConstraintViolationException] (null otherwise) and the categorized cause. A
   * [SkipReason.Transient] stays WARN (rare, retryable); every other reason is
   * demoted to DEBUG so a drill-down shows which value failed without dumping
   * every row at WARN.
   */
  private fun logUpsertSkip(
    reason: SkipReason,
    kind: String,
    keyName: String,
    keyValue: Any?,
    line: Long,
    error: Throwable?,
  ) {
    val violation = error as? ConstraintViolationException
    val constraintName = violation?.constraint
    val detail = violation?.detail
    val message = "Skipping [$kind] row [$keyName={}] [line={}] [constraint={}] [detail={}]: [{}]"
    if (reason == SkipReason.Transient) {
      logger.warn(message, keyValue, line, constraintName, detail, describe(error))
    } else {
      logger.debug(message, keyValue, line, constraintName, detail, describe(error))
    }
  }

  /**
   * Pure classifier (no logging, no mutation) mapping an upsert failure to its
   * [SkipReason] bucket. Null-guarded first so a missing exception is an explicit
   * [SkipReason.UnknownFailure], never an unnamed constraint violation.
   */
  internal fun classifyUpsertFailure(error: Throwable?): SkipReason =
    when (error) {
      null -> SkipReason.UnknownFailure
      is TransientError -> SkipReason.Transient
      is ConstraintViolationException -> SkipReason.ConstraintViolation(error.constraint)
      is PermanentError -> SkipReason.ConstraintViolation(null)
      else -> SkipReason.UnknownFailure
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

  /**
   * Runs one row's [upsert] inside a SQL `SAVEPOINT` so a CHECK/unique violation
   * (or any [DaoException]) rolls back only that row, not the whole file: without
   * a savepoint, PostgreSQL aborts the enclosing transaction on the first failed
   * statement (SQLSTATE `25P02`) and every subsequent row would falsely "skip"
   * and the terminal commit would discard the good rows. On success the savepoint
   * is released; on a failed [Result] it is rolled back to, leaving the
   * transaction usable for the next row.
   */
  private fun <T> upsertWithSavepoint(
    session: SqlSession,
    upsert: () -> Result<T>,
  ): Result<T> {
    session.prepareStatement("SAVEPOINT $ROW_SAVEPOINT").use { it.execute() }
    val result = upsert()
    if (result.isFailure) {
      session.prepareStatement("ROLLBACK TO SAVEPOINT $ROW_SAVEPOINT").use { it.execute() }
    } else {
      session.prepareStatement("RELEASE SAVEPOINT $ROW_SAVEPOINT").use { it.execute() }
    }
    return result
  }

  // ---------------------------------------------------------------------------
  // Row mapping — PURE: validate one CSV row to a [MapResult]. A mapper neither
  // reads the DB nor mutates the [LoadCount] accumulator; it returns the value to
  // upsert (plus the cells it coerced to NULL) or the precise skip reason, and the
  // load loop folds that into the accumulator. Skip/coercion DEBUG logs still fire
  // here, once per dropped row / coerced cell, so the audit trail is unchanged.
  // ---------------------------------------------------------------------------

  private fun mapInstitution(record: CSVRecord): MapResult<NewCollege> {
    val unitId = intOrNull(record, COL_UNITID)
    val name = stringOrNull(record, COL_INSTNM)
    val city = stringOrNull(record, COL_CITY)
    val state = stringOrNull(record, COL_STABBR)
    val control = intOrNull(record, COL_CONTROL)

    if (unitId == null || name == null || city == null || state == null || control == null) {
      val missing =
        buildList {
          if (unitId == null) add("unit_id")
          if (name == null) add("name")
          if (city == null) add("city")
          if (state == null) add("state")
          if (control == null) add("control")
        }
      logger.debug(
        "Skipping institution row [line={}]: missing required field {} " +
          "[unit_id={}] [name={}] [city={}] [state={}] [control={}]",
        record.recordNumber,
        missing,
        unitId,
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

    val netPrice = readControlKeyed(COL_NET_PRICE_BASE)

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
        unitId = unitId,
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
        undergradEnrollment =
          intInDomainOrNull(record, COL_UGDS, 0, Int.MAX_VALUE, "undergrad_enrollment", coercions),
        admissionRate = doubleInDomainOrNull(record, COL_ADM_RATE, RATE_MIN, RATE_MAX, "admission_rate", coercions),
        satAvg = intInDomainOrNull(record, COL_SAT_AVG, 0, Int.MAX_VALUE, "sat_avg", coercions),
        costAttendance = intInDomainOrNull(record, COL_COSTT4_A, 0, Int.MAX_VALUE, "cost_attendance", coercions),
        netPrice = netPrice,
        netPriceQ1 = readBandPrice(1),
        netPriceQ2 = readBandPrice(2),
        netPriceQ3 = readBandPrice(3),
        netPriceQ4 = readBandPrice(4),
        netPriceQ5 = readBandPrice(5),
        tuitionInState = intInDomainOrNull(record, COL_TUITIONFEE_IN, 0, Int.MAX_VALUE, "tuition_in_state", coercions),
        tuitionOutState =
          intInDomainOrNull(record, COL_TUITIONFEE_OUT, 0, Int.MAX_VALUE, "tuition_out_state", coercions),
        graduationRate = doubleInDomainOrNull(record, COL_C150_4, RATE_MIN, RATE_MAX, "graduation_rate", coercions),
        medianEarnings =
          intInDomainOrNull(record, COL_MD_EARN_WNE_P10, 0, Int.MAX_VALUE, "median_earnings", coercions),
        // median_debt is a loan amount: genuinely nonneg, so mechanism A applies
        // like the sibling money fields.
        medianDebt = intInDomainOrNull(record, COL_GRAD_DEBT_MDN, 0, Int.MAX_VALUE, "median_debt", coercions),
        pctPell = doubleInDomainOrNull(record, COL_PCTPELL, RATE_MIN, RATE_MAX, "pct_pell", coercions),
        website = stringOrNull(record, COL_INSTURL),
      )
    return MapResult.Mapped(college, coercions)
  }

  /** The validated key/required columns of a field-of-study row, resolved to a
   * [NewCollegeProgram] by the load loop once its owning college is found. */
  private data class ProgramFields(
    val unitId: Int,
    val cipCode: String,
    val cipTitle: String,
    val credentialLevel: Int,
  )

  private fun mapField(record: CSVRecord): MapResult<ProgramFields> {
    // Mechanism B: known source sentinels in required/key columns are skipped
    // under their own precise bucket before any DB work, never silently.
    if (stringOrNull(record, COL_UNITID) == "NA") {
      logger.debug("Skipping program row [line={}]: UNITID=NA sentinel", record.recordNumber)
      return MapResult.Skipped(SkipReason.UnitIdNa)
    }

    val unitId = intOrNull(record, COL_UNITID)
    val cipCode = stringOrNull(record, COL_CIPCODE)
    val cipTitle = stringOrNull(record, COL_CIPDESC)
    val credentialLevel = intOrNull(record, COL_CREDLEV)

    if (unitId == null || cipCode == null || cipTitle == null || credentialLevel == null) {
      val missing =
        buildList {
          if (unitId == null) add("unit_id")
          if (cipCode == null) add("cip_code")
          if (cipTitle == null) add("cip_title")
          if (credentialLevel == null) add("credential_level")
        }
      logger.debug(
        "Skipping program row [line={}]: missing required field {} " +
          "[unit_id={}] [cip_code={}] [cip_title={}] [credential_level={}]",
        record.recordNumber,
        missing,
        unitId,
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
        "Skipping program row [line={}]: credential_level [{}] out of domain [unit_id={}]",
        record.recordNumber,
        credentialLevel,
        unitId,
      )
      return MapResult.Skipped(SkipReason.CredentialLevelOutOfDomain)
    }

    return MapResult.Mapped(
      ProgramFields(
        unitId = unitId,
        cipCode = cipCode,
        cipTitle = cipTitle,
        credentialLevel = credentialLevel,
      ),
      emptyMap(),
    )
  }

  // ---------------------------------------------------------------------------
  // CSV parsing + cell coercion
  // ---------------------------------------------------------------------------

  /** A trimmed cell, or null when absent or blank (the Scorecard blank-cell idiom). */
  private fun stringOrNull(
    record: CSVRecord,
    column: String,
  ): String? {
    // Every cell read flows through here, and [assertRequiredColumns] has
    // already proven every REQUIRED_* column is mapped — so an unmapped column
    // can only be a read site missing from the REQUIRED_* list (RFC 139).
    // That is a programming error, failed loudly rather than silently loading
    // a whole column as NULL.
    check(record.isMapped(column)) {
      "column [$column] is read by a mapper but missing from the REQUIRED_* assertion list"
    }
    val value = record.get(column)?.trim()
    return value?.takeIf { it.isNotEmpty() }
  }

  private fun intOrNull(
    record: CSVRecord,
    column: String,
  ): Int? = stringOrNull(record, column)?.toIntOrNull()

  private fun doubleOrNull(
    record: CSVRecord,
    column: String,
  ): Double? = stringOrNull(record, column)?.toDoubleOrNull()

  /**
   * Mechanism A for a bounded **optional** integer metric: parses the cell, and
   * if it is absent/non-int it is simply null (the blank-cell idiom); if it is a
   * valid int outside `[min, max]` it is coerced to NULL and the coercion is
   * tallied by [columnName]. A junk optional cell thus never drops the row — the
   * DB CHECK remains the backstop for anything this misses.
   */
  private fun intInDomainOrNull(
    record: CSVRecord,
    column: String,
    min: Int,
    max: Int,
    columnName: String,
    coercions: MutableMap<String, Int>,
  ): Int? {
    val value = intOrNull(record, column) ?: return null
    if (value < min || value > max) {
      logCoercion(columnName, record.recordNumber, value, min, max, coercions)
      return null
    }
    return value
  }

  /** Mechanism A for a bounded **optional** double metric; see [intInDomainOrNull]. */
  private fun doubleInDomainOrNull(
    record: CSVRecord,
    column: String,
    min: Double,
    max: Double,
    columnName: String,
    coercions: MutableMap<String, Int>,
  ): Double? {
    val value = doubleOrNull(record, column) ?: return null
    if (value < min || value > max) {
      logCoercion(columnName, record.recordNumber, value, min, max, coercions)
      return null
    }
    return value
  }

  /**
   * Tallies an out-of-domain optional-metric coercion by [columnName] and emits
   * the per-cell DEBUG line. Shared by [intInDomainOrNull] and
   * [doubleInDomainOrNull] so the tally and log template live in one place.
   */
  private fun logCoercion(
    columnName: String,
    line: Long,
    value: Any,
    min: Any,
    max: Any,
    coercions: MutableMap<String, Int>,
  ) {
    coercions.merge(columnName, 1, Int::plus)
    logger.debug(
      "Coercing out-of-domain optional metric to null [column={}] [line={}] [value={}] [domain=[{}, {}]]",
      columnName,
      line,
      value,
      min,
      max,
    )
  }

  /**
   * A bracketed cause description for a skip warning, tagged with the failure
   * category (transient vs permanent) so a human scanning the log can tell a
   * retryable blip from permanently-corrupt source data. The wrapping
   * [DaoException] carries only a generic message (e.g. "Database constraint
   * violation"), so the actionable detail — which constraint failed, on what
   * value — is pulled from the root cause it wraps (and its SQLSTATE when that
   * cause is a [SQLException]).
   */
  private fun describe(error: Throwable?): String {
    if (error == null) return "[unknown error]"
    val root = rootCause(error)
    val detail =
      if (root is SQLException) {
        "[${root::class.simpleName}] [sqlstate=${root.sqlState}]: [${root.message?.trim()}]"
      } else {
        "[${root::class.simpleName}]: [${root.message}]"
      }
    return "[${error.errorCategory()}] [${error::class.simpleName}]: $detail"
  }

  private fun rootCause(error: Throwable): Throwable {
    var cause = error
    while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
    return cause
  }

  companion object {
    private const val ROW_SAVEPOINT = "scorecard_row"

    /** `college_index_build.method_version` for this derivation logic (1 = RFC 139). */
    const val METHOD_VERSION = 1

    /** The exact key set one curated alias entry may carry — a surplus key is a typo, never surplus data. */
    private val ALIAS_ENTRY_KEYS = setOf("unit_id", "aliases")

    /** The provenance digest algorithm: `college_index_build.sources.sha256` is this hash. */
    private const val DIGEST_ALGORITHM = "SHA-256"

    /** Streaming read-buffer for [digest]: one 64 KiB block per read, named through the repo's own byte type. */
    private val DIGEST_READ_BUFFER = DataSize.ofKibibytes(64)

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
        "undergrad_enrollment",
        "admission_rate",
        "sat_avg",
        "cost_attendance",
        "net_price",
        "net_price_q1",
        "net_price_q2",
        "net_price_q3",
        "net_price_q4",
        "net_price_q5",
        "tuition_in_state",
        "tuition_out_state",
        "graduation_rate",
        "median_earnings",
        "median_debt",
        "pct_pell",
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
