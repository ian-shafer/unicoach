package ed.unicoach.account

import kotlinx.serialization.Serializable

/**
 * The payload of an `ERASE_ACCOUNT` job (RFC 267 §3): the account, and the
 * close that scheduled it. The job erases the account only while that close
 * stands, so an account reopened, or closed again, is not erased on the first
 * close's schedule. The address is not here (decision D-A): it is re-read
 * inside the erase transaction, so `jobs` holds one fewer personal value.
 */
@Serializable
data class AccountErasurePayload(
  val userId: String,
  val closedAt: String, // users.deleted_at of the close that scheduled it, ISO-8601
)
