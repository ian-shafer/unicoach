package ed.unicoach.db.models

@JvmInline
value class ExtractionRunId(
  val value: Long,
) : Id {
  override val asString get() = value.toString()
}
