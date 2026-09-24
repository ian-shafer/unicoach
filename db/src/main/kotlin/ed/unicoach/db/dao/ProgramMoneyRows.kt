package ed.unicoach.db.dao

import ed.unicoach.common.util.AcademicYear
import ed.unicoach.db.models.CipCode
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.FigureReading
import ed.unicoach.db.models.MoneySource
import ed.unicoach.db.models.ProgramMoneyMeasure
import java.util.UUID

/**
 * A `college_programs` row named the way the PUBLISHER's field-of-study file
 * names it (RFC 218): the institution's IPEDS unit id, the CIP code as a
 * zero-padded string, and the raw `CREDLEV`.
 *
 * The publisher's key, not ours, because the loader has a CSV row in hand and
 * needs the program id: resolving the college first and the program second is
 * two lookups where the ingest asks one question. `cip_code` is TEXT for the
 * reason `college_programs` stores it as TEXT -- `'0901'` through an integer
 * becomes `'901'`.
 */
data class ProgramKey(
  val ipedsUnitId: Int,
  val cipCode: String,
  val credentialLevel: Int,
)

/**
 * One `program_money_stats` row (RFC 218): what one PROGRAM returns, as the
 * College Scorecard field-of-study file publishes it.
 *
 * The row points at the program rather than restating its three-column grain:
 * `college_programs` already holds (college_id, cip_code, credential_level)
 * under a unique index, and a second copy here would be a second place for them
 * to disagree.
 *
 * [reading] closes the amount and its status into ONE field, on
 * [ed.unicoach.db.models.NewCohortMoneyStat]'s precedent: the invalid pairings
 * -- a null amount with `reported`, an amount with `suppressed_by_publisher` --
 * do not compile, and `program_money_stats_amount_iff_status_check` is the
 * backstop for any writer that bypasses this type.
 *
 * [cohortFirstYear] and [cohortLastYear] are the first and last
 * academic year the publisher's cohort POOLS, each named by its start year, and
 * they are EQUAL when a figure pools nothing. They belong on the row because
 * they are what the figure is about; everything else the publisher says about
 * the variable -- which calendar years earnings were measured in, the inflation
 * base -- is a fact about the VARIABLE and lives once, in
 * `ScorecardFieldOfStudyColumns`, reached through [sourceVariable].
 */
data class NewProgramMoneyStat(
  val collegeProgramId: UUID,
  val measure: ProgramMoneyMeasure,
  /** The FIRST academic year the publisher's cohort pools, as its start year. */
  val cohortFirstYear: AcademicYear,
  /** The LAST academic year the publisher's cohort pools; never earlier than the first. */
  val cohortLastYear: AcademicYear,
  /** Whole US dollars and the status as ONE reading (RFC 158 D3): the invalid pairings do not compile. */
  val reading: FigureReading<Int>,
  /** The publisher, as the owned enumeration -- never a free string. */
  val source: MoneySource,
  /** The publisher's own variable name (`EARN_MDN_4YR`, ...), never a column of ours. */
  val sourceVariable: String,
)

/**
 * One `program_money_stats` row read for the major answer (RFC 231): what a
 * stored subject's matched program returns at one college, at credential level
 * 3 (bachelor's).
 *
 * The read's own shape rather than [NewProgramMoneyStat] reused: the write row
 * is keyed by `collegeProgramId` and is about the fill, while this row is
 * keyed by the [SubjectProgramMatch] the answer renders.
 *
 * [source] rides on the row as the ENVELOPE the inner lookup is audited
 * against, and not as the publisher a family is told: that comes from
 * `ScorecardFieldOfStudyColumns` through [sourceVariable] (RFC 231). The column
 * is the `money_source` DOMAIN, whose `money_source_check` admits four
 * publishers and no per-table CHECK narrows here, so a row claiming another
 * publisher's file is a LEGAL stored state -- and a reader that resolved
 * [sourceVariable] in one publisher's registry without looking at [source]
 * would speak another publisher's column as Scorecard's.
 *
 * [cipCode] rides on the row because the PROGRAM is what the publisher's grain
 * is, and the answer is keyed by it: a college can run two bachelor's programs
 * a subject's prefixes both reach, and dropping the code here is how two
 * different programs whose figures coincide became one.
 *
 * [reading] keeps the amount and the status as ONE value, exactly as the write
 * row does: the invalid pairings do not compile.
 */
data class ProgramReturnStat(
  val collegeId: CollegeId,
  /** The subject whose prefix set matched the program -- the answer's own slug. */
  val subjectSlug: String,
  /** The matched program's own CIP code, the publisher's grain and the answer's key. */
  val cipCode: CipCode,
  val measure: ProgramMoneyMeasure,
  /** The FIRST academic year the publisher's cohort pools, as its start year. */
  val cohortFirstYear: AcademicYear,
  /** The LAST academic year the publisher's cohort pools; never earlier than the first. */
  val cohortLastYear: AcademicYear,
  val reading: FigureReading<Int>,
  /** WHOSE file this row came out of -- the row's own statement, checked before any publisher-specific registry reads it. */
  val source: MoneySource,
  /** The publisher's own variable name, the key into `ScorecardFieldOfStudyColumns`. */
  val sourceVariable: String,
) {
  /**
   * WHICH matched program this row's figure is about -- the SAME value the
   * program read returns, so the two reads' answers are joined on one type
   * rather than on a tuple each side spells its own way.
   */
  val match: SubjectProgramMatch get() = SubjectProgramMatch(subjectSlug, collegeId, cipCode)

  /**
   * WHERE this row sits, for a refusal in [column] -- the row's own address, so
   * every module that refuses one of these cells says it the same way, and
   * every field the row HAS rides in it (the program and the measure included).
   */
  fun locationOf(column: ProgramMoneyStatColumn): ProgramMoneyStatLocation =
    ProgramMoneyStatLocation(column, collegeId, subjectSlug, cipCode, measure)
}

