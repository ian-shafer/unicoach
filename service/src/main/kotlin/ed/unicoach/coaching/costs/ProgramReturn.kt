package ed.unicoach.coaching.costs

import ed.unicoach.college.ScorecardFieldOfStudyColumns
import ed.unicoach.common.util.AcademicYear
import ed.unicoach.common.util.CalendarYear
import ed.unicoach.db.dao.ProgramMoneyStatColumn
import ed.unicoach.db.dao.ProgramReturnStat
import ed.unicoach.db.dao.SubjectProgramMatch
import ed.unicoach.db.dao.programMoneyStatNaturalKey
import ed.unicoach.db.models.AbsenceStatus
import ed.unicoach.db.models.CipCode
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.FigureReading
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.IntendedSubjectName
import ed.unicoach.db.models.ProgramMoneyMeasure
import ed.unicoach.db.models.ValueBearingStatus
import org.slf4j.LoggerFactory

// The major answer's section (RFC 231): its payload vocabulary, and the
// assembly that turns rows already read on the caller's connection into it.
//
// Its own file, like every other section of this payload (ComparisonBasis,
// BorrowingAtGraduation, AidPolicyWire, BlendedFigureBasis): grouping rows,
// resolving college names, resolving registry provenance and typing silences
// are section-assembly work, not the read orchestrator's job.
// `CollegeCostService` keeps the three things that ARE its own -- which answer
// statuses arm the section, the two batched reads, and the connection they run
// on.
//
// FOUR LEVELS, AND ONE RULE AT EVERY ONE OF THEM: where we hold nothing
// usable, this section answers with a stated reason and never with silence and
// never with a crash. No matched program is one reason, a matched program we
// hold no figures for is a second, a measure we hold no row for is a third, and
// a stored status is spoken as itself. No two of them share a wire key or a
// basis code, because a family told "this school does not run that program"
// about a program it does run has been lied to by a gap in our data.
//
// AND NOTHING ON THIS PATH RAISES. A row we cannot stamp -- an unregistered
// `source_variable`, a measure its variable does not publish, a cohort the
// registry's sentence does not describe -- is a row we hold and cannot
// attribute, which is the third reason above and not an error: every one of
// them is a state the schema permits and OUR registry decides, so refusing one
// cost a family the whole cost answer over a single row. The operator hears it
// as a located WARN instead ([provenanceOfOrNull]).

/**
 * The section's own log, for the rows it declines to stamp.
 *
 * A file-level logger, on the `Routing.kt` / `StatusPages.kt` precedent, because
 * this file's subject is a set of top-level functions and payload types rather
 * than one class.
 */
private val programReturnLog = LoggerFactory.getLogger("ed.unicoach.coaching.costs.ProgramReturn")

/**
 * The provenance of one program figure (RFC 231): the registry entry the row's
 * `source_variable` resolved to, and the cohort years that entry states -- which
 * are the years the ROW pools, because this value exists only where the two
 * agree.
 *
 * The ENTRY rather than a copy of its fields, because the publisher, the
 * verbatim cohort cell, the dollar base and the measure are ONE fact about the
 * variable: pairing a debt measure with the earnings dollar base is then not a
 * mistake a caller can make, it is a value that cannot be spelled.
 *
 * THE SENTENCE AND THE YEARS AGREE, BY CONSTRUCTION, AND THE DISAGREEING STATE
 * CANNOT BE BUILT. [cohortSentence] is the publisher's verbatim cell out of the
 * registry; the ROW carries its own two years, and a re-pinned registry can
 * leave a stored row whose years that sentence does not describe. A figure
 * served beside a sentence about another cohort contradicts itself in front of
 * a family, so the row's years are compared in ONE place -- [ofAgreeingCohort],
 * the only door in, which answers null instead and leaves the caller rendering
 * the figure's own no-row arm. The row stays perfectly readable: a re-pin never
 * makes a stored figure unspeakable, it only stops us stamping it with the wrong
 * cohort.
 *
 * SO THE YEARS ARE READ OFF THE VARIABLE AND NOT STORED BESIDE IT. Two fields
 * that must equal two fields of a third are a second copy with nothing holding
 * it -- the state a runtime `require` used to reject, on a PUBLIC constructor
 * that threw for exactly the input the factory answers null for. One invariant
 * may not have two behaviours, and the throwing one may not be the public door:
 * the constructor is private, `@ConsistentCopyVisibility` closes `copy()` with
 * it, and the disagreeing pair is no longer a value anything can spell.
 *
 * The store's `source` column is deliberately not here: it says `scorecard` on
 * every row and is the file we ingested, not the publisher a family is told.
 */
