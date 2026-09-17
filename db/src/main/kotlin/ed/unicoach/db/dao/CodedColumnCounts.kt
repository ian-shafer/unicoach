package ed.unicoach.db.dao

import ed.unicoach.db.models.FigureStatus

/**
 * One `GROUP BY` over a coded column, decoded through the enum that owns it.
 *
 * The status breakdown and the source breakdown are the SAME read against a
 * different column and a different `fromValue`, and they were written twice. A
 * stored value the enum does not read is FATAL rather than a string key: these
 * counts land in the provenance row, and a provenance row that reports a slug
 * nothing can read is worse than a failed one.
 *
 * Lifted out of [CanonicalMoneyDao] so the program-grained money store's status
 * counts (RFC 218) share it rather than pasting a third copy.
 *
 * [table] and [column] are fixed DAO identifiers, never caller data -- the
 * interpolation rule the DAO files already follow.
 *
 * [domain] and [accepted] are what the FAILURE says. "holds a value nothing
 * reads" names neither the enumeration that refused the value nor what it would
 * have accepted, so the reader of that line has to find the decode function to
 * learn either -- while the caller had both in hand. A stored slug outside the
 * domain is a migration and an enum that disagree, and the message should be
 * able to say which two.
 */
internal fun <T> countsBy(
  session: SqlSession,
  table: String,
  column: String,
  domain: String,
  accepted: List<String>,
  decode: (String) -> T?,
): Result<Map<T, Int>> =
  session
    .queryList(
      "SELECT $column, count(*) AS n FROM $table GROUP BY $column",
      bind = {},
      map = { rs ->
        val value = rs.getString(column)
        val decoded =
          decode(value)
            ?: error("[$table.$column] holds a slug no $domain reads: [$value]; the accepted values are $accepted")
        decoded to rs.getInt("n")
      },
    ).map { it.toMap() }

/** Per-status row counts over [table], typed: an unknown stored slug is refused loudly, never smuggled as a string key. */
internal fun countsByStatus(
  session: SqlSession,
  table: String,
): Result<Map<FigureStatus, Int>> =
  countsBy(
    session,
    table,
    "status",
    domain = "FigureStatus",
    accepted = FigureStatus.entries.map { it.value },
    decode = FigureStatus::fromValue,
  )
