package ed.unicoach.college

import ed.unicoach.db.Database
import ed.unicoach.db.dao.CodebookTable
import ed.unicoach.db.dao.CodebooksDao
import ed.unicoach.db.models.NewIpedsRegion
import ed.unicoach.db.models.NewNcesLocale
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory

/**
 * The `nces_locale` TYPE half — the four words the published locale label opens
 * with ("City: Large" -> `city`). A closed set of four, so it is a Kotlin enum
 * rather than a codebook lookup (RFC 147's boundary rule); `CodebookVocabularyTest`
 * asserts it is exactly the set `db/data/codebooks.json` publishes, so the
 * closed-ness is checked against the file rather than asserted in a comment.
 */
enum class NcesLocaleType(
  val word: String,
) {
  CITY("city"),
  SUBURB("suburb"),
  TOWN("town"),
  RURAL("rural"),
  ;

  companion object {
    val WORDS: List<String> = entries.map { it.word }

    fun fromWord(word: String): NcesLocaleType? = entries.firstOrNull { it.word == word }
  }
}

/** The `nces_locale` DETAIL half ("City: Large" -> `large`). See [NcesLocaleType]. */
enum class NcesLocaleDetail(
  val word: String,
) {
  LARGE("large"),
  MIDSIZE("midsize"),
  SMALL("small"),
  FRINGE("fringe"),
  DISTANT("distant"),
  REMOTE("remote"),
  ;

  companion object {
    val WORDS: List<String> = entries.map { it.word }

    fun fromWord(word: String): NcesLocaleDetail? = entries.firstOrNull { it.word == word }
  }
}

/**
 * IPEDS `ADMCON7`, the admission-test policy: three published codes, and the
 * only other closed set small enough to be an enum rather than a lookup. The
 * codes are NOT 1/2/3 — they are 1, 3 and 5 — which is precisely why they are
 * written down here from the published file instead of inferred.
 *
 * Nothing in `search_colleges` filters on it yet (`college_ipeds.test_policy`
 * arrives with RFC 148); the enum exists so the word/code pairing has one home
 * the moment it does, and `CodebookVocabularyTest` holds it to the file.
 */
enum class AdmissionTestPolicy(
  val slug: String,
  val code: Int,
) {
  REQUIRED("required", 1),
  TEST_BLIND("test-blind", 3),
  TEST_OPTIONAL("test-optional", 5),
  ;

  companion object {
    fun fromSlug(slug: String): AdmissionTestPolicy? = entries.firstOrNull { it.slug == slug }

    fun fromCode(code: Int): AdmissionTestPolicy? = entries.firstOrNull { it.code == code }
  }
}

/**
 * The word <-> code lookup for the two codebooks the college-search boundary
 * speaks (RFC 147 D45): IPEDS `OBEREG` regions and the NCES urbanization
 * locale. Built from the LOADED reference tables — `ipeds_regions` and
 * `nces_locales`, filled by the `codebooks` ingest phase — never from a map
 * written out here, so a relabelled or added code reaches the tool by being
 * loaded rather than by being re-typed.
 *
 * It is a snapshot: [load] reads the tables once and the result is immutable.
 * The vocabulary changes when a publisher issues a new codebook (every few
 * years), so a per-request read would buy nothing and cost a query on every
 * tool call.
 *
 * An EMPTY codebook is representable on purpose. A database that has never run
 * the `codebooks` phase has no vocabulary, and the honest boundary behaviour is
 * to reject every region/locale word with a message that says so ([UNAVAILABLE]
 * is that message's subject) — never to silently drop the filter, which would
 * answer a narrowed question with an unnarrowed result set.
 */
