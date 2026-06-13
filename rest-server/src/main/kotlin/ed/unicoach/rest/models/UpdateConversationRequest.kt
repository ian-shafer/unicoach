package ed.unicoach.rest.models

/**
 * Both fields are optional (a one-field PATCH is valid), so they declare
 * defaults: Jackson's FAIL_ON_MISSING_CREATOR_PROPERTIES must not reject a
 * request that names only one. The "at least one present" rule is enforced in
 * the route handler.
 */
data class UpdateConversationRequest(
  val name: String? = null,
  val archived: Boolean? = null,
)
