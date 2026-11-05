package ed.unicoach.web.colleges

import ed.unicoach.auth.SessionMetadataService
import ed.unicoach.coaching.collegelist.GetEntryResult
import ed.unicoach.coaching.collegelist.LivingPlanUpdate
import ed.unicoach.coaching.collegelist.RemoveEntryResult
import ed.unicoach.coaching.collegelist.UpdateEntryResult
import ed.unicoach.db.models.CollegeListEntryId
import ed.unicoach.db.models.CollegeListEntryStatus
import ed.unicoach.db.models.StudentId
import ed.unicoach.db.session.CollegeEntryDraft
import ed.unicoach.web.DraftNotKept
import ed.unicoach.web.DraftSave
import ed.unicoach.web.UnreadableControl
import ed.unicoach.web.UnreadableMode
import ed.unicoach.web.auth.COLLEGES_PATH
import ed.unicoach.web.auth.SIGN_IN_PATH
import ed.unicoach.web.auth.WebCaller
import ed.unicoach.web.auth.respondGateRedirect
import ed.unicoach.web.clearDraft
import ed.unicoach.web.common.http.respondSeeOther
import ed.unicoach.web.draftFor
import ed.unicoach.web.formParametersOrNull
import ed.unicoach.web.oneValue
import ed.unicoach.web.render.COLLEGE_ENTRY_DRAFT
import ed.unicoach.web.render.CollegeEntryState
import ed.unicoach.web.render.EntryForm
import ed.unicoach.web.render.EntryView
import ed.unicoach.web.render.ListReceipt
import ed.unicoach.web.render.MOVED_LIST_PATH
import ed.unicoach.web.render.REASONS_PARAM
import ed.unicoach.web.render.RemoveCollegeState
import ed.unicoach.web.render.STATUS_PARAM
import ed.unicoach.web.render.VERSION_PARAM
import ed.unicoach.web.render.collegeListPath
import ed.unicoach.web.render.isFor
import ed.unicoach.web.render.respondCollegeEntryPage
import ed.unicoach.web.render.respondNotFoundPage
import ed.unicoach.web.render.respondRemoveCollegePage
import ed.unicoach.web.render.signedInNav
import ed.unicoach.web.saveDraft
import ed.unicoach.web.unreadablePosting
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall

// One entry of My colleges (RFC 271): its edit page and save, its remove
// confirmation and remove. Registered by `collegeListRoutes`.

/** One of this student's active entries as the edit and remove pages show it, and its stored values. */
private data class StoredEntry(
  val view: EntryView,
  val form: EntryForm,
)

/**
 * [entryId] as this student's active entry, its college's name beside it, or
 * `null` when it is not one — another student's, removed, or never there.
 */
private suspend fun CollegeListServices.readEntry(
  studentId: StudentId,
  entryId: CollegeListEntryId,
): StoredEntry? {
  val found =
    when (val outcome = collegeList.getForStudent(studentId, entryId).getOrThrow()) {
      is GetEntryResult.Found -> outcome
      GetEntryResult.NotFound -> return null
    }
  val collegeId = found.entry.collegeId
  // FK-guaranteed present (ON DELETE RESTRICT); a miss is corruption worth naming.
  val collegeName =
    checkNotNull(collegeList.listNames(listOf(collegeId)).getOrThrow()[collegeId]) {
      "college [${collegeId.value}] missing for list entry [${entryId.value}]"
    }
  return StoredEntry(
    view = EntryView(entryId, collegeName, found.entry.version, found.supportingObservations),
    form = EntryForm(found.entry.status, found.entry.reasons.orEmpty(), unsaved = false),
  )
}

/** This entry's drafted edit, if the session holds one for it: what a failed save left (D-A). */
private fun WebCaller.draftedForm(
  entryId: CollegeListEntryId,
  stored: EntryForm,
): EntryForm? {
  val draft = draftFor(COLLEGE_ENTRY_DRAFT)?.takeIf { it.isFor(entryId) } ?: return null
  return EntryForm(draftedStatus(entryId, draft.status, stored.status), draft.reasons, unsaved = true)
}

/**
 * The drafted [status], read back. A draft holds only what a readable posting
 * sent, so it is always one of the four; one that is not — a stale value from a
 * renamed status — is logged and falls back to [stored] rather than checking no
 * radio. The raw value is one of our own status words, never a family's.
 */
