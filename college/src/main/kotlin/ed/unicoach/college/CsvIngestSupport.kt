package ed.unicoach.college

import ed.unicoach.common.util.DataSize
import ed.unicoach.db.dao.ConstraintDiagnostics
import ed.unicoach.db.dao.DaoException
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.UpsertOutcome
import ed.unicoach.db.models.AbsenceStatus
import ed.unicoach.db.models.FigureReading
import ed.unicoach.db.models.ValueBearingStatus
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

  /**
   * A field-of-study money row whose (institution, CIP code, credential level)
   * is in no `college_programs` row (RFC 218).
   *
   * It cannot happen in a normal run -- the `fields` phase wrote that program
   * minutes earlier in the same ingest, from the same file -- so it is counted,
   * named and skipped rather than assumed away: the one way to see a rebuild
   * running against a stale `college_programs` is for this number to stop being
   * zero.
   */
  data object NoProgramForKey : SkipReason

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
        NoProgramForKey -> "no_program_for_key"
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
    /**
     * The PAIRS this row lost whole, tallied by band and reason (RFC 195) --
     * its own map and not three more keys in [coercions]; [PairDrop] carries
     * why. Defaulted, because only a loader that reads a pair has any.
     */
    val pairsDropped: Map<PairDrop, Int> = emptyMap(),
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

  /**
   * Bands this file lost whole, by band and by reason (RFC 195) -- its own
   * tally beside [fieldsCoercedToNull]; [PairDrop] carries why.
   *
   * This map is the DURABLE record of a dropped pair: the per-drop line is
   * DEBUG and a production run does not keep it, so if the run summary does
   * not carry the count, a corrupt published band is a thing nobody can
   * observe afterwards.
   */
  val pairsDropped: MutableMap<PairDrop, Int> = mutableMapOf()

  fun recordSkip(reason: SkipReason) {
    skipsByReason.merge(reason, 1, Int::plus)
  }

  /**
   * Tallies one successful upsert: [loaded] plus the three-way split. Written
   * ONCE, here on the accumulator, because every loader that upserts through
   * [CsvIngestSupport.upsertWithSavepoint] had its own hand-copied `when` and a
   * copy that forgot `loaded++` would report a phase that wrote nothing.
   */
  fun recordOutcome(outcome: UpsertOutcome) {
    loaded++
    when (outcome) {
      UpsertOutcome.INSERTED -> inserted++
      UpsertOutcome.CHANGED -> changed++
      UpsertOutcome.UNCHANGED -> unchanged++
    }
  }

  fun recordCoercions(coercions: Map<String, Int>) {
    for ((column, n) in coercions) fieldsCoercedToNull.merge(column, n, Int::plus)
  }

  fun recordPairDrops(drops: Map<PairDrop, Int>) {
    for ((drop, n) in drops) pairsDropped.merge(drop, n, Int::plus)
  }
}

/**
 * The two cells of one optional PAIR as [CsvIngestSupport.intPairInDomainOrNull]
 * resolved them: both present, or both null -- never one (RFC 195).
 *
 * A type rather than `Pair<Int?, Int?>`, because `first`/`second` at a
 * construction site is exactly where a transposition writes the upper value
 * into the lower column, and no CHECK catches a pair that is still ordered.
 */
data class CellPair(
  val lower: Int?,
  val upper: Int?,
)

/**
 * WHY a pair was dropped whole (RFC 195). A typed reason rather than prose,
 * because the reason is tallied in the run summary and read by a test: a
 * sentence is a thing a reader greps for and a rewording silently breaks.
 *
 * The three are kept DISTINCT because they are three different faults in the
 * published file, and an operator acts on them differently: half a pair is a
 * publisher that stopped mid-record, an inverted pair is two columns the wrong
 * way round, and an unreadable pair is a school whose band is corrupt in a way
 * that is otherwise indistinguishable from a school that publishes none.
 */
enum class PairDropReason(
  val value: String,
) {
  /** One cell usable, the other not. Half a range is not a range. */
  HALF_A_PAIR("half_a_pair"),

  /** Both cells usable and `upper < lower`. Two numbers the wrong way round describe nothing. */
  INVERTED("inverted"),

  /**
   * AT LEAST ONE cell present and unparseable -- a [SUPPRESSION_SENTINELS]
   * member, `NA`, `1,200` -- and neither cell survived. Every per-cell reader nulls such a
   * cell silently (it is the blank-cell idiom and is not a domain coercion), so
   * without this reason a corrupt published band and a school that publishes
   * none reach the database as the same six nulls, with nothing counted
   * anywhere.
   *
   * EITHER, not both: a blank cell beside a `PrivacySuppressed` one is still a
   * band this row published and we could not read. A wholly BLANK pair is not
   * this -- see [CsvIngestSupport.classifyPairDrop].
   */
  UNREADABLE("unreadable"),
}

