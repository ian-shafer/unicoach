package ed.unicoach.rest.models

/**
 * The student's whole answer to "what do you want to study?" (RFC 205), on the
 * wire. Wrapper-keyed like [MoneyProfileResponse].
 */
data class IntendedSubjectsResponse(
  val intendedSubjects: PublicIntendedSubjects,
)

/**
 * The projection: the status ALWAYS, and the subjects with their names.
 *
 * [subjects] is always emitted and never null — empty exactly when [status] is
 * not `answered`, which is what the schema's deferred `status iff subjects`
 * constraint guarantees. A client therefore never has to decide what a missing
 * key means.
 *
 * [status] carries FOUR values here (`unanswered`, `answered`, `declined`,
 * `undecided`) where the three money-profile statuses carry three. That is not
 * an oversight: `undecided` — "I don't know yet" — is a real answer to this
 * question and an impossible one on an income band, and the `money_profiles`
 * CHECKs refuse it there.
 */
data class PublicIntendedSubjects(
  val status: String,
  val subjects: List<PublicSubject>,
)
