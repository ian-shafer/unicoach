package ed.unicoach.db.models

import java.util.UUID

@JvmInline
value class StudentId(
  val value: UUID,
)

@JvmInline
value class StudentVersionId(
  val value: Int,
)
