package ed.unicoach.db.models

/**
 * The insert input for a `supporters` row (RFC 221). The id is left to the
 * column's `uuidv7()` default: nothing is derived from it, so no caller needs it
 * before the row exists.
 */
data class NewSupporter(
  val userId: UserId,
)