/**
 * One kind of dropped pair: WHICH band, and WHY (RFC 195). THE ONE HOME of the
 * argument for a separate tally -- every other mention of this map points here.
 *
 * A dropped band is counted in its OWN map and not as three more keys in
 * `fieldsCoercedToNull`, because the two count different events: a coercion
 * says "column X was coerced to null", and every key in that map is a
 * `colleges` column name. A dropped band is ONE event about TWO columns, so it
 * is not a column that fell to null and it has no column name to be filed
 * under.
 *
 * [band] is therefore the band's own name with no suffix on it --
 * `sat_reading`, not `sat_reading_band_dropped`. The suffix existed only to
 * keep the key distinguishable inside `fieldsCoercedToNull`, which meant the
 * only way to read the tally back was to parse a key for its ending. This map
 * counts nothing else, so the name can be the name.
 */
data class PairDrop(
  val band: String,
  val reason: PairDropReason,
)

/**
 * Per-column counts of WHY a status-preserving read produced no value (RFC
 * 206, third arm added by RFC 218 / brief 0011 D45).
 *
 * ONE object rather than three parallel maps threaded side by side, because it
 * is one fact with three arms (brief 0011 D37): a cell that bore no usable
 * value either carried a number this loader refused ([coercedToNull]), or
 * carried the publisher's suppression sentinel ([suppressedByPublisher]), or
 * carried a PRESENT token this seam has never been taught
 * ([unrecognisedTokens]). They are never two at once, and a column that stops
 * carrying one of them is exactly the regression RFC 206 exists to make visible
 * -- which the per-STATUS row totals cannot show, because they do not name the
 * column.
 *
 * ALL THREE arms are keyed by the SOURCE COLUMN the cell was read from, so the
 * counts of one column can be read side by side and none needs a second name to
 * be looked up by. The third arm is keyed by column ALONE and not by
 * (column, token): the token is already named, per cell, in the WARN line that
 * still fires beside it, and a per-token key would make one malformed export
 * print thousands of map entries in the operator summary.
 */
internal class CellAbsenceTally {
  private val coerced = mutableMapOf<String, Int>()
  private val suppressed = mutableMapOf<String, Int>()
  private val unrecognised = mutableMapOf<String, Int>()

  /** Mechanism A's tally: a valid value outside the column's domain, nulled. */
  fun recordCoercedToNull(column: String) {
    coerced.merge(column, 1, Int::plus)
  }

  /** A cell the publisher withheld: never a coercion, and never school silence. */
  fun recordSuppressedByPublisher(column: String) {
    suppressed.merge(column, 1, Int::plus)
  }

  /**
   * A PRESENT token that is neither a known blank, nor a null sentinel, nor a
   * suppression sentinel, and does not parse: a spelling nobody taught this
   * seam. Counted so a sudden change is VISIBLE in the operator summary rather
   * than only discoverable by somebody who already suspects it (D45).
   */
  fun recordUnrecognisedToken(column: String) {
    unrecognised.merge(column, 1, Int::plus)
  }

  val coercedToNull: Map<String, Int> get() = coerced.toMap()

  val suppressedByPublisher: Map<String, Int> get() = suppressed.toMap()

  val unrecognisedTokens: Map<String, Int> get() = unrecognised.toMap()
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

  /**
   * The Scorecard's suppression sentinels, byte for byte -- the only
   * non-numeric cells with their own status (RFC 158).
   *
   * TWO spellings, and the second is not dead weight (RFC 206):
   *
   * - `PS` is what the publisher writes TODAY, in both the institution file
   *   and the field-of-study file. Recognising only the other one is why every
   *   suppressed `GRAD_DEBT_MDN` cell was stored as "the school did not report
   *   this" about a figure the publisher withheld. The count for the cohort
   *   measured while writing RFC 206 is in that RFC; it is a dated snapshot of
   *   a fetched, gitignored file, not a figure this repo can check.
   * - `PrivacySuppressed` is the spelling earlier cohort files used. It is
   *   KEPT, not replaced: dropping it would reclassify an archived file's
   *   suppressions as school silence -- this same defect, in reverse.
   *
   * Membership is EXACT. [stringOrNull]'s trim is the only normalisation; no
   * case-folding, no prefix or substring match. A sentinel set that guesses is
   * how a legitimate value becomes a suppression.
   */
  val SUPPRESSION_SENTINELS = setOf("PS", "PrivacySuppressed")

