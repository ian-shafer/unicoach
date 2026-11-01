package ed.unicoach.db.dao

import java.sql.Connection
import java.util.UUID

/**
 * The one way a test erases an account in raw SQL (RFC 264, brief 0002 D1/D9):
 * the `deleted_*` rows, and a live key moved onto its deleted sibling.
 *
 * Nothing in production writes either yet; `deletion/03`'s walk is the first
 * writer. Until it lands, every test that needs an erased account writes the
 * shape by hand, and three suites had each written it out themselves. A change
 * to the deleted tables (a new NOT NULL, a renamed column) now costs this file.
 *
 * Every deleted row takes the SAME id as the live row it stands for (D9), so
 * each function answers the id it was given.
 */
object DeletedAccountRowFixture {
  /** Writes the account's `deleted_users` row. The `users` row must exist. */
  fun eraseAccount(
    connection: Connection,
    userId: UUID,
  ): UUID {
    RawSql.execute(connection, "INSERT INTO deleted_users (id, created_at, closed_at) VALUES (?, NOW(), NOW())", userId)
    return userId
  }

  /** Writes the student's `deleted_students` row, owned by an erased account. */
  fun eraseStudent(
    connection: Connection,
    studentId: UUID,
    deletedUserId: UUID,
  ): UUID {
    RawSql.execute(connection, "INSERT INTO deleted_students (id, user_id) VALUES (?, ?)", studentId, deletedUserId)
    return studentId
  }

  /** Writes the supporter's `deleted_supporters` row, owned by an erased account. */
  fun eraseSupporter(
    connection: Connection,
    supporterId: UUID,
    deletedUserId: UUID,
  ): UUID {
    RawSql.execute(connection, "INSERT INTO deleted_supporters (id, user_id) VALUES (?, ?)", supporterId, deletedUserId)
    return supporterId
  }

  /**
   * Moves a student's account key to its deleted sibling, as the walk will.
   * [eraseAccount] must already have run for [userId].
   */
  fun moveStudentToDeletedKey(
    connection: Connection,
    studentId: UUID,
    userId: UUID,
  ) = moveAccountKey(connection, "students", studentId, userId)

  /** Moves a subscription's account key to its deleted sibling, the same way. */
  fun moveSubscriptionToDeletedKey(
    connection: Connection,
    subscriptionId: UUID,
    userId: UUID,
  ) = moveAccountKey(connection, "subscriptions", subscriptionId, userId)

  /**
   * One statement, because the pair CHECK admits no row holding both keys or
   * neither. `version = version + 1` because both tables carry the
   * optimistic-concurrency trigger, which refuses an UPDATE that does not
   * advance it.
   */
  private fun moveAccountKey(
    connection: Connection,
    table: String,
    rowId: UUID,
    userId: UUID,
  ) = RawSql.execute(
    connection,
    "UPDATE $table SET user_id = NULL, deleted_user_id = ?, version = version + 1 WHERE id = ?",
    userId,
    rowId,
  )
}
