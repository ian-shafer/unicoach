package ed.unicoach.college

import ed.unicoach.db.Database
import ed.unicoach.db.dao.CollegeTuitionExchangesDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.UpsertOutcome
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.CollegeTuitionExchangeId
import ed.unicoach.db.models.ExchangeSelection
import ed.unicoach.db.models.NewCollegeTuitionExchange
import ed.unicoach.db.models.SeparateApplication
import ed.unicoach.db.models.TuitionExchange
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.csv.CSVRecord
import java.io.File

/**
 * Loader for the committed tuition-exchange corpus (RFC 277):
 * `db/seed/tuition-exchanges/exchanges.csv` and the `link-status.csv` beside it,
 * into `college_tuition_exchanges`. It runs from `bin/ingest-colleges` because
 * the rows key on `colleges(id)`, which the import fills.
 *
 * It refuses and never skips: a mis-keyed row is one school's terms under
 * another school's name, so a unit id with no college and a name that disagrees
 * with `colleges.name` are both fatal and both name the row.
 *
 * The link answers are written first, so the citation each row then makes cannot
 * record "we asked on the reading day" over the answer committed beside it. The
 * whole corpus loads on one connection, so a refusal rolls all of it back. A
 * value outside the table's own CHECKs comes back as a [LoadException] naming
 * the file and line.
 */
