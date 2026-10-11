package ed.unicoach.db.dao

import ed.unicoach.db.models.SharingPermission
import ed.unicoach.db.models.SharingPermissionStatus
import ed.unicoach.db.models.StudentId
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.postgresql.util.PSQLException
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `sharing_permissions` (RFC 248) against real Postgres: the CHECKs and the
 * partial unique index by name, the version history, the delete, column and
 * versioning guards, and the [SharingPermissionsDao] write path over them.
 */
class SharingPermissionsDaoTest {
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

  private val session =
    object : SqlSession {
      override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
    }

  private val intendedSubjects = SharingPermission.INTENDED_SUBJECTS

  @BeforeEach
  fun resetDatabase() {
    connection.autoCommit = true
    exec("TRUNCATE TABLE sharing_permissions_versions, sharing_permissions, students, users CASCADE")
  }

  private fun createStudent(): StudentId {
    val userId = UUID.randomUUID()
    val studentId = UUID.randomUUID()
    exec("INSERT INTO users (id, email, name, password_hash) VALUES ('$userId', 'sp-$userId@test.com', 'SP User', 'ahash')")
    StudentRowFixture.insert(connection, studentId, userId)
    return StudentId(studentId)
  }

  private fun exec(sql: String) = connection.createStatement().use { it.execute(sql) }

  private fun scalar(sql: String): Int =
    connection.createStatement().use { stmt ->
      stmt.executeQuery(sql).use { rs ->
        rs.next()
        rs.getInt(1)
      }
    }

  private fun set(
    student: StudentId,
    status: SharingPermissionStatus,
  ) = SharingPermissionsDao.set(session, student, intendedSubjects, status).getOrThrow()

  private fun historyCount(student: StudentId): Int =
    scalar("SELECT count(*) FROM sharing_permissions_versions WHERE student_id = '${student.value}'")

  /** Runs [block] with [constraint] dropped, inside a transaction that is always rolled back. */
  private fun <T> withCheckDropped(
    constraint: String,
    block: () -> T,
  ): T {
    connection.autoCommit = false
    return try {
      exec("ALTER TABLE sharing_permissions DROP CONSTRAINT $constraint")
      block()
    } finally {
      connection.rollback()
      connection.autoCommit = true
    }
  }

  /** The definition Postgres renders for the named CHECK on `sharing_permissions`. */
  private fun constraintDefinition(name: String): String =
    connection
      .prepareStatement(
        """
        SELECT pg_get_constraintdef(oid) AS def
        FROM pg_constraint
        WHERE conrelid = 'sharing_permissions'::regclass AND conname = ?
        """.trimIndent(),
      ).use { stmt ->
        stmt.setString(1, name)
        stmt.executeQuery().use { rs ->
          assertTrue(rs.next(), "the named CHECK [$name] must exist")
          rs.getString("def")
        }
      }

  /**
   * `col IN ('a', 'b')` in Postgres's own spelling, which is all
   * pg_get_constraintdef returns: `= ANY (ARRAY['a'::text, ...])`, and a
   * one-word list folded to a plain `=`.
   */
  private fun renderedInCheck(
    column: String,
    words: List<String>,
  ): String =
    words.singleOrNull()?.let { "CHECK (($column = '$it'::text))" }
      ?: words.joinToString(", ", prefix = "CHECK (($column = ANY (ARRAY[", postfix = "])))") { "'$it'::text" }

  /** The violated constraint's name, read from the server's structured error rather than its message text. */
  private fun constraintOf(e: PSQLException): String? = e.serverErrorMessage?.constraint

  /** The refused column's name, read from the server's structured error rather than its message text. */
  private fun columnOf(e: PSQLException): String? = e.serverErrorMessage?.column

  @Test
  fun `a student nobody has asked has no row`() {
    val student = createStudent()
    assertNull(SharingPermissionsDao.findLive(session, student, intendedSubjects).getOrThrow())
    assertTrue(SharingPermissionsDao.listLiveForStudent(session, student).getOrThrow().isEmpty())
  }

