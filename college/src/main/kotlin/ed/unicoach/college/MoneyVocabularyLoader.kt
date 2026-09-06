package ed.unicoach.college

import ed.unicoach.db.Database
import ed.unicoach.db.dao.CanonicalMoneyDao
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.UpsertOutcome
import ed.unicoach.db.models.AidForm
import ed.unicoach.db.models.FactTable
import ed.unicoach.db.models.FigureArrangement
import ed.unicoach.db.models.FigureStatus
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.MoneySource
import ed.unicoach.db.models.NewAidForm
import ed.unicoach.db.models.NewArrangement
import ed.unicoach.db.models.NewFigureStatus
import ed.unicoach.db.models.NewIncomeBand
import ed.unicoach.db.models.NewPriceConcept
import ed.unicoach.db.models.NewResidencyBasis
import ed.unicoach.db.models.PriceConcept
import ed.unicoach.db.models.ResidencyBasis
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Loads the authored money vocabulary (`db/data/money-vocabulary.json`, RFC
 * 158 D6) into the five canonical vocabulary tables: `residency_bases`,
 * `arrangements`, `figure_statuses`, `price_concepts`, `income_bands`.
 *
 * The [SubjectLoader] discipline, because this is the same kind of input:
 * authored repo data a human reviews. The whole file is verified before the
 * first write -- exact key sets, typed values, duplicate slugs, empty
 * sections -- and a malformed one is a REVIEW error that aborts the run.
 *
 * The one validation beyond the file's own shape is the load-bearing one, and
 * it needs no database: the parsed vocabulary must agree with the Kotlin
 * enums (`ResidencyBasis`, `FigureArrangement`, `FigureStatus`,
 * `PriceConcept`, `IncomeBand`) BOTH ways -- a seed row with no enum value,
 * or an enum value with no seed row, is fatal. The mapping has one home;
 * disagreement is a build error, not drift. The authored flags agree too:
 * `value_bearing`, `arrangement_varies`, `is_living_arrangement` and the
 * income-band labels are each pinned to their enum's own declaration.
 */