/**
 * One matched bachelor's program (RFC 231): the stored subject whose prefixes
 * reached it, the college that runs it, and its own CIP code -- the PROGRAM
 * question's answer, which says nothing about whether a figure is stored for
 * it.
 *
 * [cipCode] IS the program's identity here, and not a decoration: at a FIXED
 * credential level `college_programs_unique_idx` makes (college_id, cip_code)
 * unique, and the whole major answer is read at level 3. Keyed without it, two
 * different programs a subject's prefixes both reach collapse into one -- and
 * when their figures happen to coincide, nothing downstream can tell that
 * happened.
 *
 * A named triple rather than a `Triple`: positional members are told apart only
 * by order, and a caller reading `first` as a college compiles.
 *
 * [cipCode] carries its own type ([CipCode]) and [subjectSlug] does not, yet --
 * the two are adjacent `TEXT` facts told apart by nothing at a call site, and
 * typing ONE of the pair is already enough to make the transposition a compile
 * error. The slug's own type waits for the slice that can carry it through
 * `subjects`, the search index and the intended-subjects answer in one edit.
 */
data class SubjectProgramMatch(
  val subjectSlug: String,
  val collegeId: CollegeId,
  val cipCode: CipCode,
)

/**
 * The `program_money_stats` cells a refusal on this path can name, in the
 * store's own spelling.
 *
 * An enumeration and not a `String` parameter, because the set is closed by the
 * schema: a misspelt literal compiles today and ships an operator-facing
 * refusal naming a column that does not exist -- the one line an operator
 * greps for.
 */
enum class ProgramMoneyStatColumn(
  val columnName: String,
) {
  MEASURE("measure"),
  STATUS("status"),

  /** The row's own statement of WHOSE file it came from -- the envelope a publisher-specific registry is read under. */
  SOURCE("source"),
  SOURCE_VARIABLE("source_variable"),
  AMOUNT_USD("amount_usd"),

  /** The row's own cohort, which a registry re-pin can leave the registry's sentence not describing. */
  COHORT_LAST_YEAR("cohort_last_year"),
}

/** The table every refusal on this path addresses, in ONE spelling. */
const val PROGRAM_MONEY_STATS_TABLE = "program_money_stats"

/**
 * One cell of that table, in the form every refusal on this path prints --
 * including the ones that carry no natural key of their own
 * ([ed.unicoach.db.models.FigureReading.of]'s `column`), so the two cannot
 * drift into two spellings of one cell.
 */
fun programMoneyStatColumn(column: ProgramMoneyStatColumn): String = "$PROGRAM_MONEY_STATS_TABLE.[${column.columnName}]"

/**
 * WHERE one `program_money_stats` cell sits, as an operator hunts it: a school
 * and a major (and the measure, where the caller knows it), never the
 * `college_program_id` the table keys on -- the reads that produce these rows
 * join through `college_programs`, and the operator is looking for a school
 * and a major.
 *
 * A TYPE and not a pre-formatted sentence, because every part of it is already
 * a typed value at the site that builds one: a caller that wants only the major
 * does not parse it back out of prose, and a logger one module out hands the
 * object to its `{}` placeholder -- so the sentence is authored once, here, and
 * rendered once, at the edge, and only if the event is emitted.
 *
 * ONE author for that sentence, reached from both `:db` and `:service`: a
 * format with two spellings is an operator grepping one of them and missing
 * the other.
 */
data class ProgramMoneyStatLocation(
  val column: ProgramMoneyStatColumn,
  val collegeId: CollegeId,
  val subjectSlug: String,
  val cipCode: CipCode,
  val measure: ProgramMoneyMeasure? = null,
) {
  /**
   * The natural-key half, for the one seam that takes a `String`:
   * [ed.unicoach.db.models.FigureReading.of]'s `naturalKey`. Read off
   * [programMoneyStatNaturalKey] rather than spelled again here.
   */
  val naturalKey: String get() = programMoneyStatNaturalKey(collegeId, subjectSlug, cipCode, measure)

  override fun toString(): String = "${programMoneyStatColumn(column)} ($naturalKey)"
}

/**
 * The natural key half of [ProgramMoneyStatLocation], for a caller that needs it
 * without a column.
 *
 * [cipCode] is REQUIRED, unlike [measure]: one school and one major can hold two
 * matched programs, so an address without the program names both rows and sends
 * an operator to the wrong one.
 */
fun programMoneyStatNaturalKey(
  collegeId: CollegeId,
  subjectSlug: String,
  cipCode: CipCode,
  measure: ProgramMoneyMeasure? = null,
): String =
  "college_id=[${collegeId.value}] subject=[$subjectSlug] cip_code=[${cipCode.value}]" +
    (measure?.let { " measure=[${it.value}]" } ?: "")
