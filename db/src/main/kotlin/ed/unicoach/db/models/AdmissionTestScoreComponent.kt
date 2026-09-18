package ed.unicoach.db.models

/**
 * One score a student may report for herself (RFC 207, slice test/06): which
 * part of which admission test, and the published scale a stored number must
 * sit on.
 *
 * OUR OWN enumeration, so the schema is `TEXT` + `CHECK IN (...)` and this is
 * the one Kotlin home for it (CLAUDE.md, the [AdmissionTest] precedent). It
 * backs `admission_test_scores.component`.
 *
 * The SCALE is a property of the COMPONENT, never of the table (brief 0010
 * D13): an ACT composite is as ordinary a row as an SAT total, and a 200-800
 * section score cannot be stored as a 1600-scale total. The bounds are read
 * from [PublishedScoreScale], the one home RFC 195 put beside the `colleges`
 * rows, and the SQL CHECK remains the authority -- the Kotlin values are its
 * mirror, and the two move together.
 *
 * The SAT is published in 10-point steps ([PublishedScoreScale.SAT_STEP]), so
 * `step` is part of the published scale, not a plausibility judgement: 1337 is
 * not a low SAT total, it is not an SAT total. The ACT runs whole points.
 *
 * [label] is the short name a family hears ("SAT total", "ACT composite"), on
 * [AdmissionTest.displayName]'s precedent of carrying identity and nothing
 * else. The SENTENCES this score can appear in live in `TestScoreCopy` in
 * `:service`, where a rendering test reaches them.
 */
enum class AdmissionTestScoreComponent(
  val value: String,
  val test: AdmissionTest,
  val label: String,
  val publishedScale: IntRange,
  val step: Int,
) {
  SAT_TOTAL(
    value = "sat_total",
    test = AdmissionTest.SAT,
    label = "SAT total",
    publishedScale = PublishedScoreScale.SAT_COMPOSITE,
    step = PublishedScoreScale.SAT_STEP,
  ),
  SAT_READING_WRITING(
    value = "sat_reading_writing",
    test = AdmissionTest.SAT,
    label = "SAT reading and writing",
    publishedScale = PublishedScoreScale.SAT_SECTION,
    step = PublishedScoreScale.SAT_STEP,
  ),
  SAT_MATH(
    value = "sat_math",
    test = AdmissionTest.SAT,
    label = "SAT math",
    publishedScale = PublishedScoreScale.SAT_SECTION,
    step = PublishedScoreScale.SAT_STEP,
  ),
  ACT_COMPOSITE(
    value = "act_composite",
    test = AdmissionTest.ACT,
    label = "ACT composite",
    publishedScale = PublishedScoreScale.ACT_COMPOSITE,
    step = 1,
  ),
  ACT_ENGLISH(
    value = "act_english",
    test = AdmissionTest.ACT,
    label = "ACT English",
    publishedScale = PublishedScoreScale.ACT_COMPOSITE,
    step = 1,
  ),
  ACT_MATH(
    value = "act_math",
    test = AdmissionTest.ACT,
    label = "ACT math",
    publishedScale = PublishedScoreScale.ACT_COMPOSITE,
    step = 1,
  ),
  ACT_READING(
    value = "act_reading",
    test = AdmissionTest.ACT,
    label = "ACT reading",
    publishedScale = PublishedScoreScale.ACT_COMPOSITE,
    step = 1,
  ),
  ACT_SCIENCE(
    value = "act_science",
    test = AdmissionTest.ACT,
    label = "ACT science",
    publishedScale = PublishedScoreScale.ACT_COMPOSITE,
    step = 1,
  ),
  ;

  companion object {
    /** The stored code back to the member, or null -- a miss is row corruption for the caller to locate, never a guess. */
    fun fromValue(value: String): AdmissionTestScoreComponent? = entries.find { it.value == value }
  }
}
