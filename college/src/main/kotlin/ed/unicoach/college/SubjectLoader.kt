package ed.unicoach.college

import ed.unicoach.db.Database
import ed.unicoach.db.dao.CodebooksDao
import ed.unicoach.db.dao.UpsertOutcome
import ed.unicoach.db.models.CipPrefix
import ed.unicoach.db.models.NewSubject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Loads the authored subject taxonomy (`db/data/subjects.json`, RFC 150 D49)
 * into the `subjects` table.
 *
 * It is the third kind of ingest input, beside the Scorecard CSVs and the
 * GENERATED codebook: repo data a human authored and a human reviews. So it
 * follows [CollegeScorecardLoader.parseAliases]'s contract rather than
 * [CodebookLoader]'s — the whole file is verified before the first write, and a
 * malformed one is a REVIEW error that aborts the run rather than a row that is
 * skipped and counted.
 *
 * The one validation that is not about the file's own shape is the one that
 * matters most, and it is FATAL (D49): every `cip_prefix` must match at least
 * one row of `cip_codes`. A prefix that matches nothing is a subject that can
 * never match a college — a silent hole in the taxonomy that no result would
 * ever reveal, because "no colleges offer that" and "that subject is broken"
 * read identically. `5116`, the retired nursing series, is the case this exists
 * for. It is checked against the CIP vocabulary the same run just loaded, which
 * is why the taxonomy loads in its own `subjects` phase placed IMMEDIATELY
 * after `codebooks`, and never before it.
 *
 * What is deliberately NOT enforced here is a size: there is no cap on subjects
 * and no cap on prefixes (D50). What bounds the file is the CIP vocabulary it
 * partitions — 1,710 six-digit codes, 405 four-digit series, 38 families — not
 * taste. A subject matching no COLLEGE is allowed and reads as zero matches; a
 * subject matching no CIP CODE is fatal.
 */
