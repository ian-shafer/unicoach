package ed.unicoach.email

import com.typesafe.config.Config

class EmailConfig private constructor(
  val defaultFrom: String,
  val provider: String,
  val ses: SesConfig,
) {
  companion object {
    // Reads email.defaultFrom and email.provider verbatim (both have packaged
    // defaults); no address validation here (the raw defaultFrom string is
    // validated at first send by EmailService.resolvedFrom). Delegates email.ses
    // to SesConfig.from.
    fun from(config: Config): Result<EmailConfig> =
      runCatching {
        EmailConfig(
          defaultFrom = config.getString("email.defaultFrom"),
          provider = config.getString("email.provider"),
          ses = SesConfig.from(config).getOrThrow(),
        )
      }
  }
}
