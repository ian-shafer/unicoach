package ed.unicoach.web.render

import ed.unicoach.coaching.admissions.CdsCitation
import ed.unicoach.coaching.admissions.MeritAidWire
import ed.unicoach.coaching.admissions.MeritPractice
import ed.unicoach.coaching.costs.ArrangementCost
import ed.unicoach.coaching.costs.BorrowingCoverage
import ed.unicoach.coaching.costs.BorrowingWire
import ed.unicoach.coaching.costs.CollegeControl
import ed.unicoach.coaching.costs.CollegeCost
import ed.unicoach.coaching.costs.CollegeCostProfile
import ed.unicoach.coaching.costs.CollegeResidencyBasis
import ed.unicoach.coaching.costs.CollegeSubjectReturn
import ed.unicoach.coaching.costs.ComparedTuition
import ed.unicoach.coaching.costs.ComparisonBasis
import ed.unicoach.coaching.costs.ComponentRole
import ed.unicoach.coaching.costs.CostField
import ed.unicoach.coaching.costs.CostLine
import ed.unicoach.coaching.costs.LineOrigin
import ed.unicoach.coaching.costs.LoanRow
import ed.unicoach.coaching.costs.MoneyBasis
import ed.unicoach.coaching.costs.NetPrice
import ed.unicoach.coaching.costs.OwedAverage
import ed.unicoach.coaching.costs.ProgramReturnCollege
import ed.unicoach.coaching.costs.ProgramReturnCopy
import ed.unicoach.coaching.costs.ResidencyBasis
import ed.unicoach.coaching.costs.SingleSchoolBasis
import ed.unicoach.coaching.costs.SpokenAssurance
import ed.unicoach.coaching.costs.TuitionApplicable
import ed.unicoach.coaching.costs.WithheldReason
import ed.unicoach.coaching.costs.applicableTuitionFor
import ed.unicoach.coaching.costs.canonical.AssuranceTierCopy
import ed.unicoach.coaching.costs.canonical.AssuranceTierGrouping
import ed.unicoach.coaching.costs.canonical.CoveredFigure
import ed.unicoach.coaching.costs.canonical.FigureIdentity
import ed.unicoach.coaching.costs.canonical.FigureStatusCopy
import ed.unicoach.coaching.costs.canonical.FilingLink
import ed.unicoach.coaching.costs.canonical.MeritMeasure
import ed.unicoach.coaching.costs.canonical.MoneySourceCopy
import ed.unicoach.coaching.costs.canonical.SpokenTierGroup
import ed.unicoach.coaching.costs.spokenLabel
import ed.unicoach.coaching.testing.AutomaticAwardAnswer
import ed.unicoach.coaching.testing.CorruptAwardResidencyBasisException
import ed.unicoach.coaching.testing.NationalMeritPublishedAnswer
import ed.unicoach.coaching.testing.PublishedPackage
import ed.unicoach.common.money.WholeDollars
import ed.unicoach.common.util.PublishedGpa
import ed.unicoach.common.util.UsDate
import ed.unicoach.common.util.WholeCount
import ed.unicoach.db.models.ApplyDeadline
import ed.unicoach.db.models.AutomaticAward
import ed.unicoach.db.models.AutomaticAwardBand
import ed.unicoach.db.models.AwardGuarantee
import ed.unicoach.db.models.BandScoreAxis
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.FirstChoiceRule
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.LivingArrangement
import ed.unicoach.db.models.MoneySource
import ed.unicoach.db.models.NationalMeritAward
import ed.unicoach.db.models.NationalMeritClaim
import ed.unicoach.db.models.QualifyingStanding
import ed.unicoach.db.models.ResidencyTierBasis
import ed.unicoach.family.ClaimView
import ed.unicoach.web.REPORT_CONNECT_PATH
import ed.unicoach.web.auth.CsrfToken
import ed.unicoach.web.auth.REGISTER_PATH
import ed.unicoach.web.auth.SIGN_IN_PATH
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import kotlinx.html.ButtonType
import kotlinx.html.FlowContent
import kotlinx.html.TBODY
import kotlinx.html.THEAD
import kotlinx.html.TR
import kotlinx.html.ThScope
import kotlinx.html.a
import kotlinx.html.aside
import kotlinx.html.button
import kotlinx.html.caption
import kotlinx.html.dd
import kotlinx.html.div
import kotlinx.html.dl
import kotlinx.html.dt
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.h3
import kotlinx.html.li
import kotlinx.html.p
import kotlinx.html.section
import kotlinx.html.span
import kotlinx.html.table
import kotlinx.html.tbody
import kotlinx.html.td
import kotlinx.html.th
import kotlinx.html.thead
import kotlinx.html.tr
import kotlinx.html.ul
import java.time.LocalDate
import ed.unicoach.db.models.ResidencyBasis as PublishedResidency

// The Family Cost Report (RFC 155): the parent-facing page behind a student's
// share link. It renders the SAME computation the coach reads in chat
// (CollegeCostProfile) as HTML, through the shared siteLayout, and it renders
// nothing the student did not already have.
//
// Three rules govern every line below, and they are the reason this file is
// long where a table would be short:
//
//  - NO STUDENT IDENTITY (D-F). No name, no email, no id. The link is not proof
//    of who sent it, so the page never asserts who did. It does state the
//    family's income band in dollars, because a net price whose band is unstated
//    is the dishonesty the whole cost slice exists against.
//  - NO UPGRADE CUE (D-G). CollegeCostProfile.precisionOffersFor() is an
//    in-chat prompt for the STUDENT to answer a question. A logged-out parent
//    cannot answer it, so this file never calls it -- only the honest half, the
//    labelled statement of what is missing, survives here. RFC 254 adds ONE
//    offer after the report: the reader's own account. It asks the reader for
//    nothing about the student, which is the line D-G still draws.
//  - A MISSING PART IS A LABELLED BLANK. Never a zero, never a neighbour's
//    number, and never a total summed from a partial set (that rule is
//    ArrangementCost's own -- this file only prints what it computes).
//
// The money vocabulary is RFC 141's throughout: tuition and fees, housing and
// food, the published price, a financial aid offer. Never "room and board",
// "sticker price", "award", or "without need". The SENTENCES are the cost
// domain's own (ComparisonBasis, SingleSchoolBasis, MoneyBasis) -- this file
// authors no money copy, so a parent can never be shown a caveat the coach
// would have worded differently.

/** The `<title>` suffix and the page's own heading; no student is named in either (D-F). */
private const val PAGE_TITLE = "Cost report"

/** The heading marker a test asserts on, and the page's honest claim of whose list this is. */
private const val REPORT_HEADING = "Your student's college list"

/** The tier block's own heading -- page chrome, not money copy: the sentences under it are the domain's (RFC 191). */
private const val KINDS_HEADING = "What kind of numbers these are"

/**
 * The heading over one school's published automatic-scholarship grid (RFC 239),
 * scoped by [gridHeadingOf] to the students the school published it for.
 *
 * "Scholarships" and not "awards": RFC 141's vocabulary is this page's
 * throughout, and the one sentence on the page that says "automatic award" is
 * the standing one below, which is the corpus's own name for the thing.
 */
private const val AWARD_GRID_HEADING = "Automatic scholarships this school publishes"

/**
 * WHAT KIND OF NUMBER a published grid is, said ONCE for the page (RFC 191) and
 * never per school -- the same rule the assurance-tier sentences beside it obey.
 *
 * It is the one thing a family most needs said about this block: the school
 * published a table, and a table is not an offer anybody has made them.
 *
 * "SCHOLARSHIP" AND NOT "AWARD", although RFC 239's prose wrote the sentence with
 * the second word. This page may not say "award" at all (RFC 141's vocabulary,
 * and the sweep in `CostReportPageTest` that executes it), because that word
 * makes published money sound granted -- which is the exact confusion THIS
 * sentence exists to prevent. A school's own band names and its own published
 * words are quoted verbatim and are a different thing: RFC 141 governs our
 * vocabulary, not a quotation of theirs.
 */
private const val AWARD_KIND_SENTENCE = "An automatic scholarship is published by the school and is not a financial aid offer."

/** A `gpa_only_grid` school in one sentence: it pays automatically, and a test score does not move what it pays. */
private const val NO_SCORE_EFFECT_LINE = "A test score does not change what this school's automatic scholarships pay."

/** The published cell that asks for no test score -- the tier a student who submits none lands in, at a school where a score IS money. */
private const val NO_SCORE_ROW_LABEL = "no test score"

/**
 * The column headers of a published grid (glance/03), each chosen under the
 * page's "award" ban ([AWARD_KIND_SENTENCE]): the school's own band names and
 * words are quoted in the cells, and none of OUR words above them says it.
 *
 * "Scholarship" is also the term over a National Merit row's own name
 * ([meritFactsOf]): both label the school's name for its scholarship.
 */
private const val SCHOLARSHIP_LABEL = "Scholarship"
private const val TEST_SCORE_COLUMN = "Test score"
private const val GPA_COLUMN = "GPA"

/**
 * The pays header, decided with its cells by [paysColumnOf]: "a year" is in the
 * header only when every cell under it is a dollar figure, so the unit never
 * claims a school's own words ("tuition and fees") are an amount per year.
 */
private const val PAYS_A_YEAR_COLUMN = "Pays a year"
private const val PAYS_COLUMN = "Pays"

/**
 * A grid cell in a column some other band of the same grid fills: this band's
 * school printed nothing there. Said, never left empty or dashed, so a phone
 * card never reads "GPA:" with nothing after it and the row never mis-aligns.
 */
private const val NONE_PUBLISHED = "none published"

/**
 * The heading over one school's published National Merit scholarships (RFC
 * 247). "Scholarships" and not "awards", for [AWARD_GRID_HEADING]'s reason.
 */
private const val NATIONAL_MERIT_HEADING = "National Merit scholarships this school publishes"

/**
 * WHAT KIND OF NUMBER a published National Merit scholarship is, said ONCE for
 * the page (RFC 247 D8), for [AWARD_KIND_SENTENCE]'s reason: it is the school's
 * money and not National Merit's, it is paid only to a student who reaches the
 * standing, and nobody has offered it to this family.
 */
private const val NATIONAL_MERIT_KIND_SENTENCE =
  "A National Merit scholarship a school publishes is the school's own money, paid only to students who reach " +
    "the standing it names, and is not a financial aid offer."

/**
 * K4 (glance/03, brief 0016): what "paid" and "considered" MEAN, said ONCE for
 * the page beside [NATIONAL_MERIT_KIND_SENTENCE], so each row can say which it
 * is in one word. Each row used to carry its own 14- or 15-word sentence for
 * this (RFC 247 D5), the same sentence at every school.
 *
 * It keeps D5's distinction whole: an `automatic` row is paid to everyone
 * holding the standing who meets the terms; a `competitive` one is CONSIDERED,
 * and is never said as promised.
 */
private const val NATIONAL_MERIT_GUARANTEE_KIND_SENTENCE =
  "A National Merit scholarship marked paid is paid to every student holding that standing who meets the school's " +
    "terms; one marked considered is not promised."

/**
 * The terms of a National Merit row's definition list (glance/03), each over
 * one fact, in the order a parent reads a row. The school's own name for its
 * scholarship, where it has one, is under [SCHOLARSHIP_LABEL].
 */
private const val MERIT_FOR_TERM = "For"
private const val MERIT_GUARANTEE_TERM = "Paid or considered"
private const val MERIT_PER_YEAR_TERM = "A year"
private const val MERIT_TOTAL_TERM = "In total"
private const val MERIT_WORDS_TERM = "The school's words"
private const val MERIT_FIRST_CHOICE_TERM = "First choice"
private const val MERIT_APPLY_TERM = "Apply by"

/** A row whose page says nothing about first choice: the silence stated, never resolved into a yes or a no. */
private const val FIRST_CHOICE_UNSAID = "This school's page does not say whether it must be named first choice."

/** D-A said aloud: the parent must know the page moves under them, because it does. */
private const val LIVE_LINE = "This report is live \u2014 it updates as your student updates their list."

/**
 * The one blank label for a figure this school does not publish. Never a zero.
 *
 * `internal` for one reason, stated because visibility is otherwise noise here:
 * it is a CLAIM ABOUT A NAMED SCHOOL, and RFC 166 gave three blanks it is false
 * of (the publisher's suppression, a gap of ours, a figure held only in another
 * year). The tests that pin where it may no longer appear assert on THIS string
 * rather than on a copy of it, so a reworded label cannot leave them passing
 * against words the page no longer prints.
 */
internal const val NOT_REPORTED = "Not reported by this school"

/** The blank for a public school's tuition when the family's state is not on file. */
private const val TUITION_WITHHELD = "Not shown \u2014 the state the family lives in is not on file"

/**
 * The blank for a Common Data Set cell this filing simply lacks -- a merit
 * measure the school did not report, or a loan type's average with no status
 * beside it (RFC 260 D-A).
 *
 * The TABLE's own absence words, because a merit figure carries no
 * [FigureStatus] to speak through [FigureStatusCopy]: `MeritPractice` holds a
 * measure or null. It says what the FILING does not carry and nothing about
 * why, which is all the row knows. `internal` for [NOT_REPORTED]'s reason: the
 * tests assert on this string, not on a copy of it.
 */
internal const val NOT_IN_FILING = "Not in this filing."

/**
 * The borrowed cell for a loan type whose average is shown and whose counted
 * pair is not (RFC 260 D-A).
 *
 * NEUTRAL ON PURPOSE. A missing pair is one of three facts -- the school's
 * silence, our own unread denominator, or the school's own contradiction (D6)
 * -- and D6 says the contradiction is never spoken, so a cell that named any
 * one of them would be wrong for the other two. The gap paragraphs under the
 * table say whose silence it is where that is ours or the publisher's to say.
 */
internal const val NO_SHARE_SHOWN = "No share is shown here."

// The merit table's words (RFC 260, brief 0016). The caption names the filing
// and the cohort once; the column and row headers carry what the two sentences
// used to restate per school. "Non-need (merit) aid" and never "award" (RFC
// 141's glossary); "received", because it is a grant and not an offer.
private const val MERIT_CAPTION_TAIL = ", about last year's full-time freshmen."
private const val MERIT_MEASURE_COLUMN = "Non-need (merit) aid"
private const val MERIT_COHORT_COLUMN = "Last year's full-time freshmen"
private const val MERIT_SHARE_ROW = "Share who received it"
private const val MERIT_AVERAGE_ROW = "Average received"

// The borrowing table's words (RFC 260). The caption says the cohort in the
// filing's own terms -- the students who graduated that year -- once above the
// rows; the two negative hedges the header used to carry are said once per
// page in the kinds section ([BORROWING_KIND_SENTENCE]).
private const val BORROWING_CAPTION_TAIL = ", about what the students who graduated from it that year borrowed."
private const val LOAN_TYPE_COLUMN = "Loan type"
private const val BORROWED_COLUMN = "Borrowed"
private const val OWED_COLUMN = "Owed on average at graduation"

/**
 * K1 (RFC 260): what non-need aid IS, said once for the page. It used to ride
 * inside every school's average sentence ("a grant the student never pays
 * back", brief 0016 D7); the fact is about the kind of money, not about one
 * school's figure, so it belongs in the one section that says what kind of
 * numbers these are.
 */
private const val MERIT_KIND_SENTENCE = "Non-need (merit) aid is a grant the student never pays back."

/**
 * K2 (RFC 260): the cohort every borrowing table is over, and what it is NOT.
 * The two hedges are [BorrowingWire.COHORT_HEDGES] -- the same constant
 * [BorrowingWire.HEADER_TAIL] ends on -- said once for the page rather than in
 * every school's header, so the page and the coach cannot come to disagree
 * about who the cohort excludes; the coach's own header is unchanged.
 */
private const val BORROWING_KIND_SENTENCE =
  "The borrowing figures describe the students who graduated from a school in the year of its filing -- " +
    BorrowingWire.COHORT_HEDGES + "."

/**
 * K3 (RFC 260): the federal median debt is undated by its source. Said once for
 * the page, where it used to follow every school's figure.
 */
private const val DEBT_YEAR_KIND_SENTENCE = "The source publishes no year for the median federal loan debt."

/**
 * The blank for a blended figure this school publishes for in-state students
 * while this family would pay the out-of-state price (RFC 157 D-A).
 *
 * NOT a caveat under the number, and not a footnote: RFC 142 landed because a
 * labelled `net_price_q1..q5` was still read as "the Q5 net price", and a number
 * printed beside a family's own name is read as theirs whatever the note says.
 * This page has no coach in the loop -- a parent reads it alone -- so the figure
 * goes and the reason stays.
 *
 * The REASON is the domain's own, in [WithheldReason.cellPhrase] -- the short
 * form beside the sentence the coach ships -- so a page and a coach explain one
 * blank with one vocabulary. Only [totals] is this page's, and it is a parameter
 * because the pointer has to be true where it is printed: the per-school table
 * has the family's own totals directly above this row, and the summary table has
 * none at all. A blank with no destination is a number taken away; the totals
 * built from out-of-state tuition and fees are in the same report and the parent
 * is sent to them.
 */
