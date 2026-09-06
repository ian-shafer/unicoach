package ed.unicoach.db.models

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/**
 * Input for one `college_index_build` provenance row (RFC 139), inserted once
 * at the very end of a successful ingest run. The three payloads are structured
 * JSON, not pre-serialized text — the [NewLlmRequest] convention: the DAO
 * serializes them at the JDBC edge (`?::jsonb`), so the structure survives to
 * the boundary and stays inspectable and testable. [sources] is
 * `[{file, sha256, bytes, source_arg}]` per source file, [rowsIngested] the
 * per-table inserted/changed/unchanged/skipped counts, and [changeSummary] the
 * per-column non-null before/after counts plus version bumps.
 *
 * The two derived-rebuild counts are named for the tables they describe (RFC
 * 150 D48). [nameWordsRows] is the `college_name_words` rebuild (RFC 146) — the
 * column was called `index_rows` until 0064, a name RFC 146 inherited from an
 * era when only one derived table was planned, and it is NULL for every RFC
 * 139-era row written before that table existed. [searchIndexRows] is the
 * `college_search_index` rebuild (RFC 150), NULL for every row written before
 * THAT table existed. One column, one meaning.
 */
data class NewCollegeIndexBuild(
  val startedAt: Instant,
  val finishedAt: Instant,
  val sources: JsonArray,
  val rowsIngested: JsonObject,
  val nameWordsRows: Int?,
  val searchIndexRows: Int?,
  /** The `price_figures` rebuild count (RFC 158, P11); NULL for every earlier-era row. */
  val priceFigureRows: Int?,
  /** The `cohort_money_stats` rebuild count (RFC 158, P11); NULL for every earlier-era row. */
  val cohortMoneyStatRows: Int?,
  /** The `cohort_population_counts` rebuild count (RFC 162); NULL for every earlier-era row. */
  val cohortPopulationCountRows: Int?,
  /**
   * The `aid_form_requirements` rebuild count (RFC 170); NULL for every
   * earlier-era row, and NULL for a run that supplied no CDS seed -- the `cds`
   * phase is what writes those rows, so a run without it did not rebuild them
   * and must not report 0 as if it had.
   */
  val aidFormRequirementRows: Int?,
  /**
   * The per-status row counts per canonical table (RFC 158, P11), keyed by OUR
   * vocabulary slugs -- the operator-visible fact that suppression survived
   * the fill. NULL for every earlier-era row.
   */
  val canonicalMoneySummary: JsonObject?,
  val changeSummary: JsonObject,
  val methodVersion: Int,
)
