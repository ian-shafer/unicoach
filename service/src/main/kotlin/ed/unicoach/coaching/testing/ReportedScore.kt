package ed.unicoach.coaching.testing

import ed.unicoach.db.models.AdmissionTestScoreComponent

/**
 * A student's own reported score, IN ITS PUBLISHED SCALE (RFC 207, on RFC
 * 195's [ed.unicoach.coaching.admissions.AdmittedAverage] precedent).
 *
 * A TYPE rather than a check at the renderer, because the renderer is not the
 * only caller and a bare [Int] would let a 200-800 section score be spoken as a
 * 1600-scale total without failing to compile. With the constructor private,
 * a [ReportedScore] anywhere in the process has passed its component's
 * published scale AND step.
 *
 * This is the PUBLISHED SCALE, not a plausibility floor: 400 and 1600 are both
 * stored and both said as given. The SAT's 10-point step is part of that scale
 * (`PublishedScoreScale.SAT_STEP`), so 1337 is not a low SAT total, it is not
 * an SAT total.
 */
@JvmInline
value class ReportedScore private constructor(
  val score: Int,
) {
  companion object {
    /** One reported number, or null when it is not on its component's published scale and step. */
    fun of(
      component: AdmissionTestScoreComponent,
      score: Int,
    ): ReportedScore? {
      if (score !in component.publishedScale || score % component.step != 0) return null
      return ReportedScore(score)
    }
  }
}
