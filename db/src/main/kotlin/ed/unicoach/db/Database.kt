package ed.unicoach.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import ed.unicoach.db.dao.SqlSession
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
