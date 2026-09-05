package ed.unicoach.db.dao

import java.sql.BatchUpdateException
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Statement

/**
 * ONE prepared statement, JDBC-batched, shared by every wholesale-rebuild
 * writer (RFC 158's canonical fill, RFC 162's SFA staging rebuild): those
 * phases write 60k-400k rows a run, and one round trip per row is the
 * difference between a phase and a coffee break. Executed in [BATCH_ROWS]
 * chunks so the driver never buffers a whole rebuild.
 *
 * [mapError] is the caller's own SQLSTATE mapping, so a foreign-key refusal
 * still arrives as the sentence that table's DAO would have written.
 */
internal fun <T> batchInsert(
  session: SqlSession,
  sql: String,
  rows: List<T>,
  mapError: (SQLException) -> Exception,
  bind: (PreparedStatement, T) -> Unit,
): Result<Int> =
  try {
    var written = 0
    session.prepareStatement(sql).use { stmt ->
      rows.chunked(BATCH_ROWS).forEach { chunk ->
        for (row in chunk) {
          bind(stmt, row)
          stmt.addBatch()
        }
        written += rowsWritten(stmt.executeBatch())
      }
    }
    Result.success(written)
  } catch (e: BatchUpdateException) {
    // Keep the wrapper's batch context beside the server diagnostics: how far
    // the failing chunk got says which row family broke the rebuild. The
    // mapped root cause stays the failure type (callers switch on it); the
    // wrapper rides along as a suppressed exception, lossless.
    val mapped = mapError(rootSqlException(e))
    val executed = e.updateCounts?.count { it != Statement.EXECUTE_FAILED } ?: 0
    val attempted = e.updateCounts?.size ?: 0
    // The wrapper itself rides along, not a sentence copied out of it: the
    // `BatchUpdateException` is the head of the driver's `nextException` chain,
    // and the mapped root cause is only its LAST link. Attaching the head keeps
    // every intermediate diagnostic reachable, which is what "lossless" claimed.
    mapped.addSuppressed(
      IllegalStateException("batch context: [$executed] of [$attempted] statement(s) in the failing chunk had executed", e),
    )
    Result.failure(mapped)
  } catch (e: SQLException) {
    Result.failure(mapError(rootSqlException(e)))
  } catch (e: Exception) {
    Result.failure(mapDatabaseError(e))
  }

/**
 * The rows one `executeBatch()` actually wrote.
 *
 * JDBC lets a driver answer a per-statement row count, `SUCCESS_NO_INFO`
 * (-2: the statement succeeded, the count is unknown) or `EXECUTE_FAILED`
 * (-3). `.sum()` over that array adds the negative
 * SENTINELS to the counts, so a driver -- or a future PgJDBC mode -- that
 * reports SUCCESS_NO_INFO would make a rebuild that wrote 500 rows report 0,
 * and that number is stored as provenance (`college_index_build`). A statement
 * that succeeded wrote exactly the one row this batch bound for it, so it
 * counts as 1.
 *
 * EXECUTE_FAILED cannot reach here from a successful `executeBatch()` -- the
 * driver throws `BatchUpdateException` instead, which the caller catches -- so
 * seeing one means the batch contract broke, and a silently short row count is
 * the worst possible answer to that.
 */
internal fun rowsWritten(counts: IntArray): Int =
  counts.sumOf { count ->
    when (count) {
      Statement.SUCCESS_NO_INFO -> {
        1
      }

      Statement.EXECUTE_FAILED -> {
        error("executeBatch() returned EXECUTE_FAILED without throwing BatchUpdateException; the batch row count cannot be trusted")
      }

      else -> {
        count
      }
    }
  }

/**
 * A JDBC batch failure arrives as a `BatchUpdateException` whose chained
 * `nextException` is the driver exception carrying the server diagnostics
 * (constraint name, DETAIL). Mapping the wrapper directly would report
 * [constraint=null] about a violation the server named.
 */
private fun rootSqlException(e: SQLException): SQLException {
  var current: SQLException = e
  while (true) {
    val next = current.nextException ?: break
    // NOT redundant with the `?: break` above: a driver that chains an
    // exception to ITSELF ends no chain, and this walk has no bound but the
    // chain. Without this guard one such SQLException spins forever inside a
    // catch block -- a HUNG ingest, with no timeout watching, rather than a
    // failing test.
    if (next === current) break
    current = next
  }
  return current
}

/** Rows per JDBC batch execution; one number for every rebuild writer. */
private const val BATCH_ROWS = 500
