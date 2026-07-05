package ed.unicoach.auth

import ed.unicoach.common.json.deserialize
import ed.unicoach.email.EmailJobPayload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.sql.Connection

/**
 * Shared read helpers for the enqueued `SEND_EMAIL` jobs, over a raw JDBC
 * [Connection]. The auth-service tests assert enqueue behavior by counting these
 * rows (RFC 96); centralizing the queries keeps the `job_type`/`payload->>'to'`
 * shape in one place.
 */
object SendEmailJobQueries {
  /** Deserialized payloads of every enqueued `SEND_EMAIL` job, insertion order. */
  fun payloads(connection: Connection): List<EmailJobPayload> =
    connection.prepareStatement("SELECT payload FROM jobs WHERE job_type = 'SEND_EMAIL'").use { stmt ->
      stmt.executeQuery().use { rs ->
        buildList {
          while (rs.next()) {
            add(Json.decodeFromString<JsonObject>(rs.getString("payload")).deserialize<EmailJobPayload>())
          }
        }
      }
    }

  /** Total count of enqueued `SEND_EMAIL` jobs. */
  fun count(connection: Connection): Int =
    connection.prepareStatement("SELECT COUNT(*) FROM jobs WHERE job_type = 'SEND_EMAIL'").use { stmt ->
      stmt.executeQuery().use { rs ->
        rs.next()
        rs.getInt(1)
      }
    }

  /** Count of enqueued `SEND_EMAIL` jobs addressed to [recipient]. */
  fun countTo(
    connection: Connection,
    recipient: String,
  ): Int =
    connection
      .prepareStatement("SELECT COUNT(*) FROM jobs WHERE job_type = 'SEND_EMAIL' AND payload->>'to' = ?")
      .use { stmt ->
        stmt.setString(1, recipient)
        stmt.executeQuery().use { rs ->
          rs.next()
          rs.getInt(1)
        }
      }
}
