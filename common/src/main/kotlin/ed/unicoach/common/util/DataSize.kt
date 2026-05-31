package ed.unicoach.common.util

@JvmInline
value class DataSize private constructor(
  val bytes: Long,
) {
  init {
    require(bytes >= 0) { "DataSize must be non-negative, got $bytes bytes" }
  }

  companion object {
    fun ofBytes(bytes: Long): DataSize = DataSize(bytes)
  }
}
