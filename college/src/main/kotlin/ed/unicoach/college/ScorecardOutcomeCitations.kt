package ed.unicoach.college

import ed.unicoach.db.models.CollegeMatch

/**
 * The sentence a coach says for each OUTCOME figure a search row carries, once
 * (RFC 226 / brief 0011 D47).
 *
 * Brief 0011 D47 is standing and binding: a figure a family can see carries
 * the publisher that filed it and the period it describes, all the way to the
 * sentence that speaks it. Three search figures -- undergraduate enrollment,
 * admission rate, completion rate -- are `colleges` columns that carried NO
 * stamp at all, and they are the three this run stamps. The Pell share is
 * deliberately NOT here: the search serves it from TWO sources at two
 * different vintages (RFC 226 §5), so one page-level sentence would be true
 * of some rows and false of others. Its stamp waits on the §5 fix.
 *
 * ## The publisher's own words, so the transcription can be checked
 *
 * Each [Citation.citedAs] below is a TRANSCRIPTION, and a transcription whose
 * source text is not committed beside it cannot be reviewed. The population in
 * each sentence is the publisher's own, from sheet `Institution_Data_Dictionary`
 * of `CollegeScorecardDataDictionary.xlsx`, columns `NAME OF DATA ELEMENT` and
 * `NOTES`, verbatim (the download itself -- URL, date, byte count -- is
 * recorded once in `db/seed/scorecard/FETCH-NOTES.md`, not a second time here):
 *
 * - `ADM_RATE` -- "Admission rate". The dictionary states no population note:
 *   it is one overall rate for the entering class, with no split by
 *   application round.
 * - `UGDS` -- "Enrollment of undergraduate certificate/degree-seeking
 *   students".
 * - `C150_4` -- "Completion rate for first-time, full-time students at
 *   four-year institutions (150% of expected time to completion)"; the note
 *   adds "degree/certificate-seeking", and that 150% of normal time "is
 *   typically 6 years" for a bachelor's.
 * - `PCTPELL` -- "Percentage of undergraduates who receive a Pell Grant"; the
 *   denominator is ALL undergraduates. Not stamped here, for the §5 reason
 *   above.
 *
 * Modelled on [ScoreBandCitation] (RFC 195 D-B): the publisher and the
 * population live INSIDE [Citation.citedAs], and the period is a NAMED member
 * ([Citation.cohortTerm]) interpolated into that sentence, exactly as
 * `ScoreBandCitation.COHORT_TERM` is -- so no bare numeric year reaches the
 * wire (the RFC 143 guard keeps its meaning), the sentence cannot contradict
 * its own period, and the pin test reads the period rather than cutting it out
 * of finished prose.
 *
 * The publisher's spoken name is stated HERE and not read from
 * `MoneySourceCopy.of(MoneySource.SCORECARD)`: that home is in `:service`,
 * which depends on this module, so this site cannot reach it -- and the
 * sentences cannot move there, because the two tools that emit `figure_sources`
 * are in `:college`. What the two disagreeing costs is one word of a spoken
 * attribution, never a number and never money, so the copy stays and says so;
 * [ScoreBandCitation] names the same publisher for the same reason.
 *
 * Lives in `:college`, NOT `:service`: the two tools that emit `figure_sources`
 * are in this module, which cannot see `:service` types.
 */
object ScorecardOutcomeCitations {
  /** The publisher, in the words a family hears. The ONE place this name is written down in `:college`. */
  private const val SCORECARD = "the U.S. Department of Education's College Scorecard"

  /**
   * The entering cohort `C150_4` describes, in the words a family hears. The
   * ONE place this term is written down; `ScorecardOutcomeCitationsTest` holds
   * it equal to the committed dictionary cell.
   */
  private const val COMPLETION_COHORT_TERM = "autumn 2018"

  // ONE constant per dictionary cell, never one per distinct STRING. The two
  // below hold the same text today only because the publisher happens to date
  // both figures to the same term; they are separate cells of
  // `dictionary-variable-sources.csv` and a snapshot may re-date one alone.
  // Folded into a single constant the fold would look like a tidy-up and would
  // still pass `ScorecardOutcomeCitationsTest` -- until that snapshot lands, and
  // then it would silently re-date a figure nobody edited.

  /** The entering cohort `ADM_RATE` describes, on [COMPLETION_COHORT_TERM]'s terms. */
  private const val ADMISSION_COHORT_TERM = "autumn 2024"

  /** The term `UGDS` counts its students in, on [COMPLETION_COHORT_TERM]'s terms. */
  private const val ENROLLMENT_COHORT_TERM = "autumn 2024"

