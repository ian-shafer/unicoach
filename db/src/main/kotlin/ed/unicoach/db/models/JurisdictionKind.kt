package ed.unicoach.db.models

/**
 * What kind of jurisdiction a `us_states` row is (RFC 147): a state, the federal
 * district, a territory, or a freely-associated state.
 *
 * It is the ONE authored value in the whole published codebook — IPEDS ships the
 * `OBEREG` membership and the postal codes, not this classification — so it is
 * an OWN enumeration by the CLAUDE.md rule: `TEXT` + `CHECK IN (...)` in
 * `db/schema/0060`, and exactly one Kotlin enum with a [fromValue] companion
 * here, beside [CollegeListEntryStatus] and [IncomeBand]. A published code would
 * be stored raw instead; this is not one.
 *
 * `bin/fetch-codebooks` authors it and fatal-checks the mapping in both
 * directions (DC federal-district; AS/GU/MP/PR/VI territory; FM/MH/PW
 * freely-associated-state; exactly 50 states), so an unknown value reaching here
 * means the generator and this enum have diverged — which [fromValue] returning
 * null makes the loader say, rather than a CHECK violation deep in a write.
 */
enum class JurisdictionKind(
  val value: String,
) {
  STATE("state"),
  FEDERAL_DISTRICT("federal-district"),
  TERRITORY("territory"),
  FREELY_ASSOCIATED_STATE("freely-associated-state"),
  ;

  companion object {
    /** The kind this [value] names, or null when nothing does. */
    fun fromValue(value: String): JurisdictionKind? = entries.find { it.value == value }

    /** Every value, for an error that has to list the vocabulary. */
    val VALUES: List<String> = entries.map { it.value }
  }
}
