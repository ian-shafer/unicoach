package ed.unicoach.college

import ed.unicoach.common.util.Share
import ed.unicoach.db.models.CollegeMatch
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.PriceRuler
import ed.unicoach.db.models.ResidencyBasis
import ed.unicoach.db.models.ResidencyTierBasis
import ed.unicoach.db.models.putIncomeBand
import ed.unicoach.db.models.putResidencyTiers
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Locale

/**
 * WHICH PRICE a search result is on, and what is in it, said in the tool
 * DESCRIPTION that carries the row (RFC 157, rewritten by RFC 169).
 *
 * There are two rulers and they are different numbers, so the note names both.
 *
 * `in_state_net_price_per_year_usd` is the Scorecard blend: what students
 * actually paid AFTER federal aid, and at a public school it is the figure for
 * students paying in-state tuition. `published_price_*_on_campus_per_year_usd`
 * is the school's own published total for a year of living on campus at THIS
 * family's tuition tier, with NO financial aid of any kind subtracted from it.
 *
 * The note also says the thing a reader would otherwise assume: there is no
 * out-of-state AFTER-AID price, and there never can be. Subtracting an in-state
 * average grant from an out-of-state total is arithmetic the cost surfaces
 * forbid outright (RFC 149), so the honest out-of-state answer is a published
 * price that says it is one — never a subtraction dressed up as a net price.
 *
 * Said ONCE here, because two tools that return "a college" must describe it
 * the same way (RFC 153 D70).
 *
 * One SENTENCE group, with no glue on either end: a call site joins it to the
 * copy above it with a visible separator, rather than every call site having to
 * remember that the value already begins with a space.
 */
internal val NET_PRICE_BASIS_NOTE =
  "This tool ranks on ONE of two prices. ${PriceRuler.NET_PRICE_RESULT_KEY} is what students actually paid " +
    "after federal aid, and at a public school it is the figure for students paying in-state tuition: " +
    "never offer one to a family from another state as their price. " +
    "${PriceRuler.resultKey(PriceRuler.PublishedTier.IN_STATE)} and " +
    "${PriceRuler.resultKey(PriceRuler.PublishedTier.OUT_OF_STATE)} " +
    "are the school's own published total for a year of living on campus at that tuition tier, and no " +
    "financial aid of any kind is in them. There is no out-of-state after-aid price and there never can " +
    "be -- nobody may ever subtract an in-state aid average from an out-of-state total -- so an " +
    "out-of-state family is ranked on a published price, and the result says so."

/**
 * The one sentence naming the ruler a result was produced on — the thing D14(a)
 * requires a published ranking to SAY rather than leave a reader to infer.
 *
 * It is one function, used by the `price_ruler` object on a result and by the
 * refusal a caller gets for naming the inactive ruler's field or sort word, so
 * a family can never be told two different things about which price they are
 * looking at.
 */
internal fun PriceRuler.describe(): String =
  when (this) {
    is PriceRuler.NetPrice -> {
      // It says WHAT the ranking is, and what it would take to change it. It
      // does NOT assert why this family is on it: `:college` is handed a state
      // or a null and cannot tell "nobody has asked" from "the profile read
      // faulted", and the page used to tell every one of them that the family's
      // state was "not on file" -- untrue for a fault, where we simply do not
      // know. The layer that KNOWS the cause says so (`ResidencyRead.Cause`).
      "This search is ranked on the average annual NET price -- what students actually paid after " +
        "federal aid -- which at a public school is the figure for students paying in-state tuition. " +
        "A residency-correct ranking needs this family's own state of residency on file."
    }

    is PriceRuler.Published -> {
      "This search is ranked on each school's PUBLISHED total price of living on campus for a year, at " +
        "the tuition tier this family would actually pay (out-of-state at a public school outside " +
        "$familyResidencyState, in-state everywhere else). NO financial aid is in that number. " +
        "There is no out-of-state after-aid price and there never can be, so this is the honest ranking " +
        "for a family from another state -- quote it as a published price, never as what they would pay " +
        "after aid."
    }
  }

/**
 * The active ruler's figure as a coach says it aloud — the words a refusal, a
 * constraint sentence and a description all use for the same number.
 *
 * One home, because the sentence "cheaper than Bowdoin" and the sentence
 * refusing it for want of a figure must be about the same price or a reader
 * cannot tell which one was applied.
 */
