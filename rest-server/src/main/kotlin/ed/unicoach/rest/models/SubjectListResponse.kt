package ed.unicoach.rest.models

/**
 * The picker's list (RFC 205 D29/D41). Wrapper-keyed like
 * [CollegeSearchResponse], so a later addition to the envelope is not a
 * breaking change to the array.
 */
data class SubjectListResponse(
  val subjects: List<PublicSubject>,
)

/**
 * One subject on the list: identity and label, and nothing else.
 *
 * [slug] is the identity the client sends back, never shown to anyone; [name] is
 * the label and may change freely (D39). No CIP prefix reaches a student: the
 * federal codes are the publisher's grain on the college side, not an answer
 * vocabulary.
 */
data class PublicSubject(
  val slug: String,
  val name: String,
)
