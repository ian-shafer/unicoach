package ed.unicoach.error

/**
 * The retryability category of a failure, derived from the [TransientError] /
 * [PermanentError] traits: `"transient"` (a blip that may succeed on retry),
 * `"permanent"` (a fault retrying will not fix), or `"unknown"` when the
 * exception carries neither trait. A single shared classifier so a log tag and a
 * structured error agree on the same vocabulary across modules.
 */
fun Throwable.errorCategory(): String =
  when (this) {
    is TransientError -> "transient"
    is PermanentError -> "permanent"
    else -> "unknown"
  }
