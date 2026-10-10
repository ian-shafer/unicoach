package ed.unicoach.db.models

import ed.unicoach.common.money.Nanodollars

/**
 * An LLM dollar total read off one of the per-call cost spines (RFC 108), over
 * either a lifetime or a `[start, end)` window.
 *
 * It is named after the AGGREGATE and not after a subject, which is the whole
 * point of the rename (RFC 244): the same three scalars answer for a student
 * (`student_llm_cost`) and for a supporter (`supporter_llm_cost`), and a type
 * named after one of them read as a claim about whose spend it was — the kind of
 * name a second DAO either copies or quietly contradicts.
 *
 * [costNanodollars] is the summed frozen cost in nano-dollars (1e-9 USD). The two
 * counters bound that total in opposite directions and are deliberately not
 * collapsed:
 * - [uncostedCalls] — attributed calls whose cost is NULL (usage was unreported),
 *   so the total is an UNDER-count by an unknown amount; their contribution to
 *   the sum is `0` (`COALESCE`).
 * - [estimatedCalls] — attributed calls priced at the default rate (the resolved
 *   model was absent from the price book), a deliberate OVER-estimate of that
 *   portion; unlike an uncosted call, an estimated call's cost IS in the sum.
 *
 * A downstream budget gate reads all three and sets its own policy; this carries
 * no policy of its own.
 */
class LlmCostAggregate(
  val costNanodollars: Nanodollars,
  val uncostedCalls: Int,
  val estimatedCalls: Int,
)