private fun withheldBlankFor(
  reason: WithheldReason,
  totals: String,
): String = "Not shown \u2014 ${reason.cellPhrase} \u2014 $totals."

/** The blank in the per-school table, where the family's own totals are directly above this row. */
private const val TOTALS_ABOVE = "the totals above"

/** The blank in the cross-school table, where this school's own totals are below rather than above. */
private const val TOTALS_IN_SCHOOL_TABLE_BELOW = "the totals in this school's own table below"

/**
 * The blank for a component that is not part of a way of living at all.
 *
 * No arrangement is short a role today: gate-2 D17 gave living at home the
 * food-and-housing line it used to lack (RFC 166 §7), so all three ways of
 * living carry all three roles. It stays because the mapping is data -- a fourth
 * arrangement, or a role one of them does not price, must render as itself
 * rather than as a school that failed to report something.
 */
private const val NOT_PART_OF_ARRANGEMENT = "Not a part of this way of living"

/**
 * Whose number the ONE amount on this page that is not a school's figure is
 * (RFC 166 §7, gate-2 D17): the `$0` food-and-housing line of living at home.
 *
 * It closes that column's HEADER ([columnHeaderOf]), said once per table rather
 * than in the cell (brief 0016, glance/02): the header is the figure's label,
 * and it is also every cell's `data-label` in the column, so a phone still
 * prints it beside the zero. The whole sentence, and the reason the zero is
 * honest, arrives above the table through the domain's own
 * `AtHomeAssumptionBasis` statement, which this page renders verbatim with every
 * other basis fact. This is the pointer that stops a parent reading the zero off
 * the row as something the school published: OURS, never theirs.
 */
private const val OUR_ASSUMPTION = "our assumption"

/** No partial sum is ever printed as a total; this says so where the total would have been. */
private const val NO_TOTAL = "No total \u2014 a part of this price is missing"

/** The heading both assumption blocks carry: one question, whichever shape answers it. */
private const val ASSUMPTIONS_HEADING = "What these figures assume"

/**
 * The page's ONE unit, "Per year", in three roles, deliberately one string: the
 * CAPTION of both kinds of price table -- alone over the side-by-side table, and
 * leading each school's own caption ([priceCaptionOf]) -- the CORNER header
 * over a school table's row labels, and the fallback column header at a school
 * that publishes no component, where there is no way of living to head a
 * column. All three say the same thing about the same figures, so a school with
 * no component prints "Per year | Per year" by design, not by copy-paste.
 */
private const val PER_YEAR = "Per year"

// What a school table's caption says after the unit: WHICH tuition and fees its
// figures are at (brief 0016, glance/02). Table labels, not the domain's
// sentence -- [CollegeResidencyBasis.statement] is still the coach's, and the
// assumption list's, unchanged. Only the arms that name a figure have words
// here; an arm whose basis is an ABSENCE keeps that sentence ([residencyPlacementOf]).
private const val IN_STATE_CAPTION_BASIS = "at the in-state tuition and fees this family would pay at this public school"
private const val OUT_OF_STATE_CAPTION_BASIS =
  "at the out-of-state tuition and fees this family would pay at this public school"
private const val ONE_PRICE_CAPTION_BASIS = "at the one tuition and fees price this school publishes for everyone"

/** That fallback is exactly ONE column wide, and the whole-school rows span it. Named beside the header it matches. */
private const val SINGLE_COLUMN_COUNT = 1

/**
 * The column labels, written once each.
 *
 * They are not decoration: every one of them is printed TWICE -- as a `<th>` in
 * the desktop grid and again in the cell's `data-label`, which is what the phone
 * layout shows once the table stops being a grid. Two hand-typed copies of one
 * label is a header and a stacked card that can disagree about what a column is.
 */
private const val SCHOOL_COLUMN = "School"
private const val WAY_OF_LIVING_COLUMN = "Way of living"
private const val TUITION_COLUMN = "Tuition and fees"
private const val PUBLISHED_PRICE_COLUMN = "The published price"
private const val NET_PRICE_COLUMN = "The likely price after a financial aid offer"
private const val TOTAL_ROW_LABEL = "Total per year"

/** The cross-school table's own heading: the comparison the assumption lines above it promise. */
private const val SUMMARY_HEADING = "The schools side by side"

/**
 * Said when no one way of living is priced at every school, which is
 * [ed.unicoach.coaching.costs.ArrangementBasis]'s own finding: without a way of
 * living held constant the summary compares only the two school-wide figures,
 * and the parts are quoted school by school below.
 */
private const val SUMMARY_NO_HELD_ARRANGEMENT =
  "No one way of living is priced at every school here, so this table compares only each school's own " +
    "school-wide figures; the parts of the price are quoted school by school below."

/** The blank for a summary tuition cell whose school publishes a different figure per way of living. */
private const val SUMMARY_TUITION_VARIES = "Quoted per way of living below"

/**
 * The in-state basis of BOTH blended figures, written once and joined onto each
 * table's hint (RFC 157): the two hints differ in whose figure it is and what
 * the parts are, never in what the figures are on.
 *
 * One SENTENCE, with no glue on either end -- the join is written at the two
 * call sites below, where it can be seen, rather than hidden in a leading space
 * every caller has to remember.
 */
internal const val BLENDED_IN_STATE_CLAUSE =
  "At a public school it, and the likely price after a financial aid offer, are figures for students paying " +
    "in-state tuition."

/** Said under the summary table, because two published prices side by side invite exactly this arithmetic. */
internal const val SUMMARY_HINT =
  "The published price is each school's own published cost of attendance, an average blended across the ways " +
    "of living, so it is not the sum of the parts quoted below."

/**
 * The same warning under ONE school's table, where "each school's" would be
 * false. Written once, like its twin.
 *
 * `internal`, with [BLENDED_IN_STATE_CLAUSE] and [SUMMARY_HINT], for the reason
 * [NOT_REPORTED] is: the tests that pin WHERE these sentences may no longer
 * appear assert on the constants themselves, so a reworded hint cannot leave an
 * absence assertion passing vacuously against words the page no longer prints.
 */
internal const val SCHOOL_BLENDED_HINT =
  "The published price is this school's own published cost of attendance, an average blended across the ways " +
    "of living, so it is not the sum of the parts above it."

/**
 * The blended hint, with the in-state clause appended only where a PUBLIC school
 * is on this page.
 *
 * [BLENDED_IN_STATE_CLAUSE] is a sentence about public schools, and it used to
 * be a constant tail on both hints -- so a parent comparing two private colleges
 * read a sentence about in-state tuition under a table where no school prices by
 * residency at all. The control is the cost DOMAIN's own
 * ([CollegeControl.Public]), read off the colleges this hint is printed over:
 * the per-school hint asks about its one school, the page-level hint about every
 * school it stands above, because the clause is true of the page exactly when
 * one of them is public.
 */
private fun blendedHintFor(
  hint: String,
  hintStandsOver: List<CollegeCost>,
): String = if (hintStandsOver.any { it.control is CollegeControl.Public }) "$hint $BLENDED_IN_STATE_CLAUSE" else hint

/**
 * The hint said ABOVE the school sections, over every school it stands over --
 * under the summary table where there is one ([ReportShape.Compared]), and on
 * its own where there is not ([ReportShape.ManyUncompared]).
 */
private fun pageBlendedHint(colleges: List<CollegeCost>): String = blendedHintFor(SUMMARY_HINT, colleges)

/** The hint said under the ONE table on a one-school page, over that school alone. */
private fun schoolBlendedHint(cost: CollegeCost): String = blendedHintFor(SCHOOL_BLENDED_HINT, listOf(cost))

/**
 * WHICH shape of report this profile is, decided ONCE.
 *
 * Three facts hang off this question -- which assumption block is rendered,
 * whether a school section states its own residency basis, and where the blended
 * hint is said -- and each used to re-derive it from `comparisonBasis` and the
 * college count in its own words. Two hand-written booleans that must agree are
 * how a one-school page ended up stating its residency sentence twice; a THIRD
 * would be free to disagree with both.
 *
 * The shapes are the cost domain's, not this page's invention:
 * [ComparisonBasis.of] deliberately builds nothing below two colleges, and this
 * page has always kept a named arm for two or more colleges it was handed no
 * comparison for. A fourth shape must fail to compile in the `when`s below
 * rather than inherit a silence.
 */
private sealed interface ReportShape {
  /** Nothing on the list: nothing is priced, so there is no assumption to state. */
  data object NoColleges : ReportShape

  /** The one school the assumption block above IS the basis of ([SingleSchoolBasis]). */
  data class OneSchool(
    val cost: CollegeCost,
  ) : ReportShape

  /** Two or more schools the domain built a comparison for. */
  data class Compared(
    val basis: ComparisonBasis,
  ) : ReportShape

  /** Two or more schools with no comparison basis -- priced, and compared by nobody. */
  data object ManyUncompared : ReportShape

  companion object {
    fun of(profile: CollegeCostProfile): ReportShape {
      val basis = profile.comparisonBasis
      val single = profile.colleges.singleOrNull()
      // Compared FIRST, and the order is load-bearing: the domain builds no
      // basis below two colleges, so a basis implies two or more and can never
      // steal the one-school shape out from under the residency line this page
      // stops printing on it.
      return when {
        basis != null -> Compared(basis)
        single != null -> OneSchool(single)
        profile.colleges.isEmpty() -> NoColleges
        else -> ManyUncompared
      }
    }
  }
}

/**
 * The band the page has ALREADY stated, or null where it states none.
 *
 * EXHAUSTIVE over the closed vocabulary, never `as?`: a variant added to
 * [MoneyBasis] that also names a band must fail to compile here and decide
 * whether the page states it, rather than fall silently to null and put the note
 * back beside every net-price figure on the page.
 */
private fun bandSaidAboveOf(basis: MoneyBasis): IncomeBand? =
  when (basis) {
    is MoneyBasis.AnsweredBand -> basis.band
    MoneyBasis.Declined, MoneyBasis.Unanswered -> null
  }

/**
 * Each school's slice of the major answer (RFC 251), re-keyed by the domain.
 * Every slice is empty when the answer is not on the profile -- the report
 * source removes it unless the student said yes -- and then nothing prints a
 * word about it.
 */
private fun programSlicesOf(profile: CollegeCostProfile): Map<CollegeId, List<CollegeSubjectReturn>> =
  profile.colleges.associate { it.collegeId to profile.programReturn?.forCollege(it.collegeId).orEmpty() }

/** Whether any school runs a matched program: the one condition the coverage and sources sentences are said on. */
private fun printsAProgram(programSlices: Map<CollegeId, List<CollegeSubjectReturn>>): Boolean =
  programSlices.values.flatten().any { it.college is ProgramReturnCollege.WithPrograms }

/**
 * The Family Cost Report for one student's [profile].
 *
 * Rendered through [siteLayout] with `noindex` on (D-H) so the shared chrome is
 * unchanged and the page still cannot be indexed. The response headers that go
 * with it (`Cache-Control: no-store`, `X-Robots-Tag`, `Referrer-Policy`) are the
 * route's, in `Routing.kt`, because they must also ride the 404.
 *
 * The residency basis is derived ONCE here and the per-school sentences are
 * passed down. It used to be re-derived inside two render functions, which made
 * one fact about the family into two computations that only convention kept
 * equal.
 */
suspend fun ApplicationCall.respondCostReportPage(
  profile: CollegeCostProfile,
  nav: ShellNav = ShellNav.SignedOut,
  offer: ClaimOffer,
  token: String,
) {
  val residencyBases = residencyBasesOf(profile)
  val shape = ReportShape.of(profile)
  // ONE derivation of the money basis for the whole page, like the residency
  // bases above it: the assumption block states it and the net-price cells ask
  // it which band was stated, and two calls could not disagree but could drift.
  val moneyBasis = MoneyBasis.of(profile.moneyProfile)
  val bandSaidAbove = bandSaidAboveOf(moneyBasis)
  // The assumption block IS this school's own basis on exactly one shape, and
  // that one fact decides both what a school section may stop saying (its
  // residency sentence) and where the blended hint is said (RFC 188).
  val basisIsThisSchool = shape is ReportShape.OneSchool
  // Re-keyed ONCE, like the residency bases above, so no render function groups.
  val programSlices = programSlicesOf(profile)
  val printsAProgram = printsAProgram(programSlices)
  respondHtml {
    siteLayout(PAGE_TITLE, noindex = true, nav = nav) {
      section("report") {
        h1 { +REPORT_HEADING }
        p("report-live") { +LIVE_LINE }
        assumptions(shape, residencyBases, moneyBasis)
        if (profile.colleges.isEmpty()) {
          p("report-empty") { +"There is no college on this list yet, so there is nothing to price." }
        } else {
          // The comparison the assumption lines above promise, made real: the
          // shape is [ReportShape.Compared] on exactly the same condition (two
          // or more colleges the domain compared), so the page can never state
          // the comparison assumptions over a page that compares nothing.
          (shape as? ReportShape.Compared)?.let { summaryTable(profile, it.basis, bandSaidAbove) }
          // The blended hint is a fact about the published price, not about one
          // school: it is said ONCE, above the tables it warns about. The
          // summary table carries it on a compared page and the one school
          // carries it on its own page, which leaves the uncompared many -- a
          // shape that is still priced, and still owed the warning.
          if (shape is ReportShape.ManyUncompared) p("report-hint") { +pageBlendedHint(profile.colleges) }
          profile.colleges.forEach {
            schoolSection(
              cost = it,
              residency = residencyBases.getValue(it.collegeId),
              basisIsThisSchool = basisIsThisSchool,
              bandSaidAbove = bandSaidAbove,
              subjects = programSlices.getValue(it.collegeId),
            )
          }
        }
        // What KIND of numbers the page's prose figures are, said once for the
        // set each sentence covers (RFC 191) -- above the publishers, because a
        // reader asks what a number is before asking who published it.
        tierKindsSection(profile.colleges, programSlices, printsAProgram)
        // The publishers of the figures THIS page carries, never a constant:
        // a page with no IPEDS figure on it may not claim an IPEDS source, and
        // the constant this replaced claimed the Scorecard for every figure
        // (RFC 177 D5).
        sourcesSection(profile.moneySources, printsAProgram)
      }
      // AFTER the report and outside its section (RFC 254): the offer never
      // stands in front of the report, and an `aside` keeps it out of every
      // measure taken of the report section itself.
      reportClaim(offer, token)
    }
  }
}

/** The class the after-report offer carries (RFC 254), so CSS and tests agree on it. */
const val REPORT_CLAIM_CLASS = "report-claim"

/** The offer's heading (RFC 254). */
const val CLAIM_HEADING = "Keep going with your own account"

/**
 * What the offer says about unicoach (RFC 254). It asks the reader for nothing
 * about the student — the test RFC 254 puts on every word here, so the cue RFC
 * 155 D-G suppressed cannot come back through this copy.
 */
const val CLAIM_BODY =
  "unicoach is a college coach. You can have an account of your own, to look up what any college " +
    "costs a family like yours and to keep track of what comes next."

/** What signing up from here does, said before anybody signs up (RFC 254). */
const val CLAIM_CONSENT =
  "If you sign up here, we'll ask the student who shared this report to approve connecting with you. " +
    "Nothing of theirs is shared with you until they do. They will see your name and email."

/** The signed-in reader's one action (RFC 254). */
const val CLAIM_ASK_BUTTON = "Ask to connect"

/** The one line a reader with a request already waiting sees instead of the offer (RFC 254). */
const val CLAIM_PENDING_LINE = "Your request to connect is waiting for the student to approve it."

/** After a signup whose claimant has not confirmed their email yet (RFC 254). */
const val CLAIM_PENDING_NOTICE =
  "Your account is ready. Once you confirm your email, we'll ask the student who shared the report " +
    "to approve connecting with you."

/** After a request the student has been asked about (RFC 254). */
const val CLAIM_SENT_NOTICE = "We've asked the student who shared the report to approve connecting with you."

/**
 * ONE sentence for every claim that made no request (RFC 254) — a dead link, a
 * decline before, the reader's own report — so the notice says nothing about
 * the student.
 */
const val CLAIM_NOT_SENT_NOTICE =
  "Your account is ready, but we couldn't send a request to connect from that report link."

/**
 * The one offer the page makes after the report (RFC 254): `:service`'s
 * [ClaimView], resolved against the reader this request has.
 *
 * Its own type rather than [ClaimView] because the signed-in offer is a form,
 * and a signed-in form needs the session's CSRF token (RFC 265). Carrying the
 * token on [AskToConnect] makes "ask to connect without a session" a value
 * nobody can build, the way `InviteViewer.SignedIn` carries its token for the
 * invite page's accept form.
 */
sealed interface ClaimOffer {
  /** A signed-out reader: the two doors that carry the report token onward. */
  data object SignUp : ClaimOffer

  /** A signed-in reader who may ask to connect, by a form that echoes [csrf]. */
  data class AskToConnect(
    val csrf: CsrfToken,
  ) : ClaimOffer

  /** A signed-in reader whose request is already waiting. */
  data object Pending : ClaimOffer

