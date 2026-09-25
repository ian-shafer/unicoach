package ed.unicoach.rest.models

import ed.unicoach.db.models.LinkedStudent
import ed.unicoach.family.SupporterHome
import java.util.UUID

/**
 * The supporter identity `POST /api/v1/supporters` makes and
 * `GET /api/v1/supporters/me` returns (RFC 241).
 *
 * The id is a `UUID` and not a pre-formatted `String`, as in [PublicUser] and
 * [PublicStudent]: Jackson writes the same JSON text either way, so the wire is
 * unchanged and `format: uuid` still holds — the type is what the rest of the
 * process keeps.
 */
data class PublicSupporter(
  val id: UUID,
)

/**
 * One student on a supporter's roster: which link, which student, the name to
 * render, and the relationship word.
 *
 * FOUR fields, and the reason is the whole slice's boundary. It carries no date
 * of birth and no expected graduation date, because a supporter reading a
 * student's profile is `family/05/the-subject-seam` and nothing here authorises
 * it. `LinkedStudent` — the `:db` projection this maps from — cannot supply
 * either, so the omission is structural rather than a field somebody remembered
 * to leave out.
 *
 * [linkId] rather than the supporter's own id, because the link is what a later
 * slice unlinks and what a later read authorises against.
 */
data class PublicLinkedStudent(
  val linkId: UUID,
  val studentId: UUID,
  val name: String,
  val relationship: String,
)

/**
 * The supporter home (RFC 241): who the caller is on the supporter side, and
 * the students they hold an ACTIVE link to.
 *
 * ONE body for both doors. `POST /api/v1/supporters` answers it with `201` the
 * first time and `200` afterwards, and `GET /api/v1/supporters/me` answers it
 * with `200`, so a client that has just made an account holds exactly what a
 * client that re-read it holds and no second shape exists to keep in step.
 *
 * [students] is empty for a supporter who has linked nobody — the ordinary
 * first state of a parent-first signup, not an error.
 */
data class SupporterHomeResponse(
  val supporter: PublicSupporter,
  val students: List<PublicLinkedStudent>,
)

/**
 * The one mapping from the service's home to the wire, so the two doors cannot
 * publish the same facts differently.
 */
fun SupporterHome.toResponse(): SupporterHomeResponse =
  SupporterHomeResponse(
    supporter = PublicSupporter(id = supporter.id.value),
    students = students.map { it.toResponse() },
  )

private fun LinkedStudent.toResponse(): PublicLinkedStudent =
  PublicLinkedStudent(
    linkId = linkId.value,
    studentId = studentId.value,
    name = studentName.value,
    relationship = relationship.value,
  )
