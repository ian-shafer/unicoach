package ed.unicoach.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import ed.unicoach.db.dao.SqlSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class Database(
  config: DatabaseConfig,
  private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
  private val dataSource: HikariDataSource

  init {
    val hc =
      HikariConfig().apply {
        jdbcUrl = config.jdbcUrl
        username = config.user
        password = config.password
        maximumPoolSize = config.maximumPoolSize
        connectionTimeout = config.connectionTimeout
        // Prevent Hikari from holding autocommit=true connections natively in the pool if possible,
        // though we manually enforce it in withConnection.
      }
    dataSource = HikariDataSource(hc)
  }

  suspend fun <T> withConnection(block: (SqlSession) -> T): T =
    withContext(dispatcher) {
      val conn = dataSource.connection
      val originalAutoCommit = conn.autoCommit
      try {
        conn.autoCommit = false
        val session =
          object : SqlSession {
            override fun prepareStatement(sql: String) = conn.prepareStatement(sql)
          }
        val result = block(session)
        conn.commit()
        result
      } catch (e: Exception) {
        conn.rollback()
        throw e
      } finally {
        conn.autoCommit = originalAutoCommit
        conn.close()
      }
    }

  /**
   * [withConnection] as a [Result]: anything the block or the connection
   * throws is returned as a failure carrying that exception unaltered, and
   * cancellation still propagates -- it is the caller unwinding, not a read
   * that failed, and folding it into a failure would let a cancelled coroutine
   * carry on as if it had merely seen an error.
   *
   * A block that reads through a `Result`-returning DAO unwraps it with
   * `getOrThrow()`, so the DAO's own failure arrives here as the same failure.
   */
  suspend fun <T> withConnectionCatching(block: (SqlSession) -> T): Result<T> =
    try {
      Result.success(withConnection(block))
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      Result.failure(e)
    }

  fun createRawConnection(): java.sql.Connection =
    java.sql.DriverManager.getConnection(
      dataSource.jdbcUrl,
      dataSource.username,
      dataSource.password,
    )

  fun close() {
    dataSource.close()
  }
}