  /** Nothing to offer. */
  data object None : ClaimOffer
}

/**
 * The offer after the report (RFC 254), chosen by WHO is reading and by
 * nothing about the student, so its markup cannot carry a fact about them.
 *
 * The signed-out doors and the signed-in form carry the report token on its OWN
 * key, [REPORT_FIELD] — `/register` and `/sign-in` read `token` as an invite.
 * The links are same-origin, so they carry no `rel="noreferrer"`: the route's
 * `Referrer-Policy: same-origin` already keeps the token off every other origin.
 */
private fun FlowContent.reportClaim(
  offer: ClaimOffer,
  token: String,
) {
  when (offer) {
    ClaimOffer.SignUp -> {
      aside(REPORT_CLAIM_CLASS) {
        claimOffer()
        p {
          a(href = pathCarryingToken(REGISTER_PATH, token = null, report = token)) { +REGISTER_HEADING }
        }
        p {
          +"Already have an account? "
          a(href = pathCarryingToken(SIGN_IN_PATH, token = null, report = token)) { +SIGN_IN_HEADING }
        }
      }
    }

    is ClaimOffer.AskToConnect -> {
      aside(REPORT_CLAIM_CLASS) {
        claimOffer()
        signedInForm(action = REPORT_CONNECT_PATH, csrf = offer.csrf) {
          reportField(token)
          button(type = ButtonType.submit) { +CLAIM_ASK_BUTTON }
        }
      }
    }

    ClaimOffer.Pending -> {
      aside(REPORT_CLAIM_CLASS) { p { +CLAIM_PENDING_LINE } }
    }

    ClaimOffer.None -> {
      Unit
    }
  }
}

private fun FlowContent.claimOffer() {
  h2 { +CLAIM_HEADING }
  p { +CLAIM_BODY }
  p { +CLAIM_CONSENT }
}

/**
 * Each school's own residency FACT, keyed by college id -- ONE derivation for
 * the whole page.
 *
 * The typed [CollegeResidencyBasis] rather than its sentence: the one-school
 * basis takes the fact, and this page only prints the statement derived from it,
 * so nothing here flattens a domain fact to prose on the way through.
 *
 * [ResidencyBasis] refuses an empty college list by construction (a residency
 * held constant across no table states nothing), so an empty list is an empty
 * map rather than a call that throws.
 */
private fun residencyBasesOf(profile: CollegeCostProfile): Map<CollegeId, CollegeResidencyBasis> {
  if (profile.colleges.isEmpty()) return emptyMap()
  return ResidencyBasis
    .of(profile.colleges, profile.moneyProfile)
    .byCollege
    .associateBy { it.collegeId }
}

/**
 * The assumption lines above the table.
 *
 * Two shapes, and the split is the domain's own (RFC 151 D-B): with two or more
 * colleges the per-call statements are rendered VERBATIM from
 * [ComparisonBasis.statements], because they are the same sentences the coach
 * must say in chat. Below two colleges the domain deliberately builds no
 * comparison, so [SingleSchoolBasis] states the basis for that ONE school
 * instead -- never by constructing a comparison object the domain refused to
 * build.
 *
 * Both shapes render the WHOLE list the domain hands over rather than naming the
 * lines one by one, so a statement added to the domain reaches the parent
 * instead of reaching only the coach.
 *
 * The money line is said last and is present in both shapes (D-F): a price for a
 * family like this one has to say which family that is.
 */
private fun FlowContent.assumptions(
  shape: ReportShape,
  residencyBases: Map<CollegeId, CollegeResidencyBasis>,
  moneyBasis: MoneyBasis,
) {
  when (shape) {
    is ReportShape.Compared -> {
      assumptionList("report-basis", shape.basis.statements, moneyBasis)
    }

    is ReportShape.OneSchool -> {
      assumptionList(
        "report-single-basis",
        SingleSchoolBasis.of(shape.cost, residencyBases.getValue(shape.cost.collegeId)).statements,
        moneyBasis,
      )
    }

    ReportShape.NoColleges -> {
      // Nothing is priced, so there is no assumption to state. NAMED, because
      // the branch it used to share said the same nothing about a page that
      // does print prices.
      Unit
    }

    ReportShape.ManyUncompared -> {
      // Two or more colleges with no comparison basis is not a shape the cost
      // domain builds -- but the price tables below are rendered regardless, and
      // a price with nothing said about whose price it is is the one thing D-F
      // forbids. The family's own money line is the floor, always.
      assumptionList("report-basis", emptyList(), moneyBasis)
    }
  }
}

/** One assumption block: every statement the domain made, then the family's own line. */
private fun FlowContent.assumptionList(
  sectionClass: String,
  statements: List<String>,
  moneyBasis: MoneyBasis,
) {
  section(sectionClass) {
    h2 { +ASSUMPTIONS_HEADING }
    ul {
      statements.forEach { li { +it } }
      li { +moneyBasis.statement }
    }
  }
}

/**
 * The cross-school summary: rows are schools, read across in one place.
 *
 * The per-school tables below are the DETAIL -- columns there are the ways of
 * living, so a total belongs to exactly one column. That shape cannot be read
 * across schools, and a page that states the comparison assumptions while
 * showing four separate tables asserts a comparison it never makes. This table
 * is that comparison, and it carries only the figures that survive being put
 * side by side: the tuition and fees for ONE way of living held constant, and
 * the two school-wide figures.
 *
 * The way of living is [ed.unicoach.coaching.costs.ArrangementBasis]'s own --
 * the first arrangement every school here is priced for -- so the arrangement
 * named in the assumption lines above IS the arrangement this table uses. When
 * the domain finds none comparable, the column is not shown at all and the page
 * says why: an invented "held constant" is the exact dishonesty the basis lines
 * exist against.
 *
 * Every degradation rule of the detail tables holds here: a missing figure is a
 * labelled blank, never a zero and never the neighbouring school's number, and
 * no total is summed from parts. Below the phone breakpoint the shared
 * `report-table` rules stack each row into a card per school, with the column
 * header restored beside each figure.
 */
private fun FlowContent.summaryTable(
  profile: CollegeCostProfile,
  basis: ComparisonBasis,
  bandSaidAbove: IncomeBand?,
) {
  val held = basis.livingArrangement.comparable.firstOrNull()
  section("report-summary") {
    h2 { +SUMMARY_HEADING }
    p("report-hint") { +heldArrangementHint(held) }
    table("$DATA_TABLE_CLASS report-table report-summary-table") {
      // The unit, said once for every figure in the table (brief 0016,
      // glance/02): no column header carries it, and the table compares only
      // yearly figures. No residency here -- each school's own table says its.
      caption { +PER_YEAR }
      thead { summaryHeaderRow(held) }
      tbody { profile.colleges.forEach { summaryRow(it, held, bandSaidAbove) } }
    }
    // The page's ONE blended hint when this table exists: the school sections
    // below it print none.
    p("report-hint") { +pageBlendedHint(profile.colleges) }
  }
}

/** What this table holds constant -- or the domain's own finding that nothing is comparable. */
private fun heldArrangementHint(held: LivingArrangement?): String =
  if (held == null) {
    SUMMARY_NO_HELD_ARRANGEMENT
  } else {
    "Every tuition and fees figure in this table is for ${held.label}, which is the one way of living " +
      "every school here is priced for."
  }

/** The column names; the way-of-living column exists only when one is held constant. */
private fun THEAD.summaryHeaderRow(held: LivingArrangement?) {
  tr {
    th(scope = ThScope.col) { +SCHOOL_COLUMN }
    if (held != null) th(scope = ThScope.col) { +WAY_OF_LIVING_COLUMN }
    th(scope = ThScope.col) { +TUITION_COLUMN }
    th(scope = ThScope.col) { +PUBLISHED_PRICE_COLUMN }
    th(scope = ThScope.col) { +NET_PRICE_COLUMN }
  }
}

/** One school read across: the constant it is quoted under, then its three figures. */
private fun TBODY.summaryRow(
  cost: CollegeCost,
  held: LivingArrangement?,
  bandSaidAbove: IncomeBand?,
) {
  tr("report-row-summary") {
    rowHeader(cost.name)
    held?.let { arrangement ->
      // Words, not money: the constant this table holds, said in every row so
      // the stacked phone card still names it.
      td("report-value report-way-of-living") {
        attributes[DATA_LABEL] = WAY_OF_LIVING_COLUMN
        +arrangement.label
      }
    }
    valueCell(TUITION_COLUMN, summaryTuitionCell(cost, held))
    valueCell(PUBLISHED_PRICE_COLUMN, publishedPriceCell(cost, TOTALS_IN_SCHOOL_TABLE_BELOW))
    valueCell(NET_PRICE_COLUMN, netPriceCell(cost, TOTALS_IN_SCHOOL_TABLE_BELOW, bandSaidAbove))
  }
}

/**
 * This school's tuition and fees for the way of living the table holds constant,
 * or -- with no arrangement held constant -- the one figure that is true of the
 * school rather than of one column.
 *
 * Two genuinely different policies, so each is its own named function below: one
 * looks a figure up under a constant the domain chose, the other decides whether
 * a school-wide figure exists at all.
 */
private fun summaryTuitionCell(
  cost: CollegeCost,
  held: LivingArrangement?,
): SchoolFigure = if (held == null) schoolWideTuition(cost) else heldArrangementTuition(cost, held)

/**
 * The LOOKUP policy: this school's tuition for the arrangement the table holds
 * constant. A school not priced for it, or priced for it with no published
 * tuition, is a labelled blank -- never another arrangement's figure standing in.
 */
private fun heldArrangementTuition(
  cost: CollegeCost,
  held: LivingArrangement,
): SchoolFigure {
  val amount =
    cost.breakdown
      ?.arrangements
      .orEmpty()
      .firstOrNull { it.arrangement == held }
      ?.tuitionLine
      ?.amountUsd
  return figureOf(amountUsd = amount, blank = tuitionBlankFor(cost))
}

/**
 * The COLLAPSE policy: with no way of living held constant there is no single
 * cell to read across, so a figure is shown only when this school publishes the
 * SAME tuition for every way of living it is priced for -- the only case in
 * which one number is true of the school rather than of one column. A school
 * that publishes several is a blank pointing at the detail table below.
 */
private fun schoolWideTuition(cost: CollegeCost): SchoolFigure {
  val distinct =
    cost.breakdown
      ?.arrangements
      .orEmpty()
      .mapNotNull { it.tuitionLine?.amountUsd }
      .distinct()
  return when (distinct.size) {
    1 -> figureOf(amountUsd = distinct.single(), blank = tuitionBlankFor(cost))
    0 -> figureOf(amountUsd = null, blank = tuitionBlankFor(cost))
    else -> figureOf(amountUsd = null, blank = SUMMARY_TUITION_VARIES)
  }
}

/** One school: its own residency basis, its price table, its merit practice, and its debt context. */
private fun FlowContent.schoolSection(
  cost: CollegeCost,
  residency: CollegeResidencyBasis,
  /**
   * Whether the assumption block ABOVE is this one school's own basis -- one
   * fact, in one sense, deciding both of the things this section may stop
   * saying: its residency basis (in the caption or as a sentence) and the
   * hint. Two parameters ("say the residency line", "here is your hint") could
   * be handed a combination that is not a page: a section that both states its
   * own residency AND prints the one-school hint says the residency twice again.
   */
  basisIsThisSchool: Boolean,
  bandSaidAbove: IncomeBand?,
  /** This school's slice of every stored subject's answer, in stored order; empty when none may be shown. */
  subjects: List<CollegeSubjectReturn>,
) {
  section("report-school") {
    h2 { +cost.name }
    // The city and state, and nothing else. The kind of school is NOT printed
    // here: `CollegeControl.label` is a wire label ("private_nonprofit"), and the
    // residency basis below (the table's caption, or the sentence above it)
    // already says in English whether this school prices by where the family
    // lives, which is the only thing the control decides here.
    p("report-school-where") { +"${cost.city}, ${cost.state}" }
    // WHERE this school's residency basis is said is decided once, so the
    // caption and the sentence can never both say it or both drop it. On a
    // one-school report the assumption block above IS this school's basis and
    // already states the sentence ([SingleSchoolBasis]), so neither repeats it.
    // Anywhere else the caption carries a basis that names a figure, and an
    // ABSENCE -- no figure is this family's, or we cannot say which is -- stays
    // the domain's own sentence: it explains blanks, and a caption is a label.
    val placement = residencyPlacementOf(residency.tuition)
    if (!basisIsThisSchool && placement == ResidencyPlacement.AsSentence) {
      p("report-school-residency") { +residency.statement }
    }
    tuitionTierLine(cost)
    priceTable(cost, priceCaptionOf(placement, basisIsThisSchool), bandSaidAbove)
    // ...and the same fact says the hint here, because a one-school page has no
    // summary table above to have said it.
    if (basisIsThisSchool) p("report-hint") { +schoolBlendedHint(cost) }
    meritBlock(cost)
    awardGridBlocks(cost)
    nationalMeritBlocks(cost)
    debtBlock(cost, subjects)
  }
}

/**
 * Where a school table says its residency basis: after the unit in its caption,
 * or as the domain's sentence above it.
 *
 * A sealed vocabulary rather than a boolean beside a nullable string: the
 * caption's words exist exactly when the basis goes in the caption, so a
 * caption basis with nothing to say, or a sentence arm carrying caption words,
 * cannot be built.
 */
private sealed interface ResidencyPlacement {
  /** A basis that names the figure the table is at; [words] follow the unit in the caption. */
  data class InCaption(
    val words: String,
  ) : ResidencyPlacement

  /** A basis that is an ABSENCE, said as [CollegeResidencyBasis.statement] in its own `<p>` above the table. */
  data object AsSentence : ResidencyPlacement
}

/**
 * One school's residency arm, placed (brief 0016, glance/02).
 *
 * EXHAUSTIVE over [ComparedTuition] and [TuitionApplicable], with no `else`: an
 * arm added to either must decide here whether it names a figure or states an
 * absence, rather than fall silently into a caption with no basis in it.
 */
private fun residencyPlacementOf(tuition: ComparedTuition): ResidencyPlacement =
  when (tuition) {
    is ComparedTuition.Public -> {
      when (tuition.tuitionApplicable) {
        TuitionApplicable.IN_STATE -> ResidencyPlacement.InCaption(IN_STATE_CAPTION_BASIS)

        TuitionApplicable.OUT_OF_STATE -> ResidencyPlacement.InCaption(OUT_OF_STATE_CAPTION_BASIS)

        // Two published figures and no state on file: neither is shown, and
        // the sentence is what explains the blanks in the table below.
        TuitionApplicable.UNKNOWN -> ResidencyPlacement.AsSentence
      }
    }

    ComparedTuition.SinglePublishedPrice -> {
      ResidencyPlacement.InCaption(ONE_PRICE_CAPTION_BASIS)
    }

    // We cannot say which published price applies: there is no basis to name.
    is ComparedTuition.PublishedPriceUnknown -> {
      ResidencyPlacement.AsSentence
    }
  }

/**
 * A school table's caption: always the unit, and the residency basis after it
 * where [placement] puts it there -- except on a one-school report, where the
 * assumption list directly above already states this school's basis verbatim
 * and the caption would only restate it (brief 0009 D1).
 */
private fun priceCaptionOf(
  placement: ResidencyPlacement,
  basisIsThisSchool: Boolean,
): String =
  when (placement) {
    is ResidencyPlacement.InCaption -> if (basisIsThisSchool) PER_YEAR else "$PER_YEAR, ${placement.words}"
    ResidencyPlacement.AsSentence -> PER_YEAR
  }

/**
 * WHICH tuition prices this school publishes, in the domain's own sentence (RFC
 * 166 §4) -- including `publisher_does_not_separate_in_district`, the one a
 * family at a community college most needs, which reached the coach and never
 * reached the parent reading this page alone.
 *
 * Printed only where this school publishes at least one tuition price. The
 * decision is the DOMAIN's, read off [CollegeCost.residencyTiers] in an
 * exhaustive `when` with no `else`: a SEVENTH basis must say here whether this
 * page speaks it, rather than inheriting a silence or a sentence nobody chose.
 * A school that publishes no tuition at all is `no_published_tuition`, and its
 * one honest sentence is already on the page as the blank beside the tuition
 * row -- printing it twice would say the same nothing in two voices.
 *
 * This used to be a count of [CollegeCost.publishedTuitionTiers] against the
 * catch-all `single_published_price`, which was this page guarding around a
 * basis that lied; the basis now names the empty case itself.
 */
private fun FlowContent.tuitionTierLine(cost: CollegeCost) {
  val statement =
    when (cost.residencyTiers) {
      ResidencyTierBasis.THREE_TIERS_PUBLISHED,
      ResidencyTierBasis.TWO_TIERS_PUBLISHED,
      ResidencyTierBasis.PUBLISHER_DOES_NOT_SEPARATE_IN_DISTRICT,
      ResidencyTierBasis.IN_DISTRICT_AND_ONE_OTHER_TIER,
      -> cost.residencyTiers.statement

      // No tier to name: the tuition row's own blank says why, in the school's
      // own status words.
      ResidencyTierBasis.NO_PUBLISHED_TUITION -> return

      // Dropped (brief 0016 D7): one price is a reading instruction about the
      // tuition row's own label, which is on the page, and WHOSE price it is
      // is already said by the residency basis -- in the caption, or in the
      // assumption list on a one-school report. The coach still says it.
      ResidencyTierBasis.SINGLE_PUBLISHED_PRICE -> return
    }
  p("report-school-tuition-tiers") { +statement }
}

