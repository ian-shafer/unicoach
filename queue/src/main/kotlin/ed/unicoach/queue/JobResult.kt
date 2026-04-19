package ed.unicoach.queue

sealed interface JobResult {
  val status: AttemptStatus

  data object Success : JobResult {
    override val status = AttemptStatus.SUCCESS
  }

  data class RetriableFailure(
    val message: String,
  ) : JobResult {
    override val status = AttemptStatus.RETRIABLE_FAILURE
  }

  data class PermanentFailure(
    val message: String,
  ) : JobResult {
    override val status = AttemptStatus.PERMANENT_FAILURE
  }
}
