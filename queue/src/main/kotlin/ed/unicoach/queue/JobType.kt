package ed.unicoach.queue

enum class JobType(
  val value: String,
) {
  // TEST_JOB and TEST_JOB_B are reserved variants used exclusively in integration test suites.
  // Consuming specs add production variants alongside their handler implementations.
  TEST_JOB("TEST_JOB"),
  TEST_JOB_B("TEST_JOB_B"),
  SESSION_EXTEND_EXPIRY("SESSION_EXTEND_EXPIRY"),
  EXTRACT_CONVERSATION("EXTRACT_CONVERSATION"),
  SYNTHESIZE_STUDENT("SYNTHESIZE_STUDENT"),
  SEND_EMAIL("SEND_EMAIL"),

  // The daily dispatcher (RFC 97): cron enqueues one SYNTHESIS_SWEEP; its handler
  // enumerates active students and fans out one SYNTHESIZE_STUDENT per student.
  SYNTHESIS_SWEEP("SYNTHESIS_SWEEP"),

  // The weekly fit-lens dispatcher (RFC 98), a sibling of SYNTHESIS_SWEEP: cron
  // enqueues one FIT_LENS_SWEEP; its handler enumerates active students and fans
  // out one FIT_LENS per student, each a two-call college-discovery pass.
  FIT_LENS_SWEEP("FIT_LENS_SWEEP"),
  FIT_LENS("FIT_LENS"),
  ;

  companion object {
    fun fromValue(value: String): JobType? = entries.find { it.value == value }
  }
}
