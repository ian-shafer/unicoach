package ed.unicoach.college

import ed.unicoach.common.models.ValidationError
import ed.unicoach.common.models.ValidationResult
import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.Database
import ed.unicoach.db.dao.AutomaticAwardsDao
import ed.unicoach.db.dao.BandWriteOutcome
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.dao.EmptyAwardPruneException
import ed.unicoach.db.dao.ExternalDocumentsDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.UpsertOutcome
import ed.unicoach.db.dao.WrittenRow
import ed.unicoach.db.models.AdmissionTest
import ed.unicoach.db.models.AutomaticAwardBandId
import ed.unicoach.db.models.AutomaticAwardId
import ed.unicoach.db.models.AutomaticAwardKind
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.DatePrecision
import ed.unicoach.db.models.LastSittingConsidered
import ed.unicoach.db.models.NewAutomaticAward
import ed.unicoach.db.models.NewAutomaticAwardBand
import ed.unicoach.db.models.NewExternalDocument
import ed.unicoach.db.models.PartialDate
import ed.unicoach.db.models.PartialDateColumns
import ed.unicoach.db.models.PublishedScoreScale
import ed.unicoach.db.models.ResidencyBasis
import ed.unicoach.db.models.ScholarshipSuperscoreRule
import ed.unicoach.db.models.ScoreRequirement
import ed.unicoach.db.models.SourceUrlStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.csv.CSVRecord
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The automatic-award seed as one all-or-nothing provenance group, shaped
 * exactly like [CdsSources]: each file carries the caller's original argument,
 * so the `college_index_build` row records what the operator named rather than a
 * scratch path.
 */
data class AutomaticAwardSources(
  val awards: SourceFile,
  val bands: SourceFile,
) {
  /** The two files in provenance order, for digesting. */
  val files: List<SourceFile> get() = listOf(awards, bands)

  /**
   * Each file paired with the ROLE it fills -- the ingest flag the operator
   * typed, without its punctuation. Named HERE, where the field and its role are
   * both known, for [CdsSources.namedFiles]' reason: a positional zip against a
   * flag list declared in another file mislabels which option failed the moment
   * either list is reordered.
   */
  val namedFiles: List<Pair<String, SourceFile>>
    get() =
      listOf(
        "awards" to awards,
        "award-bands" to bands,
      )
}

/**
 * Loader for the repo-committed automatic-award corpus (RFC 239, slice
 * `test/07/a-score-is-money`): `db/seed/automatic-awards/awards.csv`,
 * `bands.csv` and the `link-status.csv` sidecar beside them, into
 * `college_automatic_awards` and `college_automatic_award_bands`.
 *
 * IT REFUSES, IT NEVER SKIPS, and that is the one way it differs from
 * [CdsSeedLoader] -- which tolerates a seed UNITID with no `colleges` row
 * because its corpus is machine-generated from a federal file covering schools
 * our Scorecard snapshot lacks. This corpus is TYPED BY HAND, one row per school
 * per entry year per residency basis (`db/seed/automatic-awards/awards.csv` is
 * the one home of how many that is), and the measured failure mode is not
 * absence but MIS-KEYING: a fuzzy name match of the curated schools against the
 * loaded `colleges` table returned EIGHT wrong schools -- Arizona State matched
 * Arizona Christian, Ohio State matched Shawnee State, Michigan matched
 * Michigan-Flint. A mis-keyed row here is not a missing
 * answer, it is another school's money under this school's name. So a `unit_id`
 * with no college, and a `college_name` that disagrees with `colleges.name` for
 * that unit id, are both fatal and both name the row.
 *
 * THE ORDER IS FIXED AND IT IS PART OF THE CONTRACT: `external_documents`
 * first, then awards, then bands.
 *
 *  1. Every url the sidecar carries is upserted with the verdict and day
 *     COMMITTED beside the corpus -- including urls no award row cites (the ACT
 *     composite-rounding page is one). [ExternalDocumentsDao.upsert] and never
 *     `idOf`, because this loader is the caller that HAS the answer.
 *  2. Every award row then cites documents that already exist, so the
 *     `citationIds` call the DAO makes takes its conflict arm and cannot record
 *     "we asked on the reading day" over a real swept verdict (RFC 182).
 *  3. Bands hang off the award resolved by its natural key.
 *
 * A url an award row cites and the sidecar does not mention is therefore a
 * REFUSAL, not a defaulted `unknown`: step 2 would otherwise silently invent the
 * evidence RFC 220 requires a citation to state. For the same reason an ABSENT
 * sidecar is fatal here, where [CdsSeedLoader] tolerates one -- that loader's
 * corpus is swept by a script and may legitimately run before the first sweep;
 * this one's answers are committed beside the rows they describe.
 *
 * Everything loads inside ONE connection, so any refusal rolls the whole corpus
 * back rather than leaving a half-applied grid.
 */