  @Test
  fun `a permission is reversible both ways and every change is a version`() {
    val student = createStudent()

    val granted = set(student, SharingPermissionStatus.GRANTED)
    assertEquals(SharingPermissionStatus.GRANTED, granted.status)
    assertEquals(1, granted.version)
    assertEquals(1, historyCount(student))

    val refused = set(student, SharingPermissionStatus.REFUSED)
    assertEquals(granted.id, refused.id, "a change of mind updates the one row, never a second row")
    assertEquals(SharingPermissionStatus.REFUSED, refused.status)
    assertEquals(2, refused.version)
    assertEquals(2, historyCount(student))

    val regranted = set(student, SharingPermissionStatus.GRANTED)
    assertEquals(3, regranted.version)
    assertEquals(
      listOf(
        SharingPermissionStatus.GRANTED,
        SharingPermissionStatus.REFUSED,
        SharingPermissionStatus.GRANTED,
      ),
      SharingPermissionsDao.listVersions(session, granted.id).getOrThrow().map { it.entity.status },
    )
    assertEquals(listOf(regranted), SharingPermissionsDao.listLiveForStudent(session, student).getOrThrow())
  }

  @Test
  fun `writing the current status again writes nothing`() {
    val student = createStudent()
    set(student, SharingPermissionStatus.REFUSED)
    val again = set(student, SharingPermissionStatus.REFUSED)
    assertEquals(1, again.version, "an unchanged answer must not advance the version")
    assertEquals(1, historyCount(student), "an unchanged answer must not write history")

    set(student, SharingPermissionStatus.GRANTED)
    val repeated = set(student, SharingPermissionStatus.GRANTED)
    assertEquals(2, repeated.version)
    assertEquals(2, historyCount(student))
  }

  @Test
  fun `a write for a student that does not exist is a not-found`() {
    val error =
      SharingPermissionsDao
        .set(session, StudentId(UUID.randomUUID()), intendedSubjects, SharingPermissionStatus.GRANTED)
        .exceptionOrNull()
    assertTrue(error is NotFoundException, "got $error")
    assertEquals("sharing_permissions_student_id_fkey", error.constraint)
  }

  @Test
  fun `the database refuses an unknown permission and an unknown status by constraint name`() {
    val student = createStudent()
    val permission =
      assertFailsWith<PSQLException> {
        exec("INSERT INTO sharing_permissions (student_id, permission, status) VALUES ('${student.value}', 'college_list', 'granted')")
      }
    assertEquals("sharing_permissions_permission_check", constraintOf(permission))

    val status =
      assertFailsWith<PSQLException> {
        exec(
          "INSERT INTO sharing_permissions (student_id, permission, status) " +
            "VALUES ('${student.value}', '${intendedSubjects.value}', 'withheld')",
        )
      }
    assertEquals("sharing_permissions_status_check", constraintOf(status))
  }

  /**
   * Each CHECK and its enum are ONE vocabulary in two places. The rendered
   * definition is compared whole, built from the enum, so a word added on
   * EITHER side fails here rather than as a 500 on write or a corruption fault
   * on read, and the order is fixed too.
   */
  @Test
  fun `each CHECK holds exactly its enum's words`() {
    assertEquals(
      renderedInCheck("permission", SharingPermission.entries.map { it.value }),
      constraintDefinition("sharing_permissions_permission_check"),
    )
    assertEquals(
      renderedInCheck("status", SharingPermissionStatus.entries.map { it.value }),
      constraintDefinition("sharing_permissions_status_check"),
    )
  }

  @Test
  fun `a second live row for the same student and permission is refused by the partial unique index`() {
    val student = createStudent()
    set(student, SharingPermissionStatus.GRANTED)
    val duplicate =
      assertFailsWith<PSQLException> {
        exec(
          "INSERT INTO sharing_permissions (student_id, permission, status) " +
            "VALUES ('${student.value}', '${intendedSubjects.value}', 'refused')",
        )
      }
    assertEquals("sharing_permissions_student_permission_active_idx", constraintOf(duplicate))
  }

