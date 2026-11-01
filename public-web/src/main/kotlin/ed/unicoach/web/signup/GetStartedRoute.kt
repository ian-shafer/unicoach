package ed.unicoach.web.signup

import ed.unicoach.auth.AuthService
import ed.unicoach.student.StudentService
import ed.unicoach.web.WebSessionConfig
import ed.unicoach.web.auth.CHAT_PATH
import ed.unicoach.web.auth.STUDENT_SIGNUP_PATH
import ed.unicoach.web.auth.getVerified
import ed.unicoach.web.auth.respondGateRedirect
import ed.unicoach.web.render.respondGetStartedPage
import ed.unicoach.web.render.signedInNav
import io.ktor.server.routing.Route

/**
 * `GET /get-started` — the student signup page's reserved path (RFC 262 D-B),
 * where the chat sends a verified caller with no student row.
 *
 * Its own route, not the chat's: `web/06` builds the real form here, and
 * replaces this one function to do it. Until then it renders the placeholder,
 * and a caller who already has a student row has nothing to set up, so is sent
 * on to the chat.
 */
internal fun Route.getStartedRoute(
  studentService: StudentService,
  authService: AuthService,
  sessionConfig: WebSessionConfig,
) {
  getVerified(STUDENT_SIGNUP_PATH, authService, sessionConfig) { caller ->
    if (studentService.getStudentForUser(caller.user.id).getOrThrow() != null) {
      call.respondGateRedirect(CHAT_PATH)
    } else {
      call.respondGetStartedPage(caller.signedInNav)
    }
  }
}
