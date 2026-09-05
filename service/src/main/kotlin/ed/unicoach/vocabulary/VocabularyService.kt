package ed.unicoach.vocabulary

import ed.unicoach.coaching.moneyprofile.MoneyProfileService
import ed.unicoach.db.Database
import ed.unicoach.db.dao.CodebooksDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.UsState
import ed.unicoach.db.models.IncomeBand
import java.security.MessageDigest

/**
 * One entry of a published vocabulary: [value] is what the write path accepts,
 * [label] is what a person reads (RFC 142's rule, made servable).
 *
 * [extras] carries the keys a particular vocabulary adds — `jurisdictionKind`
 * for the states — and is empty for a vocabulary that adds none. Every entry
 * has the two universal keys and may have more, never fewer, so one client
 * adapter reads every present and future vocabulary.
 */
data class VocabularyEntry(
  val value: String,
  val label: String,
  val extras: Map<String, String> = emptyMap(),
) {
  init {
    // Every served key must be readable. `value` is what the write path
    // accepts and `label` is what a person reads, so neither may be blank —
    // `us_states.name` is NOT NULL but that does not exclude ''.
    require(value.isNotBlank()) { "a vocabulary entry declares a blank value" }
    require(label.isNotBlank()) { "vocabulary entry [$value] declares a blank label" }
    // The wire FLATTENS extras beside `value` and `label` (@JsonAnyGetter), so
    // an extra named either would serve the same JSON key twice, and a blank
    // key or a blank value would serve an unreadable one. Refused where the
    // entry is built.
    val blank = extras.filter { it.key.isBlank() || it.value.isBlank() }.keys
    require(blank.isEmpty()) {
      "vocabulary entry [$value] declares blank extra(s) ${blank.sorted()}; " +
        "extras are flattened beside value and label"
    }
    val reserved = extras.keys.filter { it in RESERVED_EXTRA_KEYS }
    require(reserved.isEmpty()) {
      "vocabulary entry [$value] declares reserved extra key(s) ${reserved.sorted()}; " +
        "extras are flattened beside value and label"
    }
  }

  private companion object {
    /** The two universal keys an extra may never shadow. */
    val RESERVED_EXTRA_KEYS = setOf("value", "label")
  }
}

/**
 * Thrown when an accepted residency code has no `us_states` row. Carries
 * [missingCodes] as structured data (not just the rendered message) so a
 * caller — a test, or future boot-error reporting — reads the exact codes
 * without parsing them back out of the message string.
 */
class UnlabelledResidencyCodesException(
  val missingCodes: List<String>,
) : IllegalStateException(
    "residency codes [${missingCodes.joinToString(", ")}] have no us_states row; " +
      "the served vocabulary would be smaller than the set the write path accepts",
  )

/** One published vocabulary: a stable snake_case [name], and its [entries] in DISPLAY order. */
data class Vocabulary(
  val name: String,
  val entries: List<VocabularyEntry>,
)

/**
 * The whole served document: every registered [vocabularies] entry plus the
 * content hash of exactly those bytes ([version]), so a client that has already
 * rendered a picker compares one short string instead of diffing every list.
 */
data class PublishedVocabularies(
  val version: String,
  val vocabularies: List<Vocabulary>,
)

/**
 * The vocabulary registry behind `GET /api/v1/vocabularies` (RFC 165): the one
 * place a closed, small, user-independent, display-facing list becomes something
 * the client may render instead of transcribe.
 *
 * Two are registered, because `profile/02` reads two. A third is one line in
 * [registry] — no new route, no client change — which is the property this shape
 * is bought for; a list nobody reads is payload the API then owes compatibility
 * on.
 *
 * Every vocabulary is built FROM the authority that validates it, never from a
 * parallel copy: the bands come from [IncomeBand] itself, and the state codes
 * come from [MoneyProfileService.USPS_STATE_CODES] — the set
 * [MoneyProfileService.parseResidencyState] tests membership in — enriched with
 * `us_states` labels by lookup. So a value a picker can offer cannot be a value
 * the server rejects.
 */