  /**
   * One outcome figure, the sentence that stamps it, and the [CollegeMatch]
   * field that backs it ([present]).
   *
   * An ENUM, not a data class. The three citations are singletons and the set
   * is closed, so: a `copy()` re-pointing one citation's wire key at another
   * figure -- which minted the Pell share's key stamped with the completion
   * rate's population and period, the very mis-attribution `putFigureSources`
   * exists to forbid -- has no spelling left; a citation that is not one of
   * ours cannot be constructed
   * at all, so a renderer taking a `Citation` is taking a registered one; and a
   * `when` over a citation is EXHAUSTIVE, so a fourth figure is a compile error
   * at every site that routes on one rather than a runtime `else`.
   *
   * [variable] is the publisher's own column name: the dictionary row this
   * citation's period is pinned against.
   * [cohortTerm] is the period, named once and interpolated into [citedAs], so
   * a reader that needs the period reads it instead of substring-searching a
   * human sentence.
   * [citedAs] is the WHOLE sentence fragment a coach says -- the single source
   * of truth for the publisher and the population, so neither can be omitted or
   * contradicted by another field.
   *
   * [present] is part of the citation rather than a second list beside
   * [byWireKey]: one field accessor per citation keeps the closed set a single
   * enumeration, so a fifth figure is added in exactly one place.
   */
  enum class Citation(
    val wireKey: String,
    val variable: String,
    val cohortTerm: String,
    val citedAs: String,
    val present: (CollegeMatch) -> Boolean,
  ) {
    COMPLETION_RATE(
      wireKey = WIRE_COMPLETION_RATE_150PCT_4YR_SHARE,
      variable = ScorecardInstitutionColumns.C150_4,
      cohortTerm = COMPLETION_COHORT_TERM,
      citedAs =
        "the share of students who started full-time, for the first time, in $COMPLETION_COHORT_TERM and had " +
          "finished a degree at that school within six years, as $SCORECARD reports it",
      present = { it.completionRate150pct4yrShare != null },
    ),

    ADMISSION_RATE(
      wireKey = WIRE_ADMISSION_RATE_SHARE,
      variable = ScorecardInstitutionColumns.ADM_RATE,
      cohortTerm = ADMISSION_COHORT_TERM,
      citedAs =
        "the share of applicants that school admitted for the class entering in $ADMISSION_COHORT_TERM, as " +
          "$SCORECARD reports it -- one overall rate, not a rate for any single application round",
      present = { it.admissionRateShare != null },
    ),

    UNDERGRAD_ENROLLMENT(
      wireKey = WIRE_UNDERGRAD_ENROLLMENT_HEADCOUNT,
      variable = ScorecardInstitutionColumns.UGDS,
      cohortTerm = ENROLLMENT_COHORT_TERM,
      citedAs =
        "the number of undergraduates seeking a degree or certificate there in $ENROLLMENT_COHORT_TERM, as " +
          "$SCORECARD reports it",
      present = { it.undergradEnrollmentHeadcount != null },
    ),
    ;

    /**
     * The citation NAMED, and named by the thing every assertion about it talks
     * about: its wire key. Written by hand because an enum's default is the
     * entry name alone, and a failed `figure_sources` assertion is read against
     * the key on the wire.
     */
    override fun toString(): String = "$name($wireKey)"
  }

  /**
   * Every outcome citation, keyed by the figure's own wire key.
   *
   * Built with a REFUSAL rather than `associateBy`, which keeps the last of two
   * entries spelling one wire key and drops the other: two citations naming one
   * figure would silently become one stamp, and `presentIn` would lose a figure
   * no reader could see was missing.
   */
  val byWireKey: Map<String, Citation> =
    Citation.entries.fold(mutableMapOf<String, Citation>()) { keys, citation ->
      val previous = keys.put(citation.wireKey, citation)
      require(previous == null) {
        "one citation per outcome wire key: [$previous] and [$citation] both stamp [${citation.wireKey}]"
      }
      keys
    }

  /**
   * The citations for the figures [rows] actually carry: a figure no returned
   * row reports gets no stamp, on `source_years`' own precedent (RFC 226).
   *
   * This is the ANSWER -- which figures does this page stamp -- published so
   * that every surface that speaks the stamps reads ONE enumeration. The
   * payload renderer in `:college` and the fit lens's prose in `:service` used
   * to decide it separately, and the prose decided it by hand-listing all three
   * unconditionally, so a fourth citation would have reached the wire and not
   * the sentence.
   */
  fun presentIn(rows: List<CollegeMatch>): List<Citation> = inWireOrder(byWireKey.values.filter { rows.any(it.present) })

  /**
   * [citations] in the order the wire is written in, which is by
   * [Citation.wireKey].
   *
   * The ONE home of that order, read by [presentIn] and by `putFigureSources`
   * alike: a reader sees the same order from every surface, adding a fourth
   * figure cannot silently re-order the three that were already there (the
   * order a [byWireKey] traversal would give is the order the citations happen
   * to be declared in), and the renderer that PROMISES sorted keys imposes them
   * itself rather than trusting the list it was handed.
   */
  fun inWireOrder(citations: Collection<Citation>): List<Citation> = citations.sortedBy { it.wireKey }
}
