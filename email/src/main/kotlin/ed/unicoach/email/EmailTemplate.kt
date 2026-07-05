package ed.unicoach.email

import kotlinx.serialization.Serializable

/**
 * The set of renderable email templates. A [EmailJobPayload] carries one of
 * these to select its renderer; email variants are distinguished by [template],
 * never by a distinct [JobType]. Serialized by constant name.
 */
@Serializable
enum class EmailTemplate {
  EMAIL_VERIFICATION,
}
