package ed.unicoach.appstore

import ed.unicoach.testing.ScriptedQueue

/**
 * A scripted, multi-response fake at the [AppStoreTransport] seam (RFC 107's
 * shape). Each `get()` call dequeues the next [AppStoreReplay] in order —
 * either a canned [AppStoreTransportResponse] or a thrown exception (how the
 * real transport surfaces IO failure). Every call's path and bearer token are
 * captured into [calls], letting a test assert the request the real client
 * built (the lookup path and the minted JWT).
 *
 * The script is strict: a `get()` call past the end of the script throws, so an
 * unexpected extra Apple call fails the test loudly rather than silently
 * returning a canned reply.
 */
class ScriptedAppStoreTransport(
  replays: List<AppStoreReplay>,
) : AppStoreTransport {
  class Call(
    val path: String,
    val bearerToken: String,
  )

  private val queue = ScriptedQueue(replays, "ScriptedAppStoreTransport.get()")
  private val captured = mutableListOf<Call>()

  /** Requests received, one per `get()` call, in call order. */
  val calls: List<Call> get() = captured

  override suspend fun get(
    path: String,
    bearerToken: String,
  ): AppStoreTransportResponse {
    // Dequeue before recording so an unscripted call leaves [calls] holding
    // only the calls the script actually answered.
    val replay = queue.next()
    captured.add(Call(path, bearerToken))
    return when (replay) {
      is AppStoreReplay.Response -> AppStoreTransportResponse(replay.status, replay.body)
      is AppStoreReplay.Throwing -> throw replay.error
    }
  }

  companion object {
    /** A single-response script. */
    fun of(
      status: Int,
      body: String,
    ): ScriptedAppStoreTransport = ScriptedAppStoreTransport(listOf(AppStoreReplay.Response(status, body)))

    /** A single-call script that throws [error] (the real transport's IO-failure shape). */
    fun throwing(error: Throwable): ScriptedAppStoreTransport = ScriptedAppStoreTransport(listOf(AppStoreReplay.Throwing(error)))
  }
}

/** One recorded `get()` outcome: a canned response, or a thrown exception. */
sealed interface AppStoreReplay {
  class Response(
    val status: Int,
    val body: String,
  ) : AppStoreReplay

  class Throwing(
    val error: Throwable,
  ) : AppStoreReplay
}
