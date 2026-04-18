package ed.unicoach.queue

enum class JobStatus(val value: String) {
    SCHEDULED("SCHEDULED"),
    RUNNING("RUNNING"),
    COMPLETED("COMPLETED"),
    DEAD_LETTERED("DEAD_LETTERED"),
}
