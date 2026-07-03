package ed.unicoach.coaching

/**
 * Outcome of [CoachingService.listTurns]. [Found] carries the visible exchanges
 * (each a user request paired with its successful final answer, tool excursions
 * collapsed) in chronological order; [NotFound] folds the
 * missing/soft-deleted/foreign convo case.
 */
sealed interface ListTurnsResult {
  data class Found(
    val exchanges: List<VisibleExchange>,
  ) : ListTurnsResult

  data object NotFound : ListTurnsResult
}
