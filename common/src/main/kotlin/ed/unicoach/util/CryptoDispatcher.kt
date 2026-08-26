package ed.unicoach.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

/**
 * Thread name prefix, so a crypto thread is identifiable in a thread dump.
 * `internal` only so `CryptoDispatcherTest` can assert against this constant
 * rather than a second copy of the literal.
 */
internal const val CRYPTO_THREAD_NAME_PREFIX = "crypto-worker-"

/**
 * Dedicated pool for Argon2 hashing, which is deliberately CPU- and
 * memory-expensive and so must not occupy [Dispatchers.Default].
 *
 * The threads are **daemon** threads, and that is load-bearing. A non-daemon
 * pool keeps the JVM alive after `main` returns, so any CLI that ever hashed a
 * password finished its work and then hung forever — once for 12 hours. The
 * previous fix was an `exitProcess(0)` in the affected CLI, which only moved
 * the obligation onto every future CLI author; making the pool daemon fixes it
 * for all of them.
 *
 * Nothing depends on this pool to keep a process alive. The servers are held up
 * by their own lifecycles and shut down through their hooks, and a CLI's
 * `runBlocking` has completed before `main` returns — so no hash is ever in
 * flight when the JVM exits.
 */
private val cryptoThreadPool =
  Executors.newFixedThreadPool(
    Runtime.getRuntime().availableProcessors(),
    Thread
      .ofPlatform()
      .daemon()
      .name(CRYPTO_THREAD_NAME_PREFIX, 1)
      .factory(),
  )

// Wrapped once, not inside the getter: asCoroutineDispatcher() allocates a fresh
// ExecutorCoroutineDispatcher per call, so inlining this back would hand out a
// different dispatcher instance on every access.
private val cryptoDispatcher = cryptoThreadPool.asCoroutineDispatcher()

val Dispatchers.Crypto: CoroutineDispatcher
  get() = cryptoDispatcher
