package ed.unicoach.db.models

import java.time.Instant

/**
 * An institution-level row from the `colleges` reference table (RFC 67): a
 * curated subset of College Scorecard data. `ipedsUnitId` is the federal natural key
 * (UNITID); `id` is the project-convention DB-generated surface UUID. Mutable
 * only via re-ingestion upsert, so it carries logical `createdAt`/`updatedAt`.
 * The row is versioned via a trigger-managed `version` and a `colleges_versions`
 * history table (the upsert bumps `version` only on a real content change), with
 * no soft-delete.
 *
 * Identity, location, codes and the non-money Scorecard measures only (RFC
 * 176): migration `0094` dropped the eighteen publisher-shaped money columns,
 * and money is read from `price_figures` / `cohort_money_stats` instead.
 */
data class College(
  override val id: CollegeId,
  override val version: Int,
  val ipedsUnitId: Int,
  val opeid: String?,
  val name: String,
  val city: String,
  val state: String,
  val region: Int?,
  val locale: Int?,
  val latitude: Double?,
  val longitude: Double?,
  val control: Int,
  val undergradEnrollmentHeadcount: Int?,
  val admissionRateShare: Double?,
  val satAverageEquivalentScore: Int?,
  val completionRate150pct4yrShare: Double?,
  val website: String?,
  // Curated nicknames ("Mizzou", "UMass Amherst") from db/data/college-aliases.json
  // (RFC 139): repo data, not Scorecard data, applied by ingest after the
  // Scorecard upsert phase. Feeds the search text (name + aliases) both the
  // one-keystroke word table and the substring arm range over (RFC 146); empty
  // when uncurated.
  val aliases: List<String>,
  override val createdAt: Instant,
  override val updatedAt: Instant,
) : Identifiable<CollegeId>,
  Created,
  Updated,
  Versioned