class AutomaticAwardSeedLoader(
  private val database: Database,
  /**
   * Where the blocking file reads run. OWNED HERE and not asked of the caller
   * ([CollegeSfaLoader]'s contract): this class opens three CSVs, and a header
   * check that states its threading requirement in prose is one a new caller
   * meets by accident or not at all.
   */
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
  /**
   * One corpus file, and every name anything downstream calls it by -- the
   * defect [label] and the line the run report prints. On the member for
   * [CdsSeedLoader.Table]'s reason: a separate literal list zipped against a
   * positional list of summaries mislabels them all the day the order changes.
   */
  enum class Table(
    val label: String,
    val logLabel: String,
  ) {
    AWARDS("awards", "awards"),
    BANDS("bands", "bands"),
  }

  /**
   * The closed set of ways this hand-typed corpus can disagree with the loader.
   * Every variant carries the coordinates an operator needs -- file, CSV line,
   * column, raw value -- as typed fields rather than as prose interpolated at
   * the throw site, so a caller (and this suite's tests) can read them without
   * parsing a sentence.
   */
  sealed interface Defect {
    data class HeaderMismatch(
      val file: String,
      /** The caller's ORIGINAL argument beside [file] (RFC 139): an `s3://` source resolves to a path nobody typed. */
      val sourceArg: String,
      val expected: List<String>,
      val actual: List<String>,
    ) : Defect {
      val missing: List<String> get() = expected - actual.toSet()
      val unexpected: List<String> get() = actual - expected.toSet()
    }

    data class RowArity(
      val table: Table,
      val line: Long,
      val cells: Int,
      val expectedCells: Int,
    ) : Defect

    data class NotAnInteger(
      val table: Table,
      val line: Long,
      val column: String,
      val value: String,
    ) : Defect

    data class NotADecimal(
      val table: Table,
      val line: Long,
      val column: String,
      val value: String,
    ) : Defect

    data class NotADate(
      val table: Table,
      val line: Long,
      val column: String,
      val value: String,
    ) : Defect

    data class EmptyRequiredCell(
      val table: Table,
      val line: Long,
      val column: String,
    ) : Defect

    data class UnknownCode(
      val table: Table,
      val line: Long,
      val column: String,
      val value: String,
      val allowed: List<String>,
    ) : Defect

    /** A year cell outside the academic years the store admits ([AcademicYear]'s own range). */
    data class NotAnAcademicYear(
      val table: Table,
      val line: Long,
      val column: String,
      val value: Int,
    ) : Defect

    /**
     * A `unit_id` no `colleges` row carries.
     *
     * FATAL, where the CDS loader skips: a hand-typed row has no acceptable
     * silent drop, and a row silently absent from this corpus reads
     * downstream as `NotCurated` -- "we have not read this school" -- which is
     * false about a school somebody DID read.
     */
    data class UnknownCollege(
      val table: Table,
      val line: Long,
      val ipedsUnitId: Int,
      val typedName: String,
    ) : Defect

    /**
     * The typed `college_name` disagrees with `colleges.name` for that
     * `unit_id`. THE MEASURED FAILURE MODE, and the reason the name is in the
     * CSV at all: eight of forty-eight fuzzy matches were the wrong school.
     */
    data class CollegeNameDisagreement(
      val line: Long,
      val ipedsUnitId: Int,
      val typedName: String,
      val storedName: String,
    ) : Defect

    /**
     * Two award rows on one (unit_id, entry_year, residency_basis): the later would silently overwrite the earlier.
     *
     * THE DECODED VALUES RIDE, not their slugs: [entryYear] and
     * [residencyBasis] are closed types that were in hand at the throw site,
     * and the spelling of either belongs at the boundary that renders the
     * message ([renderAutomaticAwardDefect]) rather than in the payload a test
     * or a caller reads.
     */
    data class DuplicateAwardKey(
      val line: Long,
      val firstLine: Long,
      val ipedsUnitId: Int,
      val entryYear: AcademicYear,
      val residencyBasis: ResidencyBasis,
    ) : Defect

    /** A band row whose (unit_id, entry_year, residency_basis) names no award row. */
    data class BandWithNoAward(
      val line: Long,
      val ipedsUnitId: Int,
      val entryYear: AcademicYear,
      val residencyBasis: ResidencyBasis,
    ) : Defect

    /**
     * A band under a `none_published` award row.
     *
     * A cross-table CHECK needs a trigger in PostgreSQL and a trigger on a
     * corpus written only by this loader buys less than this refusal (D28), so
     * the DAO's write door refuses it and this is that refusal LOCATED: the
     * door answers with [AutomaticAwardsDao.BandWriteOutcome.NoGridPublished],
     * which names the award, and only this loader can name the CSV line.
     */
    data class BandUnderNonePublished(
      val line: Long,
      val ipedsUnitId: Int,
      val entryYear: AcademicYear,
      val residencyBasis: ResidencyBasis,
    ) : Defect

    /**
     * Two band rows on one natural key `(award, award_name, test, score_low,
     * gpa_low)`: the later would silently overwrite the earlier's money.
     *
     * The award key gets this refusal for the same reason one table up. The
     * natural-key upsert RESOLVES such a pair -- last row wins, counted as a
     * change, named nowhere -- which is not the same thing as catching it.
     */
    data class DuplicateBandKey(
      val line: Long,
      val firstLine: Long,
      val ipedsUnitId: Int,
      val awardName: String,
      val test: AdmissionTest?,
      val scoreLow: Int?,
      /** The GPA floor AS THE KEY COMPARES IT -- see [BandKey.of], which is the one place that canonicalises it. */
      val gpaLow: BigDecimal?,
    ) : Defect

    /**
     * A band row stating neither an amount nor the school's own words for what
     * it pays (`college_automatic_award_bands_award_has_value_check`): a named
     * band with no value is a heading over nothing.
     */
    data class BandStatesNoValue(
      val line: Long,
      val ipedsUnitId: Int,
      val awardName: String,
    ) : Defect

    /**
     * A band whose score range and `test` cell disagree
     * (`college_automatic_award_bands_score_iff_test_check`): a score band is a
     * band ON a test, and a row with one and not the other is half a cell.
     */
    data class BandScoreAndTestDisagree(
      val line: Long,
      val ipedsUnitId: Int,
      val awardName: String,
      val test: String?,
      val scoreLow: Int?,
      val scoreHigh: Int?,
    ) : Defect

    /**
     * A stated rule whose publisher's words are missing, or words beside a rule
     * the row denies having read.
     *
     * The schema's `..._has_words_check` pair is an IFF and the read model
     * `require`s it; both refuse the row without being able to say WHICH row.
     * Read as ONE fact here so the person who typed it is told the line.
     */
    data class ClaimAndWordsDisagree(
      val line: Long,
      val ruleColumn: String,
      /** The CODE, as the corpus spells it -- a member of a closed set, never a rendering of a date. */
      val rule: String,
      val wordsColumn: String,
    ) : Defect

    /**
     * A stored score deadline with no sentence it was read from
     * (`..._deadline_has_words_check`).
     *
     * ITS OWN VARIANT, and not a `ClaimAndWordsDisagree` carrying a date
     * stringified to fit a `rule: String`: the two facts are a coded claim and
     * a parsed DAY, and flattening the day into the other variant's field left
     * no reader able to tell one from the other or to switch on either. The
     * DATE rides; the rendering happens at the boundary.
     */
    data class DeadlineWithoutWords(
      val line: Long,
      val deadlineColumn: String,
      val deadline: LocalDate,
      val wordsColumn: String,
    ) : Defect

    /**
     * `awards.csv` carried no award row at all.
     *
     * Refused rather than obeyed, because the run's last act is to drop every
     * award the corpus no longer names: an empty corpus would read as "drop
     * every school we publish", which is a corpus somebody truncated and never
     * an instruction.
     */
    data class EmptyAwardCorpus(
      val file: String,
    ) : Defect

    /**
     * A url an award row cites that the committed sidecar does not mention.
     *
     * Not a defaulted `unknown`: with RFC 220's strict `external_documents` the
     * citing write would state evidence nobody gathered. The corpus and its link
     * answers are committed together, so a url missing from one of them is a
     * corpus that was edited by halves.
     */
    data class UncheckedUrl(
      val line: Long,
      val column: String,
      val url: String,
      val statusFile: String,
    ) : Defect

    /** The sidecar this corpus REQUIRES is not a file. See the class KDoc for why absence is fatal here. */
    data class MissingLinkStatusFile(
      val file: String,
    ) : Defect

    data class SidecarRowArity(
      val file: String,
      val line: Long,
      val cells: Int,
      val expectedCells: Int,
    ) : Defect

    /** A sidecar row whose `url` cell is empty -- a verdict about nothing. */
    data class SidecarEmptyUrl(
      val file: String,
      val line: Long,
    ) : Defect

    data class SidecarUnknownStatus(
      val file: String,
      val line: Long,
      val url: String,
      val value: String,
      val allowed: List<String>,
    ) : Defect

    /** A sidecar `url_checked_on` cell that is not an ISO `yyyy-mm-dd` day. */
    data class SidecarNotADate(
      val file: String,
      val line: Long,
      val url: String,
      val value: String,
    ) : Defect

    /**
     * A sidecar row with no day beside its verdict.
     *
     * Empty is refused for EVERY status here, not only the two that
     * `source_documents_checked_when_known` binds: this corpus's answers are
     * committed by hand, and "we asked and cannot say when" is not a state a
     * hand-written file gets to be in. [NewExternalDocument.urlCheckedOn] is
     * non-null, so the alternative is this loader choosing a day.
     */
    data class SidecarMissingDate(
      val file: String,
      val line: Long,
      val url: String,
      val status: SourceUrlStatus,
    ) : Defect

    /**
     * A sidecar `url` cell that is not an absolute http(s) page.
     *
     * [NewExternalDocument] refuses the same string by VALUE, with no idea
     * which row produced it. This corpus's whole discipline is telling the
     * person who typed it which row to fix, so the refusal is made where the
     * file and the line are still in hand.
     */
    data class SidecarUrlNotAWebPage(
      val file: String,
      val line: Long,
      val url: String,
    ) : Defect

    /** Two sidecar rows giving one url DIFFERENT answers: resolved by row order it would be the later that decided. */
    data class SidecarConflictingStatus(
      val file: String,
      /** The line the FIRST answer was read from -- named, so fixing the file starts here. */
      val firstLine: Long,
      val line: Long,
      val url: String,
      val first: RecordedLinkStatus,
      val second: RecordedLinkStatus,
    ) : Defect

    /** `last_sitting_through` and `last_sitting_precision` are a PAIR (D23): one without the other is half a fact. */
    data class LastSittingPairBroken(
      val line: Long,
      val through: String,
      val precision: String,
    ) : Defect

    /**
     * A `last_sitting_through` the stored-date codec refused -- `2026-12-15` at
     * `month` precision is the case this corpus meets.
     *
     * The column stores the period's start and the service reads the period's
     * END, so a mid-period date would silently shorten a school's window.
     * Refused here as well as by `is_period_start` in SQL, because here it can
     * name the line.
     *
     * [decodeError] is the CODEC'S OWN answer, carried rather than restated.
     * `decode` refuses two different things, and an arm that picked one of them
     * on the strength of a comment would report the wrong one by name the day
     * it grows a third -- at a CSV line somebody has to fix by hand.
     */
    data class LastSittingNotAPeriodStart(
      val line: Long,
      val through: String,
      val precision: String,
      val decodeError: ValidationError,
    ) : Defect

    /**
     * A GPA bound outside the shape `gpa_low`/`gpa_high` STORE: more than
     * [PUBLISHED_GPA_SCALE] decimals, or outside the range the table's own
     * `..._gpa_range_check` admits.
     *
     * REFUSED RATHER THAN STORED, because `NUMERIC(4,3)` ROUNDS a fourth
     * decimal instead of rejecting it -- and it rounds it on the way IN, before
     * any CHECK can see what was typed. So `3.7499` becomes `3.750`, collides
     * with the school's real `3.75` tier on the natural key, and the later
     * row's money silently replaces the earlier row's. The column was widened
     * from `NUMERIC(3,2)` to stop exactly that collision at three decimals;
     * this is the same failure one decimal along, and the loader is the only
     * door that can still see the cell as it was typed.
     */
    data class GpaOutsidePublishedShape(
      val table: Table,
      val line: Long,
      val column: String,
      val value: String,
    ) : Defect

    /**
     * A band edge that is not a score on its own test's PUBLISHED scale --
     * `126` typed for `1260`.
     *
     * The scale is [PublishedScoreScale]'s, the same set
     * `add-college-score-bands.sql` states as CHECKs: a value outside it is a
     * typing fault, never a band. A band nobody's score can land in is
     * indistinguishable downstream from a school that pays nothing there.
     */
    data class ScoreOutsidePublishedScale(
      val line: Long,
      val column: String,
      val test: AdmissionTest,
      val value: Int,
      val scale: IntRange,
    ) : Defect
  }

  /**
   * A corpus file whose shape disagrees with this loader: fatal, never skipped.
   * The structured [defect] is the payload; the message is one rendering of it,
   * produced at the boundary rather than at each throw site.
   */
  class FormatException(
    val defect: Defect,
    cause: Throwable? = null,
  ) : RuntimeException(renderAutomaticAwardDefect(defect), cause)

  /**
   * A DB failure on ONE corpus row, restating that row's coordinates with the
   * DAO exception as the cause -- a CHECK violation on row 41 of 60 must not
   * surface as a bare "Database constraint violation".
   */
  class LoadException(
    val table: Table,
    val line: Long,
    val ipedsUnitId: Int,
    cause: Throwable,
  ) : RuntimeException(
      "[${table.label}] line [$line]: unit_id [$ipedsUnitId] failed: [${cause.message}]",
      cause,
    )

  /**
   * Per-file upsert disposition counts. No `skipped`: this loader has no such
   * outcome.
   *
   * A VALUE THE RUN REPLACES, not a counter a row reader is handed. `load`'s
   * own comment says the run's books are the run's, so a row that wrote
   * something answers with what it wrote and the phase adds it up -- nothing
   * mutates a tally behind a `Unit`-returning call.
   */
  data class TableSummary(
    val upserted: Int = 0,
    val changed: Int = 0,
    val unchanged: Int = 0,
  ) {
    val rows: Int get() = upserted + changed + unchanged

    /** The same counts with [outcome] added -- a NEW value, never a mutation of this one. */
    fun plus(outcome: UpsertOutcome): TableSummary =
      when (outcome) {
        UpsertOutcome.INSERTED -> copy(upserted = upserted + 1)
        UpsertOutcome.CHANGED -> copy(changed = changed + 1)
        UpsertOutcome.UNCHANGED -> copy(unchanged = unchanged + 1)
      }
  }

  /**
   * What the run wrote. [documents] is the sidecar's own row count -- every url
   * it carries is written, whether or not an award row cites it -- and
   * [bandsPruned] / [awardsPruned] are what a re-read DROPPED, one per table.
   */
  data class LoadResult(
    val documents: Int,
    val awards: TableSummary,
    val bands: TableSummary,
    val bandsPruned: Int,
    val awardsPruned: Int,
  )

  /**
   * One award row's identity in the corpus, which is the key `bands.csv` names
   * it by.
   *
   * THE DECODED VALUES, not their slugs. `residency_basis` is decoded to a
   * closed [ResidencyBasis] one line before the key is built, and flattening it
   * to `String` bought nothing -- enum equality and data-class equality are the
   * same equality -- while leaving the one axis that decides which of a
   * school's two grids a band hangs off self-describing only to a reader who
   * remembers the slug vocabulary.
   */
  private data class CorpusKey(
    val ipedsUnitId: Int,
    val entryYear: AcademicYear,
    val residencyBasis: ResidencyBasis,
  )

  /**
   * An award this run wrote: what the bands under it need to know, and what a
   * refusal ABOUT it must name.
   *
   * [ipedsUnitId] rides here rather than being fetched from the key at each
   * site, because a caller that holds only this record could otherwise name no
   * school at all -- which is how the band prune came to report `unit_id [0]`.
   */
  private data class LoadedAward(
    val id: AutomaticAwardId,
    val line: Long,
    val ipedsUnitId: Int,
    val kind: AutomaticAwardKind,
  )

  /**
   * One band row's natural key, as `college_automatic_award_bands` keys it --
   * the pair of rows a duplicate would silently collapse into one.
   *
   * THE TYPED VALUES, canonicalised by [of] and never by a call site. The key
   * carried a slug and a pre-formatted GPA string before, whose format rule
   * lived in this paragraph alone -- and the two sites that built one had
   * already begun to spell it differently.
   */
  private data class BandKey(
    val awardId: AutomaticAwardId,
    val awardName: String,
    val test: AdmissionTest?,
    val scoreLow: Int?,
    /** The GPA floor AS THE COLUMN STORES IT. Built only by [of], never by a call site. */
    val gpaLow: BigDecimal?,
  ) {
    companion object {
      /**
       * The key of one cell, canonicalised in ONE place.
       *
       * [BandKey.gpaLow] is `setScale(PUBLISHED_GPA_SCALE)` -- the value
       * `NUMERIC(4,3)` actually holds -- and not the raw cell: `3.5`, `3.50`
       * and `3.500` are three different `BigDecimal`s, one stored row and one
       * published bound, so comparing raw values would miss exactly the
       * duplicate a typist makes. The scale is safe to force because
       * [getPublishedGpa] has already refused any bound the column would have
       * had to round.
       *
       * The rule lived at the call site before, in two spellings: the dedupe
       * compared `stripTrailingZeros().toPlainString()` and the refusal printed
       * `toPlainString()`, so one published cell was matched as `3.5` and
       * reported as `3.500`.
       */
      fun of(
        awardId: AutomaticAwardId,
        input: NewAutomaticAwardBand,
      ): BandKey =
        BandKey(
          awardId = awardId,
          awardName = input.awardName,
          test = input.test,
          scoreLow = input.scoreLow,
          gpaLow = input.gpaLow?.setScale(PUBLISHED_GPA_SCALE),
        )
    }
  }

  /**
   * Asserts all three headers, reading nothing else and writing nothing. Public
   * and callable on its own so the ingest validates this corpus BESIDE the
   * Scorecard, IPEDS and CDS files, before its first phase commits: a renamed
   * column here must not be discovered after six phases have written rows
   * (RFC 148 D10).
   *
   * It owns its own [ioDispatcher] hop -- the three reads open files, and no
   * caller has to know that.
   */
  suspend fun assertHeaders(sources: AutomaticAwardSources) =
    withContext(ioDispatcher) {
      assertHeader(sources.awards, AWARD_COLUMNS)
      assertHeader(sources.bands, BAND_COLUMNS)
      val sidecar = sidecarBeside(sources.awards.file)
      requireLinkStatusFile(sidecar)
      assertHeader(SourceFile(sidecar, sources.awards.sourceArg), LINK_STATUS_COLUMNS)
    }

  /**
   * Loads the corpus: documents, then awards, then bands, in one transaction.
   *
   * [linkStatusCsv] sits BESIDE the corpus it describes, so it is defaulted from
   * the awards file's own directory rather than made a third argument of every
   * caller.
   */
  suspend fun load(
    awardsCsv: File,
    bandsCsv: File,
    linkStatusCsv: File = sidecarBeside(awardsCsv),
  ): LoadResult =
    database.withConnection { session ->
      assertAllHeaders(awardsCsv, bandsCsv, linkStatusCsv)
      val linkStatuses = readLinkStatuses(linkStatusCsv)
      writeDocuments(session, linkStatuses)
      val awards = loadAwards(session, awardsCsv, linkStatusCsv, linkStatuses)
      val bands = loadBands(session, bandsCsv, awards.byKey)
      LoadResult(
        documents = linkStatuses.size,
        awards = awards.summary,
        bands = bands.summary,
        bandsPruned = pruneBands(session, awards.byKey, bands.written),
        awardsPruned = pruneAwards(session, awardsCsv, awards.byKey),
      )
    }

  /**
   * Every header of all three files, before any row of any of them is written,
   * so a renamed column in the second file never leaves the first half-trusted.
   *
   * Asserted here as well as at the ingest's up-front check because [load] is
   * also called directly (the suite), and this is the loader's own contract.
   */
  private fun assertAllHeaders(
    awardsCsv: File,
    bandsCsv: File,
    linkStatusCsv: File,
  ) {
    assertHeader(SourceFile(awardsCsv, awardsCsv.path), AWARD_COLUMNS)
    assertHeader(SourceFile(bandsCsv, bandsCsv.path), BAND_COLUMNS)
    // EXISTENCE FIRST, and it is not decoration: [assertHeader] opens the
    // file, so an absent sidecar reached it as a bare FileNotFoundException
    // naming a temp path -- outside this loader's "every refusal names the
    // corpus and says what to do" contract. Caught by the test that asserts
    // the named defect.
    requireLinkStatusFile(linkStatusCsv)
    assertHeader(SourceFile(linkStatusCsv, linkStatusCsv.path), LINK_STATUS_COLUMNS)
  }

  /**
   * What the awards phase produced: the index every band row is resolved
   * through, and what it wrote.
   *
   * THE RUN'S BOOKS ARE THE RUN'S. The phase keeps them and answers with them;
   * no row reader is handed a counter to write behind a `Unit`.
   */
  private data class LoadedAwards(
    val byKey: Map<CorpusKey, LoadedAward>,
    val summary: TableSummary,
  )

  /** What the bands phase produced: which cells each award ends the run with, and what it wrote. */
  private data class LoadedBands(
    val written: Map<AutomaticAwardId, List<AutomaticAwardBandId>>,
    val summary: TableSummary,
  )

  /** Every award row this corpus declares, refusing a key a second row repeats. */
  private fun loadAwards(
    session: SqlSession,
    awardsCsv: File,
    linkStatusCsv: File,
    linkStatuses: Map<String, RecordedLinkStatus>,
  ): LoadedAwards {
    val byKey = mutableMapOf<CorpusKey, LoadedAward>()
    var summary = TableSummary()
    forEachRow(awardsCsv, Table.AWARDS, AWARD_COLUMNS) { record ->
      val parsed = parseAward(record, linkStatusCsv, linkStatuses)
      byKey[parsed.key]?.let { first -> throw FormatException(duplicateAwardKeyOf(parsed, first)) }
      val written = writeAward(session, record, parsed)
      summary = summary.plus(written.outcome)
      byKey[parsed.key] = LoadedAward(written.id, parsed.line, parsed.ipedsUnitId, parsed.awardKind)
    }
    return LoadedAwards(byKey, summary)
  }

  /** One award key claimed twice, named by BOTH lines. */
  private fun duplicateAwardKeyOf(
    parsed: ParsedAward,
    first: LoadedAward,
  ): Defect =
    Defect.DuplicateAwardKey(
      parsed.line,
      first.line,
      parsed.ipedsUnitId,
      parsed.key.entryYear,
      parsed.key.residencyBasis,
    )

  /**
   * Every band row this corpus publishes, keyed to the award it hangs off.
   *
   * The two indexes are the PHASE'S: which cells each award ends the run with,
   * so the prune removes exactly what this corpus no longer publishes, and
   * which LINE first claimed each natural key, so a repeated one is refused
   * rather than resolved by row order.
   */
  private fun loadBands(
    session: SqlSession,
    bandsCsv: File,
    awards: Map<CorpusKey, LoadedAward>,
  ): LoadedBands {
    val written = mutableMapOf<AutomaticAwardId, MutableList<AutomaticAwardBandId>>()
    val bandLines = mutableMapOf<BandKey, Long>()
    var summary = TableSummary()
    forEachRow(bandsCsv, Table.BANDS, BAND_COLUMNS) { record ->
      val loaded = loadBand(session, record, awards, bandLines)
      bandLines[loaded.key] = loaded.line
      written.getOrPut(loaded.awardId) { mutableListOf() } += loaded.row.id
      summary = summary.plus(loaded.row.outcome)
    }
    return LoadedBands(written, summary)
  }

  /**
   * Every cell this corpus no longer publishes, dropped -- named by the row
   * that owns it.
   *
   * A re-read that DROPS a cell must drop it from the table too: a natural-key
   * upsert alone is idempotent for an unchanged corpus and leaves a stale band
   * standing for a corrected one, which is the coach quoting money the school
   * no longer pays.
   */
  private fun pruneBands(
    session: SqlSession,
    awards: Map<CorpusKey, LoadedAward>,
    written: Map<AutomaticAwardId, List<AutomaticAwardBandId>>,
  ): Int =
    awards.values.sumOf { award ->
      AutomaticAwardsDao
        .deleteBandsExcept(session, award.id, written[award.id].orEmpty())
        .getOrElse { cause -> throw LoadException(Table.BANDS, award.line, award.ipedsUnitId, cause) }
    }

  /**
   * Every school this corpus no longer names, dropped.
   *
   * THE SAME ARGUMENT ONE TABLE UP, and it is the stronger half: a stale BAND
   * misprices a school, and a stale AWARD prices a school this corpus no longer
   * names at all -- which is exactly where a corrected unit id sends the old
   * school's whole grid. Its bands go with it through the bands table's ON
   * DELETE CASCADE.
   */
  private fun pruneAwards(
    session: SqlSession,
    awardsCsv: File,
    awards: Map<CorpusKey, LoadedAward>,
  ): Int =
    AutomaticAwardsDao
      .deleteAwardsExcept(session, awards.values.map { it.id })
      .getOrElse { cause ->
        // THE DOOR'S OWN ANSWER, located, and read from the failure rather than
        // re-derived from this run's state: `awards.csv` with no row is a file
        // somebody truncated, not an instruction to unpublish every school. The
        // day the prune refuses for a SECOND reason, `awards.isEmpty()` here
        // would have reported this defect for it.
        if (cause is EmptyAwardPruneException) {
          throw FormatException(Defect.EmptyAwardCorpus(awardsCsv.path), cause)
        }
        // Every other failure here belongs to NO row -- one statement over
        // the whole table -- so it is raised as the DAO stated it. A
        // [LoadException] would have to invent a line and a unit id, and an
        // invented unit id is the message this loader has just stopped
        // printing.
        throw cause
      }

  /**
   * Every url the sidecar carries, written with the answer committed beside the
   * corpus.
   *
   * EVERY url, not only the cited ones. The ACT composite-rounding page is cited
   * by no award row and is the citation D-H's arithmetic rests on; a loader that
   * wrote only what awards.csv mentions would leave it uncitable.
   */
  private fun writeDocuments(
    session: SqlSession,
    linkStatuses: Map<String, RecordedLinkStatus>,
  ) {
    for ((url, recorded) in linkStatuses) {
      // NON-NULL BY CONSTRUCTION: this corpus reads its sidecar with
      // `dayAlwaysRequired`, so a verdict with no day was refused by file and
      // line before this loop began. The check is here because the shared
      // reader's type admits the corpus that legitimately has none.
      val checkedOn =
        checkNotNull(recorded.checkedOn) {
          "[$url] reached the document write with no check date; the sidecar reader must refuse that by line"
        }
      ExternalDocumentsDao
        .upsert(session, NewExternalDocument(url, recorded.status, checkedOn))
        .getOrThrow()
    }
  }

  /**
   * `link-status.csv` as a lookup by url, through the ONE reader every seed
   * corpus shares ([LinkStatusSidecar]).
   *
   * THREE COLUMNS -- `url,url_status,url_checked_on` -- which is what the other
   * two hand-typed corpora commit (`db/seed/test-state-programmes`,
   * `db/seed/fafsa-deadlines`) and what `external_documents` calls its own
   * columns. `bin/cds-link-sweep`'s five-column file is that ONE script's output
   * format, and that script sweeps `db/seed/cds` alone.
   *
   * Every cell is refused rather than repaired: this file decides whether a
   * family is shown a link. A DAY IS REQUIRED FOR EVERY STATUS here, where the
   * swept corpus requires one only where `source_documents_checked_when_known`
   * does -- these answers are committed by hand, and "we asked and cannot say
   * when" is not a state a hand-written file gets to be in.
   */
  private fun readLinkStatuses(file: File): Map<String, RecordedLinkStatus> {
    requireLinkStatusFile(file)
    return sidecarReader.read(file)
  }

  /**
   * The corpus's committed link answers must BE there. Asked before anything
   * opens the file, in all three places that read it, so absence is this
   * loader's named refusal rather than the CSV reader's `FileNotFoundException`
   * about a path the operator never typed.
   */
  private fun requireLinkStatusFile(file: File) {
    if (!file.isFile) throw FormatException(Defect.MissingLinkStatusFile(file.path))
  }

  /** This corpus's sidecar rules, mapped onto this corpus's own located defects. */
  private val sidecarReader =
    LinkStatusSidecar(
      columns = LINK_STATUS_COLUMNS,
      urlColumn = "url",
      statusColumn = "url_status",
      dateColumn = "url_checked_on",
      dayAlwaysRequired = true,
    ) { file, line, fault, cause ->
      throw FormatException(sidecarDefectOf(file, line, fault), cause)
    }

  /** One shared sidecar fault as THIS corpus names it. */
  private fun sidecarDefectOf(
    file: File,
    line: Long,
    fault: LinkStatusFault,
  ): Defect =
    when (fault) {
      is LinkStatusFault.RowArity -> {
        Defect.SidecarRowArity(file.path, line, fault.cells, fault.expectedCells)
      }

      LinkStatusFault.EmptyUrl -> {
        Defect.SidecarEmptyUrl(file.path, line)
      }

      is LinkStatusFault.UrlNotAWebPage -> {
        Defect.SidecarUrlNotAWebPage(file.path, line, fault.url)
      }

      is LinkStatusFault.UnknownStatus -> {
        Defect.SidecarUnknownStatus(file.path, line, fault.url, fault.value, fault.allowed)
      }

      is LinkStatusFault.NotADate -> {
        Defect.SidecarNotADate(file.path, line, fault.url, fault.value)
      }

      is LinkStatusFault.MissingDate -> {
        Defect.SidecarMissingDate(file.path, line, fault.url, fault.status)
      }

      is LinkStatusFault.ConflictingStatus -> {
        Defect.SidecarConflictingStatus(file.path, fault.firstLine, line, fault.url, fault.first, fault.second)
      }
    }

  /**
   * One awards.csv row as the corpus states it: the coordinates the run keys
   * on, and the row it writes once the college is resolved.
   *
   * PURE. Every refusal awards.csv can earn is reachable from here with no
   * session at all, and the run's index and counts stay with [load], which owns
   * them. [inputFor] closes over cells that are ALREADY READ -- it composes,
   * it does not parse -- so a malformed cell is refused before anything is
   * written rather than during the write.
   */
  private data class ParsedAward(
    val line: Long,
    val key: CorpusKey,
    val ipedsUnitId: Int,
    val typedName: String,
    val awardKind: AutomaticAwardKind,
    val inputFor: (CollegeId) -> NewAutomaticAward,
  )

  /** One awards.csv row, read and refused; nothing here touches the database. */
  private fun parseAward(
    record: CSVRecord,
    linkStatusCsv: File,
    linkStatuses: Map<String, RecordedLinkStatus>,
  ): ParsedAward {
    val table = Table.AWARDS
    val ipedsUnitId = getInt(record, "unit_id", table)
    val typedName = getString(record, "college_name", table)
    val entryYear = getAcademicYear(record, "entry_year", table)
    val residencyBasis = getResidencyBasis(record, table)
    val awardKind = getEnumCode(record, "award_kind", table, AutomaticAwardKind.entries) { it.value }
    val (scoreRequirement, scoreRequirementAsPublished) =
      getStatedClaim(
        record,
        table,
        "score_requirement",
        "score_requirement_as_published",
        ScoreRequirement.entries,
        ScoreRequirement.UNSTATED,
      ) { it.value }
    val (superscoreRule, superscoreAsPublished) =
      getStatedClaim(
        record,
        table,
        "superscore_rule",
        "superscore_as_published",
        ScholarshipSuperscoreRule.entries,
        ScholarshipSuperscoreRule.UNSTATED,
      ) { it.value }
    val scoreDeadline = getDateOrNull(record, "score_deadline", table)
    val scoreDeadlineAsPublished = getStringOrNull(record, "score_deadline_as_published")
    // ONE WAY, not an IFF, and the schema's `..._deadline_has_words_check` says
    // so too: a school may publish the sentence without a date we could parse,
    // but a stored date with no sentence is a date nobody can check us on.
    if (scoreDeadline != null && scoreDeadlineAsPublished == null) {
      throw FormatException(
        Defect.DeadlineWithoutWords(
          record.recordNumber,
          "score_deadline",
          scoreDeadline,
          "score_deadline_as_published",
        ),
      )
    }
    val lastSittingConsidered = lastSittingOf(record, table)
    val publisher = getString(record, "publisher", table)
    val sourceUrl = requireChecked(record, "source_url", getString(record, "source_url", table), linkStatuses, linkStatusCsv)
    val archiveUrl =
      getStringOrNull(record, "archive_url")?.let { url ->
        requireChecked(record, "archive_url", url, linkStatuses, linkStatusCsv)
      }
    val checkedOn = getDate(record, "checked_on", table)
    val expiresOn = getDate(record, "expires_on", table)
    return ParsedAward(
      line = record.recordNumber,
      key = CorpusKey(ipedsUnitId, entryYear, residencyBasis),
      ipedsUnitId = ipedsUnitId,
      typedName = typedName,
      awardKind = awardKind,
      inputFor = { collegeId ->
        NewAutomaticAward(
          collegeId = collegeId,
          entryYear = entryYear,
          residencyBasis = residencyBasis,
          awardKind = awardKind,
          scoreRequirement = scoreRequirement,
          scoreRequirementAsPublished = scoreRequirementAsPublished,
          superscoreRule = superscoreRule,
          superscoreAsPublished = superscoreAsPublished,
          scoreDeadline = scoreDeadline,
          scoreDeadlineAsPublished = scoreDeadlineAsPublished,
          lastSittingConsidered = lastSittingConsidered,
          publisher = publisher,
          sourceUrl = sourceUrl,
          archiveUrl = archiveUrl,
          checkedOn = checkedOn,
          expiresOn = expiresOn,
        )
      },
    )
  }

  /**
   * A coded claim and the publisher's words for it, read as ONE fact.
   *
   * The schema's `..._has_words_check` pair is an IFF and the read model
   * `require`s it, so reading the two cells independently is how half of it
   * reaches the database unlocated -- a CHECK eighteen columns later cannot
   * name the line somebody typed.
   */
  private fun <T> getStatedClaim(
    record: CSVRecord,
    table: Table,
    ruleColumn: String,
    wordsColumn: String,
    values: List<T>,
    unstated: T,
    slugOf: (T) -> String,
  ): Pair<T, String?> {
    val rule = getEnumCode(record, ruleColumn, table, values, slugOf)
    val words = getStringOrNull(record, wordsColumn)
    if ((rule == unstated) != (words == null)) {
      throw FormatException(
        Defect.ClaimAndWordsDisagree(record.recordNumber, ruleColumn, slugOf(rule), wordsColumn),
      )
    }
    return rule to words
  }

  /** One parsed award row written, once the school it names is CONFIRMED. */
  private fun writeAward(
    session: SqlSession,
    record: CSVRecord,
    parsed: ParsedAward,
  ): WrittenRow<AutomaticAwardId> {
    val collegeId = resolveCollege(session, record, parsed.ipedsUnitId, parsed.typedName)
    return withRowLocation(Table.AWARDS, record, parsed.ipedsUnitId) {
      AutomaticAwardsDao.upsertAward(session, parsed.inputFor(collegeId))
    }
  }

  /**
   * The college this row is about, refusing an unknown `unit_id` and a typed
   * name that disagrees with the stored one.
   *
   * The comparison is on the trimmed strings and is otherwise EXACT. A
   * case-insensitive or punctuation-folded comparison is what a fuzzy match is,
   * and a fuzzy match of these names is the thing that returned eight wrong
   * schools. "University of Michigan-Ann Arbor" and "University of
   * Michigan-Flint" differ by a suffix; nothing weaker than equality separates
   * them reliably.
   */
  private fun resolveCollege(
    session: SqlSession,
    record: CSVRecord,
    ipedsUnitId: Int,
    typedName: String,
  ): CollegeId {
    val college =
      withRowLocation(Table.AWARDS, record, ipedsUnitId) { CollegesDao.findByIpedsUnitId(session, ipedsUnitId) }
        ?: throw FormatException(Defect.UnknownCollege(Table.AWARDS, record.recordNumber, ipedsUnitId, typedName))
    if (college.name.trim() != typedName) {
      throw FormatException(
        Defect.CollegeNameDisagreement(record.recordNumber, ipedsUnitId, typedName, college.name),
      )
    }
    return college.id
  }

  /**
   * One band row: read, refused where it repeats a key, written.
   *
   * It answers with what the row produced -- the award it hangs off, the key it
   * claimed, and the written row -- and writes none of the run's books itself.
   * The phase that called it owns those ([loadBands]).
   */
  private fun loadBand(
    session: SqlSession,
    record: CSVRecord,
    awards: Map<CorpusKey, LoadedAward>,
    bandLines: Map<BandKey, Long>,
  ): LoadedBand {
    val parsed = parseBand(record, awards)
    refuseRepeatedBandKey(parsed, bandLines)
    return LoadedBand(
      awardId = parsed.award.id,
      key = parsed.naturalKey,
      line = parsed.line,
      row = writeBand(session, record, parsed),
    )
  }

  /** What one band row's write produced: the award it hangs off, the key it claimed, and the row. */
  private data class LoadedBand(
    val awardId: AutomaticAwardId,
    val key: BandKey,
    val line: Long,
    val row: WrittenRow<AutomaticAwardBandId>,
  )

  /**
   * One bands.csv row as the corpus states it, resolved against the award it
   * names.
   *
   * PURE, for [ParsedAward]'s reason: every refusal bands.csv can earn is
   * reachable from here with no session at all.
   */
  private data class ParsedBand(
    val line: Long,
    val key: CorpusKey,
    val ipedsUnitId: Int,
    val award: LoadedAward,
    val input: NewAutomaticAwardBand,
  ) {
    /** The cell's identity in the table, canonicalised in [BandKey.of] and nowhere else. */
    val naturalKey: BandKey get() = BandKey.of(award.id, input)
  }

  /** One bands.csv row, read and refused; nothing here touches the database. */
  private fun parseBand(
    record: CSVRecord,
    awards: Map<CorpusKey, LoadedAward>,
  ): ParsedBand {
    val table = Table.BANDS
    val ipedsUnitId = getInt(record, "unit_id", table)
    val entryYear = getAcademicYear(record, "entry_year", table)
    val residencyBasis = getResidencyBasis(record, table)
    val key = CorpusKey(ipedsUnitId, entryYear, residencyBasis)
    val award =
      awards[key] ?: throw FormatException(
        Defect.BandWithNoAward(record.recordNumber, ipedsUnitId, entryYear, residencyBasis),
      )
    val input = bandInputOf(record, table)
    assertBandStatesOneCell(record, ipedsUnitId, input)
    assertBandIsOnItsScale(record, input)
    return ParsedBand(line = record.recordNumber, key = key, ipedsUnitId = ipedsUnitId, award = award, input = input)
  }

  /** The eight published cell columns, read and located. Nothing here resolves an award. */
  private fun bandInputOf(
    record: CSVRecord,
    table: Table,
  ): NewAutomaticAwardBand =
    NewAutomaticAwardBand(
      awardName = getString(record, "award_name", table),
      test = getStringOrNull(record, "test")?.let { raw -> decode(record, "test", table, raw, AdmissionTest.entries) { it.value } },
      scoreLow = getIntOrNull(record, "score_low", table),
      scoreHigh = getIntOrNull(record, "score_high", table),
      gpaLow = getPublishedGpa(record, "gpa_low", table),
      gpaHigh = getPublishedGpa(record, "gpa_high", table),
      awardPerYearUsd = getIntOrNull(record, "award_per_year_usd", table),
      awardAsPublished = getStringOrNull(record, "award_as_published"),
    )

  /**
   * The cell's natural key, refused where an earlier line already claimed it.
   *
   * THE SECOND ROW WOULD SILENTLY WIN, which is the refusal awards.csv already
   * has one table up: the natural-key upsert RESOLVES a repeated key last-wins
   * and counts it as a change, so without this the corpus can carry two prices
   * for one published cell and report a clean load. The key it compares and the
   * key the refusal PRINTS are the same value, from [ParsedBand.naturalKey].
   */
  private fun refuseRepeatedBandKey(
    parsed: ParsedBand,
    bandLines: Map<BandKey, Long>,
  ) {
    val key = parsed.naturalKey
    bandLines[key]?.let { firstLine ->
      throw FormatException(
        Defect.DuplicateBandKey(
          parsed.line,
          firstLine,
          parsed.ipedsUnitId,
          key.awardName,
          key.test,
          key.scoreLow,
          key.gpaLow,
        ),
      )
    }
  }

  /** The cell written, with the door's `none_published` refusal located by CSV line. */
  private fun writeBand(
    session: SqlSession,
    record: CSVRecord,
    parsed: ParsedBand,
  ): WrittenRow<AutomaticAwardBandId> {
    val outcome =
      withRowLocation(Table.BANDS, record, parsed.ipedsUnitId) {
        AutomaticAwardsDao.upsertBand(session, parsed.award.id, parsed.input)
      }
    return when (outcome) {
      is BandWriteOutcome.Written -> {
        outcome.row
      }

      // THE DOOR'S OWN ANSWER, located. The write path refuses a cell under a
      // school that publishes no automatic award (D28); this loader is the
      // only caller that can say which CSV line asked for it.
      is BandWriteOutcome.NoGridPublished -> {
        throw FormatException(
          Defect.BandUnderNonePublished(
            parsed.line,
            parsed.ipedsUnitId,
            parsed.key.entryYear,
            parsed.key.residencyBasis,
          ),
        )
      }
    }
  }

  /**
   * The two rules a band row must state about ITSELF, refused by line.
   *
   * Both are CHECKs on the table and `require`s on the read model, and neither
   * can name the row a person typed: a score range is a range ON a test, and a
   * named band states what it pays either in dollars or in the school's words.
   */
  private fun assertBandStatesOneCell(
    record: CSVRecord,
    ipedsUnitId: Int,
    input: NewAutomaticAwardBand,
  ) {
    if ((input.scoreLow != null) != (input.test != null) || (input.scoreHigh != null) != (input.test != null)) {
      throw FormatException(
        Defect.BandScoreAndTestDisagree(
          record.recordNumber,
          ipedsUnitId,
          input.awardName,
          input.test?.value,
          input.scoreLow,
          input.scoreHigh,
        ),
      )
    }
    if (input.awardPerYearUsd == null && input.awardAsPublished == null) {
      throw FormatException(Defect.BandStatesNoValue(record.recordNumber, ipedsUnitId, input.awardName))
    }
  }

  /**
   * Both edges of a band, on the scale the test itself is published on.
   *
   * "Is it an integer" is not "is it a score on `test`'s published scale", and
   * a mis-keyed `126` for `1260` loads green and matches no student forever --
   * which reads downstream exactly like a school that pays nothing there. The
   * row's own `test` cell is in hand here, so the scale is known.
   */
  private fun assertBandIsOnItsScale(
    record: CSVRecord,
    input: NewAutomaticAwardBand,
  ) {
    val test = input.test ?: return
    val scale = publishedScaleOf(test)
    listOf("score_low" to input.scoreLow, "score_high" to input.scoreHigh).forEach { (column, edge) ->
      if (edge != null && edge !in scale) {
        throw FormatException(
          Defect.ScoreOutsidePublishedScale(record.recordNumber, column, test, edge, scale),
        )
      }
    }
  }

  /** The scale a whole-test figure is published on; the one home is [PublishedScoreScale]. */
  private fun publishedScaleOf(test: AdmissionTest): IntRange =
    when (test) {
      AdmissionTest.SAT -> PublishedScoreScale.SAT_COMPOSITE
      AdmissionTest.ACT -> PublishedScoreScale.ACT_COMPOSITE
    }

  /**
   * The url, once it is known to have a committed answer.
   *
   * A url the sidecar never mentions is refused rather than written as "we never
   * asked": the citing write would state evidence nobody gathered, and this
   * corpus commits its rows and its link answers together.
   */
  private fun requireChecked(
    record: CSVRecord,
    column: String,
    url: String,
    linkStatuses: Map<String, RecordedLinkStatus>,
    linkStatusCsv: File,
  ): String {
    if (url !in linkStatuses) {
      throw FormatException(Defect.UncheckedUrl(record.recordNumber, column, url, linkStatusCsv.path))
    }
    return url
  }

  /**
   * The latest sitting this school will consider, as the one value that can hold
   * both published shapes.
   *
   * WORDS WITH NO DATE IS A LEGAL STATE, not a degraded one: UAB publishes a
   * cut-off that differs by test ("February ACT or March SAT scores ... will be
   * the final scores considered"), which no single period can hold without
   * picking one test's date for both. What is refused is half a PARSED fact: a
   * date with no precision, a precision with no date, and a date that is not its
   * period's start.
   */
  private fun lastSittingOf(
    record: CSVRecord,
    table: Table,
  ): LastSittingConsidered? {
    val asPublished = getStringOrNull(record, "last_sitting_as_published")
    val rawThrough = record.get("last_sitting_through").trim()
    val rawPrecision = record.get("last_sitting_precision").trim()
    if (rawThrough.isEmpty() && rawPrecision.isEmpty()) {
      return asPublished?.let { LastSittingConsidered.InWords(it) }
    }
    if (rawThrough.isEmpty() || rawPrecision.isEmpty()) {
      throw FormatException(Defect.LastSittingPairBroken(record.recordNumber, rawThrough, rawPrecision))
    }
    // The words are NOT optional beside a parsed period: the schema's
    // `..._last_sitting_has_words_check` says so, and refusing here names the
    // line instead of the constraint.
    if (asPublished == null) {
      throw FormatException(Defect.EmptyRequiredCell(table, record.recordNumber, "last_sitting_as_published"))
    }
    val through = getDate(record, "last_sitting_through", table)
    val precision =
      DatePrecision.fromValue(rawPrecision) ?: throw FormatException(
        Defect.UnknownCode(
          table,
          record.recordNumber,
          "last_sitting_precision",
          rawPrecision,
          DatePrecision.entries.map { it.value },
        ),
      )
    return LastSittingConsidered.Through(periodStartingAt(record, through, precision, rawThrough, rawPrecision), asPublished)
  }

  /**
   * The stored (date, precision) pair as the [PartialDate] it encodes, refusing
   * a date that is not the START of the period it claims.
   *
   * THROUGH THE ONE CODEC. [PartialDateColumns.decode] is the repo's declared
   * home for this pair -- it mirrors the schema's `is_period_start` CHECK and is
   * what this corpus's own DAO decodes the column with on the way back out. A
   * second `when` over [DatePrecision] here would be a third place deciding
   * what a month-precision date means, in the file that WRITES the rows the
   * other two read. All this adds is the CSV line, which is the one thing the
   * codec cannot know.
   */
  private fun periodStartingAt(
    record: CSVRecord,
    through: LocalDate,
    precision: DatePrecision,
    rawThrough: String,
    rawPrecision: String,
  ): PartialDate =
    when (val decoded = PartialDateColumns.decode(through, precision.value)) {
      is ValidationResult.Valid -> {
        decoded.value
      }

      // THE CODEC'S OWN REASON, carried. `decode` refuses two things and this
      // arm used to name one of them on the strength of a comment; the day it
      // grows a third, the operator would be told the wrong one at a line they
      // have to fix by hand.
      is ValidationResult.Invalid -> {
        throw FormatException(
          Defect.LastSittingNotAPeriodStart(record.recordNumber, rawThrough, rawPrecision, decoded.error),
        )
      }
    }

  /**
   * Streams one file's rows with the arity check every row gets, so the located
   * defects cannot differ between the two files.
   */
  private fun forEachRow(
    file: File,
    table: Table,
    columns: List<String>,
    onRow: (CSVRecord) -> Unit,
  ) {
    parseCsv(file).use { records ->
      for (record in records) {
        if (!record.isConsistent || record.size() != columns.size) {
          throw FormatException(Defect.RowArity(table, record.recordNumber, record.size(), columns.size))
        }
        onRow(record)
      }
    }
  }

  /** Runs one row's DB call, naming the file, CSV line and unit id on failure. */
  private fun <T> withRowLocation(
    table: Table,
    record: CSVRecord,
    ipedsUnitId: Int,
    call: () -> Result<T>,
  ): T =
    call().getOrElse { cause ->
      throw LoadException(table, record.recordNumber, ipedsUnitId, cause)
    }

  private fun assertHeader(
    source: SourceFile,
    expected: List<String>,
  ) {
    val actual = headerMismatchOf(source, expected) ?: return
    throw FormatException(Defect.HeaderMismatch(source.file.path, source.sourceArg, expected, actual))
  }

  /**
   * The shared located cell readers, one reader per table, so a refusal names
   * the file it came from.
   *
   * THE READING IS [SeedCells]' AND THE MESSAGE IS THIS CORPUS'S. The bodies
   * below are one line each because the rules -- a trimmed required cell, an
   * integer, an ISO day, a coded cell against a closed set, a year inside
   * [AcademicYear]'s range -- are corpus-independent and were character-identical
   * to [CdsSeedLoader]'s. The wrappers stay so every call site keeps naming its
   * own table, which is the one thing the shared reader cannot know.
   */
  private val cells: Map<Table, SeedCells> =
    Table.entries.associateWith { table ->
      SeedCells { defect, cause -> throw FormatException(cellDefectOf(table, defect), cause) }
    }

  /** One shared cell fault as THIS corpus names it. */
  private fun cellDefectOf(
    table: Table,
    defect: SeedCellDefect,
  ): Defect =
    when (defect.fault) {
      SeedCellFault.EMPTY_REQUIRED -> {
        Defect.EmptyRequiredCell(table, defect.line, defect.column)
      }

      SeedCellFault.NOT_AN_INTEGER -> {
        Defect.NotAnInteger(table, defect.line, defect.column, defect.value)
      }

      SeedCellFault.NOT_A_DECIMAL -> {
        Defect.NotADecimal(table, defect.line, defect.column, defect.value)
      }

      SeedCellFault.NOT_A_DATE -> {
        Defect.NotADate(table, defect.line, defect.column, defect.value)
      }

      SeedCellFault.NOT_AN_ACADEMIC_YEAR -> {
        Defect.NotAnAcademicYear(
          table,
          defect.line,
          defect.column,
          checkNotNull(defect.year) { "[${defect.column}] raised NOT_AN_ACADEMIC_YEAR with no parsed year" },
        )
      }

      SeedCellFault.UNKNOWN_CODE -> {
        Defect.UnknownCode(table, defect.line, defect.column, defect.value, defect.allowed)
      }
    }

  private fun cellsOf(table: Table): SeedCells = cells.getValue(table)

  private fun getInt(
    record: CSVRecord,
    column: String,
    table: Table,
  ): Int = cellsOf(table).getInt(record, column)

  private fun getIntOrNull(
    record: CSVRecord,
    column: String,
    table: Table,
  ): Int? = cellsOf(table).getIntOrNull(record, column)

  private fun getDecimalOrNull(
    record: CSVRecord,
    column: String,
    table: Table,
  ): BigDecimal? = cellsOf(table).getDecimalOrNull(record, column)

  /**
   * A published GPA bound, accepted only in the SHAPE the column stores: at
   * most [PUBLISHED_GPA_SCALE] decimals, and inside the range the table's own
   * `..._gpa_range_check` admits.
   *
   * REFUSED HERE AND NOWHERE ELSE, because nothing downstream can refuse it:
   * `NUMERIC(4,3)` rounds a fourth decimal on the way IN, before any CHECK sees
   * the value, so `3.7499` lands as `3.750`, collides with the school's real
   * `3.75` tier on the natural key, and the later row's money replaces the
   * earlier row's -- reported as a clean `changed`. The column was widened from
   * `NUMERIC(3,2)` to stop that collision at three decimals; this is the door
   * that stops it at four.
   */
  private fun getPublishedGpa(
    record: CSVRecord,
    column: String,
    table: Table,
  ): BigDecimal? {
    val gpa = getDecimalOrNull(record, column, table) ?: return null
    if (gpa.stripTrailingZeros().scale() > PUBLISHED_GPA_SCALE ||
      gpa < PUBLISHED_GPA_FLOOR ||
      gpa > PUBLISHED_GPA_CEILING
    ) {
      throw FormatException(
        Defect.GpaOutsidePublishedShape(table, record.recordNumber, column, record.get(column).trim()),
      )
    }
    return gpa
  }

  private fun getString(
    record: CSVRecord,
    column: String,
    table: Table,
  ): String = cellsOf(table).getString(record, column)

  /**
   * An optional cell. It names no table because it CANNOT refuse -- a blank is
   * the absence this corpus spells -- so the reader it borrows is arbitrary and
   * its refusal path is unreachable from here.
   */
  private fun getStringOrNull(
    record: CSVRecord,
    column: String,
  ): String? = record.optionalCell(column)

  private fun getDate(
    record: CSVRecord,
    column: String,
    table: Table,
  ): LocalDate = cellsOf(table).getDate(record, column)

  private fun getDateOrNull(
    record: CSVRecord,
    column: String,
    table: Table,
  ): LocalDate? = cellsOf(table).getDateOrNull(record, column)

  private fun getAcademicYear(
    record: CSVRecord,
    column: String,
    table: Table,
  ): AcademicYear = cellsOf(table).getAcademicYear(record, column)

  /**
   * The residency basis, restricted to the three a freshman award is published
   * for.
   *
   * `in_district` and `unknown` are legal members of the shared vocabulary and
   * are refused here, exactly as the table's own
   * `..._residency_basis_check` refuses them: a community-college tuition tier
   * and an IPEDS headcount category are not things a university publishes a
   * freshman award against.
   */
  private fun getResidencyBasis(
    record: CSVRecord,
    table: Table,
  ): ResidencyBasis = getEnumCode(record, "residency_basis", table, AWARD_RESIDENCY_BASES) { it.value }

  /** [decode] over a required cell -- the corpus's usual case. */
  private fun <T> getEnumCode(
    record: CSVRecord,
    column: String,
    table: Table,
    values: List<T>,
    slugOf: (T) -> String,
  ): T = cellsOf(table).getEnumCode(record, column, values, slugOf)

  /**
   * A coded cell decoded through [values], or a located [Defect.UnknownCode]
   * naming every value that would have been accepted.
   */
  private fun <T> decode(
    record: CSVRecord,
    column: String,
    table: Table,
    raw: String,
    values: List<T>,
    slugOf: (T) -> String,
  ): T = cellsOf(table).decode(record, column, raw, values.associateBy(slugOf))

  companion object {
    val AWARD_COLUMNS =
      listOf(
        "unit_id",
        "college_name",
        "entry_year",
        "residency_basis",
        "award_kind",
        "score_requirement",
        "superscore_rule",
        "score_deadline",
        "score_deadline_as_published",
        "score_requirement_as_published",
        "superscore_as_published",
        "last_sitting_through",
        "last_sitting_precision",
        "last_sitting_as_published",
        "publisher",
        "source_url",
        "archive_url",
        "checked_on",
        "expires_on",
      )

    val BAND_COLUMNS =
      listOf(
        "unit_id",
        "entry_year",
        "residency_basis",
        "award_name",
        "test",
        "score_low",
        "score_high",
        "gpa_low",
        "gpa_high",
        "award_per_year_usd",
        "award_as_published",
      )

    /**
     * The committed link answers' header: THREE columns, the shape the two
     * landed hand-typed corpora commit and the shape `external_documents` names
     * its own columns by. See [readLinkStatuses].
     */
    val LINK_STATUS_COLUMNS =
      listOf(
        "url",
        "url_status",
        "url_checked_on",
      )

    /** The three bases a freshman award is published for; see [getResidencyBasis]. */
    val AWARD_RESIDENCY_BASES =
      listOf(
        ResidencyBasis.IN_STATE,
        ResidencyBasis.OUT_OF_STATE,
        ResidencyBasis.NOT_APPLICABLE,
      )

    /**
     * What `gpa_low`/`gpa_high` STORE: `NUMERIC(4,3)`, and a 0-5 range CHECK.
     *
     * Named here because the loader is the one door that can hold a typed bound
     * to them -- see [getPublishedGpa] for why the database cannot.
     */
    const val PUBLISHED_GPA_SCALE: Int = 3

    val PUBLISHED_GPA_FLOOR: BigDecimal = BigDecimal.ZERO

    val PUBLISHED_GPA_CEILING: BigDecimal = BigDecimal(5)
  }
}