private fun WebCaller.draftedStatus(
  entryId: CollegeListEntryId,
  status: String,
  stored: CollegeListEntryStatus,
): CollegeListEntryStatus {
  CollegeListEntryStatus.fromValue(status)?.let { return it }
  collegesLog.warn(
    "college entry [{}] draft for session [{}] holds unknown status [{}]; showing the stored status [{}]",
    entryId.value,
    session.id.value,
    status,
    stored.value,
  )
  return stored
}

/** `GET /colleges/{entryId}`: the edit page, starting on this entry's drafted edit when there is one. */
internal suspend fun ApplicationCall.showEntry(
  services: CollegeListServices,
  caller: WebCaller,
  studentId: StudentId,
) {
  val entryId = pathEntryId() ?: return respondNotFoundPage(caller.signedInNav)
  val stored = services.readEntry(studentId, entryId) ?: return respondNotFoundPage(caller.signedInNav)
  respondCollegeEntryPage(caller.signedInNav, CollegeEntryState.Open, stored.view, caller.draftedForm(entryId, stored.form) ?: stored.form)
}

/**
 * `POST /colleges/{entryId}`: saves the posted status and reasons at the
 * posted version. Living plan is on no screen, so every save keeps it.
 *
 * A save that met a list changed elsewhere — a stale version, or an entry
 * removed meanwhile — keeps the typing in the draft and lands on the list with
 * the moved notice (D-A); reopening the entry offers it over the CURRENT
 * version, so saving it is a deliberate second act.
 */
internal suspend fun ApplicationCall.saveEntry(
  services: CollegeListServices,
  sessionMetadata: SessionMetadataService,
  caller: WebCaller,
  studentId: StudentId,
) {
  val entryId = pathEntryId() ?: return respondNotFoundPage(caller.signedInNav)
  val save =
    when (val posting = formParametersOrNull()?.entryPosting()) {
      null, is EntryPosting.Unreadable -> return respondEntryUnreadable(services, caller, studentId, entryId, posting?.at)
      is EntryPosting.Save -> posting
    }
  val outcome =
    services.collegeList
      .updateEntry(
        studentId,
        entryId,
        save.version,
        save.status,
        save.reasonsToStore,
        LivingPlanUpdate.Keep,
        addObservationIds = emptyList(),
      ).getOrElse { cause -> return respondSaveFault(services, sessionMetadata, caller, studentId, entryId, save, cause) }
  when (outcome) {
    is UpdateEntryResult.Success -> {
      respondSaved(sessionMetadata, caller, entryId)
    }

    UpdateEntryResult.VersionConflict, UpdateEntryResult.NotFound -> {
      respondMovedKeepingTyping(sessionMetadata, caller, entryId, save)
    }

    UpdateEntryResult.InvalidReasons -> {
      respondNotSaved(services, sessionMetadata, caller, studentId, entryId, save) {
        CollegeEntryState.InvalidReasons(it)
      }
    }

    // The save posts no observations, so the citation check cannot refuse it.
    is UpdateEntryResult.ObservationNotFound -> {
      refusedUnexpectedly(entryId, outcome)
    }
  }
}

/**
 * Not this page's submission: the page as stored at 400, nothing written and no
 * draft — there is no reading of it to keep. [at] is the control that was not
 * one the page sent, or `null` for no form body at all.
 */
private suspend fun ApplicationCall.respondEntryUnreadable(
  services: CollegeListServices,
  caller: WebCaller,
  studentId: StudentId,
  entryId: CollegeListEntryId,
  at: UnreadableControl?,
) {
  collegesLog.unreadablePosting("college entry", at)
  val stored = services.readEntry(studentId, entryId) ?: return respondNotFoundPage(caller.signedInNav)
  respondCollegeEntryPage(caller.signedInNav, CollegeEntryState.Unreadable, stored.view, stored.form)
}

/** The answer to a save that wrote: the entry's draft dropped, then the list with the saved receipt. */
private suspend fun ApplicationCall.respondSaved(
  sessionMetadata: SessionMetadataService,
  caller: WebCaller,
  entryId: CollegeListEntryId,
) {
  clearEntryDraft(sessionMetadata, caller, entryId)
  respondSeeOther(collegeListPath(ListReceipt.SAVED, entryId))
}

/** The answer to a save the service faulted on: [cause] logged, then the edit page with the not-saved message. */
private suspend fun ApplicationCall.respondSaveFault(
  services: CollegeListServices,
  sessionMetadata: SessionMetadataService,
  caller: WebCaller,
  studentId: StudentId,
  entryId: CollegeListEntryId,
  save: EntryPosting.Save,
  cause: Throwable,
) {
  collegesLog.warn("college entry [{}] not saved for student [{}]", entryId.value, studentId.value, cause)
  respondNotSaved(services, sessionMetadata, caller, studentId, entryId, save) { CollegeEntryState.NotSaved(it) }
}

