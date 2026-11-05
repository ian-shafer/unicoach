package ed.unicoach.web.details

import ed.unicoach.auth.AuthService
import ed.unicoach.auth.SessionMetadataService
import ed.unicoach.coaching.moneyprofile.FieldUpdate
import ed.unicoach.coaching.moneyprofile.GetMoneyProfileResult
import ed.unicoach.coaching.moneyprofile.MoneyProfileService
import ed.unicoach.coaching.moneyprofile.MoneyProfileUpdate
import ed.unicoach.coaching.moneyprofile.UpsertMoneyProfileResult
import ed.unicoach.db.models.DependencyStatus
import ed.unicoach.db.models.IncomeBand
import ed.unicoach.db.models.LivingArrangement
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.session.MoneyProfileDraft
import ed.unicoach.student.AccountRoleService
import ed.unicoach.vocabulary.VocabularyService
import ed.unicoach.web.DraftSave
import ed.unicoach.web.UnreadableControl
import ed.unicoach.web.UnreadableMode
import ed.unicoach.web.WebSessionConfig
import ed.unicoach.web.auth.SIGN_IN_PATH
import ed.unicoach.web.auth.WebCaller
import ed.unicoach.web.auth.YOUR_DETAILS_PATH
import ed.unicoach.web.auth.getStudent
import ed.unicoach.web.auth.postStudent
import ed.unicoach.web.auth.respondGateRedirect
import ed.unicoach.web.clearDraft
import ed.unicoach.web.common.http.respondSeeOther
import ed.unicoach.web.draftFor
import ed.unicoach.web.formParametersOrNull
import ed.unicoach.web.oneValue
import ed.unicoach.web.render.DETAILS_ACTION_PARAM
import ed.unicoach.web.render.DETAILS_FIELD_PARAM
import ed.unicoach.web.render.DetailsAction
import ed.unicoach.web.render.DetailsField
import ed.unicoach.web.render.DetailsOptions
import ed.unicoach.web.render.MONEY_PROFILE_DRAFT
import ed.unicoach.web.render.SAVED_QUERY_PARAM
import ed.unicoach.web.render.YourDetailsState
import ed.unicoach.web.render.optionsOf
import ed.unicoach.web.render.picks
import ed.unicoach.web.render.respondYourDetailsPage
import ed.unicoach.web.render.signedInNav
import ed.unicoach.web.saveDraft
import ed.unicoach.web.unreadablePosting
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import org.slf4j.LoggerFactory

/** Your details' own log: a write it could not make is otherwise only a sentence on a page. */
private val detailsLog = LoggerFactory.getLogger("ed.unicoach.web.YourDetails")

/** What Your details reads and writes through (RFC 270), held together so the two routes take one value. */
internal class YourDetailsServices(
  val accountRoles: AccountRoleService,
  val moneyProfiles: MoneyProfileService,
  val vocabularies: VocabularyService,
)

/**
 * Your details (RFC 270): a student's four money answers, each given, declined
 * or removed on its own, at any time.
 *
 * | Method + path           | Does                                                     |
 * | ----------------------- | -------------------------------------------------------- |
 * | `GET /account/details`  | the page, with any drafted picks                         |
 * | `POST /account/details` | one field: save a pick, decline it, or remove the answer |
 *
 * Both are student pages ([getStudent]/[postStudent]): a supporter has no
 * money profile, so is sent home, as the chat sends one.
 */
internal fun Route.yourDetailsRoutes(
  services: YourDetailsServices,
  sessionMetadata: SessionMetadataService,
  authService: AuthService,
  sessionConfig: WebSessionConfig,
) {
  getStudent(YOUR_DETAILS_PATH, authService, sessionConfig, services.accountRoles) { caller, studentId ->
    // A KEY from the closed set, as a `WebNotice` is: an unknown value renders
    // nothing, so no text from a URL reaches the page.
    val saved = DetailsField.fromKey(call.request.queryParameters[SAVED_QUERY_PARAM])
    call.respondDetails(services, caller, studentId, YourDetailsState.Open(saved), caller.draftedPicks())
  }

  postStudent(YOUR_DETAILS_PATH, authService, sessionConfig, services.accountRoles) { caller, studentId ->
    // Not this page's submission: the page as it stands at 400, nothing written
    // and no draft — there is no reading of it to keep (the change-email
    // precedent). A body that is not a form at all is logged by
    // `formParametersOrNull`.
    val posting =
      call.formParametersOrNull()?.detailsPosting()
        ?: return@postStudent call.respondDetails(services, caller, studentId, YourDetailsState.Unreadable, caller.draftedPicks())
    when (posting) {
      is DetailsPosting.Unreadable -> call.respondUnreadable(services, caller, studentId, posting.at)
      is DetailsPosting.BlankSave -> call.respondBlankSave(services, caller, studentId, posting.field)
      is DetailsPosting.Write -> call.write(services, sessionMetadata, caller, studentId, posting)
    }
  }
}

