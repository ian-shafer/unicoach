package ed.unicoach.db.models

import java.util.UUID

@JvmInline
value class AdmissionTestScoreId(
  val value: UUID,
) : Id {
  override val asString get() = value.toString()
}

@JvmInline
value class AdmissionTestScoreDeclineId(
  val value: UUID,
) : Id {
  override val asString get() = value.toString()
}
