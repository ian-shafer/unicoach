package ed.unicoach.db.models

/**
 * The replay unit: one logged request paired with its response, if written.
 *
 * [response] is nullable because a request can exist with no response — the
 * first transaction (request) has committed but the second (response) has not
 * (provider in flight, or the response transaction never ran).
 */
data class ConvoTurn(
  val request: ConvoRequest,
  val response: ConvoResponse?,
)
