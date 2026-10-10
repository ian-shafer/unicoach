package ed.unicoach.college

import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.Database
import ed.unicoach.db.dao.CollegesDao
import ed.unicoach.db.dao.EmptyNationalMeritPruneException
import ed.unicoach.db.dao.ExternalDocumentsDao
import ed.unicoach.db.dao.NationalMeritAwardsDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.UpsertOutcome
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.NationalMeritAwardId
import ed.unicoach.db.models.NationalMeritAwardKind
import ed.unicoach.db.models.NewExternalDocument
import ed.unicoach.db.models.NewNationalMeritAward
import ed.unicoach.db.models.QualifyingStanding
import ed.unicoach.db.models.ResidencyBasis
import ed.unicoach.db.models.SourceUrlStatus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.csv.CSVRecord
import java.io.File
import java.time.LocalDate
import java.time.Month
import java.time.MonthDay

/**
 * Loader for the repo-committed National Merit award corpus (RFC 243, slice
 * `test/08/the-psat-is-money`): `db/seed/national-merit-awards/awards.csv` and
 * the `link-status.csv` sidecar beside it, into
 * `college_national_merit_awards`.
 *
 * [AutomaticAwardSeedLoader] IS THE SHAPE THIS FOLLOWS, part for part, because
 * it is the same ROUTE one corpus along: hand-typed rows keyed by
 * `colleges(id)`, which the IMPORT fills, so this loads with
 * `bin/ingest-colleges` and not from a migration (RFC 239 D-C). What differs is
 * ONE file rather than two -- a standing is not a score, so there is no band
 * table -- and the refusals are this table's own CHECKs rather than that one's.
 *
 * IT REFUSES, IT NEVER SKIPS. This corpus is TYPED BY HAND from each school's
 * own scholarship page, and the measured failure mode is not absence but
 * MIS-KEYING: a fuzzy name match of curated schools against the loaded
 * `colleges` table returned EIGHT wrong schools during RFC 239's curation pass.
 * A mis-keyed row here is not a missing answer, it is another school's money
 * under this school's name. So a `unit_id` with no college, and a
 * `college_name` that disagrees with `colleges.name` for that unit id, are both
 * fatal and both name the row.
 *
 * THE ORDER IS FIXED AND IT IS PART OF THE CONTRACT: `external_documents`
 * first, then the award rows.
 *
 *  1. Every url the sidecar carries is upserted with the verdict and day
 *     COMMITTED beside the corpus. [ExternalDocumentsDao.upsert] and never
 *     `idOf`, because this loader is the caller that HAS the answer -- two of
 *     this corpus's nineteen urls are `unknown` with the day we asked
 *     (Oklahoma State's host answers 403 to `bin/check-link` and 200 to a
 *     browser), and that is the TRUE state rather than a degraded one.
 *  2. Every award row then cites documents that already exist, so the
 *     `citationIds` call the DAO makes takes its conflict arm and cannot record
 *     "we asked on the reading day" over a real swept verdict (RFC 182).
 *
 * A url an award row cites and the sidecar does not mention is therefore a
 * REFUSAL, not a defaulted `unknown`: step 2 would otherwise silently invent
 * the evidence RFC 220 requires a citation to state. For the same reason an
 * ABSENT sidecar is fatal here, where [CdsSeedLoader] tolerates one -- that
 * loader's corpus is swept by a script and may legitimately run before the
 * first sweep; this one's answers are committed beside the rows they describe.
 *
 * Everything loads inside ONE connection, so any refusal rolls the whole corpus
 * back rather than leaving a school half-published.
 */
