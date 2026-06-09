package ed.unicoach.email

import com.typesafe.config.Config

class EmailConfig private constructor(
  val defaultFrom: String,
) {
  companion object {
    // Reads email.defaultFrom verbatim; no address validation here (the raw
    // string is validated at first send by EmailService.resolvedFrom).
    fun from(config: Config): Result<EmailConfig> =
      runCatching {
        EmailConfig(defaultFrom = config.getString("email.defaultFrom"))
      }
  }
}
