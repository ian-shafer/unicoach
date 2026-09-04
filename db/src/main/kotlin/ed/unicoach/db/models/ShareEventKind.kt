package ed.unicoach.db.models

/**
 * What one `share_events` row records about the Family Cost Report share
 * surface (RFC 160). Persisted as the lowercase [value] string matching the
 * `share_events_kind_check` CHECK.
 *
 * Every kind declares [namesShareRow] at its definition site — whether it names
 * a concrete `cost_report_shares` row — so a future kind must state its shape
 * the day it lands, and the DAO/tests read the declaration instead of
 * computing a complement. [OPTED_OUT] is the one kind with no share row — the
 * student's durable "never suggest sharing again", recorded from chat — and
 * the DB CHECK (`share_events_share_id_check`) holds the pairing both ways.
 */
enum class ShareEventKind(
  val value: String,
  /** Whether this kind names a concrete `cost_report_shares` row (all but the shareless opt-out). */
  val namesShareRow: Boolean,
) {
  /** A first live link was minted (or the first after a student-driven revoke); names the new row. */
  MINTED("minted", namesShareRow = true),

  /** The student asked again and got the same live link back; names the existing live row. */
  REPEAT("repeat", namesShareRow = true),

  /** The secret rotated: the stale row died inside the reissue and a new row was minted; names the new row. */
  REISSUED("reissued", namesShareRow = true),

  /** The student's live share was revoked; names the revoked row. */
  REVOKED("revoked", namesShareRow = true),

  /** "Never suggest sharing again" — permanent share-nudge suppression; no share row by nature. */
  OPTED_OUT("opted_out", namesShareRow = false),
  ;

  companion object {
    fun fromValue(value: String): ShareEventKind? = entries.find { it.value == value }
  }
}