class TuitionExchangeSeedLoader(
  private val database: Database,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
  /** The closed set of ways the corpus can disagree with this loader. */
  sealed interface Defect {
    data class HeaderMismatch(
      val file: String,
      val sourceArg: String,
      val expected: List<String>,
      val actual: List<String>,
    ) : Defect

    data class RowArity(
      val file: String,
      val line: Long,
      val cells: Int,
      val expectedCells: Int,
    ) : Defect

    data class Cell(
      val file: String,
      val cell: SeedCellDefect,
    ) : Defect

    data class Sidecar(
      val file: String,
      val line: Long,
      val fault: LinkStatusFault,
    ) : Defect

    data class UnknownCollege(
      val file: String,
      val line: Long,
      val ipedsUnitId: Int,
      val typedName: String,
    ) : Defect

    data class CollegeNameDisagreement(
      val file: String,
      val line: Long,
      val ipedsUnitId: Int,
      val typedName: String,
      val storedName: String,
    ) : Defect

    data class DuplicateKey(
      val file: String,
      val line: Long,
      val firstLine: Long,
      val ipedsUnitId: Int,
      val exchange: TuitionExchange,
    ) : Defect

    data class UncheckedUrl(
      val file: String,
      val line: Long,
      val url: String,
      val statusFile: String,
    ) : Defect

    data class PercentAboveCeiling(
      val file: String,
      val line: Long,
      val ipedsUnitId: Int,
      val exchange: TuitionExchange,
      val percent: Int,
    ) : Defect

    data class EmptyCorpus(
      val file: String,
    ) : Defect

    data class MissingLinkStatusFile(
      val file: String,
    ) : Defect
  }

  /** A corpus file this loader refuses. [defect] is the payload; the message is one rendering of it. */
  class FormatException(
    val defect: Defect,
    cause: Throwable? = null,
  ) : RuntimeException(renderDefect(defect), cause)

  /** A database failure on one row, restating that row's location with the DAO exception as the cause. */
  class LoadException(
    val file: String,
    val line: Long,
    val ipedsUnitId: Int,
    cause: Throwable,
  ) : RuntimeException("[$file] line [$line]: unit_id [$ipedsUnitId] failed: [${cause.message}]", cause)

  /** A database failure on one committed link answer. */
  class DocumentException(
    val statusFile: String,
    val url: String,
    cause: Throwable,
  ) : RuntimeException("[$statusFile]: url [$url] failed: [${cause.message}]", cause)

  /** What the run wrote. A re-load of an unchanged corpus is all [unchanged]; [pruned] is what a re-read dropped. */
  data class LoadResult(
    val documents: Int,
    val upserted: Int,
    val changed: Int,
    val unchanged: Int,
    val pruned: Int,
  )

  /** The cells a row is checked and resolved by, read before its terms, with the line they came from. */
  private data class RowIdentity(
    val line: Long,
    val unitId: Int,
    val exchange: TuitionExchange,
    val sourceUrl: String,
    val collegeName: String,
  )

  private data class Written(
    val id: CollegeTuitionExchangeId,
    val line: Long,
    val outcome: UpsertOutcome,
  )

  /** Asserts both headers and writes nothing, so a renamed column is found before the ingest's first phase commits. */
  suspend fun assertHeaders(source: SourceFile) =
    withContext(ioDispatcher) {
      assertHeader(source, EXCHANGE_COLUMNS)
      assertLinkStatusFile(SourceFile(sidecarBeside(source.file), source.sourceArg))
    }

  /** Loads the corpus: link answers, then rows, then the prune, in one transaction. */
  suspend fun load(
    exchangesCsv: File,
    linkStatusCsv: File = sidecarBeside(exchangesCsv),
  ): LoadResult =
    database.withConnection { session ->
      assertHeader(SourceFile(exchangesCsv, exchangesCsv.path), EXCHANGE_COLUMNS)
      assertLinkStatusFile(SourceFile(linkStatusCsv, linkStatusCsv.path))
      val linkStatuses = sidecarReader.read(linkStatusCsv)
      writeLinkAnswers(
        session,
        linkStatuses,
        datelessMessage = { url, _ ->
          "[${linkStatusCsv.path}] gives [$url] no url_checked_on, and this writer takes a sidecar read with " +
            "dayAlwaysRequired: that reader must refuse it by line"
        },
      ) { url, cause -> throw DocumentException(linkStatusCsv.path, url, cause) }
      val written = loadRows(session, exchangesCsv, linkStatusCsv, linkStatuses.keys)
      val pruned = prune(session, exchangesCsv, written)
      val outcomes = written.groupingBy { it.outcome }.eachCount()
      LoadResult(
        documents = linkStatuses.size,
        upserted = outcomes[UpsertOutcome.INSERTED] ?: 0,
        changed = outcomes[UpsertOutcome.CHANGED] ?: 0,
        unchanged = outcomes[UpsertOutcome.UNCHANGED] ?: 0,
        pruned = pruned,
      )
    }

  /** Every row in file order, each checked and then written, so the first bad row aborts the transaction by its line. */
  private fun loadRows(
    session: SqlSession,
    exchangesCsv: File,
    linkStatusCsv: File,
    answeredUrls: Set<String>,
  ): List<Written> {
    val cells = cellsOf(exchangesCsv)
    val firstLineByKey = mutableMapOf<Pair<Int, TuitionExchange>, Long>()
    return parseCsv(exchangesCsv).use { records ->
      records.map { record ->
        requireArity(exchangesCsv, record)
        val identity = identityOf(cells, record)
        requireFirstMention(exchangesCsv, identity, firstLineByKey)
        requireAnsweredUrl(exchangesCsv, linkStatusCsv, identity, answeredUrls)
        val collegeId = resolveCollege(session, exchangesCsv, identity)
        val row = newRowOf(cells, record, identity, collegeId)
        requireWithinCeiling(exchangesCsv, identity, row.maxPercentOfResidentTuition)
        writeRow(session, exchangesCsv, identity, row)
      }
    }
  }

  private fun identityOf(
    cells: SeedCells,
    record: CSVRecord,
  ): RowIdentity =
    RowIdentity(
      line = record.recordNumber,
      unitId = cells.getInt(record, "unit_id"),
      exchange = cells.getEnumCode(record, "exchange", TuitionExchange.entries) { it.value },
      sourceUrl = cells.getString(record, "source_url"),
      collegeName = cells.getString(record, "college_name"),
    )

  private fun requireFirstMention(
    exchangesCsv: File,
    identity: RowIdentity,
    firstLineByKey: MutableMap<Pair<Int, TuitionExchange>, Long>,
  ) {
    val firstLine = firstLineByKey.putIfAbsent(identity.unitId to identity.exchange, identity.line) ?: return
    throw FormatException(Defect.DuplicateKey(exchangesCsv.path, identity.line, firstLine, identity.unitId, identity.exchange))
  }

  private fun requireAnsweredUrl(
    exchangesCsv: File,
    linkStatusCsv: File,
    identity: RowIdentity,
    answeredUrls: Set<String>,
  ) {
    if (identity.sourceUrl !in answeredUrls) {
      throw FormatException(Defect.UncheckedUrl(exchangesCsv.path, identity.line, identity.sourceUrl, linkStatusCsv.path))
    }
  }

  private fun resolveCollege(
    session: SqlSession,
    exchangesCsv: File,
    identity: RowIdentity,
  ): CollegeId =
    resolveCollegeNamed(
      session,
      identity.unitId,
      identity.collegeName,
      onLookupFailure = { cause -> throw LoadException(exchangesCsv.path, identity.line, identity.unitId, cause) },
      onUnknown = {
        throw FormatException(Defect.UnknownCollege(exchangesCsv.path, identity.line, identity.unitId, identity.collegeName))
      },
      onNameDisagreement = { storedName ->
        throw FormatException(
          Defect.CollegeNameDisagreement(exchangesCsv.path, identity.line, identity.unitId, identity.collegeName, storedName),
        )
      },
    )

  private fun newRowOf(
    cells: SeedCells,
    record: CSVRecord,
    identity: RowIdentity,
    collegeId: CollegeId,
  ): NewCollegeTuitionExchange =
    NewCollegeTuitionExchange(
      collegeId = collegeId,
      exchange = identity.exchange,
      maxPercentOfResidentTuition = cells.getInt(record, "max_percent_of_resident_tuition"),
      selection = cells.getEnumCode(record, "selection", ExchangeSelection.entries) { it.value },
      separateApplication = cells.getEnumCode(record, "separate_application", SeparateApplication.entries) { it.value },
      minimumUnweightedGpa = cells.getDecimalOrNull(record, "minimum_unweighted_gpa"),
      percentOfEligibleOffered = cells.getIntOrNull(record, "percent_of_eligible_offered"),
      applyBy = cells.getDateOrNull(record, "apply_by"),
      sourceUrl = identity.sourceUrl,
      checkedOn = cells.getDate(record, "checked_on"),
      expiresOn = cells.getDate(record, "expires_on"),
    )

  private fun requireWithinCeiling(
    exchangesCsv: File,
    identity: RowIdentity,
    percent: Int,
  ) {
    if (percent > identity.exchange.ceilingPercentOfResidentTuition) {
      throw FormatException(Defect.PercentAboveCeiling(exchangesCsv.path, identity.line, identity.unitId, identity.exchange, percent))
    }
  }

  private fun writeRow(
    session: SqlSession,
    exchangesCsv: File,
    identity: RowIdentity,
    row: NewCollegeTuitionExchange,
  ): Written {
    val result =
      CollegeTuitionExchangesDao
        .upsert(session, row)
        .getOrElse { cause -> throw LoadException(exchangesCsv.path, identity.line, identity.unitId, cause) }
    return Written(result.id, identity.line, result.outcome)
  }

  /**
   * Drops every school the corpus no longer names, so the coach stops quoting a
   * re-read's casualty. A file with no row is refused here: it is a truncated
   * file, not an instruction to unpublish every school.
   */
  private fun prune(
    session: SqlSession,
    exchangesCsv: File,
    written: List<Written>,
  ): Int {
    if (written.isEmpty()) throw FormatException(Defect.EmptyCorpus(exchangesCsv.path))
    return CollegeTuitionExchangesDao.deleteExcept(session, written.map { it.id }).getOrThrow()
  }

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

  private fun cellsOf(file: File): SeedCells = SeedCells { defect, cause -> throw FormatException(Defect.Cell(file.path, defect), cause) }

  private fun assertLinkStatusFile(source: SourceFile) {
    requireLinkStatusFile(source.file) { throw FormatException(Defect.MissingLinkStatusFile(source.file.path)) }
    assertHeader(source, LINK_STATUS_COLUMNS)
  }

  private fun requireArity(
    file: File,
    record: CSVRecord,
  ) {
    if (!record.isConsistent || record.size() != EXCHANGE_COLUMNS.size) {
      throw FormatException(Defect.RowArity(file.path, record.recordNumber, record.size(), EXCHANGE_COLUMNS.size))
    }
  }

  private fun assertHeader(
    source: SourceFile,
    expected: List<String>,
  ) {
    val actual = headerMismatchOf(source, expected) ?: return
    throw FormatException(Defect.HeaderMismatch(source.file.path, source.sourceArg, expected, actual))
  }

  companion object {
    val EXCHANGE_COLUMNS =
      listOf(
        "unit_id",
        "college_name",
        "exchange",
        "max_percent_of_resident_tuition",
        "selection",
        "separate_application",
        "minimum_unweighted_gpa",
        "percent_of_eligible_offered",
        "apply_by",
        "source_url",
        "checked_on",
        "expires_on",
      )

    val LINK_STATUS_COLUMNS = listOf("url", "url_status", "url_checked_on")
  }
}