@ConsistentCopyVisibility
data class ProgramFigureProvenance private constructor(
  val variable: ScorecardFieldOfStudyColumns.FieldOfStudyVariable,
) {
  /** The FIRST academic year this figure's cohort pools, as its start year -- the row's own, which the registry states. */
  val cohortFirstYear: AcademicYear get() = variable.cohortFirstYear

  /** The LAST academic year that cohort pools; never earlier than the first. */
  val cohortLastYear: AcademicYear get() = variable.cohortLastYear

  /** Who published the figure, in the publisher's own word for itself. */
  val publisher: String get() = variable.publisher

  /** The publisher's own cohort cell, verbatim -- the evidence for the two years and the dollar base. */
  val cohortSentence: String get() = variable.publisherCohortCell

  /**
   * The calendar year whose dollars the figure is in, where [variable]'s own
   * registry entry names one -- and null where it does not.
   *
   * WHICH variables name one is the registry's fact and is not restated here: a
   * later pin that names a dollar year for a debt column would leave a sentence
   * in this file wrong, and the registry's own KDoc calls today's absence "a
   * finding, not an omission".
   *
   * A [CalendarYear] beside the two [AcademicYear] members above, because a
   * dollar base and a cohort year are different facts that share one
   * representation: while this was an `Int`, wrapping it as an [AcademicYear]
   * and speaking a dollar base as a cohort type-checked.
   */
  val inflationBaseYear: CalendarYear? get() = variable.inflationBaseYear

  companion object {
    /**
     * This provenance, or NULL when the registry's cohort cell would describe a
     * cohort the row is not about (RFC 231).
     *
     * The ONLY door in -- the constructor is private -- and null rather than a
     * refusal: a registry re-pin is OUR edit, and it must not make a stored row
     * unreadable or take a whole cost answer down with it. The figure takes its
     * own no-row arm and says so, and there is no second door that answers the
     * same input with an exception.
     *
     * [cohortFirstYear] and [cohortLastYear] are the ROW's, and this is the one
     * place they are compared with the registry's.
     */
    fun ofAgreeingCohort(
      variable: ScorecardFieldOfStudyColumns.FieldOfStudyVariable,
      cohortFirstYear: AcademicYear,
      cohortLastYear: AcademicYear,
    ): ProgramFigureProvenance? =
      if (variable.cohortFirstYear == cohortFirstYear && variable.cohortLastYear == cohortLastYear) {
        ProgramFigureProvenance(variable)
      } else {
        null
      }
  }
}

/**
 * One figure of a program return (RFC 231), at ONE measure. Sealed and
 * exhaustive over every status `figure_statuses` holds plus the one state the
 * store cannot hold at all -- no row -- so a renderer can fold no two silences
 * into one, and a value rides only on [Valued].
 *
 * SEVEN ARMS AND NOT THREE. `program_money_stats.status` is
 * `TEXT NOT NULL REFERENCES figure_statuses(slug)`, so the WHOLE vocabulary is
 * legal in the table and the only narrowing CHECK on it explicitly contemplates
 * `imputed_by_publisher`. Speaking three of the six and refusing the rest meant
 * one legal row killed the entire cost answer for that student -- a figure we
 * hold, thrown away, plus every figure beside it. Each status is now spoken as
 * itself.
 */
sealed interface ProgramReturnFigure {
  /** Which figure this is -- present on every arm, including the one with no row behind it. */
  val measure: ProgramMoneyMeasure

  /**
   * A figure we HOLD a usable row for: its provenance rides with it, and its
   * [status] is the arm's own, so no renderer can stamp one silence with
   * another's code.
   *
   * [measure] is the VARIABLE's own measure, never a second value beside it
   * that could disagree with the publisher the figure is stamped with.
   */
  sealed interface Held : ProgramReturnFigure {
    val provenance: ProgramFigureProvenance
    val status: FigureStatus

