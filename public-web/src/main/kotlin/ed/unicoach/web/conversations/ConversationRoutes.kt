package ed.unicoach.web.conversations

import ed.unicoach.auth.AuthService
import ed.unicoach.coaching.CoachingService
import ed.unicoach.coaching.ConvoUpdate
import ed.unicoach.coaching.DeleteConvoResult
import ed.unicoach.coaching.GetConvoResult
import ed.unicoach.coaching.UpdateConvoResult
import ed.unicoach.db.models.ArchiveScope
import ed.unicoach.db.models.ConvoId
import ed.unicoach.db.models.ConvoWithActivity
import ed.unicoach.db.models.StudentId
import ed.unicoach.student.AccountRoleService
import ed.unicoach.web.WebSessionConfig
import ed.unicoach.web.auth.CONVERSATIONS_PATH
import ed.unicoach.web.auth.WebCaller
import ed.unicoach.web.auth.getStudent
import ed.unicoach.web.auth.postStudent
import ed.unicoach.web.chat.pathConvoId
import ed.unicoach.web.common.http.canonicalUuidOrNull
import ed.unicoach.web.common.http.respondSeeOther
import ed.unicoach.web.formField
import ed.unicoach.web.render.ARCHIVED_QUERY_PARAM
import ed.unicoach.web.render.CONVERSATION_ID_PARAM
import ed.unicoach.web.render.ConversationsArrival
import ed.unicoach.web.render.DELETED_LIST_PATH
import ed.unicoach.web.render.DELETED_QUERY_PARAM
import ed.unicoach.web.render.DELETED_QUERY_VALUE
import ed.unicoach.web.render.archivedListPath
import ed.unicoach.web.render.respondConversationsPage
import ed.unicoach.web.render.respondDeleteConversationPage
import ed.unicoach.web.render.respondNotFoundPage
import ed.unicoach.web.render.signedInNav
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route

/** One conversation, as a route path. */
private const val CONVERSATION_ROUTE = "$CONVERSATIONS_PATH/{$CONVERSATION_ID_PARAM}"

/**
 * Your conversations (RFC 272): the student's conversations — open, archive,
 * delete — every call in-process (brief 0013 D13), through the `CoachingService`
 * methods the iPhone app's REST routes call. The web adds no rule of its own.
 *
 * | Method + path                      | Does                                                            |
 * | ---------------------------------- | --------------------------------------------------------------- |
 * | `GET /conversations`               | the list ([ArchiveScope.UNARCHIVED]), or the empty state        |
 * | `POST /conversations/{id}/archive` | archive it; 303 to the list with its receipt                    |
 * | `GET /conversations/{id}/delete`   | "Delete this conversation?" for a conversation the student owns |
 * | `POST /conversations/{id}/delete`  | delete it; 303 to the list with its receipt                     |
 *
 * Every one is a student page ([getStudent]/[postStudent]): a supporter has no
 * student conversations, so is sent to their account, as the chat sends one.
 * A conversation that is not the student's own — another's, deleted, never
 * there — is the branded 404 on every id route, which never says whose it was.
 */
internal fun Route.conversationRoutes(
  coachingService: CoachingService,
  accountRoles: AccountRoleService,
  authService: AuthService,
  sessionConfig: WebSessionConfig,
) {
  getStudent(CONVERSATIONS_PATH, authService, sessionConfig, accountRoles) { caller, studentId ->
    val conversations = coachingService.listConvos(studentId, ArchiveScope.UNARCHIVED).getOrThrow()
    call.respondConversationsPage(caller.signedInNav, conversations, call.arrival(coachingService, studentId))
  }

  postStudent("$CONVERSATION_ROUTE/archive", authService, sessionConfig, accountRoles) { caller, studentId ->
    call.archive(coachingService, caller, studentId)
  }

  getStudent("$CONVERSATION_ROUTE/delete", authService, sessionConfig, accountRoles) { caller, studentId ->
    call.showDelete(coachingService, caller, studentId)
  }

  postStudent("$CONVERSATION_ROUTE/delete", authService, sessionConfig, accountRoles) { caller, studentId ->
    call.delete(coachingService, caller, studentId)
  }
}

/**
 * What the redirect that led to the list asked it to say. Each value is read as
 * a KEY from a closed set: the archive receipt renders only for a canonical
 * UUID naming one of the student's own conversations that is archived — its
 * name read from the service, never the URL — and the delete receipt only for
 * `deleted=1`.
 */
private suspend fun ApplicationCall.arrival(
  coachingService: CoachingService,
  studentId: StudentId,
): ConversationsArrival {
  val query = request.queryParameters
  val archived =
    query
      .formField(ARCHIVED_QUERY_PARAM)
      ?.let(::canonicalUuidOrNull)
      ?.let { id -> coachingService.ownConvo(studentId, ConvoId(id)) }
      ?.takeIf { listing -> listing.convo.archivedAt != null }
  return ConversationsArrival(
    archivedName = archived?.convo?.name?.value,
    deleted = query.formField(DELETED_QUERY_PARAM) == DELETED_QUERY_VALUE,
  )
}

/** [convoId] as the student's own conversation, or `null` when it is not one — another's, deleted, or never there. */
private suspend fun CoachingService.ownConvo(
  studentId: StudentId,
  convoId: ConvoId,
): ConvoWithActivity? =
  when (val outcome = getConvo(studentId, convoId).getOrThrow()) {
    is GetConvoResult.Found -> outcome.listing
    GetConvoResult.NotFound -> null
  }

/** `POST /conversations/{id}/archive`: archives it at once, as the iPhone app does, and lands on the list with the receipt. */
private suspend fun ApplicationCall.archive(
  coachingService: CoachingService,
  caller: WebCaller,
  studentId: StudentId,
) {
  val convoId = pathConvoId() ?: return respondNotFoundPage(caller.signedInNav)
  when (val outcome = coachingService.updateConvo(studentId, convoId, ConvoUpdate(archived = true)).getOrThrow()) {
    is UpdateConvoResult.Success -> {
      respondSeeOther(archivedListPath(convoId))
    }

    UpdateConvoResult.NotFound -> {
      respondNotFoundPage(caller.signedInNav)
    }

    // The update names `archived`, so the "at least one field" check cannot
    // refuse it, and it carries no name for the name check to refuse.
    is UpdateConvoResult.ValidationFailure -> {
      error("conversation [${convoId.value}] archive posted no name, yet was refused as [$outcome]")
    }
  }
}

/** `GET /conversations/{id}/delete`: the confirmation, naming the conversation it deletes. */
private suspend fun ApplicationCall.showDelete(
  coachingService: CoachingService,
  caller: WebCaller,
  studentId: StudentId,
) {
  val convoId = pathConvoId() ?: return respondNotFoundPage(caller.signedInNav)
  val listing = coachingService.ownConvo(studentId, convoId) ?: return respondNotFoundPage(caller.signedInNav)
  respondDeleteConversationPage(caller.signedInNav, convoId, listing.convo.name.value)
}

/** `POST /conversations/{id}/delete`: deletes it and lands on the list with the receipt. */
private suspend fun ApplicationCall.delete(
  coachingService: CoachingService,
  caller: WebCaller,
  studentId: StudentId,
) {
  val convoId = pathConvoId() ?: return respondNotFoundPage(caller.signedInNav)
  when (coachingService.deleteConvo(studentId, convoId).getOrThrow()) {
    DeleteConvoResult.Success -> respondSeeOther(DELETED_LIST_PATH)
    DeleteConvoResult.NotFound -> respondNotFoundPage(caller.signedInNav)
  }
}