private fun renderDefect(defect: TuitionExchangeSeedLoader.Defect): String =
  when (defect) {
    is TuitionExchangeSeedLoader.Defect.HeaderMismatch -> {
      "tuition-exchange corpus file [${defect.file}] (from [${defect.sourceArg}]) has the wrong header: " +
        "expected ${defect.expected}, found ${defect.actual}. Nothing was written"
    }

    is TuitionExchangeSeedLoader.Defect.RowArity -> {
      "[${defect.file}] line [${defect.line}] has [${defect.cells}] cells, expected [${defect.expectedCells}]"
    }

    is TuitionExchangeSeedLoader.Defect.Cell -> {
      renderSeedCellDefect(defect.file, defect.cell)
    }

    is TuitionExchangeSeedLoader.Defect.Sidecar -> {
      renderLinkStatusFault(defect.file, defect.line, defect.fault)
    }

    is TuitionExchangeSeedLoader.Defect.UnknownCollege -> {
      "[${defect.file}] line [${defect.line}]: unit_id [${defect.ipedsUnitId}] (typed as [${defect.typedName}]) " +
        "is in no `colleges` row. Load the colleges snapshot first, or correct the unit_id"
    }

    is TuitionExchangeSeedLoader.Defect.CollegeNameDisagreement -> {
      "[${defect.file}] line [${defect.line}]: unit_id [${defect.ipedsUnitId}] is [${defect.storedName}] in " +
        "`colleges`, but this row types [${defect.typedName}]; the pair must agree exactly"
    }

    is TuitionExchangeSeedLoader.Defect.DuplicateKey -> {
      "[${defect.file}] line [${defect.line}] repeats unit_id [${defect.ipedsUnitId}] exchange " +
        "[${defect.exchange.value}] from line [${defect.firstLine}]"
    }

    is TuitionExchangeSeedLoader.Defect.UncheckedUrl -> {
      "[${defect.file}] line [${defect.line}] cites [${defect.url}], which [${defect.statusFile}] does not " +
        "mention: commit the link answer beside the row that cites it"
    }

    is TuitionExchangeSeedLoader.Defect.PercentAboveCeiling -> {
      "[${defect.file}] line [${defect.line}]: unit_id [${defect.ipedsUnitId}] caps tuition at [${defect.percent}]% of " +
        "resident tuition, above the [${defect.exchange.ceilingPercentOfResidentTuition}]% that " +
        "[${defect.exchange.value}] publishes as its highest. Correct the cell"
    }

    is TuitionExchangeSeedLoader.Defect.EmptyCorpus -> {
      "[${defect.file}] carries no row, and the load ends by dropping every row the corpus no longer names, so " +
        "this is a truncated file and not an instruction. Nothing was written"
    }

    is TuitionExchangeSeedLoader.Defect.MissingLinkStatusFile -> {
      "the tuition-exchange corpus requires its committed link answers at [${defect.file}], and no file is " +
        "there; nothing was written"
    }
  }
