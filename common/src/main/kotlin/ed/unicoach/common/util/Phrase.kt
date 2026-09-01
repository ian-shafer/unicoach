package ed.unicoach.common.util

/**
 * A list of things said as English rather than as a comma-separated dump: "a",
 * "a and b", "a, b and c".
 *
 * GRAMMAR, not vocabulary — the words come from wherever they are declared (a
 * living arrangement's label, a school's own name), and this joiner never
 * invents or renames one. It lives in `:common` because two modules now say
 * lists of domain words aloud: the coach's comparison basis in `:service`, and
 * the parent-facing report page in `:public-web`. One copy per module is how the
 * two would come to punctuate the same sentence differently.
 */
fun phraseOf(words: List<String>): String {
  // No words is not a shorter phrase, it is nothing to say -- and an empty
  // string spliced into a sentence renders a hole in family-facing copy
  // ("The public schools here -  - are shown at..."). Every caller holds a
  // non-empty list by construction; this refuses the one that stops doing so.
  require(words.isNotEmpty()) { "a phrase needs words: an absent phrase is never an empty one" }
  if (words.size == 1) return words.single()
  return words.dropLast(1).joinToString(", ") + " and " + words.last()
}
