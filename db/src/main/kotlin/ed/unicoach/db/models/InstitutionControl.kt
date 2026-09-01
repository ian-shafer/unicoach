package ed.unicoach.db.models

/**
 * Institutional control (`colleges.control`, the IPEDS/Scorecard code) as a
 * vocabulary: the code -> label mapping with exactly ONE home, beside
 * [IncomeBand] (RFC 143). Both model-facing surfaces that name a control read
 * it from here — `college_search` and the cost tool's `CollegeControl` — so
 * neither hand-writes the phrases and neither can ship a bare source code.
 *
 * The labels are the vocabulary only. Cost semantics (which published tuition
 * applies at a public college) stay in the service layer's `CollegeControl`,
 * which this enum knows nothing about.
 */
enum class InstitutionControl(
  /** The Scorecard code as ingested into `colleges.control`. */
  val code: Int,
  /** The phrase a coach reads aloud, and the value that goes on the wire. */
  val label: String,
) {
  PUBLIC(1, "public"),
  PRIVATE_NONPROFIT(2, "private_nonprofit"),
  PRIVATE_FOR_PROFIT(3, "private_for_profit"),
  ;

  companion object {
    /** The control this [code] names, or null when the source vocabulary does not define it. */
    fun fromCode(code: Int): InstitutionControl? = entries.find { it.code == code }

    /**
     * The control this [label] names, or null for the [unknownLabel] phrase an
     * extended source code produces.
     *
     * The label -> control half of the vocabulary, beside the code -> label
     * half, so no caller hand-scans [entries] and no caller has to rediscover
     * that [labelFor] is TOTAL: a stored label that matches no entry is a real
     * state, and a lookup that says so is what lets the caller report it
     * instead of silently losing the constraint it was building.
     */
    fun fromLabel(label: String): InstitutionControl? = entries.find { it.label == label }

    /**
     * The label for a [code] the vocabulary does NOT define: named as unknown,
     * but carrying the raw code inside the phrase, so a vocabulary the source
     * has extended stays observable at the wire instead of being silently
     * swallowed into "public" or dropped.
     *
     * Split out of [labelFor] on purpose (RFC 143): a caller that already KNOWS
     * the code is unrecognised — the cost domain's `CollegeControl.Unrecognized`
     * — must render the unknown phrase for whatever code it holds, not
     * whichever label a total lookup happens to find. [labelFor] is the total
     * function for a code of unknown provenance; this is the fallback half.
     */
    fun unknownLabel(code: Int): String = "unknown (control [$code])"

    /**
     * The wire label for any [code], defined or not — the search path, which
     * reads a raw `colleges.control` straight out of the row and has done no
     * classification of its own.
     */
    fun labelFor(code: Int): String = fromCode(code)?.label ?: unknownLabel(code)
  }
}
