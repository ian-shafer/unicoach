package ed.unicoach.db.models

@JvmInline
value class ConvoRequestId(
  val value: Long,
) : Id {
  override val asString get() = value.toString()
}
