package ed.unicoach.db.models

@JvmInline
value class ObservationId(
  val value: Long,
) : Id {
  override val asString get() = value.toString()
}
