package ed.unicoach.web.auth

import ed.unicoach.db.session.SessionMetadata
import ed.unicoach.util.TokenGenerator
import ed.unicoach.web.formField
import ed.unicoach.web.formParametersOrNull
import ed.unicoach.web.render.CSRF_FIELD
import io.ktor.server.application.ApplicationCall
import java.security.MessageDigest
import java.util.Base64

/**
 * The header the chat client's `fetch` carries the token in (RFC 265). A form
 * POST carries it in the [CSRF_FIELD] hidden input instead; `chat.ts` restates
 * this name and `chat-client-contract.json` holds both halves to one value.
 */
const val CSRF_HEADER = "X-CSRF-Token"

/**
 * A session's CSRF synchronizer token as it is written into a page and read
 * back from a request (brief 0013 D22, RFC 265): the stored bytes in URL-safe
 * base64, unpadded, so it travels in a form field and a header unescaped.
 *
 * Built only by [of], from a session that HAS a well-formed token: a signed-in
 * page cannot be handed an empty or short one to render.
 */
@JvmInline
value class CsrfToken private constructor(
  val value: String,
) {
  /**
   * Whether [presented] is this token, compared in constant time: a comparison
   * that returned at the first wrong character would tell a prober how many
   * leading characters they had right.
   */
  fun matches(presented: String): Boolean =
    MessageDigest.isEqual(presented.toByteArray(Charsets.US_ASCII), value.toByteArray(Charsets.US_ASCII))

  companion object {
    /**
     * [metadata]'s token, or `null` unless it is exactly the
     * [TokenGenerator.TOKEN_BYTES] every session is minted with. An empty
     * token is a row minted before RFC 265; any other size is a row no code
     * wrote. Neither is a token this server issued, so neither is accepted.
     */
    fun of(metadata: SessionMetadata): CsrfToken? =
      if (metadata.csrfToken.size() != TokenGenerator.TOKEN_BYTES) {
        null
      } else {
        CsrfToken(Base64.getUrlEncoder().withoutPadding().encodeToString(metadata.csrfToken.toByteArray()))
      }
  }
}

/**
 * Why a state-changing request's token was refused: the one fact the refusal's
 * WARN carries, so a stale page (a well-formed [MISMATCH]) can be told apart
 * from a request that never carried the form at all. Never the token itself.
 */
internal enum class CsrfRefusal {
  /** The [CSRF_HEADER] was sent more than once. */
  HEADER_REPEATED,

  /** No header, and the body is not a form, so there is no field to read. */
  NOT_A_FORM,

  /** No header, and the form carries no [CSRF_FIELD] or carries it twice. */
  FIELD_ABSENT_OR_REPEATED,

  /** A token was presented, and it is not this session's. */
  MISMATCH,
}

/**
 * Checks the token this request presents against [expected]: `null` when it
 * matches, else why it was refused.
 *
 * The [CSRF_HEADER] is read first and, when present, alone: the chat client
 * sends its message as the body, so a header-bearing request is not asked to be
 * a form too. Otherwise the form body's one [CSRF_FIELD] is read, through
 * [formParametersOrNull], which keeps it for the handler that reads the same
 * body next.
 */
internal suspend fun ApplicationCall.csrfRefusal(expected: CsrfToken): CsrfRefusal? {
  val headers = request.headers.getAll(CSRF_HEADER)
  val presented =
    if (headers != null) {
      headers.singleOrNull() ?: return CsrfRefusal.HEADER_REPEATED
    } else {
      val form = formParametersOrNull() ?: return CsrfRefusal.NOT_A_FORM
      form.formField(CSRF_FIELD) ?: return CsrfRefusal.FIELD_ABSENT_OR_REPEATED
    }
  return if (expected.matches(presented)) null else CsrfRefusal.MISMATCH
}
