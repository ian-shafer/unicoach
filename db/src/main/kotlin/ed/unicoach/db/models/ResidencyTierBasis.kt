package ed.unicoach.db.models

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

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
 *
 * It lives in `:db` rather than beside the cost surfaces that first spoke it,
 * on the [LivingArrangement] precedent recorded in that file: the vocabulary
 * moved down when a module below `:service` needed it, and the MAPPING stayed
 * up in the cost domain. Here the second reader is college SEARCH (RFC 169,
 * brief 0006 D19), which renders one result row in `:college` for both search
 * tools and cannot see `:service`. Moving the enum keeps ONE home for the
 * sentence; copying it would give one fact two wordings, which is the whole
 * defect [statement] exists to prevent.
 *
 * `residencyTiersOf` and `publishedTuitionTiersOf` stay in
 * `ed.unicoach.coaching.costs.canonical.ResidencyTiers`: they read
 * `ServedFigures` and `CostField`, which are the cost domain's own types and no
 * part of what this vocabulary IS.
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
 * One tuition tier as the basis decision reads it: whether it BEARS a value at
 * the served year, and the status behind it.
 *
 * The two are carried together because the decision needs both and they answer
 * different questions. `amountPresent` says the school publishes that tier;
 * [status] says WHY when it does not, and the difference between
 * `not_applicable` and every other absence is the whole of D19.
 *
 * `status = null` is "no row at all", which is an absence like any other and
 * deliberately not a case of its own: a cell the publisher never wrote and a
 * cell it wrote `not_reported_by_institution` on are the same silence.
 */
data class TuitionTierReading(
  val amountPresent: Boolean,
  val status: FigureStatus?,
) {
  companion object {
    /** No row at all: the shape a caller that holds no figure for the tier passes. */
    val ABSENT = TuitionTierReading(amountPresent = false, status = null)
  }
}

/**
 * WHICH tuition tiers a college publishes, from the three tier readings alone —
 * THE one derivation, and the reason it lives here rather than beside either
 * caller.
 *
 * Two surfaces answer this question about the same school and must never answer
 * it differently: the cost path (`residencyTiersOf`, which reads `ServedFigures`
 * in the cost domain) and college SEARCH, which reads the same three tuition
 * rows for the page it is about to return and emits
 * [ResidencyTierBasis.PUBLISHER_DOES_NOT_SEPARATE_IN_DISTRICT]'s statement when
 * this function says so (RFC 169, brief 0006 D19). The rule used to be a `when`
 * inside the cost path; a second copy in `:college` would have been a second
 * dialect of "which prices does this school publish", which is exactly what
 * `publishedTuitionTiersOf`'s own doc forbids.
 *
 * It takes READINGS, not `ServedFigures` or `CostField`: those are the cost
 * domain's own types and no part of what the rule IS, which is also why the two
 * mapping helpers stayed up there.
 *
 * "Publishes a tier" means a VALUE-BEARING figure. A row flagged
 * `not_applicable` -- the typical public four-year and every private, where
 * IC_AY carries an in-district cell that means nothing -- is NOT a tier: it
 * exists in the store and says the tier does not apply.
 *
 * The distinction from [ResidencyTierBasis.PUBLISHER_DOES_NOT_SEPARATE_IN_DISTRICT]
 * is exactly the distinction between an answer and a silence: an `in_district`
 * row saying `not_applicable` is the publisher ANSWERING, and every other
 * absence -- no row at all, `not_reported_by_institution`,
 * `suppressed_by_publisher`, `not_collected_by_us` -- is a district price we
 * cannot speak about. Measured on the 2023 IC_AY snapshot over the default
 * search universe: 2,269 schools publish a district amount, 354 carry the
 * publisher's `not_applicable` ANSWER, and 641 are the silence.
 */
fun residencyTierBasisOf(
  inDistrict: TuitionTierReading,
  inState: TuitionTierReading,
  outOfState: TuitionTierReading,
): ResidencyTierBasis {
  val hasInDistrict = inDistrict.amountPresent
  val hasInState = inState.amountPresent
  val hasOutOfState = outOfState.amountPresent

  // The publisher ANSWERED "there is no district tier at this school" with
  // exactly one status: `not_applicable`. Every other absence -- not reported,
  // suppressed for privacy, not collected by us, and no row at all -- is a
  // district price we cannot speak about, and they are the SAME state as far as
  // this sentence goes. Read from the reading, never from row existence: an
  // in_district cell at `not_reported_by_institution` used to read as
  // TWO_TIERS_PUBLISHED, telling a community-college family the school's price
  // list has two tiers when we do not know that.
  val inDistrictAnswered = inDistrict.status == FigureStatus.NOT_APPLICABLE

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
 * Writes the residency-tier pair — the machine `basis` and the `statement` a
 * coach says it in — into the object being built, under the
 * [RESIDENCY_TIERS_KEY] both surfaces use.
 *
 * ONE emitter, on the [putIncomeBand] precedent and for the same reason: a code
 * never travels without its sentence (RFC 151 D-D), and no site can half-fire by
 * writing one and forgetting the other. It also means a cost answer and a search
 * result describe the same school's price list under the same key with the same
 * words, which is what stops a family reading two accounts of one fact.
 */
fun JsonObjectBuilder.putResidencyTiers(basis: ResidencyTierBasis) {
  putJsonObject(RESIDENCY_TIERS_KEY) {
    put("basis", basis.value)
    put("statement", basis.statement)
  }
}

/** The wire key [putResidencyTiers] writes under; named once so two surfaces cannot spell it differently. */
const val RESIDENCY_TIERS_KEY = "residency_tiers"
