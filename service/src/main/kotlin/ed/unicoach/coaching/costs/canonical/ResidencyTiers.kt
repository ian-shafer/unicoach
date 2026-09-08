package ed.unicoach.coaching.costs.canonical

import ed.unicoach.coaching.costs.CostField
import ed.unicoach.db.models.ResidencyTierBasis
import ed.unicoach.db.models.TuitionTierReading
import ed.unicoach.db.models.residencyTierBasisOf

/**
 * Which tiers this college publishes, at the served year — the cost path's
 * adapter onto [residencyTierBasisOf], which is where the rule itself lives.
 *
 * The rule moved down to `:db` when college search became its second reader
 * (RFC 169, brief 0006 D19): two surfaces answering "which prices does this
 * school publish" about the same school must answer it identically, and
 * [publishedTuitionTiersOf]'s own doc already forbids a surface re-deriving the
 * tier shape for itself. What stays here is the READ — `ServedFigures` and
 * `CostField` are the cost domain's own types — and nothing else.
 */
fun residencyTiersOf(served: ServedFigures): ResidencyTierBasis =
  residencyTierBasisOf(
    served.getTuitionTierReading(CostField.TUITION_AND_FEES_IN_DISTRICT_PER_YEAR_USD),
    served.getTuitionTierReading(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD),
    served.getTuitionTierReading(CostField.TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD),
  )

/**
 * One tuition tier's reading at the served year: whether it bears a value, and
 * the status behind it. Read through [ServedFigures.figureOf] rather than
 * [ServedFigures.amountOf], because a field with no row and a field whose row
 * bears no value answer the same null there and the basis decision must tell
 * them apart.
 */
private fun ServedFigures.getTuitionTierReading(field: CostField): TuitionTierReading =
  figureOf(field).let { figure ->
    TuitionTierReading(amountPresent = figure?.amountUsd != null, status = figure?.status)
  }

/**
 * The tuition tiers this college actually shows, in declaration order -- the
 * fields a payload emits a tuition key for.
 *
 * Read off the rows rather than off [ResidencyTierBasis], because the basis is
 * the SENTENCE and this is the DATA: a school can publish an in-district figure
 * and no out-of-state one, and the tier is still real.
 *
 * THE one derivation of "which tuition prices does this school publish". The
 * payload emits its tuition keys by iterating it
 * ([ed.unicoach.coaching.costs.CollegeCost.publishedTuitionTiers]) and
 * `reportsPublishedTuition` decides the residency upgrade from it, so no surface
 * re-derives the tier shape from one amount -- which is how an offer came to
 * promise three prices beside a statement saying the school publishes two.
 */
fun publishedTuitionTiersOf(served: ServedFigures): List<CostField> =
  CostField.listInDeclarationOrder(CostField.TUITION_FIELDS).filter { field -> served.amountOf(field) != null }
