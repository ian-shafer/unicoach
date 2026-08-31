package ed.unicoach.college

import ed.unicoach.db.Database
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

  private val regionBySlug: Map<String, Region> = regions.associateBy { it.slug }
  private val regionByCode: Map<Int, Region> = regions.associateBy { it.code }
  private val localeByCode: Map<Int, Locale> = locales.associateBy { it.code }

  /** True when neither codebook has been loaded — see the class note. */
  val isEmpty: Boolean = regions.isEmpty() && locales.isEmpty()

  /** Every region word, in published-code order: the tool's `region` enum. */
  val regionSlugs: List<String> = regions.map { it.slug }

  /** The published `OBEREG` code for [slug], or null when no such word exists. */
  fun regionCode(slug: String): Int? = regionBySlug[slug]?.code

  /** The word for a stored `colleges.region` code, or null when it has no row. */
  fun regionSlug(code: Int): String? = regionByCode[code]?.slug

  /** The locale row a stored `colleges.locale` code names, or null. */
  fun locale(code: Int): Locale? = localeByCode[code]

  /**
   * The published locale codes matching [type], narrowed to [detail] when one is
   * given. An empty list means the pairing is not published (there is no
   * "city: fringe"), which the caller reports as a listed error — the codes
   * themselves never leave this class as a filter that quietly matches nothing.
   */
  fun localeCodes(
    type: NcesLocaleType,
    detail: NcesLocaleDetail? = null,
  ): List<Int> = locales.filter { it.type == type && (detail == null || it.detail == detail) }.map { it.code }

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
              Codebook(regions, CodebooksDao.ncesLocales(session).getOrThrow())
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
