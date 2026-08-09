package ed.unicoach.testing

/**
 * A strict, ordered scripted queue: each [next] call dequeues the next outcome,
 * or throws naming [label] when the script is exhausted — the "unexpected extra
 * call fails loudly" contract every transport-seam fake in this codebase would
 * otherwise re-derive by hand.
 *
 * [label] identifies the exhausted seam in the failure message, so pass the
 * fake's call site rather than a bare type name (e.g.
 * `"ScriptedAppStoreTransport.get()"`); the message is the only context a test
 * failing on an unscripted call gets.
 *
 * Outcomes are dequeued but never interpreted: a seam fake stays responsible for
 * mapping its own outcome type to a return value or a throw, and for recording
 * whatever it captures about the call.
 */
class ScriptedQueue<T>(
  private val outcomes: List<T>,
  private val label: String,
) {
  private var index = 0

  fun next(): T {
    check(index < outcomes.size) {
      "$label exhausted: unexpected call #${index + 1}, script has ${outcomes.size}"
    }
    return outcomes[index++]
  }
}
