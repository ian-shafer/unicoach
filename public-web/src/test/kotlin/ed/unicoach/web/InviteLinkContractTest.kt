package ed.unicoach.web

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigResolveOptions
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The half of the supporter-invite link contract the compiler cannot see
 * (RFC 232). `:service` builds the invite link from
 * `supporterInvite.inviteUrlBase`; `public-web` serves it at [INVITE_PATH].
 * Renaming either side alone 404s every invite already sent.
 */
class InviteLinkContractTest {
  private val inviteUrlBase: String =
    ConfigFactory
      .parseResourcesAnySyntax("service.conf")
      .withFallback(ConfigFactory.parseString("""APP_DOMAIN = "example.test", PUBLIC_WEB_PORT = 8082"""))
      .resolve(ConfigResolveOptions.defaults().setAllowUnresolved(true))
      .getString("supporterInvite.inviteUrlBase")

  @Test
  fun `the packaged invite-url default points at the path this module serves`() {
    assertTrue(
      inviteUrlBase.endsWith(INVITE_PATH),
      "service.conf's supporterInvite.inviteUrlBase [$inviteUrlBase] no longer ends at [$INVITE_PATH]: " +
        "every invite link already sent would 404",
    )
  }
}
