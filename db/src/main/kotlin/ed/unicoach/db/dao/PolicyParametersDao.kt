package ed.unicoach.db.dao

import ed.unicoach.common.models.ValidationError
import ed.unicoach.db.models.PolicyParameter
import ed.unicoach.db.models.PolicyParameterRow
import java.sql.ResultSet

/**
 * Read layer over the `policy_parameters` reference table (RFC 159): the
 * migration-seeded federal Pell and Direct Loan policy figures, one row per
 * (award_year, parameter). READ-ONLY by design -- rows are authored by
 * migration and corrected only by a later migration, so this DAO exposes no
 * insert, update or delete path at all ([SystemPromptsDao] is the closest
 * sibling, and even it can insert). Stateless `object`, one [SqlSession] per
 * call, transaction boundary owned by the caller.
 */
object PolicyParametersDao {
  private fun mapRow(rs: ResultSet): PolicyParameterRow {
    val awardYear = rs.getInt("award_year")
    return PolicyParameterRow(
      awardYear = awardYear,
      parameter = parseParameter(rs.getString("parameter"), awardYear),
      value = rs.getInt("value"),
      sourceName = rs.getString("source_name"),
      sourceUrl = rs.getString("source_url"),
    )
  }

  /**
   * Reconstructs a persisted parameter name. The schema CHECK already
   * guarantees a member value is stored, so a miss here is row corruption,
   * surfaced as a [CorruptPersistedValueException] ([MoneyProfilesDao]'s
   * convention) -- never silently skipped, because a skipped row would let the
   * coach serve a parameter group with a figure quietly missing from it.
   */
  private fun parseParameter(
    value: String,
    awardYear: Int,
  ): PolicyParameter =
    PolicyParameter.fromValue(value)
      ?: throw CorruptPersistedValueException(
        value,
        ValidationError.InvalidFormat(expected = "a known PolicyParameter value"),
        location = "policy_parameters.parameter (award_year [$awardYear])",
      )

  /**
   * Every seeded parameter row, ordered newest award year first so a reader
   * resolving "the latest year a group is present for" walks forward. One
   * statement for the whole table -- at launch it is 29 rows.
   */
  fun listAll(session: SqlSession): Result<List<PolicyParameterRow>> =
    session.queryList(
      "SELECT * FROM policy_parameters ORDER BY award_year DESC, parameter",
      bind = {},
      map = ::mapRow,
    )

  /** The rows of one award year, in stable parameter order. */
  fun listForAwardYear(
    session: SqlSession,
    awardYear: Int,
  ): Result<List<PolicyParameterRow>> =
    session.queryList(
      "SELECT * FROM policy_parameters WHERE award_year = ? ORDER BY parameter",
      bind = { it.setInt(1, awardYear) },
      map = ::mapRow,
    )
}