internal fun PriceRuler.spokenFigure(): String =
  when (this) {
    is PriceRuler.NetPrice -> "average annual in-state net price"
    is PriceRuler.Published -> "published on-campus price for this family, before any aid"
  }

/**
 * The `price_ruler` object a result carries: the metric it ranked on, and the
 * sentence above.
 *
 * It is present on EVERY page, not only under the published ruler: a key that
 * appears exactly when the answer is unusual is a key a reader learns to ignore,
 * and the net ruler has a basis worth naming too.
 */
internal fun JsonObjectBuilder.putPriceRuler(ruler: PriceRuler) {
  putJsonObject("price_ruler") {
    put("metric", ruler.sortWord)
    put("max_filter_field", ruler.filterField)
    put("note", ruler.describe())
  }
}

/**
 * The ONE rendering of a [CollegeMatch] as a tool result row (RFC 153 D70).
 *
 * It was `CollegeSearchTool`'s private method until `similar_colleges` needed
 * the same row: two tools returning "a college" must return the SAME college,
 * key for key, or the model learns that a result row means different things
 * depending on which tool it asked. The similar-colleges tool appends its own
 * `distance` and `axes_scored` to this object rather than rebuilding it.
 *
 * Every coded axis is WORDS (RFC 147 D45 / RFC 150 D61) and `college_id` is
 * FIRST by design: it is the handle `update_college_list` takes, and putting it
 * ahead of the name leaves nothing else a model could mistake for one.
 */
internal fun matchObject(
  match: CollegeMatch,
  vocabulary: CollegeQueryVocabulary,
  ruler: PriceRuler,
): JsonObject =
  buildJsonObject {
    // First key by design: the id the model must copy into
    // `update_college_list`'s `college_id`, ahead of the name — the only
    // other field it could mistake for a handle on the school.
    put("college_id", match.id.value.toString())
    put("name", match.name)
    put("city", match.city)
    put("state", match.state)
    // The word, never a code -- and now STRUCTURALLY so (RFC 150 D61): the
    // search index stores our vocabulary, so there is no code on this path to
    // leak and no code-to-word step left to forget.
    put("control", match.control)
    // The same words the `region`/`locale_type`/`locale_detail` filters take
    // (RFC 147 D45), so what the model reads back is what it can ask for.
    putOrNull("region", match.region)
    val locale = vocabulary.localeOf(match.locale)
    putOrNull("locale_type", locale?.type?.word)
    putOrNull("locale_detail", locale?.detail?.word)
    putOrNull("undergrad_enrollment_headcount", match.undergradEnrollmentHeadcount)
    putOrNull("admission_rate_share", match.admissionRateShare)
    putRulerPrice(match, ruler)
    // One self-describing array rather than five opaque per-quintile keys
    // (RFC 142): every amount arrives beside the band code AND the dollar
    // range a coach says aloud, so the model never has to translate a source
    // bucket name into English -- and cannot say "Q5" because it never saw it.
    putJsonArray("net_price_by_income_band") {
      IncomeBand.entries.forEach { band ->
        // An unreported bracket is omitted entirely rather than carried as a
        // labelled null: the array names the bands this college actually
        // reports, so there is nothing to mistake for a price of zero.
        band.getNetPrice(match)?.let { amount ->
          add(
            buildJsonObject {
              putIncomeBand(band)
              put(PriceRuler.NET_PRICE_RESULT_KEY, amount)
            },
          )
        }
      }
    }
    putOrNull("completion_rate_150pct_4yr_share", match.completionRate150pct4yrShare)
    putOrNull("median_earnings_10y_after_entry_usd", match.medianEarnings10yAfterEntryUsd)
    putOrNull("median_debt_at_completion_usd", match.medianDebtAtCompletionUsd)
    putOrNull("pell_share", match.pellShare)
    // Present only when a program filter was written: the key MEANS "what your
    // program filter matched", so on a search that asked nothing about
    // programs there is no answer to give. It used to print `programs: []` on
    // every non-program search — an empty array that reads as "this college
    // offers nothing".
    match.programTitles?.let { titles ->
      putJsonArray("programs") {
        titles.forEach { add(it) }
      }
    }
  }

