package ed.unicoach.db.dao

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Types

/**
 * The authored money vocabulary (RFC 158 D6, widened by RFC 170 D4) as a test
 * fixture: the six tables the canonical fact tables foreign-key into. P2 accepted, with eyes
 * open, that the vocabulary phase is a write precondition -- so every suite
 * that writes a `price_figures` / `cohort_money_stats` row needs these tables
 * non-empty, exactly as [CodebookReferenceFixture] serves 0067's precondition.
 *
 * THE ROWS ARE READ FROM `db/data/money-vocabulary.json`, the very file the
 * ingest loads, and never typed here: a hand-typed copy is a second
 * vocabulary, and drift between the two is the failure the loader's
 * enum-agreement fatal exists to make impossible.
 *
 * [seed] is idempotent (`ON CONFLICT DO NOTHING`), so a suite can call it
 * after every TRUNCATE. It deliberately does NOT go through
 * [CanonicalMoneyDao]: those writes carry change detection a fixture has no
 * business faking, and a raw insert keeps the fixture readable as data.
 */
object MoneyVocabularyFixture {
  /** The committed seed, found by walking up from the test's working directory (the [CodebookReferenceFixture] precedent). */
  val COMMITTED_FILE: File =
    generateSequence(File(".").absoluteFile) { it.parentFile }
      .map { File(it, "db/data/money-vocabulary.json") }
      .firstOrNull { it.isFile }
      ?: error("db/data/money-vocabulary.json was not found above [${File(".").absolutePath}]")

  private val vocabulary: JsonObject by lazy {
    kotlinx.serialization.json.Json
      .parseToJsonElement(COMMITTED_FILE.readText()) as JsonObject
  }

  /** Inserts every seed row not already present. Safe to call before each test and safe to call twice. */
  fun seed(session: SqlSession) {
    insertAll(
      session,
      "INSERT INTO residency_bases (slug, description) VALUES (?, ?) ON CONFLICT DO NOTHING",
      rows("residency_bases"),
    ) { stmt, row ->
      stmt.setString(1, row.text("slug"))
      stmt.setString(2, row.text("description"))
    }
    insertAll(
      session,
      "INSERT INTO arrangements (slug, description, is_living_arrangement) VALUES (?, ?, ?) " +
        "ON CONFLICT DO NOTHING",
      rows("arrangements"),
    ) { stmt, row ->
      stmt.setString(1, row.text("slug"))
      stmt.setString(2, row.text("description"))
      stmt.setBoolean(3, row.boolean("is_living_arrangement"))
    }
    insertAll(
      session,
      "INSERT INTO figure_statuses (slug, description, value_bearing) VALUES (?, ?, ?) " +
        "ON CONFLICT DO NOTHING",
      rows("figure_statuses"),
    ) { stmt, row ->
      stmt.setString(1, row.text("slug"))
      stmt.setString(2, row.text("description"))
      stmt.setBoolean(3, row.boolean("value_bearing"))
    }
    insertAll(
      session,
      "INSERT INTO price_concepts (slug, description, arrangement_varies) VALUES (?, ?, ?) " +
        "ON CONFLICT DO NOTHING",
      rows("price_concepts"),
    ) { stmt, row ->
      stmt.setString(1, row.text("slug"))
      stmt.setString(2, row.text("description"))
      stmt.setBoolean(3, row.boolean("arrangement_varies"))
    }
    insertAll(
      session,
      "INSERT INTO income_bands (slug, min_usd, max_usd, bracket_label, sort_order) VALUES (?, ?, ?, ?, ?) " +
        "ON CONFLICT DO NOTHING",
      rows("income_bands"),
    ) { stmt, row ->
      stmt.setString(1, row.text("slug"))
      stmt.setInt(2, row.text("min_usd").toInt())
      val max = row.getValue("max_usd")
      if (max is JsonNull) stmt.setNull(3, Types.INTEGER) else stmt.setInt(3, max.jsonPrimitive.content.toInt())
      stmt.setString(4, row.text("bracket_label"))
      stmt.setInt(5, row.text("sort_order").toInt())
    }
    insertAll(
      session,
      "INSERT INTO aid_forms (slug, description) VALUES (?, ?) ON CONFLICT DO NOTHING",
      rows("aid_forms"),
    ) { stmt, row ->
      stmt.setString(1, row.text("slug"))
      stmt.setString(2, row.text("description"))
    }
  }

  /** [seed] for a suite holding a raw JDBC [Connection] -- the [CodebookReferenceFixture] adapter. */
  fun seed(connection: Connection) =
    seed(
      object : SqlSession {
        override fun prepareStatement(sql: String): PreparedStatement = connection.prepareStatement(sql)
      },
    )

  private fun <T> insertAll(
    session: SqlSession,
    sql: String,
    rows: List<T>,
    bind: (PreparedStatement, T) -> Unit,
  ) {
    session.prepareStatement(sql).use { stmt ->
      for (row in rows) {
        bind(stmt, row)
        stmt.addBatch()
      }
      stmt.executeBatch()
    }
  }

  private fun rows(section: String): List<JsonObject> = (vocabulary.getValue(section) as JsonArray).map { it as JsonObject }

  private fun JsonObject.text(key: String): String =
    requireNotNull(this[key]) { "money-vocabulary.json row is missing [$key]: [$this]" }.jsonPrimitive.content

  private fun JsonObject.boolean(key: String): Boolean = text(key).toBooleanStrict()
}