class Codebook(
  regionRows: List<NewIpedsRegion>,
  localeRows: List<NewNcesLocale>,
  /**
   * The closed SLUG vocabularies the RFC 150 filters speak. After D61 the
   * derived search index stores the slug, so a filter binds the word itself and
   * these lists are the whole job: what the schema advertises, and what an
   * unknown word is refused against. They default to empty so a caller that
   * only needs the two typed codebooks — a test, a fixture — is unchanged.
   */
  val subjectSlugs: List<String> = emptyList(),
  /**
   * The published `us_states.usps_code` values — the closed vocabulary the
   * `states` filter is resolved against. Not a slug list: the postal code IS
   * the key, and it is the value `colleges.state` stores.
   */
  val stateCodes: List<String> = emptyList(),
  val testPolicySlugs: List<String> = emptyList(),
  val religiousAffiliationSlugs: List<String> = emptyList(),
  val carnegieClassSlugs: List<String> = emptyList(),
  val carnegieSizeSlugs: List<String> = emptyList(),
  val athleticAssociationSlugs: List<String> = emptyList(),
) {
  /** One `ipeds_regions` row, reduced to what the boundary says and reads. */
  data class Region(
    val slug: String,
    val code: Int,
    val name: String,
  )

  /** One `nces_locales` row, with the two published halves parsed into enums. */
  data class Locale(
    val slug: String,
    val code: Int,
    val type: NcesLocaleType,
    val detail: NcesLocaleDetail,
    val name: String,
  )

  val regions: List<Region> = regionRows.sortedBy { it.code }.map { Region(it.slug, it.code, it.name) }

  val locales: List<Locale> =
    localeRows.sortedBy { it.code }.map { row ->
      // The DB CHECKs already restrict both columns to these words, so a miss
      // here means the Kotlin enum and the loaded codebook have diverged — a
      // defect to fail loudly on at construction, not to paper over per call.
      val type =
        NcesLocaleType.fromWord(row.type)
          ?: error("nces_locales row [${row.slug}] has type [${row.type}], which NcesLocaleType does not name")
      val detail =
        NcesLocaleDetail.fromWord(row.detail)
          ?: error("nces_locales row [${row.slug}] has detail [${row.detail}], which NcesLocaleDetail does not name")
      Locale(row.slug, row.code, type, detail, row.name)
    }

  private val localeBySlug: Map<String, Locale> = locales.associateBy { it.slug }

  /**
   * The filter FIELDS this snapshot can offer no word for, named exactly as the
   * model-facing schema names them.
   *
   * The health check has to cover what the boundary advertises, not just the
   * two typed codebooks: a database whose `subjects` phase never ran carries
   * regions and locales, so the old check called it healthy while the `subject`
   * filter had no values at all. A field listed here is NOT advertised
   * ([CollegeQueryVocabulary.schemaProperties] drops it) and every word sent for
   * it is refused with [UNAVAILABLE] — one fact, one home, both readers.
   */
  val emptyVocabularies: List<String> =
    buildList {
      if (regions.isEmpty()) add("region")
      if (locales.isEmpty()) {
        add("locale_type")
        add("locale_detail")
      }
      if (subjectSlugs.isEmpty()) add("subject")
      if (testPolicySlugs.isEmpty()) add("test_policy")
      if (religiousAffiliationSlugs.isEmpty()) add("religious_affiliation")
      if (carnegieClassSlugs.isEmpty()) add("carnegie_class")
      if (carnegieSizeSlugs.isEmpty()) add("carnegie_size")
      if (athleticAssociationSlugs.isEmpty()) add("athletic_association")
    }

  /**
   * True when a vocabulary the boundary advertises is missing — including the
   * partially-loaded snapshot an ingest run without `--subjects` leaves, which
   * must never read as healthy.
   */
  val isDegraded: Boolean = emptyVocabularies.isNotEmpty()

  /** True when neither codebook has been loaded — see the class note. */
  val isEmpty: Boolean = regions.isEmpty() && locales.isEmpty()

  /** Every region word, in published-code order: the tool's `region` enum. */
  val regionSlugs: List<String> = regions.map { it.slug }

  /** True when [slug] is a region this codebook carries. */
  fun hasRegion(slug: String): Boolean = regions.any { it.slug == slug }

  /**
   * The locale row named by a `college_search_index.locale` SLUG, or null.
   *
   * There is no code-keyed counterpart any more (RFC 150 D61): the index holds
   * a slug with a real foreign key onto `nces_locales`, so a "stored code the
   * codebook does not name" is not a state this path can reach, and the
   * `unknown (locale [N])` rendering that described it is gone with it.
   */
  fun locale(slug: String): Locale? = localeBySlug[slug]

  /**
   * The published locale SLUGS matching [type], narrowed to [detail] when one is
   * given. An empty list means the pairing is not published (there is no
   * "city: fringe"), which the caller reports as a listed error — the set never
   * leaves this class as a filter that quietly matches nothing.
   */
  fun localeSlugs(
    type: NcesLocaleType,
    detail: NcesLocaleDetail? = null,
  ): List<String> = locales.filter { it.type == type && (detail == null || it.detail == detail) }.map { it.slug }

  /** The details published for [type], for the listed error a bad pairing gets. */
  fun localeDetails(type: NcesLocaleType): List<String> = locales.filter { it.type == type }.map { it.detail.word }

  /** The locale type words the LOADED codebook actually carries. */
  val localeTypeWords: List<String> = locales.map { it.type.word }.distinct()

  /** The locale detail words the LOADED codebook actually carries. */
  val localeDetailWords: List<String> = locales.map { it.detail.word }.distinct()

  companion object {
    /** What a boundary says when it is asked for a word and has no codebook. */
    const val UNAVAILABLE =
      "the published codebooks are not loaded in this database (run the ingest's `codebooks` phase)"

    /** A codebook with no vocabulary at all — the empty snapshot, named. */
    val EMPTY = Codebook(emptyList(), emptyList())

    /**
     * The wiring-time read: [load], with an ABSENT or UNREADABLE codebook
     * degraded to [EMPTY] and logged rather than thrown.
     *
     * Deliberate policy, not laziness. The codebook is a VOCABULARY, not a
     * dependency the product cannot answer without: with none loaded the tool
     * refuses a region word with a message that says so, and every other filter
     * — states, price, size, program — keeps working. Refusing to boot would
     * turn "the ingest phase has not run" into a total outage, and would make
     * server assembly depend on a database read succeeding at that instant.
     *
     * It does NOT degrade a SHAPE fault. The constructor fails loud on a loaded
     * row whose `type`/`detail` no Kotlin enum names — a real divergence between
     * the code and the reference table — and swallowing that would silently
     * disable the whole vocabulary at boot for exactly the defect the `error()`
     * was written to surface. So an [IllegalStateException] is rethrown and only
     * a read fault (no connection, a DAO failure) becomes [EMPTY].
     */
    suspend fun loadOrEmpty(database: Database): Codebook =
      load(database).getOrElse { error ->
        if (error is IllegalStateException) throw error
        LoggerFactory
          .getLogger(Codebook::class.java)
          .warn("codebook: could not be read ({}); continuing with no vocabulary -- {}", error.toString(), UNAVAILABLE)
        EMPTY
      }

    /**
     * Reads both reference tables in one connection.
     *
     * The result is a SNAPSHOT taken once, by whoever wires the boundary. That
     * is right for a vocabulary a publisher revises every few years, and it has
     * one operational consequence worth stating plainly: **the first codebook
     * load on a live deployment takes effect at the next restart.** A process
     * that booted before the `codebooks` ingest phase ran serves [EMPTY] — no
     * region filter, and every result rendered `unknown (region [N])` — until it
     * is restarted. `bin/ingest-colleges` says so at the end of the phase.
     */
    suspend fun load(database: Database): Result<Codebook> =
      // runCatching over the whole read, not just the DAO results: `withConnection`
      // THROWS on connection acquisition (a closed or unreachable pool), and a
      // Result that only covered the two queries would let that escape as an
      // exception from a function whose signature promises otherwise.
      runCatching {
        database
          .withConnection { session ->
            CodebooksDao.ipedsRegions(session).mapCatching { regions ->
              Codebook(
                regionRows = regions,
                localeRows = CodebooksDao.ncesLocales(session).getOrThrow(),
                // The slug vocabularies (RFC 150 D54): one generic read per
                // domain over the CodebookTable allowlist, not a typed row
                // reader each. `subjects` is not a codebook — it is the
                // authored taxonomy — so it comes from its own read.
                subjectSlugs = CodebooksDao.subjects(session).getOrThrow().map { it.slug },
                // Not a `slugs` read: `us_states` is keyed by the postal code,
                // which that function refuses to dress up as a slug.
                stateCodes = CodebooksDao.usStateCodes(session).getOrThrow(),
                testPolicySlugs = CodebooksDao.slugs(session, CodebookTable.ADMISSION_TEST_POLICIES).getOrThrow(),
                religiousAffiliationSlugs =
                  CodebooksDao.slugs(session, CodebookTable.RELIGIOUS_AFFILIATIONS).getOrThrow(),
                carnegieClassSlugs =
                  CodebooksDao.slugs(session, CodebookTable.CARNEGIE_2021_BASIC_CLASSES).getOrThrow(),
                carnegieSizeSlugs =
                  CodebooksDao.slugs(session, CodebookTable.CARNEGIE_2021_SIZE_SETTINGS).getOrThrow(),
                athleticAssociationSlugs =
                  CodebooksDao.slugs(session, CodebookTable.ATHLETIC_ASSOCIATIONS).getOrThrow(),
              )
            }
          }.getOrThrow()
      }.onFailure { error ->
        // `runCatching` catches Throwable, so a cancelled caller would come back
        // as an ordinary failure and structured concurrency would break. It is
        // not this function's to report.
        if (error is CancellationException) throw error
      }.onSuccess { codebook ->
        val logger = LoggerFactory.getLogger(Codebook::class.java)
        if (codebook.isEmpty) {
          // Loud, once, at wiring time. Silence here would mean discovering the
          // missing ingest phase as a puzzling per-tool-call refusal instead.
          logger.warn("codebook: no ipeds_regions or nces_locales rows -- {}", UNAVAILABLE)
        } else if (codebook.isDegraded) {
          // A PARTIAL load is the dangerous one: it looks healthy. Name the
          // filters that will not be offered, and the phase that fills them.
          logger.warn(
            "codebook: no values loaded for filter(s) {} -- those filters are not offered and every value " +
              "sent for them is refused; run the ingest phase that loads them (`codebooks`, `subjects`)",
            codebook.emptyVocabularies,
          )
        } else {
          // The counts AND the snapshot semantics, so an operator who has just
          // run the ingest can see whether this process picked it up.
          logger.info(
            "codebook: {} region(s) and {} locale(s) loaded as a boot-time snapshot; " +
              "a later codebook load takes effect at the next restart",
            codebook.regions.size,
            codebook.locales.size,
          )
        }
      }
  }
}