/**
 * One school's price table: tuition and fees first (the stable block), then the
 * estimated components, then the arrangement total, then the published price,
 * then the likely price after a financial aid offer.
 *
 * Columns are the ways of living this school is priced for, so a total belongs
 * to exactly one column and no figure is ever read across two. A school that
 * publishes no component at all has no way of living to head a column and gets
 * one plain column instead -- its tuition, published price and price after aid
 * are still true and still shown.
 *
 * [tableCaption] is [priceCaptionOf]'s: the unit, and the residency basis
 * where the caption is the place that says it.
 */
private fun FlowContent.priceTable(
  cost: CollegeCost,
  tableCaption: String,
  bandSaidAbove: IncomeBand?,
) {
  val arrangements = cost.breakdown?.arrangements.orEmpty()
  val columnCount = if (arrangements.isEmpty()) SINGLE_COLUMN_COUNT else arrangements.size
  // Its own class so the shape test's no-total rule (RFC 260) can name it: this
  // is the one table on the page allowed a total row, because its columns are
  // ways of living and a total belongs to exactly one of them.
  table("$DATA_TABLE_CLASS report-table report-price-table") {
    caption { +tableCaption }
    thead { priceTableHeaderRow(arrangements) }
    tbody {
      tuitionRow(cost, arrangements)
      componentRows(cost, arrangements)
      if (arrangements.isNotEmpty()) totalRow(arrangements)
      wholeSchoolRow(PUBLISHED_PRICE_COLUMN, publishedPriceCell(cost, TOTALS_ABOVE), columnCount)
      wholeSchoolRow(NET_PRICE_COLUMN, netPriceCell(cost, TOTALS_ABOVE, bandSaidAbove), columnCount)
    }
  }
}

/**
 * The columns: one per way of living this school is priced for, or a single
 * plain column at a school that publishes no component to head one with.
 */
private fun THEAD.priceTableHeaderRow(arrangements: List<ArrangementCost>) {
  tr {
    th(scope = ThScope.col) { +PER_YEAR }
    if (arrangements.isEmpty()) {
      th(scope = ThScope.col) { +PER_YEAR }
      return@tr
    }
    arrangements.forEach { th(scope = ThScope.col) { +columnHeaderOf(it) } }
  }
}

/**
 * A way of living's column header -- the ONE home of a column's words, because
 * they are printed twice: as the `<th>` here, and as the `data-label` of every
 * cell under it ([tuitionRow], [componentRows], [totalRow]), which is what the
 * phone layout prints once the header row is hidden.
 *
 * The header also says WHOSE number an amount in the column is, where that is
 * ours rather than the school's (brief 0016, glance/02): living at home reads
 * "living at home (housing and food counted as $0, our assumption)". Said here,
 * once per table, instead of in the cell -- and, through the `data-label`,
 * still beside the zero on a phone.
 *
 * DERIVED from the arrangement's own lines, never from which arrangement it is
 * or a typed "$0": the domain decides which amounts are ours
 * ([CostLine.origin]), names the field ([spokenLabel]) and fixes the amount, so
 * a renamed role, a different assumed amount, or an assumed line under another
 * arrangement reaches this header without an edit here.
 */
private fun columnHeaderOf(arrangement: ArrangementCost): String {
  val ours = arrangement.lines.filter { isAssumedByUs(it.origin) }
  if (ours.isEmpty()) return arrangement.arrangement.label
  val counted = ours.joinToString(", ") { "${it.field.spokenLabel} counted as ${WholeDollars.spoken(it.amountUsd)}" }
  return "${arrangement.arrangement.label} ($counted, $OUR_ASSUMPTION)"
}

/**
 * Whether an amount is ours rather than a publisher's (RFC 166 §7). EXHAUSTIVE,
 * no `else`: a third origin must decide here whether a column header owns it.
 */
private fun isAssumedByUs(origin: LineOrigin): Boolean =
  when (origin) {
    LineOrigin.PUBLISHED -> false
    LineOrigin.ASSUMED_BY_UNICOACH -> true
  }

/** The tuition and fees row: the stable block, above everything estimated. */
private fun TBODY.tuitionRow(
  cost: CollegeCost,
  arrangements: List<ArrangementCost>,
) {
  // A school that publishes no component has no way of living to head a column,
  // so its one cell is headed by the table's own fallback -- the only thing that
  // differs between the two shapes, which is why the row is written once.
  val cells =
    if (arrangements.isEmpty()) {
      listOf(GridCell(PER_YEAR, line = null, blank = tuitionBlankFor(cost)))
    } else {
      arrangements.map { arrangement ->
        GridCell(columnHeaderOf(arrangement), arrangement.tuitionLine, tuitionBlankFor(cost))
      }
    }
  gridRow("report-row-tuition", TUITION_COLUMN, cells)
}

/**
 * One row of the arrangement grid -- said ONCE when it is the same thing in
 * every column (RFC 188).
 *
 * The test is DERIVED and not a list of roles: a row collapses exactly when the
 * figures it would print are equal, `distinct()` over the rendered
 * [SchoolFigure] itself, which carries the amount, the blank label and the basis
 * note. Tuition and books and supplies collapse today because the DOMAIN holds
 * one field for the whole school in each ([ComponentRole.fieldOf] maps every
 * arrangement to `BOOKS_AND_SUPPLIES_PER_YEAR_USD`, and `tuitionLine` is
 * `applicableTuitionFor(control)` for every column) -- but that is their
 * property, not this renderer's rule. Naming the two roles here would let a
 * fourth arrangement with its OWN books field silently collapse two different
 * numbers into one figure.
 *
 * A single-column table collapses nothing: there is no repeat to remove, and the
 * `data-label` the phone layout reads is the way of living, which a one-column
 * row still owes its reader.
 */
private fun TBODY.gridRow(
  rowClass: String,
  label: String,
  cells: List<GridCell>,
) {
  val schoolWide = schoolWideFigureOf(cells)
  tr(rowClass) {
    rowHeader(label)
    if (schoolWide != null) {
      // The span is the row's OWN cell count, never a column count passed in
      // beside the cells: two arguments that must agree are a `colspan` free to
      // disagree with the header above it.
      //
      // No `data-label`: the row's own header names this figure, and the way of
      // living it used to be labelled with is exactly what it no longer varies
      // by. The same shape [wholeSchoolRow] prints, for the same reason.
      spanningValueCell(schoolWide, cells.size)
      return@tr
    }
    cells.forEach { valueCell(it.columnLabel, it.figure) }
  }
}

/**
 * The one figure a whole row says, or null where the row really does say a
 * different thing in each column -- the DECISION half of [gridRow], named.
 *
 * The `size > 1` guard is the ONE-COLUMN case: nothing repeats there, and the
 * collapsed cell would drop the `data-label` a phone still reads the way of
 * living from.
 *
 * Structural equality is load-bearing: [SchoolFigure] is a `data class` pair, so
 * `distinct()` compares the amount, the blank label and the basis note, and two
 * cells that differ in any of the three are two different figures. The cell's
 * [GridCell.origin] is compared beside it, because the at-home zero no longer
 * carries a note to tell it apart (its column header says whose it is): our
 * assumed zero therefore never collapses into a published zero beside it, which
 * would drop the `data-label` that says so on a phone.
 */
private fun schoolWideFigureOf(cells: List<GridCell>): SchoolFigure? =
  if (cells.size > 1) {
    cells
      .map { it.figure to it.origin }
      .distinct()
      .singleOrNull()
      ?.first
  } else {
    null
  }

/**
 * One cell of the arrangement grid: the figure, the column it stands under, and
 * whose number it is.
 *
 * A named record rather than a `Pair<String, SchoolFigure>`, because the String
 * is not any string -- it is the column header ([columnHeaderOf]) the phone
 * layout restores beside the figure as its `data-label`, and `it.second` said
 * none of that.
 *
 * Built from the [CostLine] itself, never from a figure and an origin passed
 * side by side: [figure] and [origin] are both READ OFF the one line, so an
 * amount without an origin, or a blank with one, cannot be constructed.
 * [schoolWideFigureOf] depends on that pairing -- an amount whose origin went
 * missing would let our assumed zero collapse into a published zero and drop
 * the `data-label` that says it is ours. [origin] is null exactly where there is
 * no line (a blank): there is no number, so there is nobody's number.
 *
 * Not a [SchoolFigure.Amount] field, because every other figure on the page --
 * the totals, the published and net prices, the summary -- has no [CostLine]
 * behind it, and would have to invent an origin to be built at all.
 */
private class GridCell(
  val columnLabel: String,
  line: CostLine?,
  blank: String,
) {
  val figure: SchoolFigure = figureOf(amountUsd = line?.amountUsd, blank = blank)
  val origin: LineOrigin? = line?.origin
}

/**
 * Why a school's tuition and fees figure is absent, said as itself.
 *
 * A public school whose family has not said where they live has TWO published
 * figures and neither of them is this family's, so the cell says that rather
 * than "not reported", which would be a false statement about the school.
 */
private fun tuitionBlankFor(cost: CollegeCost): String {
  val control = cost.control
  val withheld = control is CollegeControl.Public && control.tuitionApplicable == TuitionApplicable.UNKNOWN
  if (withheld) return TUITION_WITHHELD
  // WHICH tuition figure this cell would have carried is the domain's own
  // decision ([applicableTuitionFor]), never a second control -> field map here.
  val field = applicableTuitionFor(control) ?: return NOT_REPORTED
  return blankFor(cost, field, NOT_REPORTED)
}

/**
 * The reason a cell is empty, in the STORE's own words where it has any -- the
 * one site on this page that decides what a blank says (RFC 166 §6).
 *
 * [NOT_REPORTED] is a statement ABOUT A NAMED SCHOOL, printed for a parent, and
 * it is false of FOUR of the six statuses: the publisher's estimate, the
 * publisher's suppression, a figure that does not apply at this school, and a
 * figure we have not collected -- which includes RFC 166 §3's year gap, a figure
 * this school DID publish, held only at another academic year. The page prints
 * the domain's sentence for those instead of blaming the school, and authors no
 * copy of its own: the words are [FigureStatusCopy]'s, carried here on
 * [CollegeCost.statusNoteFor].
 *
 * EXHAUSTIVE over the status, not a test for the three bad cases: a seventh
 * status must decide at compile time whether it may be spoken as this school's
 * silence.
 *
 * [otherwise] is the caller's own blank for "the school simply does not report
 * it" -- [NOT_REPORTED] in a cell, the debt paragraph's own sentence below --
 * and it is also what a field with NO ROW AT ALL gets: we hold no status, so we
 * have nothing else to say.
 */
private fun blankFor(
  cost: CollegeCost,
  field: CostField,
  otherwise: String,
): String {
  val note = cost.statusNoteFor(field) ?: return otherwise
  return when (note.status) {
    // The one status [otherwise] is TRUE of: the school reported nothing here.
    // The page keeps its own cell-sized words for it rather than lengthening
    // every ordinary blank on the page into a sentence -- which is also why the
    // tier sentence is NOT composed onto this arm: it rides with the domain's
    // own sentence, and a table cell whose whole content is two long sentences
    // is a different page.
    FigureStatus.NOT_REPORTED_BY_INSTITUTION -> otherwise

    // [FigureStatus.REPORTED] now carries a note -- a shown figure gets one so
    // that it can carry its assurance tier (RFC 179 D6) -- and that note says
    // NOTHING about its status: [CollegeCostService.figureStatusesOf] nulls the
    // statement for a SHOWN reported figure, and a BLANK one cannot be reported
    // at all (`price_figures_value_iff_status_check` /
    // `cohort_money_stats_value_iff_status_check`: a row bears a value exactly
    // when its status is `reported` or `imputed_by_publisher`). A blank cell
    // reached through this arm therefore keeps the caller's own words: a note
    // that says nothing about a status may not blank a cell.
    FigureStatus.REPORTED -> otherwise

    // The four the domain speaks for. Two sentences, in the seam's own order:
    // whose act first, what KIND of number second (RFC 179) -- never one
    // sentence, and never re-worded here. The status sentence is handed over
    // NULLABLE: [AssuranceTierCopy.composedStatementOf] already treats the tier
    // sentence as the FLOOR, and unwrapping it first would fold "this status
    // has no sentence" into the school's silence and drop a tier this note does
    // carry.
    FigureStatus.IMPUTED_BY_PUBLISHER,
    FigureStatus.SUPPRESSED_BY_PUBLISHER,
    FigureStatus.NOT_APPLICABLE,
    FigureStatus.NOT_COLLECTED_BY_US,
    // LINKED, holding no filing: a cost-figure note on this page carries a
    // federal tier, whose sentence promises no link. The page's ONE surface
    // that does hold a filing asks [CdsCitation.linksToFile] instead.
    -> AssuranceTierCopy.composedStatementOf(note.statement, note.assurance, FilingLink.LINKED)
  }
}

/**
 * One row per component ROLE -- housing and food, books and supplies, other
 * expenses -- rather than one per cost field, because each way of living has at
 * most one field in a role and a row per field would print two half-empty
 * "Housing and food" rows side by side.
 *
 * [ComponentRole] is the cost DOMAIN's, beside the fields it classifies: a
 * seventh component added to the vocabulary fails to compile there rather than
 * silently rendering no row here while still being counted in the total.
 *
 * A role is a row only when some way of living on this table is actually made of
 * it. No arrangement is short a role today -- D17 gave living at home its own
 * `$0` food-and-housing line (RFC 166 §7) -- so the filter guards a fourth
 * arrangement, not the with-family column.
 */
private fun TBODY.componentRows(
  cost: CollegeCost,
  arrangements: List<ArrangementCost>,
) {
  ComponentRole.entries
    .filter { role -> arrangements.any { role.fieldOf(it.arrangement) != null } }
    .forEach { role ->
      gridRow(
        "report-row-component",
        role.label,
        arrangements.map { arrangement ->
          val field = role.fieldOf(arrangement.arrangement)
          val line = arrangement.componentLines.firstOrNull { it.field == field }
          val blank = if (field == null) NOT_PART_OF_ARRANGEMENT else blankFor(cost, field, NOT_REPORTED)
          // No note on the assumed at-home zero: whose number it is is said by
          // the column header, which is also this cell's `data-label`.
          GridCell(columnHeaderOf(arrangement), line, blank)
        },
      )
    }
}

/**
 * The per-arrangement total, or the blank that says a part is missing -- never a
 * partial sum. Labelled with the column header, assumption and all: the at-home
 * total is summed over our zero.
 */
private fun TBODY.totalRow(arrangements: List<ArrangementCost>) {
  tr("report-row-total") {
    rowHeader(TOTAL_ROW_LABEL)
    arrangements.forEach {
      valueCell(columnHeaderOf(it), figureOf(amountUsd = it.totalPerYearUsd, blank = NO_TOTAL))
    }
  }
}

/** A figure that belongs to the school rather than to one way of living, spanning every column. */
private fun TBODY.wholeSchoolRow(
  label: String,
  figure: SchoolFigure,
  columnCount: Int,
) {
  tr("report-row-school") {
    rowHeader(label)
    spanningValueCell(figure, columnCount)
  }
}

/** One figure across every column of the table -- the ONE site that writes that cell. */
private fun TR.spanningValueCell(
  figure: SchoolFigure,
  columnCount: Int,
) {
  td("report-value") {
    colSpan = columnCount.toString()
    figureBody(figure)
  }
}

/**
 * One figure in a cell: the dollars, or the labelled reason there are none.
 *
 * A sealed vocabulary rather than a nullable amount beside a blank label. The
 * record spanned four combinations and two of them were nonsense -- an amount
 * beside a blank reason, and a blank beside a basis note -- so which field was
 * live was a convention the reader had to carry from the renderer. A basis note
 * belongs to a figure that exists and a blank label to one that does not, so
 * neither case can now carry the other's copy.
 */
private sealed interface SchoolFigure {
  /** A published figure, with the basis it rests on (RFC 135's ethos label). */
  data class Amount(
    val amountUsd: Int,
    val note: String? = null,
  ) : SchoolFigure

  /** No figure, and the labelled reason there is none. Never a zero. */
  data class Blank(
    val label: String,
  ) : SchoolFigure
}

/** The ONE site that turns "an amount that may be absent, and why" into the case it is. */
private fun figureOf(
  amountUsd: Int?,
  blank: String,
  note: String? = null,
): SchoolFigure = amountUsd?.let { SchoolFigure.Amount(it, note) } ?: SchoolFigure.Blank(blank)

/**
 * The school's own published cost of attendance -- a blended average, and
 * labelled as one.
 *
 * [totals] points at the figures that ARE this family's, for the OTHER reason
 * this cell can be empty (RFC 157 D-A): the school published the figure and it
 * is not this family's. The amount is already null by then -- the cost answer
 * holds it back, so this page cannot print it even by mistake -- and the caller
 * passes the pointer that is true where the cell is printed.
 */
