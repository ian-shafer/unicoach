package ed.unicoach.email

import com.typesafe.config.Config

class SesConfig private constructor(
  val region: String,
  val accessKeyId: String?,
  val secretAccessKey: String?,
) {
  companion object {
    // Reads email.ses.region fail-fast (packaged default) and the two credential
    // keys via hasPath -> nullable (absent when the env-var override is unset). No
    // region or credential validation here; the SES SDK surfaces those at first
    // send, mapped to TransientFailure by the adapter's catch-all.
    fun from(config: Config): Result<SesConfig> =
      runCatching {
        SesConfig(
          region = config.getString("email.ses.region"),
          accessKeyId = config.takeIf { it.hasPath("email.ses.accessKeyId") }?.getString("email.ses.accessKeyId"),
          secretAccessKey =
            config.takeIf { it.hasPath("email.ses.secretAccessKey") }?.getString("email.ses.secretAccessKey"),
        )
      }
  }
}
