package ed.unicoach.college

import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.models.CollegeMatch
import kotlin.reflect.KProperty1

// The seven citations' periods, as VALUES. Top-level and private rather than
// members of [ScorecardOutcomeCitations], deliberately: the object's own
// `byWireKey` reads `Citation.entries`, so a period read off the object from an
// enum entry's constructor would be a class-initialisation cycle -- the enum
// would ask for the object, the object would ask for the entries, and the JVM
// would hand back a half-built enum rather than fail. Nothing else about them
// is file-level: they are this registry's, and nothing outside this file can
// see them.

/** The entering cohort `C150_4` describes. */
private val COMPLETION_COHORT = ScorecardOutcomeCitations.CohortPeriod.EnteringTerm(2018)

// ONE value per dictionary cell, never one per distinct PERIOD. The pairs
// below hold the same period today only because the publisher happens to date
// both figures alike; they are separate cells of
// `dictionary-variable-sources.csv` and a snapshot may re-date one alone.
// Folded into a single value the fold would look like a tidy-up and would
// still pass `ScorecardOutcomeCitationsTest` -- until that snapshot lands, and
// then it would silently re-date a figure nobody edited.

/** The entering cohort `ADM_RATE` describes, on [COMPLETION_COHORT]'s terms. */
private val ADMISSION_COHORT = ScorecardOutcomeCitations.CohortPeriod.EnteringTerm(2024)

/** The term `UGDS` counts its students in, on [COMPLETION_COHORT]'s terms. */
private val ENROLLMENT_COHORT = ScorecardOutcomeCitations.CohortPeriod.EnteringTerm(2024)

// RFC 242's four cells. The publisher dates the three `OMAWDP8_*` variables to
// an ENTERING ACADEMIC YEAR ("AY 2016-17 cohort"), not to a fall term, so their
// spoken form is the academic year and not an autumn. Saying "autumn 2016" for
// a cohort that entered across a July-June year would misstate the population,
// which is the exact class of error RFC 183 exists to prevent. That difference
// is now carried by the TYPE rather than by the wording of a string: the two
// shapes are two arms of [ScorecardOutcomeCitations.CohortPeriod], each of
// which says itself, so no surface can speak one as the other and the pin test
// compares periods as values instead of scraping years out of finished prose.

/** The entering cohort `OMAWDP8_ALL` describes. */
private val COMPLETION_8YR_COHORT = ScorecardOutcomeCitations.CohortPeriod.EnteringAcademicYear(AcademicYear(2016))

/** The entering cohort `OMAWDP8_PELL_ALL` describes, on [COMPLETION_8YR_COHORT]'s terms. */
private val COMPLETION_8YR_PELL_COHORT = ScorecardOutcomeCitations.CohortPeriod.EnteringAcademicYear(AcademicYear(2016))

/** The entering cohort `OMAWDP8_NOPELL_ALL` describes, on [COMPLETION_8YR_COHORT]'s terms. */
private val COMPLETION_8YR_NON_PELL_COHORT =
  ScorecardOutcomeCitations.CohortPeriod.EnteringAcademicYear(AcademicYear(2016))

/**
 * The entering cohort `TRANS_4` describes. Its dictionary cell is `C150_4`'s
 * own, because it counts that same six-year cohort -- and it is still its own
 * value, because it is its own cell.
 */
private val TRANSFER_OUT_COHORT = ScorecardOutcomeCitations.CohortPeriod.EnteringTerm(2018)

