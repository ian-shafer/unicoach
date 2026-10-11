package ed.unicoach.rest.models

import com.fasterxml.jackson.annotation.JsonValue
import ed.unicoach.db.models.SharingPermissionStatus

/**
 * Every sharing permission the student can answer (RFC 248), on the wire.
 * Wrapper-keyed like [IntendedSubjectsResponse]. One entry per
 * [ed.unicoach.db.models.SharingPermission], in enum order — a permission the
 * student was never asked is listed as [SharingPermissionStatusView.NOT_ASKED],
 * never left out.
 */
data class SharingPermissionsResponse(
  val permissions: List<PublicSharingPermission>,
)

/**
 * The closed wire vocabulary of [PublicSharingPermission.status] (RFC 248):
 * the two stored answers plus [NOT_ASKED], the wire word for "no row". Only
 * [GRANTED] lets anyone see the data. Jackson serializes [wire] via its
 * `@JsonValue`, as [SubscriptionStatusView] does. [from] is exhaustive, so a
 * status added to [SharingPermissionStatus] breaks the build here.
 */
enum class SharingPermissionStatusView(
  @get:JsonValue val wire: String,
) {
  /** The student was never asked. Never stored and cannot be written; withdrawing is [REFUSED]. */
  NOT_ASKED("not_asked"),
  GRANTED(SharingPermissionStatus.GRANTED.value),
  REFUSED(SharingPermissionStatus.REFUSED.value),
  ;

  companion object {
    /** The member that renders the student's stored answer; `null` (no row) is [NOT_ASKED]. */
    fun from(status: SharingPermissionStatus?): SharingPermissionStatusView =
      when (status) {
        null -> NOT_ASKED
        SharingPermissionStatus.GRANTED -> GRANTED
        SharingPermissionStatus.REFUSED -> REFUSED
      }
  }
}

/** One permission and the student's answer to it. */
data class PublicSharingPermission(
  val permission: String,
  val status: SharingPermissionStatusView,
)

/** The PUT body for one permission: the student's answer, `granted` or `refused`. */
data class UpdateSharingPermissionRequest(
  val status: String? = null,
)
