package ed.unicoach.net

import com.typesafe.config.Config
import java.time.Duration

class NetConfig private constructor(
  val sessionSlidingWindowThreshold: Duration,
) {
  companion object {
    fun from(config: Config): Result<NetConfig> =
      runCatching {
        val netConfig = config.getConfig("net")
        val threshold = netConfig.getDuration("session.slidingWindowThreshold")
        NetConfig(sessionSlidingWindowThreshold = threshold)
      }
  }
}
