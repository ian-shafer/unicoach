package ed.unicoach.college

import ed.unicoach.common.util.DataSize
import ed.unicoach.db.dao.ConstraintViolationException
import ed.unicoach.db.dao.DaoException
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.error.PermanentError
import ed.unicoach.error.TransientError
import ed.unicoach.error.errorCategory
import org.apache.commons.csv.CSVRecord
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest
import java.sql.SQLException
import java.util.HexFormat

/**
 * A source CSV/JSON header (or shape) violation: the run aborts before any
 * write, naming the [missing] columns — the "silent NULL column" failure mode
 * RFC 139 closes. Thrown by [CsvIngestSupport.assertRequiredColumns];
 * [IngestApplication] maps it to a non-zero exit.
 */
class MissingSourceColumnsException(
  val fileName: String,
  val sourceArg: String,
  val missing: List<String>,
) : RuntimeException(
    "source file [$fileName] (from [$sourceArg]) is missing required column(s) $missing; nothing was written",
  )

/**
 * An ingest that failed AFTER one or more phases had already committed (RFC
 * 139). Each phase is its own transaction, so the earlier ones cannot be
 * rolled back; by the success-only rule there is also no
 * `college_index_build` row. Names [committedPhases] so a partially applied
 * snapshot is reported loudly instead of being inferred from a stack trace, and
 * [failedPhase] so the operator is told which phase threw rather than having to
 * deduce it from the phase order — ambiguous since RFC 146 put `name-words`
 * between the row phases and `provenance`, and the two leave very different
 * states behind. The ingest is idempotent: re-running completes it.
 */
class PartialIngestException(
  val committedPhases: List<String>,
  val failedPhase: String,
  cause: Throwable,
) : RuntimeException(
    "ingest failed in phase [$failedPhase] after committing $committedPhases; the database holds a " +
      "partially applied snapshot, NO college_index_build row was written, and provenance was NOT " +
      "recorded — re-run the ingest to complete it",
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

  data object NoCollegeForIpedsUnitId : SkipReason

  /** `UNITID=NA` field-of-study rows: non-IPEDS institutions absent from the
   * institution file (RFC 78). Detected and counted, not linked. */
  data object IpedsUnitIdNa : SkipReason

  /** `CREDLEV` outside `1..8`: the `99` "Non-Credential Program" sentinel and
   * any other out-of-domain credential level. */
  data object CredentialLevelOutOfDomain : SkipReason

  /** An IPEDS `C_A.CIPCODE` that is not six digits once its dot is removed (RFC
   * 144): `cip_code` is NOT NULL and part of the census upsert key, so it cannot
   * be nulled — the row is dropped under its own named bucket rather than a
   * generic constraint reject. */
  data object CipCodeMalformed : SkipReason

  /** A row whose field count differs from its header's (RFC 144): a longer row's
   * surplus cells would be read by nobody, and a shorter row's cell read throws
   * out of the mapper and takes the whole phase with it. Rejected up front under
   * its own counted bucket, like every other malformed row. */
  data object RowArityMismatch : SkipReason

  /** A row carrying a natural key an EARLIER row of the same file already
   * claimed (RFC 144). The first row wins and this one is dropped — counted, so
   * file order never silently decides what an institution's stored values are. */
  data object DuplicateKeyInFile : SkipReason

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
        NoCollegeForIpedsUnitId -> "no_college_for_ipeds_unit_id"
        IpedsUnitIdNa -> "ipeds_unit_id_na"
        CredentialLevelOutOfDomain -> "credential_level_out_of_domain"
        CipCodeMalformed -> "cip_code_malformed"
        RowArityMismatch -> "row_arity_mismatch"
        DuplicateKeyInFile -> "duplicate_key_in_file"
        is ConstraintViolation -> "constraint_violation"
        Transient -> "transient"
        UnknownFailure -> "unknown_failure"
      }
}

/**
 * The outcome of mapping one CSV row to a domain value, returned by the pure
 * mappers so the load loop — not the mapper — folds it into the [LoadCount]
 * accumulator. A [Mapped] carries the value to upsert plus the optional cells
 * that were coerced to NULL (by column name, count 1 each), so the loop records
 * the coercions only when the value actually reaches the DB; a [Skipped] carries
 * the precise [SkipReason] the loop tallies and the row is dropped.
 */
internal sealed interface MapResult<out T> {
  data class Mapped<T>(
    val value: T,
    val coercions: Map<String, Int>,
  ) : MapResult<T>

  data class Skipped(
    val reason: SkipReason,
  ) : MapResult<Nothing>
}

/** Mutable per-file accumulator, folded into each loader's per-file result type. */
internal class LoadCount {
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
}

/**
 * The source-family-agnostic half of the ingest machinery (RFC 144): digests,
 * header assertion, CSV parsing and cell coercion, the per-row savepoint, the
 * upsert-failure classifier, and the phase tracker. Lifted verbatim out of
 * [CollegeScorecardLoader] so [IpedsLoader] reuses it rather than forking it —
 * the one behavioural change being that the row savepoint is now named
 * `ingest_row`, not `scorecard_row` (two loaders sharing a transaction must not
 * silently re-target each other's savepoint).
 */
