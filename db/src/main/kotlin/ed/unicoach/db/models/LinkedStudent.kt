package ed.unicoach.db.models

/**
 * One ACTIVE link as the SUPPORTER side sees it (RFC 241): which link, which
 * student it points at, the relationship word, and the student's display name.
 *
 * The projection is the point. A supporter's roster read could have returned
 * [Student] rows, and that would have handed every caller above `:db` the
 * student's `dateOfBirth` and expected graduation date — deciding
 * `family/05/the-subject-seam`'s read policy silently, through a `StudentsDao.findById`
 * lookup that authorises nothing. This type cannot carry
 * either: the only student column reachable through it is the display name, and
 * that comes from `users.name` ([User.name]) because `students` carries no name
 * at all. Widening what a supporter may read is therefore an edit to this file,
 * which is a decision somebody makes on purpose.
 *
 * [studentName] is the name the student's own account holds, not a nickname the
 * supporter chose: the same disclosure RFC 232's invite page already makes of
 * the counterparty (`SupporterInviteService.preview`), and a weaker one, because
 * here a link already exists.
 */
data class LinkedStudent(
  val linkId: SupporterStudentLinkId,
  val studentId: StudentId,
  val relationship: SupporterRelationship,
  val studentName: PersonName,
)
