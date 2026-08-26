package ed.unicoach.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The crypto pool's threads must be daemon threads. A non-daemon pool keeps the
 * JVM alive after `main` returns, silently hanging any CLI that hashed a
 * password — a failure mode that already cost one run 12 hours.
 *
 * This covers the mechanism; that `bin/state-apply` actually exits is asserted
 * end-to-end in `bin/scripts-tests`, because daemon-ness has no observable
 * effect inside a Gradle test worker.
 */
class CryptoDispatcherTest {
  @Test
  fun `every thread in the crypto pool is a daemon thread`() =
    runBlocking {
      val poolSize = Runtime.getRuntime().availableProcessors()

      // Hold every task until all of them are running, so the pool is forced to
      // create all its threads. Without the latch a single thread could serve
      // every task in turn, and a factory that daemonised only the first thread
      // would pass.
      val allRunning = CountDownLatch(poolSize)
      val threads =
        (1..poolSize)
          .map {
            async(Dispatchers.Crypto) {
              allRunning.countDown()
              allRunning.await()
              Thread.currentThread()
            }
          }.awaitAll()

      assertEquals(poolSize, threads.distinct().size, "expected one distinct pool thread per task")
      for (thread in threads) {
        assertTrue(thread.isDaemon, "crypto pool thread [${thread.name}] must be a daemon thread")
        assertTrue(
          thread.name.startsWith(CRYPTO_THREAD_NAME_PREFIX),
          "crypto pool thread name [${thread.name}] must start with [$CRYPTO_THREAD_NAME_PREFIX]",
        )
      }
    }
}
