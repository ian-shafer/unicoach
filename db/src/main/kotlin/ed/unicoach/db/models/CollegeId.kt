package ed.unicoach.db.models

import java.util.UUID

@JvmInline
value class CollegeId(
  val value: UUID,
) : Id {
  override val asString get() = value.toString()
}
