package ed.unicoach.cron

/**
 * The natural key of a [PeriodicJob] row: its `name`. An inline value class over
 * [String], following the `Job`/`NewJob` id style in `:queue`.
 *
 * A name is a lowercase slug: `[a-z0-9]` head then `[a-z0-9-]`, up to
 * [MAX_LENGTH] chars ([PATTERN]). The bound mirrors the DB's
 * `CHECK (length(name) <= 128)`; the character allowlist is stricter than the
 * DB (the column is free `TEXT`) so that a name parsed from an untrusted admin
 * path segment can never carry `CR`/`LF`/`/`/`?`/`#` into a redirect `Location`
 * header. [parse] is the single gate for untrusted input.
 */
@JvmInline
value class PeriodicJobName(
  val value: String,
) {
  companion object {
    /** The DB's `CHECK (length(name) <= 128)` bound, in characters. */
    const val MAX_LENGTH = 128

    /** A lowercase slug, `[a-z0-9]` head then `[a-z0-9-]`, up to [MAX_LENGTH] chars. */
    val PATTERN = Regex("^[a-z0-9][a-z0-9-]{0,${MAX_LENGTH - 1}}$")

    /** Parses an untrusted string, returning null unless it matches [PATTERN]. */
    fun parse(raw: String): PeriodicJobName? = if (PATTERN.matches(raw)) PeriodicJobName(raw) else null
  }
}
