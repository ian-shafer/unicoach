package ed.unicoach.db.dao

import org.junit.jupiter.api.Test
import java.sql.Statement
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The row count every wholesale-rebuild writer reports (RFC 162 tier-2 fix).
 *
 * `executeBatch()` answers an `int[]` whose members are row counts OR the two
 * JDBC sentinels, so the arithmetic over it is not a `sum()`: a driver that
 * answers `SUCCESS_NO_INFO` would make a chunk that wrote 500 rows report
 * -1000, and that number is stored as provenance
 * (`college_index_build.*_rows`) and printed as the run summary.
 */
class BatchWritesTest {
  @Test
  fun `a plain per-statement row count is the count`() {
    assertEquals(3, rowsWritten(intArrayOf(1, 1, 1)))
  }

  @Test
  fun `SUCCESS_NO_INFO counts the one row its statement bound, never minus two`() {
    // The batch binds exactly one row per statement, so a statement that
    // succeeded without saying how many rows it touched wrote one.
    assertEquals(3, rowsWritten(intArrayOf(1, Statement.SUCCESS_NO_INFO, 1)))
    assertEquals(2, rowsWritten(intArrayOf(Statement.SUCCESS_NO_INFO, Statement.SUCCESS_NO_INFO)))
  }

  @Test
  fun `EXECUTE_FAILED without a BatchUpdateException is refused, never counted`() {
    // The driver throws instead of returning this, so seeing it means the
    // batch contract broke -- and a silently short row count is the worst
    // available answer to that.
    assertFailsWith<IllegalStateException> { rowsWritten(intArrayOf(1, Statement.EXECUTE_FAILED)) }
  }

  @Test
  fun `an empty batch wrote nothing`() {
    assertEquals(0, rowsWritten(intArrayOf()))
  }
}
