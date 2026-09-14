package ed.unicoach.db.models

import ed.unicoach.common.util.AcademicYear
import java.time.LocalDate
import java.util.UUID

/**
 * Surface id for an [AdmissionTestStateProgramme] row. Beside the model rather
 * than in its own file because `admission_test_state_programmes` is seeded
 * reference data with no id-keyed read path (the [AdmissionTestAdministrationId]
 * precedent, which is the same shape one table along).
 */
@JvmInline
value class AdmissionTestStateProgrammeId(
  val value: UUID,
) : Id {
  override val asString get() = value.toString()
}

/**
 * Who must sit the school-day test, which is a different question from who
 * CHOOSES it ([TestChooser]) -- the two axes the first draft of this table
 * fused into one column and could not then express.
 *
 * There is no `NONE`. Absence of a row means we did not read a source, never
 * that the state runs nothing: asserting the negative needs a state page saying
 * so, which these pages mostly do not (RFC 208 section 2). An enum arm no row
 * can reach is an invitation to write one.
 */
enum class SchoolDayParticipation(
  /** The stored code, and the `CHECK IN ('required', 'offered')` the schema admits. */
  val value: String,
) {
  /** Every student in the named grades sits it. */
  REQUIRED(value = "required"),

  /** The state runs it and pays attention to it; the student may sit it. */
  OFFERED(value = "offered"),
  ;

  companion object {
    /** The stored code back to the member, or null -- a miss is row corruption for the caller to locate, never a guess. */
    fun fromValue(value: String): SchoolDayParticipation? = entries.find { it.value == value }
  }
}

/**
 * WHO picks which admission test the school day is given over to.
 *
 * Arkansas is why [STUDENT] and [DISTRICT] both exist rather than one "not the
 * state" arm: its 2025 law funds a MENU and the state chose none of them, while
 * Tennessee requires the sitting and lets the DISTRICT pick and South Carolina
 * lets the STUDENT pick. A family hears those as three different sentences, and
 * the English for each lives in `:service`, not here.
 */
enum class TestChooser(
  /** The stored code, and the `CHECK IN ('state', 'district', 'student')` the schema admits. */
  val value: String,
) {
  STATE(value = "state"),
  DISTRICT(value = "district"),
  STUDENT(value = "student"),
  ;

  companion object {
    /** The stored code back to the member, or null -- a miss is row corruption for the caller to locate, never a guess. */
    fun fromValue(value: String): TestChooser? = entries.find { it.value == value }
  }
}

/**
 * WHOSE school year [AdmissionTestStateProgramme.schoolYear] is.
 *
 * Most state pages describe a standing programme and name no year at all. The
 * alternative to this column is inventing a year on the page's behalf, which is
 * the defect brief 0010 exists to refuse -- so a row whose page named none
 * carries the year we READ it in and says that it did. The coach speaks the
 * difference: "the page names the 2026-27 year" is a different sentence from
 * "we read this in the 2026-27 year", and only one of them is the state's claim.
 */
enum class SchoolYearBasis(
  /** The stored code, and the `CHECK IN ('stated_by_source', 'reading_year')` the schema admits. */
  val value: String,
) {
  /** The state's own page named the school year. */
  STATED_BY_SOURCE(value = "stated_by_source"),

  /** The page named none, so the row carries the academic year of its own `checked_on`. */
  READING_YEAR(value = "reading_year"),
  ;

  companion object {
    /** The stored code back to the member, or null -- a miss is row corruption for the caller to locate, never a guess. */
    fun fromValue(value: String): SchoolYearBasis? = entries.find { it.value == value }
  }
}

/**
 * The state's choice of test, and the chooser it left it to -- ONE value,
 * because neither half is a claim any source supports on its own (the
 * [ScoreRelease] precedent RFC 197 set).
 *
 * The schema says the same thing in SQL
 * (`admission_test_state_programmes_test_pairing_check`:
 * `(test IS NOT NULL) = (test_chooser = 'state')`). Modelling the pair as two
 * independent nullables would let the Kotlin side hold a state the database
 * rejects: a district-chosen row carrying `sat` says the state chose after all,
 * and a state-chosen row carrying no test tells a family to sit something
 * unnamed. Here neither typechecks.
 */