/** The answer to a posting this page's forms did not send: logged, then the page as it stands at 400. */
private suspend fun ApplicationCall.respondUnreadable(
  services: YourDetailsServices,
  caller: WebCaller,
  studentId: StudentId,
  at: UnreadableControl,
) {
  detailsLog.unreadablePosting("details", at)
  respondDetails(services, caller, studentId, YourDetailsState.Unreadable, caller.draftedPicks())
}

/** The answer to a `save` of [field]'s blank option: the page at 400 asking for a pick, nothing written. */
private suspend fun ApplicationCall.respondBlankSave(
  services: YourDetailsServices,
  caller: WebCaller,
  studentId: StudentId,
  field: DetailsField,
) {
  respondDetails(services, caller, studentId, YourDetailsState.BlankSave(field), caller.draftedPicks())
}

/** The picks the session's draft holds, by field. */
private fun WebCaller.draftedPicks(): Map<DetailsField, String> = draftFor(MONEY_PROFILE_DRAFT)?.picks().orEmpty()

/**
 * Makes [posting]'s one-field write. A success empties that field in the draft
 * and redirects back to it with its receipt; a failure — the write's, or a
 * writer that found no student — is logged and the page renders at 503 with the
 * failure said under the field, a `save`'s pick kept in the draft.
 */
private suspend fun ApplicationCall.write(
  services: YourDetailsServices,
  sessionMetadata: SessionMetadataService,
  caller: WebCaller,
  studentId: StudentId,
  posting: DetailsPosting.Write,
) {
  if (services.attemptWrite(studentId, posting)) {
    respondWritten(sessionMetadata, caller, posting.field)
  } else {
    respondNotWritten(services, sessionMetadata, caller, studentId, posting)
  }
}

/** Makes [posting]'s one-field write: `true` once written, `false` once the failure — or a writer that found no student — is logged. */
private suspend fun YourDetailsServices.attemptWrite(
  studentId: StudentId,
  posting: DetailsPosting.Write,
): Boolean =
  moneyProfiles.upsert(studentId, posting.update).fold(
    onSuccess = { outcome -> outcome.wasWritten(studentId, posting) },
    onFailure = { cause ->
      logNotWritten(studentId, posting, cause)
      false
    },
  )

/** Whether this outcome wrote [posting]; a writer that found no student is logged first. */
private fun UpsertMoneyProfileResult.wasWritten(
  studentId: StudentId,
  posting: DetailsPosting.Write,
): Boolean =
  when (this) {
    is UpsertMoneyProfileResult.Success -> {
      true
    }

    UpsertMoneyProfileResult.StudentNotFound -> {
      detailsLog.warn(
        "details [{}] of [{}] not written for student [{}]: the writer found no student",
        posting.action.key,
        posting.field.key,
        studentId.value,
      )
      false
    }
  }

/** Logs a write of [posting] that failed with [cause]. */
private fun logNotWritten(
  studentId: StudentId,
  posting: DetailsPosting.Write,
  cause: Throwable,
) {
  detailsLog.warn(
    "details [{}] of [{}] not written for student [{}]",
    posting.action.key,
    posting.field.key,
    studentId.value,
    cause,
  )
}

/**
 * The answer to a write that succeeded: [field]'s drafted pick is emptied
 * ([emptyDraftedPick]), then the family is sent back to the field with its
 * receipt.
 */
private suspend fun ApplicationCall.respondWritten(
  sessionMetadata: SessionMetadataService,
  caller: WebCaller,
  field: DetailsField,
) {
  emptyDraftedPick(sessionMetadata, caller, field)
  respondSeeOther("$YOUR_DETAILS_PATH?$SAVED_QUERY_PARAM=${field.key}#${field.key}")
}