  /**
   * The publisher's NULL sentinels, byte for byte: with a blank cell, the only
   * spellings of "the school did not report this" this seam expects.
   *
   * Membership is EXACT, exactly as for [SUPPRESSION_SENTINELS]. The set exists
   * so a present token in NEITHER set is a spelling nobody has taught this seam
   * -- which is all `PS` ever was, for years, until RFC 206 -- and can be said
   * out loud instead of being passed off as school silence. It changes no
   * status: both sets of tokens still read as [StatusfulCell.NotReported].
   */
  val NULL_SENTINELS = setOf("NULL", "NA")

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

  /**
   * A status-preserving cell reading (RFC 158): the value, or WHY there is no
   * value. [intOrNull] collapses a suppression sentinel, `"NULL"`, `"NA"` and a
   * blank into one indistinguishable `null`; the canonical money store keys
   * absence on its reason (D3), so the canonical fill reads through these
   * variants instead. The existing readers are untouched -- every `colleges`
   * column keeps its RFC 139 behavior.
   */
  sealed interface StatusfulCell<out T> {
    /** A parseable, in-domain value: the `reported` status. */
    data class Reported<T>(
      val value: T,
    ) : StatusfulCell<T>

    /** One of the publisher's [SUPPRESSION_SENTINELS]: `suppressed_by_publisher`. */
    data object SuppressedByPublisher : StatusfulCell<Nothing>

    /** A blank cell or a publisher null sentinel (`NULL`/`NA`), or a
     * domain-coerced value (tallied by the caller's coercion map):
     * `not_reported_by_institution`. */
    data object NotReported : StatusfulCell<Nothing>
  }

  /**
   * The one sentinel-to-status mapping (D3's one home): blank and the
   * publisher null sentinels ("NULL", "NA", anything [parse] refuses) read as
   * not-reported with the reason kept, a [SUPPRESSION_SENTINELS] member keeps
   * its own reading. The int/double pair below differ only in [parse].
   *
   * The suppression is tallied HERE, where it is RECOGNISED, and under the
   * SOURCE column it was read from (RFC 206). Counting it further down -- in
   * the domain reader, say -- would count only the columns that happen to
   * carry a domain. The net-price cells carry none at all (a negative net
   * price is valid), so every suppression they ever carry -- the archived
   * institution files spell one, and the committed fixture keeps it -- would
   * be missing from a figure that reads as a complete one. A partial count
   * printed as a whole is the error class this RFC exists to remove, not one
   * to add.
   */
  private fun <T : Any> statusfulCell(
    record: CSVRecord,
    column: String,
    tally: CellAbsenceTally,
    parse: (String) -> T?,
  ): StatusfulCell<T> {
    val raw = stringOrNull(record, column) ?: return StatusfulCell.NotReported
    if (raw in SUPPRESSION_SENTINELS) {
      tally.recordSuppressedByPublisher(column)
      return StatusfulCell.SuppressedByPublisher
    }
    val value = parse(raw) ?: return mapToNotReported(record, column, raw, tally)
    return StatusfulCell.Reported(value)
  }

  /**
   * [StatusfulCell.NotReported] for a PRESENT cell that bore no readable value,
   * with a token this seam does not know NAMED in the log.
   *
   * A blank cell never arrives here -- [stringOrNull] has already turned it into
   * `null` -- and a [NULL_SENTINELS] member is school silence spelled the way
   * the publisher spells it, so both stay quiet. Anything else is a token the
   * seam has not been taught, and mapping it silently to absence is the exact
   * defect RFC 206 was written to repair: `PS` took that path for years and
   * every one of those cells reached families as "this school did not report
   * it". The status is unchanged -- this only refuses to be silent about it.
   *
   * COUNTED **AND** WARNED, and neither replaces the other (RFC 218, brief 0011
   * D45). This arm used to have no tally, so the WARN line was the whole report
   * -- and a log line is invisible until somebody already suspects a problem.
   * It now records [CellAbsenceTally.recordUnrecognisedToken] for the column,
   * which is what makes a sudden change visible in the operator summary; the
   * WARN line STAYS, because the counter says how many and in which column
   * while the line says which row and which token. Contrast the sibling per-row
   * coercion line at [logCoercedCell], which is DEBUG: a reader chasing a
   * number in the summary needs the token here, and the token is the one thing
   * a count cannot carry.
   */
  private fun mapToNotReported(
    record: CSVRecord,
    column: String,
    raw: String,
    tally: CellAbsenceTally,
  ): StatusfulCell<Nothing> {
    if (raw !in NULL_SENTINELS) {
      tally.recordUnrecognisedToken(column)
      logger.warn(
        "Unrecognised non-numeric cell read as not-reported [column={}] [line={}] [value={}]",
        column,
        record.recordNumber,
        raw,
      )
    }
    return StatusfulCell.NotReported
  }

