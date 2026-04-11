package ed.unicoach.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import ed.unicoach.db.dao.SqlSession

class Database(config: DatabaseConfig) {
    private val dataSource: HikariDataSource

    init {
        val hc = HikariConfig().apply {
            jdbcUrl = config.url
            username = config.user
            password = config.password
            maximumPoolSize = config.maximumPoolSize
            connectionTimeout = config.connectionTimeoutMs
            // Prevent Hikari from holding autocommit=true connections natively in the pool if possible,
            // though we manually enforce it in withConnection.
        }
        dataSource = HikariDataSource(hc)
    }

    fun <T> withConnection(block: (SqlSession) -> T): T {
        val conn = dataSource.connection
        val originalAutoCommit = conn.autoCommit
        return try {
            conn.autoCommit = false
            val result = block(SqlSession(conn))
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
    
    fun close() {
        dataSource.close()
    }
}