class NationalMeritAwardSeedLoader(
  private val database: Database,
  /**
   * Where the blocking file reads run. OWNED HERE and not asked of the caller
   * ([AutomaticAwardSeedLoader]'s contract): this class opens two CSVs, and a
   * header check that states its threading requirement in prose is one a new
   * caller meets by accident or not at all.
   */
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
  /**
   * The closed set of ways this hand-typed corpus can disagree with the loader.
   *
   * EVERY VARIANT NAMES THE FILE, THE LINE AND THE CELL, as typed fields rather
   * than as prose interpolated at the throw site, so a caller (and this suite's
   * tests) can read them without parsing a sentence. [file] is carried by every
   * row-level arm and not folded into a table label the way
   * [AutomaticAwardSeedLoader.Table] folds its two: this corpus is ONE file, so
   * a label would be a constant standing in for the path an operator has to
   * open.
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
      val file: String,
      val line: Long,
      val cells: Int,
      val expectedCells: Int,
    ) : Defect

    /**
     * One CELL this corpus refused, as the shared reader located it.
     *
     * ONE VARIANT AND NOT ONE PER FAULT. The six faults a seed cell can have,
     * and the sentences they are reported with, are facts about the FILE's shape
     * rather than about National Merit ([SeedCellDefect],
     * [renderSeedCellDefect]) -- and the first corpus to type them out for
     * itself had already drifted from this one by a word. [cell] carries the
     * fault, the line, the column and what was typed, so a caller still reads
     * every coordinate without parsing a sentence.
     */
    data class Cell(
      val file: String,
      val cell: SeedCellDefect,
    ) : Defect

    /**
     * One `link-status.csv` row this corpus refused, as the shared reader
     * located it.
     *
     * ONE VARIANT AND NOT ONE PER FAULT, for [Cell]'s reason and with stronger
     * evidence: the seven faults ([LinkStatusFault]) are the FILE's own rules,
     * and the two corpora that each named them separately printed all seven
     * sentences identically.
     */
    data class Sidecar(
      val file: String,
      val line: Long,
      val fault: LinkStatusFault,
    ) : Defect

    /**
     * A `unit_id` no `colleges` row carries.
     *
     * FATAL, where the CDS loader skips: a hand-typed row has no acceptable
     * silent drop, and a row silently absent from this corpus reads downstream
     * as "we have not read this school" -- which is false about a school
     * somebody DID read.
     */
    data class UnknownCollege(
      val file: String,
      val line: Long,
      val column: String,
      val ipedsUnitId: Int,
      val typedName: String,
    ) : Defect

    /**
     * The typed `college_name` disagrees with `colleges.name` for that
     * `unit_id`. THE MEASURED FAILURE MODE, and the reason the name is in the
     * CSV at all.
     */
    data class CollegeNameDisagreement(
      val file: String,
      val line: Long,
      val column: String,
      val ipedsUnitId: Int,
      val typedName: String,
      val storedName: String,
    ) : Defect

    /**
     * Two rows on one natural key `(unit_id, entry_year, residency_basis,
     * qualifying_standing)`: the later would silently overwrite the earlier's
     * money.
     *
     * THE DECODED VALUES RIDE, not their slugs: they are closed types that were
     * in hand at the throw site, and the spelling of any of them belongs at the
     * boundary that renders the message rather than in the payload a test or a
     * caller reads.
     */
    data class DuplicateAwardKey(
      val file: String,
      val line: Long,
      val firstLine: Long,
      val ipedsUnitId: Int,
      val entryYear: AcademicYear,
      val residencyBasis: ResidencyBasis,
      val qualifyingStanding: QualifyingStanding?,
    ) : Defect

    /**
     * A standing on a row that says the school publishes nothing, or a paying
     * row that does not say WHICH standing it pays
     * (`college_national_merit_awards_standing_iff_award_check`).
     *
     * Both are the same incoherence read from the same cell, so both are this
     * one arm: the CHECK is an equality between two facts, not two rules.
     */
    data class StandingAndKindDisagree(
      val file: String,
      val line: Long,
      val column: String,
      val awardKind: NationalMeritAwardKind,
      val qualifyingStanding: QualifyingStanding?,
    ) : Defect

    /**
     * The row states a claim and not the sentence it was read from: a paying
     * row with no `award_as_published`, or a verified absence with no
     * `absence_as_published` (the schema's `..._claim_has_words_check` and
     * `..._absence_has_words_check`).
     *
     * ONE ARM NAMING THE MISSING CELL. Which sentence is owed follows from
     * [awardKind], so an arm per kind would carry the same two fields twice and
     * give a test two names for one rule.
     */
    data class MissingWords(
      val file: String,
      val line: Long,
      val column: String,
      val awardKind: NationalMeritAwardKind,
    ) : Defect

    /**
     * The row carries the OTHER kind's words: an absence quote on a paying row,
     * or award words on a verified absence.
     *
     * The two sentence columns are separate so that neither kind can borrow the
     * other's, and this is that separation enforced where the line is still in
     * hand -- a paying row rendered from an absence quote tells a family the
     * school pays nothing, in the school's own voice.
     */
    data class BorrowedWords(
      val file: String,
      val line: Long,
      val column: String,
      val awardKind: NationalMeritAwardKind,
      val value: String,
    ) : Defect

    /**
     * A verified absence that states an award anyway
     * (`college_national_merit_awards_none_published_is_empty_check`): a name,
     * a figure, a deadline or a first-choice rule on a row that says the school
     * publishes nothing for any of them to describe.
     *
     * EVERY OFFENDING CELL IS NAMED, not the first one: a curator who crossed
     * two columns fixes both, and a message that names one sends them back to
     * the same row twice.
     */
    data class NonePublishedStatesAnAward(
      val file: String,
      val line: Long,
      val columns: List<String>,
    ) : Defect

    /**
     * A figure outside the shape the column stores
     * (`college_national_merit_awards_money_positive_check`): a non-positive
     * amount, or a number of years outside one to six.
     *
     * A zero here is the defect RFC 239's review found shipping one corpus
     * along -- a package priced in WORDS ranked as $0 -- arriving from the
     * other direction: a typed `0` is not a published price.
     */
    data class MoneyOutsidePublishedShape(
      val file: String,
      val line: Long,
      val column: String,
      val value: Int,
      val allowed: IntRange,
    ) : Defect

    /**
     * A first-choice requirement with no words, or words with no requirement
     * (`college_national_merit_awards_first_choice_trio_check`, first half).
     *
     * Naming a college first choice with NMSC is an act with consequences
     * elsewhere, so a requirement WE state without the school's sentence is our
     * claim rather than theirs.
     */
    data class FirstChoicePairBroken(
      val file: String,
      val line: Long,
      val requiredColumn: String,
      val wordsColumn: String,
      val required: Boolean?,
      val asPublished: String?,
    ) : Defect

    /**
     * A first-choice DEADLINE on a row that does not require first choice
     * (`college_national_merit_awards_first_choice_trio_check`, second half):
     * a date with nothing to be the deadline of.
     */
    data class FirstChoiceDeadlineWithoutRequirement(
      val file: String,
      val line: Long,
      val column: String,
      val deadline: LocalDate,
      val required: Boolean?,
    ) : Defect

    /**
     * A stored deadline with no sentence it was read from
     * (`..._apply_deadline_pair_check`).
     *
     * ITS OWN VARIANT, and not a [MissingWords] carrying a date stringified to
     * fit: the two facts are a coded claim and a parsed DAY, and flattening the
     * day into the other variant's field leaves no reader able to switch on
     * either. The DATE rides; the rendering happens at the boundary.
     */
    data class DeadlineWithoutWords(
      val file: String,
      val line: Long,
      val column: String,
      val deadline: LocalDate,
      val wordsColumn: String,
    ) : Defect

    /**
     * A deadline outside the application cycle that ends in this row's entry
     * year (`college_national_merit_awards_deadlines_in_cycle_check`): July 1
     * the summer before, through September 30 of entry.
     *
     * IT CATCHES THE MISTAKE A CURATOR ACTUALLY MAKES -- a date typed into the
     * wrong cycle. "Before May 1" read off a page during the 2027 cycle and
     * typed as `2026-05-01` is a deadline that passed before the student
     * started, and nothing downstream could tell it from one that had not.
     */
    data class DeadlineOutsideCycle(
      val file: String,
      val line: Long,
      val column: String,
      val deadline: LocalDate,
      val entryYear: AcademicYear,
      val cycle: ClosedRange<LocalDate>,
    ) : Defect

    /**
     * `expires_on` on or before `checked_on`
     * (`college_national_merit_awards_expiry_after_checked_check`): the day we
     * stop believing the page cannot precede the day we read it.
     */
    data class ExpiryNotAfterCheck(
      val file: String,
      val line: Long,
      val column: String,
      val checkedOn: LocalDate,
      val expiresOn: LocalDate,
    ) : Defect

    /**
     * `archive_url` repeating `source_url`
     * (`college_national_merit_awards_archive_differs_check`): a mirror of a
     * page is a SECOND address, and a row citing one address twice states one
     * piece of evidence as two.
     */
    data class ArchiveRepeatsSource(
      val file: String,
      val line: Long,
      val column: String,
      val url: String,
    ) : Defect

    /**
     * A url an award row cites that the committed sidecar does not mention.
     *
     * Not a defaulted `unknown`: with RFC 220's strict `external_documents` the
     * citing write would state evidence nobody gathered -- `citationIds`
     * records FOUND on the reading day for a url it has never seen. The corpus
     * and its link answers are committed together, so a url missing from one of
     * them is a corpus that was edited by halves.
     */
    data class UncheckedUrl(
      val file: String,
      val line: Long,
      val column: String,
      val url: String,
      val statusFile: String,
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

    /** The sidecar this corpus REQUIRES is not a file. See the class KDoc for why absence is fatal here. */
    data class MissingLinkStatusFile(
      val file: String,
    ) : Defect
  }

  /**
   * A corpus file whose shape or contents disagree with this loader: fatal,
   * never skipped. The structured [defect] is the payload; the message is one
   * rendering of it, produced at the boundary rather than at each throw site.
   */
  class FormatException(
    val defect: Defect,
    cause: Throwable? = null,
  ) : RuntimeException(renderNationalMeritAwardDefect(defect), cause)

  /**
   * A DB failure on ONE corpus row, restating that row's coordinates with the
   * DAO exception as the cause -- a CHECK violation on row 21 of 26 must not
   * surface as a bare "Database constraint violation".
   */
  class LoadException(
    val file: String,
    val line: Long,
    val ipedsUnitId: Int,
    cause: Throwable,
  ) : RuntimeException(
      "[$file] line [$line]: unit_id [$ipedsUnitId] failed: [${cause.message}]",
      cause,
    )

  /**
   * A DB failure on ONE committed link answer, restating the url and the file
   * it was read from with the DAO exception as the cause -- the document half
   * of [LoadException], which names a CSV line and a unit id this phase does
   * not have.
   *
   * All-or-nothing has to abort AND say what failed: nineteen urls go through
   * one loop, and a refusal on the twelfth surfaced as the DAO's bare message
   * while every award row in the same run got file, line and unit id.
   */
  class DocumentException(
    val statusFile: String,
    val url: String,
    cause: Throwable,
  ) : RuntimeException(
      "[$statusFile]: url [$url] failed: [${cause.message}]",
      cause,
    )

  /**
   * Upsert disposition counts. No `skipped`: this loader has no such outcome.
   *
   * A VALUE THE RUN REPLACES, not a counter a row reader is handed: a row that
   * wrote something answers with what it wrote and the phase adds it up, so
   * nothing mutates a tally behind a `Unit`-returning call.
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
   * [awardsPruned] is what a re-read DROPPED.
   */
  data class LoadResult(
    val documents: Int,
    val awards: TableSummary,
    val awardsPruned: Int,
  )

  /**
   * One row's identity in the corpus, which is the natural key the table is
   * keyed by.
   *
   * THE DECODED VALUES, not their slugs: enum equality and data-class equality
   * are the same equality, and a key of strings is self-describing only to a
   * reader who remembers the slug vocabulary.
   */
  private data class CorpusKey(
    val ipedsUnitId: Int,
    val entryYear: AcademicYear,
    val residencyBasis: ResidencyBasis,
    val qualifyingStanding: QualifyingStanding?,
  )

  /** An award this run wrote, and the line a refusal ABOUT it must name. */
  private data class LoadedAward(
    val id: NationalMeritAwardId,
    val line: Long,
    val ipedsUnitId: Int,
  )

  /**
   * Asserts both headers, reading nothing else and writing nothing. Public and
   * callable on its own so the ingest validates this corpus BESIDE the
   * Scorecard, IPEDS, CDS and automatic-award files, before its first phase
   * commits: a renamed column here must not be discovered after seven phases
   * have written rows (RFC 148 D10).
   *
   * It owns its own [ioDispatcher] hop -- the reads open files, and no caller
   * has to know that.
   */
  suspend fun assertHeaders(source: SourceFile) =
    withContext(ioDispatcher) {
      assertHeader(source, AWARD_COLUMNS)
      val sidecar = sidecarBeside(source.file)
      requireLinkStatusFile(sidecar)
      assertHeader(SourceFile(sidecar, source.sourceArg), LINK_STATUS_COLUMNS)
    }

  /**
   * Loads the corpus: documents, then awards, in one transaction.
   *
   * [linkStatusCsv] sits BESIDE the corpus it describes, so it is defaulted
   * from the awards file's own directory rather than made a second argument of
   * every caller.
   */
  suspend fun load(
    awardsCsv: File,
    linkStatusCsv: File = sidecarBeside(awardsCsv),
  ): LoadResult =
    database.withConnection { session ->
      assertAllHeaders(awardsCsv, linkStatusCsv)
      val linkStatuses = readLinkStatuses(linkStatusCsv)
      writeDocuments(session, linkStatusCsv, linkStatuses)
      val awards = loadAwards(session, awardsCsv, linkStatusCsv, linkStatuses)
      LoadResult(
        documents = linkStatuses.size,
        awards = awards.summary,
        awardsPruned = pruneAwards(session, awardsCsv, awards.byKey),
      )
    }

  /**
   * Both headers, before any row of either is written.
   *
   * Asserted here as well as at the ingest's up-front check because [load] is
   * also called directly (the suite), and this is the loader's own contract.
   */
  private fun assertAllHeaders(
    awardsCsv: File,
    linkStatusCsv: File,
  ) {
    assertHeader(SourceFile(awardsCsv, awardsCsv.path), AWARD_COLUMNS)
    // EXISTENCE FIRST, and it is not decoration: [assertHeader] opens the file,
    // so an absent sidecar would reach it as a bare FileNotFoundException
    // naming a path the operator never typed -- outside this loader's "every
    // refusal names the corpus and says what to do" contract.
    requireLinkStatusFile(linkStatusCsv)
    assertHeader(SourceFile(linkStatusCsv, linkStatusCsv.path), LINK_STATUS_COLUMNS)
  }

  /**
   * What the awards phase produced: what it wrote, and the rows it wrote.
   *
   * THE RUN'S BOOKS ARE THE RUN'S. The phase keeps them and answers with them;
   * no row reader is handed a counter to write behind a `Unit`.
   */
  private data class LoadedAwards(
    val byKey: Map<CorpusKey, LoadedAward>,
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
    forEachRow(awardsCsv, AWARD_COLUMNS) { record ->
      val parsed = parseAward(record, awardsCsv, linkStatusCsv, linkStatuses)
      byKey[parsed.key]?.let { first -> throw FormatException(duplicateAwardKeyOf(awardsCsv, parsed, first)) }
      val collegeId = resolveCollege(session, awardsCsv, record, parsed)
      val written =
        withRowLocation(awardsCsv, record, parsed.ipedsUnitId) {
          NationalMeritAwardsDao.upsert(session, parsed.inputFor(collegeId))
        }
      summary = summary.plus(written.outcome)
      byKey[parsed.key] = LoadedAward(written.id, parsed.line, parsed.ipedsUnitId)
    }
    return LoadedAwards(byKey, summary)
  }

  /** One natural key claimed twice, named by BOTH lines. */
  private fun duplicateAwardKeyOf(
    awardsCsv: File,
    parsed: ParsedAward,
    first: LoadedAward,
  ): Defect =
    Defect.DuplicateAwardKey(
      awardsCsv.path,
      parsed.line,
      first.line,
      parsed.ipedsUnitId,
      parsed.key.entryYear,
      parsed.key.residencyBasis,
      parsed.key.qualifyingStanding,
    )

  /**
   * Every school this corpus no longer names, dropped.
   *
   * A re-read that DROPS a school must drop it from the table too: a
   * natural-key upsert alone is idempotent for an unchanged corpus and leaves a
   * stale row standing for a corrected one -- which is the coach quoting money
   * the school no longer pays, under a line nobody will look at again.
   */
  private fun pruneAwards(
    session: SqlSession,
    awardsCsv: File,
    awards: Map<CorpusKey, LoadedAward>,
  ): Int =
    NationalMeritAwardsDao
      .deleteExcept(session, awards.values.map { it.id })
      .getOrElse { cause ->
        // THE DOOR'S OWN ANSWER, located, and read from the failure rather than
        // re-derived from this run's state: `awards.csv` with no row is a file
        // somebody truncated, not an instruction to unpublish every school. The
        // day the prune refuses for a SECOND reason, `awards.isEmpty()` here
        // would have reported this defect for it.
        if (cause is EmptyNationalMeritPruneException) {
          throw FormatException(Defect.EmptyAwardCorpus(awardsCsv.path), cause)
        }
        // Every other failure here belongs to NO row -- one statement over the
        // whole table -- so it is raised as the DAO stated it. A [LoadException]
        // would have to invent a line and a unit id.
        throw cause
      }

  /**
   * Every url the sidecar carries, written with the answer committed beside the
   * corpus.
   *
   * EVERY url, not only the cited ones: the sidecar is this corpus's whole set
   * of link answers, and a loader that wrote only what `awards.csv` mentions
   * would silently drop an answer the next re-read is meant to compare against.
   */
  private fun writeDocuments(
    session: SqlSession,
    linkStatusCsv: File,
    linkStatuses: Map<String, RecordedLinkStatus>,
  ) {
    for ((url, recorded) in linkStatuses) {
      // NON-NULL BY CONSTRUCTION: this corpus reads its sidecar with
      // `dayAlwaysRequired`, so a verdict with no day was refused by file and
      // line before this loop began. The check is here because the shared
      // reader's type admits the corpus that legitimately has none -- and it
      // names the file and the verdict, because a check that fires is a bug in
      // that reader and the next reader needs both.
      val checkedOn =
        checkNotNull(recorded.checkedOn) {
          "[${linkStatusCsv.path}] gives [$url] the status [${recorded.status.value}] with no url_checked_on, " +
            "and this corpus reads the sidecar with dayAlwaysRequired: that reader must refuse it by line"
        }
      ExternalDocumentsDao
        .upsert(session, NewExternalDocument(url, recorded.status, checkedOn))
        .getOrElse { cause -> throw DocumentException(linkStatusCsv.path, url, cause) }
    }
  }

  /**
   * `link-status.csv` as a lookup by url, through the ONE reader every seed
   * corpus shares ([LinkStatusSidecar]).
   *
   * A DAY IS REQUIRED FOR EVERY STATUS here, where the swept corpus requires
   * one only where `source_documents_checked_when_known` does -- these answers
   * are committed by hand, and "we asked and cannot say when" is not a state a
   * hand-written file gets to be in.
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
      throw FormatException(Defect.Sidecar(file.path, line, fault), cause)
    }

  /**
   * One awards.csv row as the corpus states it: the coordinates the run keys
   * on, and the row it writes once the college is resolved.
   *
   * PURE. Every refusal awards.csv can earn is reachable from here with no
   * session at all, and the run's index and counts stay with [loadAwards],
   * which owns them. [inputFor] closes over cells that are ALREADY READ -- it
   * composes, it does not parse -- so a malformed cell is refused before
   * anything is written rather than during the write.
   */
  private data class ParsedAward(
    val line: Long,
    val key: CorpusKey,
    val ipedsUnitId: Int,
    val typedName: String,
    val inputFor: (CollegeId) -> NewNationalMeritAward,
  )

  /** One awards.csv row, read and refused; nothing here touches the database. */
  private fun parseAward(
    record: CSVRecord,
    awardsCsv: File,
    linkStatusCsv: File,
    linkStatuses: Map<String, RecordedLinkStatus>,
  ): ParsedAward {
    val file = awardsCsv.path
    val ipedsUnitId = getInt(record, awardsCsv, "unit_id")
    val typedName = getString(record, awardsCsv, "college_name")
    val entryYear = getAcademicYear(record, awardsCsv, "entry_year")
    val residencyBasis = getEnumCode(record, awardsCsv, "residency_basis", AWARD_RESIDENCY_BASES) { it.value }
    val awardKind = getEnumCode(record, awardsCsv, "award_kind", NationalMeritAwardKind.entries) { it.value }
    val standing =
      optional(record, "qualifying_standing")?.let { raw ->
        decode(record, awardsCsv, "qualifying_standing", raw, QualifyingStanding.entries) { it.value }
      }
    val nonePublished = awardKind == NationalMeritAwardKind.NONE_PUBLISHED
    // A standing on a row that says the school publishes nothing, and a paying
    // row that does not say WHICH standing it pays, are the same equality read
    // from the same cell -- and the CHECK cannot name the line somebody typed.
    if (nonePublished != (standing == null)) {
      throw FormatException(
        Defect.StandingAndKindDisagree(file, record.recordNumber, "qualifying_standing", awardKind, standing),
      )
    }
    val awardName = optional(record, "award_name")
    val perYear = publishedFigureOrNull(record, awardsCsv, "award_per_year_usd", MONEY_RANGE)
    val total = publishedFigureOrNull(record, awardsCsv, "award_total_usd", MONEY_RANGE)
    val years = publishedFigureOrNull(record, awardsCsv, "award_years", AWARD_YEARS_RANGE)
    val awardWords = optional(record, "award_as_published")
    val absenceWords = optional(record, "absence_as_published")
    assertWordsMatchKind(record, file, awardKind, awardWords, absenceWords)
    val firstChoiceRequired = booleanOrNull(record, awardsCsv, "first_choice_required")
    val firstChoiceDeadline = getDateOrNull(record, awardsCsv, "first_choice_deadline")
    val firstChoiceWords = optional(record, "first_choice_as_published")
    assertFirstChoiceTrio(record, file, firstChoiceRequired, firstChoiceDeadline, firstChoiceWords)
    val applyDeadline = getDateOrNull(record, awardsCsv, "apply_deadline")
    val applyWords = optional(record, "apply_deadline_as_published")
    if (applyDeadline != null && applyWords == null) {
      throw FormatException(
        Defect.DeadlineWithoutWords(
          file,
          record.recordNumber,
          "apply_deadline",
          applyDeadline,
          "apply_deadline_as_published",
        ),
      )
    }
    assertDeadlinesAreInCycle(record, file, entryYear, firstChoiceDeadline, applyDeadline)
    if (nonePublished) assertNothingElseIsStated(record, file)
    val publisher = getString(record, awardsCsv, "publisher")
    val sourceUrl =
      requireChecked(record, file, "source_url", getString(record, awardsCsv, "source_url"), linkStatuses, linkStatusCsv)
    val archiveUrl =
      optional(record, "archive_url")?.let { url ->
        if (url == sourceUrl) {
          throw FormatException(Defect.ArchiveRepeatsSource(file, record.recordNumber, "archive_url", url))
        }
        requireChecked(record, file, "archive_url", url, linkStatuses, linkStatusCsv)
      }
    val checkedOn = getDate(record, awardsCsv, "checked_on")
    val expiresOn = getDate(record, awardsCsv, "expires_on")
    if (!expiresOn.isAfter(checkedOn)) {
      throw FormatException(
        Defect.ExpiryNotAfterCheck(file, record.recordNumber, "expires_on", checkedOn, expiresOn),
      )
    }
    return ParsedAward(
      line = record.recordNumber,
      key = CorpusKey(ipedsUnitId, entryYear, residencyBasis, standing),
      ipedsUnitId = ipedsUnitId,
      typedName = typedName,
      inputFor = { collegeId ->
        NewNationalMeritAward(
          collegeId = collegeId,
          entryYear = entryYear,
          residencyBasis = residencyBasis,
          awardKind = awardKind,
          qualifyingStanding = standing,
          awardName = awardName,
          awardPerYearUsd = perYear,
          awardTotalUsd = total,
          awardYears = years,
          awardAsPublished = awardWords,
          absenceAsPublished = absenceWords,
          firstChoiceRequired = firstChoiceRequired,
          firstChoiceDeadline = firstChoiceDeadline,
          firstChoiceAsPublished = firstChoiceWords,
          applyDeadline = applyDeadline,
          applyDeadlineAsPublished = applyWords,
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
   * The claim and the sentence it was read from, as ONE fact per kind.
   *
   * The schema states this as two CHECKs and the read model `require`s both;
   * neither can say WHICH row. A paying row with no words is a figure nobody
   * can check us on, and a paying row carrying the ABSENCE sentence renders as
   * the school's own voice saying it pays nothing.
   */
  private fun assertWordsMatchKind(
    record: CSVRecord,
    file: String,
    awardKind: NationalMeritAwardKind,
    awardWords: String?,
    absenceWords: String?,
  ) {
    val nonePublished = awardKind == NationalMeritAwardKind.NONE_PUBLISHED
    val owedColumn = if (nonePublished) "absence_as_published" else "award_as_published"
    val owedWords = if (nonePublished) absenceWords else awardWords
    val borrowedColumn = if (nonePublished) "award_as_published" else "absence_as_published"
    val borrowedWords = if (nonePublished) awardWords else absenceWords
    if (owedWords == null) {
      throw FormatException(Defect.MissingWords(file, record.recordNumber, owedColumn, awardKind))
    }
    if (borrowedWords != null) {
      throw FormatException(Defect.BorrowedWords(file, record.recordNumber, borrowedColumn, awardKind, borrowedWords))
    }
  }

  /**
   * NMSC's first-choice mechanism as the three cells that carry it, refused by
   * line.
   *
   * The flag and the words are a PAIR -- a requirement with no sentence is our
   * claim rather than the school's -- and a DEADLINE is admitted only where the
   * requirement is actually stated, because a date with nothing to be the
   * deadline of is what a curator leaves behind when they correct the flag and
   * not the row.
   */
  private fun assertFirstChoiceTrio(
    record: CSVRecord,
    file: String,
    required: Boolean?,
    deadline: LocalDate?,
    asPublished: String?,
  ) {
    if ((required == null) != (asPublished == null)) {
      throw FormatException(
        Defect.FirstChoicePairBroken(
          file,
          record.recordNumber,
          "first_choice_required",
          "first_choice_as_published",
          required,
          asPublished,
        ),
      )
    }
    if (deadline != null && required != true) {
      throw FormatException(
        Defect.FirstChoiceDeadlineWithoutRequirement(file, record.recordNumber, "first_choice_deadline", deadline, required),
      )
    }
  }

  /**
   * Both deadlines inside the application cycle that ends in this row's entry
   * year, refused by line and by CELL.
   *
   * The same window the table's own `..._deadlines_in_cycle_check` admits,
   * asked where the line is still in hand: the CHECK knows the date is wrong
   * and cannot say which of twenty-six hand-typed rows carries it.
   */
  private fun assertDeadlinesAreInCycle(
    record: CSVRecord,
    file: String,
    entryYear: AcademicYear,
    firstChoiceDeadline: LocalDate?,
    applyDeadline: LocalDate?,
  ) {
    val cycle = applicationCycleOf(entryYear)
    listOf("first_choice_deadline" to firstChoiceDeadline, "apply_deadline" to applyDeadline)
      .forEach { (column, deadline) ->
        if (deadline != null && deadline !in cycle) {
          throw FormatException(
            Defect.DeadlineOutsideCycle(file, record.recordNumber, column, deadline, entryYear, cycle),
          )
        }
      }
  }

  /**
   * A verified absence states NOTHING else, and every cell that says otherwise
   * is named.
   *
   * There is no award for a name, a figure, a deadline or a first-choice rule
   * to describe, which is what
   * `college_national_merit_awards_none_published_is_empty_check` says in SQL
   * without being able to name the row.
   *
   * READ AGAINST THE PERMITTED SET, not against a hand-listed nine columns: the
   * twenty-third column this corpus grows would otherwise be permitted on an
   * absence row by default, which is the direction an allowlist cannot fail in.
   * A new column is refused here until somebody names it in
   * [NONE_PUBLISHED_CELLS].
   */
  private fun assertNothingElseIsStated(
    record: CSVRecord,
    file: String,
  ) {
    val stated = (AWARD_COLUMNS - NONE_PUBLISHED_CELLS).filter { column -> optional(record, column) != null }
    if (stated.isNotEmpty()) {
      throw FormatException(Defect.NonePublishedStatesAnAward(file, record.recordNumber, stated))
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
    awardsCsv: File,
    record: CSVRecord,
    parsed: ParsedAward,
  ): CollegeId {
    val college =
      withRowLocation(awardsCsv, record, parsed.ipedsUnitId) {
        CollegesDao.findByIpedsUnitId(session, parsed.ipedsUnitId)
      } ?: throw FormatException(
        Defect.UnknownCollege(awardsCsv.path, record.recordNumber, "unit_id", parsed.ipedsUnitId, parsed.typedName),
      )
    if (college.name.trim() != parsed.typedName) {
      throw FormatException(
        Defect.CollegeNameDisagreement(
          awardsCsv.path,
          record.recordNumber,
          "college_name",
          parsed.ipedsUnitId,
          parsed.typedName,
          college.name,
        ),
      )
    }
    return college.id
  }

  /**
   * The url, once it is known to have a committed answer.
   *
   * A url the sidecar never mentions is refused rather than written as "found
   * on the reading day": [ExternalDocumentsDao.citationIds] would record
   * exactly that, which is evidence nobody gathered.
   */
  private fun requireChecked(
    record: CSVRecord,
    file: String,
    column: String,
    url: String,
    linkStatuses: Map<String, RecordedLinkStatus>,
    linkStatusCsv: File,
  ): String {
    if (url !in linkStatuses) {
      throw FormatException(Defect.UncheckedUrl(file, record.recordNumber, column, url, linkStatusCsv.path))
    }
    return url
  }

  /**
   * Streams the corpus's rows with the arity check every row gets, so a
   * truncated line is this loader's located refusal rather than an unlocated
   * `IllegalArgumentException` out of Commons CSV.
   */
  private fun forEachRow(
    file: File,
    columns: List<String>,
    onRow: (CSVRecord) -> Unit,
  ) {
    parseCsv(file).use { records ->
      for (record in records) {
        if (!record.isConsistent || record.size() != columns.size) {
          throw FormatException(Defect.RowArity(file.path, record.recordNumber, record.size(), columns.size))
        }
        onRow(record)
      }
    }
  }

  /** Runs one row's DB call, naming the file, CSV line and unit id on failure. */
  private fun <T> withRowLocation(
    file: File,
    record: CSVRecord,
    ipedsUnitId: Int,
    call: () -> Result<T>,
  ): T =
    call().getOrElse { cause ->
      throw LoadException(file.path, record.recordNumber, ipedsUnitId, cause)
    }

  private fun assertHeader(
    source: SourceFile,
    expected: List<String>,
  ) {
    val actual = headerMismatchOf(source, expected) ?: return
    throw FormatException(Defect.HeaderMismatch(source.file.path, source.sourceArg, expected, actual))
  }

  /**
   * The shared located cell readers, one per corpus FILE, so a refusal names
   * the file it came from.
   *
   * THE READING IS [SeedCells]' AND THE MESSAGE IS THIS CORPUS'S. The rules --
   * a trimmed required cell, an integer, an ISO day, a coded cell against a
   * closed set, a year inside [AcademicYear]'s range -- are corpus-independent,
   * so a second hand-copied set of them is how one corpus comes to trim a cell
   * another does not.
   *
   * KEYED BY PATH rather than built once, because the file a defect names is
   * the caller's argument and not a constant: the committed corpus, a temp
   * directory in the suite and an operator's own copy are three different paths
   * through one loader.
   */
  private val cells: MutableMap<String, SeedCells> = mutableMapOf()

  private fun cellsOf(file: File): SeedCells =
    cells.getOrPut(file.path) {
      SeedCells { defect, cause ->
        // NOT_A_DECIMAL IS UNREACHABLE FOR THIS CORPUS, and it says so rather
        // than travelling as a located refusal: every figure here is whole
        // dollars or whole years, so nothing calls `getDecimalOrNull`. A new
        // decimal column needs a decision here, not a sentence about a cell
        // that is allowed not to be a whole number.
        if (defect.fault == SeedCellFault.NOT_A_DECIMAL) {
          // IT CARRIES THE CELL AND THE CAUSE. `error(...)` threw with neither,
          // where the line below carries both -- and the typed cell is the one
          // fact that tells the next reader whether a new decimal column
          // arrived or the shared reader mis-routed a fault.
          throw IllegalStateException(
            "[${file.path}] line [${defect.line}] column [${defect.column}] is [${defect.value}] and was read " +
              "as a decimal; this corpus stores no decimal cell, so a new one needs its own located defect here",
            cause,
          )
        }
        throw FormatException(Defect.Cell(file.path, defect), cause)
      }
    }

  private fun getInt(
    record: CSVRecord,
    file: File,
    column: String,
  ): Int = cellsOf(file).getInt(record, column)

  /**
   * A published figure, in the shape the column stores: absent, or inside
   * [allowed].
   *
   * REFUSED AND NOT COERCED. `college_national_merit_awards_money_positive_check`
   * admits no zero and no negative, and `award_years` is one to six; the loader
   * is the door that still holds the cell as it was typed and can name it.
   */
  private fun publishedFigureOrNull(
    record: CSVRecord,
    file: File,
    column: String,
    allowed: IntRange,
  ): Int? {
    val value = cellsOf(file).getIntOrNull(record, column) ?: return null
    if (value !in allowed) {
      throw FormatException(
        Defect.MoneyOutsidePublishedShape(file.path, record.recordNumber, column, value, allowed),
      )
    }
    return value
  }

  /**
   * A `BOOLEAN` cell, spelled as the two words the corpus commits.
   *
   * THROUGH [SeedCells.decode], so an unrecognised spelling is a located
   * [Defect.Cell] carrying [SeedCellFault.UNKNOWN_CODE] and naming both
   * accepted values -- `yes`, `TRUE` and `1` are all things a curator types,
   * and `String.toBoolean()` reads every one of them as FALSE.
   */
  private fun booleanOrNull(
    record: CSVRecord,
    file: File,
    column: String,
  ): Boolean? {
    val raw = optional(record, column) ?: return null
    return cellsOf(file).decode(record, column, raw, BOOLEAN_CELLS)
  }

  private fun getString(
    record: CSVRecord,
    file: File,
    column: String,
  ): String = cellsOf(file).getString(record, column)

  /**
   * An optional cell. It names no file because it CANNOT refuse -- a blank is
   * the absence this corpus spells -- so there is no located defect for it to
   * carry.
   */
  private fun optional(
    record: CSVRecord,
    column: String,
  ): String? = record.optionalCell(column)

  private fun getDate(
    record: CSVRecord,
    file: File,
    column: String,
  ): LocalDate = cellsOf(file).getDate(record, column)

  private fun getDateOrNull(
    record: CSVRecord,
    file: File,
    column: String,
  ): LocalDate? = cellsOf(file).getDateOrNull(record, column)

  private fun getAcademicYear(
    record: CSVRecord,
    file: File,
    column: String,
  ): AcademicYear = cellsOf(file).getAcademicYear(record, column)

  /** [SeedCells.getEnumCode] over a required cell -- the corpus's usual case. */
  private fun <T> getEnumCode(
    record: CSVRecord,
    file: File,
    column: String,
    values: List<T>,
    slugOf: (T) -> String,
  ): T = cellsOf(file).getEnumCode(record, column, values, slugOf)

  /**
   * A coded cell decoded through [values], or a located [Defect.Cell] carrying
   * [SeedCellFault.UNKNOWN_CODE] and naming every accepted value.
   */
  private fun <T> decode(
    record: CSVRecord,
    file: File,
    column: String,
    raw: String,
    values: List<T>,
    slugOf: (T) -> String,
  ): T = cellsOf(file).decode(record, column, raw, values.associateBy(slugOf))

  companion object {
    val AWARD_COLUMNS =
      listOf(
        "unit_id",
        "college_name",
        "entry_year",
        "residency_basis",
        "award_kind",
        "qualifying_standing",
        "award_name",
        "award_per_year_usd",
        "award_total_usd",
        "award_years",
        "award_as_published",
        "absence_as_published",
        "first_choice_required",
        "first_choice_deadline",
        "first_choice_as_published",
        "apply_deadline",
        "apply_deadline_as_published",
        "publisher",
        "source_url",
        "archive_url",
        "checked_on",
        "expires_on",
      )

    /**
     * The only cells a verified absence may fill: its coordinates, the sentence
     * that proves the absence, and its evidence.
     *
     * `qualifying_standing` and `award_as_published` are not among them and are
     * refused earlier, by name ([Defect.StandingAndKindDisagree],
     * [Defect.BorrowedWords]).
     * `college_national_merit_awards_none_published_is_empty_check` says the
     * same thing in SQL without being able to name the row.
     */
    val NONE_PUBLISHED_CELLS =
      setOf(
        "unit_id",
        "college_name",
        "entry_year",
        "residency_basis",
        "award_kind",
        "absence_as_published",
        "publisher",
        "source_url",
        "archive_url",
        "checked_on",
        "expires_on",
      )

    /**
     * The committed link answers' header: THREE columns, the shape every
     * hand-typed corpus commits and the shape `external_documents` names its
     * own columns by.
     */
    val LINK_STATUS_COLUMNS =
      listOf(
        "url",
        "url_status",
        "url_checked_on",
      )

    /**
     * The three bases a freshman award is published for.
     *
     * `in_district` and `unknown` are legal members of the shared vocabulary
     * and are refused here, exactly as the table's own
     * `..._residency_basis_check` refuses them: a community-college tuition
     * tier and an IPEDS headcount category are not things a university
     * publishes a freshman award against.
     */
    val AWARD_RESIDENCY_BASES =
      listOf(
        ResidencyBasis.IN_STATE,
        ResidencyBasis.OUT_OF_STATE,
        ResidencyBasis.NOT_APPLICABLE,
      )

    /**
     * The two spellings a `BOOLEAN` cell is committed as, named here so the
     * refusal can print them.
     */
    val BOOLEAN_CELLS = mapOf("true" to true, "false" to false)

    /**
     * What `award_per_year_usd` and `award_total_usd` admit: a POSITIVE number
     * of whole dollars (`..._money_positive_check`). The ceiling is
     * `INTEGER`'s, which is the column's.
     */
    val MONEY_RANGE: IntRange = 1..Int.MAX_VALUE

    /** What `award_years` admits (`..._money_positive_check`): one to six years. */
    val AWARD_YEARS_RANGE: IntRange = 1..6

    /**
     * The first day of the application cycle ending in an entry year: 1 July
     * the summer before it.
     *
     * A `MonthDay` and not a bare `7, 1`, the form `HandTypedReferenceData`
     * already uses for a cycle boundary: these two values are the one rule
     * deciding which apply deadlines this corpus accepts, and nothing in
     * `7, 1` says July.
     */
    val CYCLE_OPENS: MonthDay = MonthDay.of(Month.JULY, 1)

    /** And its last: 30 September of the entry year itself. */
    val CYCLE_CLOSES: MonthDay = MonthDay.of(Month.SEPTEMBER, 30)

    /**
     * The application cycle that ends in [entryYear]: [CYCLE_OPENS] the summer
     * before, through [CYCLE_CLOSES] of entry.
     *
     * THE TABLE'S OWN WINDOW, mirrored here because the CHECK cannot name a CSV
     * line -- and stated once, so the two halves of the deadline refusal cannot
     * come to disagree about which cycle a date belongs to.
     */
    fun applicationCycleOf(entryYear: AcademicYear): ClosedRange<LocalDate> =
      CYCLE_OPENS.atYear(entryYear.firstCalendarYear - 1)..CYCLE_CLOSES.atYear(entryYear.firstCalendarYear)
  }
}

/**
 * One rendering of a [NationalMeritAwardSeedLoader.Defect], at the boundary
 * rather than at each throw site -- so every variant's coordinates are stated
 * once and a new variant cannot ship with a message somebody forgot to write.
 */
private fun renderNationalMeritAwardDefect(defect: NationalMeritAwardSeedLoader.Defect): String =
  when (defect) {
    is NationalMeritAwardSeedLoader.Defect.HeaderMismatch -> {
      // BOTH HEADERS, not the derived set difference: `headerMismatchOf`
      // compares ORDERED lists, so a curator who moves one column trips this
      // with `missing` and `unexpected` both empty -- the operator told the
      // header is wrong and nothing else. Column order is part of this
      // contract, so the two lists are the whole answer.
      "national-merit award corpus file [${defect.file}] (from [${defect.sourceArg}]) has the wrong header: " +
        "expected ${defect.expected}, found ${defect.actual}; the order is part of the header, so a column " +
        "moved is a mismatch. Nothing was written"
    }

    is NationalMeritAwardSeedLoader.Defect.RowArity -> {
      "[${defect.file}] line [${defect.line}] has [${defect.cells}] cells, expected [${defect.expectedCells}]"
    }

    is NationalMeritAwardSeedLoader.Defect.UnknownCollege -> {
      "[${defect.file}] line [${defect.line}] column [${defect.column}]: unit_id [${defect.ipedsUnitId}] " +
        "(typed as [${defect.typedName}]) is in no `colleges` row. This corpus is hand-typed and REFUSES rather " +
        "than skipping: a row silently dropped here reads downstream as \"we have not read this school\", which " +
        "is false about a school somebody did read. Load the colleges snapshot first, or correct the unit_id."
    }

    is NationalMeritAwardSeedLoader.Defect.CollegeNameDisagreement -> {
      "[${defect.file}] line [${defect.line}] column [${defect.column}]: unit_id [${defect.ipedsUnitId}] is " +
        "[${defect.storedName}] in `colleges`, but this row types [${defect.typedName}]. A mis-keyed row is " +
        "another school's money under this school's name -- a fuzzy match of these names returned eight wrong " +
        "schools -- so the pair must agree exactly."
    }

    is NationalMeritAwardSeedLoader.Defect.DuplicateAwardKey -> {
      "[${defect.file}] line [${defect.line}] repeats the key already read at line [${defect.firstLine}]: " +
        "unit_id [${defect.ipedsUnitId}] entry_year [${defect.entryYear.firstCalendarYear}] " +
        "residency_basis [${defect.residencyBasis.value}] qualifying_standing " +
        "[${defect.qualifyingStanding?.value}]. One school publishes ONE answer per standing, entry year and " +
        "residency basis; the second row would silently overwrite the first's money."
    }

    is NationalMeritAwardSeedLoader.Defect.StandingAndKindDisagree -> {
      "[${defect.file}] line [${defect.line}] column [${defect.column}] is " +
        "[${defect.qualifyingStanding?.value}] on an award_kind of [${defect.awardKind.value}] " +
        "(college_national_merit_awards_standing_iff_award_check). A school that publishes nothing pays no " +
        "standing, and a school that pays must say WHICH standing it pays."
    }

    is NationalMeritAwardSeedLoader.Defect.MissingWords -> {
      "[${defect.file}] line [${defect.line}] column [${defect.column}] is empty on an award_kind of " +
        "[${defect.awardKind.value}] (college_national_merit_awards_claim_has_words_check / " +
        "..._absence_has_words_check). Every claim carries the page's own sentence: a figure or an absence with " +
        "no words is a claim nobody can check us on."
    }

    is NationalMeritAwardSeedLoader.Defect.BorrowedWords -> {
      "[${defect.file}] line [${defect.line}] column [${defect.column}] states [${defect.value}] on an " +
        "award_kind of [${defect.awardKind.value}] " +
        "(college_national_merit_awards_claim_has_words_check / ..._absence_has_words_check). The two kinds of " +
        "sentence never cross: an absence quote on a paying row renders as the school saying it pays nothing."
    }

    is NationalMeritAwardSeedLoader.Defect.NonePublishedStatesAnAward -> {
      "[${defect.file}] line [${defect.line}] is award_kind [none_published] and still states " +
        "${defect.columns} (college_national_merit_awards_none_published_is_empty_check). A school we verified " +
        "publishes no National Merit award has no award for a name, a figure, a deadline or a first-choice rule " +
        "to describe."
    }

    is NationalMeritAwardSeedLoader.Defect.MoneyOutsidePublishedShape -> {
      "[${defect.file}] line [${defect.line}] column [${defect.column}] is [${defect.value}], which is outside " +
        "[${defect.allowed.first}..${defect.allowed.last}] " +
        "(college_national_merit_awards_money_positive_check). A typed zero is not a published price: this " +
        "corpus prices half its schools in WORDS, and the empty cell is how it says so."
    }

    is NationalMeritAwardSeedLoader.Defect.FirstChoicePairBroken -> {
      "[${defect.file}] line [${defect.line}] has ${defect.requiredColumn} [${defect.required}] and " +
        "${defect.wordsColumn} [${defect.asPublished}] " +
        "(college_national_merit_awards_first_choice_trio_check): the two are a pair. Naming a college first " +
        "choice with NMSC is an act with consequences elsewhere, so a requirement with no sentence is our claim " +
        "and not theirs."
    }

    is NationalMeritAwardSeedLoader.Defect.FirstChoiceDeadlineWithoutRequirement -> {
      "[${defect.file}] line [${defect.line}] column [${defect.column}] is [${defect.deadline}] on a row whose " +
        "first_choice_required is [${defect.required}] " +
        "(college_national_merit_awards_first_choice_trio_check): a date with nothing to be the deadline of."
    }

    is NationalMeritAwardSeedLoader.Defect.DeadlineWithoutWords -> {
      "[${defect.file}] line [${defect.line}] stores [${defect.column}] as [${defect.deadline}] and its " +
        "[${defect.wordsColumn}] cell is empty. A stored date carries the sentence it was read from, which is " +
        "what the table's ..._pair_check enforces without being able to name this line."
    }

    is NationalMeritAwardSeedLoader.Defect.DeadlineOutsideCycle -> {
      "[${defect.file}] line [${defect.line}] column [${defect.column}] is [${defect.deadline}], outside the " +
        "application cycle that ends in entry year [${defect.entryYear.firstCalendarYear}] " +
        "([${defect.cycle.start}] to [${defect.cycle.endInclusive}], " +
        "college_national_merit_awards_deadlines_in_cycle_check). A date typed into the wrong cycle is the " +
        "mistake a curator actually makes, and a deadline that passed before the student started reads exactly " +
        "like one that has not."
    }

    is NationalMeritAwardSeedLoader.Defect.ExpiryNotAfterCheck -> {
      "[${defect.file}] line [${defect.line}] column [${defect.column}] is [${defect.expiresOn}], on or before " +
        "checked_on [${defect.checkedOn}] (college_national_merit_awards_expiry_after_checked_check). " +
        "expires_on is the day we stop believing the page, never a deadline the row states."
    }

    is NationalMeritAwardSeedLoader.Defect.ArchiveRepeatsSource -> {
      "[${defect.file}] line [${defect.line}] column [${defect.column}] repeats source_url [${defect.url}] " +
        "(college_national_merit_awards_archive_differs_check). A mirror is a SECOND address; one address cited " +
        "twice states one piece of evidence as two."
    }

    is NationalMeritAwardSeedLoader.Defect.UncheckedUrl -> {
      "[${defect.file}] line [${defect.line}] column [${defect.column}] cites [${defect.url}], which " +
        "[${defect.statusFile}] does not mention. A citation states its evidence or it cannot be stored " +
        "(RFC 220): commit the link answer for this url beside the row that cites it."
    }

    is NationalMeritAwardSeedLoader.Defect.EmptyAwardCorpus -> {
      "[${defect.file}] carries no award row. The load ends by dropping every award this corpus no longer names, " +
        "so an empty file would unpublish every school: that is a truncated corpus, not an instruction. Nothing " +
        "was written."
    }

    is NationalMeritAwardSeedLoader.Defect.MissingLinkStatusFile -> {
      "the national-merit award corpus requires its committed link answers at [${defect.file}], and no file is " +
        "there; nothing was written"
    }

    is NationalMeritAwardSeedLoader.Defect.Cell -> {
      renderSeedCellDefect(defect.file, defect.cell)
    }

    is NationalMeritAwardSeedLoader.Defect.Sidecar -> {
      renderLinkStatusFault(defect.file, defect.line, defect.fault)
    }
  }