    override val measure: ProgramMoneyMeasure get() = provenance.variable.measure
  }

  /** A held figure that carries a number -- the two value-bearing statuses, and only those. */
  sealed interface Valued : Held {
    val amountUsd: Int
  }

  /** The publisher published a number, and [amountUsd] carries it. */
  data class Reported(
    override val amountUsd: Int,
    override val provenance: ProgramFigureProvenance,
  ) : Valued {
    override val status: FigureStatus get() = FigureStatus.REPORTED
  }

  /**
   * The publisher estimated the figure rather than receiving it, and shows it
   * anyway; [amountUsd] carries the publisher's own estimate.
   *
   * The field-of-study file we ingest today emits no imputed cell -- but the
   * table accepts one, so the answer says which kind of number it is instead of
   * refusing the row.
   */
  data class ImputedByPublisher(
    override val amountUsd: Int,
    override val provenance: ProgramFigureProvenance,
  ) : Valued {
    override val status: FigureStatus get() = FigureStatus.IMPUTED_BY_PUBLISHER
  }

  /** The publisher withheld the figure for privacy (its `PS` token); no number. */
  data class Suppressed(
    override val provenance: ProgramFigureProvenance,
  ) : Held {
    override val status: FigureStatus get() = FigureStatus.SUPPRESSED_BY_PUBLISHER
  }

  /** The school reported nothing for this cell (the publisher's `NA` token); no number. */
  data class NotReported(
    override val provenance: ProgramFigureProvenance,
  ) : Held {
    override val status: FigureStatus get() = FigureStatus.NOT_REPORTED_BY_INSTITUTION
  }

  /** The source says the cell cannot apply to this program at this school; no number. */
  data class NotApplicable(
    override val provenance: ProgramFigureProvenance,
  ) : Held {
    override val status: FigureStatus get() = FigureStatus.NOT_APPLICABLE
  }

  /** OURS: the publisher carries the cell and we have not gathered it; no number. */
  data class NotCollectedByUs(
    override val provenance: ProgramFigureProvenance,
  ) : Held {
    override val status: FigureStatus get() = FigureStatus.NOT_COLLECTED_BY_US
  }

  /**
   * We hold NO usable row for this measure of this program: the store has none,
   * or the one it has cannot be stamped -- a cohort its own sentence does not
   * describe (see [ProgramFigureProvenance]), a `source_variable` the registry
   * does not carry, or a measure that variable does not publish.
   *
   * ONE arm for all four, because they are one fact to a FAMILY: we hold no
   * published figure for that measure. The three that are our own gap are told
   * apart where the distinction can be acted on -- the operator log -- and not
   * on a wire a family reads.
   *
   * Its own arm, and every measure gets an entry, because a SHORT `figures`
   * array is an absence with no reason: a family reading two figures where three
   * were promised cannot tell which is missing or why, and neither can the
   * model. There is no provenance here on purpose -- the only honest thing to
   * say is that we hold nothing.
   */
  data class NoFigure(
    override val measure: ProgramMoneyMeasure,
  ) : ProgramReturnFigure
}

/**
 * One matched bachelor's program at one college (RFC 231), keyed by the CIP code
 * the publisher publishes at.
 *
 * The publisher's grain IS the program, so the payload mirrors it: a college
 * running two programs a subject's prefixes both reach carries two entries, each
 * with its own code and its own figures. Sealed on whether we hold any figure
 * for it at all, because "this school runs that program and the publisher gave
 * us nothing for it" is a different sentence from every other absence in this
 * section.
 */
sealed interface ProgramReturnProgram {
  /** The program's own CIP code, as `college_programs` stores it. */
  val cipCode: CipCode

