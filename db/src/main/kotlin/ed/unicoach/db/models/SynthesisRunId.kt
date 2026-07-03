package ed.unicoach.db.models

@JvmInline
value class SynthesisRunId(
  val value: Long,
) : Id {
  override val asString get() = value.toString()
}
