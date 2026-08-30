package ed.unicoach.db.models

import java.time.LocalDate

/**
 * Input for upserting one `college_ipeds` row (RFC 144) on its natural key
 * `ipedsUnitId`. Carries no `id` (DB-generated) and no timestamps (DB-managed).
 *
 * Every field is a RAW IPEDS code or a value derived from one by the documented
 * sentinel rule (brief 0004): `null` means UNKNOWN — the `-1`/`-3` sentinels,
 * IC's `'.'`, ADM's empty string, or an absent IC/ADM row. The `-2` "not
 * applicable" sentinel is NOT unknown: it is preserved verbatim in the code
 * columns ([relAffil], [carnegieBasic], [carnegieSize], [cbsa], [footballConf])
 * where it means "explicitly none", and mapped to `false` in the boolean ones.
 *
 * [athleticAssoc] is the list of `IC.ASSOC1..6` ordinals set to `1`
 * (1 NCAA, 2 NAIA, 3 NJCAA, 4 NSCAA, 5 NCCAA, 6 other). It is not nullable, so
 * an all-`-1` (unreported) institution is indistinguishable from one that
 * belongs to nothing — a known, documented gap affecting 6 rows in 2023.
 */
data class NewCollegeIpeds(
  val ipedsUnitId: Int,
  val surveyYear: Int,
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
  val relAffil: Int?,
  val hasRotc: Boolean?,
  val hasStudyAbroad: Boolean?,
  val disabilityBand: Int?,
  /** Raw IC `DISABPCT`, a 0–100 PERCENT — not a 0–1 fraction like `College.pctPell`. */
  val disabilityPct: Double?,
  val hasHousing: Boolean?,
  val housingCapacity: Int?,
  val applicationFee: Int?,
  val athleticAssoc: List<Int>,
  val footballConf: Int?,
  val testPolicy: Int?,
)
