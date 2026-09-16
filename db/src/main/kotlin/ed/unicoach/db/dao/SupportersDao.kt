package ed.unicoach.db.dao

import ed.unicoach.db.models.NewSupporter
import ed.unicoach.db.models.SoftDeleteScope
import ed.unicoach.db.models.Supporter
import ed.unicoach.db.models.SupporterId
import ed.unicoach.db.models.UserId
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

/**
 * Store for `supporters` (RFC 221) — the adult side of a family relationship as
 * its own entity, one row per user.
 *
 * The [CostReportSharesDao] shape: a stateless `object` over [SqlSession], every
 * method returning [Result], and plain JDBC. Transaction boundaries belong to
 * the caller's `Database.withConnection`.
 *
 * It is not API surface. Nothing above `:db` reaches these rows in this slice;
 * `family/02/invite-and-accept` is the first caller, and this DAO is the spine's
 * only sanctioned writer.
 */
object SupportersDao :
  Creatable<NewSupporter, Supporter>,
  SoftDeleteFindable<Supporter, SupporterId> {
  private fun mapSupporter(rs: ResultSet): Supporter =
    Supporter(
      id = SupporterId(UUID.fromString(rs.getString("id"))),
      userId = UserId(UUID.fromString(rs.getString("user_id"))),
      version = rs.getInt("version"),
      createdAt = rs.getInstant("created_at"),
      updatedAt = rs.getInstant("updated_at"),
      deletedAt = rs.getInstantOrNull("deleted_at"),
    )

  /**
   * Creates the supporter row for a user. The id comes from the column's
   * `uuidv7()` default: nothing is derived from it, so no caller needs it first.
   *
   * `supporters_user_id_unique_idx` is TOTAL, so a second row for the same user
   * is refused even when the first was soft-deleted — there is no legitimate
   * re-creation path, because account deletion cascades the user. That refusal
   * is [SupporterAlreadyExistsException], not a constraint name the caller has
   * to recognise.
   */
  override fun create(
    session: SqlSession,
    input: NewSupporter,
  ): Result<Supporter> =
    session.insertReturning(
      table = "supporters",
      columns = linkedMapOf<String, Bind>("user_id" to { stmt, i -> stmt.setObject(i, input.userId.value) }),
      map = ::mapSupporter,
      mapError = mapCreateError(input.userId),
    )

  /**
   * Reads one supporter by id within [scope], [NotFoundException] when no row in
   * that scope has it.
   *
   * A closed supporter is still readable — a caller holding an id is asking
   * about a specific row it already knows of — but it has to ASK, with
   * [SoftDeleteScope.ALL]. The scope is the house primitive rather than a second
   * method name, so "which rows does this read see" is one word at the call
   * site.
   *
   * The scope rides in the WHERE clause as [SoftDeleteScope.predicate], which is
   * the one place that knows what each scope MEANS. Filtered in Kotlin after the
   * read it was a second copy of those three rules, and the copy could drift.
   *
   * The refusal names the id AND the scope, because "not found" is two different
   * situations here — no such supporter, and a supporter this read was not
   * allowed to see — and a caller that cannot tell them apart re-queries with
   * the same scope.
   */
  override fun findById(
    session: SqlSession,
    id: SupporterId,
    scope: SoftDeleteScope,
  ): Result<Supporter> =
    session.queryOne(
      "SELECT * FROM supporters WHERE id = ? AND ${scope.predicate()}",
      bind = { it.setObject(1, id.value) },
      map = ::mapSupporter,
      onNoRow = { NotFoundException("No supporter [${id.asString}] in scope [$scope]") },
    )

  /**
   * The one indexed lookup that answers "is this user a supporter?" — null when
   * they are not, within [scope] and live-only by default.
   *
   * Null rather than [NotFoundException]: every user who has not signed up as a
   * supporter is in this state, which is an ordinary absence and not a failed
   * read. The scope rides in the WHERE for the reason [findById] gives.
   */
  fun findByUserId(
    session: SqlSession,
    userId: UserId,
    scope: SoftDeleteScope = SoftDeleteScope.ACTIVE,
  ): Result<Supporter?> =
    session
      .queryOne(
        "SELECT * FROM supporters WHERE user_id = ? AND ${scope.predicate()}",
        bind = { it.setObject(1, userId.value) },
        map = ::mapSupporter,
      ).orNullOnNotFound()

  /**
   * Compare-and-swap delete: stamps `deleted_at` on a LIVE supporter at
   * [currentVersion] and returns the closed row.
   *
   * `delete` is the house verb for this exact write (`Dao.kt`'s `OccDeletable`,
   * [StudentsDao.delete]); the link table keeps the domain verb
   * [SupporterStudentLinksDao.unlink] because "unlink" is the product's own word
   * for severing a relationship, while a supporter row is just deleted.
   *
   * The table ships a soft-delete state, so it ships the one sanctioned way to
   * reach it. Without this the first caller that needs to delete a supporter
   * writes its own UPDATE, and an UPDATE that forgets `version = version + 1` is
   * refused by `enforce_versioning` with SQLSTATE `40001` — a fact each new
   * writer would have to rediscover.
   *
   * The failures are TWO, told apart by [occUpdate] and not collapsed into one
   * null: [NotFoundException] when no supporter has that id at all — a wrong id,
   * on the authorisation spine — and [ConcurrentModificationException] when the
   * row is there but this write did not apply. The second covers both a stale
   * version and an already-closed row, and they are the same event: closing
   * bumps the version, so whoever closed it first made this caller's version
   * stale. `deleted_at IS NULL` stays in the WHERE so the moment the supporter
   * left is never re-stamped by a second delete.
   *
   * There is no re-opening: `supporters_user_id_unique_idx` is TOTAL, so a
   * closed supporter holds its `user_id` forever and account deletion is what
   * removes it.
   *
   * **Closing a supporter does NOT sever its links.** `supporter_student_links`
   * rows pointing at this supporter stay exactly as they are, and the link
   * table's liveness guard fires on INSERT only, so each of them can still be
   * unlinked — tidying them away remains possible, it is simply not automatic.
   * What SHOULD happen to a closed supporter's links is an account-deletion
   * question (`family/07`), and that slice owns it; a cascade invented here
   * would decide it silently.
   */
  fun delete(
    session: SqlSession,
    id: SupporterId,
    currentVersion: Int,
  ): Result<Supporter> =
    session.occUpdate(
      table = "supporters",
      sql =
        """
        UPDATE supporters
        SET deleted_at = NOW(), version = version + 1
        WHERE id = ? AND version = ? AND deleted_at IS NULL
        RETURNING *
        """.trimIndent(),
      bind = { stmt ->
        stmt.setObject(1, id.value)
        stmt.setInt(2, currentVersion)
      },
      idValue = id.value,
      map = ::mapSupporter,
    )

  /**
   * The write-path mapping: the absent `users` row NAMED — with the id that was
   * not there, because a message that drops it starts a table scan — and the
   * one-supporter-per-user index as [SupporterAlreadyExistsException].
   *
   * It takes the input rather than being a bare function reference for exactly
   * that: a mapper that cannot see what was written can only emit a constant
   * sentence. [delete] needs none of it — neither the foreign key nor the unique
   * index can fire on an UPDATE that changes only `deleted_at` — and so keeps
   * [occUpdate]'s default mapping.
   */
  private fun mapCreateError(userId: UserId): (SQLException) -> Exception =
    { e ->
      val serverError = (e as? org.postgresql.util.PSQLException)?.serverErrorMessage
      if (e.sqlState == SQLSTATE_UNIQUE_VIOLATION && serverError?.constraint == "supporters_user_id_unique_idx") {
        SupporterAlreadyExistsException(
          "User [${userId.asString}] already has a supporter row; the index is TOTAL, so a closed " +
            "supporter still holds its user_id",
        )
      } else {
        mapChildWriteError(
          e,
          mapOf(
            "supporters_user_id_fkey" to
              "No user [${userId.asString}] to create a supporter for",
          ),
        )
      }
    }
}
