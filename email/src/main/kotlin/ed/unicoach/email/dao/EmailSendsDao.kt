package ed.unicoach.email.dao

import ed.unicoach.db.dao.DatabaseException
import ed.unicoach.db.dao.SqlSession
import ed.unicoach.db.dao.mapDatabaseError
import ed.unicoach.email.SentEmail
import java.sql.ResultSet
import java.util.UUID

object EmailSendsDao {
  fun insert(
    session: SqlSession,
    newSend: NewEmailSend,
  ): Result<SentEmail> =
    try {
      val sql =
        """
        INSERT INTO email_sends (
          recipient_email, sender_email, subject, body, status, provider, provider_message_id, error_message
        )
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        RETURNING *
        """.trimIndent()
      session.prepareStatement(sql).use { stmt ->
        stmt.setString(1, newSend.recipient.value)
        stmt.setString(2, newSend.sender.value)
        stmt.setString(3, newSend.subject.value)
        stmt.setString(4, newSend.body.value)
        stmt.setString(5, newSend.status.name)
        stmt.setString(6, newSend.provider)
        stmt.setString(7, newSend.providerMessageId)
        stmt.setString(8, newSend.errorMessage)

        stmt.executeQuery().use { rs ->
          if (rs.next()) {
            Result.success(mapSentEmail(rs))
          } else {
            Result.failure(DatabaseException(RuntimeException("Insert succeeded but RETURNING produced no row")))
          }
        }
      }
    } catch (e: Exception) {
      Result.failure(mapDatabaseError(e))
    }

  private fun mapSentEmail(rs: ResultSet): SentEmail =
    SentEmail(
      id = UUID.fromString(rs.getString("id")),
      providerMessageId = rs.getString("provider_message_id"),
    )
}
