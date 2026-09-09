package ed.unicoach.db.models

import ed.unicoach.db.dao.corruptValue

/**
 * How HARD the number behind a figure is: what kind of instrument produced it
 * (RFC 179).
 *
 * `MoneySource` says WHO published a figure (RFC 177). It does not say what
 * kind of number a publisher produces, and "the college had to file this with
 * the federal government and the form edit-checks it" and "the college typed
 * this about itself and nobody checks it" are different claims about a family's
 * money. This enum is the second half of that key.
 *
 * DERIVED, never stored: a pure function of the `(source, source_variable)`
 * pair both fact tables already hold ([of]). No column, no migration, no seeded
 * table, and nothing on [MoneySource] -- of the eighteen Scorecard cells this
 * corpus loads, sixteen are re-published IPEDS and only two are federal
 * administrative records, so a tier hung on the publisher would be wrong about
 * most of one publisher's file.
 *
 * ORTHOGONAL to [FigureStatus], and read as a PAIR with it: an imputed IPEDS
 * cell is soft in a different way from a Common Data Set cell, and the two may
 * not collapse into one another.
 *
 * There is no `fromValue`. Nothing persists a tier and nothing reads one back
 * out of a store, so a decoder for it would be machinery with no reader.
 */
enum class AssuranceTier(
  val value: String,
) {
  /** A federal file about students, kept for another purpose. NSLDS, Treasury. */
  ADMINISTRATIVE_RECORD("administrative_record"),

  /** The school filed it because the law compels the filing, and the form edit-checks it. */
  MANDATORY_SURVEY("mandatory_survey"),

  /** The school published it about itself, on its own initiative, unaudited. */
  VOLUNTARY_SELF_REPORT("voluntary_self_report"),
  ;

  companion object {
    /**
     * The tier of one stored cell, from the pair the row carries.
     *
     * A `when` over [MoneySource] with NO `else`, so a fifth publisher must
     * decide what kind of number it produces before this compiles. Three arms
     * answer for the WHOLE source and one is keyed on the variable, and that is
     * a decision rather than a default: an IC_AY or SFA cell is a cell of one
     * compelled, edit-checked survey whichever variable it is, and a Common
     * Data Set cell is one filing the school published about itself whichever
     * field id it carries. The instrument is a property of the source there.
     * The Scorecard is the one publisher for which that is false -- it relays
     * some publishers and originates others -- so it is the one arm keyed on
     * the variable.
     *
     * `source_variable` is an OPEN `TEXT` column on all four fact tables, so no
     * `when` over it can be exhaustive and the compiler cannot supply the
     * safety net it supplies for the source. An unmapped Scorecard variable is
     * therefore FATAL, on the `CanonicalMoneyLoader.ORDERED_SOURCES`
     * unranked-member precedent: a column added to the loader without a tier
     * stops the read instead of being served under a guessed one. It never
     * defaults to the softest tier and never to the hardest.
     *
     * It is fatal in the HOUSE's shape: this is a read-path reconstruction
     * failure over a stored pair, so it leaves as the located
     * [ed.unicoach.db.dao.CorruptPersistedValueException] every sibling decode
     * raises -- with its `PermanentError` marker, both halves of the pair as the
     * exception's `value`, and [location] naming the row. [location] is
     * REQUIRED, not defaulted: every caller already holds the row's identity or
     * the field it is serving, and an unlocated corrupt-value fault costs the
     * operator a `source_variable` scan of two fact tables.
     */
    fun of(
      source: MoneySource,
      sourceVariable: String,
      location: String,
    ): AssuranceTier =
      when (source) {
        MoneySource.IPEDS_SFA -> {
          MANDATORY_SURVEY
        }

        MoneySource.IPEDS_IC_AY -> {
          MANDATORY_SURVEY
        }

        MoneySource.COMMON_DATA_SET -> {
          VOLUNTARY_SELF_REPORT
        }

        MoneySource.SCORECARD -> {
          SCORECARD_TIERS[sourceVariable]
            ?: throw corruptValue(
              // BOTH halves of the key as the exception's own DATA: the tier is
              // a function of the pair, so a fixer needs the publisher as well
              // as the cell id, and neither may be left to be parsed back out
              // of prose.
              "source=[${source.value}] source_variable=[$sourceVariable]",
              "a tiered Scorecard source_variable (AssuranceTier.SCORECARD_TIERS)",
              location,
            )
        }
      }

    /**
     * The twenty-four Scorecard `source_variable` strings this corpus loads,
     * and the tier each one is on.
     *
     * Twenty-four strings for eighteen conceptual cells: `NPT4` and its five
     * income bands are each published twice, `_PUB` and `_PRIV`, and the loader
     * picks one per institution from `CONTROL`. The map is keyed on the LITERAL
     * string a row carries, so it needs all twenty-four.
     *
     * Sixteen of the eighteen cells are IPEDS, re-published: the Scorecard is
     * mostly a relay, and the college filed those with IPEDS. The two that are
     * not are `GRAD_DEBT_MDN` (NSLDS, the federal loan file) and
     * `MD_EARN_WNE_P10` (Treasury tax records) -- administrative records about
     * students, kept for another purpose entirely.
     *
     * Written as DATA and not as a `when`, because that is what it is: a
     * transcription of another publisher's own `SOURCE` column, pinned against
     * the committed `db/seed/scorecard/dictionary-variable-sources.csv`.
     *
     * `DEBT_MDN` is deliberately absent: this corpus does not load that column
     * ([ed.unicoach.college.ScorecardInstitutionColumns]), and a tier for a
     * variable no loader writes is a row with no reader.
     */
    val SCORECARD_TIERS: Map<String, AssuranceTier> =
      buildMap {
        // The eight `price_figures` cells, all IPEDS-sourced.
        put("TUITIONFEE_IN", MANDATORY_SURVEY)
        put("TUITIONFEE_OUT", MANDATORY_SURVEY)
        put("ROOMBOARD_ON", MANDATORY_SURVEY)
        put("ROOMBOARD_OFF", MANDATORY_SURVEY)
        put("BOOKSUPPLY", MANDATORY_SURVEY)
        put("OTHEREXPENSE_ON", MANDATORY_SURVEY)
        put("OTHEREXPENSE_OFF", MANDATORY_SURVEY)
        put("OTHEREXPENSE_FAM", MANDATORY_SURVEY)
        // The blended average cost, and the Pell share: IPEDS again.
        put("COSTT4_A", MANDATORY_SURVEY)
        put("PCTPELL", MANDATORY_SURVEY)
        // The net-price family, each cell published twice on CONTROL. Written
        // out, because the key IS the literal string a stored row carries: a
        // reader grepping `NPT43_PRIV` must find this map beside the CSV it is
        // transcribed from, not a loop that happens to generate it.
        put("NPT4_PUB", MANDATORY_SURVEY)
        put("NPT41_PUB", MANDATORY_SURVEY)
        put("NPT42_PUB", MANDATORY_SURVEY)
        put("NPT43_PUB", MANDATORY_SURVEY)
        put("NPT44_PUB", MANDATORY_SURVEY)
        put("NPT45_PUB", MANDATORY_SURVEY)
        put("NPT4_PRIV", MANDATORY_SURVEY)
        put("NPT41_PRIV", MANDATORY_SURVEY)
        put("NPT42_PRIV", MANDATORY_SURVEY)
        put("NPT43_PRIV", MANDATORY_SURVEY)
        put("NPT44_PRIV", MANDATORY_SURVEY)
        put("NPT45_PRIV", MANDATORY_SURVEY)
        // The two that are NOT the Scorecard relaying IPEDS.
        put("GRAD_DEBT_MDN", ADMINISTRATIVE_RECORD)
        put("MD_EARN_WNE_P10", ADMINISTRATIVE_RECORD)
      }
  }
}
