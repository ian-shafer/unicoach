package ed.unicoach.db.models

import ed.unicoach.common.money.Nanodollars

/**
 * A per-student LLM dollar total read off the `student_llm_cost` spine (RFC 108),
 * over either a lifetime or a `[start, end)` window.
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
class StudentLlmCost(
  val costNanodollars: Nanodollars,
  val uncostedCalls: Int,
  val estimatedCalls: Int,
)
