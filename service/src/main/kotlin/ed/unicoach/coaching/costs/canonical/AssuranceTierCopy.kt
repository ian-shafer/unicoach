package ed.unicoach.coaching.costs.canonical

import ed.unicoach.db.models.AssuranceTier

/**
 * The three assurance tiers, spoken (RFC 179).
 *
 * The English half of [AssuranceTier], which carries none: the enum is a
 * derived reading of two stored codes and lives in `:db` beside [MoneySource]
 * and [FigureStatus], and how a family HEARS it is a rendering decision and
 * lives here beside [FigureStatusCopy] and [MoneySourceCopy] -- RFC 177 D1's
 * split, unchanged.
 *
 * The `when` is exhaustive with NO `else`: a fourth tier must fail to compile
 * here rather than ship wordless, which is exactly how `not_collected_by_us`
 * once reached production with no sentence at all.
 *
 * These sentences NAME NO PUBLISHER, and not merely because
 * `MoneyAttributionNamesNoPublisherTest` sweeps this source set for publisher
 * names. Composing [MoneySourceCopy.labelOf] into the mandatory-survey sentence
 * would print a FALSE claim: sixteen of the Scorecard's twenty-four strings are
 * on that tier, and the college did not report those to the Scorecard -- it
 * reported them to IPEDS and the Scorecard re-published them. The
 * mandatory-survey fact is about the FILING, which is the same act for all
 * three of its publishers, so the sentence states the act and names nobody. The
 * publisher is still named exactly once, by the seam that owns publisher names
 * ([FigureStatusCopy.statementOf]), in the sentence this one sits beside.
 *
 * A tier sentence and a status sentence are always composed as TWO sentences,
 * in that order -- status first (whose act), tier second (what kind of number)
 * -- and never concatenated into one. On the wire they are two keys and the
 * consumer composes nothing.
 *
 * No number, no score, no rating, ever (brief 0008 D7). Three words and three
 * sentences is the whole of it.
 */
object AssuranceTierCopy {
  /**
   * A status sentence and the tier sentence that follows it, as ONE piece of
   * copy -- the composition itself, stated once.
   *
   * The order is the seam's own and is not a caller's choice: whose act first,
   * what KIND of number second. [statusStatement] is nullable because a shown,
   * plainly reported figure says nothing about its status (RFC 179 D6) -- then
   * the tier sentence stands alone, and it is the FLOOR: [statementOf] is
   * exhaustive and every arm is a non-blank sentence, so the composition always
   * says something.
   *
   * Here rather than at each renderer because it was written three times -- a
   * page cell, a page paragraph and the fit-lens digest -- each with its own
   * null handling, which is three chances for one of them to print a dangling
   * space or to drop the tier entirely.
   */
  fun composedStatementOf(
    statusStatement: String?,
    tier: AssuranceTier,
  ): String = listOfNotNull(statusStatement, statementOf(tier)).joinToString(" ")

  /** How a family hears what KIND of number this is. Composed beside the status sentence, never inside it. */
  fun statementOf(tier: AssuranceTier): String =
    when (tier) {
      AssuranceTier.ADMINISTRATIVE_RECORD -> {
        "This comes from federal loan and tax records, not from the college. It covers only students " +
          "who got federal aid, and small numbers are blurred a little to protect privacy."
      }

      AssuranceTier.MANDATORY_SURVEY -> {
        "The college had to report this to the federal government, and the form checks it against last " +
          "year's answer. Nobody audits it."
      }

      AssuranceTier.VOLUNTARY_SELF_REPORT -> {
        "The college published this about itself. No one checks it. We link to the college's own file " +
          "so you can see it."
      }
    }
}
