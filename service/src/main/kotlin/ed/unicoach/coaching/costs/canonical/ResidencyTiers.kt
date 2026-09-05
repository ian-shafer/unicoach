package ed.unicoach.coaching.costs.canonical

import ed.unicoach.coaching.costs.CostField
import ed.unicoach.db.models.FigureStatus

/**
 * WHICH tuition tiers a college publishes (RFC 166 §4), as a code and the words
 * that go with it.
 *
 * `price_figures` carries `in_district` as a first-class residency basis
 * (`CHG1AY`), and RFC 161 measured 269 institutions where the Scorecard's
 * "in-state" figure is really the in-district one. No consumer surface has ever
 * shown the tier, so a family at a community college read one price where their
 * own district price is materially lower.
 *
 * The third case is the one that matters most and is the least visible: at the
 * ~2,300 IC_PY institutions there is no `in_district` row at all, and the
 * in-state figure we hold may or may not be an in-district figure wearing an
 * in-state label. RFC 161 declined to re-label it on a guess -- "that would
 * trade one wrong label for another" -- so the figure keeps the label the
 * publisher gave it and we say what we do not know. AN IN-STATE FIGURE IS NEVER
 * PRESENTED AS AN IN-DISTRICT PRICE.
 *
 * No new question is asked to resolve any of this. `money_profiles.residency_state`
 * cannot select an in-district price -- a state answer does not answer a
 * district -- so the in-district figure is shown as a LABELLED TIER and is never
 * the tuition line inside a total.
 */
enum class ResidencyTierBasis(
  val value: String,
  /** The sentence a coach says this basis in; the code never travels without it (RFC 151 D-D). */
  val statement: String,
) {
  /** In-district, in-state and out-of-state are all published, and all three are named. */
  THREE_TIERS_PUBLISHED(
    "three_tiers_published",
    "This school publishes three tuition prices: one for students living in its district, one for other " +
      "students living in its state, and one for students from outside the state.",
  ),

  /** The ordinary public four-year and private case: in-state and out-of-state, and no district tier to mention. */
  TWO_TIERS_PUBLISHED(
    "two_tiers_published",
    "This school publishes two tuition prices: one for students living in its state and one for students " +
      "from outside it.",
  ),

  /**
   * There is no in-district row for this school at all. The in-state figure is
   * served AS IN-STATE and the gap is stated, never guessed at: at a community
   * college whose publisher does not separate the two, the figure we hold may
   * already be the district price, and saying which it is would be inventing a
   * fact.
   */
  PUBLISHER_DOES_NOT_SEPARATE_IN_DISTRICT(
    "publisher_does_not_separate_in_district",
    "For this school our source does not separate a district price from the state price, so the in-state " +
      "figure is shown as it was published and we cannot say whether a lower district price exists.",
  ),

  /**
   * A district price and one other tier, and not the full three.
   *
   * Its own basis because neither neighbour is true of it:
   * [TWO_TIERS_PUBLISHED]'s words name the in-state and out-of-state pair
   * specifically, and [SINGLE_PUBLISHED_PRICE] would state one price beside two
   * emitted tuition keys -- money copy contradicting the payload it rides in.
   */
  IN_DISTRICT_AND_ONE_OTHER_TIER(
    "in_district_and_one_other_tier",
    "This school publishes a tuition price for students living in its district and one other tuition price; " +
      "it does not publish all three tiers.",
  ),

  /**
   * Exactly ONE tuition tier bears a value here.
   *
   * The words do not say "one price for everybody": the one tier we hold can
   * itself be a residency-specific figure (a public school whose out-of-state
   * price is suppressed keeps its in-state one), and a sentence claiming
   * universal applicability would contradict the `tuition_applicable` key
   * riding beside it.
   */
  SINGLE_PUBLISHED_PRICE(
    "single_published_price",
    "This school publishes one tuition price in what we hold; where that price is for one kind of student, " +
      "the tuition figure beside it says which.",
  ),

  /**
   * NO tuition tier bears a value at all -- said as itself.
   *
   * Its own basis because the alternative was the catch-all: this input used to
   * fall to [SINGLE_PUBLISHED_PRICE] and a school we hold no tuition price for
   * was announced as publishing one, in the same payload whose tuition keys are
   * absent and whose `data_availability` says so.
   */
  NO_PUBLISHED_TUITION(
    "no_published_tuition",
    "We hold no published tuition price for this school, so there is no tuition tier to name.",
  ),
}

