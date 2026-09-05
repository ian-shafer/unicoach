package ed.unicoach.coaching.costs.canonical

import ed.unicoach.db.Database
import ed.unicoach.db.dao.CanonicalMoneyReadDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.models.CanonicalMoneyRead
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.UnreadableMoneyRow
import org.slf4j.LoggerFactory

/**
 * The canonical money store's read side, folded per college (RFC 166 §2).
 *
 * TWO statements for the whole answer, whatever the size of the list: one over
 * `price_figures` and one over `cohort_money_stats`, both batched over the ids
 * already selected. That is the batching contract `CollegeCostServiceTest`
 * pins -- a fifty-school list must cost the same statements as a one-school
 * list -- and it is why the year and vintage selection
 * ([CollegeFigures.publishedPriceYearOf] and its latest-vintage cohort fold) is
 * Kotlin rather than a window function: a college carries ~48 price rows and
 * ~35 cohort rows, so the whole
 * of a request's money data is a small fold that a DB-free test can drive.
 *
 * THE one door onto the store. Every consumer of canonical money -- the cost
 * answer and the fit-lens digest -- reads through this interface, so the
 * cohort-address rule, the fold and the failure handling are stated once. The
 * digest used to call the DAO itself and hand-assemble a half-populated
 * [CollegeFigures]; that second path had to be chased by hand the first time
 * the address rule moved, which is exactly how two surfaces come to quote two
 * different net prices.
 *
 * An INTERFACE, injected with a default, rather than an `object`: this is the
 * IO-bound collaborator every cost figure comes from, so a test must be able to
 * substitute it -- above all to prove what a FAILED read does, which otherwise
 * can only be induced by closing a connection pool underneath a running
 * service.
 *
 * FAILURE is part of the contract, not left to whatever the store threw: every
 * function here answers, or throws [CanonicalReadFailedException] carrying the
 * store fault as its cause. A `:db` exception -- a `DatabaseException` wrapping
 * a `PSQLException`, say -- never escapes this port, so no caller has to speak
 * `:db`'s vocabulary to handle a failed read.
 */
interface CanonicalCostReader {
  /**
   * Every selected college's figures, keyed by id. A college with no canonical
   * row at all still gets an (empty) entry, because "this college has no money
   * data" is an answer the cost path must be able to give without a null check
   * at every call site.
   *
   * The CALLER owns the [SqlSession]: the Family Cost Report resolves a share
   * token first and then reads the whole profile on that one connection, so one
   * read is one snapshot.
   */
  fun read(
    session: SqlSession,
    collegeIds: List<CollegeId>,
  ): Map<CollegeId, CollegeFigures>

  /**
   * The COHORT half only -- one statement -- for a caller that wants a
   * population statistic and no published price at all (RFC 166 §9, the
   * fit-lens digest).
   *
   * The price side is deliberately not read rather than being read and thrown
   * away, and the empty `priceFigures` is this function's business, not its
   * caller's: a caller assembling that aggregate itself is asserting, silently,
   * that the cohort readers never consult the price side.
   */
  fun readCohortStats(
    session: SqlSession,
    collegeIds: List<CollegeId>,
  ): Map<CollegeId, CollegeFigures>

  /**
   * The same cohort read for a caller with NO session of its own: the reader
   * opens and closes its own short-lived connection.
   *
   * The fit-lens pass reads here between two LLM calls, holding no transaction
   * -- so it must not have to open a connection merely because a DAO wants a
   * session. Where a caller does own a snapshot it passes it, and gets the
   * overload above.
   */
  suspend fun readCohortStats(collegeIds: List<CollegeId>): Map<CollegeId, CollegeFigures>
}

/**
 * The port's ONE failure: the canonical store could not be read at all --
 * a pool checkout timeout, a dropped connection, a missing table -- with the
 * store fault kept whole as [cause] for the log.
 *
 * Declared here, beside the contract it belongs to, so a `:db` type is not the
 * de facto failure vocabulary of a `:service` port: a caller that wants to
 * treat a transient fault differently from a permanent one would otherwise have
 * to import `ed.unicoach.db.dao` into a coaching class to name what it is
 * catching. The sibling read port in this same cost path says the same thing
 * the same way ([ed.unicoach.web.report.ServiceCostReportSource]'s
 * `CostReportReadFailedException`).
 *
 * A row this build cannot DECODE is NOT this: it costs its own row, is warned
 * about, and the read still answers.
 */
class CanonicalReadFailedException(
  cause: Throwable,
) : RuntimeException("canonical money read failed", cause)

/**
 * The store-backed reader: the only implementation that reaches Postgres.
 *
 * Also the ONE place an unreadable row is spoken about. The DAO carries a row
 * this build cannot decode out of the batch as data (nothing in `:db` logs);
 * here it becomes a warning naming the column and the row's natural key, so a
 * deploy-skew gap is visible to an operator instead of reading, downstream, as
 * missing source coverage.
 */
class DbCanonicalCostReader(
  private val database: Database,
) : CanonicalCostReader {
  private val logger = LoggerFactory.getLogger(DbCanonicalCostReader::class.java)

  override fun read(
    session: SqlSession,
    collegeIds: List<CollegeId>,
  ): Map<CollegeId, CollegeFigures> {
    val prices = CanonicalMoneyReadDao.listPriceFigures(session, collegeIds).orFail().warned()
    val stats = CanonicalMoneyReadDao.listCohortStats(session, collegeIds).orFail().warned()
    return collegeIds.associateWith { id ->
      CollegeFigures(
        collegeId = id,
        priceFigures = prices[id].orEmpty(),
        cohortStats = stats[id].orEmpty(),
      )
    }
  }

  override fun readCohortStats(
    session: SqlSession,
    collegeIds: List<CollegeId>,
  ): Map<CollegeId, CollegeFigures> {
    val stats = CanonicalMoneyReadDao.listCohortStats(session, collegeIds).orFail().warned()
    return collegeIds.associateWith { id ->
      CollegeFigures(
        collegeId = id,
        priceFigures = emptyList(),
        cohortStats = stats[id].orEmpty(),
      )
    }
  }

  override suspend fun readCohortStats(collegeIds: List<CollegeId>): Map<CollegeId, CollegeFigures> =
    database.withConnection { session -> readCohortStats(session, collegeIds) }

  /**
   * The store's [Result] unwrapped INTO the port's own failure: a read that
   * could not run leaves here as [CanonicalReadFailedException], never as the
   * `:db` type the DAO happened to map the driver fault to.
   */
  private fun <T> Result<T>.orFail(): T = getOrElse { throw CanonicalReadFailedException(it) }

  /** Warns for each row the batch could not decode, and answers the rows it could. */
  private fun <T> CanonicalMoneyRead<T>.warned(): Map<CollegeId, List<T>> {
    for (row in unreadable) warn(row)
    return byCollege
  }

  /**
   * The whole evidence trail for a figure that was quietly withdrawn from a
   * family's answer, so the decode failure is logged AS a throwable -- slf4j's
   * last argument, ahead of no placeholder -- and not merely described. The
   * frame that threw (`decode` vs `FigureReading.of`) is what an operator reads
   * in the deploy-skew incident this path exists for, and a sentence cannot
   * carry it.
   */
  private fun warn(row: UnreadableMoneyRow) {
    logger.warn(
      "canonical money read skipped an unreadable [{}] row: stored=[{}] at [{}]; the figure it carries is " +
        "answered as one we have not collected, and the rest of the batch is unaffected",
      row.table,
      row.stored,
      row.location,
      row.cause,
    )
  }
}
