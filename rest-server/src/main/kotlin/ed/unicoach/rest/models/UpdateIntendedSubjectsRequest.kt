package ed.unicoach.rest.models

/**
 * The PUT body for a student's intended subjects (RFC 205 D17): the WHOLE
 * answer, replaced. Exactly one of [subjects], [declined] and [undecided] may be
 * supplied; two or more is one pooled 400, and none is a 400 too, because a body
 * that asks for nothing is a caller defect rather than a no-op.
 *
 * There is deliberately no `clear` key, unlike [UpdateMoneyProfileRequest]. A
 * student who removes their last subject lands on `undecided`, never back on
 * `unanswered`: `unanswered` is only ever the state of a student who was never
 * asked, and we do not re-arm an invitation the family already answered.
 *
 * An EMPTY [subjects] array is therefore legal and MEANS `undecided` — it is an
 * answer, not a validation failure.
 *
 * [declined] and [undecided] are NULLABLE on purpose: this route counts the keys
 * that are PRESENT, so absence and a present `false` have to be tellable apart.
 * A non-nullable `Boolean = false` reads a sent `false` as an omission and
 * silently drops it, which made `{"subjects": [...], "declined": false}` a 200
 * here while the chat tool refused the very same shape. Null is absent; `false`
 * is present and is refused by [ed.unicoach.coaching.literalTrueViolations], the
 * rule both doors read.
 */
data class UpdateIntendedSubjectsRequest(
  val subjects: List<String>? = null,
  val declined: Boolean? = null,
  val undecided: Boolean? = null,
)
