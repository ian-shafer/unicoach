package ed.unicoach.db.models

@JvmInline
value class ConvoResponseId(
  val value: Long,
) : Id {
  override val asString get() = value.toString()
}