sealed interface SchoolDayTestChoice {
  /** The chooser as the column stores it, so a reader never re-derives it from the arm. */
  val chooser: TestChooser

  /** The state chose the test, and names it. */
  data class StateChose(
    val test: AdmissionTest,
  ) : SchoolDayTestChoice {
    override val chooser: TestChooser get() = TestChooser.STATE
  }

  /**
   * The state chose NO test: the district or the student does. [chooser] is
   * never [TestChooser.STATE] -- that arm is [StateChose], which names a test.
   */
  data class ChosenLocally(
    override val chooser: TestChooser,
  ) : SchoolDayTestChoice {
    init {
      require(chooser != TestChooser.STATE) {
        "[$chooser] is the state choosing, which names a test: use StateChose"
      }
    }
  }
}

/**
 * The grade or grades the school-day sitting happens in -- almost always 11
 * alone, and Minnesota's statute covers grade 11 OR 12.
 *
 * BOTH ends travel together or neither does, which is why this is a type and
 * not two nullable `Int`s on the row: the schema's
 * `admission_test_state_programmes_grade_pairing_check` refuses a half-open
 * range in the database, and a half-open range in memory is the same defect one
 * layer up. A null [AdmissionTestStateProgramme.grades] means the source named
 * no grade and is NEVER completed with a guess -- "your junior year" is the
 * sentence a wrong guess here breaks.
 */
data class GradeRange(
  val lowest: Int,
  val highest: Int,
) {
  init {
    // The same bounds the schema states
    // (`admission_test_state_programmes_grade_range_check`). A loader builds one
    // straight from a CSV cell, and without this the bad pair escapes the
    // seed's located-defect contract and surfaces as an anonymous insert
    // failure a thousand rows later (the `AcademicYear` precedent).
    require(lowest in LOWEST_HIGH_SCHOOL_GRADE..HIGHEST_HIGH_SCHOOL_GRADE) {
      "[$lowest] is not a high-school grade: the range is [$LOWEST_HIGH_SCHOOL_GRADE..$HIGHEST_HIGH_SCHOOL_GRADE]"
    }
    require(highest in LOWEST_HIGH_SCHOOL_GRADE..HIGHEST_HIGH_SCHOOL_GRADE) {
      "[$highest] is not a high-school grade: the range is [$LOWEST_HIGH_SCHOOL_GRADE..$HIGHEST_HIGH_SCHOOL_GRADE]"
    }
    require(lowest <= highest) { "grade range [$lowest..$highest] is inverted" }
  }

  companion object {
    /** The grades an admission-test programme can name, and the bounds the schema's CHECK states. */
    const val LOWEST_HIGH_SCHOOL_GRADE = 9
    const val HIGHEST_HIGH_SCHOOL_GRADE = 12
  }
}

/**
 * One US jurisdiction's state-run, school-day SAT or ACT programme for one
 * school year, from the `admission_test_state_programmes` reference table (RFC
 * 208): whether the state requires or merely offers the sitting inside the
 * school day, who chooses which test, which test where the state chose one, the
 * grades it happens in, whether the state pays, and the page a human read it
 * from.
 *
 * IT IS A FACT ABOUT THE STATE AND NEVER ABOUT THE SCHOOL. Whether one named
 * high school runs its test day, and on what date, is not here and cannot be
 * inferred from here: that is the single school-level question the coach may
 * ask (brief 0010 D10), it gates nothing, and "I don't know" is a complete
 * answer.
 *
 * IT IS APPEND-ONLY HISTORY. A re-read ADDS a row for the new school year, so
 * last year's answer stays true about last year; Illinois moved from the SAT to
 * the ACT and Kentucky from the ACT to the SAT within two cycles, and both are
 * worth keeping rather than erasing. The read is therefore the NEWEST row per
 * state ([AdmissionTestStateProgrammeDao.listNewestPerState]) and never "the"
 * row for a state.
 *
 * Hand-typed from state agency pages, on the [AdmissionTestAdministration]
 * pattern, which is why every row carries its OWN provenance
 * ([sourceUrl]/[archiveUrl], [checkedOn]) and its OWN [expiresOn] rather than
 * the table carrying one.
 *
 * NO `FigureStatus` and no assurance tier, for the reason RFC 197 states: that
 * vocabulary belongs to the canonical MONEY tables, and borrowing it here would
 * claim a provenance model this data does not have.
 */
