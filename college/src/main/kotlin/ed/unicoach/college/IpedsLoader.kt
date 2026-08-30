package ed.unicoach.college

import ed.unicoach.college.CsvIngestSupport.doubleInDomainOrNull
import ed.unicoach.college.CsvIngestSupport.intInDomainOrNull
import ed.unicoach.college.CsvIngestSupport.intOrNull
import ed.unicoach.college.CsvIngestSupport.logCoercion
import ed.unicoach.college.CsvIngestSupport.recordUpsertFailure
import ed.unicoach.college.CsvIngestSupport.stringOrNull
import ed.unicoach.college.CsvIngestSupport.upsertWithSavepoint
import ed.unicoach.db.Database
import ed.unicoach.db.dao.CollegeIpedsDao
import ed.unicoach.db.dao.UpsertOutcome
import ed.unicoach.db.models.CollegeId
import ed.unicoach.db.models.NewCollegeIpeds
import ed.unicoach.db.models.NewCollegeProgramsCensus
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.csv.CSVRecord
import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * The four IPEDS source files of one survey year, supplied as an
 * all-or-nothing group (gate-2 D19) alongside the Scorecard trio.
 * [surveyYear] is explicit rather than derived from a filename — a derived year
 * is a silent coercion, and the year is stamped on every row written.
 */
data class IpedsSources(
  val hd: SourceFile,
  val ic: SourceFile,
  val adm: SourceFile,
  val completions: SourceFile,
  val surveyYear: Int,
) {
  /** The four files in provenance order, for digesting and header assertion. */
  val files: List<SourceFile> get() = listOf(hd, ic, adm, completions)
}

/**
 * Per-file outcome for the IPEDS attribute phase: the RFC 139 upsert split on an
 * UNVERSIONED table (the DAO reports it directly), plus the structured breakdown
 * of every skipped row and every optional cell coerced to NULL.
 *
 * [seen] counts HD records — the phase's driving file, one row per institution
 * written. [skipsByReason] and [fieldsCoercedToNull] span ALL THREE files: an
 * unmappable IC/ADM row and a coerced IC cell each remove part of an
 * institution's attributes, so they are counted here rather than logged only.
 */
data class IpedsLoadResult(
  val seen: Int,
  val inserted: Int,
  val changed: Int,
  val unchanged: Int,
  val skipsByReason: Map<SkipReason, Int>,
  val fieldsCoercedToNull: Map<String, Int>,
) {
  val loaded: Int get() = inserted + changed + unchanged
  val skipped: Int get() = skipsByReason.values.sum()

  /**
   * HD records naming a `ipeds_unit_id` no `colleges` row carries. Counted and
   * skipped, never invented — and read off the skip taxonomy rather than
   * tallied twice, so the two numbers cannot disagree.
   */
  val unmatchedIpedsUnitIds: Int get() = skipsByReason[SkipReason.NoCollegeForIpedsUnitId] ?: 0
  val transientSkips: Int get() = skipsByReason[SkipReason.Transient] ?: 0
}

/**
 * Per-file outcome for the program-census phase. [seen] is every C_A row read;
 * [selected] is the subset the documented bachelor's-first-major filter keeps
 * (`AWLEVEL = 5`, `MAJORNUM = 1`, `CIPCODE <> '99'`). The difference is a
 * deliberate exclusion, not a loss, so it is reported as its own number instead
 * of being buried in the skip taxonomy — which stays reserved for rows that
 * SHOULD have loaded and did not.
 */
data class CensusLoadResult(
  val seen: Int,
  val selected: Int,
  val inserted: Int,
  val changed: Int,
  val unchanged: Int,
  val skipsByReason: Map<SkipReason, Int>,
) {
  val loaded: Int get() = inserted + changed + unchanged
  val skipped: Int get() = skipsByReason.values.sum()
  val unmatchedIpedsUnitIds: Int get() = skipsByReason[SkipReason.NoCollegeForIpedsUnitId] ?: 0
  val transientSkips: Int get() = skipsByReason[SkipReason.Transient] ?: 0
}

/**
 * Re-runnable loader for the IPEDS institutional-characteristics survey (RFC
 * 144): `HD` (the driving file), `IC` and `ADM` (left-joined in memory), and
 * `C_A` (the 6-digit program census). It shares every generic mechanism with
 * [CollegeScorecardLoader] through [CsvIngestSupport] — header assertion, cell
 * coercion, the per-row savepoint, the skip taxonomy — and adds only what is
 * IPEDS-specific: the column lists and the sentinel rules.
 *
 * **The sentinel rule, stated once.** `-1` (not reported), `-3` (not available),
 * IC's `.` for a continuous column and ADM's empty string all mean UNKNOWN and
 * become NULL. `-2` means "not applicable", which for these columns is a real
 * *no*: it is kept as the raw code where the column is a code column
 * (`rel_affil`, `carnegie_basic`, `carnegie_size`, `cbsa`, `football_conf`) and
 * mapped to `false` where the column is a boolean. Collapsing the two would
 * turn "explicitly not religious" (5,164 institutions) into "we don't know".
 */