  /**
   * We hold at least one figure for this program, and [figures] carries an
   * entry for EVERY measure.
   *
   * THE SHAPE IS THE TYPE'S, NOT A GUARD'S (RFC 231). [ofHeldFigures] TOTALS the
   * measures itself and is the only door in -- the constructor is private and
   * `@ConsistentCopyVisibility` closes `copy()` with it -- so a short array, a
   * mis-ordered one and an all-absent one are not values a call site can build,
   * and a measure we hold no row for has to say so
   * ([ProgramReturnFigure.NoFigure]) instead of vanishing. The two runtime
   * `require`s this replaces stated the same rule in a file that makes
   * non-emptiness a PARAMETER everywhere else.
   */
  @ConsistentCopyVisibility
  data class WithFigures private constructor(
    override val cipCode: CipCode,
    val figures: List<ProgramReturnFigure>,
  ) : ProgramReturnProgram {
    companion object {
      /**
       * One matched program's entry, from the HELD figures we could stamp for it:
       * every measure gets an entry, in [ProgramMoneyMeasure] declaration order,
       * and a program we could stamp nothing for is [NoPublishedFigures] rather
       * than a figures array with no figure in it.
       *
       * It answers with the SEALED type, and the choice between the two arms
       * lives here, because "we hold nothing for this program" is a fact about
       * the figures handed in -- read at the one place that knows them, rather
       * than by an `if` at each call site that may forget it. Its home is this
       * arm's companion because a private constructor is private to ITS OWN
       * class in Kotlin, so the factory that uses it cannot live one level out.
       */
      fun ofHeldFigures(
        cipCode: CipCode,
        held: Map<ProgramMoneyMeasure, ProgramReturnFigure.Held>,
      ): ProgramReturnProgram =
        if (held.isEmpty()) {
          NoPublishedFigures(cipCode)
        } else {
          WithFigures(
            cipCode = cipCode,
            figures = ProgramMoneyMeasure.entries.map { measure -> held[measure] ?: ProgramReturnFigure.NoFigure(measure) },
          )
        }
    }
  }

  /**
   * The school RUNS this program and we hold no published figure for any of its
   * measures.
   *
   * Distinct from [ProgramReturnCollege.NoProgram] because the two are opposite
   * statements about a school, and distinct from an all-[ProgramReturnFigure.NoFigure]
   * list because there is nothing per-measure to say: the gap is the program's.
   */
  data class NoPublishedFigures(
    override val cipCode: CipCode,
  ) : ProgramReturnProgram
}

/**
 * One college's slice of one subject's program return (RFC 231). Sealed:
 * either the subject matched at least one bachelor's program here and those
 * programs follow, or none did and [NoProgram] says so -- a college entry with a
 * name and no programs, never an empty programs array.
 */
sealed interface ProgramReturnCollege {
  val collegeId: CollegeId
  val name: String

  /**
   * The subject matched one or more bachelor's programs here.
   *
   * The head program is its own parameter because an EMPTY program list is
   * [NoProgram] said without its basis -- `"programs": []`, an absence with no
   * reason -- and that state should not be CONSTRUCTIBLE, rather than held off
   * by one `if` at one call site.
   */
  data class WithPrograms(
    override val collegeId: CollegeId,
    override val name: String,
    val headProgram: ProgramReturnProgram,
    val tailPrograms: List<ProgramReturnProgram> = emptyList(),
  ) : ProgramReturnCollege {
    /** The programs in payload order, head first -- what every renderer reads. */
    val programs: List<ProgramReturnProgram> get() = listOf(headProgram) + tailPrograms
  }

  /** No `college_programs` row at bachelor's level matched the subject's prefixes here. */
  data class NoProgram(
    override val collegeId: CollegeId,
    override val name: String,
  ) : ProgramReturnCollege
}

/**
 * One subject's whole `program_return` entry (RFC 231): the stored answer's
 * slug and resolved name, and one [ProgramReturnCollege] per selected college
 * in the selection's own order.
 *
 * The head college is its own parameter for the reason
 * [ProgramReturnCollege.WithPrograms] holds its head program that way:
 * `"colleges": []` is an absence with no reason, and the section's rule is
 * ABSENT, never empty -- at EVERY level, by construction rather than by one `if`
 * at one call site.
 */
data class ProgramReturnSubject(
  val subjectSlug: String,
  val subjectName: String,
  val headCollege: ProgramReturnCollege,
  val tailColleges: List<ProgramReturnCollege> = emptyList(),
) {
  /** The colleges in the selection's own order, head first -- what every renderer reads. */
  val colleges: List<ProgramReturnCollege> get() = listOf(headCollege) + tailColleges
}

/**
 * The whole `program_return` section (RFC 231): one entry per stored subject, in
 * the stored answer's own order.
 *
 * Head and tail again, for the last level of the same rule: the section is
 * ABSENT (a null field, no key on the wire) or it carries a subject, and
 * `"program_return": []` is not a shape this payload can be asked to render.
 */
