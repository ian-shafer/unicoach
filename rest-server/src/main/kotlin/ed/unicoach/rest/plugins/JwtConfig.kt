package ed.unicoach.rest.plugins

import com.typesafe.config.Config
import ed.unicoach.common.config.SecretString
import ed.unicoach.common.config.getNonBlankString

class JwtConfig private constructor(
  val secret: SecretString,
  val issuer: String,
  val audience: String,
) {
  companion object {
    fun from(config: Config): Result<JwtConfig> =
      runCatching {
        val secretValue = config.getNonBlankString("jwt.secret")
        val issuer = config.getNonBlankString("jwt.issuer")
        val audience = config.getNonBlankString("jwt.audience")

        JwtConfig(
          secret = SecretString(secretValue),
          issuer = issuer,
          audience = audience,
        )
      }
  }
}
