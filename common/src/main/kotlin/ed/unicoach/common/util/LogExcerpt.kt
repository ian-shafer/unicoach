package ed.unicoach.common.util

/** Bounded prefix length for a raw excerpt (e.g. an LLM output) in a WARN log line. */
const val LOG_EXCERPT_CHARS: Int = 2_000

/**
 * Caps [raw] to a bounded prefix so a WARN log line stays sane: the first
 * [LOG_EXCERPT_CHARS] characters, followed by an elision marker naming how many
 * were dropped. Shared by the LLM-call services (extraction, synthesis,
 * fit-lens) that log raw model output on a parse failure.
 */
fun truncateForLog(raw: String): String =
  if (raw.length <= LOG_EXCERPT_CHARS) {
    raw
  } else {
    raw.take(LOG_EXCERPT_CHARS) + "…(${raw.length - LOG_EXCERPT_CHARS} more chars)"
  }