/** A save refused as [outcome], which nothing it posts can earn: a broken service contract, not a family's answer. */
private fun refusedUnexpectedly(
  entryId: CollegeListEntryId,
  outcome: UpdateEntryResult,
): Nothing = error("college entry [${entryId.value}] save posted no observations, yet was refused as [$outcome]")

/** The answer to a save that met a moved list: the typing kept in the draft, then the list with the moved notice. */
private suspend fun ApplicationCall.respondMovedKeepingTyping(
  sessionMetadata: SessionMetadataService,
  caller: WebCaller,
  entryId: CollegeListEntryId,
  save: EntryPosting.Save,
) {
  when (val kept = keepTyping(sessionMetadata, caller, entryId, save)) {
    DraftSave.Kept -> {
      Unit
    }

    is DraftSave.NotKept -> {
      // The list's notice cannot say it: the redirect is the answer, and the
      // typing is lost with the page the family left.
      collegesLog.warn("college entry [{}] typing not kept after a moved list ({})", entryId.value, kept.why)
    }

    DraftSave.SessionGone -> {
      return respondGateRedirect(SIGN_IN_PATH)
    }
  }
  respondSeeOther(MOVED_LIST_PATH)
}

/**
 * The answer to a save that did not write: the typing kept in the draft, then
 * the edit page over the entry as it now stands, in [state] made from whether
 * the draft was kept. An entry gone meanwhile lands on the list as moved.
 */
private suspend fun ApplicationCall.respondNotSaved(
  services: CollegeListServices,
  sessionMetadata: SessionMetadataService,
  caller: WebCaller,
  studentId: StudentId,
  entryId: CollegeListEntryId,
  save: EntryPosting.Save,
  state: (DraftNotKept?) -> CollegeEntryState,
) {
  val notKept =
    when (val kept = keepTyping(sessionMetadata, caller, entryId, save)) {
      DraftSave.Kept -> null
      is DraftSave.NotKept -> kept.why
      DraftSave.SessionGone -> return respondGateRedirect(SIGN_IN_PATH)
    }
  val stored = services.readEntry(studentId, entryId) ?: return respondSeeOther(MOVED_LIST_PATH)
  respondCollegeEntryPage(caller.signedInNav, state(notKept), stored.view, EntryForm(save.status, save.reasons, unsaved = true))
}

/** Keeps [save]'s typing as the draft, replacing any other entry's. Strings as posted (RFC 265). */
private suspend fun ApplicationCall.keepTyping(
  sessionMetadata: SessionMetadataService,
  caller: WebCaller,
  entryId: CollegeListEntryId,
  save: EntryPosting.Save,
): DraftSave =
  saveDraft(
    sessionMetadata,
    caller,
    COLLEGE_ENTRY_DRAFT,
    CollegeEntryDraft
      .newBuilder()
      .setEntryId(entryId.asString)
      .setStatus(save.status.value)
      .setReasons(save.reasons)
      .build(),
  )

/** Drops the draft if it is [entryId]'s, after that entry saved or was removed; another entry's stays. */
private suspend fun ApplicationCall.clearEntryDraft(
  sessionMetadata: SessionMetadataService,
  caller: WebCaller,
  entryId: CollegeListEntryId,
) {
  if (caller.draftFor(COLLEGE_ENTRY_DRAFT)?.isFor(entryId) == true) clearDraft(sessionMetadata, caller, COLLEGE_ENTRY_DRAFT)
}

// --- remove --------------------------------------------------------------------------

/** `GET /colleges/{entryId}/remove`: the confirmation, carrying the version read for it. */
internal suspend fun ApplicationCall.showRemove(
  services: CollegeListServices,
  caller: WebCaller,
  studentId: StudentId,
) {
  val entryId = pathEntryId() ?: return respondNotFoundPage(caller.signedInNav)
  val stored = services.readEntry(studentId, entryId) ?: return respondNotFoundPage(caller.signedInNav)
  respondRemoveCollegePage(caller.signedInNav, RemoveCollegeState.OPEN, stored.view)
}

/**
 * `POST /colleges/{entryId}/remove`: removes the entry at the posted version.
 * A list changed elsewhere lands on the list with the moved notice and keeps
 * the entry.
 */
