/*
 * The one place the budget-gate test fixture is defined (RFC 109). Every suite
 * that wires a BudgetService with a chosen allowance — the four coaching
 * passes' tests, their handlers' tests, and BudgetServiceTest itself — builds it
 * here rather than repeating the config-parse incantation.
 */
package ed.unicoach.coaching.budget

import com.typesafe.config.ConfigFactory
import ed.unicoach.db.Database

/**
 * A [BudgetService] over [database] whose free allowance is [freeAllowanceUsd],
 * stated the way config states it.
 */
fun testBudgetService(
  database: Database,
  freeAllowanceUsd: String,
): BudgetService =
  BudgetService(
    database,
    BudgetConfig
      .from(ConfigFactory.parseString("budget.freeAllowanceUsd = $freeAllowanceUsd"))
      .getOrThrow(),
  )

/**
 * A [BudgetService] whose allowance no test's spend can reach, so every case
 * that is not ABOUT the budget gate runs as it did before RFC 109. Well under
 * the ~$92M ceiling [BudgetConfig] enforces, so it stays a plain allowance
 * rather than a case about the ceiling.
 */
fun generousBudgetService(database: Database): BudgetService = testBudgetService(database, freeAllowanceUsd = "1000000.00")

/** The kill-switch allowance: every student is exhausted at zero spend. */
fun exhaustedBudgetService(database: Database): BudgetService = testBudgetService(database, freeAllowanceUsd = "0.00")
