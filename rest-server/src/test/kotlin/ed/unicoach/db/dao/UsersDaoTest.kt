package ed.unicoach.db.dao

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import ed.unicoach.db.models.ValidationResult
import ed.unicoach.db.models.EmailAddress
import ed.unicoach.db.models.PersonName
import ed.unicoach.db.models.PasswordHash
import ed.unicoach.db.models.AuthMethod
import ed.unicoach.db.models.NewUser
import ed.unicoach.db.models.UserId
import ed.unicoach.db.models.UserVersionId
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

    @Test
    fun `create routes duplicate emails directly into DuplicateEmail`() {
        val session1 = object : SqlSession {
            override fun prepareStatement(sql: String) = connection.prepareStatement(sql)
        }
        val emailProvider = ed.unicoach.db.models.EmailAddress.Companion
        val nameProvider = ed.unicoach.db.models.PersonName.Companion
        val passProvider = ed.unicoach.db.models.PasswordHash.Companion
        
        val newEmail = (emailProvider.create("dup@example.com") as ValidationResult.Valid).value
        val newName = (nameProvider.create("Dup Name") as ValidationResult.Valid).value
        val newPass = (passProvider.create("dupHash") as ValidationResult.Valid).value
        
        val newUser = ed.unicoach.db.models.NewUser(
            email = newEmail,
            name = newName,
            displayName = null,
            authMethod = ed.unicoach.db.models.AuthMethod.Password(newPass)
        )
        
        val createResult1 = UsersDao.create(session1, newUser)
        assertTrue(createResult1 is CreateResult.Success)
        
        val createResult2 = UsersDao.create(session1, newUser)
        assertTrue(createResult2 is CreateResult.DuplicateEmail, "Expected DuplicateEmail, got $createResult2")
    }

    @Test
    fun `update trips into ConcurrentModification with stale version`() {
        val session1 = object : SqlSession {
            override fun prepareStatement(sql: String) = connection.prepareStatement(sql)
        }
        val email = (ed.unicoach.db.models.EmailAddress.create("occ@example.com") as ValidationResult.Valid).value
        val name = (ed.unicoach.db.models.PersonName.create("OCC Name") as ValidationResult.Valid).value
        val pass = (ed.unicoach.db.models.PasswordHash.create("occHash") as ValidationResult.Valid).value
        
        val newUser = ed.unicoach.db.models.NewUser(
            email = email,
            name = name,
            displayName = null,
            authMethod = ed.unicoach.db.models.AuthMethod.Password(pass)
        )
        
        val createResult = UsersDao.create(session1, newUser)
        assertTrue(createResult is CreateResult.Success)
        val createdUser = createResult.user
        
        // Emulate an update from another process bounds
        val nextVersionUser = createdUser.copy(name = (ed.unicoach.db.models.PersonName.create("OCC Next") as ValidationResult.Valid).value)
        val validUpdateResult = UsersDao.update(session1, nextVersionUser)
        assertTrue(validUpdateResult is UpdateResult.Success)
        
        // Attempt update with original stale model
        val staleUpdateResult = UsersDao.update(session1, createdUser.copy(name = (ed.unicoach.db.models.PersonName.create("Stale Edit") as ValidationResult.Valid).value))
        assertTrue(staleUpdateResult is UpdateResult.ConcurrentModification, "Expected ConcurrentModification, got $staleUpdateResult")
    }
}
