package ed.unicoach.queue

enum class AttemptStatus(val value: String) {
    SUCCESS("SUCCESS"),
    RETRIABLE_FAILURE("RETRIABLE_FAILURE"),
    PERMANENT_FAILURE("PERMANENT_FAILURE"),
}