private fun publishedPriceCell(
  cost: CollegeCost,
  totals: String,
): SchoolFigure =
  figureOf(
    amountUsd = cost.stickerCostOfAttendancePerYearUsd,
    blank = blendedBlankFor(cost, CostField.STICKER_COST_OF_ATTENDANCE_PER_YEAR_USD, totals),
  )

/**
 * The likely price after a financial aid offer, with its basis attached at the
 * figure (RFC 135's ethos label): an overall average can never be printed here
 * looking like a price for this family.
 *
 * [totals] plays the same part it plays for the published price above, and for
 * the same reason: this figure is the more dangerous half of RFC 157's defect,
 * because the arrangement totals beside it are at least residency-correct.
 */
private fun netPriceCell(
  cost: CollegeCost,
  totals: String,
  bandSaidAbove: IncomeBand?,
): SchoolFigure {
  val blank = blendedBlankFor(cost, CostField.NET_PRICE, totals)
  return when (val netPrice = cost.netPrice) {
    is NetPrice.BandSpecific -> {
      figureOf(
        amountUsd = netPrice.amount,
        blank = blank,
        // Said ONCE per page (RFC 188), and only when the page's own money line
        // already names THIS band: [MoneyBasis.AnsweredBand] states the bracket
        // in the assumption block above every figure on the page, so repeating
        // it at each of the six net-price cells of a three-school report told a
        // parent one fact six times. The test is the band itself, never the
        // answered STATUS -- a cell whose band differs from the one the page
        // stated keeps its own note rather than borrowing a sentence about
        // another bracket.
        note =
          "the average for families with a household income of ${netPrice.band.bracket}"
            .takeUnless { netPrice.band == bandSaidAbove },
      )
    }

    is NetPrice.OverallAverage -> {
      figureOf(
        amountUsd = netPrice.amount,
        blank = blank,
        note = "an overall average across all families, not a figure for this family",
      )
    }

    // The case that says itself: there is no number, and [blank] carries the
    // reason. A note about a band or an average would describe a figure this
    // cell is not printing.
    is NetPrice.Withheld -> {
      SchoolFigure.Blank(blank)
    }
  }
}

/**
 * WHY one of the two blended figures is absent, said as itself -- the twin of
 * [tuitionBlankFor].
 *
 * The withheld set is the cost DOMAIN's own ([CollegeCost.withheld]), never
 * re-derived here from the control and the family's state: the service decides
 * what this family may be shown, and a second rule in a renderer would be free
 * to disagree with the number the same page prints.
 *
 * EXHAUSTIVE over the reason, not a boolean test on the field: a member added to
 * [WithheldReason] must fail to compile here -- the one site on this page that
 * owes it copy -- rather than have its blank explained with the in-state
 * sentence. The null branch is the school's own silence, which is what
 * [NOT_REPORTED] has always meant.
 */
private fun blendedBlankFor(
  cost: CollegeCost,
  field: CostField,
  totals: String,
): String =
  when (val reason = cost.withheldReasonFor(field)) {
    WithheldReason.IN_STATE_ONLY_FIGURE -> withheldBlankFor(reason, totals)

    // Not withheld from this family: whether the blank is the school's silence
    // or somebody else's is [blankFor]'s decision, from the store's status.
    null -> blankFor(cost, field, NOT_REPORTED)
  }

/** The row's own label, in the leading header cell. */
private fun TR.rowHeader(label: String) {
  th(scope = ThScope.row) { +label }
}

/**
 * One value cell: the figure, under the label the phone layout restores beside
 * it once the table stops being a grid. In the per-school tables the label is
 * the way of living the column stands for; in the summary, where the ROWS are
 * schools, it is the figure the column names.
 */
private fun TR.valueCell(
  columnLabel: String,
  figure: SchoolFigure,
) {
  td("report-value") {
    attributes[DATA_LABEL] = columnLabel
    figureBody(figure)
  }
}

/**
 * One value cell whose content is already WORDS: a share with its sign, a
 * count beside its share, or a labelled blank (RFC 260). The same class and the
 * same phone label as [valueCell], because a reader and the stylesheet treat
 * the two alike; only the body differs, and a [SchoolFigure] is the wrong type
 * for a cell that holds a percentage.
 *
 * A blank keeps the one styled shape every blank on this page has
 * ([figureBody]'s `report-blank` span), so a missing merit measure and a
 * missing price are quiet in the same way.
 */
private fun TR.textValueCell(
  columnLabel: String,
  cell: TextCell,
) {
  td("report-value") {
    attributes[DATA_LABEL] = columnLabel
    when (cell) {
      is TextCell.Figure -> +cell.text
      is TextCell.Blank -> span("report-blank") { +cell.label }
    }
  }
}

/** What a [textValueCell] holds: a figure said in words, or the labelled blank that stands where one is not. */
private sealed interface TextCell {
  data class Figure(
    val text: String,
  ) : TextCell

  data class Blank(
    val label: String,
  ) : TextCell
}

/**
 * THE renderer for a figure, and the only one: the dollars, or the labelled
 * blank that says why there are none.
 *
 * One function because three copies of this rule had already drifted -- the
 * third had lost the `report-blank` span, so a whole-school blank rendered
 * unstyled beside a styled one in the table above it.
 *
 * The note prints ONLY beside a figure. It is the figure's BASIS ("an overall
 * average across all families"), so under a blank it described a number that is
 * not there: a parent read "Not reported by this school" followed by a sentence
 * about what the missing figure would have averaged.
 */
private fun FlowContent.figureBody(figure: SchoolFigure) {
  when (figure) {
    is SchoolFigure.Amount -> {
      +WholeDollars.spoken(figure.amountUsd)
      figure.note?.let { note -> span("report-note") { +note } }
    }

    is SchoolFigure.Blank -> {
      span("report-blank") { +figure.label }
    }
  }
}

/**
 * What this school reported about money it gives for something other than
 * financial need (RFC 148), in the domain's own sentences and under the
 * school's OWN citation -- the CDS is a different source from the Scorecard and
 * is cited separately. It is never subtracted from any price above.
 *
 * ATTRIBUTED ONCE, ABOVE THE FIGURES (RFC 190): the citation is an attribution
 * line under the heading rather than a footnote at the foot of the block, for
 * the reason the borrowing header is one -- the claimant is stated before the
 * figures it covers, and every sentence below it comes from that one filing.
 *
 * A citation with no fact under it is not data. [MeritPractice] already refuses
 * to exist without a measure, but it can hold recipients with no freshman
 * headcount to make a share out of and no average -- which renders a heading
 * and a caption with nothing under them. That is a section claiming a school
 * reported something while showing nothing it reported, so [shownMeritOf]
 * answers null and nothing is opened.
 *
 * ONE TABLE, TWO ROWS (RFC 260, brief 0016 D1). The two sentences each school
 * repeated -- the 17-word hedge included -- become a share cell and an average
 * cell under the filing's caption; what the hedge said about the KIND of money
 * is said once per page in the kinds section (K1), and "not an offer to this
 * student" is already the sources section's. Both rows always print when the
 * table does (D-A): a dropped row would hide that the school filed half the
 * section, so a missing measure is a labelled blank, [NOT_IN_FILING].
 */
private fun FlowContent.meritBlock(cost: CollegeCost) {
  val merit = shownMeritOf(cost) ?: return
  section("report-merit") {
    h3 { +"Money this school gives for something other than financial need" }
    table("$DATA_TABLE_CLASS report-table report-merit-table") {
      // The claimant FIRST, as the table's caption, and not again at the foot
      // (RFC 190): said before the figures it covers, so the table is scoped by
      // its attribution. AND IT IS A LINK (RFC 196) -- the one place this table
      // links the filing; `AssuranceTierCopy`'s voluntary-self-report sentence
      // promises it, in our own words, in the kinds section.
      caption {
        sourceLink(merit.source)
        +MERIT_CAPTION_TAIL
      }
      thead {
        tr {
          th(scope = ThScope.col) { +MERIT_MEASURE_COLUMN }
          th(scope = ThScope.col) { +MERIT_COHORT_COLUMN }
        }
      }
      tbody {
        tr {
          rowHeader(MERIT_SHARE_ROW)
          textValueCell(MERIT_COHORT_COLUMN, filingCellOf(merit.shareOfAllFullTimeFreshmen?.spokenPercent()))
        }
        tr {
          rowHeader(MERIT_AVERAGE_ROW)
          textValueCell(MERIT_COHORT_COLUMN, filingCellOf(merit.averageNonNeedAid?.let(WholeDollars::spoken)))
        }
      }
    }
  }
}

/**
 * This school's merit practice WHERE THE PAGE SHOWS IT, or null: the one
 * decision, asked by [meritBlock] to open the table and by [tierKindsSection]
 * to cover its figures and say K1. A second rule in either place would be free
 * to state a tier for a table the page does not print, or print one it never
 * states a tier for.
 */
private fun shownMeritOf(cost: CollegeCost): MeritPractice? {
  val merit = cost.meritAid ?: return null
  if (merit.shareOfAllFullTimeFreshmen == null && merit.averageNonNeedAid == null) return null
  return merit
}

/** A Common Data Set cell as words, or the filing's own absence blank where the measure is null (RFC 260 D-A). */
private fun filingCellOf(spoken: String?): TextCell = spoken?.let { TextCell.Figure(it) } ?: TextCell.Blank(NOT_IN_FILING)

/**
 * The merit figures this school's table shows, for the kinds section (RFC 260,
 * brief 0016 D10): one [CoveredFigure] per shown measure, on the tier
 * [MeritAidWire.ASSURANCE] reads off the Common Data Set cell. Derived from
 * the same [shownMeritOf] decision the table renders on, so the covered set
 * cannot name a figure the page does not print.
 *
 * A measure the filing lacks is NOT here, for [blankFiguresOf]'s reason: a
 * figure with no number is not a kind of number, and its cell says so itself.
 */
private fun meritFiguresOf(cost: CollegeCost): List<CoveredFigure> {
  val merit = shownMeritOf(cost) ?: return emptyList()
  return listOfNotNull(
    merit.shareOfAllFullTimeFreshmen?.let { MeritMeasure.SHARE },
    merit.averageNonNeedAid?.let { MeritMeasure.AVERAGE },
  ).map {
    CoveredFigure(
      collegeName = cost.name,
      figure = FigureIdentity.MeritAid(it),
      assurance = SpokenAssurance(MeritAidWire.ASSURANCE),
    )
  }
}

/**
 * What this school PUBLISHES about its automatic scholarships (RFC 239) -- one
 * block per published grid, and nothing whatever about the student.
 *
 * NOTHING ABOUT THE STUDENT IS THE WHOLE RULE HERE (D-E). This page is read by a
 * logged-out parent from a share link, and it holds no test score:
 * [CollegeCost.automaticAwards] comes from the student-free door of
 * `AutomaticAwardService`, so there is no score, no band a student reaches and no
 * point gap in scope to print. "Your student scored 1250, so this school pays
 * $6,000" would be a NEW privacy ruling for this page rather than an inherited
 * one, and RFC 239 declines to make it. The absence is asserted by test, because
 * that assertion IS the ruling.
 *
 * ONE BLOCK PER GRID, because residency is part of what the school published: of
 * the 40 curated colleges, 20 publish two grids -- one for students they class
 * as residents and one for everybody else -- and which of them a family falls
 * under is a legal status the SCHOOL determines. Printing one of them would be
 * picking that status for the family; printing both, each scoped by its own
 * heading, prints what the school published and picks nothing.
 *
 * A SIBLING OF [meritBlock] AND NOT A CHILD OF IT (RFC 190): that block
 * attributes everything under its heading to one Common Data Set filing, and a
 * curated scholarship page is a second publisher. Each block here names its own.
 */
private fun FlowContent.awardGridBlocks(cost: CollegeCost) {
  cost.automaticAwards.mapNotNull { printableGridOf(it) }.forEach { awardGridBlock(it) }
}

/** One grid this page will print, with the heading that scopes it to the students the school published it for. */
private data class PrintableGrid(
  val award: AutomaticAward,
  val heading: String,
  /** True for a `score_grid` school: a test score changes the money. False for a school whose automatic table a score does not move. */
  val scoreIsMoney: Boolean,
)

/**
 * Which grids this page prints, decided ONCE so the block and the standing
 * sentence in [tierKindsSection] can never disagree about whether the page
 * speaks about automatic scholarships at all.
 *
 * An exhaustive `when` with no `else`, the [borrowingParagraphs] shape: three of
 * the five answers return before a section is opened, and a sixth arm must say
 * here whether this page speaks it.
 *
 *  - `NotCurated` -- we have not read this school. The page is not the place to
 *    explain our own corpus coverage (the rule [borrowingParagraphs] states).
 *  - `NonePublished` -- a verified absence. True, cited, and not a scholarship
 *    grid: a heading over nothing is what [meritBlock] refuses.
 *  - `Expired` -- our reading of this school is stale, so it is not shown to a
 *    family as current money (D7/D20).
 */
private fun printableGridOf(answer: AutomaticAwardAnswer): PrintableGrid? {
  val award: AutomaticAward
  val scoreIsMoney: Boolean
  when (answer) {
    is AutomaticAwardAnswer.NotCurated -> {
      return null
    }

    is AutomaticAwardAnswer.NonePublished -> {
      return null
    }

    is AutomaticAwardAnswer.Expired -> {
      return null
    }

    is AutomaticAwardAnswer.NoScoreEffect -> {
      award = answer.award
      scoreIsMoney = false
    }

    is AutomaticAwardAnswer.ScoreBands -> {
      award = answer.award
      scoreIsMoney = true
    }
  }
  return PrintableGrid(award = award, heading = gridHeadingOf(award.residencyBasis), scoreIsMoney = scoreIsMoney)
}

/**
 * How this page attributes a fact it read off someone else's page without a
 * link: who published it, and the day we read it (RFC 190). One home, so the
 * award grid, the National Merit block and the base rate cite alike.
 */
private fun readOnCitationOf(
  publisher: String,
  readOn: LocalDate,
): String = "Source: $publisher, read on ${UsDate.of(readOn)}."

/**
 * WHO a school published a residency-scoped row for, or null for one package
 * for everybody -- the one rule both published-scholarship blocks use (RFC
 * 239's grid headings, RFC 247's standing lines), so the two name the same
 * audience the same way and refuse the same corruption.
 *
 * ALIASED AT THE IMPORT as `PublishedResidency`, because this file already has
 * a `ResidencyBasis`: [ed.unicoach.coaching.costs.ResidencyBasis] is the
 * FAMILY's basis for a price, and this one is the axis the SCHOOL published a
 * row on. Two different facts that must never be read as one.
 *
 * `in_district` and `unknown` are refused by each table's own CHECK, so no
 * stored row reaches them -- which is why reaching them is CORRUPTION AND IS
 * NAMED rather than a row this page quietly declines to print. The caller's
 * [refuse] names it, because only the caller holds the row that locates it.
 * A school publishing two grids would otherwise print one of them and
 * read as complete, and nothing -- not the page, not a log -- would say a
 * current, cited row had been suppressed. This page answers every other
 * absence with a labelled blank (`NOT_REPORTED`, [withheldBlankFor],
 * [blankFor]); the one thing it does not do is show a family a shorter table
 * than the school published with nothing saying so.
 */
private fun publishedAudienceOf(
  residency: PublishedResidency,
  refuse: () -> Nothing,
): String? =
  when (residency) {
    PublishedResidency.NOT_APPLICABLE -> {
      null
    }

    PublishedResidency.IN_STATE -> {
      "for students it classes as in-state"
    }

    PublishedResidency.OUT_OF_STATE -> {
      "for students it classes as out-of-state"
    }

    PublishedResidency.IN_DISTRICT, PublishedResidency.UNKNOWN -> {
      refuse()
    }
  }

/**
 * WHO the school published this grid for, said in the heading. NEVER ABSENT:
 * the three bases the table admits each have a heading, and the two it refuses
 * are corruption rather than a silence this page is entitled to.
 */
private fun gridHeadingOf(residency: PublishedResidency): String =
  publishedAudienceOf(residency) {
    error(
      "automatic award grid carries residency basis [${residency.value}], which this page cannot name an " +
        "audience for (college_automatic_awards_residency_basis_check refuses it, so this row is corrupt)",
    )
  }?.let { "$AWARD_GRID_HEADING $it" }
    ?: AWARD_GRID_HEADING

