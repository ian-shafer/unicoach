package ed.unicoach.rest.plugins

import ed.unicoach.common.json.asJson
import ed.unicoach.db.models.TokenHash
import ed.unicoach.queue.EnqueueResult
import ed.unicoach.queue.JobType
import ed.unicoach.queue.QueueService
import ed.unicoach.queue.SessionExpiryPayload
import ed.unicoach.rest.auth.SessionConfig
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.request.uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.Base64

class SessionExpiryPluginConfig {
  lateinit var sessionConfig: SessionConfig
  lateinit var queueService: QueueService
  var ignorePathPrefixes: Set<String> = emptySet()
}

val SessionExpiryPlugin =
  createApplicationPlugin(
    name = "SessionExpiryPlugin",
    createConfiguration = ::SessionExpiryPluginConfig,
  ) {
    val cookieName = pluginConfig.sessionConfig.cookieName
    val queueService = pluginConfig.queueService
    val ignorePathPrefixes = pluginConfig.ignorePathPrefixes

    on(ResponseSent) { call ->
      val token = call.request.cookies[cookieName] ?: return@on
      val path = call.request.uri
      if (ignorePathPrefixes.any { path.startsWith(it) }) return@on
      val status = call.response.status()?.value ?: return@on
      if (status !in 200..299) return@on

      // Fire-and-forget on the application scope. On shutdown, Ktor cancels
      // this scope and the coroutine is silently dropped. The next request
      // after restart will re-enqueue.
      call.application.launch(Dispatchers.IO) {
        try {
          val tokenHash = TokenHash.fromRawToken(token)
          val encodedHash =
            Base64
              .getEncoder()
              .encodeToString(tokenHash.value)
          val payload =
            SessionExpiryPayload(
              tokenHash = encodedHash,
            ).asJson()
          when (
            val result =
              queueService.enqueue(
                JobType.SESSION_EXTEND_EXPIRY,
                payload,
              )
          ) {
            is EnqueueResult.Success -> { /* no-op */ }
            is EnqueueResult.DatabaseFailure ->
              LoggerFactory
                .getLogger("SessionExpiryPlugin")
                .error("Failed to enqueue session expiry job: {}", result.error)
          }
        } catch (e: Exception) {
          // Fire-and-forget. Log and swallow.
          LoggerFactory
            .getLogger("SessionExpiryPlugin")
            .error("Failed to enqueue session expiry job", e)
        }
      }
    }
  }
