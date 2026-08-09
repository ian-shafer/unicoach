package ed.unicoach.common.util

@JvmInline
value class DataSize private constructor(
  val bytes: Long,
) {
  init {
    require(bytes >= 0) { "DataSize must be non-negative, got $bytes bytes" }
  }

  companion object {
    private const val BYTES_PER_KIBIBYTE = 1024L

    fun ofBytes(bytes: Long): DataSize = DataSize(bytes)

    /**
     * The KiB→byte multiplier lives here, so a caller that means "16 KiB" says
     * so instead of writing `16 * 1024` at its own declaration site.
     */
    fun ofKibibytes(kibibytes: Long): DataSize = ofBytes(kibibytes * BYTES_PER_KIBIBYTE)
  }
}
