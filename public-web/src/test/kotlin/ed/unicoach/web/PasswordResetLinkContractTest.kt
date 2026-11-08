package ed.unicoach.web

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigResolveOptions
import ed.unicoach.auth.PasswordResetEmailContext
import ed.unicoach.auth.PasswordResetEmailRenderer
import ed.unicoach.common.json.asJson
import ed.unicoach.web.auth.RESET_PASSWORD_PATH
import ed.unicoach.web.render.TOKEN_FIELD
import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The half of the password-reset link contract the compiler cannot see (RFC
 * 276). `:service` builds the link from `passwordReset.resetUrlBase` and writes
 * the token's query key in `PasswordResetEmailRenderer`; `public-web` serves it
 * at [RESET_PASSWORD_PATH] and reads the token from [TOKEN_FIELD]. Renaming
 * either side alone kills every link already mailed.
 */
class PasswordResetLinkContractTest {
  private val resetUrlBase: String =
    ConfigFactory
      .parseResourcesAnySyntax("service.conf")
      .withFallback(ConfigFactory.parseString("""APP_DOMAIN = "example.test", PUBLIC_WEB_PORT = 8082"""))
      .resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true))
      .getString("passwordReset.resetUrlBase")

  @Test
  fun `the packaged reset link lands on the path and query key this module serves`() {
    val body =
      PasswordResetEmailRenderer(resetUrlBase)
        .render(PasswordResetEmailContext(resetToken = "abc").asJson())
        .getOrThrow()
        .body
        .value

    val link = Url(body.lines().single { it.startsWith("http") })

    assertEquals(RESET_PASSWORD_PATH, link.encodedPath, "every reset link already mailed would 404")
    assertEquals("abc", link.parameters[TOKEN_FIELD], "every reset link already mailed would be read as carrying no token")
  }
}