/**
 * One school's published grid as ONE TABLE (glance/03, brief 0016 D5): who it
 * is for in the heading, who published it, when we read it, what it asks a
 * student to submit and the day scores must be there by in the caption, and
 * one row per published band beneath.
 *
 * ATTRIBUTED ONCE, ABOVE THE CELLS (RFC 190), exactly as [meritBlock] and
 * [borrowingBlock] are: the caption is the claimant and the day we read the
 * page, then everything that came off it. Never once per band. The school's
 * requirement and deadline ride in the same caption because they are about
 * the whole table, not about any one row of it.
 *
 * NOT A LINK, unlike [sourceLink]. That helper renders a [CdsCitation], whose
 * `linkTargetOrNull` asserts the scheme before anything reaches an `href` --
 * "href is where escaping does not save a logged-out parent". This corpus's
 * `source_url` has no such door yet, so the publisher is named and no anchor is
 * opened; naming the publisher is what the citation owes a family.
 *
 * The block is ABSENT only where the school published NO band at all -- a
 * heading and a citation with nothing under them is a section claiming the
 * school published something while showing nothing it published ([meritBlock]'s
 * rule). Every band the school did publish is a row: no cell function answers
 * "drop this band", so a shorter grid than the school's is not a state this
 * page has.
 */
private fun FlowContent.awardGridBlock(grid: PrintableGrid) {
  val award = grid.award
  // In the order the read served them (`AutomaticAwardsDao` orders by test, then
  // by the bottom of the band, then by the grade point average floor -- the
  // order a school prints its own grid in), so this page never re-sorts a
  // school's table into an order it did not publish.
  val bands = award.bands
  if (bands.isEmpty()) return
  val columns = gridColumnsOf(bands, grid.scoreIsMoney)
  section("report-award-grid") {
    h3 { +grid.heading }
    table("$DATA_TABLE_CLASS report-table report-award-table") {
      caption { gridCaption(grid) }
      thead {
        tr {
          th(scope = ThScope.col) { +SCHOLARSHIP_LABEL }
          columns.forEach { column -> th(scope = ThScope.col) { +column.header } }
        }
      }
      tbody {
        bands.forEach { band ->
          tr {
            // The school's own name for the band, quoted -- the row's header.
            rowHeader(band.awardName)
            columns.forEach { column ->
              textValueCell(column.header, column.cellOf(band)?.let { TextCell.Figure(it) } ?: TextCell.Blank(NONE_PUBLISHED))
            }
          }
        }
      }
    }
  }
}

/**
 * The grid's caption: four sentences, each in its own span so a reader -- and a
 * test -- can find each fact by name, said in the order the page always said
 * them above the old list.
 */
private fun FlowContent.gridCaption(grid: PrintableGrid) {
  val award = grid.award
  span("report-source") { +readOnCitationOf(award.publisher, award.checkedOn) }
  if (!grid.scoreIsMoney) {
    +" "
    span("report-award-no-score-effect") { +NO_SCORE_EFFECT_LINE }
  }
  // The school's OWN sentence about submitting a score where it published one.
  // Where it published none, the silence is stated as the silence it is --
  // never resolved into a "yes" or a "no" the page would be inventing.
  +" "
  span("report-award-requirement") {
    +(award.scoreRequirementAsPublished ?: "This school does not say whether a test score must be submitted.")
  }
  // THROUGH THE ONE DATE SEAM. [UsDate] is the only thing in this repo that
  // gives a date a human shape, and it returns a date and nothing else -- the
  // sentence around it is this caller's.
  award.scoreDeadline?.let { deadline ->
    +" "
    span("report-award-deadline") { +"Scores must be with this school by ${UsDate.of(deadline)}." }
  }
}

/**
 * One column of a printed grid after the band names: its header, and the cell a
 * band has under it, or null where the school printed nothing for that band
 * there (rendered [NONE_PUBLISHED]).
 */
private class GridColumn(
  val header: String,
  val cellOf: (AutomaticAwardBand) -> String?,
)

/**
 * The columns this grid prints, in reading order: what it asks (a score, a
 * grade point average), then what it pays.
 *
 * A COLUMN NO BAND FILLS IS NOT PRINTED. A school that publishes no grade
 * point average axis gets no GPA column, and one whose table a score does not
 * move gets no test score column -- a header over a column of "none published"
 * would say the school left out something it never asked. A column at least
 * one band fills IS printed, and the bands that leave it empty say so.
 */
private fun gridColumnsOf(
  bands: List<AutomaticAwardBand>,
  scoreIsMoney: Boolean,
): List<GridColumn> =
  listOfNotNull(
    GridColumn(TEST_SCORE_COLUMN) { scoreCellOf(it, scoreIsMoney) }.takeIf { column -> bands.any { column.cellOf(it) != null } },
    GridColumn(GPA_COLUMN, ::gpaRangeOf).takeIf { column -> bands.any { column.cellOf(it) != null } },
    paysColumnOf(bands),
  )

/**
 * What a band asks on the test axis: its range on the test's own scale, or the
 * published no-score row's label.
 *
 * A band on [BandScoreAxis.NoScore] names no test: that is the published
 * NO-SCORE row -- the tier a student who submits nothing lands in -- and it is
 * what makes "a score is worth more than no score" visible without inventing a
 * zero band. It is a MEMBER of the axis, so this reads it rather than inferring
 * it from a test it did not find.
 *
 * [NO_SCORE_ROW_LABEL] ONLY WHERE A SCORE IS MONEY. At a school whose grid a
 * score moves, a band on the no-score row IS the published no-score tier and
 * saying so is the point. At a school whose table a score does not move, EVERY
 * band is on that row, and labelling them all "no test score" would invent a
 * test-optional tier out of a school that has no test axis at all -- so there
 * the cell is null, and [gridColumnsOf] drops the column.
 */
private fun scoreCellOf(
  band: AutomaticAwardBand,
  scoreIsMoney: Boolean,
): String? =
  when (val axis = band.score) {
    is BandScoreAxis.NoScore -> NO_SCORE_ROW_LABEL.takeIf { scoreIsMoney }
    is BandScoreAxis.Range -> "${axis.test.displayName} ${axis.low}–${axis.high}"
  }

/**
 * The pays column, its header and its cells decided TOGETHER, from the whole
 * grid, so the header can never claim a unit one of its cells does not have.
 *
 * Every band a dollar figure: "Pays a year" over bare amounts. Any band in the
 * school's own words: "Pays" over amounts that each say "a year" themselves and
 * words quoted verbatim -- "tuition and fees" is not a sum per year, and "The
 * award amount varies from $8,000- $12,000 annually" is the school's sentence,
 * which no header of ours re-labels.
 *
 * THE SCHOOL'S OWN WORDS WHERE IT PUBLISHES NO FIGURE. Nineteen of the 571
 * committed cells pay "tuition and fees" rather than a number, and they are the
 * TOP bands -- rendering a blank or a zero there would show a school's best
 * scholarship as its worst.
 */
private fun paysColumnOf(bands: List<AutomaticAwardBand>): GridColumn {
  val unitInHeader = bands.all { it.awardPerYearUsd != null }
  return GridColumn(if (unitInHeader) PAYS_A_YEAR_COLUMN else PAYS_COLUMN) { paysOf(it, unitInHeader) }
}

/**
 * What this band pays, in the school's own terms, with "a year" said here
 * unless [unitInHeader] -- the one decision [paysColumnOf] made for the column.
 *
 * NEVER ABSENT. `college_automatic_award_bands_award_has_value_check` and
 * [AutomaticAwardBand]'s own `require` both refuse a band carrying neither an
 * amount nor the school's words, so this states that guarantee rather than
 * dropping a published band out of a money table in silence.
 */
private fun paysOf(
  band: AutomaticAwardBand,
  unitInHeader: Boolean,
): String =
  band.awardPerYearUsd?.let { if (unitInHeader) WholeDollars.spoken(it) else "${WholeDollars.spoken(it)} a year" }
    ?: checkNotNull(band.awardAsPublished) {
      "band [${band.id.value}] of award [${band.awardId.value}] states neither an amount nor the school's own " +
        "words (college_automatic_award_bands_award_has_value_check)"
    }

/**
 * The grade point average cell, at whichever end the school published one,
 * under the [GPA_COLUMN] header that names the measure.
 *
 * Either bound may be absent, because a school may publish "3.50 and above" or
 * no grade point average axis at all, and a missing bound is never filled in
 * with a number the school did not print.
 */
private fun gpaRangeOf(band: AutomaticAwardBand): String? {
  // THE ONE HOME FOR THE TYPOGRAPHY ([PublishedGpa]), because the coach's tool
  // says the same published bound aloud and the rule is about a BigDecimal, not
  // about a report.
  val low = band.gpaLow?.let { PublishedGpa.of(it) }
  val high = band.gpaHigh?.let { PublishedGpa.of(it) }
  return when {
    low != null && high != null -> "$low–$high"
    low != null -> "$low and above"
    high != null -> "up to $high"
    else -> null
  }
}

/**
 * What this school PUBLISHES for a National Merit standing (RFC 247) -- every
 * printable row, labelled by the audience the school published it for, and
 * nothing whatever about the student.
 *
 * NOTHING ABOUT THE STUDENT, BY TYPE (D1). [CollegeCost.nationalMerit] comes
 * from `NationalMeritService`'s student-free door, and
 * [NationalMeritPublishedAnswer] carries no score, no standing of hers and no
 * comparison with the family's state. A standing is not a score, which is what
 * lets this page print the package at all; it never lets it print her result.
 *
 * EVERY RESIDENCY ROW, COMPARED WITH NOTHING (D2). The page knows the family's
 * state and does not say which row is theirs: residency for tuition purposes is
 * a status the school decides, and [awardGridBlocks] beside this picks nothing
 * either.
 *
 * ONE SECTION PER PUBLISHER AND READ DATE, so every row under a heading is
 * attributed once, above it (RFC 190): a school whose rows named two would get
 * two attributed sections rather than one misattributed one.
 *
 * A SIBLING OF [awardGridBlocks] AND NOT A CHILD OF IT, for the reason that
 * block is not a child of [meritBlock]: a different corpus, a different page.
 */
private fun FlowContent.nationalMeritBlocks(cost: CollegeCost) {
  cost.nationalMerit
    .mapNotNull { printableMeritRowOf(it) }
    // `groupBy` keeps first-appearance order, so the rows keep the read's order.
    .groupBy { it.award.publisher to it.award.checkedOn }
    .forEach { (citation, rows) -> nationalMeritBlock(publisher = citation.first, readOn = citation.second, rows = rows) }
}

/** One National Merit row this page will print: the school's claim, and the price it was built with. */
private data class PrintableMeritRow(
  val award: NationalMeritAward,
  val claim: NationalMeritClaim.Paying,
  /** The school's price and the base rate it must be said with, as one value: the only home of money here. */
  val priced: PublishedPackage.Priced,
)

/**
 * Which National Merit rows this page prints, decided ONCE so the block and the
 * sentences in [tierKindsSection] about it (the kind, the base rate, K4) can
 * never disagree (D3, D8).
 *
 * Exhaustive with no `else`, the [printableGridOf] shape. Three refusals (D4):
 *
 *  - `NonePublished` -- a verified absence, and a heading over nothing is what
 *    [meritBlock] refuses.
 *  - `Expired` -- our reading is stale, so it is not shown as current money.
 *  - a `Withheld` package -- no cycle we still believe publishes a base rate,
 *    and the school's words ARE the price, so neither its figures nor its words
 *    are spoken without the odds beside them.
 *
 * A school we have not read is not in the answer at all, so it needs no arm.
 */
private fun printableMeritRowOf(answer: NationalMeritPublishedAnswer): PrintableMeritRow? {
  val claim: NationalMeritClaim.Paying
  val published: PublishedPackage
  when (answer) {
    is NationalMeritPublishedAnswer.NonePublished -> {
      return null
    }

    is NationalMeritPublishedAnswer.Expired -> {
      return null
    }

    is NationalMeritPublishedAnswer.Automatic -> {
      claim = answer.claim
      published = answer.published
    }

    is NationalMeritPublishedAnswer.Competitive -> {
      claim = answer.claim
      published = answer.published
    }
  }
  val priced =
    when (published) {
      is PublishedPackage.Priced -> published
      PublishedPackage.Withheld -> return null
    }
  return PrintableMeritRow(award = answer.award, claim = claim, priced = priced)
}

/**
 * One publisher's rows for one school: the heading, the attribution once, then
 * one definition list per row, in the order the read served them.
 *
 * A DEFINITION LIST AND NOT A TABLE (glance/03, brief 0016 D5): a row is a
 * handful of facts, two of them the school's own sentences, and a table of them
 * is six columns wide on a laptop with an empty money cell for every school
 * that publishes words alone. A `<dl>` reads the same on a phone and a laptop:
 * each fact under its own term, each term in the markup rather than in a
 * sentence around the figure.
 *
 * THE ATTRIBUTION STAYS A LINE UNDER THE HEADING, because a `<dl>` has no
 * caption, and one line per (publisher, read date) section is still said once
 * above every row it covers (RFC 190).
 *
 * NOTHING IS SUMMED, RANKED OR CALLED BEST (D6). A school may publish words
 * and no figure, so there is no order by money to compute, and none is.
 *
 * NOT A LINK, for [awardGridBlock]'s reason: this corpus's `source_url` has no
 * scheme-checked door to an `href` yet, so the publisher is named and no anchor
 * is opened.
 */
private fun FlowContent.nationalMeritBlock(
  publisher: String,
  readOn: LocalDate,
  rows: List<PrintableMeritRow>,
) {
  section("report-national-merit") {
    h3 { +NATIONAL_MERIT_HEADING }
    p("report-source") { +readOnCitationOf(publisher, readOn) }
    rows.forEach { row ->
      dl("report-merit-facts") {
        meritFactsOf(row).forEach { fact ->
          dt { +fact.term }
          dd(fact.valueClass) { +fact.value }
        }
      }
    }
  }
}

/** One fact of a printed National Merit row: its term, and its value under the class a reader -- and a test -- finds it by. */
private data class MeritFact(
  val term: String,
  val valueClass: String,
  val value: String,
)

/**
 * One printed row's facts, in the order a parent reads them: whose
 * scholarship, who it is for, whether it is paid, what, and when. A fact the
 * row does not carry is not a pair at all -- no term over nothing.
 *
 * THE MONEY IS THE SCHOOL'S OWN FIGURES, where it publishes any, and no pair
 * where it publishes words alone -- never a `$0` (D6). A count of years with no
 * amount is words alone too: the school's sentence carries it, and "Over 4
 * years" with no figure says nothing. Read off the [PublishedPackage.Priced], so
 * no figure reaches this page except inside the value that carries the base
 * rate.
 */
private fun meritFactsOf(row: PrintableMeritRow): List<MeritFact> =
  listOfNotNull(
    // The school's own name for its scholarship, quoted where it names one.
    row.claim.awardName?.let { MeritFact(SCHOLARSHIP_LABEL, "report-merit-name", it) },
    MeritFact(MERIT_FOR_TERM, "report-merit-standing", meritStandingOf(row)),
    MeritFact(MERIT_GUARANTEE_TERM, "report-merit-guarantee", guaranteeOf(row.claim.guarantee)),
    perYearFactOf(row.priced),
    totalFactOf(row.priced),
    MeritFact(MERIT_WORDS_TERM, "report-merit-words", row.priced.asPublished),
    MeritFact(MERIT_FIRST_CHOICE_TERM, "report-merit-first-choice", firstChoiceLineOf(row.claim.firstChoice)),
    row.claim.applyDeadline?.let { MeritFact(MERIT_APPLY_TERM, "report-merit-apply", applyDateOf(it)) },
  )

/**
 * The yearly figure, where the school publishes one. THE YEARS QUALIFY THE
 * TOTAL ([totalFactOf]), so they ride here ("$17,500 for 4 years") only on a
 * row with no total -- the count is never lost and never said twice.
 */
private fun perYearFactOf(priced: PublishedPackage.Priced): MeritFact? =
  priced.perYearUsd?.let { perYear ->
    val spoken = WholeDollars.spoken(perYear)
    val years = yearsOf(priced)
    val value = if (years != null && priced.totalUsd == null) "$spoken for $years" else spoken
    MeritFact(MERIT_PER_YEAR_TERM, "report-merit-per-year", value)
  }

/** The total, where the school publishes one, with the years beside it ("$70,000 over 4 years"). */
private fun totalFactOf(priced: PublishedPackage.Priced): MeritFact? =
  priced.totalUsd?.let { total ->
    val spoken = WholeDollars.spoken(total)
    MeritFact(MERIT_TOTAL_TERM, "report-merit-total", yearsOf(priced)?.let { "$spoken over $it" } ?: spoken)
  }

/** The count of years a package runs, said as a reader says it, or null where the school publishes none. */
private fun yearsOf(priced: PublishedPackage.Priced): String? = priced.years?.let { if (it == 1) "1 year" else "$it years" }

/** The school's application deadline, under the "Apply by" term: its day through the date seam, then its own words. */
private fun applyDateOf(deadline: ApplyDeadline): String = "${UsDate.of(deadline.on)}. ${deadline.asPublished}"

/**
 * Who the row is for: the standing, the entering class it was published for
 * (D7 -- a school's headline figure can be for a different entering class than
 * the one a reader assumes), and the school's audience where it publishes one.
 * Under the "For" term, so the value is the rest of the old sentence.
 */
private fun meritStandingOf(row: PrintableMeritRow): String {
  val audience =
    publishedAudienceOf(row.award.residencyBasis) { throw CorruptAwardResidencyBasisException(row.award) }
      ?.let { ", $it" }
      .orEmpty()
  return "${standingOf(row.claim.standing)} entering in ${row.award.entryYear.label}$audience"
}

