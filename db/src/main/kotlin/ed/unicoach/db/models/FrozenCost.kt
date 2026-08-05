package ed.unicoach.db.models

import ed.unicoach.common.money.Nanodollars

/**
 * A call's frozen dollar cost (RFC 108): the nano-dollar amount and whether it was
 * priced at the price book's default rate. The two facts are never valid
 * independently — db/schema/0041's `llm_responses_cost_estimated_check` encodes the
 * same pairing at the DB layer — so they are carried as one non-null-together value.
 * A call whose cost could not be computed is `cost: FrozenCost? = null` on the
 * response/insert, never a `costNanodollars`/`costIsEstimated` pair that can disagree.
 */
data class FrozenCost(
  val nanodollars: Nanodollars,
  val estimated: Boolean,
)