  /**
   * Mechanism A over a status-preserving read: a valid value outside
   * `[min, max]` keeps the coercion tally by [column] and lands as
   * [StatusfulCell.NotReported] -- coerced-away is "no usable report", never a
   * fabricated suppression. A suppressed reading passes through UNTOUCHED and
   * is not tallied again here: it has no value to be out of domain, and
   * [statusfulCell] already counted it on the other arm of [tally] (RFC 206).
   * An absent reading is tallied on neither -- the row-level status counts
   * already say how many there were.
   *
   * ONE name keys both arms: the source column the cell was read from. There
   * used to be a second `columnName` parameter for the coercion arm, and every
   * live call site passed the same string twice -- two adjacent `String`
   * parameters with opposite meanings, where a transposed call compiles, reads
   * the wrong column and mis-keys the tally. A name that is always the other
   * name is not a name.
   */
  private fun <T : Comparable<T>> StatusfulCell<T>.coercedToDomain(
    recordNumber: Long,
    min: T,
    max: T,
    column: String,
    tally: CellAbsenceTally,
  ): StatusfulCell<T> =
    when (this) {
      is StatusfulCell.Reported -> {
        if (value < min || value > max) {
          tally.recordCoercedToNull(column)
          logCoercedCell(column, recordNumber, value, min, max)
          StatusfulCell.NotReported
        } else {
          this
        }
      }

      StatusfulCell.SuppressedByPublisher -> {
        this
      }

      StatusfulCell.NotReported -> {
        this
      }
    }

  /** [intOrNull] with the absence reason preserved; see [StatusfulCell]. */
  fun statusfulIntCell(
    record: CSVRecord,
    column: String,
    tally: CellAbsenceTally,
  ): StatusfulCell<Int> = statusfulCell(record, column, tally, String::toIntOrNull)

  /** Mechanism A over a status-preserving int read; see [coercedToDomain]. */
  fun statusfulIntCellInDomain(
    record: CSVRecord,
    column: String,
    min: Int,
    max: Int,
    tally: CellAbsenceTally,
  ): StatusfulCell<Int> = statusfulIntCell(record, column, tally).coercedToDomain(record.recordNumber, min, max, column, tally)

  /**
   * [doubleOrNull] with the absence reason preserved; see [StatusfulCell].
   *
   * Non-finite parses (`NaN`, `Infinity`) are REJECTED here and take the
   * unrecognised-token arm (counted and WARNed) rather than reaching a dollar
   * conversion that would throw on them: one impossible cell must not abort a
   * wholesale rebuild.
   */
  fun statusfulDoubleCell(
    record: CSVRecord,
    column: String,
    tally: CellAbsenceTally,
  ): StatusfulCell<Double> = statusfulCell(record, column, tally) { raw -> raw.toDoubleOrNull()?.takeIf { it.isFinite() } }

  /** Mechanism A over a status-preserving double read; see [coercedToDomain]. */
  fun statusfulDoubleCellInDomain(
    record: CSVRecord,
    column: String,
    min: Double,
    max: Double,
    tally: CellAbsenceTally,
  ): StatusfulCell<Double> = statusfulDoubleCell(record, column, tally).coercedToDomain(record.recordNumber, min, max, column, tally)

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