/** Empties [field]'s drafted pick, if it had one; the whole draft is cleared once no field holds one. */
private suspend fun ApplicationCall.emptyDraftedPick(
  sessionMetadata: SessionMetadataService,
  caller: WebCaller,
  field: DetailsField,
) {
  val draft = caller.draftFor(MONEY_PROFILE_DRAFT) ?: return
  if (field.draftedIn(draft) == null) return
  val rest = field.withPick(draft, null)
  if (rest == MoneyProfileDraft.getDefaultInstance()) {
    clearDraft(sessionMetadata, caller, MONEY_PROFILE_DRAFT)
  } else {
    keepRestOfDraft(sessionMetadata, caller, rest)
  }
}

/**
 * Writes [rest] — the draft less a field that just saved — as [clearDraft]
 * would drop it: BEST EFFORT, because the write the POST made has committed and
 * nothing here may report it as anything else. A pick left behind is shown as
 * "Not saved yet." on the next GET, which the family can see and retry.
 */
private suspend fun ApplicationCall.keepRestOfDraft(
  sessionMetadata: SessionMetadataService,
  caller: WebCaller,
  rest: MoneyProfileDraft,
) {
  when (val outcome = saveDraft(sessionMetadata, caller, MONEY_PROFILE_DRAFT, rest)) {
    // Kept, or gone with the session that held it.
    DraftSave.Kept, DraftSave.SessionGone -> {
      Unit
    }

    is DraftSave.NotKept -> {
      detailsLog.warn(
        "details draft for session [{}] not shrunk after its field saved ({}); the stale pick stays until that field next saves",
        caller.session.id.value,
        outcome.why,
      )
    }
  }
}

/**
 * The answer to a write that did not happen: the page at 503 with the failure
 * said under the field. A `save`'s pick is first kept in the draft
 * ([keepFailedPick]) and stays selected; a session gone meanwhile is sent to
 * sign in instead.
 */
private suspend fun ApplicationCall.respondNotWritten(
  services: YourDetailsServices,
  sessionMetadata: SessionMetadataService,
  caller: WebCaller,
  studentId: StudentId,
  posting: DetailsPosting.Write,
) {
  when (posting) {
    is DetailsPosting.Write.Save -> {
      val notKept =
        when (val kept = keepFailedPick(sessionMetadata, caller, posting)) {
          DraftSave.Kept -> null
          is DraftSave.NotKept -> kept.why
          DraftSave.SessionGone -> return respondGateRedirect(SIGN_IN_PATH)
        }
      val notSaved = YourDetailsState.NotSaved(posting.field, notKept)
      respondDetails(services, caller, studentId, notSaved, caller.draftedPicks() + (posting.field to posting.pick))
    }

    is DetailsPosting.Write.Decline, is DetailsPosting.Write.Remove -> {
      respondDetails(services, caller, studentId, YourDetailsState.NotSaved(posting.field, null), caller.draftedPicks())
    }
  }
}

/** Keeps a failed [save]'s pick in the session draft, merged with the picks already there. */
private suspend fun ApplicationCall.keepFailedPick(
  sessionMetadata: SessionMetadataService,
  caller: WebCaller,
  save: DetailsPosting.Write.Save,
): DraftSave =
  saveDraft(
    sessionMetadata,
    caller,
    MONEY_PROFILE_DRAFT,
    save.field.withPick(caller.draftFor(MONEY_PROFILE_DRAFT) ?: MoneyProfileDraft.getDefaultInstance(), save.pick),
  )

/** The page, over the stored profile and the served income and state vocabularies. */
private suspend fun ApplicationCall.respondDetails(
  services: YourDetailsServices,
  caller: WebCaller,
  studentId: StudentId,
  state: YourDetailsState,
  pending: Map<DetailsField, String>,
) {
  val profile =
    when (val found = services.moneyProfiles.getForStudent(studentId).getOrThrow()) {
      is GetMoneyProfileResult.Found -> found.profile
      GetMoneyProfileResult.NotFound -> null
    }
  val vocabularies =
    services.vocabularies
      .publishedVocabularies()
      .getOrThrow()
      .vocabularies
  val options =
    DetailsOptions(
      income = vocabularies.optionsOf(VocabularyService.INCOME_BANDS),
      residency = vocabularies.optionsOf(VocabularyService.RESIDENCY_STATES),
    )
  respondYourDetailsPage(caller.signedInNav, state, profile, pending, options)
}

/** A posted form, read as one of the three things this page's forms send, or as not one of them. */
private sealed interface DetailsPosting {
  /** One field's write, one subtype per [action]: [update] touches [field] alone. */
  sealed interface Write : DetailsPosting {
    val field: DetailsField
    val action: DetailsAction
    val update: MoneyProfileUpdate

