package ed.unicoach.db.models

@JvmInline
value class ShareEventId(
  val value: Long,
) : Id {
  override val asString get() = value.toString()
}
