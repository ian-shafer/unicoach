package ed.unicoach.db.models

/**
 * A canonical fact table, as a NAME the data layer owns (RFC 170).
 *
 * The wholesale rebuild deletes a table's rows per publisher
 * ([ed.unicoach.db.dao.CanonicalMoneyDao.deleteFactsOfSources]), and its
 * argument used to be a bare `String` guarded by a runtime `require` against an
 * allowlist -- so a caller wrote a physical table name as free text, a typo
 * compiled, and the run faulted in the middle of a transaction that had already
 * deleted rows. A member cannot be mistyped, the allowlist IS the enum, and the
 * physical name stops travelling upward as a string.
 */
enum class FactTable(
  /** The physical table, spliced into SQL by the DAO -- never by a caller. */
  val tableName: String,
) {
  PRICE_FIGURES("price_figures"),
  COHORT_MONEY_STATS("cohort_money_stats"),
  COHORT_POPULATION_COUNTS("cohort_population_counts"),
  AID_FORM_REQUIREMENTS("aid_form_requirements"),

  /**
   * The program-grained money sibling (RFC 218). A member here and not only in
   * its own DAO because the vocabulary retirement path walks this enum: it
   * clears the rebuilt sources' fact rows before a retired `figure_statuses`
   * slug is deleted, and a table it does not know about would fail that delete
   * with a 23503 nobody could act on.
   */
  PROGRAM_MONEY_STATS("program_money_stats"),
}
