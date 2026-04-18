package ed.unicoach.queue

enum class JobType(val value: String) {
    // TEST_JOB and TEST_JOB_B are reserved variants used exclusively in integration test suites.
    // Consuming specs add production variants alongside their handler implementations.
    TEST_JOB("TEST_JOB"),
    TEST_JOB_B("TEST_JOB_B"),
    ;

    companion object {
        fun fromValue(value: String): JobType? =
            entries.find { it.value == value }
    }
}


