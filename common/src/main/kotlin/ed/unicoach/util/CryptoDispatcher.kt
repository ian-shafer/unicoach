package ed.unicoach.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors

private val cryptoThreadPool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())

val Dispatchers.Crypto: CoroutineDispatcher
  get() = cryptoThreadPool.asCoroutineDispatcher()
