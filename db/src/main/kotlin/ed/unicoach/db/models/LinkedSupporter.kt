package ed.unicoach.db.models

/**
 * One ACTIVE link as the STUDENT side sees it (RFC 249): which link, which
 * student it points at, and the display name of the adult on the other end.
 *
 * The mirror of [LinkedStudent], and closed for the same reason: the only
 * supporter column reachable through it is `users.name`. It cannot carry the
 * adult's email, because the read that builds it selects none.
 */
data class LinkedSupporter(
  val linkId: SupporterStudentLinkId,
  val studentId: StudentId,
  val name: PersonName,
)