data class AdmissionTestStateProgramme(
  override val id: AdmissionTestStateProgrammeId,
  /** The USPS code, a `us_states(usps_code)` foreign key. A jurisdiction's NAME is the codebook's, never this row's. */
  val state: String,
  /**
   * The school year this row describes, under the `academic_year` domain (0087,
   * RFC 170 D14): 2026 IS the 2026-27 school year, and the `2026-27` label is
   * [AcademicYear]'s and is never stored.
   */
  val schoolYear: AcademicYear,
  /** Whose year [schoolYear] is -- the state's stated one, or the year we read the page in ([SchoolYearBasis]). */
  val schoolYearBasis: SchoolYearBasis,
  val participation: SchoolDayParticipation,
  /**
   * Whether the state requires every public high school to HOLD the sitting,
   * which is a different fact from whether a student must sit it. Virginia is
   * why the column exists: its law makes every school board run the SAT School
   * Day and then says no student must participate, so [participation] alone
   * reduces the strongest sentence in the corpus to "offered".
   *
   * NULL IS UNKNOWN AND NEVER FALSE. We did not read this for most states, and
   * "the state does not require schools to hold it" is a claim needing its own
   * page. No reader and no sentence may turn this null into that claim.
   */
  val schoolMustOffer: Boolean?,
  /** The state's test and the chooser it left it to, as one value ([SchoolDayTestChoice]). */
  val choice: SchoolDayTestChoice,
  /** The grades the sitting happens in, or null where the source named none -- never completed with a guess. */
  val grades: GradeRange?,
  /**
   * Whether the state pays, read from a page and NEVER inferred from
   * [participation]. Null means the state's own page does not say -- the
   * commonest case -- and is spoken as "we do not know who pays", never as
   * "the sitting is free". Nothing here records a price.
   */
  val stateFunded: Boolean?,
  /** The state page the row's core claims were read from. A fact with no citation is what this table exists to prevent. */
  val sourceUrl: String,
  /** An archive capture OF THAT SAME PAGE, where one was needed (Arizona and New Hampshire return 403 to every client we have). */
  val archiveUrl: String?,
  /** The day a human read [sourceUrl]. A day in the world, so a `LocalDate` and not an instant. */
  val checkedOn: LocalDate,
  /**
   * The day this reading stops being believed. It is a fact about the READING
   * and not a promise about the world: past it the coach says we hold no current
   * record for that state, and a committed row whose state has no newer row
   * within [HandTypedReferenceData.REFRESH_LEAD] of it fails the build (D20).
   */
  val expiresOn: LocalDate,
) : Identifiable<AdmissionTestStateProgrammeId>

/**
 * A row to write. Separate from [AdmissionTestStateProgramme] because the id is
 * the database's (`uuidv7()`), exactly as every other `New*` model in this
 * package works.
 */
data class NewAdmissionTestStateProgramme(
  val state: String,
  val schoolYear: AcademicYear,
  val schoolYearBasis: SchoolYearBasis,
  val participation: SchoolDayParticipation,
  val schoolMustOffer: Boolean?,
  val choice: SchoolDayTestChoice,
  val grades: GradeRange?,
  val stateFunded: Boolean?,
  val sourceUrl: String,
  val archiveUrl: String?,
  val checkedOn: LocalDate,
  val expiresOn: LocalDate,
)