/**
 * D5 in one word: an `automatic` row is paid; a `competitive` one is considered
 * and never said as paid. What each word promises is
 * [NATIONAL_MERIT_GUARANTEE_KIND_SENTENCE]'s, once for the page.
 */
private fun guaranteeOf(guarantee: AwardGuarantee): String =
  when (guarantee) {
    AwardGuarantee.AUTOMATIC -> "Paid"
    AwardGuarantee.COMPETITIVE -> "Considered"
  }

/** The standing, in this page's own words (the chat tool's labels are the coach's, and say "her"). */
private fun standingOf(standing: QualifyingStanding): String =
  when (standing) {
    QualifyingStanding.FINALIST -> "National Merit Finalists"
    QualifyingStanding.SEMIFINALIST -> "National Merit Semifinalists"
    QualifyingStanding.COMMENDED -> "National Merit Commended Students"
  }

/** The first-choice rule in the school's own words, the silence where it states none. */
private fun firstChoiceLineOf(rule: FirstChoiceRule?): String =
  when (rule) {
    is FirstChoiceRule.Required -> {
      rule.deadline?.let { "${rule.asPublished} Name it first choice by ${UsDate.of(it)}." } ?: rule.asPublished
    }

    is FirstChoiceRule.NotRequired -> {
      rule.asPublished
    }

    null -> {
      FIRST_CHOICE_UNSAID
    }
  }

/**
 * The base rate, said once for the page (D3), with its own publisher and read
 * date because the block it sits in names none.
 *
 * READ OFF THE PRINTED ROWS' OWN PRICES, so no National Merit figure is on this
 * page unless the base rate it was built with is too. Every price in one read
 * carries the same base rate (one `PublishedBaseRate.of` per read), so a page
 * whose rows carried two is corruption and is named, not a choice to make here.
 */
private fun baseRateSentenceOf(rows: List<PrintableMeritRow>): String {
  val rates = rows.map { it.priced.baseRate }.distinct()
  val baseRate =
    rates.singleOrNull()
      ?: error(
        "the printed National Merit rows carry [${rates.size}] base rates " +
          "${rates.map { "${it.competitionYear} (${it.publisher}, read ${it.checkedOn})" }}; " +
          "one read builds every price with one",
      )
  val share =
    checkNotNull(baseRate.finalistShareOfEntrants) {
      "the [${baseRate.competitionYear}] base rate publishes no Finalist share, which PublishedBaseRate.of never chooses"
    }
  return "About ${share.spokenPercent()} of entrants in the ${baseRate.competitionYear} National Merit competition " +
    "reach Finalist. ${readOnCitationOf(baseRate.publisher, baseRate.checkedOn)}"
}

/**
 * Debt context: TWO publishers with two vintages in one section (RFC 175, D9).
 *
 * The parent-facing artifact is where a federal-only debt figure misleads most,
 * so the school's own Common Data Set half sits beside it. Four rules keep that
 * honest, and they are stated here because they are the whole reason the two
 * paragraphs are allowed to share a section:
 *
 * - The Scorecard sentence keeps saying "federal loans"; that the source
 *   publishes no year for it is said once for the page, in the kinds section
 *   (RFC 260 K3).
 * - The CDS table names the SCHOOL as the claimant and carries its filing
 *   year, in its caption, because these are self-reported survey answers (D10).
 * - The two are never summed, never differenced and never presented as one debt
 *   number. Nothing here adds them, and nothing may.
 * - Where the school filed no private figure, this says so, rather than leaving
 *   a family with the impression that federal is the whole of it.
 *
 * No figure in this section is a price, and none of it is ever subtracted from
 * one (brief 0003).
 *
 * The figures by field of study (RFC 251) sit BETWEEN the two: every one of
 * them names its own publisher, and after the borrowing caption they would read
 * as the school's own Common Data Set figures.
 */
private fun FlowContent.debtBlock(
  cost: CollegeCost,
  subjects: List<CollegeSubjectReturn>,
) {
  val debt = shownDebtOf(cost)
  section("report-debt") {
    if (debt == null) {
      // The Scorecard suppresses this figure for privacy more often than it
      // suppresses any other, so the school's-silence sentence was the WRONG
      // one at exactly the schools that reach it most.
      p {
        +blankFor(
          cost,
          CostField.MEDIAN_DEBT_AT_COMPLETION_USD,
          "This school does not report the federal loan debt students carried when they finished.",
        )
      }
    } else {
      // The rule's one permanent named exemption (RFC 260, brief 0016 D4): one
      // figure, one sentence, and it stays prose because a one-cell table says
      // less than the sentence does.
      p("report-debt-federal") { +shownDebtSentence(cost, debt) }
    }
    programReturnBlocks(subjects)
    borrowingBlock(cost)
  }
}

/**
 * What graduates of the student's chosen subjects earn and owe at this school
 * (RFC 251): one block per stored subject, ALL of them and in stored order, and
 * inside it every matched program by its published title, in CIP order.
 *
 * Nobody's favourite: no subject or program is promoted, sorted by value,
 * summarised or dropped. Every word is [ProgramReturnCopy]'s, and so is the
 * choice between one mark for the subject, a sentence per program and a table;
 * this only lays out the [ProgramReturnCopy.SubjectLayout] it is handed.
 */
private fun FlowContent.programReturnBlocks(subjects: List<CollegeSubjectReturn>) {
  subjects.forEach { subjectReturnBlock(it) }
}

/** One subject's block: the [ProgramReturnCopy.SubjectLayout] it is handed, under its lead or its caption. */
private fun FlowContent.subjectReturnBlock(subject: CollegeSubjectReturn) {
  val lead = ProgramReturnCopy.subjectLeadOf(subject.subjectName)
  div("report-program-return") {
    when (val layout = ProgramReturnCopy.subjectLayoutOf(subject.college)) {
      is ProgramReturnCopy.SubjectLayout.Mark -> {
        p("report-program-subject") { +"$lead ${layout.statement}" }
      }

      is ProgramReturnCopy.SubjectLayout.Statements -> {
        subjectStatements(lead, layout)
      }

      is ProgramReturnCopy.SubjectLayout.Table -> {
        programTable(ProgramReturnCopy.subjectCaptionOf(subject.subjectName, subject.college.name), layout.table)
      }
    }
  }
}

private fun FlowContent.subjectStatements(
  lead: String,
  layout: ProgramReturnCopy.SubjectLayout.Statements,
) {
  p("report-program-subject") { +lead }
  layout.programs.forEach { program ->
    p("report-program") {
      span("report-program-title") { +ProgramReturnCopy.programLabelOf(program) }
      +" ${program.statement}"
    }
  }
}

/**
 * One subject's programs at one school, one row each (brief 0016 D2). The
 * publisher is in each money column's header, the cohorts in the kinds section,
 * and no `tfoot`: earnings and debt are never added together.
 */
private fun FlowContent.programTable(
  subjectCaption: String,
  programs: ProgramReturnCopy.ProgramTable,
) {
  table("$DATA_TABLE_CLASS report-table report-program-table") {
    caption { +subjectCaption }
    thead {
      tr {
        th(scope = ThScope.col) { +ProgramReturnCopy.PROGRAM_COLUMN_HEADER }
        programs.columns.forEach { column -> th(scope = ThScope.col) { +column.header } }
      }
    }
    tbody { programs.rows.forEach { programRow(it, programs.columns) } }
  }
  programs.imputedNotes.forEach { note -> p("report-program-imputed") { +note } }
}

private fun TBODY.programRow(
  row: ProgramReturnCopy.ProgramRow,
  columns: List<ProgramReturnCopy.ProgramColumn>,
) {
  tr {
    rowHeader(row.title)
    when (row) {
      is ProgramReturnCopy.ProgramRow.Cells -> {
        columns.forEach { column -> textValueCell(column.header, textCellOf(row.cells.getValue(column.measure))) }
      }

      is ProgramReturnCopy.ProgramRow.WholeRow -> {
        spanningValueCell(SchoolFigure.Blank(row.statement), columns.size)
      }
    }
  }
}

private fun textCellOf(cell: ProgramReturnCopy.ProgramCell): TextCell =
  when (cell) {
    is ProgramReturnCopy.ProgramCell.Figure -> TextCell.Figure(cell.text)
    is ProgramReturnCopy.ProgramCell.Estimate -> TextCell.Figure(cell.text)
    is ProgramReturnCopy.ProgramCell.Gap -> TextCell.Blank(cell.statement)
  }

/**
 * The median federal debt, said, with what KIND of number it is under it (RFC
 * 179): `GRAD_DEBT_MDN` is the federal loan file, not something the college
 * filed, and the shown paragraph is where a family first hears that.
 *
 * One level down from the block that decides this is a `p`: what the paragraph
 * SAYS is a sentence-building decision, not layout. Composed by the seam that
 * owns the composition, never by a `+ " "` here -- the ORDER of the two
 * sentences is [AssuranceTierCopy]'s, once, for every surface.
 *
 * IT NAMES ITS OWN PUBLISHER (RFC 190). This paragraph and the borrowing block
 * below it are two publishers in one section: the block header attributes
 * everything under it to the school's own Common Data Set, so a federal figure
 * that named nobody would read as covered by that header. The name comes from
 * the CELL the figure came from, through [MoneySourceCopy] -- never hand-typed,
 * and never at all where the domain resolved no cell.
 */
private fun shownDebtSentence(
  cost: CollegeCost,
  debt: Int,
): String {
  // The figure alone. "The source publishes no year for this figure" used to
  // follow it at every school; it is a fact about the measure, not about one
  // school's number, and is said once for the page as K3 (RFC 260).
  val figure = "students who finished here carried a median of ${WholeDollars.spoken(debt)} in federal loans."
  // UNREACHABLE for a shown debt figure, and provably so: [CollegeCost.medianDebtAtCompletionUsd]
  // and this note read the SAME cohort row -- a value here means that row exists, and a row with
  // provenance always yields a note (RFC 179 D6). It stays because the note list is assembled by
  // another module: a page may not invent a tier the domain did not resolve, so where the note is
  // somehow absent the figure is said alone rather than under a guessed one -- and unattributed,
  // under the page's own sources section, because a page may not invent a publisher either.
  val note =
    cost.statusNoteFor(CostField.MEDIAN_DEBT_AT_COMPLETION_USD)
      ?: return figure.replaceFirstChar { it.uppercase() }
  // This figure's OWN publisher, read off the cell it came from (RFC 184) and spoken by the one
  // seam that names publishers, never typed here: the borrowing block below is attributed to the
  // school's own Common Data Set, and a federal figure left unattributed beside it reads as
  // covered by that header.
  return "${MoneySourceCopy.openingLabelOf(note.cell.source)} reports that $figure"
}

/**
 * The figures this page says in PROSE, and therefore the figures whose KIND it
 * states (RFC 191).
 *
 * The covered set of the tier block, derived from the same condition the
 * paragraph itself renders on -- one federal debt figure per school that has
 * one -- rather than listed beside it. A second list would be free to drift into
 * naming a figure the page does not print, which is the header-covers-the-wrong-
 * figure failure this block exists to avoid.
 *
 * Price-table cells are NOT here on purpose. A blank cell still says its own
 * status sentence with its own tier beside it ([blankFor]), because a figure
 * with no number is not a kind of number -- the exclusion RFC 189 wrote into
 * the tool description, on this surface. A shown price cell states no tier
 * today and states none after RFC 260; that gap is RFC 191's open item, not a
 * thing this function may quietly close. The MERIT table's shown cells are the
 * one exception, by decision (brief 0016 D10) and in [meritFiguresOf]: the
 * kinds section is where a Common Data Set figure's kind is said once.
 */
private fun proseFiguresOf(
  cost: CollegeCost,
  subjects: List<CollegeSubjectReturn>,
): List<CoveredFigure> = listOfNotNull(debtFigureOf(cost)) + programFiguresOf(cost, subjects)

/** Every figure this school's section states a kind for in the kinds block: its prose figures and its merit table's. */
private fun coveredFiguresOf(
  cost: CollegeCost,
  subjects: List<CollegeSubjectReturn>,
): List<CoveredFigure> = proseFiguresOf(cost, subjects) + meritFiguresOf(cost)

/**
 * The federal median debt WHERE THE PAGE SHOWS IT, or null: the one decision
 * [debtBlock] prints the figure on, [debtFigureOf] covers its tier on, and
 * [pageKindsOf] says K3 on, for [shownMeritOf]'s reason -- a second rule in any
 * of them would be free to say the figure is undated under no figure, or state
 * a tier for a sentence the page does not print.
 */
private fun shownDebtOf(cost: CollegeCost): Int? = cost.medianDebtAtCompletionUsd

private fun debtFigureOf(cost: CollegeCost): CoveredFigure? {
  if (shownDebtOf(cost) == null) return null
  val note = cost.statusNoteFor(CostField.MEDIAN_DEBT_AT_COMPLETION_USD) ?: return null
  return CoveredFigure(
    collegeName = cost.name,
    figure = FigureIdentity.Cost(CostField.MEDIAN_DEBT_AT_COMPLETION_USD),
    assurance = SpokenAssurance(note.assurance),
  )
}

/**
 * The figures by field of study this school's blocks speak of (RFC 251), with
 * a number or without one -- which ones, and on what tier, is
 * [ProgramReturnCopy.coveredFiguresOf]'s.
 */
private fun programFiguresOf(
  cost: CollegeCost,
  subjects: List<CollegeSubjectReturn>,
): List<CoveredFigure> = subjects.flatMap { ProgramReturnCopy.coveredFiguresOf(cost.name, it.college) }

/**
 * The figures whose tier this page states IN A CELL rather than in the block
 * ([blankFor]), so the block can tell whether a bare label would reach one of
 * them (RFC 191 D4).
 *
 * A blank keeps its own two sentences and is covered by no group -- a figure
 * with no number is not a kind of number. But the label is the same label: with
 * one school's debt figure shown and another's withheld on a different tier, an
 * unqualified "the median federal loan debt" above one sentence would claim the
 * other figure too. So these are handed to the grouping as context and never
 * rendered by it.
 *
 * Exactly the four statuses [blankFor] speaks a tier for, read from the same
 * enum it switches on: a status this page answers with the caller's own blank
 * says no tier, and adding it here would qualify a label against a sentence
 * nobody printed.
 */
private fun blankFiguresOf(cost: CollegeCost): List<CoveredFigure> =
  cost.figureStatuses
    .filter {
      it.status in
        setOf(
          FigureStatus.IMPUTED_BY_PUBLISHER,
          FigureStatus.SUPPRESSED_BY_PUBLISHER,
          FigureStatus.NOT_APPLICABLE,
          FigureStatus.NOT_COLLECTED_BY_US,
        )
    }.map { CoveredFigure(collegeName = cost.name, figure = FigureIdentity.Cost(it.field), assurance = SpokenAssurance(it.assurance)) }

/**
 * What kind of numbers this page's prose figures are -- each sentence said ONCE,
 * for the set of figures it covers (RFC 191, brief 0009 D3).
 *
 * It replaces the same thirty words printed inside every school's debt
 * paragraph. Which sentence, in what order, and which figures each one covers is
 * [AssuranceTierGrouping]'s -- this file authors no money copy -- and the
 * section is ABSENT where the page shows no prose figure at all, rather than
 * rendering a heading over nothing.
 *
 * It sits above [sourcesSection] because both are standing material about the
 * whole page: what kind of numbers these are, then who published them and what
 * this is not.
 */
private fun FlowContent.tierKindsSection(
  colleges: List<CollegeCost>,
  programSlices: Map<CollegeId, List<CollegeSubjectReturn>>,
  printsAProgram: Boolean,
) {
  val kinds = pageKindsOf(colleges, programSlices, printsAProgram)
  if (kinds.isEmpty) return
  section("report-kinds") {
    h2 { +KINDS_HEADING }
    kinds.groups.forEach { group ->
      p("report-kind") {
        // Two sentences, never one: the scope names the figures and the tier
        // sentence is [AssuranceTierCopy]'s, byte for byte, never re-worded here
        // (RFC 179). No count, no rank, no score sits beside either. Asked of
        // the copy seam WITH the page's link answer rather than read off the
        // group, because the group's sentence always keeps the self-report
        // promise and this page does not always keep it (RFC 200).
        span("report-kind-scope") { +group.scope }
        +" "
        +AssuranceTierCopy.statementOf(group.tier, kinds.coveredFilingLink)
      }
    }
    // ONCE FOR THE PAGE (RFC 191), here and never inside a school's block: a
    // family reading ten schools is told once what kind of number a published
    // grid is, in the one section that exists to say what kind of numbers these
    // are.
    if (kinds.printsAGrid) p("report-kind") { +AWARD_KIND_SENTENCE }
    if (kinds.meritRows.isNotEmpty()) {
      p("report-kind") { +NATIONAL_MERIT_KIND_SENTENCE }
      p("report-kind report-merit-base-rate") { +baseRateSentenceOf(kinds.meritRows) }
      // K4 (glance/03): what the one word in each row's "Paid or considered"
      // pair promises, said once here rather than as a sentence in every row.
      p("report-kind report-kind-merit-guarantee") { +NATIONAL_MERIT_GUARANTEE_KIND_SENTENCE }
    }
    // Once for the page, like the two above (brief 0011 D19). It leans on the
    // administrative-record kind sentence for "only students who got federal
    // aid", which every printed program's figures are grouped under above.
    if (kinds.printsAProgram) p("report-kind report-program-coverage") { +ProgramReturnCopy.COVERAGE_SENTENCE }
    // K5 (glance/04): the cohorts and dollar year every program table's cells
    // share, said once here rather than as a parenthesis on every figure.
    kinds.programCohorts?.let { p("report-kind report-program-cohorts") { +it } }
    // K1-K3 (RFC 260): the three hedges the merit and borrowing blocks and the
    // debt paragraph used to repeat at every school, each said once for the
    // page, each only when a block it is about is on the page.
    if (kinds.printsAMeritTable) p("report-kind report-kind-merit") { +MERIT_KIND_SENTENCE }
    if (kinds.printsABorrowingTable) p("report-kind report-kind-borrowing") { +BORROWING_KIND_SENTENCE }
    if (kinds.showsADebtFigure) p("report-kind report-kind-debt-year") { +DEBT_YEAR_KIND_SENTENCE }
  }
}