class MoneyVocabularyLoader(
  private val database: Database,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
  /**
   * The vocabulary file disagrees with this loader or with the Kotlin enums:
   * a bad shape, a missing or surplus key, a wrongly-typed value, a duplicate
   * slug, an empty section, or an enum disagreement. Repo data, so this is an
   * authoring or review error and it aborts the run before anything is
   * written -- the [SubjectLoader.InvalidFileException] contract.
   */
  class InvalidFileException(
    val fileName: String,
    val detail: String,
    val section: String? = null,
    val entryIndex: Int? = null,
    cause: Throwable? = null,
  ) : RuntimeException(
      "money vocabulary file [$fileName]" +
        (section?.let { " section [$it]" } ?: "") +
        (entryIndex?.let { " entry [$it]" } ?: "") +
        " is invalid: $detail",
      cause,
    )

  /**
   * A vocabulary slug is retiring, but fact rows a source this run will not
   * rebuild still reference it -- so honouring the retirement would delete data
   * nothing is going to write back.
   *
   * Fatal, and it names the way out: re-run the ingest with that source's files
   * so its rows are rebuilt in the same run.
   */
  class RetirementBlockedException(
    val fileName: String,
    val strandedSources: List<MoneySource>,
    val rebuiltSources: Collection<MoneySource>,
  ) : RuntimeException(
      "money vocabulary [$fileName] retires a slug, but canonical fact rows published by " +
        "${strandedSources.map { it.value }} would have to be deleted and this run rebuilds only " +
        "${rebuiltSources.map { it.value }}; re-run with that source's files so its rows are written back",
    )

  /** The parsed five sections, verified as a file but not yet written. */
  data class ParsedVocabulary(
    val residencyBases: List<NewResidencyBasis>,
    val arrangements: List<NewArrangement>,
    val figureStatuses: List<NewFigureStatus>,
    val priceConcepts: List<NewPriceConcept>,
    val incomeBands: List<NewIncomeBand>,
    val aidForms: List<NewAidForm>,
  ) {
    val rows: Int
      get() =
        residencyBases.size + arrangements.size + figureStatuses.size + priceConcepts.size +
          incomeBands.size + aidForms.size
  }

  /** What one [load] did, for the ingest's human summary and the build row. */
  data class LoadResult(
    val rows: Int,
    val inserted: Int,
    val changed: Int,
    val unchanged: Int,
    val deleted: Int,
  )

  /**
   * Parses and fully validates [source]: file shape AND the enum agreement,
   * neither of which needs a database. Run BEFORE the first phase commits, so
   * a typo in repo data can never be discovered after phases have written
   * rows -- the [SubjectLoader.parse] contract.
   */
  suspend fun parse(source: SourceFile): ParsedVocabulary = parse(source.file)

  internal suspend fun parse(file: File): ParsedVocabulary {
    val root =
      try {
        Json.parseToJsonElement(withContext(ioDispatcher) { file.readText() })
      } catch (e: SerializationException) {
        throw InvalidFileException(file.path, "not valid JSON [${e.message}]", cause = e)
      } catch (e: java.io.IOException) {
        // The documented contract is InvalidFileException for every parse
        // failure; an unreadable file is one of them, not a leaked IOException.
        throw InvalidFileException(file.path, "cannot be read [${e.message}]", cause = e)
      }
    val obj =
      root as? JsonObject
        ?: throw InvalidFileException(file.path, "the top level must be a JSON object")
    if (obj.keys != SECTION_KEYS) {
      throw InvalidFileException(file.path, "top-level keys must be exactly [${SECTION_KEYS.sorted()}], got [${obj.keys.sorted()}]")
    }
    val parsed =
      ParsedVocabulary(
        residencyBases =
          section(file, obj, "residency_bases", setOf("slug", "description")) { entry, _ ->
            NewResidencyBasis(slug = entry.slug(), description = entry.text("description"))
          },
        arrangements =
          section(file, obj, "arrangements", setOf("slug", "description", "is_living_arrangement")) { entry, _ ->
            NewArrangement(
              slug = entry.slug(),
              description = entry.text("description"),
              isLivingArrangement = entry.boolean("is_living_arrangement"),
            )
          },
        figureStatuses =
          section(file, obj, "figure_statuses", setOf("slug", "description", "value_bearing")) { entry, _ ->
            NewFigureStatus(
              slug = entry.slug(),
              description = entry.text("description"),
              valueBearing = entry.boolean("value_bearing"),
            )
          },
        priceConcepts =
          section(file, obj, "price_concepts", setOf("slug", "description", "arrangement_varies")) { entry, _ ->
            NewPriceConcept(
              slug = entry.slug(),
              description = entry.text("description"),
              arrangementVaries = entry.boolean("arrangement_varies"),
            )
          },
        incomeBands =
          section(file, obj, "income_bands", setOf("slug", "min_usd", "max_usd", "bracket_label", "sort_order")) { entry, _ ->
            NewIncomeBand(
              slug = entry.slug(),
              minUsd = entry.int("min_usd"),
              maxUsd = entry.intOrNull("max_usd"),
              bracketLabel = entry.text("bracket_label"),
              sortOrder = entry.int("sort_order"),
            )
          },
        aidForms =
          section(file, obj, "aid_forms", setOf("slug", "description")) { entry, _ ->
            NewAidForm(slug = entry.slug(), description = entry.text("description"))
          },
      )
    validateAgainstEnums(file, parsed)
    return parsed
  }

  /**
   * Loads an already-parsed vocabulary in ONE transaction it owns: upsert
   * every row of every section (change-suppressed, three-way
   * [UpsertOutcome]), then delete the slugs each table no longer carries --
   * the `subjects` shape. With the enum agreement already proven, the delete
   * is a transition guard: a slug can leave the file only together with its
   * enum member, and the table must follow the file. When a retirement is
   * pending, the fact rows THIS RUN REBUILDS ([rebuiltSources]) are cleared
   * first so the delete cannot wedge on a foreign key from the previous fill.
   *
   * [rebuiltSources] is the run's own answer to "whose rows will exist again
   * when this ingest finishes": the canonical-money fill's sources always, plus
   * the Common Data Set when the run was given the CDS seed. A retirement that
   * would need to delete anyone else's rows is refused
   * ([RetirementBlockedException]) rather than performed.
   */
  suspend fun load(
    fileName: String,
    vocabulary: ParsedVocabulary,
    rebuiltSources: Collection<MoneySource>,
  ): LoadResult =
    database.withConnection { session ->
      var inserted = 0
      var changed = 0
      var unchanged = 0

      fun record(outcome: UpsertOutcome) =
        when (outcome) {
          UpsertOutcome.INSERTED -> inserted++
          UpsertOutcome.CHANGED -> changed++
          UpsertOutcome.UNCHANGED -> unchanged++
        }
      for (row in vocabulary.residencyBases) record(CanonicalMoneyDao.upsertResidencyBasis(session, row).getOrThrow())
      for (row in vocabulary.arrangements) record(CanonicalMoneyDao.upsertArrangement(session, row).getOrThrow())
      for (row in vocabulary.figureStatuses) record(CanonicalMoneyDao.upsertFigureStatus(session, row).getOrThrow())
      for (row in vocabulary.priceConcepts) record(CanonicalMoneyDao.upsertPriceConcept(session, row).getOrThrow())
      for (row in vocabulary.incomeBands) record(CanonicalMoneyDao.upsertIncomeBand(session, row).getOrThrow())
      for (row in vocabulary.aidForms) record(CanonicalMoneyDao.upsertAidForm(session, row).getOrThrow())
      // A retired slug may still be referenced by the previous fill's fact
      // rows, and the delete-not-in below would then fail 23503 BEFORE the
      // canonical-money phase ever clears them -- wedging every re-run. The
      // rows this run REBUILDS are cleared here, which loses nothing: they are
      // written again later in the same run (P12).
      //
      // Only those. Deleting every source's rows was a silent data-loss path:
      // `--money-vocabulary` runs on every ingest while the CDS group is
      // optional, so a run without `-p` erased every aid-form requirement and
      // every Common Data Set cohort row, refilled only the sources it does
      // rebuild, and exited green. A slug still held by a source this run will
      // NOT rebuild is a refusal, not a deletion -- the operator re-runs with
      // that source's files rather than losing its rows.
      if (hasRetiredSlugs(session, vocabulary)) {
        for (table in FactTable.entries) {
          CanonicalMoneyDao.deleteFactsOfSources(session, table, rebuiltSources).getOrThrow()
        }
        val stranded = CanonicalMoneyDao.factSourcesOtherThan(session, rebuiltSources).getOrThrow()
        if (stranded.isNotEmpty()) {
          throw RetirementBlockedException(fileName, stranded, rebuiltSources)
        }
      }

      val deleted =
        deleteNotIn(session, "residency_bases", vocabulary.residencyBases.map { it.slug }) +
          deleteNotIn(session, "arrangements", vocabulary.arrangements.map { it.slug }) +
          deleteNotIn(session, "figure_statuses", vocabulary.figureStatuses.map { it.slug }) +
          deleteNotIn(session, "price_concepts", vocabulary.priceConcepts.map { it.slug }) +
          deleteNotIn(session, "income_bands", vocabulary.incomeBands.map { it.slug }) +
          deleteNotIn(session, "aid_forms", vocabulary.aidForms.map { it.slug })
      val result =
        LoadResult(
          rows = vocabulary.rows,
          inserted = inserted,
          changed = changed,
          unchanged = unchanged,
          deleted = deleted,
        )
      logger.info(
        "Money vocabulary [{}]: [{}] rows ([{}] inserted, [{}] changed, [{}] unchanged, [{}] deleted) across " +
          "residency_bases, arrangements, figure_statuses, price_concepts, income_bands, aid_forms",
        fileName,
        result.rows,
        result.inserted,
        result.changed,
        result.unchanged,
        result.deleted,
      )
      result
    }

  private fun deleteNotIn(
    session: SqlSession,
    table: String,
    kept: List<String>,
  ): Int = CanonicalMoneyDao.deleteVocabularyNotIn(session, table, kept).getOrThrow()

  /** TRUE when any vocabulary table stores a slug the parsed seed no longer carries -- a retirement is pending. */
  private fun hasRetiredSlugs(
    session: SqlSession,
    vocabulary: ParsedVocabulary,
  ): Boolean =
    listOf(
      "residency_bases" to vocabulary.residencyBases.map { it.slug },
      "arrangements" to vocabulary.arrangements.map { it.slug },
      "figure_statuses" to vocabulary.figureStatuses.map { it.slug },
      "price_concepts" to vocabulary.priceConcepts.map { it.slug },
      "income_bands" to vocabulary.incomeBands.map { it.slug },
      "aid_forms" to vocabulary.aidForms.map { it.slug },
    ).any { (table, kept) ->
      CanonicalMoneyDao.vocabularySlugs(session, table).getOrThrow().any { it !in kept }
    }

  // ---------------------------------------------------------------------------
  // Enum agreement (both ways, fatal)
  // ---------------------------------------------------------------------------

  private fun validateAgainstEnums(
    file: File,
    parsed: ParsedVocabulary,
  ) {
    fun refuse(
      section: String,
      detail: String,
    ): Nothing = throw InvalidFileException(file.path, detail, section = section)

    fun requireSameMembers(
      section: String,
      seedSlugs: Set<String>,
      enumName: String,
      enumValues: Set<String>,
    ) {
      val missingFromEnum = seedSlugs - enumValues
      val missingFromSeed = enumValues - seedSlugs
      if (missingFromEnum.isNotEmpty() || missingFromSeed.isNotEmpty()) {
        refuse(
          section,
          "the seed and $enumName disagree" +
            (if (missingFromEnum.isEmpty()) "" else "; seed rows with no enum value: ${missingFromEnum.sorted()}") +
            (if (missingFromSeed.isEmpty()) "" else "; enum values with no seed row: ${missingFromSeed.sorted()}") +
            "; the mapping has one home and disagreement is a build error, not drift",
        )
      }
    }
    requireSameMembers(
      "residency_bases",
      parsed.residencyBases.map { it.slug }.toSet(),
      "ResidencyBasis",
      ResidencyBasis.entries.map { it.value }.toSet(),
    )
    requireSameMembers(
      "arrangements",
      parsed.arrangements.map { it.slug }.toSet(),
      "FigureArrangement",
      FigureArrangement.entries.map { it.value }.toSet(),
    )
    requireSameMembers(
      "figure_statuses",
      parsed.figureStatuses.map { it.slug }.toSet(),
      "FigureStatus",
      FigureStatus.entries.map { it.value }.toSet(),
    )
    requireSameMembers(
      "price_concepts",
      parsed.priceConcepts.map { it.slug }.toSet(),
      "PriceConcept",
      PriceConcept.entries.map { it.value }.toSet(),
    )
    requireSameMembers(
      "income_bands",
      parsed.incomeBands.map { it.slug }.toSet(),
      "IncomeBand",
      IncomeBand.entries.map { it.value }.toSet(),
    )
    requireSameMembers(
      "aid_forms",
      parsed.aidForms.map { it.slug }.toSet(),
      "AidForm",
      AidForm.entries.map { it.value }.toSet(),
    )

    // The authored flags agree with the enum's own declaration, per member:
    // a seed that calls housing arrangement-invariant would silently change
    // what the loader-enforced pairing rule (P3) permits.
    for (row in parsed.arrangements) {
      val enum = FigureArrangement.fromValue(row.slug)!!
      if (enum.isLivingArrangement != row.isLivingArrangement) {
        refuse(
          "arrangements",
          "[${row.slug}] is_living_arrangement [${row.isLivingArrangement}] disagrees with " +
            "FigureArrangement.${enum.name} [${enum.isLivingArrangement}]",
        )
      }
    }
    for (row in parsed.figureStatuses) {
      val enum = FigureStatus.fromValue(row.slug)!!
      if (enum.valueBearing != row.valueBearing) {
        refuse(
          "figure_statuses",
          "[${row.slug}] value_bearing [${row.valueBearing}] disagrees with FigureStatus.${enum.name} [${enum.valueBearing}]",
        )
      }
    }
    for (row in parsed.priceConcepts) {
      val enum = PriceConcept.fromValue(row.slug)!!
      if (enum.arrangementVaries != row.arrangementVaries) {
        refuse(
          "price_concepts",
          "[${row.slug}] arrangement_varies [${row.arrangementVaries}] disagrees with " +
            "PriceConcept.${enum.name} [${enum.arrangementVaries}]",
        )
      }
    }
    // P10: the bracket labels feed live wire labels through IncomeBand.bracket
    // until shape/04 cuts the consumer over, so seed and Kotlin must
    // byte-agree -- one bracket, one spelling.
    for (row in parsed.incomeBands) {
      val enum = IncomeBand.fromValue(row.slug)!!
      if (enum.bracket != row.bracketLabel) {
        refuse(
          "income_bands",
          "[${row.slug}] bracket_label [${row.bracketLabel}] disagrees with IncomeBand.${enum.name} [${enum.bracket}]",
        )
      }
    }
    val sortOrders = parsed.incomeBands.sortedBy { it.sortOrder }.map { it.slug }
    val enumOrder = IncomeBand.entries.map { it.value }
    if (sortOrders != enumOrder) {
      refuse(
        "income_bands",
        "sort_order sequence $sortOrders disagrees with IncomeBand declaration order $enumOrder",
      )
    }
  }

  // ---------------------------------------------------------------------------
  // Section/entry parsing (exact key set, typed values -- the SubjectLoader
  // parseEntry discipline)
  // ---------------------------------------------------------------------------

  /** One parsed entry plus its location, so every refusal names the file, section and index. */
  private class Entry(
    val file: File,
    val section: String,
    val index: Int,
    val fields: JsonObject,
  ) {
    fun invalid(detail: String): Nothing = throw InvalidFileException(file.path, detail, section = section, entryIndex = index)

    fun slug(): String {
      val slug = text("slug")
      if (!SLUG_REGEX.matches(slug)) {
        invalid("[slug] must match [${SLUG_REGEX.pattern}] (the canonical-money underscore-slug format)")
      }
      return slug
    }

    fun text(key: String): String {
      val value =
        (fields.getValue(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
          ?: invalid("[$key] must be a JSON string, got [${fields.getValue(key)}]")
      if (value.isBlank()) invalid("[$key] must not be blank")
      return value
    }

    fun boolean(key: String): Boolean =
      (fields.getValue(key) as? JsonPrimitive)
        ?.takeIf { !it.isString }
        ?.content
        ?.toBooleanStrictOrNull()
        ?: invalid("[$key] must be a JSON boolean, got [${fields.getValue(key)}]")

    fun int(key: String): Int =
      (fields.getValue(key) as? JsonPrimitive)
        ?.takeIf { !it.isString }
        ?.content
        ?.toIntOrNull()
        ?: invalid("[$key] must be a JSON integer, got [${fields.getValue(key)}]")

    fun intOrNull(key: String): Int? {
      if (fields.getValue(key) is JsonNull) return null
      return int(key)
    }
  }

  private fun <T> section(
    file: File,
    root: JsonObject,
    name: String,
    keys: Set<String>,
    map: (Entry, Int) -> T,
  ): List<T> {
    val array =
      root.getValue(name) as? JsonArray
        ?: throw InvalidFileException(file.path, "must be a JSON array", section = name)
    // An EMPTY section parses clean and then wipes its table: the upserts
    // write nothing and delete-not-in deletes every row, and with the fact
    // tables foreign-keyed onto the vocabulary that is a run-wide failure
    // nobody authored. The enum agreement would refuse it too, but the empty
    // file deserves its own message (the SubjectLoader floor-of-one rule).
    if (array.isEmpty()) {
      throw InvalidFileException(
        file.path,
        "must not be empty; loading it would delete every row and refuse every canonical fact write",
        section = name,
      )
    }
    val entries =
      array.mapIndexed { index, element ->
        val obj =
          element as? JsonObject
            ?: throw InvalidFileException(file.path, "each entry must be a JSON object", section = name, entryIndex = index)
        if (obj.keys != keys) {
          throw InvalidFileException(
            file.path,
            "keys must be exactly [${keys.sorted()}], got [${obj.keys.sorted()}]",
            section = name,
            entryIndex = index,
          )
        }
        Entry(file, name, index, obj)
      }
    val duplicates =
      entries
        .groupingBy { (it.fields.getValue("slug") as? JsonPrimitive)?.content }
        .eachCount()
        .filterValues { it > 1 }
        .keys
        .filterNotNull()
        .sorted()
    if (duplicates.isNotEmpty()) {
      throw InvalidFileException(file.path, "duplicate slug(s) [$duplicates]", section = name)
    }
    return entries.mapIndexed { index, entry -> map(entry, index) }
  }

  companion object {
    private val logger = LoggerFactory.getLogger(MoneyVocabularyLoader::class.java)

    /** The exact top-level key set -- a surplus section is a typo, never surplus data. */
    private val SECTION_KEYS =
      setOf("residency_bases", "arrangements", "figure_statuses", "price_concepts", "income_bands", "aid_forms")

    /**
     * The canonical-money slug spelling, restated from the migration's
     * `*_slug_format_check` CHECKs so a typo fails at review altitude:
     * underscore-separated lowercase, because the vocabulary must byte-agree
     * with `LivingArrangement`/`IncomeBand`/`money_profiles`' existing
     * underscore values (NOT the hyphens-only 0060 `slug` domain).
     */
    private val SLUG_REGEX = Regex("^[a-z0-9]+(_[a-z0-9]+)*$")
  }
}
