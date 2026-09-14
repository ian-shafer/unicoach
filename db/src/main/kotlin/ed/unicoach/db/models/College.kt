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
  // The published 25th/75th percentile admission score bands (RFC 195),
  // Scorecard SATVR25/75, SATMT25/75 and ACTCM25/75 for the autumn 2024
  // entering cohort. FLAT, like every other Scorecard measure on this row: a
  // band becomes a TYPE one level up, at the service boundary
  // (`SchoolScoreBands`), where "a band is a pair" is expressed once. Each
  // pair is present or absent together -- the `colleges` CHECKs make half a
  // band unrepresentable -- and a percentile is a fact about last year's
  // admitted class, never a cut-off and never a target.
  val satReading25thPercentileScore: Int?,
  val satReading75thPercentileScore: Int?,
  val satMath25thPercentileScore: Int?,
  val satMath75thPercentileScore: Int?,
  val actComposite25thPercentileScore: Int?,
  val actComposite75thPercentileScore: Int?,
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

/**
 * The PUBLISHED scales a stored admission-test score may hold (RFC 195) -- one
 * home, beside the row that stores them.
 *
 * These are the test publishers' own scales, never a plausibility judgement:
 * an SAT section runs 200-800, an ACT composite 1-36, and an SAT composite
 * (`SAT_AVG`, [College.satAverageEquivalentScore]) 400-1600 because it is the
 * two sections summed. Two colleges publish an ACT band of 5-7 and 1-5 and
 * both are stored and said as published (RFC 195 D-D): deciding which
 * published numbers are believable is nobody's job here.
 *
 * They live in `:db` because every module that has an opinion about them
 * already depends on it -- the ingest that reads the cells, and the service
 * that decides whether a stored figure may be spoken. Written three times they
 * drifted by construction: the loader's bounds, the section type's per-test
 * scale and the migration test's boundary probes were three literals nothing
 * compared.
 *
 * THE SQL CHECK REMAINS THE AUTHORITY. `db/schema/add-college-score-bands.sql`
 * states the same numbers as `BETWEEN` clauses and is what actually refuses a
 * write; this object is the Kotlin MIRROR of it, with the failure direction a
 * mirror always has -- widen the CHECK without widening this and a newly legal
 * published score is refused in Kotlin and silently absent. Move both
 * together.
 */
object PublishedScoreScale {
  /** `SATVR*`/`SATMT*`: one SAT section. */
  val SAT_SECTION: IntRange = 200..800

  /** `ACTCM*`: the ACT composite, whose scale starts at 1 and not at 0. */
  val ACT_COMPOSITE: IntRange = 1..36

  /** `SAT_AVG`: an SAT-equivalent COMPOSITE, the two sections summed. */
  val SAT_COMPOSITE: IntRange = 400..1600
}
