package ed.unicoach.queue

import com.typesafe.config.Config

class QueueConfig private constructor() {
    companion object {
        fun from(config: Config): Result<QueueConfig> = runCatching {
            // Verify the block exists as a basic health check
            config.getConfig("queue")
            QueueConfig()
        }
    }
}