  /**
   * Mechanism A for an optional PAIR of bounded int cells: both, or NEITHER.
   *
   * Some measures are a RANGE rather than two numbers that happen to sit beside
   * each other -- a 25th/75th percentile score band is the case this was
   * written for (RFC 195) -- and a range has two properties no per-cell read
   * can express:
   *
   * - it is a PAIR. Half a range is not a range.
   * - it is ORDERED. `upper >= lower`, or the two cells describe nothing.
   *
   * Both rules live HERE rather than at a call site because both are true in
   * any domain, and because the cost of getting them wrong is paid by a
   * different row than the one at fault: a table that CHECKs the pairing and
   * the ordering rejects the whole INSERT, so `upsertWithSavepoint` records a
   * skip and the run loses the entity -- its name, its location, everything --
   * over one unusable measure. Dropping the PAIR keeps the row.
   *
   * `>=`, not `>`: a range whose two ends land on the same value is a real
   * narrow population, not a fault. That is the same boundary the `colleges`
   * CHECKs draw.
   *
   * The body reads read / read / classify / act, on the
   * `val reason = classifyUpsertFailure(error)` precedent in this same file:
   * [classifyPairDrop] decides WHAT this pair is and [recordPairDrop] decides
   * what happens to it, so neither question is answered inside the other.
   *
   * [scale] is ONE range rather than a `min` and a `max`, because two int
   * parameters of the same type beside each other are transposable in silence
   * and an inverted domain admits nothing at all.
   *
   * Each cell is first coerced by [intInDomainOrNull], so an out-of-domain half
   * is tallied under its own column name; a pair then dropped is tallied ONCE
   * more in [pairsDropped] under [pairName] with its [PairDropReason]. That
   * tally is a separate accumulator from [coercions] because it counts a
   * different event -- see [PairDrop].
   *
   * [lowerColumnName]/[upperColumnName] are the DESTINATION column names, the
   * same argument [intInDomainOrNull] calls `columnName`, and they are not
   * [lowerColumn]/[upperColumn]: the source CSV header (`SATVR25`) and the
   * `colleges` column it lands in (`sat_reading_25th_percentile_score`) are two
   * different names for two different things, sitting next to each other here.
   */
  fun intPairInDomainOrNull(
    record: CSVRecord,
    lowerColumn: String,
    upperColumn: String,
    scale: IntRange,
    lowerColumnName: String,
    upperColumnName: String,
    pairName: String,
    coercions: MutableMap<String, Int>,
    pairsDropped: MutableMap<PairDrop, Int>,
  ): CellPair {
    val lower = intInDomainOrNull(record, lowerColumn, scale.first, scale.last, lowerColumnName, coercions)
    val upper = intInDomainOrNull(record, upperColumn, scale.first, scale.last, upperColumnName, coercions)
    val reason = classifyPairDrop(record, lowerColumn, upperColumn, lower, upper)
    return when (reason) {
      // The VALID outcome, spelled out. It used to be the `when`'s `else` arm,
      // which made "this pair is a range" the thing that happened when no
      // refusal matched rather than a case anyone wrote down.
      null -> CellPair(lower, upper)

      else -> recordPairDrop(record, lowerColumn, upperColumn, lower, upper, pairName, reason, pairsDropped)
    }
  }

  /**
   * What is wrong with this pair, or null when nothing is (RFC 195).
   *
   * The third arm is the one a per-cell reader cannot see. A cell that is
   * PRESENT and unparseable -- a [SUPPRESSION_SENTINELS] member, `NA`,
   * `1,200` -- is nulled
   * by [intOrNull] with no tally, because a cell that does not parse is the
   * ordinary blank-cell idiom everywhere else in this file. For a pair it is
   * not ordinary: it is a published band we could not read, and it arrives at
   * the database looking exactly like a school that publishes no band.
   *
   * EITHER cell is enough, and the MIXED pair is why. A blank cell beside a
   * `PrivacySuppressed` one resolves to two nulls just as two junk cells do,
   * and an `&&` here made that row silent: neither half survives, no earlier
   * arm matches, and the loss reaches `colleges` as two nulls nothing counted.
   * A wholly BLANK pair stays uncounted -- that is a school reporting nothing,
   * which is ordinary and would bury the corrupt rows this tally exists for.
   *
   * It is deliberately NOT extended to cells that parsed and were coerced for
   * being out of domain: those are already counted, by name, under their own
   * two columns.
   */
  private fun classifyPairDrop(
    record: CSVRecord,
    lowerColumn: String,
    upperColumn: String,
    lower: Int?,
    upper: Int?,
  ): PairDropReason? =
    when {
      (lower == null) != (upper == null) -> {
        PairDropReason.HALF_A_PAIR
      }

      lower != null && upper != null && upper < lower -> {
        PairDropReason.INVERTED
      }

      lower == null && upper == null && (isUnreadable(record, lowerColumn) || isUnreadable(record, upperColumn)) -> {
        PairDropReason.UNREADABLE
      }

      else -> {
        null
      }
    }