/**
 * The sentence a coach says for each OUTCOME figure a search row carries, once
 * (RFC 226 / brief 0011 D47).
 *
 * Brief 0011 D47 is standing and binding: a figure a family can see carries
 * the publisher that filed it and the period it describes, all the way to the
 * sentence that speaks it. RFC 226 stamped three search figures --
 * undergraduate enrollment, admission rate, six-year completion rate --
 * `colleges` columns that carried NO stamp at all. RFC 242 adds four more: the
 * eight-year award-completion figure, its Pell and non-Pell arms, and the
 * four-year transfer-out share. Seven in all.
 *
 * The institution's Pell SHARE (`PCTPELL`) is still deliberately NOT here, and
 * that exclusion is UNCHANGED by the two Pell arms below: the search serves
 * `PCTPELL` from TWO sources at two different vintages (RFC 226 §5), so one
 * page-level sentence would be true of some rows and false of others. Its stamp
 * waits on the §5 fix. `OMAWDP8_PELL_ALL` is a different figure from a
 * different single source -- a completion rate for Pell recipients, not the
 * share of undergraduates who receive Pell -- and has one dictionary cell.
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
 * - `OMAWDP8_ALL` -- "Percentage of all student receiving an award within 8
 *   years of entry" (the dictionary's own wording, typo included); the NOTES
 *   cell is empty. "All" is the publisher's own union of its four sibling
 *   elements in the same `completion` category -- `OMAWDP8_FTFT` "full-time,
 *   first-time", `OMAWDP8_PTFT` "part-time, first-time", `OMAWDP8_FTNFT`
 *   "full-time, not first-time" and `OMAWDP8_PTNFT` "part-time, not
 *   first-time" -- which is where the population clause below comes from.
 * - `OMAWDP8_PELL_ALL` -- "Percentage of all Pell Grant recipient students
 *   receiving an award within 8 years of entry".
 * - `OMAWDP8_NOPELL_ALL` -- "Percentage of all non-Pell Grant recipient
 *   students receiving an award within 8 years of entry".
 * - `TRANS_4` -- "Transfer rate for first-time, full-time students at
 *   four-year institutions (within 150% of expected time to completion/6
 *   years)"; the note adds "degree/certificate-seeking" and that the
 *   denominator is "the corresponding completion rate cohort", i.e. `C150_4`'s
 *   own -- which is why its sentence states that population rather than
 *   borrowing the eight-year one.
 *
 * ## What no sentence here may say
 *
 * Two completion measures over two populations and two entering cohorts are
 * two measures. No `citedAs` compares them, and none narrates a gap between
 * the Pell and non-Pell arms: measured over the pinned snapshot, the Pell
 * figure is the LOWER of the two at only 65.8% of institutions, so "Pell
 * students finish less often" is wrong about one school in three. The relation
 * BETWEEN figures is not a per-figure sentence's to carry; it lives in the
 * coach prompt, which is quantified over whatever figures arrive.
 *
 * Modelled on [ScoreBandCitation] (RFC 195 D-B): the publisher and the
 * population live INSIDE [Citation.citedAs], and the period is a NAMED member
 * ([Citation.cohort]) spoken into that sentence, exactly as
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
   * The period ONE citation describes, in the two shapes the publisher's
   * dictionary states it in: an entering fall TERM, and a full entering
   * ACADEMIC YEAR.
   *
   * A CLOSED type, not a string, because the difference between the two is
   * load-bearing and used to be carried only by the WORDING of a `String`. A
   * cohort that arrived across a July-June year did not arrive in an autumn,
   * and saying so misstates the population -- the class of error RFC 183 found
   * two Scorecard stamps wrong by two years over. Typed, a third shape is a
   * compile error at every surface that speaks one.
   *
   * [spoken] is the ONE place a period becomes prose, so the words a family
   * hears have one author: they used to be written here AND rebuilt by the pin
   * test, which then scraped the finished sentence for the year it had just
   * formatted.
   */
  sealed interface CohortPeriod {
    /** The words a family hears, interpolated into [Citation.citedAs]. */
    val spoken: String

    /** The one calendar year that period's sentence may contain. */
    val statedYear: Int

    /** An entering fall term (`Fall 2018`), said as an autumn. */
    data class EnteringTerm(
      val autumnYear: Int,
    ) : CohortPeriod {
      override val spoken: String get() = "autumn $autumnYear"

      override val statedYear: Int get() = autumnYear
    }

    /** A full entering academic year (`AY 2016-17 cohort`), said as that academic year and NEVER as an autumn. */
    data class EnteringAcademicYear(
      val year: AcademicYear,
    ) : CohortPeriod {
      override val spoken: String get() = "the ${year.label} academic year"

      override val statedYear: Int get() = year.firstCalendarYear
    }
  }

  /**
   * One outcome figure, the sentence that stamps it, and the [CollegeMatch]
   * field that backs it ([figureIn]).
   *
   * An ENUM, not a data class. The seven citations are singletons and the set
   * is closed, so: a `copy()` re-pointing one citation's wire key at another
   * figure -- which minted the Pell share's key stamped with the completion
   * rate's population and period, the very mis-attribution `putFigureSources`
   * exists to forbid -- has no spelling left; a citation that is not one of
   * ours cannot be constructed
   * at all, so a renderer taking a `Citation` is taking a registered one; and a
   * `when` over a citation is EXHAUSTIVE, so an eighth figure is a compile
   * error at every site that routes on one rather than a runtime `else`. RFC
   * 242's four arrived exactly that way.
   *
   * [variable] is the publisher's own column name: the dictionary row this
   * citation's period is pinned against.
   * [cohort] is the period, named once as a VALUE and spoken into [citedAs]
   * through [CohortPeriod.spoken], so a reader that needs the period reads it
   * instead of substring-searching a human sentence -- and the pin test
   * compares two periods rather than two strings it built itself.
   * [citedAs] is the WHOLE sentence fragment a coach says -- the single source
   * of truth for the publisher and the population, so neither can be omitted or
   * contradicted by another field.
   *
   * [figureIn] is part of the citation rather than a second list beside
   * [byWireKey]: one field accessor per citation keeps the closed set a single
   * enumeration, so an eighth figure is added in exactly one place.
   *
   * [figureIn] reads the VALUE, not merely whether there is one. A figure's
   * wire key and the field behind it used to be paired here AND again by hand
   * in `matchObject` and in the fit lens's row line, so a row could carry one
   * figure's number under another figure's key and another figure's sentence --
   * the exact mis-attribution `putFigureSources` exists to forbid, reintroduced
   * one level down at the number. One accessor, read by every surface that
   * writes or prints the figure, so that pairing has one spelling.
   */
  enum class Citation(
    val wireKey: String,
    val variable: String,
    val cohort: CohortPeriod,
    val citedAs: String,
    val figureIn: KProperty1<CollegeMatch, Number?>,
  ) {
    COMPLETION_RATE(
      wireKey = WIRE_COMPLETION_RATE_150PCT_4YR_SHARE,
      variable = ScorecardInstitutionColumns.C150_4,
      cohort = COMPLETION_COHORT,
      citedAs =
        "the share of students who started full-time, for the first time, in ${COMPLETION_COHORT.spoken} and had " +
          "finished a degree at that school within six years, as $SCORECARD reports it",
      figureIn = CollegeMatch::completionRate150pct4yrShare,
    ),

    ADMISSION_RATE(
      wireKey = WIRE_ADMISSION_RATE_SHARE,
      variable = ScorecardInstitutionColumns.ADM_RATE,
      cohort = ADMISSION_COHORT,
      citedAs =
        "the share of applicants that school admitted for the class entering in ${ADMISSION_COHORT.spoken}, as " +
          "$SCORECARD reports it -- one overall rate, not a rate for any single application round",
      figureIn = CollegeMatch::admissionRateShare,
    ),

    UNDERGRAD_ENROLLMENT(
      wireKey = WIRE_UNDERGRAD_ENROLLMENT_HEADCOUNT,
      variable = ScorecardInstitutionColumns.UGDS,
      cohort = ENROLLMENT_COHORT,
      citedAs =
        "the number of undergraduates seeking a degree or certificate there in ${ENROLLMENT_COHORT.spoken}, as " +
          "$SCORECARD reports it",
      figureIn = CollegeMatch::undergradEnrollmentHeadcount,
    ),

    COMPLETION_RATE_8YR(
      wireKey = WIRE_COMPLETION_RATE_8YR_SHARE,
      variable = ScorecardInstitutionColumns.OMAWDP8_ALL,
      cohort = COMPLETION_8YR_COHORT,
      citedAs =
        "the share of ALL students who entered that school in ${COMPLETION_8YR_COHORT.spoken} -- full-time and " +
          "part-time, new to college and transferring in -- who had been awarded a degree or certificate " +
          "within eight years, as $SCORECARD reports it",
      figureIn = CollegeMatch::completionRate8yrShare,
    ),

    // The two arms restate their own period in full rather than leaning on
    // "the same figure as above": they are separate dictionary cells, so a
    // snapshot may re-date one alone and the word "same" would then be a lie
    // that no test could see. Neither sentence mentions the other arm.
    COMPLETION_RATE_8YR_PELL(
      wireKey = WIRE_COMPLETION_RATE_8YR_PELL_SHARE,
      variable = ScorecardInstitutionColumns.OMAWDP8_PELL_ALL,
      cohort = COMPLETION_8YR_PELL_COHORT,
      citedAs =
        "the share of the students who entered that school in ${COMPLETION_8YR_PELL_COHORT.spoken} and received " +
          "a federal Pell Grant who had been awarded a degree or certificate within eight years, as " +
          "$SCORECARD reports it -- a group the publisher counted, not a forecast for any one student",
      figureIn = CollegeMatch::completionRate8yrPellShare,
    ),

    COMPLETION_RATE_8YR_NON_PELL(
      wireKey = WIRE_COMPLETION_RATE_8YR_NON_PELL_SHARE,
      variable = ScorecardInstitutionColumns.OMAWDP8_NOPELL_ALL,
      cohort = COMPLETION_8YR_NON_PELL_COHORT,
      citedAs =
        "the share of the students who entered that school in ${COMPLETION_8YR_NON_PELL_COHORT.spoken} and did " +
          "not receive a federal Pell Grant who had been awarded a degree or certificate within eight " +
          "years, as $SCORECARD reports it -- a group the publisher counted, not a forecast for any one " +
          "student",
      figureIn = CollegeMatch::completionRate8yrNonPellShare,
    ),

    TRANSFER_OUT_RATE(
      wireKey = WIRE_TRANSFER_OUT_RATE_4YR_SHARE,
      variable = ScorecardInstitutionColumns.TRANS_4,
      cohort = TRANSFER_OUT_COHORT,
      citedAs =
        "the share of students who started full-time, for the first time, in ${TRANSFER_OUT_COHORT.spoken} and " +
          "transferred to another school instead of finishing at that one, as $SCORECARD reports it -- " +
          "counted out of the same six-year group as the six-year completion rate, and leaving is not the " +
          "same as not finishing",
      figureIn = CollegeMatch::transferOutRate4yrShare,
    ),
    ;

    /**
     * Whether [row] reports this figure at all -- absence is what goes
     * unstamped, and it is DERIVED from [figureIn] rather than stated beside
     * it: a figure is present exactly when its own number is.
     */
    fun present(row: CollegeMatch): Boolean = figureIn(row) != null

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
   * the sentence. RFC 242 added four; neither surface was edited for them.
   */
  fun presentIn(rows: List<CollegeMatch>): List<Citation> = inWireOrder(byWireKey.values.filter { citation -> rows.any(citation::present) })

  /**
   * [citations] in the order the wire is written in, which is by
   * [Citation.wireKey].
   *
   * The ONE home of that order, read by [presentIn] and by `putFigureSources`
   * alike: a reader sees the same order from every surface, adding an eighth
   * figure cannot silently re-order the seven that were already there (the
   * order a [byWireKey] traversal would give is the order the citations happen
   * to be declared in), and the renderer that PROMISES sorted keys imposes them
   * itself rather than trusting the list it was handed.
   */
  fun inWireOrder(citations: Collection<Citation>): List<Citation> = citations.sortedBy { it.wireKey }
}
