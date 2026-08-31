package ed.unicoach.db.models

/**
 * What a structured college search can produce (RFC 150 D54): a [Page], or a
 * program filter the loaded vocabulary cannot expand
 * ([UnresolvableProgramFilter]).
 *
 * A refused program word is a DOMAIN outcome, not a fault. "5116" is not a CIP
 * series the loaded vocabulary carries, and a subject word no taxonomy row
 * names is the same kind of thing an unknown `region` word already is: a named
 * error the model can correct by writing a different word. `Result.failure`
 * stays reserved for the database itself failing, so a caller can tell "your
 * word is wrong" from "the search broke" — and never tells a family the search
 * broke when the word simply is not in the vocabulary.
 */
sealed interface CollegeSearchOutcome {
  /** The search ran: one page of matches with its honesty figures. */
  data class Page(
    val page: CollegeSearchPage,
  ) : CollegeSearchOutcome

  /**
   * `college_search_index` has never been built (RFC 150): the migration
   * creates it EMPTY and only the ingest's `search-index` phase fills it, so
   * between a deploy and the next ingest a database full of colleges would
   * otherwise answer every search with "0 colleges match" and no warning.
   *
   * A zero is the one answer that must never be given here: it is a truthful
   * shape carrying a false fact, and no consumer can tell it from a genuinely
   * empty result. Every boundary renders this as "the search index has not been
   * built yet" instead.
   */
  data object IndexNotBuilt : CollegeSearchOutcome

  /**
   * The program filter named nothing the loaded vocabulary can expand.
   *
   * [field] is the query field the caller wrote, [value] the word it wrote, and
   * [cause] WHY it could not be expanded. All three are DATA: the sentence a
   * family or a model eventually reads is composed at the boundary that speaks
   * to them, not here.
   *
   * It carried a pre-formatted `reason: String` first, and that string was the
   * only thing either consumer read — so `field` and `value` were dead, three
   * distinct causes were flattened into one sentence, and a log line could not
   * be grouped by cause without matching prose. An empty result set would have
   * answered a narrow question with silence; this says why instead, in a shape
   * that can be branched on.
   */
  data class UnresolvableProgramFilter(
    val field: Field,
    val value: String,
    val cause: Cause,
    /**
     * The OTHER program word this one contradicts, set only by
     * [Cause.SUBJECT_AND_CIP_PREFIX_SHARE_NO_CIP_CODE] — the one refusal that is
     * about a PAIR of words rather than a single unusable one, so neither word
     * alone names the problem.
     */
    val conflictsWith: String? = null,
  ) : CollegeSearchOutcome {
    /** The query field the caller wrote, named as the vocabulary advertises it. */
    enum class Field(
      val word: String,
    ) {
      CIP_PREFIX("cipPrefix"),
      SUBJECT("subject"),
    }

    /** Why the filter could not be expanded — three genuinely different facts. */
    enum class Cause {
      /** The prefix matches no `cip_codes` row: it is not a code this vocabulary publishes. */
      NOT_A_PUBLISHED_CIP_CODE,

      /** No `subjects` row carries this slug: the word is not in the taxonomy at all. */
      SUBJECT_NOT_IN_TAXONOMY,

      /** The subject exists, but its prefixes expand to no `cip_codes` row. */
      SUBJECT_MATCHES_NO_CIP_CODE,

      /**
       * A `subject` AND a `cipPrefix` were both written, and their expansions
       * share no code — so no single program can satisfy the pair.
       *
       * Both words are readable on their own; together they are contradictory.
       * The search used to run anyway, as two independent clauses, and matched a
       * college that offers one program in each: an answer to a question nobody
       * asked, delivered with `programs: []` and no reason.
       */
      SUBJECT_AND_CIP_PREFIX_SHARE_NO_CIP_CODE,
    }
  }
}
