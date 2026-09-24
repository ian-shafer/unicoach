package ed.unicoach.coaching.costs.canonical

import ed.unicoach.db.models.AssuranceTier

/**
 * Whether the surface saying a tier sentence is ALSO rendering the college's
 * own file (RFC 200).
 *
 * A parameter and not a default, because exactly one tier sentence --
 * `voluntary_self_report` -- ends by PROMISING that link, and the promise must
 * not be made by a caller that is not keeping it. Declared as an enum rather
 * than a `Boolean` so the two arms are named where they are read: a bare
 * `false` at a call site says nothing about which half of the sentence it
 * decides.
 *
 * For a CDS filing the answer is `CdsCitation.linksToFile` and nothing else --
 * the ONE predicate that decides whether we point a reader at a filing. A
 * caller holding NO filing at all passes [LINKED], because its tier's sentence
 * promises no link in the first place; each such site says so in a comment.
 */
enum class FilingLink {
  /** We are rendering the college's own file beside these words. */
  LINKED,

  /** We are not, so the sentence that promises one is not said. */
  NOT_LINKED,
}

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
 * These sentences NAME NO PUBLISHER, and the reason is not a sweep -- RFC 231
 * deleted that test, and RFC 177 D7 now holds through [MoneySourceCopy]'s
 * `when`s with no `else`, which force a publisher to be spoken through the
 * registry before this file compiles.
 * Composing [MoneySourceCopy.labelOf] into the mandatory-survey sentence
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
    filing: FilingLink,
  ): String = listOfNotNull(statusStatement, statementOf(tier, filing)).joinToString(" ")

  /**
   * How a family hears what KIND of number this is. Composed beside the status
   * sentence, never inside it.
   *
   * [filing] is REQUIRED and has no default, which is the whole of RFC 200's
   * safety argument: the self-report sentence ENDS with a promise to link the
   * college's own file, so no caller may state the tier without also stating
   * whether that link is being rendered. A default would let a surface holding
   * no filing -- or holding a dead one -- make the promise by omission, which
   * is exactly the defect this parameter exists to make uncompilable. It is the
   * ONE place the question "are we pointing at this file?" is answered for
   * copy, and for a CDS filing the answer comes from `CdsCitation.linksToFile`
   * and nowhere else.
   */
  fun statementOf(
    tier: AssuranceTier,
    filing: FilingLink,
  ): String =
    when (tier) {
      AssuranceTier.ADMINISTRATIVE_RECORD -> {
        "This comes from federal loan and tax records, not from the college. It covers only students " +
          "who got federal aid, and small numbers are blurred a little to protect privacy."
      }

      AssuranceTier.MANDATORY_SURVEY -> {
        "The college had to report this to the federal government, and the form checks it against last " +
          "year's answer. Nobody audits it."
      }

      // The ONE tier whose sentence promises a link, so the ONE tier [filing]
      // can change. The `when` is exhaustive with no `else` here too: a fourth
      // answer to "are we linking?" must fail to compile rather than quietly
      // pick the promise.
      AssuranceTier.VOLUNTARY_SELF_REPORT -> {
        when (filing) {
          FilingLink.LINKED -> SELF_REPORT_WITHOUT_LINK + SELF_REPORT_LINK_PROMISE

          // The third sentence is DELETED, whole, and the first two are
          // returned byte for byte (RFC 200). It is never reworded, and the
          // distinction is the whole point: these sentences are byte-frozen
          // against the immutable v19 prompt row (`SystemPromptCatalogTest`),
          // so a reword would move a string no new seed can fix and would mint
          // a prompt version for a copy change. Brief 0009 D1 draws the line we
          // follow -- a RESTATEMENT may be deleted, a frozen sentence may never
          // be reworded -- so a filing whose link we dropped simply does not
          // have the promise SAID about it.
          FilingLink.NOT_LINKED -> SELF_REPORT_WITHOUT_LINK
        }
      }
    }

  /**
   * The frozen self-report copy MINUS the promise -- the first two sentences,
   * byte for byte as v19 froze them.
   *
   * Split out rather than edited: `SELF_REPORT_WITHOUT_LINK +
   * SELF_REPORT_LINK_PROMISE` is character-identical to the single literal it
   * replaces, so the v19 pin does not move and no prompt version is minted.
   */
  const val SELF_REPORT_WITHOUT_LINK = "The college published this about itself. No one checks it."

  /**
   * The promise RFC 196 finally kept, as its own whole sentence -- leading
   * space included, because it is appended to the sentence before it.
   *
   * Written in exactly ONE place in this source set, and asserted to be, so
   * that dropping it for an unlinkable filing is a deletion at one seam rather
   * than a second copy someone forgot.
   */
  const val SELF_REPORT_LINK_PROMISE = " We link to the college's own file so you can see it."
}
