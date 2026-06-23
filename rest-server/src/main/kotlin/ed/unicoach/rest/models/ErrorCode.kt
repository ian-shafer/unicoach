package ed.unicoach.rest.models

import com.fasterxml.jackson.annotation.JsonValue

/**
 * The closed set of REST error codes. Each entry pairs an idiomatic Kotlin name
 * with its lowercase snake_case wire string, which Jackson serializes via
 * [wire]'s `@JsonValue`. This is the single source of truth for the wire code:
 * a code that is not lowercase snake_case cannot be added without editing this
 * enum, and `ErrorCodeTest` fails if one is. `ErrorResponse.code` is typed as
 * `ErrorCode`, so the wire form cannot be stringly constructed or mis-cased.
 */
enum class ErrorCode(
  @get:JsonValue val wire: String,
) {
  // Shared / auth / conversation families (already lowercase)
  UNAUTHORIZED("unauthorized"),
  VALIDATION_FAILED("validation_failed"),
  CONFLICT("conflict"),
  INVALID_TOKEN("invalid_token"),
  TOKEN_EXPIRED("token_expired"),
  TOKEN_ALREADY_USED("token_already_used"),
  NOT_FOUND("not_found"),
  STUDENT_PROFILE_REQUIRED("student_profile_required"),
  COACH_UNAVAILABLE("coach_unavailable"),
  COACH_FAILED("coach_failed"),

  // Student family — normalized from UPPERCASE by RFC 69.
  // VALIDATION_ERROR is a legacy synonym of VALIDATION_FAILED, kept distinct
  // here (casing-only change); a future RFC unifies the two.
  VALIDATION_ERROR("validation_error"),
  STUDENT_NOT_FOUND("student_not_found"),
  STUDENT_ALREADY_EXISTS("student_already_exists"),
  VERSION_CONFLICT("version_conflict"),

  // Cross-cutting plugins (already lowercase)
  BAD_REQUEST("bad_request"),
  PAYLOAD_TOO_LARGE("payload_too_large"),
  PERMANENT_ERROR("permanent_error"),
  INTERNAL_ERROR("internal_error"),
  FORBIDDEN("forbidden"),

  // Email-verification gate (new)
  EMAIL_NOT_VERIFIED("email_not_verified"),

  // Google SSO
  ACCOUNT_DISABLED("account_disabled"),
  SERVICE_UNAVAILABLE("service_unavailable"),
}
