package ed.unicoach.coaching

/**
 * The independently-optional fields of a conversation PATCH. A null field means
 * "leave unchanged"; a present field means "set". `name` is never nulled (the
 * column is NOT NULL), so null unambiguously means untouched. `archived`'s three
 * states are null (untouched) / true (archive) / false (unarchive).
 *
 * The contract's "at least one field present" rule is not expressible on this
 * type; an empty update is rejected by [CoachingService.updateConvo] as a
 * validation failure, not at construction.
 */
data class ConvoUpdate(
  val name: String? = null,
  val archived: Boolean? = null,
)