internal class IpedsLoader(
  private val database: Database,
  private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
  private val logger = LoggerFactory.getLogger(IpedsLoader::class.java)

  /**
   * Asserts all four IPEDS headers before any IPEDS write. Called from
   * [CollegeScorecardLoader.ingest] together with the Scorecard assertions, so
   * a bad IPEDS header cannot corrupt a run that has already written Scorecard
   * rows: all seven files are asserted up front, before phase 1. Like
   * [loadAttributes] it owns its own [ioDispatcher] hop, so the caller reads as
   * a plain phase list and never has to know this one blocks.
   */
  suspend fun assertHeaders(sources: IpedsSources) =
    withContext(ioDispatcher) {
      CsvIngestSupport.assertRequiredColumns(sources.hd, REQUIRED_HD_COLUMNS)
      CsvIngestSupport.assertRequiredColumns(sources.ic, REQUIRED_IC_COLUMNS)
      CsvIngestSupport.assertRequiredColumns(sources.adm, REQUIRED_ADM_COLUMNS)
      CsvIngestSupport.assertRequiredColumns(sources.completions, REQUIRED_COMPLETIONS_COLUMNS)
    }

  // ---------------------------------------------------------------------------
  // Phase: attributes (HD ⟕ IC ⟕ ADM)
  // ---------------------------------------------------------------------------

  /**
   * Loads one `college_ipeds` row per HD record whose `UNITID` matches an
   * existing `colleges.ipeds_unit_id`. IC and ADM are read into memory first and
   * LEFT-joined: 114 HD institutions have no IC row and 749 of the 2,488
   * four-year universe institutions have no ADM row, so an inner join would
   * silently drop ~30% of the universe. An unmatched `ipeds_unit_id` is counted and
   * skipped, never invented.
   */
  suspend fun loadAttributes(sources: IpedsSources): IpedsLoadResult {
    // ONE accumulator for the whole phase, side files included: an IC row that
    // cannot be mapped, or an IC cell coerced to NULL, is a loss this phase
    // must report, not a DEBUG line. The tallies are per-FILE (each pass runs
    // to completion before the next), so no IC coercion is attributed to an HD
    // row.
    val count = LoadCount()
    val icRows = withContext(ioDispatcher) { readKeyedRows(sources.ic.file, "IC", count, ::mapIc) }
    val admRows = withContext(ioDispatcher) { readKeyedRows(sources.adm.file, "ADM", count, ::mapAdm) }
    return database.withConnection { session ->
      // The match set, read once (~6k rows): a per-row lookup would be 6k
      // round-trips to answer a question one SELECT answers.
      val collegeIpedsUnitIds = CollegeIpedsDao.collegeIdsByIpedsUnitId(session).getOrThrow().keys
      // The ipeds_unit_ids this pass has already written: HD is the driving file and
      // its key is unique in the real source, but a repeated UNITID would
      // otherwise overwrite the earlier row and be reported as an ordinary
      // `changed`. First row wins, and the loser is COUNTED.
      val written = mutableSetOf<Int>()
      parseCsv(sources.hd.file).use { records ->
        for (record in records) {
          count.seen++
          if (!CsvIngestSupport.isWellFormed(record)) {
            count.recordSkip(SkipReason.RowArityMismatch)
            logger.debug(
              "Skipping IPEDS HD row [line={}]: [{}] cells, header has [{}]",
              record.recordNumber,
              record.size(),
              records.headerMap.size,
            )
            continue
          }
          val mapped =
            when (val result = mapHd(record)) {
              is MapResult.Skipped -> {
                count.recordSkip(result.reason)
                continue
              }

              is MapResult.Mapped -> {
                result
              }
            }
          val hd = mapped.value
          if (hd.ipedsUnitId !in collegeIpedsUnitIds) {
            count.recordSkip(SkipReason.NoCollegeForIpedsUnitId)
            logger.debug(
              "Skipping IPEDS attribute row [line={}]: no college for [ipeds_unit_id={}]",
              record.recordNumber,
              hd.ipedsUnitId,
            )
            continue
          }
          if (!written.add(hd.ipedsUnitId)) {
            count.recordSkip(SkipReason.DuplicateKeyInFile)
            logger.warn(
              "IPEDS HD file repeats [ipeds_unit_id={}] at [line={}]; keeping the first row",
              hd.ipedsUnitId,
              record.recordNumber,
            )
            continue
          }
          val row = hd.toRow(sources.surveyYear, icRows[hd.ipedsUnitId], admRows[hd.ipedsUnitId])
          // Recorded only now that the row actually reaches the DB — a skipped
          // row's coercions are not coercions that happened to the table.
          count.recordCoercions(mapped.coercions)
          val result = upsertWithSavepoint(session) { CollegeIpedsDao.upsert(session, row) }
          if (result.isFailure) {
            recordUpsertFailure(count, result.exceptionOrNull(), "ipeds", "ipeds_unit_id", hd.ipedsUnitId, record.recordNumber)
          } else {
            count.loaded++
            when (result.getOrThrow()) {
              UpsertOutcome.INSERTED -> count.inserted++
              UpsertOutcome.CHANGED -> count.changed++
              UpsertOutcome.UNCHANGED -> count.unchanged++
            }
          }
        }
      }
      logger.info(
        "Load summary [ipeds]: [loaded={}] [skips={}] [coercions={}]",
        count.loaded,
        count.skipsByReason,
        count.fieldsCoercedToNull,
      )
      IpedsLoadResult(
        seen = count.seen,
        inserted = count.inserted,
        changed = count.changed,
        unchanged = count.unchanged,
        skipsByReason = count.skipsByReason.toMap(),
        fieldsCoercedToNull = count.fieldsCoercedToNull.toMap(),
      )
    }
  }

  // ---------------------------------------------------------------------------
  // Phase: 6-digit program census (C_A)
  // ---------------------------------------------------------------------------

  /**
   * Loads the bachelor's first-major rows of the completions file. The
   * `MAJORNUM = 1` filter is not cosmetic: it is what makes the natural key
   * `(college_id, cip_code, award_level)` sound, since the second-major rows
   * collide on it 19,041 times at `AWLEVEL = 5` and would double-count
   * students. `CIPCODE = '99'` is the grand-total row (verified equal to the
   * sum of its 6-digit siblings in all 3,493 groups) and is excluded for the
   * same reason.
   */
  suspend fun loadProgramsCensus(sources: IpedsSources): CensusLoadResult =
    database.withConnection { session ->
      val count = LoadCount()
      var selected = 0
      val collegeIds = CollegeIpedsDao.collegeIdsByIpedsUnitId(session).getOrThrow()
      // The census keys already written by this pass; see the HD loop's note.
      // Two raw CIPCODEs can also collide only AFTER the dot is stripped, so
      // the check is on the stored key, not the source cell.
      val written = mutableSetOf<Triple<CollegeId, String, Int>>()
      parseCsv(sources.completions.file).use { records ->
        for (record in records) {
          count.seen++
          if (!CsvIngestSupport.isWellFormed(record)) {
            count.recordSkip(SkipReason.RowArityMismatch)
            logger.debug(
              "Skipping census row [line={}]: [{}] cells, header has [{}]",
              record.recordNumber,
              record.size(),
              records.headerMap.size,
            )
            continue
          }
          when (val selection = selectBachelorsFirstMajor(record)) {
            is CensusSelection.Skipped -> {
              count.recordSkip(selection.reason)
              continue
            }

            CensusSelection.Excluded -> {
              continue
            }

            CensusSelection.Selected -> {
              selected++
            }
          }
          val fields =
            when (val mapped = mapCompletion(record)) {
              is MapResult.Skipped -> {
                count.recordSkip(mapped.reason)
                continue
              }

              is MapResult.Mapped -> {
                mapped.value
              }
            }
          val collegeId = collegeIds[fields.ipedsUnitId]
          if (collegeId == null) {
            count.recordSkip(SkipReason.NoCollegeForIpedsUnitId)
            logger.debug(
              "Skipping census row [line={}]: no college for [ipeds_unit_id={}]",
              record.recordNumber,
              fields.ipedsUnitId,
            )
            continue
          }
          val row = fields.toRow(collegeId, sources.surveyYear)
          if (!written.add(Triple(row.collegeId, row.cipCode, row.awardLevel))) {
            count.recordSkip(SkipReason.DuplicateKeyInFile)
            logger.warn(
              "Census file repeats [college_id={}] [cip_code={}] [award_level={}] at [line={}]; keeping the first row",
              row.collegeId.value,
              row.cipCode,
              row.awardLevel,
              record.recordNumber,
            )
            continue
          }
          val result = upsertWithSavepoint(session) { CollegeIpedsDao.upsertProgramsCensus(session, row) }
          if (result.isFailure) {
            recordUpsertFailure(count, result.exceptionOrNull(), "census", "cip_code", row.cipCode, record.recordNumber)
          } else {
            count.loaded++
            when (result.getOrThrow()) {
              UpsertOutcome.INSERTED -> count.inserted++
              UpsertOutcome.CHANGED -> count.changed++
              UpsertOutcome.UNCHANGED -> count.unchanged++
            }
          }
        }
      }
      logger.info(
        "Load summary [programs-census]: [seen={}] [selected={}] [loaded={}] [skips={}]",
        count.seen,
        selected,
        count.loaded,
        count.skipsByReason,
      )
      CensusLoadResult(
        seen = count.seen,
        selected = selected,
        inserted = count.inserted,
        changed = count.changed,
        unchanged = count.unchanged,
        skipsByReason = count.skipsByReason.toMap(),
      )
    }

  // ---------------------------------------------------------------------------
  // Row mapping — PURE: no DB reads, no accumulator mutation (the S1 contract).
  // ---------------------------------------------------------------------------

  /** The HD half of one institution: everything the driving file contributes. */
  internal data class HdAttributes(
    val ipedsUnitId: Int,
    val cyActive: Boolean,
    val deathYear: Int?,
    val closedAt: LocalDate?,
    val newIpedsUnitId: Int?,
    val instLevel: Int?,
    val ugOffer: Boolean?,
    val sector: Int?,
    val carnegieBasic: Int?,
    val carnegieSize: Int?,
    val cbsa: Int?,
  ) {
    /** Left-joins the optional IC and ADM halves onto this HD row. */
    fun toRow(
      surveyYear: Int,
      ic: IcAttributes?,
      adm: AdmAttributes?,
    ): NewCollegeIpeds =
      NewCollegeIpeds(
        ipedsUnitId = ipedsUnitId,
        surveyYear = surveyYear,
        cyActive = cyActive,
        deathYear = deathYear,
        closedAt = closedAt,
        newIpedsUnitId = newIpedsUnitId,
        instLevel = instLevel,
        ugOffer = ugOffer,
        sector = sector,
        carnegieBasic = carnegieBasic,
        carnegieSize = carnegieSize,
        cbsa = cbsa,
        relAffil = ic?.relAffil,
        hasRotc = ic?.hasRotc,
        hasStudyAbroad = ic?.hasStudyAbroad,
        disabilityBand = ic?.disabilityBand,
        disabilityPct = ic?.disabilityPct,
        hasHousing = ic?.hasHousing,
        housingCapacity = ic?.housingCapacity,
        applicationFee = ic?.applicationFee,
        athleticAssoc = ic?.athleticAssoc ?: emptyList(),
        footballConf = ic?.footballConf,
        // No ADM row at all is the common case (749 of the 2,488 universe
        // institutions): test_policy is simply unknown, never a fabricated code.
        testPolicy = adm?.testPolicy,
      )
  }

  /**
   * One side file's row, keyed by `ipeds_unit_id` for the in-memory left join onto
   * HD. The key is a type-checked property rather than a passed-in selector, so
   * a side file without one cannot compile.
   */
  internal sealed interface UnitKeyed {
    val ipedsUnitId: Int
  }

  /** The IC half of one institution, or absent when IC has no row for it. */
  internal data class IcAttributes(
    override val ipedsUnitId: Int,
    val relAffil: Int?,
    val hasRotc: Boolean?,
    val hasStudyAbroad: Boolean?,
    val disabilityBand: Int?,
    val disabilityPct: Double?,
    val hasHousing: Boolean?,
    val housingCapacity: Int?,
    val applicationFee: Int?,
    val athleticAssoc: List<Int>,
    val footballConf: Int?,
  ) : UnitKeyed

  /** The ADM half of one institution, or absent when ADM has no row for it. */
  internal data class AdmAttributes(
    override val ipedsUnitId: Int,
    val testPolicy: Int?,
  ) : UnitKeyed

  /** The validated key/required columns of one completions row. */
  internal data class CompletionFields(
    val ipedsUnitId: Int,
    val cipCode: String,
    val awardLevel: Int,
    val awardsTotal: Int,
  ) {
    fun toRow(
      collegeId: CollegeId,
      surveyYear: Int,
    ): NewCollegeProgramsCensus =
      NewCollegeProgramsCensus(
        collegeId = collegeId,
        cipCode = cipCode,
        awardLevel = awardLevel,
        awardsTotal = awardsTotal,
        surveyYear = surveyYear,
      )
  }

  internal fun mapHd(record: CSVRecord): MapResult<HdAttributes> {
    // The tally is built HERE and returned in the [MapResult] — one output
    // channel, the [mapInstitution] shape. A caller-owned accumulator passed in
    // would make this mapper an accumulator too, and the section contract above
    // false at its first function.
    val coercions = mutableMapOf<String, Int>()
    val ipedsUnitId = intOrNull(record, COL_UNITID)
    // CYACTIVE takes only 1 and 3 in the real file and has no sentinel, and the
    // column is NOT NULL — so an unmappable value is a skipped row with a named
    // missing field, never a fabricated "active".
    val cyActive =
      when (intOrNull(record, COL_CYACTIVE)) {
        CYACTIVE_YES -> true
        CYACTIVE_NO -> false
        else -> null
      }
    if (ipedsUnitId == null || ipedsUnitId < IPEDS_UNIT_ID_MIN || cyActive == null) {
      val missing =
        buildList {
          if (ipedsUnitId == null || ipedsUnitId < IPEDS_UNIT_ID_MIN) add("ipeds_unit_id")
          if (cyActive == null) add("cy_active")
        }
      logger.debug(
        "Skipping IPEDS HD row [line={}]: missing required field {} [ipeds_unit_id={}]",
        record.recordNumber,
        missing,
        ipedsUnitId,
      )
      return MapResult.Skipped(SkipReason.MissingRequiredField(missing))
    }
    return MapResult.Mapped(
      HdAttributes(
        ipedsUnitId = ipedsUnitId,
        cyActive = cyActive,
        // DEATHYR's -2 means "still alive", not a missing year.
        deathYear = codeInDomainOrNull(record, COL_DEATHYR, YEAR_MIN, YEAR_MAX, "death_year", coercions),
        closedAt = closedAt(record, coercions),
        newIpedsUnitId = codeInDomainOrNull(record, COL_NEWID, IPEDS_UNIT_ID_MIN, Int.MAX_VALUE, "new_ipeds_unit_id", coercions),
        instLevel = codeInDomainOrNull(record, COL_ICLEVEL, INST_LEVEL_MIN, INST_LEVEL_MAX, "inst_level", coercions),
        ugOffer = boolFromCode(record, COL_UGOFFER, "ug_offer", coercions),
        sector = codeInSetOrNull(record, COL_SECTOR, SECTOR_CODES, "sector", coercions),
        // -2 is PRESERVED on the three raw code columns below: it means "not in
        // the Carnegie universe" / "not in a CBSA", which is an exclusion the
        // source asserts, not a gap it admits to.
        carnegieBasic = rawCode(record, COL_C21BASIC),
        carnegieSize = rawCode(record, COL_C21SZSET),
        cbsa = rawCode(record, COL_CBSA),
      ),
      coercions,
    )
  }

  internal fun mapIc(record: CSVRecord): MapResult<IcAttributes> {
    val ipedsUnitId = intOrNull(record, COL_UNITID) ?: return MapResult.Skipped(mapMissingIpedsUnitId(record, "IC"))
    // Tallied and RETURNED, like every other mapper's: the caller folds them
    // into the phase accumulator at file granularity, so an IC coercion is
    // never attributed to whichever HD row happened to follow it.
    val coercions = mutableMapOf<String, Int>()
    return MapResult.Mapped(
      IcAttributes(
        ipedsUnitId = ipedsUnitId,
        // -2 = explicitly NOT religiously affiliated (5,164 institutions). Kept.
        relAffil = rawCode(record, COL_RELAFFIL),
        hasRotc = boolFromCheckbox(record, COL_SLO5, "has_rotc", coercions),
        hasStudyAbroad = boolFromCheckbox(record, COL_SLO6, "has_study_abroad", coercions),
        // DISAB is a BAND indicator for the share of undergraduates registered
        // as having a disability (1 = <=3%, 2 = >3%), not "offers disability
        // services" (D24). Both -1 and -2 are unknown here.
        disabilityBand =
          codeInDomainOrNull(record, COL_DISAB, DISABILITY_BAND_MIN, DISABILITY_BAND_MAX, "disability_band", coercions),
        // '.' fails toDoubleOrNull and is therefore already NULL; the domain
        // bound is the CHECK's mirror.
        disabilityPct = doubleInDomainOrNull(record, COL_DISABPCT, PCT_MIN, PCT_MAX, "disability_pct", coercions),
        hasHousing = boolFromRoom(record, "has_housing", coercions),
        housingCapacity = intInDomainOrNull(record, COL_ROOMCAP, 0, Int.MAX_VALUE, "housing_capacity", coercions),
        // APPLFEEU = 0 is a REAL free application, distinct from '.' (not
        // reported). intInDomainOrNull keeps the 0 and nulls the dot.
        applicationFee = intInDomainOrNull(record, COL_APPLFEEU, 0, Int.MAX_VALUE, "application_fee", coercions),
        athleticAssoc = athleticAssoc(record),
        footballConf = rawCode(record, COL_CONFNO1),
      ),
      coercions,
    )
  }

  internal fun mapAdm(record: CSVRecord): MapResult<AdmAttributes> {
    val ipedsUnitId = intOrNull(record, COL_UNITID) ?: return MapResult.Skipped(mapMissingIpedsUnitId(record, "ADM"))
    val coercions = mutableMapOf<String, Int>()
    val raw = intOrNull(record, COL_ADMCON7)
    val testPolicy =
      when {
        raw == null -> {
          null
        }

        raw in TEST_POLICY_CODES -> {
          raw
        }

        else -> {
          logCoercion("test_policy", record.recordNumber, raw, TEST_POLICY_CODES, coercions)
          null
        }
      }
    return MapResult.Mapped(AdmAttributes(ipedsUnitId = ipedsUnitId, testPolicy = testPolicy), coercions)
  }

  /**
   * What the documented bachelor's-first-major filter decided about one C_A
   * row. Three outcomes, not two: an unreadable `AWLEVEL`/`MAJORNUM` cell is
   * NOT an exclusion — the row should have been judged and could not be — so it
   * is [Skipped] and counted, while a genuine non-bachelor, second-major or
   * grand-total row is [Excluded] and is a deliberate loss the census reports as
   * `seen - selected`.
   */
  internal sealed interface CensusSelection {
    data object Selected : CensusSelection

    data object Excluded : CensusSelection

    data class Skipped(
      val reason: SkipReason,
    ) : CensusSelection
  }

  /** The documented bachelor's-first-major filter, tri-state; see [CensusSelection]. */
  internal fun selectBachelorsFirstMajor(record: CSVRecord): CensusSelection {
    val awardLevel = intOrNull(record, COL_AWLEVEL)
    val majorNum = intOrNull(record, COL_MAJORNUM)
    if (awardLevel == null || majorNum == null) {
      val missing =
        buildList {
          if (awardLevel == null) add("award_level")
          if (majorNum == null) add("major_num")
        }
      logger.debug(
        "Skipping census row [line={}]: unreadable filter field {}",
        record.recordNumber,
        missing,
      )
      return CensusSelection.Skipped(SkipReason.MissingRequiredField(missing))
    }
    if (awardLevel != AWLEVEL_BACHELORS || majorNum != MAJORNUM_FIRST) return CensusSelection.Excluded
    if (stringOrNull(record, COL_CIPCODE) == CIPCODE_GRAND_TOTAL) return CensusSelection.Excluded
    return CensusSelection.Selected
  }

  internal fun mapCompletion(record: CSVRecord): MapResult<CompletionFields> {
    val ipedsUnitId = intOrNull(record, COL_UNITID)
    val rawCip = stringOrNull(record, COL_CIPCODE)
    val awardLevel = intOrNull(record, COL_AWLEVEL)
    val awardsTotal = intOrNull(record, COL_CTOTALT)
    if (ipedsUnitId == null || rawCip == null || awardLevel == null || awardsTotal == null) {
      val missing =
        buildList {
          if (ipedsUnitId == null) add("ipeds_unit_id")
          if (rawCip == null) add("cip_code")
          if (awardLevel == null) add("award_level")
          if (awardsTotal == null) add("awards_total")
        }
      logger.debug(
        "Skipping census row [line={}]: missing required field {} [ipeds_unit_id={}]",
        record.recordNumber,
        missing,
        ipedsUnitId,
      )
      return MapResult.Skipped(SkipReason.MissingRequiredField(missing))
    }
    // C_A.CIPCODE is dotted and quoted ("11.0701"); the schema stores six
    // digits. Anything that is not six digits once the dot is gone is dropped
    // under its own bucket -- cip_code is NOT NULL and part of the key, so it
    // cannot be nulled instead.
    val cipCode = rawCip.replace(".", "")
    if (!CIP_CODE_PATTERN.matches(cipCode)) {
      logger.debug(
        "Skipping census row [line={}]: CIPCODE [{}] is not a 6-digit code [ipeds_unit_id={}]",
        record.recordNumber,
        rawCip,
        ipedsUnitId,
      )
      return MapResult.Skipped(SkipReason.CipCodeMalformed)
    }
    return MapResult.Mapped(
      CompletionFields(ipedsUnitId = ipedsUnitId, cipCode = cipCode, awardLevel = awardLevel, awardsTotal = awardsTotal),
      emptyMap(),
    )
  }

  // ---------------------------------------------------------------------------
  // Sentinel helpers — the one place the -1/-2/-3/'.' rules are implemented
  // ---------------------------------------------------------------------------

  /** A raw IPEDS code with only the genuine unknowns nulled: `-2` is preserved. */
  private fun rawCode(
    record: CSVRecord,
    column: String,
  ): Int? = intOrNull(record, column)?.takeIf { it !in UNKNOWN_SENTINELS }

  /**
   * A raw code constrained to `[min, max]`: the unknown sentinels and `-2` are
   * NULL, an in-domain value is kept, and anything else present is coerced to
   * NULL and tallied by [columnName] (never silently dropped).
   */
  private fun codeInDomainOrNull(
    record: CSVRecord,
    column: String,
    min: Int,
    max: Int,
    columnName: String,
    coercions: MutableMap<String, Int>,
  ): Int? {
    val value = intOrNull(record, column) ?: return null
    if (value in UNKNOWN_SENTINELS || value == NOT_APPLICABLE) return null
    if (value < min || value > max) {
      logCoercion(columnName, record.recordNumber, value, min, max, coercions)
      return null
    }
    return value
  }

  /**
   * A raw code constrained to an explicit code SET: the unknown sentinels and
   * `-2` are NULL, a PUBLISHED code is kept, and anything else present is
   * coerced to NULL and tallied by [columnName]. The set sibling of
   * [codeInDomainOrNull], for a column whose published codes are not a
   * contiguous range — accepting the gaps would store junk indistinguishable
   * from a real code.
   */
  private fun codeInSetOrNull(
    record: CSVRecord,
    column: String,
    codes: Set<Int>,
    columnName: String,
    coercions: MutableMap<String, Int>,
  ): Int? {
    val value = intOrNull(record, column) ?: return null
    if (value in UNKNOWN_SENTINELS || value == NOT_APPLICABLE) return null
    if (value !in codes) {
      logCoercion(columnName, record.recordNumber, value, codes, coercions)
      return null
    }
    return value
  }

  /** `HD.UGOFFER`: `1` yes, `2` no, `-3` unknown. */
  private fun boolFromCode(
    record: CSVRecord,
    column: String,
    columnName: String,
    coercions: MutableMap<String, Int>,
  ): Boolean? =
    when (val value = intOrNull(record, column)) {
      null -> {
        null
      }

      YES -> {
        true
      }

      NO_TWO -> {
        false
      }

      in UNKNOWN_SENTINELS -> {
        null
      }

      else -> {
        logCoercion(columnName, record.recordNumber, value, YES, NO_TWO, coercions)
        null
      }
    }

  /**
   * An `IC` checkbox (`SLO5`, `SLO6`): `1` yes; `0` is an *implied* no (the box
   * was left unticked) and `-2` is "not applicable", both of which are real
   * NOs; only `-1` is genuinely unknown.
   */
  private fun boolFromCheckbox(
    record: CSVRecord,
    column: String,
    columnName: String,
    coercions: MutableMap<String, Int>,
  ): Boolean? =
    when (val value = intOrNull(record, column)) {
      null -> {
        null
      }

      YES -> {
        true
      }

      CHECKBOX_IMPLIED_NO, NOT_APPLICABLE -> {
        false
      }

      in UNKNOWN_SENTINELS -> {
        null
      }

      else -> {
        logCoercion(columnName, record.recordNumber, value, CHECKBOX_IMPLIED_NO, YES, coercions)
        null
      }
    }

  /** `IC.ROOM`: `1` yes, `2` no (not `0`), `-1`/`-2` unknown. */
  private fun boolFromRoom(
    record: CSVRecord,
    columnName: String,
    coercions: MutableMap<String, Int>,
  ): Boolean? =
    when (val value = intOrNull(record, COL_ROOM)) {
      null -> {
        null
      }

      YES -> {
        true
      }

      NO_TWO -> {
        false
      }

      NOT_APPLICABLE, in UNKNOWN_SENTINELS -> {
        null
      }

      else -> {
        logCoercion(columnName, record.recordNumber, value, YES, NO_TWO, coercions)
        null
      }
    }

  /**
   * `HD.CLOSEDAT` is a 10-char SPACE-PADDED alpha field whose "not applicable"
   * cell is the literal `'-2        '`; [stringOrNull] trims it, so what is
   * compared here is the text sentinel [CLOSED_AT_NOT_APPLICABLE] — this column
   * is alpha, and its sentinel is its own, not a stringified integer code. Two
   * rows also carry `00/00/0000`, which is not a date and would crash a strict
   * parse — both become NULL, the latter tallied as a coercion rather than
   * swallowed.
   */
  private fun closedAt(
    record: CSVRecord,
    coercions: MutableMap<String, Int>,
  ): LocalDate? {
    val value = stringOrNull(record, COL_CLOSEDAT) ?: return null
    if (value == CLOSED_AT_NOT_APPLICABLE) return null
    return try {
      LocalDate.parse(value, CLOSED_AT_FORMAT)
    } catch (e: DateTimeParseException) {
      // The throwable itself, not e.message: the type, cause chain and
      // parse index are what a drill-down needs.
      logger.debug(
        "Coercing unparseable CLOSEDAT to null [line={}] [value={}]",
        record.recordNumber,
        value,
        e,
      )
      coercions.merge("closed_at", 1, Int::plus)
      null
    }
  }

  /**
   * The `IC.ASSOC1..6` ordinals whose flag is `1` (1 NCAA, 2 NAIA, 3 NJCAA,
   * 4 NSCAA, 5 NCCAA, 6 other). Known tri-state gap, stated rather than hidden:
   * the column is NOT NULL, so the 6 institutions whose flags are all `-1`
   * (unreported) produce an empty list, indistinguishable from "belongs to
   * nothing".
   */
  private fun athleticAssoc(record: CSVRecord): List<Int> =
    ATHLETIC_ASSOC_ORDINALS.filter { intOrNull(record, "$COL_ASSOC_PREFIX$it") == YES }

  private fun mapMissingIpedsUnitId(
    record: CSVRecord,
    file: String,
  ): SkipReason {
    logger.debug("Skipping IPEDS [{}] row [line={}]: missing UNITID", file, record.recordNumber)
    return SkipReason.MissingRequiredField(listOf("ipeds_unit_id"))
  }

  /**
   * Reads a whole side file into a `ipeds_unit_id`-keyed index for the in-memory left
   * join. A repeated `ipeds_unit_id` keeps the FIRST row and is counted like any other
   * dropped row: silent last-wins would let file order decide an institution's
   * attributes, and a WARN line nobody tallies would leave the run reporting
   * `0 skipped` while half an institution was thrown away.
   */
  private fun <T : UnitKeyed> readKeyedRows(
    file: File,
    label: String,
    count: LoadCount,
    map: (CSVRecord) -> MapResult<T>,
  ): Map<Int, T> {
    val rows = mutableMapOf<Int, T>()
    parseCsv(file).use { records ->
      for (record in records) {
        if (!CsvIngestSupport.isWellFormed(record)) {
          count.recordSkip(SkipReason.RowArityMismatch)
          logger.debug(
            "Skipping IPEDS [{}] row [line={}]: [{}] cells, header has [{}]",
            label,
            record.recordNumber,
            record.size(),
            records.headerMap.size,
          )
          continue
        }
        when (val mapped = map(record)) {
          // A side-file row that cannot be mapped silently removes one
          // institution's IC/ADM half, so it is a counted loss like any other.
          is MapResult.Skipped -> {
            count.recordSkip(mapped.reason)
          }

          is MapResult.Mapped -> {
            if (rows.putIfAbsent(mapped.value.ipedsUnitId, mapped.value) != null) {
              // The discarded twin: counted like any other dropped row, and its
              // coercions belong to the row that lost, not to the table.
              count.recordSkip(SkipReason.DuplicateKeyInFile)
              logger.warn("IPEDS [{}] file repeats [ipeds_unit_id={}]; keeping the first row", label, mapped.value.ipedsUnitId)
            } else {
              count.recordCoercions(mapped.coercions)
            }
          }
        }
      }
    }
    return rows
  }

  companion object {
    // -------------------------------------------------------------------------
    // Sentinel codes — the vocabulary the mappers above are written in.
    // -------------------------------------------------------------------------

    /** `-1` (not reported) and `-3` (not available): the only genuine UNKNOWNs. */
    private val UNKNOWN_SENTINELS = setOf(-1, -3)

    /** `-2` "not applicable": a real "no"/"none", NOT an unknown. */
    private const val NOT_APPLICABLE = -2

    private const val YES = 1
    private const val NO_TWO = 2
    private const val CHECKBOX_IMPLIED_NO = 0
    private const val CYACTIVE_YES = 1
    private const val CYACTIVE_NO = 3

    /** `ADM.ADMCON7`: 1 required, 3 blind, 5 optional — the table's CHECK domain. */
    private val TEST_POLICY_CODES = setOf(1, 3, 5)

    private val ATHLETIC_ASSOC_ORDINALS = 1..6

    private const val AWLEVEL_BACHELORS = 5
    private const val MAJORNUM_FIRST = 1
    private const val CIPCODE_GRAND_TOTAL = "99"
    private val CIP_CODE_PATTERN = Regex("^[0-9]{6}$")

    /**
     * `HD.CLOSEDAT` real dates are US-ordered `MM/DD/YYYY`. The locale is PINNED
     * rather than inherited from the ingest host: a default locale with a
     * non-ASCII numbering system would otherwise turn every CLOSEDAT into a
     * coerced NULL with no code change.
     */
    private val CLOSED_AT_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd/uuuu", Locale.ROOT)

    /** `HD.CLOSEDAT`'s "not applicable" cell, as the trimmed text this ALPHA column carries. */
    private const val CLOSED_AT_NOT_APPLICABLE = "-2"

    // Domains mirrored from the 0055 CHECKs (defense in depth: the CHECK is the
    // backstop, these keep a junk cell from costing a whole row a savepoint).

    /**
     * The `survey_year`/`death_year` domain, mirroring the 0055 range CHECKs —
     * and the bound `IngestApplication`'s `--survey-year` refusal reads, so the
     * argv grammar and the loader can never disagree about what a plausible
     * year is.
     */
    internal val YEAR_RANGE = 1980..2100
    private val YEAR_MIN = YEAR_RANGE.first
    private val YEAR_MAX = YEAR_RANGE.last

    /** `college_ipeds_ipeds_unit_id_positive_check` / `..._new_ipeds_unit_id_positive_check`: an IPEDS id is 1-based. */
    private const val IPEDS_UNIT_ID_MIN = 1
    private const val INST_LEVEL_MIN = 1
    private const val INST_LEVEL_MAX = 3

    /**
     * `HD.SECTOR`: the nine sector codes plus `0` (administrative unit) and the
     * `99` "sector unknown (not active)" code. An explicit SET, not a range:
     * `10..98` are values IPEDS does not publish, so accepting them would store
     * junk indistinguishable from a real sector — and the 0055 CHECK is narrowed
     * to the same set.
     */
    private val SECTOR_CODES: Set<Int> = (0..9).toSet() + 99
    private const val DISABILITY_BAND_MIN = 1
    private const val DISABILITY_BAND_MAX = 2
    private const val PCT_MIN = 0.0
    private const val PCT_MAX = 100.0

    // -------------------------------------------------------------------------
    // CSV column names — the single source of truth (RFC 139/144): the mappers
    // read through these constants and the REQUIRED_* assertion lists are BUILT
    // from them, so adding a read means adding one constant here.
    // -------------------------------------------------------------------------
    private const val COL_UNITID = "UNITID"

    // HD
    private const val COL_CYACTIVE = "CYACTIVE"
    private const val COL_DEATHYR = "DEATHYR"
    private const val COL_CLOSEDAT = "CLOSEDAT"
    private const val COL_NEWID = "NEWID"
    private const val COL_ICLEVEL = "ICLEVEL"
    private const val COL_UGOFFER = "UGOFFER"
    private const val COL_SECTOR = "SECTOR"
    private const val COL_C21BASIC = "C21BASIC"
    private const val COL_C21SZSET = "C21SZSET"
    private const val COL_CBSA = "CBSA"

    // IC
    private const val COL_RELAFFIL = "RELAFFIL"
    private const val COL_SLO5 = "SLO5"
    private const val COL_SLO6 = "SLO6"
    private const val COL_DISAB = "DISAB"
    private const val COL_DISABPCT = "DISABPCT"
    private const val COL_ROOM = "ROOM"
    private const val COL_ROOMCAP = "ROOMCAP"
    private const val COL_APPLFEEU = "APPLFEEU"
    private const val COL_ASSOC_PREFIX = "ASSOC"
    private const val COL_CONFNO1 = "CONFNO1"

    // ADM
    private const val COL_ADMCON7 = "ADMCON7"

    // C_A
    private const val COL_CIPCODE = "CIPCODE"
    private const val COL_MAJORNUM = "MAJORNUM"
    private const val COL_AWLEVEL = "AWLEVEL"
    private const val COL_CTOTALT = "CTOTALT"

    /** Every HD column [mapHd] reads — required to EXIST in the header (cells may still be sentinels). */
    internal val REQUIRED_HD_COLUMNS =
      listOf(
        COL_UNITID,
        COL_CYACTIVE,
        COL_DEATHYR,
        COL_CLOSEDAT,
        COL_NEWID,
        COL_ICLEVEL,
        COL_UGOFFER,
        COL_SECTOR,
        COL_C21BASIC,
        COL_C21SZSET,
        COL_CBSA,
      )

    /** Every IC column [mapIc] reads; derived like [REQUIRED_HD_COLUMNS]. */
    internal val REQUIRED_IC_COLUMNS =
      listOf(
        COL_UNITID,
        COL_RELAFFIL,
        COL_SLO5,
        COL_SLO6,
        COL_DISAB,
        COL_DISABPCT,
        COL_ROOM,
        COL_ROOMCAP,
        COL_APPLFEEU,
        COL_CONFNO1,
      ) + ATHLETIC_ASSOC_ORDINALS.map { "$COL_ASSOC_PREFIX$it" }

    /** Every ADM column [mapAdm] reads. */
    internal val REQUIRED_ADM_COLUMNS = listOf(COL_UNITID, COL_ADMCON7)

    /** Every completions column [mapCompletion] and the bachelor's filter read. */
    internal val REQUIRED_COMPLETIONS_COLUMNS =
      listOf(COL_UNITID, COL_CIPCODE, COL_MAJORNUM, COL_AWLEVEL, COL_CTOTALT)

    /**
     * The nullable `college_ipeds` columns whose non-null counts the change
     * summary tracks (RFC 144) — every attribute this loader can leave unknown.
     * NOT a second list: it IS the DAO's countable-column allowlist, so the
     * summary can never ask for a column [CollegeIpedsDao.nonNullCounts] would
     * reject, and adding a nullable column is one edit. (`cy_active` and
     * `athletic_assoc` are absent from that allowlist because they are NOT
     * NULL, so their count is the row count and measures nothing.)
     */
    internal val NON_NULL_SUMMARY_COLUMNS: List<String> = CollegeIpedsDao.NON_NULL_COUNTABLE_COLUMNS.toList()
  }
}