data class ProgramReturn(
  val headSubject: ProgramReturnSubject,
  val tailSubjects: List<ProgramReturnSubject> = emptyList(),
) {
  /** The subject entries in the stored answer's own order, head first. */
  val subjects: List<ProgramReturnSubject> get() = listOf(headSubject) + tailSubjects
}

/**
 * The section, assembled from rows already read: per stored subject, per
 * selected college, per matched program, the published program figures.
 *
 * TWO reads in, and each answers a different question. [matches] is the PROGRAM
 * question -- which (subject, college, CIP) triples run a matching bachelor's
 * program -- and it is what decides between "this school does not run that
 * program" and "we hold no figures for it". [rows] is the MONEY question. A
 * section built from the money rows alone would speak a gap in our data as a
 * statement about a school's offering, which is the one thing this section may
 * never do.
 *
 * NULL when there is nothing to answer about -- no selected college -- because
 * the section's convention is ABSENT, never empty: a subject entry whose
 * `colleges` array is `[]` would be a second spelling of the absence that
 * [CollegeCostService.programReturnFor] already spells `null`. (An `answered`
 * status always carries at least one subject: `IntendedSubjectAnswer`'s own
 * invariant.)
 *
 * [nameOf] is the SELECTION's college lookup, not a map rebuilt here: a
 * selected id with no `colleges` row is an invariant break that must fail
 * naming the student and the college, exactly as every other refusal on this
 * path does.
 */
internal fun programReturnOf(
  subjects: List<IntendedSubjectName>,
  collegeIds: List<CollegeId>,
  nameOf: (CollegeId) -> String,
  matches: Set<SubjectProgramMatch>,
  rows: List<ProgramReturnStat>,
): ProgramReturn? {
  if (collegeIds.isEmpty()) return null
  // Keyed on the DAO's own named triple, never `subject to college to code`: a
  // positional key's parts are told apart only by the line that built it, and
  // this is the key the data-loss defect was found in.
  val byMatch = rows.groupBy { it.match }
  // THE OTHER HALF OF A DISAGREEMENT BETWEEN THE TWO READS. The program read
  // and the money read are two statements on one read-committed connection, so
  // a wholesale ingest rebuild between them can leave a money row whose match
  // the program read did not return. The assembly below enumerates `matches`,
  // so such a row is a figure we HOLD and serve to nobody -- and every other
  // loss on this path is a located WARN, which is what this one now is too. The
  // program read stays authoritative: a college the money rows alone vouch for
  // would be answered with programs the read that owns that question did not
  // find.
  (byMatch.keys - matches).forEach { orphan ->
    programReturnLog.warn(
      "held program figures at [{}] name no matched bachelor's program: the program read and the money " +
        "read disagree, so the figures are not served",
      programMoneyStatNaturalKey(orphan.collegeId, orphan.subjectSlug, orphan.cipCode),
    )
  }
  val programsByCollege = matches.groupBy { it.collegeId }
  val entries = subjects.map { subject -> subjectEntryOf(subject, collegeIds, nameOf, programsByCollege, byMatch) }
  // An `answered` status always carries a subject, so this is the type keeping
  // the empty section unconstructible rather than a case the read expects.
  val head = entries.firstOrNull() ?: return null
  return ProgramReturn(head, entries.drop(1))
}

/**
 * One subject's whole entry: one college entry per selected college, in the
 * selection's own order.
 *
 * [collegeIds] is non-empty -- [programReturnOf] answers with no section at all
 * below one college -- which is what gives the entry its head.
 */
private fun subjectEntryOf(
  subject: IntendedSubjectName,
  collegeIds: List<CollegeId>,
  nameOf: (CollegeId) -> String,
  programsByCollege: Map<CollegeId, List<SubjectProgramMatch>>,
  byMatch: Map<SubjectProgramMatch, List<ProgramReturnStat>>,
): ProgramReturnSubject {
  val colleges =
    collegeIds.map { collegeId ->
      collegeEntryOf(
        collegeId = collegeId,
        name = nameOf(collegeId),
        matches = programsByCollege[collegeId].orEmpty().filter { it.subjectSlug == subject.slug },
        byMatch = byMatch,
      )
    }
  return ProgramReturnSubject(
    subjectSlug = subject.slug,
    subjectName = subject.name,
    headCollege = colleges.first(),
    tailColleges = colleges.drop(1),
  )
}

