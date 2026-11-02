package ed.unicoach.db

import ed.unicoach.db.dao.LLM_COST_AGGREGATE_PROJECTION
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.assertTrue

/**
 * The per-account spend meter reads `account_llm_cost` through index scans
 * (RFC 266 §7, slice deletion/03/the-walk, decision D-G).
 *
 * The views read each owner key as `COALESCE(live, deleted)` (RFC 264), and
 * only an index on that same expression serves the filter. If a view's
 * expression is rewritten, the planner stops using its index; this test names
 * the index that went unused. `enable_seqscan = off` takes table size out of
 * the planner's choice, so the plan says which index paths EXIST, not which a
 * near-empty test database happens to prefer.
 */
class SpendViewIndexTest {
  companion object {
    private lateinit var connection: Connection

    @JvmStatic
    @BeforeAll
    fun setupAll() {
      val config =
        ed.unicoach.common.config.AppConfig
          .load("common.conf", "db.conf")
          .getOrThrow()
      val dbConfig = DatabaseConfig.from(config).getOrThrow()
      connection = DriverManager.getConnection(dbConfig.jdbcUrl, dbConfig.user, dbConfig.password ?: "")
    }

    @JvmStatic
    @AfterAll
    fun teardownAll() {
      if (::connection.isInitialized && !connection.isClosed) connection.close()
    }
  }

  @Test
  fun `the meter's query on account_llm_cost uses the three account indexes`() {
    connection.autoCommit = false
    val plan =
      try {
        connection.createStatement().use { it.execute("SET LOCAL enable_seqscan = off") }
        // The meter's own query (AccountLlmCostDao), with a literal account id
        // so the plan is the custom plan a bound id gets.
        connection.createStatement().use { stmt ->
          val meter = "$LLM_COST_AGGREGATE_PROJECTION FROM account_llm_cost WHERE user_id = '${UUID.randomUUID()}'"
          stmt.executeQuery("EXPLAIN $meter").use { rs ->
            buildList { while (rs.next()) add(rs.getString(1)) }.joinToString("\n")
          }
        }
      } finally {
        connection.rollback()
        connection.autoCommit = true
      }
    for (index in listOf("students_account_idx", "supporters_account_idx", "supporter_llm_costs_account_idx")) {
      assertTrue(index in plan, "the meter's plan must use $index; it was:\n$plan")
    }
  }
}