  /** True when the cell is PRESENT and is not an integer -- written, but not readable as a number. */
  private fun isUnreadable(
    record: CSVRecord,
    column: String,
  ): Boolean {
    val raw = stringOrNull(record, column) ?: return false
    return raw.toIntOrNull() == null
  }

  /**
   * Drops a pair whole: the DEBUG line for this row, the durable tally for the
   * run, and two nulls.
   *
   * DEBUG and not WARN, and that is the one choice here worth stating. The
   * durable record of a lost band is the run tally in [LoadCount.pairsDropped],
   * which the summary always prints; this line is the per-row drill-down an
   * operator turns on once the summary has told them there is something to
   * look for.
   */
  private fun recordPairDrop(
    record: CSVRecord,
    lowerColumn: String,
    upperColumn: String,
    lower: Int?,
    upper: Int?,
    pairName: String,
    reason: PairDropReason,
    pairsDropped: MutableMap<PairDrop, Int>,
  ): CellPair {
    logger.debug(
      "Dropping pair [line={}] [pair={}] [columns={}/{}] [lower={}] [upper={}]: {}",
      record.recordNumber,
      pairName,
      lowerColumn,
      upperColumn,
      lower,
      upper,
      reason.value,
    )
    pairsDropped.merge(PairDrop(pairName, reason), 1, Int::plus)
    return CellPair(null, null)
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
    logCoercedCell(columnName, line, value, min, max)
  }

  /**
   * The per-cell DEBUG line alone, without the tally. Split out because the
   * status-preserving readers keep their count in a [CellAbsenceTally] and the
   * optional-metric readers keep theirs in a bare map (RFC 206): two counters,
   * one sentence, said in one place.
   */
  private fun logCoercedCell(
    columnName: String,
    line: Long,
    value: Any,
    min: Any,
    max: Any,
  ) {
    logger.debug(
      "Coercing out-of-domain cell to null [column={}] [line={}] [value={}] [domainMin={}] [domainMax={}]",
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
   * failure carrying PostgreSQL diagnostics ([ConstraintDiagnostics] — a CHECK,
   * a unique violation, or a FOREIGN KEY rejection) is bucketed by its
   * constraint name; any other
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
   * [ConstraintDiagnostics] failure (null otherwise) and the categorized cause. A
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
    // [ConstraintDiagnostics], not [ConstraintViolationException]: a `23503`
    // arrives as a NotFoundException carrying the same two fields, and since
    // migration 0067 a row rejected by `colleges_state_codebook_fkey` is a
    // routine skip. Matching only the CHECK/unique type logged that skip as
    // [constraint=null] [detail=null] — the two things that name the value.
    val violation = error as? ConstraintDiagnostics
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
   *
   * The named bucket keys off [ConstraintDiagnostics], which BOTH violation
   * types implement: a foreign-key rejection is a `NotFoundException`, and
   * bucketing it under the unnamed `PermanentError` arm below lost the
   * constraint that says which value was bad.
   */
  fun classifyUpsertFailure(error: Throwable?): SkipReason =
    when (error) {
      null -> SkipReason.UnknownFailure
      is TransientError -> SkipReason.Transient
      is ConstraintDiagnostics -> SkipReason.ConstraintViolation(error.constraint)
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

/**
 * The one cell-reading -> [FigureReading] mapping (RFC 158 D3), written once: a
 * value exists exactly when its status bears one, by construction. Lifted out of
 * [CanonicalMoneyLoader] so the field-of-study loader shares the same three arms
 * rather than carrying a second copy (RFC 218): a fourth [FigureStatus] arm must
 * still be added here and nowhere else.
 */
internal fun <T> CsvIngestSupport.StatusfulCell<T>.reading(): FigureReading<T> =
  when (this) {
    is CsvIngestSupport.StatusfulCell.Reported -> FigureReading.Present(value, ValueBearingStatus.REPORTED)
    CsvIngestSupport.StatusfulCell.SuppressedByPublisher -> FigureReading.Absent(AbsenceStatus.SUPPRESSED_BY_PUBLISHER)
    CsvIngestSupport.StatusfulCell.NotReported -> FigureReading.Absent(AbsenceStatus.NOT_REPORTED_BY_INSTITUTION)
  }