  @Test
  fun `a physical delete is refused`() {
    val student = createStudent()
    val row = set(student, SharingPermissionStatus.GRANTED)
    val thrown =
      assertFailsWith<PSQLException> { exec("DELETE FROM sharing_permissions WHERE id = '${row.id.value}'") }
    assertContains(thrown.message!!, "Physical deletions are strictly blocked")
  }

  @Test
  fun `student_id and permission are frozen, and status is not`() {
    val student = createStudent()
    val other = createStudent()
    val row = set(student, SharingPermissionStatus.GRANTED)

    // Both attempts advance `version` correctly, so the ONLY thing that can
    // refuse them is the column guard.
    val moved =
      assertFailsWith<PSQLException> {
        exec("UPDATE sharing_permissions SET student_id = '${other.value}', version = version + 1 WHERE id = '${row.id.value}'")
      }
    assertContains(moved.message!!, "student_id field is immutable")
    assertEquals("student_id", columnOf(moved))
    assertEquals(
      "sharing_permissions row [${row.id.value}] student_id [${student.value}] -> [${other.value}]",
      moved.serverErrorMessage?.detail,
    )

    // The attempt targets a word the CHECK does not admit, with the CHECK
    // dropped inside a rolled-back transaction: what refuses it must be the
    // column guard, not the vocabulary.
    val renamed =
      withCheckDropped("sharing_permissions_permission_check") {
        assertFailsWith<PSQLException> {
          exec("UPDATE sharing_permissions SET permission = 'college_list', version = version + 1 WHERE id = '${row.id.value}'")
        }
      }
    assertContains(renamed.message!!, "permission field is immutable")
    assertEquals("permission", columnOf(renamed))
    assertEquals(
      "sharing_permissions row [${row.id.value}] permission [${intendedSubjects.value}] -> [college_list]",
      renamed.serverErrorMessage?.detail,
    )

    set(student, SharingPermissionStatus.REFUSED)
    assertEquals(
      SharingPermissionStatus.REFUSED,
      SharingPermissionsDao.findLive(session, student, intendedSubjects).getOrThrow()?.status,
    )
  }

  @Test
  fun `an update that does not advance the version is refused with 40001`() {
    val student = createStudent()
    val row = set(student, SharingPermissionStatus.GRANTED)
    val thrown =
      assertFailsWith<PSQLException> { exec("UPDATE sharing_permissions SET status = 'refused' WHERE id = '${row.id.value}'") }
    assertEquals("40001", thrown.sqlState)
  }

  @Test
  fun `a corrupt stored status is refused, never read as not granted`() {
    val student = createStudent()
    val row = set(student, SharingPermissionStatus.GRANTED)

    // The CHECK makes this unreachable through the DAO, so the row is written
    // with the CHECK dropped inside a transaction that is rolled back.
    val error =
      withCheckDropped("sharing_permissions_status_check") {
        exec("UPDATE sharing_permissions SET status = 'maybe', version = version + 1 WHERE id = '${row.id.value}'")
        SharingPermissionsDao.findLive(session, student, intendedSubjects).exceptionOrNull()
      }

    assertTrue(error is CorruptPersistedValueException, "got $error")
    assertContains(error.message!!, "sharing_permissions.status")
    assertContains(error.message!!, row.id.value.toString())
  }

  @Test
  fun `a corrupt stored permission is refused, never skipped`() {
    val student = createStudent()

    // The CHECK makes this unreachable through the DAO and the column guard
    // refuses an UPDATE of `permission`, so the row is INSERTed with the CHECK
    // dropped inside a transaction that is rolled back. It is read through
    // `listLiveForStudent`: `findLive` filters on a known permission and would
    // never see it.
    val error =
      withCheckDropped("sharing_permissions_permission_check") {
        exec("INSERT INTO sharing_permissions (student_id, permission, status) VALUES ('${student.value}', 'college_list', 'granted')")
        SharingPermissionsDao.listLiveForStudent(session, student).exceptionOrNull()
      }

    assertTrue(error is CorruptPersistedValueException, "got $error")
    assertContains(error.message!!, "sharing_permissions.permission")
    assertContains(error.message!!, "college_list")
  }
}
