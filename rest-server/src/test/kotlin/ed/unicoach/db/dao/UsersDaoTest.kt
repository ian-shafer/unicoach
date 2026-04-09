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
    fun `findByIdForUpdate throws LockAcquisitionFailure when locked by another connection`() {
        val rawId = java.util.UUID.randomUUID()
        connection.createStatement().use { stmt ->
            // Our schema requires auth method, so provide a password hash
            stmt.execute("INSERT INTO users (id, email, name, password_hash) VALUES ('$rawId', 'test-$rawId@test.com', 'Test User', 'ahash')")
        }

        // Connection 1 will lock the row
        connection.autoCommit = false
        val session1 = object : SqlSession {
            override fun prepareStatement(sql: String) = connection.prepareStatement(sql)
        }
        
        val result1 = UsersDao.findByIdForUpdate(session1, ed.unicoach.db.models.UserId(rawId))
        assertTrue(result1 is FindResult.Success)

        // Connection 2 attempts to lock the same row and should fail immediately with NOWAIT
        val conn2 = DriverManager.getConnection(container.jdbcUrl, container.username, container.password)
        conn2.autoCommit = false
        val session2 = object : SqlSession {
            override fun prepareStatement(sql: String) = conn2.prepareStatement(sql)
        }

        val result2 = UsersDao.findByIdForUpdate(session2, ed.unicoach.db.models.UserId(rawId))
        assertTrue(result2 is FindResult.LockAcquisitionFailure, "Expected LockAcquisitionFailure, got $result2")

        conn2.rollback()
        conn2.close()
        
        connection.rollback()
        connection.autoCommit = true
    }
    
    @Test
    fun `findById includeDeleted false correctly emits NotFound for softly deleted rows`() {
        val rawId = java.util.UUID.randomUUID()
        connection.createStatement().use { stmt ->
            stmt.execute("INSERT INTO users (id, email, name, password_hash, deleted_at) VALUES ('$rawId', 'test2-$rawId@test.com', 'Deleted', 'ahash', NOW())")
        }
        
        val session1 = object : SqlSession {
            override fun prepareStatement(sql: String) = connection.prepareStatement(sql)
        }
        
        val resultDeleted = UsersDao.findById(session1, ed.unicoach.db.models.UserId(rawId), includeDeleted = false)
        assertTrue(resultDeleted is FindResult.NotFound)

        val resultIncluded = UsersDao.findById(session1, ed.unicoach.db.models.UserId(rawId), includeDeleted = true)
        assertTrue(resultIncluded is FindResult.Success)
    }
}
