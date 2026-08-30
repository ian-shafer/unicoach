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
 * per-column non-null before/after counts plus version bumps. [indexRows] is
 * the row count of the derived `college_name_words` rebuild (RFC 146); it was
 * NULL for every RFC 139-era build row, before that table existed.
 */
data class NewCollegeIndexBuild(
  val startedAt: Instant,
  val finishedAt: Instant,
  val sources: JsonArray,
  val rowsIngested: JsonObject,
  val indexRows: Int?,
  val changeSummary: JsonObject,
  val methodVersion: Int,
)
