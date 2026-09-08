package ed.unicoach.db.models

/**
 * The closed vocabulary a residency state may take: the College Scorecard
 * `STABBR` domain (the source of our college data, RFC 133) — the 50 states, DC,
 * and the USPS territory / freely-associated-state codes.
 *
 * MEMBERSHIP is the boundary, not a two-letter shape. `^[A-Z]{2}$` admits 676
 * strings and 59 of them are legal, so a shape check is a check that mostly
 * passes things it should refuse. The schema's own `^[A-Z]{2}$` CHECK stays as
 * the coarser DB-level backstop.
 *
 * It lives in `:db` rather than beside the service that first owned it, on the
 * [LivingArrangement] and [ResidencyTierBasis] precedent: a module below
 * `:service` needs it. [PriceRuler.Published] WRITES this value into SQL, so
 * "is it a real state?" has to be answerable where the ruler is constructed —
 * and the whole point of one home is that the served set (RFC 165), the write
 * boundary and the ruler cannot disagree about which codes exist.
 */
object UsStateCodes {
  val ALL: Set<String> =
    (
      "AL AK AZ AR CA CO CT DE FL GA HI ID IL IN IA KS KY LA ME MD " +
        "MA MI MN MS MO MT NE NV NH NJ NM NY NC ND OH OK OR PA RI SC " +
        "SD TN TX UT VT VA WA WV WI WY DC AS FM GU MH MP PR PW VI"
    ).split(" ").toSet()

  /**
   * The normalized code, or null when [raw] is not a member. The single home for
   * the rule every writer enforces (trim, uppercase, membership); each surface
   * keeps only its own error shape.
   */
  fun parse(raw: String): String? = raw.trim().uppercase().takeIf { it in ALL }
}