/**
 * Which tiers this college publishes, decided from the rows alone.
 *
 * "Publishes a tier" means a VALUE-BEARING figure at the served year. A row
 * flagged `not_applicable` -- the typical public four-year and every private,
 * where IC_AY carries an in-district cell that means nothing -- is NOT a tier:
 * it exists in the store and says the tier does not apply, so no in-district key
 * is emitted and the tier is not mentioned at all.
 *
 * The distinction from [ResidencyTierBasis.PUBLISHER_DOES_NOT_SEPARATE_IN_DISTRICT]
 * is exactly the distinction between an answer and a silence: an
 * `in_district` row saying `not_applicable` is the publisher ANSWERING, and
 * every other absence -- no row at all, `not_reported_by_institution`,
 * `suppressed_by_publisher`, `not_collected_by_us` -- is a district price we
 * cannot speak about.
 */
fun residencyTiersOf(served: ServedFigures): ResidencyTierBasis {
  val inDistrict = served.figureOf(CostField.TUITION_AND_FEES_IN_DISTRICT_PER_YEAR_USD)
  val inState = served.figureOf(CostField.TUITION_AND_FEES_IN_STATE_PER_YEAR_USD)
  val outOfState = served.figureOf(CostField.TUITION_AND_FEES_OUT_OF_STATE_PER_YEAR_USD)

  val hasInDistrict = inDistrict?.amountUsd != null
  val hasInState = inState?.amountUsd != null
  val hasOutOfState = outOfState?.amountUsd != null

  // The publisher ANSWERED "there is no district tier at this school" with
  // exactly one status: `not_applicable`. Every other absence -- not reported,
  // suppressed for privacy, not collected by us, and no row at all -- is a
  // district price we cannot speak about, and they are the SAME state as far as
  // this sentence goes. Read from the reading, never from row existence: an
  // in_district cell at `not_reported_by_institution` used to read as
  // TWO_TIERS_PUBLISHED, telling a community-college family the school's price
  // list has two tiers when we do not know that.
  val inDistrictAnswered = inDistrict?.status == FigureStatus.NOT_APPLICABLE

  return when {
    // FIRST, because it used to be last: NOTHING bears a value, and the school
    // is not made to publish a price we do not hold.
    !hasInDistrict && !hasInState && !hasOutOfState -> {
      ResidencyTierBasis.NO_PUBLISHED_TUITION
    }

    hasInDistrict && hasInState && hasOutOfState -> {
      ResidencyTierBasis.THREE_TIERS_PUBLISHED
    }

    // A two-tier price list and no district ANSWER: the publisher never
    // separated the district price -- or withheld it -- and we say so rather
    // than guessing which of the two the in-state figure really is.
    !inDistrictAnswered && hasInState && hasOutOfState -> {
      ResidencyTierBasis.PUBLISHER_DOES_NOT_SEPARATE_IN_DISTRICT
    }

    // The publisher said the district tier does not apply here: the ordinary
    // public four-year and every private.
    hasInState && hasOutOfState -> {
      ResidencyTierBasis.TWO_TIERS_PUBLISHED
    }

    // A district price beside exactly one of the state tiers. Rare, and the
    // reason this arm exists rather than falling through: the fall-through said
    // "one tuition price" while [publishedTuitionTiersOf] emitted two keys, so
    // the sentence contradicted the payload it travelled in.
    hasInDistrict && (hasInState || hasOutOfState) -> {
      ResidencyTierBasis.IN_DISTRICT_AND_ONE_OTHER_TIER
    }

    // EXACTLY one tuition tier bears a value here -- including the odd school
    // whose only value-bearing tier is the district one. One key, one price,
    // and no contradiction between the words and the payload. The zero case is
    // no longer reachable: it is the first arm.
    else -> {
      ResidencyTierBasis.SINGLE_PUBLISHED_PRICE
    }
  }
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