internal suspend fun ApplicationCall.removeEntry(
  services: CollegeListServices,
  sessionMetadata: SessionMetadataService,
  caller: WebCaller,
  studentId: StudentId,
) {
  val entryId = pathEntryId() ?: return respondNotFoundPage(caller.signedInNav)
  val version =
    when (val posting = formParametersOrNull()?.removePosting()) {
      null, is RemovePosting.Unreadable -> {
        collegesLog.unreadablePosting("college remove", posting?.at)
        return respondRemoveAsItStands(services, caller, studentId, entryId, RemoveCollegeState.UNREADABLE)
      }

      is RemovePosting.Remove -> {
        posting.version
      }
    }
  services.collegeList.removeFromList(studentId, entryId, version).fold(
    onSuccess = { outcome ->
      when (outcome) {
        is RemoveEntryResult.Success -> {
          clearEntryDraft(sessionMetadata, caller, entryId)
          respondSeeOther(COLLEGES_PATH)
        }

        RemoveEntryResult.VersionConflict, RemoveEntryResult.NotFound -> {
          respondSeeOther(MOVED_LIST_PATH)
        }
      }
    },
    onFailure = { cause ->
      collegesLog.warn("college entry [{}] not removed for student [{}]", entryId.value, studentId.value, cause)
      respondRemoveAsItStands(services, caller, studentId, entryId, RemoveCollegeState.NOT_SAVED)
    },
  )
}

/** The confirmation over the entry as it now stands, in [state]; an entry that is not this student's is the 404. */
private suspend fun ApplicationCall.respondRemoveAsItStands(
  services: CollegeListServices,
  caller: WebCaller,
  studentId: StudentId,
  entryId: CollegeListEntryId,
  state: RemoveCollegeState,
) {
  val stored = services.readEntry(studentId, entryId) ?: return respondNotFoundPage(caller.signedInNav)
  respondRemoveCollegePage(caller.signedInNav, state, stored.view)
}

// --- the posting grammar -------------------------------------------------------------

/** The edit form's posting, read as a save or as not this page's. */
private sealed interface EntryPosting {
  /**
   * A save of [status] and [reasons], as typed, at [version]. [reasonsToStore]
   * is what the service is asked to store: a blank or whitespace-only box
   * clears the reasons, as the iOS app's does; anything else is sent verbatim.
   */
  data class Save(
    val version: Int,
    val status: CollegeListEntryStatus,
    val reasons: String,
  ) : EntryPosting {
    val reasonsToStore: String? get() = reasons.takeIf(String::isNotBlank)
  }

  data class Unreadable(
    val at: UnreadableControl,
  ) : EntryPosting
}

/** The save grammar: exactly one `version` (an integer), one `status` (one of the four) and one `reasons` (any string, CRLF read as LF). */
private fun Parameters.entryPosting(): EntryPosting {
  val version = version { return EntryPosting.Unreadable(it) }
  val status =
    CollegeListEntryStatus.fromValue(oneValue(STATUS_PARAM) { return EntryPosting.Unreadable(it) })
      ?: return EntryPosting.Unreadable(UnreadableControl(STATUS_PARAM, UnreadableMode.NOT_AN_OPTION))
  // A browser posts a textarea's newlines as CRLF but counts each as ONE
  // character against its maxlength; read them as the LF that was counted, so
  // a box the browser allowed is not refused by the CHECK, and the stored text
  // is the LF the iOS app stores.
  val reasons = oneValue(REASONS_PARAM) { return EntryPosting.Unreadable(it) }.replace("\r\n", "\n")
  return EntryPosting.Save(version, status, reasons)
}

/** The remove form's posting, read as a remove or as not this page's. */
private sealed interface RemovePosting {
  data class Remove(
    val version: Int,
  ) : RemovePosting

  data class Unreadable(
    val at: UnreadableControl,
  ) : RemovePosting
}

/** The remove grammar: exactly one `version`, an integer. */
private fun Parameters.removePosting(): RemovePosting = RemovePosting.Remove(version { return RemovePosting.Unreadable(it) })

/** The one `version` posted, as an integer; anything else is handed to [unreadable], which ends the reading. */
private inline fun Parameters.version(unreadable: (UnreadableControl) -> Nothing): Int =
  oneValue(VERSION_PARAM, unreadable).toIntOrNull() ?: unreadable(UnreadableControl(VERSION_PARAM, UnreadableMode.MALFORMED))