/**
 * One college's slice: its matched programs in CIP order, or the no-program
 * basis.
 *
 * Sorted by CIP code, because the read that produced [matches] is a SET and a
 * family comparing two schools must not see one school's two majors swap places
 * between calls.
 */
private fun collegeEntryOf(
  collegeId: CollegeId,
  name: String,
  matches: List<SubjectProgramMatch>,
  byMatch: Map<SubjectProgramMatch, List<ProgramReturnStat>>,
): ProgramReturnCollege {
  val programs = matches.sortedBy { it.cipCode.value }.map { programEntryOf(it, byMatch[it].orEmpty()) }
  val head = programs.firstOrNull() ?: return ProgramReturnCollege.NoProgram(collegeId, name)
  return ProgramReturnCollege.WithPrograms(collegeId, name, head, programs.drop(1))
}

/**
 * One matched program's entry: every measure, or the no-figures basis.
 *
 * [rows] is at most one row per measure -- `ProgramMoneyDao.programMoneyBySubject`
 * chooses the most recent cohort IN THE QUERY -- and a row we cannot stamp is
 * dropped here ([provenanceOfOrNull]), so a program can arrive with rows and
 * still hold nothing speakable. WHICH of the two arms that leaves is
 * [ProgramReturnProgram.WithFigures.ofHeldFigures]'s answer and not this
 * function's: the totalling of the measure set and the choice of
 * [ProgramReturnProgram.NoPublishedFigures] belong to the type that owns the
 * shape.
 */
private fun programEntryOf(
  match: SubjectProgramMatch,
  rows: List<ProgramReturnStat>,
): ProgramReturnProgram =
  ProgramReturnProgram.WithFigures.ofHeldFigures(
    cipCode = match.cipCode,
    held = rows.mapNotNull(::heldFigureOfOrNull).associateBy { it.measure },
  )

/**
 * One row's provenance, resolved through [ScorecardFieldOfStudyColumns] by
 * `source_variable` -- the schema's own comment directs a reader of a row there
 * for the publisher, the cohort sentence and the dollar base.
 *
 * NULL, NEVER A REFUSAL, on all four ways a stored row can fail to be stamped:
 * a `source` that is not the publisher whose file this registry describes, a
 * `source_variable` the registry does not carry, a row whose stored `measure`
 * is not the one that variable publishes, and a row whose years the registry's
 * cohort cell would not describe. The figure takes its own no-row arm and says
 * so; every figure beside it still answers, and the cost answer still returns.
 *
 * THE ENVELOPE IS AUDITED BEFORE THE INNER LOOKUP. `source` is the row's own
 * statement of whose file it came out of, and
 * [ScorecardFieldOfStudyColumns] describes ONE publisher's field-of-study file,
 * so resolving `source_variable` there without looking at `source` is a lookup
 * performed in a registry the row never claimed to belong to. The column
 * PERMITS another publisher: it is the `money_source` domain, whose
 * `money_source_check` admits `('ipeds_sfa', 'ipeds_ic_ay', 'scorecard',
 * 'common_data_set')`, and `create-program-money-stats.sql` adds no CHECK
 * narrowing it -- so a variable name shared with another publisher's file would
 * otherwise be spoken to a family under the Scorecard's publisher, its cohort
 * sentence and its dollar base.
 *
 * None of the four is corruption we may refuse on. `source_variable` is `TEXT
 * NOT NULL` under a non-empty CHECK alone, so a fourth ingested Scorecard
 * column is a SCHEMA-LEGAL value this registry has not learned yet -- a
 * widening, not a broken row -- a variable/measure disagreement is the
 * registry disagreeing with the store, which is our edit exactly as a re-pin
 * is, and a row of another publisher's file is a legal stored state of a column
 * whose domain admits four publishers. Refusing any of them took the whole
 * `college_cost_profile` answer down for that student over one row we could not
 * attribute.
 *
 * The OPERATOR hears about all four, by located `WARN`: a family is owed a
 * sentence, and we are owed the cell. They are not the same absence to us, and
 * an ingest that started writing a column this registry cannot attribute must
 * not be silent. The cohort disagreement is the LOUDEST of them, not the
 * quietest: a re-pin degrades every stored row of that variable at once, so a
 * silent arm there would blank a figure for every student with the only signal
 * being a family asking why.
 */