/**
 * The price keys of ONE row: the net-price blend every row carries, the number
 * the ACTIVE ruler actually ranked this row on, and D19's sentence when that
 * number is an in-state figure whose district tier nobody separated.
 *
 * Extracted so [matchObject] stays a flat list of keys. The tier choice, the
 * result key and the D19 silence rule were three decisions nested inside one
 * serializer, which is three reasons for one function to change.
 */
private fun JsonObjectBuilder.putRulerPrice(
  match: CollegeMatch,
  ruler: PriceRuler,
) {
  // The BASIS is in the key (RFC 169 D7). This number is the after-federal-aid
  // blend, and at a public school it is the in-state figure -- a key that did
  // not say so is what let it be read out to a family from another state.
  putOrNull(PriceRuler.NET_PRICE_RESULT_KEY, match.netPricePerYearUsd)
  if (ruler !is PriceRuler.Published) return

  // Under the PUBLISHED ruler the row also carries the number it was actually
  // ranked on, under a key naming the tuition tier applied to THIS row. The
  // page-level `price_ruler` says what is in it, and what is not.
  val tier = ruler.tierFor(match.control, match.state)
  putOrNull(PriceRuler.resultKey(tier), match.rulerPriceUsd)

  // Brief 0006 D19. A published price reported on the IN-STATE tier, at a school
  // whose district status is a SILENCE rather than an answer, may already BE the
  // district price wearing a state label -- and saying which it is would be
  // inventing a fact. So the row is ranked exactly as every other, the figure is
  // shown as it was published, and the gap is stated in the vocabulary's own
  // sentence.
  //
  // The basis is DECIDED in `:db` by the one derivation the cost surfaces use,
  // so this site chooses nothing: it asks whether the answer is the one member
  // D19 is about. A school whose publisher ANSWERED "no district tier here"
  // carries nothing, which is the 354-school difference between a truthful label
  // and a wrong one.
  if (tier == PriceRuler.PublishedTier.IN_STATE &&
    match.residencyTierBasis == ResidencyTierBasis.PUBLISHER_DOES_NOT_SEPARATE_IN_DISTRICT
  ) {
    putResidencyTiers(match.residencyTierBasis)
  }
}

/**
 * The ONE rendering of `excluded_unknown` (RFC 150 D55): every supplied filter
 * and every ranked axis a college could not be judged on, with its count, in
 * sorted key order so a reader sees the same order from every tool.
 *
 * `{}` when nothing the call asked for can exclude an unknown, which is the
 * truthful answer rather than an absent key.
 */
internal fun JsonObjectBuilder.putExcludedUnknown(excludedUnknown: Map<String, Int>) {
  putJsonObject("excluded_unknown") {
    excludedUnknown.toSortedMap().forEach { (axis, count) -> put(axis, count) }
  }
}

/**
 * The ONE rendering of `source_years`: the vintages of the rows actually
 * returned, read at result time.
 *
 * A key is ABSENT when no returned row carries that vintage — an empty page
 * reports no years, which is the truthful answer. When the returned rows MIX
 * vintages the key carries the SPAN instead of one year: the mixture is a fact
 * about the answer, and reporting nothing (what a single-year reading had to
 * do) hid it behind the same silence as "unknown".
 */
internal fun JsonObjectBuilder.putSourceYears(sourceYears: Map<String, IntRange>) {
  putJsonObject("source_years") {
    sourceYears.toSortedMap().forEach { (source, years) ->
      if (years.first == years.last) {
        put(source, years.first)
      } else {
        putJsonObject(source) {
          put("earliest", years.first)
          put("latest", years.last)
        }
      }
    }
  }
}

/**
 * A `_share` ratio as the percentage a coach says aloud, through the ONE type
 * that owns ratio->percent (`common/util/Share.kt`), whose own doc forbids
 * `* 100` at a call site.
 *
 * Two private one-liners used to do it, in two files, rounding to a whole
 * percent — so a 12.5% admission rate was spoken as "13%" here and as "12.5"
 * everywhere else in the repo.
 */
internal fun mapShareToSpokenPercent(share: Double): String = Share.ofRatio(share).spokenPercent()

/**
 * A whole-dollar figure as a coach says it aloud, grouped: `$54,321`, never the
 * bare `$54321` a `$$it` interpolation produced. One home, so the two sentences
 * that describe the same net-price column cannot drift.
 */
internal fun mapUsdToSpoken(usd: Int): String = "$" + "%,d".format(Locale.US, usd)
