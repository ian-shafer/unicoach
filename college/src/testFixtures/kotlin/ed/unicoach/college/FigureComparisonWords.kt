package ed.unicoach.college

/**
 * The words that narrate a RELATION between two outcome figures -- one arm
 * against the other, or one completion window against the other (RFC 242).
 *
 * ONE list, read by the citation guard in `:college` and by the coach-prompt
 * guard in `:service`, because the `cited_as` sentences and the prompt
 * paragraph are two surfaces of ONE copy rule: no figure's sentence states how
 * it compares with another figure. The two guards used to keep two lists with
 * two vocabularies, so a word added to protect one surface left the hole open
 * on the other -- and the family-facing surface was the one missing `higher`,
 * `more often` and `gap`.
 *
 * `than` is deliberately NOT here. `\bthan\b` matches the `than` inside
 * `rather than` and `other than` -- word boundaries exclude `thankless`, not a
 * preceding word -- so the word cannot be forbidden without failing ordinary
 * non-comparative phrasing. The six below are comparatives wherever they
 * appear.
 *
 * A test fixture so both modules read the one list: `:service`'s test source
 * set already depends on `testFixtures(project(":college"))`.
 */
val FIGURE_COMPARISON_WORDS: List<String> =
  listOf("higher", "lower", "less often", "more often", "gap", "worse odds")

/**
 * The [FIGURE_COMPARISON_WORDS] [text] uses AS WORDS, in any case: `lower`
 * inside `slower` or `follower` is not a comparison, and `Higher` opening a
 * sentence is one. The one matcher every guard reads, so the word-boundary and
 * case rules are decided once, beside the list.
 */
fun figureComparisonWordsIn(text: String): List<String> =
  FIGURE_COMPARISON_WORDS.filter { word -> Regex("""\b${Regex.escape(word)}\b""", RegexOption.IGNORE_CASE).containsMatchIn(text) }