private fun provenanceOfOrNull(stat: ProgramReturnStat): ProgramFigureProvenance? {
  if (stat.source != ScorecardFieldOfStudyColumns.SOURCE) {
    programReturnLog.warn(
      "source=[{}] is not the publisher whose field-of-study file this registry describes at [{}]: " +
        "the figure takes its no-figure arm rather than being stamped with a Scorecard publisher",
      stat.source.value,
      stat.locationOf(ProgramMoneyStatColumn.SOURCE),
    )
    return null
  }
  val variable = ScorecardFieldOfStudyColumns.ofOrNull(stat.sourceVariable)
  if (variable == null) {
    programReturnLog.warn(
      "no field-of-study registry entry carries source_variable=[{}] at [{}]: " +
        "the figure takes its no-figure arm, and the answer is served without it",
      stat.sourceVariable,
      stat.locationOf(ProgramMoneyStatColumn.SOURCE_VARIABLE),
    )
    return null
  }
  if (variable.measure != stat.measure) {
    programReturnLog.warn(
      "source_variable=[{}] publishes measure=[{}] and the row stores measure=[{}] at [{}]: " +
        "the figure takes its no-figure arm rather than being stamped with the other measure's publisher",
      variable.sourceVariable,
      variable.measure.value,
      stat.measure.value,
      stat.locationOf(ProgramMoneyStatColumn.MEASURE),
    )
    return null
  }
  val provenance = ProgramFigureProvenance.ofAgreeingCohort(variable, stat.cohortFirstYear, stat.cohortLastYear)
  if (provenance == null) {
    programReturnLog.warn(
      "source_variable=[{}] publishes cohort [{}]..[{}] and the row pools [{}]..[{}] at [{}]: " +
        "the figure takes its no-figure arm rather than being served beside a sentence about another cohort",
      variable.sourceVariable,
      variable.cohortFirstYear.label,
      variable.cohortLastYear.label,
      stat.cohortFirstYear.label,
      stat.cohortLastYear.label,
      stat.locationOf(ProgramMoneyStatColumn.COHORT_LAST_YEAR),
    )
  }
  return provenance
}

/**
 * One stored row into one HELD figure, or null where the row cannot be stamped
 * at all ([provenanceOfOrNull]).
 *
 * THE SEAM A NEW STATUS MUST PASS. Both `when`s below are exhaustive with no
 * `else` over the two halves of the `figure_statuses` partition, so a seventh
 * status fails to COMPILE here and has to be spoken before it can be stored --
 * rather than reaching a family as a crash, as three of the six once did.
 * `CollegeCostServiceTest`'s `every legal figure status is spoken as itself`
 * drives the same seam from `FigureStatus.entries`, one status at a time, so it
 * fails loudly there too rather than quietly finding a crash path.
 */
private fun heldFigureOfOrNull(stat: ProgramReturnStat): ProgramReturnFigure.Held? {
  val provenance = provenanceOfOrNull(stat) ?: return null
  return when (val reading = stat.reading) {
    is FigureReading.Present -> {
      when (reading.bearing) {
        ValueBearingStatus.REPORTED -> ProgramReturnFigure.Reported(reading.value, provenance)
        ValueBearingStatus.IMPUTED_BY_PUBLISHER -> ProgramReturnFigure.ImputedByPublisher(reading.value, provenance)
      }
    }

    is FigureReading.Absent -> {
      when (reading.absence) {
        AbsenceStatus.SUPPRESSED_BY_PUBLISHER -> ProgramReturnFigure.Suppressed(provenance)
        AbsenceStatus.NOT_REPORTED_BY_INSTITUTION -> ProgramReturnFigure.NotReported(provenance)
        AbsenceStatus.NOT_APPLICABLE -> ProgramReturnFigure.NotApplicable(provenance)
        AbsenceStatus.NOT_COLLECTED_BY_US -> ProgramReturnFigure.NotCollectedByUs(provenance)
      }
    }
  }
}