class VocabularyService(
  private val database: Database,
) {
  /**
   * The registry: what this server publishes, answerable by reading one
   * declaration. Each supplier takes the session so a DB-backed vocabulary and a
   * pure one register identically.
   */
  private val registry: List<(SqlSession) -> Result<Vocabulary>> =
    listOf(
      { _ -> Result.success(incomeBands()) },
      { session -> residencyStates(session) },
    )

  /**
   * Every registered vocabulary, in registration order, with the content hash of
   * the assembled document. A supplier's failure — a residency code with no
   * `us_states` row — fails the whole read: serving 58 of 59 states silently
   * drops a value the write path accepts, which is the divergence this endpoint
   * exists to remove.
   */
  suspend fun publishedVocabularies(): Result<PublishedVocabularies> =
    try {
      database.withConnection { session -> listVocabularies(session).map(Companion::documentOf) }
    } catch (e: Exception) {
      Result.failure(e)
    }

  /**
   * Every registered supplier's vocabulary, in registration order, or the first
   * supplier fault — one supplier's failure fails the whole read.
   */
  private fun listVocabularies(session: SqlSession): Result<List<Vocabulary>> {
    val assembled = mutableListOf<Vocabulary>()
    for (supplier in registry) {
      val vocabulary = supplier(session)
      vocabulary.exceptionOrNull()?.let { return Result.failure(it) }
      assembled.add(vocabulary.getOrThrow())
    }
    return Result.success(assembled)
  }

  companion object {
    const val INCOME_BANDS: String = "income_bands"
    const val RESIDENCY_STATES: String = "residency_states"

    /** The one extra key served today: `residency_states` says what kind of jurisdiction a code names. */
    const val JURISDICTION_KIND: String = "jurisdictionKind"

    /**
     * The assembled document: [vocabularies] in registration order, hashed.
     *
     * The names must be DISTINCT. The wire type is a map keyed by name, so two
     * suppliers registering one name would drop a whole vocabulary at
     * serialization while [contentVersion] still hashed both — the served
     * document would then disagree with its own version. Refused here, where
     * the document is assembled, rather than discovered on the wire.
     */
    fun documentOf(vocabularies: List<Vocabulary>): PublishedVocabularies {
      val duplicates =
        vocabularies
          .map { it.name }
          .groupingBy { it }
          .eachCount()
          .filterValues { it > 1 }
          .keys
          .sorted()
      require(duplicates.isEmpty()) {
        "vocabulary name(s) ${duplicates.joinToString(", ", "[", "]")} are registered more than once; " +
          "the served document is keyed by name, so one would be dropped"
      }
      return PublishedVocabularies(contentVersion(vocabularies), vocabularies)
    }

    /**
     * The household income bands (RFC 133/134), in the enum's declaration order
     * — lowest band first, which is also the order a picker shows them in.
     *
     * No DB read: [IncomeBand.bracket] IS the dollar range in words, and the
     * codebook loader already asserts byte-equality between it and
     * `income_bands.bracket_label`, so the enum and the table cannot drift. The
     * numeric bounds are deliberately not served — a second representation of
     * the same range is a copy the client would reformat.
     */
    fun incomeBands(): Vocabulary =
      Vocabulary(
        name = INCOME_BANDS,
        entries = IncomeBand.entries.map { VocabularyEntry(value = it.value, label = it.bracket) },
      )

    /** [residencyStates] over the rows [CodebooksDao.usStates] returns for [session]. */
    fun residencyStates(session: SqlSession): Result<Vocabulary> =
      CodebooksDao.usStates(session).mapCatching { rows ->
        residencyStates(MoneyProfileService.USPS_STATE_CODES, rows).getOrThrow()
      }

    /**
     * The residency vocabulary: the [acceptedCodes] set the money-profile writer
     * validates against, given labels and `jurisdictionKind` by lookup into
     * [states], in `us_states` NAME order.
     *
     * The SET is the validator's; the WORDS are the codebook's. A code with no
     * row is a FAULT (a failed [Result], a 500 at the route) rather than a
     * dropped entry: the condition is unreachable in a migrated database, and
     * the alternative is silently publishing a smaller vocabulary than the one
     * the write path accepts. `jurisdictionKind` is served because a UI that
     * says "state" about Palau is wrong.
     *
     * Rows in [states] that the validator does not accept are simply not served:
     * `us_states` is the wider published codebook, and this vocabulary answers
     * "what may I send as residency", not "what rows exist".
     */
    fun residencyStates(
      acceptedCodes: Set<String>,
      states: List<UsState>,
    ): Result<Vocabulary> {
      unservableCodes(acceptedCodes, states)?.let { return Result.failure(it) }
      return Result.success(Vocabulary(name = RESIDENCY_STATES, entries = residencyEntries(acceptedCodes, states)))
    }

    /** The accepted codes with no `us_states` row, as the fault they are — or null when every code has one. */
    private fun unservableCodes(
      acceptedCodes: Set<String>,
      states: List<UsState>,
    ): UnlabelledResidencyCodesException? {
      val byCode = states.associateBy { it.code }
      val missing = acceptedCodes.filter { it !in byCode }.sorted()
      if (missing.isEmpty()) return null
      return UnlabelledResidencyCodesException(missing)
    }

    /**
     * The accepted states as entries. [states] is already name-ordered by the
     * read, so filtering keeps display order without re-sorting here.
     */
    private fun residencyEntries(
      acceptedCodes: Set<String>,
      states: List<UsState>,
    ): List<VocabularyEntry> =
      states
        .filter { it.code in acceptedCodes }
        .map {
          VocabularyEntry(
            value = it.code,
            label = it.name,
            extras = mapOf(JURISDICTION_KIND to it.jurisdictionKind.value),
          )
        }

    /**
     * A short content hash over exactly what is served — every name, value,
     * label and extra, in order.
     *
     * Deliberately not an ETag: no GET in this tree sets a cache header, and
     * inventing a caching convention inside a substrate slice is how conventions
     * get invented badly. It is a field, so adding `If-None-Match` later is a
     * route change and not a contract change.
     */
    fun contentVersion(vocabularies: List<Vocabulary>): String = shortHash(canonicalForm(vocabularies))

    /**
     * Every served name, value, label and extra, in order, in a form that is
     * INJECTIVE: one document, one string, and no two documents share a string.
     *
     * Every field — and every count — is length-prefixed ([framed]), so nothing
     * a label carries can be read as structure. A delimiter-only framing would
     * escape nothing: `label` is `us_states.name`, arbitrary loaded text, and a
     * separator inside it would move a field boundary and let two different
     * documents hash the same.
     */
    private fun canonicalForm(vocabularies: List<Vocabulary>): String =
      framed(vocabularies.size.toString()) +
        vocabularies.joinToString("") { vocabulary ->
          framed(vocabulary.name) +
            framed(vocabulary.entries.size.toString()) +
            vocabulary.entries.joinToString("", transform = ::canonicalEntry)
        }

    /** One entry's fields, extras in key order so the form is deterministic. */
    private fun canonicalEntry(entry: VocabularyEntry): String =
      framed(entry.value) + framed(entry.label) + framed(entry.extras.size.toString()) +
        entry.extras.entries
          .sortedBy { it.key }
          .joinToString("") { framed(it.key) + framed(it.value) }

    /** [field] as `<UTF-8 byte length><separator><field>`, so no byte of the data can be read as a boundary. */
    private fun framed(field: String): String = "${field.toByteArray(Charsets.UTF_8).size}$FIELD_LENGTH_SEPARATOR$field"

    /** The digest's first [VERSION_BYTES] bytes, in hex. */
    private fun shortHash(canonical: String): String =
      MessageDigest
        .getInstance(VERSION_DIGEST)
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .take(VERSION_BYTES)
        .joinToString("") { "%02x".format(it) }

    /** Eight bytes -> sixteen hex characters: long enough that two documents will not collide, short enough to read. */
    private const val VERSION_BYTES = 8

    /**
     * The canonical form's one framing character, between a field's byte length
     * and the field itself. It needs no escaping — the length decides where the
     * field ends — but it must never change silently: the version every client
     * compares is a hash of these bytes.
     */
    private const val FIELD_LENGTH_SEPARATOR = ":"

    /** The digest the version is the prefix of. */
    private const val VERSION_DIGEST = "SHA-256"
  }
}
