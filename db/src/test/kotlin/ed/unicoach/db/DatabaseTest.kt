package ed.unicoach.db

import ed.unicoach.common.config.AppConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * [Database.withConnectionCatching] over a real pool: a value comes back as a
 * success, a fault comes back as a failure carrying the exception thrown,
 * and cancellation is never folded into a failure.
 */
class DatabaseTest {
  companion object {
    private lateinit var database: Database

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config = AppConfig.load("common.conf", "db.conf").getOrThrow()
      database = Database(DatabaseConfig.from(config).getOrThrow())
    }

    // Every checkout shares the cluster's connection budget: the pool is closed,
    // never leaked.
    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::database.isInitialized) database.close()
    }
  }

  @Test
  fun `a block's value comes back as a success`() {
    val result =
      runBlocking {
        database.withConnectionCatching { session ->
          session.prepareStatement("SELECT 41 + 1").use { stmt ->
            stmt.executeQuery().use { rs ->
              rs.next()
              rs.getInt(1)
            }
          }
        }
      }

    assertEquals(Result.success(42), result)
  }

  @Test
  fun `a fault comes back as a failure carrying the exception thrown, unaltered`() {
    val fault = IllegalStateException("the read failed")

    val result = runBlocking { database.withConnectionCatching<Int> { throw fault } }

    assertFaultIs(fault, result)
  }

  @Test
  fun `a DAO failure unwrapped in the block comes back as the same failure`() {
    val fault = java.sql.SQLException("the DAO failed")

    val result = runBlocking { database.withConnectionCatching { Result.failure<Int>(fault).getOrThrow() } }

    assertFaultIs(fault, result)
  }

  @Test
  fun `cancellation propagates rather than becoming a failure`() {
    val cancellation = CancellationException("the caller is unwinding")

    val thrown =
      assertFailsWith<CancellationException> {
        runBlocking { database.withConnectionCatching<Int> { throw cancellation } }
      }

    assertEquals(cancellation.message, thrown.message)
  }

  /**
   * The failure is [fault]'s own type and message. Not identity: in debug mode
   * kotlinx.coroutines may hand back a stack-trace-recovered copy of an
   * exception that crossed `withContext`, carrying the original as its cause.
   */
  private fun assertFaultIs(
    fault: Exception,
    result: Result<Int>,
  ) {
    val failure = result.exceptionOrNull()
    assertEquals(fault::class, failure?.let { it::class }, "$result")
    assertEquals(fault.message, failure?.message)
  }
}
