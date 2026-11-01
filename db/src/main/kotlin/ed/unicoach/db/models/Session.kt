package ed.unicoach.db.models

import ed.unicoach.db.session.SessionMetadata
import java.time.Instant

data class Session(
  override val id: SessionId,
  override val version: Int,
  override val createdAt: Instant,
  val userId: UserId?,
  val loginMethod: LoginMethod?,
  /**
   * What the session carries besides who it belongs to (RFC 265). Never null:
   * proto3 has no "absent" distinct from the default, so a SQL `NULL` reads as
   * [SessionMetadata.getDefaultInstance] and a nullable type would spell one
   * state two ways.
   */
  val metadata: SessionMetadata,
  val userAgent: String?,
  val initialIp: String?,
  val expiresAt: Instant,
) : Identifiable<SessionId>,
  Created,
  Versioned
