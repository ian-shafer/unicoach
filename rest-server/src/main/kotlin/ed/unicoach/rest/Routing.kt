package ed.unicoach.rest

import ed.unicoach.error.FieldError
import ed.unicoach.family.SupporterInviteService
import ed.unicoach.rest.models.ErrorCode
import ed.unicoach.rest.models.ErrorResponse
import ed.unicoach.rest.routing.AppleNotificationRouteHandler
import ed.unicoach.rest.routing.AuthRouteHandler
import ed.unicoach.rest.routing.CoachingUsageRouteHandler
import ed.unicoach.rest.routing.CollegeListRouteHandler
import ed.unicoach.rest.routing.CollegeRouteHandler
import ed.unicoach.rest.routing.ConvoRouteHandler
import ed.unicoach.rest.routing.IntendedSubjectsRouteHandler
import ed.unicoach.rest.routing.MoneyProfileRouteHandler
import ed.unicoach.rest.routing.SharingPermissionRouteHandler
import ed.unicoach.rest.routing.StudentRouteHandler
import ed.unicoach.rest.routing.StudentSupporterRouteHandler
import ed.unicoach.rest.routing.SubjectsRouteHandler
import ed.unicoach.rest.routing.SubscriptionRouteHandler
import ed.unicoach.rest.routing.SupporterInviteRoutes
import ed.unicoach.rest.routing.SupporterRoutes
import ed.unicoach.rest.routing.VocabularyRouteHandler
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import java.util.UUID

/** The uniform 400 shape: `VALIDATION_FAILED` with per-field detail — one value, shared by every route handler. */
suspend fun RoutingContext.respondValidationFailed(fieldErrors: List<FieldError>) {
  call.respond(HttpStatusCode.BadRequest, ErrorResponse(ErrorCode.VALIDATION_FAILED, "Validation failed", fieldErrors))
}

/**
 * The path parameter [name] as a typed id, or null when it is absent or not a
 * canonical UUID. Read from the route's path only: in `call.parameters` a query
 * parameter of the same name wins over the path, so `?linkId=` could redirect a
 * DELETE. `UUID.fromString` also accepts short groups (`1-2-3-4-5`), so only a
 * value that prints back as itself is an id.
 */
fun <T> RoutingContext.pathId(
  name: String,
  wrap: (UUID) -> T,
): T? {
  val raw = call.pathParameters[name] ?: return null
  val id =
    try {
      UUID.fromString(raw)
    } catch (_: IllegalArgumentException) {
      return null
    }
  return if (id.toString() == raw.lowercase()) wrap(id) else null
}

fun Route.rejectUnsupportedMethods(vararg methods: HttpMethod) {
  handle {
    val allowedString = methods.joinToString(", ") { it.value }
    call.response.headers.append(HttpHeaders.Allow, allowedString)
    call.respondText(
      text = "Method Not Allowed. Expected: $allowedString",
      status = HttpStatusCode.MethodNotAllowed,
    )
  }
}

fun Application.configureRouting(
  authService: ed.unicoach.auth.AuthService,
  studentService: ed.unicoach.student.StudentService,
  coachingService: ed.unicoach.coaching.CoachingService,
  sessionConfig: ed.unicoach.rest.auth.SessionConfig,
  emailVerificationService: ed.unicoach.auth.EmailVerificationService,
  emailVerifier: ed.unicoach.auth.EmailVerifier,
  queueService: ed.unicoach.queue.QueueService,
  extractionConfig: ed.unicoach.coaching.extraction.ExtractionConfig,
  collegeListService: ed.unicoach.coaching.collegelist.CollegeListService,
  collegeSearchService: ed.unicoach.college.CollegeSearchService,
  moneyProfileService: ed.unicoach.coaching.moneyprofile.MoneyProfileService,
  budgetService: ed.unicoach.coaching.budget.BudgetService,
  subscriptionService: ed.unicoach.subscriptions.SubscriptionService,
  appleNotificationVerifier: ed.unicoach.appstore.AppleNotificationVerifier,
  vocabularyService: ed.unicoach.vocabulary.VocabularyService,
  subjectVocabularyService: ed.unicoach.coaching.SubjectVocabularyService,
  intendedSubjectsService: ed.unicoach.coaching.IntendedSubjectsService,
  sharingPermissionService: ed.unicoach.coaching.SharingPermissionService,
  supporterInviteService: ed.unicoach.family.SupporterInviteService,
  supporterService: ed.unicoach.family.SupporterService,
) {
  val authRouteHandler = AuthRouteHandler(authService, sessionConfig, emailVerificationService, emailVerifier)
  val studentRouteHandler = StudentRouteHandler(authService, studentService, sessionConfig)
  val convoRouteHandler =
    ConvoRouteHandler(authService, studentService, coachingService, sessionConfig, queueService, extractionConfig)
  val collegeListRouteHandler =
    CollegeListRouteHandler(authService, studentService, collegeListService, sessionConfig)
  val collegeRouteHandler =
    CollegeRouteHandler(authService, studentService, collegeSearchService, sessionConfig)
  val moneyProfileRouteHandler =
    MoneyProfileRouteHandler(authService, studentService, moneyProfileService, sessionConfig)
  val coachingUsageRouteHandler =
    CoachingUsageRouteHandler(authService, studentService, budgetService, sessionConfig)
  val subscriptionRouteHandler =
    SubscriptionRouteHandler(authService, subscriptionService, sessionConfig)
  val appleNotificationRouteHandler =
    AppleNotificationRouteHandler(appleNotificationVerifier, queueService)
  val vocabularyRouteHandler =
    VocabularyRouteHandler(authService, studentService, vocabularyService, sessionConfig)
  val subjectsRouteHandler =
    SubjectsRouteHandler(authService, studentService, subjectVocabularyService, sessionConfig)
  val intendedSubjectsRouteHandler =
    IntendedSubjectsRouteHandler(authService, studentService, intendedSubjectsService, sessionConfig)
  val sharingPermissionRouteHandler =
    SharingPermissionRouteHandler(authService, studentService, sharingPermissionService, sessionConfig)
  val supporterInviteRouteHandler =
    SupporterInviteRoutes(authService, studentService, sessionConfig, supporterInviteService)
  val supporterRouteHandler =
    SupporterRoutes(authService, sessionConfig, supporterService, budgetService)
  val studentSupporterRouteHandler =
    StudentSupporterRouteHandler(authService, studentService, supporterService, sessionConfig)
  routing {
    route("/healthz") {
      get {
        call.respondText(
          text = "{\"status\":\"ok\"}",
          contentType = ContentType.Application.Json,
        )
      }
      rejectUnsupportedMethods(HttpMethod.Get)
    }
    authRouteHandler.registerRoutes(this)
    studentRouteHandler.registerRoutes(this)
    convoRouteHandler.registerRoutes(this)
    collegeListRouteHandler.registerRoutes(this)
    collegeRouteHandler.registerRoutes(this)
    moneyProfileRouteHandler.registerRoutes(this)
    coachingUsageRouteHandler.registerRoutes(this)
    subscriptionRouteHandler.registerRoutes(this)
    appleNotificationRouteHandler.registerRoutes(this)
    vocabularyRouteHandler.registerRoutes(this)
    subjectsRouteHandler.registerRoutes(this)
    intendedSubjectsRouteHandler.registerRoutes(this)
    sharingPermissionRouteHandler.registerRoutes(this)
    supporterInviteRouteHandler.registerRoutes(this)
    supporterRouteHandler.registerRoutes(this)
    studentSupporterRouteHandler.registerRoutes(this)
  }
}
