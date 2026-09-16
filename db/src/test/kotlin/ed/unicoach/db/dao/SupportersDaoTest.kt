package ed.unicoach.db.dao

import ed.unicoach.db.models.NewSupporter
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.db.models.Supporter
import ed.unicoach.db.models.SupporterId
import ed.unicoach.db.models.UserId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.SQLException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Real-DB coverage for `supporters` (RFC 221), modelled on
 * [CostReportSharesDaoTest]: one connection opened in @BeforeAll, a truncate
 * before each test, and raw statements for the fixture rows and for the guards
 * no DAO method can reach.
 */
class SupportersDaoTest {
  companion object {
    private lateinit var connection: Connection

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
      connection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::connection.isInitialized && !connection.isClosed) connection.close()
    }
  }

  @BeforeEach
  fun resetDatabase() {
    connection.createStatement().use { stmt ->
      stmt.execute("TRUNCATE TABLE supporter_student_links, supporters, students, users CASCADE")
    }
  }

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  private fun createUser(): UserId {
    val userId = UUID.randomUUID()
    connection
      .prepareStatement("INSERT INTO users (id, email, name, password_hash) VALUES (?, ?, 'Supporter User', 'ahash')")
      .use { stmt ->
        stmt.setObject(1, userId)
        stmt.setString(2, "sup-$userId@test.com")
        stmt.executeUpdate()
      }
    return UserId(userId)
  }

  /**
   * Closing a supporter goes through the DAO, never through a hand-written
   * UPDATE: [SupportersDao.delete] is the sanctioned writer of this table's
   * soft-delete state, and a test that reached around it would be the thing the
   * next caller copies.
   */
  private fun delete(supporter: Supporter): Supporter = SupportersDao.delete(session, supporter.id, supporter.version).getOrThrow()

  @Test
  fun `create inserts a supporter the user can be found by`() {
    val user = createUser()

    val supporter = SupportersDao.create(session, NewSupporter(user)).getOrThrow()

    assertEquals(user, supporter.userId)
    assertEquals(1, supporter.version, "a freshly inserted row is version 1")
    assertNull(supporter.deletedAt, "a freshly created supporter is live")

    val found = assertNotNull(SupportersDao.findByUserId(session, user).getOrThrow())
    assertEquals(supporter.id, found.id)
    assertEquals(supporter.id, SupportersDao.findById(session, supporter.id).getOrThrow().id)
  }

  @Test
  fun `findByUserId is null for a user who is not a supporter`() {
    val user = createUser()

    assertNull(
      SupportersDao.findByUserId(session, user).getOrThrow(),
      "a user who never signed up as a supporter is an absence, not a failed read",
    )
  }

  /**
   * `supporters_user_id_unique_idx` is TOTAL, and the soft-deleted half is the
   * assertion that matters: it is exactly what a PARTIAL index would allow, and
   * it is the difference from `supporter_student_links`, whose active index is
   * partial on purpose. Account deletion cascades the user, so there is no
   * legitimate path that re-creates a supporter for the same one.
   */
  @Test
  fun `a second supporter for the same user is refused, soft-deleted first row included`() {
    val user = createUser()
    val first = SupportersDao.create(session, NewSupporter(user)).getOrThrow()

    val second = SupportersDao.create(session, NewSupporter(user))
    assertIs<SupporterAlreadyExistsException>(
      second.exceptionOrNull(),
      "one supporter per user, named rather than an index string, got [$second]",
    )

    delete(first)

    val third = SupportersDao.create(session, NewSupporter(user))
    val refusal =
      assertIs<SupporterAlreadyExistsException>(
        third.exceptionOrNull(),
        "the index is TOTAL: a soft delete does not free the user_id, got [$third]",
      )
    assertTrue(
      refusal.message?.contains(user.asString) == true,
      "the refusal must name the user it is already a supporter for, got [${refusal.message}]",
    )
  }

  @Test
  fun `user_id is immutable`() {
    val user = createUser()
    val other = createUser()
    val supporter = SupportersDao.create(session, NewSupporter(user)).getOrThrow()

    val failure =
      assertFailsWith<SQLException> {
        connection
          .prepareStatement("UPDATE supporters SET user_id = ?, version = version + 1 WHERE id = ?")
          .use { stmt ->
            stmt.setObject(1, other.value)
            stmt.setObject(2, supporter.id.value)
            stmt.executeUpdate()
          }
      }

    assertEquals("P0001", failure.sqlState, "re-pointing a supporter at another user must be refused")
    assertEquals(
      user,
      SupportersDao.findById(session, supporter.id).getOrThrow().userId,
      "the refused update must change nothing",
    )
  }

  @Test
  fun `an update that does not bump version is refused`() {
    val user = createUser()
    val supporter = SupportersDao.create(session, NewSupporter(user)).getOrThrow()

    val failure =
      assertFailsWith<SQLException> {
        connection.prepareStatement("UPDATE supporters SET deleted_at = NOW() WHERE id = ?").use { stmt ->
          stmt.setObject(1, supporter.id.value)
          stmt.executeUpdate()
        }
      }

    assertEquals("40001", failure.sqlState, "enforce_versioning refuses an UPDATE that does not advance version")
    assertNull(
      SupportersDao.findById(session, supporter.id).getOrThrow().deletedAt,
      "the refused update must change nothing",
    )
  }

  @Test
  fun `a physical delete is refused`() {
    val user = createUser()
    val supporter = SupportersDao.create(session, NewSupporter(user)).getOrThrow()

    val failure =
      assertFailsWith<SQLException> {
        connection.prepareStatement("DELETE FROM supporters WHERE id = ?").use { stmt ->
          stmt.setObject(1, supporter.id.value)
          stmt.executeUpdate()
        }
      }

    assertEquals("P0001", failure.sqlState, "supporters are soft-deleted, never removed")
    assertEquals(
      supporter.id,
      SupportersDao.findById(session, supporter.id).getOrThrow().id,
      "the row must still be there",
    )
  }

  /**
   * The history records the REMOVAL itself, not merely the last state before it:
   * the `AFTER UPDATE` snapshot carries `deleted_at`, so a supporter's closure is
   * in `supporters_versions` and not only inferable from its absence.
   */
  @Test
  fun `a soft delete writes a supporters_versions row carrying deleted_at`() {
    val user = createUser()
    val supporter = SupportersDao.create(session, NewSupporter(user)).getOrThrow()

    delete(supporter)

    val history = mutableListOf<Pair<Int, Boolean>>()
    connection
      .prepareStatement("SELECT version, deleted_at FROM supporters_versions WHERE id = ? ORDER BY version")
      .use { stmt ->
        stmt.setObject(1, supporter.id.value)
        stmt.executeQuery().use { rs ->
          while (rs.next()) history.add(rs.getInt("version") to (rs.getTimestamp("deleted_at") != null))
        }
      }

    assertEquals(listOf(1 to false, 2 to true), history, "the insert and the soft delete are both snapshotted")
  }

  /**
   * The scope is the house primitive, not a method name: a closed supporter is
   * out of every live read, and a caller that wants it anyway says so with
   * [SoftDeleteScope.ALL].
   */
  @Test
  fun `a closed supporter is found only when the scope asks for it`() {
    val user = createUser()
    val supporter = SupportersDao.create(session, NewSupporter(user)).getOrThrow()

    delete(supporter)

    assertNull(SupportersDao.findByUserId(session, user).getOrThrow(), "a closed supporter is not active")
    val excluded =
      assertIs<NotFoundException>(
        SupportersDao.findById(session, supporter.id).exceptionOrNull(),
        "the default scope is ACTIVE, so a closed row is not there",
      )
    assertEquals(
      "No supporter [${supporter.id.asString}] in scope [ACTIVE]",
      excluded.message,
      "a row the scope excluded and an id that was never a supporter must not read the same",
    )
    assertNotNull(
      SupportersDao.findById(session, supporter.id, SoftDeleteScope.ALL).getOrThrow().deletedAt,
      "asked for explicitly, the closed row resolves and carries its deleted_at",
    )
    assertEquals(
      supporter.id,
      assertNotNull(SupportersDao.findByUserId(session, user, SoftDeleteScope.ALL).getOrThrow()).id,
      "the user_id lookup takes the same scope",
    )
  }

  /**
   * Closing is compare-and-swap, and the two ways it can fail are TOLD APART,
   * not collapsed into one null. A row that is there and did not take the write
   * — already closed, or at another version — is a lost race
   * ([ConcurrentModificationException]); an id no supporter has is a wrong id
   * ([NotFoundException]), which on the authorisation spine is a different
   * mistake with a different fix.
   */
  @Test
  fun `a second delete and a stale version both lose the race`() {
    val user = createUser()
    val supporter = SupportersDao.create(session, NewSupporter(user)).getOrThrow()
    val closed = delete(supporter)
    val stampedAt = assertNotNull(closed.deletedAt)

    assertIs<ConcurrentModificationException>(
      SupportersDao.delete(session, closed.id, closed.version).exceptionOrNull(),
      "an already-closed row is a race somebody else won, not an absent row",
    )
    assertIs<ConcurrentModificationException>(
      SupportersDao.delete(session, supporter.id, supporter.version).exceptionOrNull(),
      "a stale version cannot close it either",
    )
    assertEquals(
      stampedAt,
      SupportersDao.findById(session, supporter.id, SoftDeleteScope.ALL).getOrThrow().deletedAt,
      "the first delete's timestamp stands: the moment the supporter left is never re-stamped",
    )
  }

  /** The other half of the pair above: no such supporter is NOT a lost race. */
  @Test
  fun `closing an id no supporter has is not found`() {
    assertIs<NotFoundException>(
      SupportersDao.delete(session, SupporterId(UUID.randomUUID()), 1).exceptionOrNull(),
      "a wrong id must not look like a lost optimistic-concurrency race",
    )
  }

  /**
   * The `users` foreign key is mapped by NAME in [SupportersDao], and a name
   * Postgres does not generate is a branch that can never run. Forcing the
   * violation is what proves the literal matches the constraint.
   */
  @Test
  fun `a supporter for a user that is not there reports the named foreign key`() {
    val absent = UserId(UUID.randomUUID())

    val result = SupportersDao.create(session, NewSupporter(absent))

    val failure = assertIs<NotFoundException>(result.exceptionOrNull(), "Expected NotFoundException")
    assertEquals(
      "No user [${absent.asString}] to create a supporter for",
      failure.message,
      "the mapped message names the id that was not there, not a constant sentence",
    )
    assertEquals(
      "supporters_user_id_fkey",
      failure.constraint,
      "the constraint the DAO names must be the one Postgres raised",
    )
  }

  /**
   * `supporters_user_id_unique_idx` is TOTAL, and the catalogue says so: the
   * behavioural test above proves it refuses a second row, this one states WHY,
   * so a later "consistency" change to a partial index fails loudly. The link
   * table's active-pair index is partial on purpose, and that half is asserted
   * in [SupporterStudentLinksDaoTest].
   */
  @Test
  fun `the supporter user index is total`() {
    assertFalse(
      IndexCatalogueFixture.isPartial(connection, "supporters_user_id_unique_idx"),
      "the supporter user index must be TOTAL: there is no legitimate re-creation path",
    )
  }
}
