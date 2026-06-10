package ed.unicoach.db.models

import java.util.UUID

@JvmInline
value class ConvoId(
  val value: UUID,
) : Id {
  override val asString get() = value.toString()
}
