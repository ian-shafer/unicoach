package ed.unicoach.db.models

/**
 * The insert input. Unlike most `New*` rows this one carries the [id]: the share
 * token is derived from the row id (RFC 155), so the id has to exist before the
 * hash that is stored with it. [CostReportSharesDao.nextId] mints it from the
 * same `uuidv7()` generator the column default would have used.
 */
data class NewCostReportShare(
  val id: CostReportShareId,
  val studentId: StudentId,
  val tokenHash: TokenHash,
)
