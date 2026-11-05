package ed.unicoach.web.render

import ed.unicoach.account.ErasureDisclosure
import io.ktor.server.application.ApplicationCall
import io.ktor.server.html.respondHtml
import kotlinx.html.h1
import kotlinx.html.h2
import kotlinx.html.p
import kotlinx.html.section

/**
 * The Privacy Policy body. The copy is static, but it renders dynamically
 * through [siteLayout] — not from a static HTML file — so it inherits the shared
 * chrome. The marker "Privacy Policy" proves this body rendered.
 */
suspend fun ApplicationCall.respondPrivacyPage(nav: ShellNav = ShellNav.SignedOut) {
  respondHtml {
    siteLayout("Privacy Policy", nav = nav) {
      section("legal") {
        h1 { +"Privacy Policy" }
        p {
          +(
            "This Privacy Policy explains what information unicoach collects, how " +
              "it is used, and the choices you have."
          )
        }

        h2 { +"Information we collect" }
        p {
          +(
            "We collect the account information you provide — such as your name " +
              "and email address — and what you share with your coach: your " +
              "conversations, the details you add to your profile, and the colleges " +
              "on your list."
          )
        }

        h2 { +"How we use information" }
        p {
          +(
            "Your information is used to operate and improve the coaching service. " +
              "We do not sell your personal information. " +
              ErasureDisclosure.AI_PROVIDER_RETENTION
          )
        }

        h2 { +"Your choices" }
        p {
          // Names the iOS control `DeleteAccountControl` ("Delete account",
          // ios-app/UnicoachiOS/SettingsView.swift), as does the minors notice
          // below. Rename or move it and both point users at a control that no
          // longer exists. The kept list is [ErasureDisclosure]'s, shared with
          // the ACCOUNT_ERASED email; that object names the iOS copy.
          +(
            "You can delete your account in the app: Settings › Delete account. " +
              "Your account is deleted at once, everything in it is erased within " +
              "${ErasureDisclosure.ERASURE_WINDOW_DAYS} days, and we email your verified address when it is done. " +
              ErasureDisclosure.KEPT + " " +
              "You may request access to your account information by contacting us."
          )
        }
        p {
          // The notice to minors Cal. B&P 22581 requires, with the statute's
          // own caveat; the clause after the colon names the two real reasons
          // removal is not complete (RFC 266 D-A).
          +(
            "If you are under 18, you can remove anything you have posted to " +
              "unicoach by deleting your account in the app (Settings › Delete " +
              "account) or by contacting us. Removal does not ensure complete or " +
              "comprehensive removal: a parent you connected keeps their own " +
              "conversations, and the records listed above are kept."
          )
        }
      }
    }
  }
}
