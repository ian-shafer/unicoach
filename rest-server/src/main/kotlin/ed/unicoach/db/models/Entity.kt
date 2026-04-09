package ed.unicoach.db.models

import java.time.Instant

interface BaseEntity<ID : Any> {
    val id: ID
    val versionId: UserVersionId
    val createdAt: Instant
    val updatedAt: Instant
}

interface AdvancedEntity {
    val rowCreatedAt: Instant
    val rowUpdatedAt: Instant
}

interface BaseVersionEntity<ID : Any> : BaseEntity<ID>
