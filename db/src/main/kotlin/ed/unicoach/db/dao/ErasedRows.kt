package ed.unicoach.db.dao

/**
 * Read-time predicates that exclude erased rows (RFC 266 §8): fixed SQL
 * fragments, no caller data — every alias, table and column is a DAO
 * identifier.
 *
 * A row belongs to the account that owns it (decision D-A), and erasure moves
 * that owner's key onto its `deleted_*` sibling, leaving the live key NULL. So a
 * row is erased exactly when its owner key is NULL. An erased row holds no
 * personal value, so a DAO's mapper cannot map it; every read that could meet
 * one filters it here, in SQL, before the mapper runs.
 *
 * Each DAO names its own table's predicate beside its mapper, built from these.
 * `users` is the exception: it has no owner key, and [UsersDao.notErased] asks
 * `deleted_users` instead.
 */
internal object ErasedRows {
  /** The key column through which an [owner][notErased] holds a row. */
  enum class Owner(
    val column: String,
  ) {
    ACCOUNT("user_id"),
    STUDENT("student_id"),
    SUPPORTER("supporter_id"),
  }

  /** The row [alias] names is not erased: its [owner]'s key has not moved. */
  fun notErased(
    alias: String,
    owner: Owner,
  ): String = "$alias.${owner.column} IS NOT NULL"

  /**
   * The `_versions` row [historyAlias] names belongs to a [liveTable] row that is
   * not erased. History copies the key as it was then, so it is read through the
   * live row, whose key is what erasure moves.
   */
  fun historyNotErased(
    historyAlias: String,
    liveTable: String,
    owner: Owner,
  ): String = "EXISTS (SELECT 1 FROM $liveTable live WHERE live.id = $historyAlias.id AND ${notErased("live", owner)})"
}