/**
 * Everything the kinds section says, decided ONCE for the page (RFC 260):
 * which tier groups, which once-per-page sentences, and whether the one tier
 * sentence that promises a link may make the promise.
 *
 * A value rather than locals in [tierKindsSection], so "is this sentence on the
 * page" is stated in one place and both the section's absence ([isEmpty]) and
 * each paragraph read that one statement. Before it, every sentence was decided
 * twice -- in the early return and in the body -- and a sentence added to one
 * and not the other was silently never printed, or printed under a heading the
 * gate had already decided not to open.
 */
private data class PageKinds(
  val groups: List<SpokenTierGroup>,
  /**
   * Whether the covered figures' filings are all LINKED on this page, the
   * answer the self-report tier sentence is said under (RFC 200). The only
   * filing-backed figures the covered set holds are the merit tables', so it
   * is asked of exactly those; every other tier's sentence promises no link
   * and reads the same either way.
   */
  val coveredFilingLink: FilingLink,
  val printsAGrid: Boolean,
  val meritRows: List<PrintableMeritRow>,
  val printsAProgram: Boolean,
  val programCohorts: String?,
  val printsAMeritTable: Boolean,
  val printsABorrowingTable: Boolean,
  val showsADebtFigure: Boolean,
) {
  /** True when the page shows nothing whose kind this section states, so no heading is opened over nothing. */
  val isEmpty: Boolean
    get() =
      groups.isEmpty() &&
        !printsAGrid &&
        meritRows.isEmpty() &&
        !printsAProgram &&
        programCohorts == null &&
        !printsAMeritTable &&
        !printsABorrowingTable &&
        !showsADebtFigure
}

/**
 * The page's [PageKinds], each fact asked of the one function its table or
 * paragraph renders on, never re-derived from the data here: a second rule
 * would be free to say a sentence about a block the page does not print, or
 * stay silent about one it does.
 */
private fun pageKindsOf(
  colleges: List<CollegeCost>,
  programSlices: Map<CollegeId, List<CollegeSubjectReturn>>,
  printsAProgram: Boolean,
): PageKinds {
  val shownMerit = colleges.mapNotNull { shownMeritOf(it) }
  return PageKinds(
    groups =
      AssuranceTierGrouping.groupsOf(
        figures = colleges.flatMap { coveredFiguresOf(it, programSlices.getValue(it.collegeId)) },
        spokenElsewhere = colleges.flatMap { blankFiguresOf(it) },
      ),
    // EVERY shown merit filing, not any: one sentence covers all of their
    // figures, so it may promise the link only where every one of them keeps
    // it. Asked of [CdsCitation.linksToFile], the one place that question is
    // answered -- the same predicate [sourceLink] drops the anchor on. A page
    // with no merit table holds no filing here, and LINKED is then the
    // [FilingLink] rule for a caller holding none.
    coveredFilingLink = if (shownMerit.all { it.source.linksToFile }) FilingLink.LINKED else FilingLink.NOT_LINKED,
    // WHETHER ANY GRID IS PRINTED, asked of [printableGridOf] rather than
    // re-derived from the answer arms here.
    printsAGrid = colleges.any { cost -> cost.automaticAwards.any { printableGridOf(it) != null } },
    // The same rule for National Merit rows (RFC 247 D3, D8), asked of
    // [printableMeritRowOf] -- and the base rate is read off these same rows.
    meritRows = colleges.flatMap { cost -> cost.nationalMerit.mapNotNull { printableMeritRowOf(it) } },
    printsAProgram = printsAProgram,
    programCohorts = ProgramReturnCopy.cohortsSentenceOf(programSlices.values.flatten().map { it.college }),
    // And K1-K3 (RFC 260): [shownMeritOf], [reportedBorrowingOf] and
    // [shownDebtOf], the decisions the merit table, the borrowing table and
    // the debt paragraph print on.
    printsAMeritTable = shownMerit.isNotEmpty(),
    printsABorrowingTable = colleges.any { reportedBorrowingOf(it) != null },
    showsADebtFigure = colleges.any { shownDebtOf(it) != null },
  )
}

/**
 * What this school says its own graduates borrowed, by loan type -- as ROWS
 * (RFC 260, brief 0016 D1).
 *
 * Every row and every sentence comes from [BorrowingWire.rowsOf], the row
 * reading of the one home of this copy, so the page and the coach narrate a
 * filing from the same figures, in the same order, with the same absences said
 * aloud -- `BorrowingWireTest` holds the rows to the sentences the coach reads.
 * Nothing is rendered for a school with no filing: the page is not the place to
 * explain our own corpus coverage.
 *
 * ATTRIBUTED ONCE, IN THE CAPTION (RFC 190, superseding RFC 175 D10's
 * per-sentence rule): the caption names the school, its cycle and its Common
 * Data Set, as a link (RFC 196), and every cell below it is a figure from that
 * one filing. The caption cannot be dropped -- the rows and their citation are
 * one value -- and no figure from another publisher is rendered under it: the
 * federal median debt above names its own publisher ([shownDebtSentence]).
 *
 * The two hedges the block header used to carry ("not this year's freshmen,
 * and not all its undergraduates") are said once for the page, in the kinds
 * section (K2); [BorrowingWire.HEADER_TAIL] itself is unchanged for the coach.
 *
 * NO TOTAL ROW AND NO `tfoot`: the loan types overlap and are never added
 * together (RFC 175 D3). The gap sentences -- our unread cell, the publisher's
 * withholding, the private absence -- render as one `<p>` each under the table,
 * verbatim with the page's trailing period, exactly as before.
 */
private fun FlowContent.borrowingBlock(cost: CollegeCost) {
  val coverage = reportedBorrowingOf(cost) ?: return
  val rows = BorrowingWire.rowsOf(coverage)
  val borrowedColumn = borrowedColumnOf(rows.graduatingClass)
  div("report-borrowing") {
    table("$DATA_TABLE_CLASS report-table report-borrowing-table") {
      caption {
        sourceLink(rows.citation)
        +BORROWING_CAPTION_TAIL
      }
      thead {
        tr {
          th(scope = ThScope.col) { +LOAN_TYPE_COLUMN }
          th(scope = ThScope.col) { +borrowedColumn }
          th(scope = ThScope.col) { +OWED_COLUMN }
        }
      }
      tbody {
        rows.rows.forEach { row ->
          tr {
            rowHeader(rowLabelOf(row))
            textValueCell(borrowedColumn, borrowedCellOf(row))
            textValueCell(OWED_COLUMN, owedCellOf(row))
          }
        }
      }
    }
    rows.gaps.forEach { gap -> p("report-borrowing-gap") { +(BorrowingWire.sentenceOf(gap, rows.citation) + ".") } }
  }
}

/**
 * The borrowed column's header. It carries the class every share divides by,
 * once, where a reader looks for it; with no class on file the column is headed
 * by the bare verb, and every cell under it is then [NO_SHARE_SHOWN].
 */
private fun borrowedColumnOf(graduatingClass: Int?): String =
  graduatingClass?.let { "$BORROWED_COLUMN, of the ${WholeCount.spoken(it)} who graduated" } ?: BORROWED_COLUMN

/**
 * A row's header: the wire's own words for the loan type, sentence-initial. The
 * wire hands them out in mid-sentence form, the form its sentences need, so the
 * table capitalises them here rather than keeping a second copy of the words.
 */
private fun rowLabelOf(row: LoanRow): String = row.spokenLoanType.replaceFirstChar { it.uppercase() }

/**
 * This school's borrowing filing WHERE THE PAGE SHOWS IT, or null: the one
 * decision [borrowingBlock] opens the table on and [tierKindsSection] says K2
 * on, for [shownMeritOf]'s reason.
 *
 * Exhaustive over the four states, not a pair of early returns: the page says
 * nothing about our own corpus coverage, and an arm that fell through by
 * accident would print a school's section under another school's rule. The
 * three silences are all about which filing WE hold; the coach payload is where
 * each one is spoken.
 */
private fun reportedBorrowingOf(cost: CollegeCost): BorrowingCoverage.Reported? =
  when (val coverage = cost.borrowing) {
    BorrowingCoverage.NoFiling, is BorrowingCoverage.NoBlock, BorrowingCoverage.NotReadByUs -> null
    is BorrowingCoverage.Reported -> coverage
  }

/**
 * The borrowed cell: the count and its share, from the one CONSISTENT pair the
 * row carries, or the neutral blank (RFC 260 D-A). The share is never shown
 * without the count it is derived from, and the count never without the class
 * in the column header above it -- [BorrowedShare] cannot hold one without the
 * other.
 */
private fun borrowedCellOf(row: LoanRow): TextCell {
  val borrowed = row.borrowed ?: return TextCell.Blank(NO_SHARE_SHOWN)
  return TextCell.Figure("${WholeCount.spoken(borrowed.borrowers)} (${borrowed.share.spokenPercent()})")
}

/**
 * The owed cell: the average, or -- where the filing answers the column and no
 * figure is shown -- the frozen status sentence for WHOSE silence that is
 * ([OwedAverage.Withheld], the same words the gap paragraph under the table
 * frames), else the filing's own absence blank.
 *
 * Agentless on purpose (RFC 177), with the publisher in hand: the caption above
 * already names the school's Common Data Set, so a sentence naming it again
 * would say the same document twice in one table. The sentence was resolved
 * when the row was built ([BorrowingWire.rowsOf]), so this is layout only.
 */
private fun owedCellOf(row: LoanRow): TextCell =
  when (val owed = row.averageOwed) {
    is OwedAverage.Shown -> TextCell.Figure(WholeDollars.spoken(owed.usd))
    is OwedAverage.Withheld -> TextCell.Blank(owed.statement)
    OwedAverage.NotInFiling -> TextCell.Blank(NOT_IN_FILING)
  }

/**
 * One CDS filing, said as a LINK to the college's own file (RFC 196, brief 0009
 * D15).
 *
 * THE ONE PLACE THIS PAGE OPENS AN ANCHOR TO A SCHOOL'S FILE; the only other
 * anchor is the sources section's one link to the College Scorecard, said only
 * with a figure by field of study ([fieldOfStudySources]). Merit and borrowing
 * are two blocks with one promise behind them: `AssuranceTierCopy`'s
 * voluntary-self-report sentence ends "We link to the college's own file so you
 * can see it", and that sentence is byte-frozen against the immutable v19
 * prompt row -- it cannot be reworded, so the link has to exist. One helper
 * rather than two renders means a third CDS block cannot be added with a
 * different link shape.
 *
 * - The TARGET is `source_url`, the college's own file, which is what the
 *   sentence promises. `archive_url` -- our mirror -- is not rendered beside it
 *   and never instead of it; it stays reachable on the coach wire, where every
 *   citation still carries both.
 * - The TEXT is [CdsCitation.citedAs], the string this page already printed. The
 *   url is heterogeneous and long (1,744 characters at its worst in our own
 *   seed) and is never shown to anybody.
 * - [CdsCitation.linkTargetOrNull] answers null for a filing we no longer point
 *   at (RFC 200), and OTHERWISE asserts the scheme and FAULTS rather than
 *   rendering: `href` is where escaping does not save a logged-out parent. The
 *   order is deliberate -- a filing whose file is gone needs no link, so its
 *   url is never judged -- and it is tested.
 * - `rel="noreferrer"` even though the report route already sends
 *   `Referrer-Policy: same-origin`, which gives a cross-origin link no
 *   `Referer` for exactly this reason. The header rides the
 *   ROUTE and the link is on the PAGE; a page rendered from a second route later
 *   would otherwise lose the guarantee with nothing failing.
 *
 * AND THE SAME WORDS WITH NO ANCHOR, where the college's own file is recorded
 * as gone (RFC 200 D6). Only the anchor goes: the family still learns whose
 * claim this is and in which cycle, because [CdsCitation.citedAs] is printed
 * either way. A link to a 404 is a worse answer than no link, and dropping the
 * citation with it would take a fact off the page to hide a broken url.
 */
private fun FlowContent.sourceLink(citation: CdsCitation) {
  val target = citation.linkTargetOrNull()
  if (target == null) {
    +citation.citedAs
  } else {
    a(href = target) {
      rel = "noreferrer"
      +citation.citedAs
    }
  }
}

/**
 * Where every figure came from, and the sentence the whole page exists to keep
 * honest.
 *
 * [sources] are the publishers that actually won the canonical money figures on
 * THIS page (RFC 177 D5), so the first sentence is derived rather than typed. It
 * used to name the College Scorecard for every figure while the loader ranks
 * both IPEDS surveys above it -- a false attribution on a parent-facing page.
 * With no such figure at all the sentence is omitted: there is no publisher to
 * name.
 *
 * The sentence says "cost, price and federal debt" because that is what the set
 * covers: `CollegeFigures.servedSourcesOf` walks every `CostField`, and
 * `MEDIAN_DEBT_AT_COMPLETION_USD` is one of them. Said as "cost and price" it
 * named a Scorecard that published only the debt figure as a publisher of this
 * school's prices -- the same shape of false attribution, one size smaller.
 *
 * The merit-and-borrowing sentence stays hand-written and stays true: those
 * figures are not canonical money rows, they carry no [MoneySource], and each
 * one is already cited to its own school's Common Data Set beside it.
 *
 * The figures by field of study get their own paragraph, said only when a
 * school on the page runs a matched program ([printsAProgram], RFC 251).
 */
private fun FlowContent.sourcesSection(
  sources: List<MoneySource>,
  printsAProgram: Boolean,
) {
  section("report-sources") {
    h2 { +"Sources and what this is not" }
    p {
      // ABSENT, never an empty string spliced into the paragraph: with no money
      // figure on the page there is no publisher to name (RFC 177). The
      // nullable reaches its point of use, so the sentence is EMITTED or not,
      // and nothing here restores the `""` sentinel `spokenListOf` returns null
      // instead of.
      moneyAttributionSentence(sources)?.let { +it }
      +(
        "The merit and " +
          "borrowing figures come from each school's own Common Data Set, cited beside them. The federal " +
          "debt figure and the school's own borrowing figures are different measures from different " +
          "publishers over different groups of students, so they are never added together and never " +
          "compared with each other."
      )
    }
    if (printsAProgram) fieldOfStudySources()
    p {
      +(
        "These are averages for past students at each school. They are not an offer, and no figure here is a " +
          "price this family has been quoted."
      )
    }
    p {
      +(
        "Only a school's own financial aid offer is a price for this family. Loans and work-study are never " +
          "subtracted from any price here."
      )
    }
  }
}

/**
 * The attribution sentence for this page's money figures, or NULL where there is
 * no publisher to name (RFC 177 D5).
 *
 * Composition, kept out of [sourcesSection]: the renderer lays the section out
 * and this decides what it SAYS. The trailing space is part of the sentence,
 * because the paragraph continues with the merit-and-borrowing sentence right
 * after it.
 *
 * Null rather than an empty string, all the way from `MoneySourceCopy.spokenListOf`
 * to the emit: absence has one representation on this path.
 */
private fun moneyAttributionSentence(sources: List<MoneySource>): String? =
  MoneySourceCopy.spokenListOf(sources)?.let { "The cost, price and federal debt figures come from $it. " }

/**
 * Who published the figures by field of study, with the page's one link to the
 * College Scorecard (brief 0011 D34, RFC 251). Every word and the target are
 * [ProgramReturnCopy.SOURCES]'s; the target is a typed constant, so there is no
 * stored url to judge here. `rel="noreferrer"` for [sourceLink]'s reason.
 */
private fun FlowContent.fieldOfStudySources() {
  val sentence = ProgramReturnCopy.SOURCES
  p("report-field-of-study-sources") {
    +sentence.lead
    a(href = sentence.link.toString()) {
      rel = "noreferrer"
      +sentence.linkText
    }
    +sentence.tail
  }
}
