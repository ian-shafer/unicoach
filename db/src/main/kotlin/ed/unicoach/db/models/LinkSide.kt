package ed.unicoach.db.models

/**
 * WHICH end of a `supporter_student_links` row a caller stands on, with the
 * caller's own id (RFC 249).
 *
 * A side read filters on it, and [ed.unicoach.db.dao.SupporterStudentLinksDao.unlink]
 * is AUTHORISED by it: the unlink names the column and binds this id in the
 * same WHERE that severs the row, so a link the caller does not hold matches
 * nothing. Only a typed id can construct one, so a supporter's id cannot be
 * bound to `student_id` — the two used to share one erased `UUID` parameter.
 */
sealed interface LinkSide {
  data class Student(
    val id: StudentId,
  ) : LinkSide

  data class Supporter(
    val id: SupporterId,
  ) : LinkSide
}
