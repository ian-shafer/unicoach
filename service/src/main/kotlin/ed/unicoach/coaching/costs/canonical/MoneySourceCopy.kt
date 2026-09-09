package ed.unicoach.coaching.costs.canonical

import ed.unicoach.common.util.phraseOf
import ed.unicoach.db.models.MoneySource

/**
 * How a family hears a money publisher named (RFC 177 D1).
 *
 * The ONE English name of a publisher on this surface. [MoneySource] is external
 * identity -- a code list whose members are the store's own slugs -- and English
 * a family reads is a rendering decision, so it lives here, beside
 * [FigureStatusCopy], and never on the enum.
 *
 * It exists because the attribution was hand-typed. A single constant said every
 * cost figure came from the College Scorecard, while `CanonicalMoneyLoader`
 * ranks IPEDS SFA and IPEDS IC_AY ABOVE the Scorecard -- so the publisher we
 * named was, for most figures, not the publisher that won. Nothing above the
 * domain layer could know who published a number, so every surface that wanted
 * to say it had to guess, and the guess drifted off the data.
 *
 * Every `when` here is exhaustive with NO `else`: a fifth [MoneySource] must
 * fail to compile at this seam rather than be served to a family under another
 * publisher's name.
 */
object MoneySourceCopy {
  /**
   * How a family hears this publisher named, mid-sentence.
   *
   * Both IPEDS surveys name the survey and not just the department: they are
   * different collections with different reporting years, and a family told
   * only "the Department of Education" cannot tell which of the two a figure
   * came from.
   */
  fun labelOf(source: MoneySource): String =
    when (source) {
      MoneySource.IPEDS_IC_AY -> "the U.S. Department of Education's IPEDS survey of college costs"

      MoneySource.IPEDS_SFA -> "the U.S. Department of Education's IPEDS survey of student financial aid"

      MoneySource.SCORECARD -> "the U.S. Department of Education College Scorecard"

      // The school is the publisher of its own Common Data Set. The school and
      // the cycle ride on `CdsCitation` where one is in hand; this is the bare
      // publisher name, for the sentences that only need that.
      MoneySource.COMMON_DATA_SET -> "the school's own Common Data Set"
    }

  /** [labelOf] at the START of a sentence -- one place, so no caller hand-capitalises a publisher's name. */
  fun openingLabelOf(source: MoneySource): String = labelOf(source).replaceFirstChar { it.uppercase() }

  /**
   * The order publishers are SPOKEN in, mirroring
   * `CanonicalMoneyLoader.ORDERED_SOURCES` -- the precedence that decides which
   * row wins, so the publisher that answers for most figures is named first.
   *
   * A `when` rather than an index into that list, because `ORDERED_SOURCES`
   * ranks only the three sources that compete for one natural key, while
   * `common_data_set` is filled elsewhere and competes with nobody. Stated
   * exhaustively here, a fifth source cannot reach a page unranked.
   *
   * The two ARE tied: `MoneySourceCopyTest` asserts this order against the
   * loader's own list rather than against a literal, so re-ranking
   * `ORDERED_SOURCES` fails here instead of silently leaving every page and
   * payload naming publishers in an order the data no longer supports.
   */
  fun rankOf(source: MoneySource): Int =
    when (source) {
      MoneySource.IPEDS_SFA -> 0
      MoneySource.IPEDS_IC_AY -> 1
      MoneySource.SCORECARD -> 2
      MoneySource.COMMON_DATA_SET -> 3
    }

  /**
   * The distinct publishers behind a set of figures, named in [rankOf] order and
   * joined into one English list, or NULL where there is no publisher to name.
   *
   * Null, never the empty string: a page or a payload with no money figure on it
   * has no publisher, and an empty string spliced into a sentence renders a hole
   * in family-facing copy. One representation of absence, so each caller cannot
   * test for it its own way.
   *
   * The GRAMMAR is [phraseOf]'s, in `:common` -- one copy per module is how two
   * surfaces come to punctuate the same list differently. This object owns only
   * the vocabulary and the order.
   */
  fun spokenListOf(sources: Collection<MoneySource>): String? =
    sources
      .distinct()
      .sortedBy { rankOf(it) }
      .map { labelOf(it) }
      .takeIf { it.isNotEmpty() }
      ?.let { phraseOf(it) }
}
