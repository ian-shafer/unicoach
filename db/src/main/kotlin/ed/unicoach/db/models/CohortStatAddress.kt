package ed.unicoach.db.models

/**
 * WHERE one `cohort_money_stats` cell lives, as the three columns that
 * together say WHICH STUDENTS a number describes: the measure, the population
 * it is about, and the aid relationship that bounds it.
 *
 * ONE type for the whole repo, and it lives here in `:db` beside the three
 * enums it is made of because every module that names an address depends on
 * `:db`: the ingest that WRITES the cells (`:college`), the search DAO that
 * READS them (`:db`), and the cost surface that folds them (`:service`).
 * Three private copies of one triple were three vocabularies, and a
 * cross-module comparison had to convert between them before it could compare
 * anything.
 *
 * A type rather than three arguments, because the three must travel WHOLE
 * (RFC 176 D4). The store holds TWO `avg_net_price` series -- the Scorecard
 * NPT4 family, and IPEDS SFA's grant-aided cohort -- which differ by
 * POPULATION and AID SCOPE and never by vintage. A reader keyed on the measure
 * alone, taking the newest vintage, therefore serves the wrong cohort's number
 * under the right label with every test green; that is RFC 166 tier-0 blocker
 * 1, and this type is what makes the rule unforgettable.
 *
 * Two columns of the natural key are deliberately NOT part of it:
 * `income_band`, which selects a CELL of a series rather than the series (the
 * band series files one row per band under one address), and
 * `residency_scope`, which is CONTROL-dependent -- both fills choose it from a
 * fact about the institution, so pinning one here would drop every public
 * school's net price. A reader that can meet two scopes at one address
 * resolves them by an explicit rule; it does not pin one in the address.
 */
data class CohortStatAddress(
  val measure: MoneyMeasure,
  val population: CohortPopulation,
  val aidScope: CohortAidScope,
)

/**
 * The cohort addresses more than one module names -- the shared half of the
 * address grid, in the module both halves already depend on.
 *
 * WHAT `avg_net_price` IS -- which population, bounded by which aid
 * relationship -- is not a per-surface decision, so it is stated once here and
 * referenced by the fill that writes it (`:college`'s `CanonicalMoneyLoader`)
 * and the DAO that reads it (`CollegesDao`). WHICH of them a surface SERVES
 * still is a per-surface decision, and each surface keeps its own list built
 * from these members (`CollegesDao.SERVED_ADDRESSES`,
 * `CanonicalMoneyLoader.WRITTEN_ADDRESSES`).
 *
 * Addresses only ONE module names stay with that module: the fill writes three
 * more (the published-cost blend, SFA's grant-aided net price, the average
 * Pell award) that no reader here addresses.
 */
object CohortAddresses {
  /** The NPT4 family (Scorecard) and SFA's NPIS4/NPT4 band twins: federal-aid-receiving Title IV undergraduates. */
  val AVG_NET_PRICE: CohortStatAddress =
    CohortStatAddress(
      MoneyMeasure.AVG_NET_PRICE,
      CohortPopulation.TITLE_IV_AIDED_UNDERGRADUATES,
      CohortAidScope.FEDERAL_AID_RECEIVING,
    )

  /** MD_EARN_WNE_P10: employed, not enrolled, ten years after entry. Undated. */
  val MEDIAN_EARNINGS_10Y: CohortStatAddress =
    CohortStatAddress(
      MoneyMeasure.MEDIAN_EARNINGS_10Y,
      CohortPopulation.EMPLOYED_NOT_ENROLLED_10Y_AFTER_ENTRY,
      CohortAidScope.ALL,
    )

  /** GRAD_DEBT_MDN: the completers who borrowed federally. Undated. */
  val MEDIAN_DEBT_AT_COMPLETION: CohortStatAddress =
    CohortStatAddress(
      MoneyMeasure.MEDIAN_DEBT_AT_COMPLETION,
      CohortPopulation.FEDERAL_LOAN_BORROWING_COMPLETERS,
      CohortAidScope.FEDERAL_LOAN_BORROWING,
    )

  /**
   * PCTPELL / UPGRNTP: the Pell share of ALL undergraduates -- the one address
   * both the Scorecard and the SFA fill write, by design (same measure, same
   * population, same scope, different vintage), so newest-vintage-wins is
   * correct here and only here.
   */
  val PELL_SHARE: CohortStatAddress =
    CohortStatAddress(MoneyMeasure.PELL_SHARE, CohortPopulation.UNDERGRADUATES, CohortAidScope.ALL)
}
