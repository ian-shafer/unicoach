package ed.unicoach.db.models

import ed.unicoach.db.dao.corruptValue

/**
 * ONE published cell: the `(source, source_variable)` pair a stored money row
 * carries, closed into a single type and decoded exactly once, at the read
 * boundary (RFC 184).
 *
 * RFC 179 made an [AssuranceTier] a function of that pair. The pair then
 * travelled as TWO LOOSE FIELDS on every carrier above the DAO, and the legal
 * variable names are a function of the source with nothing in the type system
 * saying so, so this compiled and was nonsense:
 *
 *     FigureProvenance(status, MoneySource.SCORECARD, "H.209")   // a CDS field id under the Scorecard
 *
 * Nothing caught it. It died at read time, inside one family's cost report.
 * Here the two halves cannot be paired wrongly, because a caller never names
 * both: [Surveyed] cannot be handed [MoneySource.SCORECARD] (there is no such
 * [Survey] member), [SelfPublished] takes no source at all, and a Scorecard
 * cell can only be built through [ScorecardCell.of], whose one argument is a
 * variable the tier table answers for.
 *
 * Being exact about the guarantee, because a half-claim about type safety is
 * worse than none: `source_variable` is an open `TEXT` column, and the SET of
 * legal Scorecard variables lives in `:college`
 * ([ed.unicoach.college.ScorecardInstitutionColumns]), which depends on `:db`
 * and not the reverse -- so it cannot be closed at compile time from here.
 * What becomes IMPOSSIBLE is a cell whose SOURCE and VARIABLE FAMILY disagree,
 * which is the defect in the example above. What stays a runtime refusal, in
 * exactly ONE place ([ScorecardCell.of]), is an unknown Scorecard string.
 *
 * DERIVED, never stored: the store keeps the two columns it always kept, and
 * this type is how the read side holds them. Nothing serialises a cell and
 * nothing reads one back out of a store, so there is no `fromValue` and no
 * `value` -- [of] is the decoder, and it takes the stored pair.
 */
