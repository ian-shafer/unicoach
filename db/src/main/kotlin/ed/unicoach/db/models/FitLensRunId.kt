package ed.unicoach.db.models

@JvmInline
value class FitLensRunId(
  val value: Long,
) : Id {
  override val asString get() = value.toString()
}
