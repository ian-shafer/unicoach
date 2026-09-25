package ed.unicoach.chat

import kotlinx.serialization.json.JsonElement

/**
 * The generalised "this answer names no date of ours" guard, shared by every
 * tool whose refusal exists to WITHHOLD dates (RFC 208 D7, RFC 230).
 *
 * It sits beside [BareSourceCodeGuard] for the reason that walker moved here: a
 * second suite had hand-typed the pattern, and a raw-string regex that can never
 * match reads as covered, which is worse than no guard at all. The positive
 * control is therefore a CALL rather than a literal each caller has to remember
 * to write.
 */
object PayloadDateGuard {
  /** An ISO date anywhere in a payload, prose and `why` sentences included. */
  val ISO_DATE: Regex = Regex("""\d{4}-\d{2}-\d{2}""")

  /** Every ISO date [payload] carries, in the order it says them. */
  fun datesIn(payload: JsonElement): List<String> = datesIn(payload.toString())

  /** Every ISO date [text] carries -- a refusal SENTENCE is not a payload, and is swept the same way. */
  fun datesIn(text: String): List<String> = ISO_DATE.findAll(text).map { it.value }.toList()

  /** The self-check on [ISO_DATE], so every consumer gets the positive control for free. */
  fun isoDatePatternFires(): Boolean = ISO_DATE.containsMatchIn(A_DATED_PAYLOAD)

  /** A payload that DOES carry a date, so an empty sweep means something. */
  private const val A_DATED_PAYLOAD = """{"as_of":"2026-10-02"}"""
}