sealed class PublishedCell(
  /**
   * The value each arm exposes as [variable], taken again as a constructor
   * parameter so [init] can see it.
   *
   * NOT redundant with the abstract [variable]: a subclass's `override val` is
   * assigned AFTER the superclass constructor has run, so an `init` reading
   * [variable] would read an unset field rather than the name the caller
   * passed. Every arm therefore passes its OWN `variable` here -- passing
   * anything else would check the blank rule against a string this cell does
   * not carry. Delete the parameter and the guard below goes quiet.
   */
  variable: String,
) {
  init {
    // A cell whose publisher's own name for it is empty is not a cell: every
    // fact table already CHECKs `source_variable <> ''`, so the type may not be
    // looser than the store it stands for. Stated ONCE, here on the union,
    // because the three arms share the property -- an `init` copied into each
    // is three chances to edit one rule apart.
    //
    // `require`, i.e. IllegalArgumentException, and NOT the located
    // corrupt-value path: this refuses a CONSTRUCTION site that named nothing,
    // which is a programming error the build should never ship. The
    // corrupt-value path is for a value read back OUT of the store, where one
    // bad row must cost one row.
    require(variable.isNotBlank()) {
      "a published cell names the publisher's own variable, and no source_variable is ever blank"
    }
  }

  /** WHO published this cell (RFC 177) -- the `source` column's value, as a type. */
  abstract val source: MoneySource

  /** The publisher's OWN name for the cell: IC_AY's `CHG2AY3`, the Scorecard's `TUITIONFEE_IN`, the CDS's `H.209`. */
  abstract val variable: String

  /** What KIND of number this cell is (RFC 179). A property of the cell, not a second lookup a caller must remember to do. */
  abstract val assurance: AssuranceTier

  /**
   * An IPEDS survey cell: SFA or IC_AY.
   *
   * The tier answers for the WHOLE source here, which is a decision rather than
   * a default -- an IPEDS cell is a cell of one compelled, edit-checked survey
   * whichever variable it is. The instrument is a property of the source.
   *
   * The constructor is PUBLIC and needs no factory, because its shape already
   * makes it right: [Survey] has no Scorecard and no Common Data Set member, so
   * there is no wrong pairing to guard against. A public constructor that
   * cannot be wrong is a better guard than a private one plus a factory,
   * because it never has to be enforced.
   */
  data class Surveyed(
    val survey: Survey,
    override val variable: String,
  ) : PublishedCell(variable) {
    override val source: MoneySource get() = survey.source

    override val assurance: AssuranceTier get() = AssuranceTier.MANDATORY_SURVEY
  }

  /**
   * A Common Data Set cell: the school's own filing, unaudited, whichever field
   * id it carries (`H.209`, `H.801`).
   *
   * Takes no source, for [Surveyed]'s reason: there is exactly one
   * self-published publisher in this corpus, so naming it would only create the
   * opportunity to name it wrongly.
   */
  data class SelfPublished(
    override val variable: String,
  ) : PublishedCell(variable) {
    override val source: MoneySource get() = MoneySource.COMMON_DATA_SET

    override val assurance: AssuranceTier get() = AssuranceTier.VOLUNTARY_SELF_REPORT
  }

  /**
   * A College Scorecard cell -- the ONE publisher whose tier is keyed on the
   * VARIABLE rather than on the source.
   *
   * The Scorecard relays some publishers and originates others: it re-publishes
   * IPEDS cells, and it also carries federal administrative records about
   * students. So the tier cannot be read off the source, and this arm carries
   * it. WHICH cells are which is [AssuranceTier.SCORECARD_TIERS]' own answer,
   * counted and named there rather than tallied a second time here, where a
   * nineteenth loaded column would leave the tally quietly wrong.
   *
   * The constructor is PRIVATE, and this is the one arm that needs it to be: a
   * public one could pair `("TUITIONFEE_IN", ADMINISTRATIVE_RECORD)` -- the
   * very defect this type exists to stop, moved one type inward. Everything
   * goes through [of], which reads the tier from
   * [AssuranceTier.SCORECARD_TIERS] and cannot be talked out of it.
   * `@ConsistentCopyVisibility` closes `copy()` with the constructor, which
   * would otherwise be the same hole with a different name.
   */
  @ConsistentCopyVisibility
  data class ScorecardCell private constructor(
    override val variable: String,
    override val assurance: AssuranceTier,
  ) : PublishedCell(variable) {
    override val source: MoneySource get() = MoneySource.SCORECARD

    companion object {
      /**
       * The Scorecard cell [variable] names, or the located refusal.
       *
       * `source_variable` is an OPEN `TEXT` column, so no `when` over it can be
       * exhaustive and the compiler cannot supply the safety net it supplies
       * for the source. An unmapped variable is therefore FATAL, on the
       * `CanonicalMoneyLoader.ORDERED_SOURCES` unranked-member precedent: a
       * column added to the loader without a tier stops the cell instead of
       * being served under a guessed one. It never defaults to the softest tier
       * and never to the hardest.
       *
       * It is fatal in the HOUSE's shape -- the located
       * [ed.unicoach.db.dao.CorruptPersistedValueException] every sibling
       * decode raises, with its `PermanentError` marker and both halves of the
       * pair as the exception's `value`. [location] is REQUIRED, not defaulted:
       * every caller already holds the row's identity or the field it is
       * serving, and an unlocated corrupt-value fault costs the operator a
       * `source_variable` scan of two fact tables.
       */
      fun of(
        variable: String,
        location: String,
      ): ScorecardCell =
        ScorecardCell(
          variable,
          AssuranceTier.SCORECARD_TIERS[variable]
            ?: throw corruptValue(
              // BOTH halves of the key as the exception's own DATA: the tier is
              // a function of the pair, so a fixer needs the publisher as well
              // as the cell id, and neither may be left to be parsed back out
              // of prose.
              "source=[${MoneySource.SCORECARD.value}] source_variable=[$variable]",
              "a tiered Scorecard source_variable (AssuranceTier.SCORECARD_TIERS)",
              location,
            ),
        )
    }
  }

  /**
   * The two IPEDS surveys this corpus reads, AS A TYPE -- so [Surveyed] cannot
   * name a third publisher.
   *
   * It is a separate enum rather than a subset of [MoneySource] because a
   * subset cannot be expressed there: [MoneySource] is the whole publisher
   * vocabulary the store writes, and the compiler has no way to say "these two
   * members only". Two members here, and [source] maps each back to the column
   * value it is stored as, so nothing above this file learns a second spelling.
   */
  enum class Survey(
    val source: MoneySource,
  ) {
    SFA(MoneySource.IPEDS_SFA),
    IC_AY(MoneySource.IPEDS_IC_AY),
  }

  companion object {
    /**
     * The ONE decoder: a stored pair in, a cell out.
     *
     * A `when` over [MoneySource] with NO `else`, so a fifth publisher must
     * decide what kind of number it produces before this compiles. This is the
     * only place in the tree that reads the pair, and [AssuranceTier.of]
     * delegates to it -- a second `when` over the same pair is how two answers
     * to one question start to drift.
     *
     * [location] names the row, and is passed on to [ScorecardCell.of], which
     * is the only arm that can refuse.
     */
    fun of(
      source: MoneySource,
      sourceVariable: String,
      location: String,
    ): PublishedCell =
      when (source) {
        MoneySource.IPEDS_SFA -> {
          Surveyed(Survey.SFA, sourceVariable)
        }

        MoneySource.IPEDS_IC_AY -> {
          Surveyed(Survey.IC_AY, sourceVariable)
        }

        MoneySource.COMMON_DATA_SET -> {
          SelfPublished(sourceVariable)
        }

        MoneySource.SCORECARD -> {
          ScorecardCell.of(sourceVariable, location)
        }
      }
  }
}

/**
 * A carrier that stands for exactly ONE [PublishedCell] (RFC 184).
 *
 * Declared once, here beside the union it projects, because the delegation is
 * one fact about a cell rather than six identical bodies free to be edited
 * apart: every carrier from the read row up to the wire note answers "who
 * published this" with the cell's own publisher, and a carrier that spelled
 * that answer its own way is how the pair came apart in the first place.
 *
 * [PublishedCell.assurance] is deliberately NOT here. Exactly one carrier republishes the
 * tier, and one site is not duplication -- an interface that invented the
 * accessor would be offering surface no implementor asked for.
 */
interface CellCarrier {
  /** WHICH published cell this carrier stands for. */
  val cell: PublishedCell

  /** WHO published it (RFC 177), off the cell. */
  val source: MoneySource get() = cell.source
}
