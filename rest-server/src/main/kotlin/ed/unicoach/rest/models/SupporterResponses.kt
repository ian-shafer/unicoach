package ed.unicoach.rest.models

import ed.unicoach.db.models.LinkedSupporter
import ed.unicoach.family.CeilingStatement
import ed.unicoach.family.StudentSupporters
import ed.unicoach.family.SupportedStudent
import ed.unicoach.family.SupporterHome
import java.time.Instant
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
 * render, the relationship word, and the names of the other adults supporting
 * the same student (RFC 249) — never the caller's own.
 *
 * Nothing about the student beyond the name, and the reason is the whole
 * slice's boundary. It carries no date of birth and no expected graduation
 * date, because a supporter reading a student's profile is
 * `family/05/the-subject-seam` and nothing here authorises it. `LinkedStudent`
 * — the `:db` projection this maps from — cannot supply either, so the omission
 * is structural rather than a field somebody remembered to leave out.
 *
 * [linkId] rather than the supporter's own id, because the link is what either
 * end disconnects (RFC 249) and what a later read authorises against.
 */
data class PublicLinkedStudent(
  val linkId: UUID,
  val studentId: UUID,
  val name: String,
  val relationship: String,
  val otherSupporterNames: List<String>,
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
 *
 * [pendingRequests] counts this supporter's requests to connect still waiting
 * on a student (RFC 254), so a parent with nobody linked yet can be told why.
 */
data class SupporterHomeResponse(
  val supporter: PublicSupporter,
  val students: List<PublicLinkedStudent>,
  val pendingRequests: Int,
)

/**
 * The one mapping from the service's home to the wire, so the two doors cannot
 * publish the same facts differently.
 */
fun SupporterHome.toResponse(): SupporterHomeResponse =
  SupporterHomeResponse(
    supporter = PublicSupporter(id = supporter.id.value),
    students = students.map { it.toResponse() },
    pendingRequests = pendingRequests,
  )

private fun SupportedStudent.toResponse(): PublicLinkedStudent =
  PublicLinkedStudent(
    linkId = student.linkId.value,
    studentId = student.studentId.value,
    name = student.studentName.value,
    relationship = student.relationship.value,
    otherSupporterNames = otherSupporterNames.map { it.value },
  )

/**
 * One adult connected to the caller, as the STUDENT reads them (RFC 249): the
 * link to disconnect, and a name. No email and no relationship word — the
 * `:db` projection carries neither.
 */
data class PublicLinkedSupporter(
  val linkId: UUID,
  val name: String,
)

/** The ceiling in the student's voice, stated once above the list (RFC 249). */
data class SupporterCeilingResponse(
  val canSee: List<String>,
  val staysPrivate: List<String>,
)

/**
 * One pending request to connect, as the STUDENT reads it (RFC 254): the
 * request to approve or decline, and the claimant's name and email from their
 * own verified account. The email is here on purpose: the report link is not
 * proof of who forwarded it, so the student decides with the address in view.
 */
data class PublicSupporterRequest(
  val requestId: UUID,
  val name: String,
  val email: String,
  val requestedAt: Instant,
)

/**
 * `GET /api/v1/students/me/supporters` (RFC 249): the adults connected to the
 * student, what every one of them can see and not see, and whether the
 * student's Family Cost Report link is live — which a disconnect does not turn
 * off, so the client says so exactly when this is true. Since RFC 254 it also
 * carries the pending requests to connect, oldest first.
 */
data class StudentSupportersResponse(
  val supporters: List<PublicLinkedSupporter>,
  val requests: List<PublicSupporterRequest>,
  val ceiling: SupporterCeilingResponse,
  val costReportLinkLive: Boolean,
)

fun StudentSupporters.toResponse(): StudentSupportersResponse =
  StudentSupportersResponse(
    supporters = supporters.map { it.toResponse() },
    requests =
      requests.map {
        PublicSupporterRequest(
          requestId = it.requestId.value,
          name = it.name.value,
          email = it.email.value,
          requestedAt = it.requestedAt,
        )
      },
    ceiling = ceiling.toResponse(),
    costReportLinkLive = costReportLinkLive,
  )

/** One connected adult on the wire — the list's entry, and the approve door's answer (RFC 254). */
fun LinkedSupporter.toResponse(): PublicLinkedSupporter = PublicLinkedSupporter(linkId = linkId.value, name = name.value)

private fun CeilingStatement.toResponse(): SupporterCeilingResponse = SupporterCeilingResponse(canSee = canSee, staysPrivate = staysPrivate)