/**
 * One rendering of a [AutomaticAwardSeedLoader.Defect], at the boundary rather
 * than at each throw site -- so every variant's coordinates are stated once and
 * a new variant cannot ship with a message somebody forgot to write.
 *
 * THE SHARED FAULTS' SENTENCES ARE NOT WRITTEN HERE. A cell fault and a
 * `link-status.csv` fault are facts about the FILE's shape rather than about
 * this corpus, and the second hand-typed corpus to carry them spelled all seven
 * sidecar sentences identically and one cell sentence differently -- so they are
 * stated once, beside the readers that raise them
 * ([renderSeedCellDefect], [renderLinkStatusFault]), and these arms hand their
 * own coordinates to those. Each arm rebuilds the located fault it was made
 * from, which is the price of keeping THIS loader's defect variants exactly as
 * they are: an operator reads the same sentences as before, and nothing
 * matching on these types had to change.
 */
private fun renderAutomaticAwardDefect(defect: AutomaticAwardSeedLoader.Defect): String =
  when (defect) {
    is AutomaticAwardSeedLoader.Defect.HeaderMismatch -> {
      "automatic-award corpus file [${defect.file}] (from [${defect.sourceArg}]) has the wrong header: " +
        "missing ${defect.missing}, unexpected ${defect.unexpected}; nothing was written"
    }

    is AutomaticAwardSeedLoader.Defect.RowArity -> {
      "[${defect.table.label}] line [${defect.line}] has [${defect.cells}] cells, expected [${defect.expectedCells}]"
    }

    is AutomaticAwardSeedLoader.Defect.NotAnInteger -> {
      renderSeedCellDefect(
        defect.table.label,
        SeedCellDefect(SeedCellFault.NOT_AN_INTEGER, defect.line, defect.column, defect.value),
      )
    }

    is AutomaticAwardSeedLoader.Defect.NotADecimal -> {
      renderSeedCellDefect(
        defect.table.label,
        SeedCellDefect(SeedCellFault.NOT_A_DECIMAL, defect.line, defect.column, defect.value),
      )
    }

    is AutomaticAwardSeedLoader.Defect.NotADate -> {
      renderSeedCellDefect(
        defect.table.label,
        SeedCellDefect(SeedCellFault.NOT_A_DATE, defect.line, defect.column, defect.value),
      )
    }

    is AutomaticAwardSeedLoader.Defect.EmptyRequiredCell -> {
      renderSeedCellDefect(defect.table.label, SeedCellDefect(SeedCellFault.EMPTY_REQUIRED, defect.line, defect.column, ""))
    }

    is AutomaticAwardSeedLoader.Defect.UnknownCode -> {
      renderSeedCellDefect(
        defect.table.label,
        SeedCellDefect(SeedCellFault.UNKNOWN_CODE, defect.line, defect.column, defect.value, defect.allowed),
      )
    }

    is AutomaticAwardSeedLoader.Defect.NotAnAcademicYear -> {
      renderSeedCellDefect(
        defect.table.label,
        SeedCellDefect(SeedCellFault.NOT_AN_ACADEMIC_YEAR, defect.line, defect.column, "", year = defect.value),
      )
    }

    is AutomaticAwardSeedLoader.Defect.UnknownCollege -> {
      "[${defect.table.label}] line [${defect.line}]: unit_id [${defect.ipedsUnitId}] " +
        "(typed as [${defect.typedName}]) is in no `colleges` row. This corpus is hand-typed and REFUSES " +
        "rather than skipping: a row silently dropped here reads downstream as \"we have not read this school\", " +
        "which is false about a school somebody did read. Load the colleges snapshot first, or correct the unit_id."
    }

    is AutomaticAwardSeedLoader.Defect.CollegeNameDisagreement -> {
      "[awards] line [${defect.line}]: unit_id [${defect.ipedsUnitId}] is [${defect.storedName}] in `colleges`, " +
        "but this row types [${defect.typedName}]. A mis-keyed row is another school's money under this " +
        "school's name -- a fuzzy match of these names returned eight wrong schools -- so the pair must agree exactly."
    }

    is AutomaticAwardSeedLoader.Defect.DuplicateAwardKey -> {
      "[awards] line [${defect.line}] repeats the key already read at line [${defect.firstLine}]: " +
        "unit_id [${defect.ipedsUnitId}] entry_year [${defect.entryYear.firstCalendarYear}] " +
        "residency_basis [${defect.residencyBasis.value}]. " +
        "One school publishes ONE grid per entry year and residency basis; the second row would silently overwrite the first."
    }

    is AutomaticAwardSeedLoader.Defect.BandWithNoAward -> {
      "[bands] line [${defect.line}] names unit_id [${defect.ipedsUnitId}] entry_year " +
        "[${defect.entryYear.firstCalendarYear}] residency_basis [${defect.residencyBasis.value}], which no " +
        "awards.csv row declares"
    }

    is AutomaticAwardSeedLoader.Defect.BandUnderNonePublished -> {
      "[bands] line [${defect.line}] hangs a band under unit_id [${defect.ipedsUnitId}] entry_year " +
        "[${defect.entryYear.firstCalendarYear}] residency_basis [${defect.residencyBasis.value}], whose " +
        "award_kind is [none_published]. " +
        "A school we verified publishes NO automatic award cannot publish a cell of one."
    }

    is AutomaticAwardSeedLoader.Defect.DuplicateBandKey -> {
      "[bands] line [${defect.line}] repeats the cell already read at line [${defect.firstLine}]: " +
        "unit_id [${defect.ipedsUnitId}] award_name [${defect.awardName}] test [${defect.test?.value}] " +
        "score_low [${defect.scoreLow}] gpa_low [${defect.gpaLow?.toPlainString()}]. One published cell is ONE " +
        "row; the second " +
        "would silently overwrite the first's money."
    }

    is AutomaticAwardSeedLoader.Defect.BandStatesNoValue -> {
      "[bands] line [${defect.line}]: unit_id [${defect.ipedsUnitId}] band [${defect.awardName}] states neither " +
        "award_per_year_usd nor award_as_published (college_automatic_award_bands_award_has_value_check). " +
        "A named band with no value is a heading over nothing."
    }

    is AutomaticAwardSeedLoader.Defect.ScoreOutsidePublishedScale -> {
      "[bands] line [${defect.line}] column [${defect.column}] is [${defect.value}], which is not a " +
        "[${defect.test.value}] score: that test is published on ${defect.scale}. A band edge outside its own " +
        "test's scale is a typing fault -- 126 for 1260 -- and a band no student's score can land in reads " +
        "downstream exactly like a school that pays nothing there."
    }

    is AutomaticAwardSeedLoader.Defect.GpaOutsidePublishedShape -> {
      "[${defect.table.label}] line [${defect.line}] column [${defect.column}] is [${defect.value}], which is " +
        "not a bound gpa_low/gpa_high can store: at most " +
        "[${AutomaticAwardSeedLoader.PUBLISHED_GPA_SCALE}] decimals, between " +
        "[${AutomaticAwardSeedLoader.PUBLISHED_GPA_FLOOR.toPlainString()}] and " +
        "[${AutomaticAwardSeedLoader.PUBLISHED_GPA_CEILING.toPlainString()}]. NUMERIC(4,3) would ROUND a " +
        "fourth decimal rather than refuse it -- 3.7499 becomes 3.750 -- and the rounded bound then collides " +
        "with the school's real tier on the natural key, replacing its money. Type the bound the page states."
    }

    is AutomaticAwardSeedLoader.Defect.BandScoreAndTestDisagree -> {
      "[bands] line [${defect.line}]: unit_id [${defect.ipedsUnitId}] band [${defect.awardName}] has test " +
        "[${defect.test}] with score_low [${defect.scoreLow}] score_high [${defect.scoreHigh}] " +
        "(college_automatic_award_bands_score_iff_test_check). A score band is a band ON a test: name both, or " +
        "neither for the published no-score row."
    }

    is AutomaticAwardSeedLoader.Defect.ClaimAndWordsDisagree -> {
      "[awards] line [${defect.line}] states [${defect.ruleColumn}] as [${defect.rule}] and its " +
        "[${defect.wordsColumn}] cell does not match it. A stated rule carries the school's own words and an " +
        "unstated one carries none, which is what the table's ..._has_words_check enforces without being able " +
        "to name this line."
    }

    is AutomaticAwardSeedLoader.Defect.DeadlineWithoutWords -> {
      "[awards] line [${defect.line}] stores [${defect.deadlineColumn}] as [${defect.deadline}] and its " +
        "[${defect.wordsColumn}] cell is empty. A stored date carries the sentence it was read from, which is " +
        "what the table's ..._deadline_has_words_check enforces without being able to name this line."
    }

    is AutomaticAwardSeedLoader.Defect.EmptyAwardCorpus -> {
      "[${defect.file}] carries no award row. The load ends by dropping every award this corpus no longer " +
        "names, so an empty file would unpublish every school: that is a truncated corpus, not an instruction. " +
        "Nothing was written."
    }

    is AutomaticAwardSeedLoader.Defect.UncheckedUrl -> {
      "[awards] line [${defect.line}] column [${defect.column}] cites [${defect.url}], which [${defect.statusFile}] " +
        "does not mention. A citation states its evidence or it cannot be stored (RFC 220): commit the link answer " +
        "for this url beside the row that cites it."
    }

    is AutomaticAwardSeedLoader.Defect.MissingLinkStatusFile -> {
      "the automatic-award corpus requires its committed link answers at [${defect.file}], and no file is there; " +
        "nothing was written"
    }

    is AutomaticAwardSeedLoader.Defect.SidecarRowArity -> {
      renderLinkStatusFault(defect.file, defect.line, LinkStatusFault.RowArity(defect.cells, defect.expectedCells))
    }

    is AutomaticAwardSeedLoader.Defect.SidecarEmptyUrl -> {
      renderLinkStatusFault(defect.file, defect.line, LinkStatusFault.EmptyUrl)
    }

    is AutomaticAwardSeedLoader.Defect.SidecarUnknownStatus -> {
      renderLinkStatusFault(
        defect.file,
        defect.line,
        LinkStatusFault.UnknownStatus(defect.url, defect.value, defect.allowed),
      )
    }

    is AutomaticAwardSeedLoader.Defect.SidecarNotADate -> {
      renderLinkStatusFault(defect.file, defect.line, LinkStatusFault.NotADate(defect.url, defect.value))
    }

    is AutomaticAwardSeedLoader.Defect.SidecarMissingDate -> {
      renderLinkStatusFault(defect.file, defect.line, LinkStatusFault.MissingDate(defect.url, defect.status))
    }

    is AutomaticAwardSeedLoader.Defect.SidecarUrlNotAWebPage -> {
      renderLinkStatusFault(defect.file, defect.line, LinkStatusFault.UrlNotAWebPage(defect.url))
    }

    is AutomaticAwardSeedLoader.Defect.SidecarConflictingStatus -> {
      renderLinkStatusFault(
        defect.file,
        defect.line,
        LinkStatusFault.ConflictingStatus(defect.firstLine, defect.url, defect.first, defect.second),
      )
    }

    is AutomaticAwardSeedLoader.Defect.LastSittingPairBroken -> {
      "[awards] line [${defect.line}] has last_sitting_through [${defect.through}] and last_sitting_precision " +
        "[${defect.precision}]: the two are a pair, and one without the other is half a fact"
    }

    is AutomaticAwardSeedLoader.Defect.LastSittingNotAPeriodStart -> {
      "[awards] line [${defect.line}] stores last_sitting_through [${defect.through}] at precision " +
        "[${defect.precision}], which the stored-date codec refused: [${defect.decodeError}]. The column holds " +
        "the period's start and the service reads its end, so a mid-period date silently shortens the school's " +
        "window."
    }
  }
