package ed.unicoach.college

import ed.unicoach.common.util.Share
import ed.unicoach.db.models.CollegeMatch
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.putIncomeBand
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Locale

/**
 * The residency basis of [matchObject]'s `net_price_per_year_usd` and of every
 * amount in `net_price_by_income_band`, said in the tool DESCRIPTION that
 * carries the row (RFC 157).
 *
 * The College Scorecard builds both for students paying the in-state rate at a
 * public school and publishes no out-of-state counterpart, and this read goes
 * round `CollegeCostService` -- and so round its withholding. Said ONCE here,
 * because two tools that return "a college" must describe it the same way (RFC
 * 153 D70). Labelling only: the search index does not carry the family's
 * residency, so withholding belongs with the index (RFC 157 D-G).
 *
 * One SENTENCE, with no glue on either end: a call site joins it to the copy
 * above it with a visible separator, rather than every call site having to
 * remember that the value already begins with a space.
 */
internal const val NET_PRICE_BASIS_NOTE =
  "At a public school net_price_per_year_usd and every net_price_by_income_band amount are figures for " +
    "students paying in-state tuition: never offer one to a family from another state as their price."

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
    putOrNull("net_price_per_year_usd", match.netPricePerYearUsd)
    // One self-describing array rather than five opaque `net_price_per_year_income_qN_usd` keys
    // (RFC 142): every amount arrives beside the band code AND the dollar
    // range a coach says aloud, so the model never has to translate a source
    // bucket name into English -- and cannot say "Q5" because it never saw it.
    putJsonArray("net_price_by_income_band") {
      IncomeBand.entries.forEach { band ->
        // An unreported bracket is omitted entirely rather than carried as a
        // labelled null: the array names the bands this college actually
        // reports, so there is nothing to mistake for a price of zero.
        band.netPriceFor(match)?.let { amount ->
          add(
            buildJsonObject {
              putIncomeBand(band)
              put("net_price_per_year_usd", amount)
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
