package ed.unicoach.db.dao

import org.postgresql.util.PSQLException
import java.sql.Connection
import java.sql.SQLException

/**
 * Writes one row of [table] from LITERAL SQL and answers with the constraint
 * PostgreSQL refused it with, or null if the row was accepted. A write that
 * failed for anything that is NOT a constraint throws: it is no answer to the
 * question this asks.
 *
 * IT GOES AROUND THE MODELS ON PURPOSE: a sealed type or a `require` makes most
 * bad rows unconstructable in Kotlin, which is exactly why the CHECKs still have
 * to be EXECUTED -- a migration, a generated seed and `psql` all write through
 * the database and none of them typechecks.
 *
 * IT LIVES BESIDE THE SUITES rather than in each of them because the
 * `PSQLException`-to-constraint-name unwrap -- the part a reader has to get
 * right -- is the same two lines for every table, and it had been typed out
 * once per schema suite. A caller keeps its own one-line wrapper naming its
 * table, so its cases read as before.
 *
 * `:db`'s own test source set and not `testFixtures`: every suite that writes a
 * literal row to reach a CHECK is in this module, and the Postgres driver is on
 * this compilation's classpath already.
 */
internal fun violatedConstraintOf(
  connection: Connection,
  table: String,
  columns: Map<String, String>,
): String? =
  try {
    connection.createStatement().use { stmt ->
      stmt.execute(
        "INSERT INTO $table (${columns.keys.joinToString(", ")}) VALUES (${columns.values.joinToString(", ")})",
      )
    }
    null
  } catch (e: SQLException) {
    // A NON-CONSTRAINT FAILURE IS NOT AN ANSWER to "which constraint refused
    // this row" -- it is a broken test environment, and throwing names it where
    // it happened. The fabricated sentence this replaced was a value-shaped
    // stand-in for an absence, in the one channel every caller reads as a
    // constraint name and routes on.
    (e as? PSQLException)?.serverErrorMessage?.constraint
      ?: throw IllegalStateException(
        "[$table] refused a literal row with no constraint name (SQLSTATE [${e.sqlState}])",
        e,
      )
  }