internal object CsvIngestSupport {
  private val logger = LoggerFactory.getLogger(CsvIngestSupport::class.java)

  /** The per-row savepoint name; source-family neutral, so two loaders never collide. */
  private const val ROW_SAVEPOINT = "ingest_row"

  /** The provenance digest algorithm: `college_index_build.sources.sha256` is this hash. */
  private const val DIGEST_ALGORITHM = "SHA-256"

  /** Streaming read-buffer for [digest]: one 64 KiB block per read, named through the repo's own byte type. */
  private val DIGEST_READ_BUFFER = DataSize.ofKibibytes(64)

  /**
   * Asserts every column a loader reads is present in [source]'s CSV header,
   * throwing [MissingSourceColumnsException] with the missing names. Runs
   * before any write: a missing column would otherwise load as NULL across the
   * whole table, indistinguishable from suppressed data.
   */
  fun assertRequiredColumns(
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
  fun digest(source: SourceFile): SourceDigest {
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

  /**
   * True when [record] carries exactly one cell per header column — the row
   * shape every mapper assumes. A SHORT row makes `CSVRecord.get(name)` throw
   * (`Index for header 'X' is n but CSVRecord only has m values!`) from
   * whichever cell a mapper happens to read first, aborting the phase; a LONG
   * row's surplus cells are read by nobody. Checked once at the top of each row
   * loop so both are one counted [SkipReason.RowArityMismatch] instead.
   */
  fun isWellFormed(record: CSVRecord): Boolean = record.isConsistent

  /** A trimmed cell, or null when blank (the Scorecard blank-cell idiom). */
  fun stringOrNull(
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

  fun intOrNull(
    record: CSVRecord,
    column: String,
  ): Int? = stringOrNull(record, column)?.toIntOrNull()

  fun doubleOrNull(
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
  fun intInDomainOrNull(
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
  fun doubleInDomainOrNull(
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
   * Tallies an out-of-domain coercion for a column whose domain is an explicit
   * SET rather than a range, printing the admissible values verbatim. A set
   * flattened through the range overload's `min()`/`max()` misdescribes the
   * rejection — a coerced `ADMCON7 = 4` logged as `[domain=[1, 5]]` reads as
   * in-domain and accuses the coercion logic instead of the cell.
   */
  fun logCoercion(
    columnName: String,
    line: Long,
    value: Any,
    domain: Collection<Any>,
    coercions: MutableMap<String, Int>,
  ) {
    coercions.merge(columnName, 1, Int::plus)
    logger.debug(
      "Coercing out-of-domain optional metric to null [column={}] [line={}] [value={}] [domain={}]",
      columnName,
      line,
      value,
      domain,
    )
  }

  /**
   * Tallies an out-of-domain optional-metric coercion by [columnName] and emits
   * the per-cell DEBUG line. Shared by [intInDomainOrNull] and
   * [doubleInDomainOrNull] so the tally and log template live in one place.
   */
  fun logCoercion(
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
   * Runs one row's [upsert] inside a SQL `SAVEPOINT` so a CHECK/unique violation
   * (or any [DaoException]) rolls back only that row, not the whole file: without
   * a savepoint, PostgreSQL aborts the enclosing transaction on the first failed
   * statement (SQLSTATE `25P02`) and every subsequent row would falsely "skip"
   * and the terminal commit would discard the good rows. On success the savepoint
   * is released; on a failed [Result] it is rolled back to, leaving the
   * transaction usable for the next row.
   */
  fun <T> upsertWithSavepoint(
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
  fun recordUpsertFailure(
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
  fun logUpsertSkip(
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
  fun classifyUpsertFailure(error: Throwable?): SkipReason =
    when (error) {
      null -> SkipReason.UnknownFailure
      is TransientError -> SkipReason.Transient
      is ConstraintViolationException -> SkipReason.ConstraintViolation(error.constraint)
      is PermanentError -> SkipReason.ConstraintViolation(null)
      else -> SkipReason.UnknownFailure
    }

  /**
   * Runs one committing phase, recording it in [committedPhases] on success and
   * — if anything has already committed — converting a failure into a
   * [PartialIngestException] that names what landed, and [name] as the phase
   * that failed (RFC 139; the failing phase added by RFC 146). A failure with
   * nothing committed yet propagates untouched: that is the clean
   * nothing-was-written abort.
   */
  suspend fun <T> phase(
    name: String,
    committedPhases: MutableList<String>,
    body: suspend () -> T,
  ): T {
    val value =
      try {
        body()
      } catch (e: Exception) {
        if (committedPhases.isEmpty()) throw e
        throw PartialIngestException(committedPhases.toList(), name, e)
      }
    committedPhases += name
    return value
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
  fun describe(error: Throwable?): String {
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
}
