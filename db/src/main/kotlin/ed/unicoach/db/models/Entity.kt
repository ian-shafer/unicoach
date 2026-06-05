package ed.unicoach.db.models

import java.time.Instant

interface BaseEntity<ID : Any, V : Any> {
  val id: ID
  val versionId: V
  val createdAt: Instant
  val updatedAt: Instant
}

interface AdvancedEntity {
  val rowCreatedAt: Instant
  val rowUpdatedAt: Instant
}

interface BaseVersionEntity<ID : Any, V : Any> : BaseEntity<ID, V>