    /** A `save` of [pick], one of [field]'s options, which [update] sets. */
    data class Save(
      override val field: DetailsField,
      val pick: String,
      override val update: MoneyProfileUpdate,
    ) : Write {
      override val action: DetailsAction get() = DetailsAction.SAVE
    }

    /** A `decline`: no pick is read. */
    data class Decline(
      override val field: DetailsField,
    ) : Write {
      override val action: DetailsAction get() = DetailsAction.DECLINE
      override val update: MoneyProfileUpdate get() = this.field.statusUpdate(FieldUpdate.Decline)
    }

    /** A `remove`: no pick is read. */
    data class Remove(
      override val field: DetailsField,
    ) : Write {
      override val action: DetailsAction get() = DetailsAction.REMOVE
      override val update: MoneyProfileUpdate get() = this.field.statusUpdate(FieldUpdate.Clear)
    }
  }

  /** A `save` posted with the blank option. */
  data class BlankSave(
    val field: DetailsField,
  ) : DetailsPosting

  /**
   * The control the reading stopped [at]. A value that is not an option is an
   * unknown field or action, or a pick the field does not offer.
   */
  data class Unreadable(
    val at: UnreadableControl,
  ) : DetailsPosting
}

/**
 * The POST grammar: exactly one `field`, exactly one `action`, and for `save`
 * exactly one value under the field's own control, which is blank or one of
 * that control's options. `decline` and `remove` read no pick.
 */
private fun Parameters.detailsPosting(): DetailsPosting {
  val field =
    DetailsField.fromKey(oneValue(DETAILS_FIELD_PARAM) { return DetailsPosting.Unreadable(it) })
      ?: return notAnOption(DETAILS_FIELD_PARAM)
  val action =
    DetailsAction.fromKey(oneValue(DETAILS_ACTION_PARAM) { return DetailsPosting.Unreadable(it) })
      ?: return notAnOption(DETAILS_ACTION_PARAM)
  return when (action) {
    DetailsAction.SAVE -> {
      val pick = oneValue(field.key) { return DetailsPosting.Unreadable(it) }
      if (pick.isEmpty()) return DetailsPosting.BlankSave(field)
      val update = field.pickUpdate(pick) ?: return notAnOption(field.key)
      DetailsPosting.Write.Save(field, pick, update)
    }

    DetailsAction.DECLINE -> {
      DetailsPosting.Write.Decline(field)
    }

    DetailsAction.REMOVE -> {
      DetailsPosting.Write.Remove(field)
    }
  }
}

/** [control] posted a value none of its options carries. */
private fun notAnOption(control: String): DetailsPosting.Unreadable =
  DetailsPosting.Unreadable(UnreadableControl(control, UnreadableMode.NOT_AN_OPTION))

/** The write that sets this field to [pick], or `null` when [pick] is none of the field's options. */
private fun DetailsField.pickUpdate(pick: String): MoneyProfileUpdate? =
  when (this) {
    DetailsField.INCOME_BAND -> {
      IncomeBand.fromValue(pick)?.let { MoneyProfileUpdate(income = FieldUpdate.Set(it)) }
    }

    DetailsField.RESIDENCY_STATE -> {
      pick.takeIf { it in MoneyProfileService.USPS_STATE_CODES }?.let { MoneyProfileUpdate(residency = FieldUpdate.Set(it)) }
    }

    DetailsField.LIVING_PLAN -> {
      LivingArrangement.fromValue(pick)?.let { MoneyProfileUpdate(living = FieldUpdate.Set(it)) }
    }

    DetailsField.DEPENDENCY -> {
      DependencyStatus.fromValue(pick)?.let { MoneyProfileUpdate(dependency = FieldUpdate.Set(it)) }
    }
  }

/** The write that declines or clears this field alone. */
private fun DetailsField.statusUpdate(update: FieldUpdate<Nothing>): MoneyProfileUpdate =
  when (this) {
    DetailsField.INCOME_BAND -> MoneyProfileUpdate(income = update)
    DetailsField.RESIDENCY_STATE -> MoneyProfileUpdate(residency = update)
    DetailsField.LIVING_PLAN -> MoneyProfileUpdate(living = update)
    DetailsField.DEPENDENCY -> MoneyProfileUpdate(dependency = update)
  }
