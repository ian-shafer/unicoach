package ed.unicoach.db.models

import java.time.Instant

interface Id {
  val asString: String
}

interface Identifiable<ID : Id> {
  val id: ID
}

interface Created {
  val createdAt: Instant
}

interface Updated {
  val updatedAt: Instant
}

interface Versioned {
  val version: Int
}

interface SoftDeletable {
  val deletedAt: Instant?
}