class SubjectLoader(
  private val database: Database,
  // Blocking file IO (the taxonomy read) runs on this dispatcher, never a
  // caller's coroutine thread — the [CodebookLoader] constructor-injection
  // pattern, overridable in tests. The switch lives INSIDE this class, so no
  // caller has to know that [parse] touches a disk.
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
  /**
   * The subject file disagrees with this loader: a bad shape, a missing or
   * surplus key, a wrongly-typed value, a prefix that is not a CIP prefix, or a
   * duplicate slug. Repo data, so this is an authoring or review error and it
   * aborts the run before anything is written — the
   * [CollegeScorecardLoader.InvalidAliasFileException] contract.
   */
  class InvalidFileException(
    val fileName: String,
    val detail: String,
    val slug: String? = null,
    val entryIndex: Int? = null,
    cause: Throwable? = null,
  ) : RuntimeException(
      "subject file [$fileName]" +
        (entryIndex?.let { " entry [$it]" } ?: "") +
        (slug?.let { " subject [$it]" } ?: "") +
        " is invalid: $detail",
      cause,
    )

  /**
   * A `cip_prefix` matches ZERO rows of `cip_codes` (D49). Named separately from
   * [InvalidFileException] because it is not a shape error: the file is
   * well-formed and the prefix is a well-formed CIP prefix — it simply is not a
   * code the loaded vocabulary publishes. The message names every offending
   * (subject, prefix) pair, not just the first, so one edit fixes the file.
   */
  class UnmatchedCipPrefixException(
    val fileName: String,
    val unmatched: List<Pair<String, String>>,
  ) : RuntimeException(
      "subject file [$fileName] has ${unmatched.size} cip_prefix value(s) matching no cip_codes row: " +
        unmatched.joinToString(", ") { (slug, prefix) -> "[$slug] -> [$prefix]" } +
        "; the taxonomy cannot ship a subject that can never match a college",
    )

  /** What one [load] did, for the ingest's human summary and the build row. */
  data class LoadResult(
    val subjects: Int,
    val inserted: Int,
    val changed: Int,
    val unchanged: Int,
    val deleted: Int,
    /** Distinct six-digit `cip_codes` rows the whole taxonomy expands to. */
    val expandedCipCodes: Int,
  )

  /**
   * Parses [source] and refuses everything the FILE alone can be wrong about:
   * JSON shape, key set, value types, prefix canonicalization, duplicate slugs.
   *
   * Split from [load] for the reason the codebook parse is: it touches no
   * database, so the ingest runs it BEFORE the first phase commits, and a typo
   * in repo data can never be discovered after five phases have written rows.
   * The database half — the prefix-matches-a-real-code check — cannot run this
   * early, because the CIP vocabulary it checks against is loaded by the very
   * phase this file loads in.
   */
  suspend fun parse(source: SourceFile): List<NewSubject> = parse(source.file)

  internal suspend fun parse(file: File): List<NewSubject> {
    val root =
      try {
        Json.parseToJsonElement(withContext(ioDispatcher) { file.readText() })
      } catch (e: SerializationException) {
        throw InvalidFileException(file.path, "not valid JSON [${e.message}]", cause = e)
      }
    val array =
      root as? JsonArray
        ?: throw InvalidFileException(file.path, "the top level must be a JSON array")
    // An EMPTY taxonomy parses clean and then WIPES the table: `load` upserts
    // nothing and `deleteSubjectsNotIn(emptySet)` deletes every row, so the next
    // rebuild materialises no `subject_slugs` at all and every subject filter
    // silently stops matching. The codebook loader guards its own shrink floor
    // for the same reason; here the floor is one. A taxonomy is deliberately
    // authored data — deleting it is an edit nobody makes by accident, and if
    // they do, they should have to say so in a migration rather than in a file
    // that reads as "no subjects today".
    if (array.isEmpty()) {
      throw InvalidFileException(
        file.path,
        "the taxonomy is empty; loading it would delete every subject and silently disable every " +
          "subject filter until the next authored file is loaded",
      )
    }
    val entries = array.mapIndexed { index, element -> parseEntry(file, index, element) }

    // A duplicate slug is FATAL for the aliases file's reason: loading both
    // would be last-writer-wins by file order, a silent editing mistake in
    // curated repo data.
    val duplicates =
      entries
        .groupingBy { it.slug }
        .eachCount()
        .filterValues { it > 1 }
        .keys
        .sorted()
    if (duplicates.isNotEmpty()) {
      throw InvalidFileException(file.path, "duplicate slug(s) $duplicates")
    }
    return entries
  }

  /**
   * Loads an already-parsed taxonomy in ONE transaction it owns ([Database.withConnection],
   * the [CodebookLoader.load] shape): prove every prefix against `cip_codes`
   * FIRST, then upsert, then delete the subjects the file no longer carries.
   *
   * The order is the contract. The validation reads the `cip_codes` rows the
   * `codebooks` phase just wrote, and it throws before the first `subjects`
   * write — so a taxonomy with a dead prefix leaves the table exactly as it was,
   * and the run reaches no `college_index_build` row at all.
   *
   * The delete is not optional housekeeping. `college_search_index.subject_slugs`
   * is MATERIALISED (D51) and the rebuild happens later in the same run, so a
   * subject removed from the file but left in the table would keep matching
   * colleges forever — the one way editing the taxonomy could make search worse.
   */
  suspend fun load(
    fileName: String,
    subjects: List<NewSubject>,
  ): LoadResult =
    database.withConnection { session ->
      val prefixes = subjects.flatMap { it.cipPrefixes }.distinct()
      val matches = CodebooksDao.cipCodesMatchingPrefixes(session, prefixes).getOrThrow()
      val unmatched =
        subjects.flatMap { subject ->
          subject.cipPrefixes.filter { (matches[it] ?: 0) == 0 }.map { subject.slug to it }
        }
      if (unmatched.isNotEmpty()) throw UnmatchedCipPrefixException(fileName, unmatched)

      var inserted = 0
      var changed = 0
      var unchanged = 0
      for (subject in subjects) {
        when (CodebooksDao.upsertSubject(session, subject).getOrThrow()) {
          UpsertOutcome.INSERTED -> inserted++
          UpsertOutcome.CHANGED -> changed++
          UpsertOutcome.UNCHANGED -> unchanged++
        }
      }
      val deleted = CodebooksDao.deleteSubjectsNotIn(session, subjects.map { it.slug }).getOrThrow()
      val result =
        LoadResult(
          subjects = CodebooksDao.subjectCount(session).getOrThrow(),
          inserted = inserted,
          changed = changed,
          unchanged = unchanged,
          deleted = deleted,
          // Printed on EVERY run, deliberately (D51's risk): the taxonomy is
          // materialised into the index, so an edit to subjects.json changes
          // nothing until a rebuild runs. Saying how many codes the taxonomy
          // expands to is what makes a rebuild visibly a rebuild.
          expandedCipCodes = matches.values.sum(),
        )
      logger.info(
        "Subjects [{}]: {} subjects ({} inserted, {} changed, {} unchanged, {} deleted) over {} CIP prefixes " +
          "expanding to {} code match(es); the search index reflects them only after the search-index phase",
        fileName,
        result.subjects,
        result.inserted,
        result.changed,
        result.unchanged,
        result.deleted,
        prefixes.size,
        result.expandedCipCodes,
      )
      result
    }

  private fun parseEntry(
    file: File,
    index: Int,
    element: JsonElement,
  ): NewSubject {
    fun invalid(detail: String): Nothing = throw InvalidFileException(file.path, detail, entryIndex = index)
    val obj = element as? JsonObject ?: invalid("each entry must be a JSON object")
    if (obj.keys != SUBJECT_KEYS) {
      invalid("keys must be exactly $SUBJECT_KEYS, got ${obj.keys.sorted()}")
    }
    val slug = stringOf(obj["slug"]) ?: invalid("[slug] must be a JSON string")

    fun invalidWithSlug(detail: String): Nothing = throw InvalidFileException(file.path, detail, slug = slug, entryIndex = index)
    // The `slug` DOMAIN would refuse this at the write, but the write is inside
    // a transaction five other things share; refusing here keeps a typo a
    // review error with the entry index attached rather than a constraint name.
    if (!SLUG_REGEX.matches(slug)) invalidWithSlug("[slug] must match [${SLUG_REGEX.pattern}] (the shared `slug` domain)")
    val name = stringOf(obj["name"]) ?: invalidWithSlug("[name] must be a JSON string")
    if (name.isBlank()) invalidWithSlug("[name] must not be blank")
    val rawPrefixes = stringListOf(obj["cip_prefixes"]) ?: invalidWithSlug("[cip_prefixes] must be an array of JSON strings")
    if (rawPrefixes.isEmpty()) invalidWithSlug("[cip_prefixes] must not be empty")
    // Canonicalized through the ONE parser the query boundary already uses, so
    // an authored "26.07" and a model-authored "26.07" become the same digits.
    val prefixes =
      rawPrefixes.map { raw ->
        CipPrefix.parseOrNull(raw) ?: invalidWithSlug("[cip_prefixes] value [$raw] is not a 2-, 4- or 6-digit CIP prefix")
      }
    val duplicated =
      prefixes
        .groupingBy { it }
        .eachCount()
        .filterValues { it > 1 }
        .keys
        .sorted()
    if (duplicated.isNotEmpty()) invalidWithSlug("[cip_prefixes] repeats $duplicated after canonicalization")
    return NewSubject(slug = slug, name = name, cipPrefixes = prefixes)
  }

  companion object {
    private val logger = LoggerFactory.getLogger(SubjectLoader::class.java)

    /** The exact key set one subject entry may carry — a surplus key is a typo, never surplus data. */
    private val SUBJECT_KEYS = setOf("slug", "name", "cip_prefixes")

    /** The shared `slug` DOMAIN (0060), restated so a typo fails at review altitude. */
    private val SLUG_REGEX = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
  }
}
