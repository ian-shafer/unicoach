package ed.unicoach.db.dao

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import kotlin.test.assertTrue

class UsersDaoTest {

    companion object {
        private val container = PostgreSQLContainer<Nothing>("postgres:16.2-alpine").apply {
            withDatabaseName("testdb")
            withUsername("testuser")
            withPassword("testpass")
        }

        private lateinit var connection: Connection

        @JvmStatic
        @BeforeAll
        fun setupAll() {
            container.start()
            connection = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
            
            // Run schema files
            val schemaDir = File("../db/schema").absoluteFile
            if (!schemaDir.exists()) error("Schema dir not found: ${schemaDir.path}")
            
            val files = schemaDir.listFiles { _, name -> name.endsWith(".sql") }?.sortedBy { it.name }
                ?: error("No schema files found")
                
            files.forEach { file ->
                val sql = file.readText()
                connection.createStatement().use { stmt ->
                    stmt.execute(sql)
                }
            }
        }

        @JvmStatic
        @AfterAll
        fun teardownAll() {
            if (::connection.isInitialized) {
                connection.close()
            }
            container.stop()
        }
    }

    // A real implementation of SqlSession for tests
    private val session = object : SqlSession {
        override fun prepareStatement(sql: String): PreparedStatement {
            return connection.prepareStatement(sql)
        }
    }

    @Test
    fun `test framework is ready and schema initialized`() {
        connection.createStatement().use { stmt ->
            val rs = stmt.executeQuery("SELECT count(*) FROM users")
            assertTrue(rs.next())
            val count = rs.getInt(1)
            println("Verified users table exists, count = $count")
        }
    }
}
