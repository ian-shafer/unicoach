package ed.unicoach.db.models

import ed.unicoach.db.DatabaseConfig
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The entitling status set is declared by [SubscriptionStatus.entitles] and
 * written a second time in SQL — `subscriptions_user_current_idx`'s predicate,
 * the partial index that serves `SubscriptionsDao`'s gate read — and the two are
 * pinned against each other here (RFC 244; the `SourceUrlStatusSchemaPinTest`
 * shape of RFC 200).
 *
 * `SubscriptionsDao` builds its `status IN (...)` from the enum, so the Kotlin
 * side cannot drift. The index predicate can: Postgres will not complain, no
 * other test fails, and the cost is silent — a status added to
 * [SubscriptionStatus.ENTITLING] that the predicate does not list drops the gate's read onto a sequential scan over
 * every subscription in the table.
 *
 * Asked of the MIGRATED DATABASE and not of a migration's text. A landed
 * migration under `db/schema` is immutable by this repo's convention, so a sweep
 * over one of them can never see a change — while the live index can change,
 * through any later migration that re-creates it, which is exactly the drift
 * this file exists to catch. `pg_get_expr` also renders the predicate Postgres
 * actually parsed, so no SQL grammar is re-derived here.
 */
class SubscriptionEntitlingStatusPinTest {
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

  /** The predicate of the gate's partial index, as the database reports it; null when no such index is live. */
  private fun livePredicate(): String? =
    connection
      .prepareStatement(
        """
        SELECT pg_get_expr(i.indpred, i.indrelid) AS predicate
        FROM pg_index i
        JOIN pg_class c ON c.oid = i.indexrelid
        WHERE c.relname = 'subscriptions_user_current_idx'
        """.trimIndent(),
      ).use { stmt ->
        stmt.executeQuery().use { rs -> if (rs.next()) rs.getString("predicate") else null }
      }

  @Test
  fun `the index this test pins is live, and is partial`() {
    // The control: an index that was renamed or dropped would otherwise fail the
    // pin below as "listed nothing", which reads like a parse fault rather than
    // the index being gone.
    val predicate =
      assertNotNull(
        livePredicate(),
        "the gate's partial index subscriptions_user_current_idx is not in the migrated database",
      )
    assertTrue(predicate.contains("status"), "read an index whose predicate is not about the status set: [$predicate]")
  }

  @Test
  fun `the live index predicate lists exactly the statuses the enum says entitle`() {
    val predicate = assertNotNull(livePredicate())
    val listed =
      Regex("""'([^']*)'""")
        .findAll(predicate)
        .map { it.groupValues[1] }
        .toList()

    assertEquals(
      SubscriptionStatus.ENTITLING.map { it.value },
      listed,
      "the gate read and the index that serves it name different statuses: a status the enum entitles and " +
        "the index omits costs a sequential scan on every gated turn — the live predicate is [$predicate]",
    )
  }
}
