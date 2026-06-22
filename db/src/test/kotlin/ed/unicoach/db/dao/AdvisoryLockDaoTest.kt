package ed.unicoach.db.dao

import ed.unicoach.db.models.StudentId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdvisoryLockDaoTest {
  companion object {
    private lateinit var jdbcUrl: String
    private lateinit var user: String
    private var password: String? = null

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf")
          .getOrThrow()
      val dbConfig =
        ed.unicoach.db.DatabaseConfig
          .from(config)
          .getOrThrow()
      jdbcUrl = dbConfig.jdbcUrl
      user = dbConfig.user
      password = dbConfig.password
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
    }
  }

  private fun newConnection(): Connection = DriverManager.getConnection(jdbcUrl, user, password ?: "")

  private fun sessionFor(conn: Connection): SqlSession =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = conn.prepareStatement(sql)
    }

  @Test
  fun `lockStudent returns success`() {
    val conn = newConnection()
    try {
      conn.autoCommit = false
      val result = AdvisoryLockDao.lockStudent(sessionFor(conn), StudentId(UUID.randomUUID()))
      assertTrue(result.isSuccess, "got $result")
      conn.commit()
    } finally {
      conn.close()
    }
  }

  @Test
  fun `same-student lock blocks until the holding transaction commits`() {
    val student = StudentId(UUID.randomUUID())
    val holder = newConnection()
    holder.autoCommit = false

    // Holder acquires and keeps the lock open (transaction-scoped).
    AdvisoryLockDao.lockStudent(sessionFor(holder), student).getOrThrow()

    val acquired = AtomicBoolean(false)
    val started = CountDownLatch(1)
    val waiter = newConnection()
    waiter.autoCommit = false
    val waiterThread =
      Thread {
        started.countDown()
        AdvisoryLockDao.lockStudent(sessionFor(waiter), student).getOrThrow()
        acquired.set(true)
        waiter.commit()
      }
    waiterThread.start()
    started.await()

    // The waiter must still be blocked while the holder keeps its transaction open.
    Thread.sleep(500)
    assertFalse(acquired.get(), "waiter acquired the same-student lock before holder committed")

    // Release: committing the holder lets the waiter proceed.
    holder.commit()
    waiterThread.join(TimeUnit.SECONDS.toMillis(5))
    assertTrue(acquired.get(), "waiter never acquired the lock after holder committed")

    holder.close()
    waiter.close()
  }

  @Test
  fun `different students do not contend`() {
    val holder = newConnection()
    holder.autoCommit = false
    AdvisoryLockDao.lockStudent(sessionFor(holder), StudentId(UUID.randomUUID())).getOrThrow()

    // A distinct student hashes to a distinct key, so this acquires immediately.
    val other = newConnection()
    other.autoCommit = false
    val acquired = AtomicBoolean(false)
    val t =
      Thread {
        AdvisoryLockDao.lockStudent(sessionFor(other), StudentId(UUID.randomUUID())).getOrThrow()
        acquired.set(true)
        other.commit()
      }
    t.start()
    t.join(TimeUnit.SECONDS.toMillis(5))
    assertTrue(acquired.get(), "a distinct student's lock should not contend with the holder")

    holder.commit()
    holder.close()
    other.close()
  }
}
