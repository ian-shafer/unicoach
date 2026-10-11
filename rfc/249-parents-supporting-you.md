# RFC 249 — Parents supporting you

**Slice:** `family/04/parents-supporting-you` (brief 0014, _parents in the
room_). **Status:** proposed. **Base:** `main@4336f7dc`.

The student's side of the relationship: a list of every adult connected to them,
what those adults can see, and a two-tap disconnect. The parent's side mirrors
it: their connected students, the other adults supporting each student, and the
same disconnect.

## Context

`family/01/the-link` (RFC 221) landed `supporters` and
`supporter_student_links`, with unlink modelled as a soft delete (`deleted_at`)
and a PARTIAL active-pair unique index so a re-link inserts a fresh row
(`db/schema/supporters-and-student-links.sql:84-89`).
`family/02/invite-and-accept` (RFC 232) made links;
`family/03/the-parent-account` (RFC 241) gave the parent a home and a roster
read. Five facts from the code decide this design.

1. **A DAO unlink exists and nothing calls it.**
   `SupporterStudentLinksDao.unlink(id, currentVersion)` (`:349-370`) is an OCC
   soft delete, DAO-tested, with zero production callers. It takes no caller
   identity, so authorisation would be the caller's job in a second read.
2. **No door lists a student's supporters.** Student routes are `/students` and
   `/students/me` only (`StudentRoutes.kt:69-82`). `listForStudent` (`:163-166`)
   returns bare link rows, with no names, and does not filter a closed
   supporter.
3. **Re-invite already works.** Accept is `findActive ?: create`
   (`SupporterInviteService.kt:544-552`); a severed row is invisible to
   `findActive`, so a new row is inserted. Proven at DAO level only
   (`SupporterStudentLinksDaoTest.kt:220-241`).
4. **The copy already promises this slice.** "The student can end one
   supporter's access without ending anyone else's" ships in the coach tool, the
   prompt and the synthesis nudge, with no door behind it.
5. **The ceiling has four hand-written homes** — `InvitePage.inviteCeiling`
   (`InvitePage.kt:56-70`), `InviteSupporterChatTool` (`:153-161`), the coach
   prompt (`coach-system-prompt.md:255-256`) and
   `SynthesisService.INVITE_NUDGE_STATEMENT` (`:1165-1168`). This slice adds a
   fifth reader (the student's list), so it must not add a fifth home.

Today a connected adult reads nothing of the student's data — only the roster
(name and relationship). Reading the list and the cost picture is
`family/05/the-subject-seam` and `family/06/the-parent-coach`, the same beat.

## What this slice does NOT do

- **No schema change.** No table, no column, no migration, no `db/schema/ORDER`
  line. Who disconnected is not recorded: nothing reads it, and the history
  table already records that and when (`_versions` snapshots `deleted_at`).
- **No notification to the other party.** There is no push channel and one email
  template (`EmailTemplate.EMAIL_VERIFICATION`). The other side sees the change
  on its next read. Notify-on-close belongs to `family/09` (D9).
- **No relationship word on screen.** D22: rendering the four words is new copy
  needing its own decision, and `SUPPORTER` still carries two meanings (RFC 240
  open item). Lists show names only.
- **No change to unused invite links.** D2: sharing an invite link is the
  inviter's consent, so a live link the student created stays live; it expires
  in 14 days. Invite revocation still has no door (RFC 232 open item).
- **No change to the Family Cost Report link** (X12). Its revoke stays
  all-or-nothing and in chat. The list says so, only when such a link is live.
- **No change to the coach.** After a disconnect, the invite nudge's
  `ALREADY_LINKED` suppression lifts, exactly as RFC 240 designed
  (`SynthesisService.kt:461-463`, "consumed and then unlinked").
- **No web door.** `web/` widens the doors later.

## Detailed Design

### 1. Reads and the scoped unlink (`:db`)

`SupporterStudentLinksDao`:

- **`listActiveSupportersOf(studentIds)`** → `List<LinkedSupporter>`. ONE
  statement over an `IdList`, the `listActiveLinkedStudents` precedent mirrored,
  with a single-id overload for the student's own list:

  ```sql
  SELECT l.id AS link_id, l.student_id, u.name AS supporter_name
  FROM supporter_student_links l
  JOIN supporters sup ON sup.id = l.supporter_id
  JOIN users u ON u.id = sup.user_id
  WHERE l.student_id IN (?, …)
    AND l.deleted_at IS NULL AND sup.deleted_at IS NULL AND u.deleted_at IS NULL
  ORDER BY l.created_at, l.id
  ```

  `LinkedSupporter(linkId, studentId, name)` is a closed projection: it cannot
  carry an email, because it selects none. Served by
  `supporter_student_links_student_idx`. It is also the co-supporter read: the
  home passes its own roster's students and drops its own links, so no second
  statement restates the liveness predicates.

- **`unlink(linkId, side: LinkSide)`** REPLACES `unlink(id, currentVersion)`.
  `LinkSide` is `Student(StudentId)` or `Supporter(SupporterId)`; the side IS
  the authorisation:

  ```sql
  UPDATE supporter_student_links
  SET deleted_at = NOW(), version = version + 1
  WHERE id = ? AND <student_id | supporter_id> = ? AND deleted_at IS NULL
  RETURNING *
  ```

  One row → `Unlinked(link)`, carrying the severed row; zero rows → `NotFound`;
  more than one cannot happen (`id` is the primary key) and fails the call
  rather than being reported as either outcome. "Not yours", "never existed" and
  "already disconnected" are one answer, so a door cannot be used to probe link
  ids. `version = version + 1` satisfies `enforce_versioning`; OCC adds nothing
  to a write whose only target state is "severed", and a concurrent double
  disconnect resolves to one `Unlinked` and one `NotFound`. The old method has
  no production caller; its tests move to the new signature.

### 2. The ceiling — one home, two voices (`:service`)

New `ed.unicoach.family.SupporterCeiling`: an ordered list of items, each with
two wordings side by side — `toSupporter` (third person, the invite page's
current text) and `toStudent` (second person, for the student's list) — split
into `canSee` and `staysPrivate`. `InvitePage` renders `toSupporter` from it
instead of its private literals; the student list is served `toStudent` over
REST. The two voices sit on adjacent lines, so an edit to one is visibly an edit
beside the other.

Wordings (the invite page's lines are unchanged except the added one):

| `canSee` — to the supporter (invite page)                               | to the student (list)                                   |
| ----------------------------------------------------------------------- | ------------------------------------------------------- |
| See the student's college list and key application deadlines.           | Your college list and key application deadlines.        |
| See estimated college costs and financial-aid calculations.             | Estimated college costs and financial-aid calculations. |
| Use your own coach workspace for financial-aid and timeline help.       | (none — it is the supporter's own workspace)            |
| **new:** Other supporters connected to this student will see your name. | The names of the other people connected to you.         |

| `staysPrivate` — to the supporter                                                                        | to the student                                          |
| -------------------------------------------------------------------------------------------------------- | ------------------------------------------------------- |
| Student coach conversations stay 100% private to the student. Transcripts are never shared.              | Your coach conversations. Transcripts are never shared. |
| Supporters cannot edit the student's college list, profile, or tasks — parents support, students direct. | They cannot change your college list, profile or tasks. |

The coach tool's and prompt's spoken sentences stay where they are: they are a
restatement for speech, pinned by prompt tests, and folding them in is not this
slice. The RFC 240 open item shrinks from "two homes for the page" to "the
spoken form is separate".

**The list states the ceiling, not today's reads.** Today a connected adult
reads only the student's name; `family/05`/`06` deliver the rest in this beat.
Stating the ceiling errs on the side of telling the student MORE is visible,
never less, and it is the same promise the adult accepted. When
`beyond/10/one-place-for-permissions` adds a student-controlled permission, it
joins this one list rather than starting a second.

**Stated once, not per row.** Every connected adult has the same ceiling today,
so the list says it once ("Everyone here can see…") instead of repeating it
under each name (brief 0009, say it once). A per-adult difference, when one
exists, moves into the row.

### 3. Service (`:service`)

`SupporterService` gains:

- `supportersOf(studentId)` →
  `StudentSupporters(supporters, ceiling,
  costReportLinkLive)` — the list, the
  student-voiced ceiling, and whether the student's Family Cost Report link is
  live (`CostReportSharesDao`'s live-share read), so the X12 sentence appears
  only when it is true.
- `disconnectAsStudent(studentId, linkId)` and
  `disconnectAsSupporter(userId, linkId)` → the DAO's `UnlinkOutcome`
  (`Unlinked | NotFound`), with no service copy of it; the supporter variant
  resolves the caller's live `supporters` row first, and a caller with none is
  `NotFound`, logged at info as the one place that miss is told apart.
- `homeFor` additionally reads `listActiveSupportersOf` over its roster's
  students, drops the caller's own links, and attaches each student's other
  adults' names to the roster entry.

### 4. REST (`:rest-server`)

| Route                                            | Caller                 | Answer                                                                                          |
| ------------------------------------------------ | ---------------------- | ----------------------------------------------------------------------------------------------- |
| `GET /api/v1/students/me/supporters`             | `CallerResolution`     | `200 {supporters:[{linkId,name}], ceiling:{canSee:[..],staysPrivate:[..]}, costReportLinkLive}` |
| `DELETE /api/v1/students/me/supporters/{linkId}` | `CallerResolution`     | `204`; `404 supporter_link_not_found`; `400` on a malformed id                                  |
| `GET /api/v1/supporters/me` (changed)            | `CallerAuthentication` | each student gains `otherSupporterNames: [String]`                                              |
| `DELETE /api/v1/supporters/me/students/{linkId}` | `CallerAuthentication` | `204`; `404 supporter_link_not_found`; `400` on a malformed id                                  |

The student routes keep `CallerResolution`'s `409 student_profile_required`; the
supporter route never sends it. All four are documented in
`api-specs/openapi.yaml` with closed schemas; the roster's pinned field set
(`SupporterRoutingTest.kt:354-358`, `OpenApiSupporterTest.kt:65-91`) widens by
one field, deliberately. The two DELETEs stay in `bin/test-fuzz`: its exclusions
are operations that revoke the injected credential, and the fuzz user holds no
link, so these doors only answer 400 or 404 there.

### 5. iOS

**Door — student:** drawer → Settings → the existing "Family & Supporters"
section (`SettingsView.swift:142-151`), which now holds the list above the
invite control. One level deep from the drawer; no new drawer row (the drawer is
height-tight and DESIGN.md §7 keeps destructive actions out of it).

**Door — parent:** home → Settings (one push) → the same section, voiced for a
supporter: "Students you support", each with "Also supporting {name}: A, B" when
others exist.

Section contents (student):

- heading "People connected to you", then one row per adult: name and a
  `Disconnect` text button;
- **tap 1** opens an inline confirmation in the row (an in-view disclosure, the
  `SupporterSubjectSwitcher` precedent, so the snapshot gate can photograph it):
  "Disconnect {name}? They lose access right away. Your account and theirs stay
  as they are." with `Disconnect` (destructive) and `Cancel`;
- **tap 2** calls the DELETE; on success the row leaves; a
  `404 supporter_link_not_found` also removes the row (the link was on this
  list, so another device ended it first); any other failure keeps the row and
  an inline secondary line says it did not work (the invite control's failure
  precedent);
- "What everyone here can see" and "What stays private", from the served
  ceiling, stated once;
- when `costReportLinkLive`: "Disconnecting does not turn off your Family Cost
  Report link. Anyone with that link can still open it — ask your coach to turn
  it off.";
- empty: "No one is connected yet." — the invite control below is unchanged.
- load failure: "Couldn't load who is connected to you." with a `Try again` text
  button; supporter empty: "No students connected yet."; on an empty list the
  ceiling and the cost-report sentence are not shown.

Supporter side: the same row and confirmation ("Disconnect from {name}? You lose
access right away. Your account and theirs stay as they are."). After a
successful disconnect Settings calls `refreshHome`, passed in by
`SupporterHomeView` — popping back does not re-run the home's `.task`. The
supporter's list renders from the roster the home hands Settings on each render,
never a copy, so the row leaves when the refreshed roster stops carrying it.
`SupporterSubject.resolve` already falls back when the selected student leaves
the roster, so the switcher and header re-shape themselves.

Loading: the student list loads in a `.task` on the family section that Settings
mounts, so it reloads each time Settings is pushed.

Clients: the student-side calls join the student's existing family client in
`StudentClient.swift` (`SupporterInviteClientProtocol`, renamed if the
implementer judges it clearer); `SupporterClient` gains `disconnect(linkId:)`.

### Reachability — the door

Student: drawer → Settings → "Family & Supporters". Parent: home → Settings →
"Family & Supporters". Both one level deep.

### Value before ask (standing D12)

Nothing here gates anything. Disconnecting needs no approval from the other
side, deletes no account and no data, and re-inviting works afterwards.

## Files Modified

**Database** — none. No migration, no `db/schema/ORDER` line.

- `db/src/main/kotlin/ed/unicoach/db/dao/SupporterStudentLinksDao.kt` — the
  adults-on-a-link read; `unlink` takes a `LinkSide`.
- `db/src/main/kotlin/ed/unicoach/db/models/` — `LinkedSupporter`, `LinkSide`,
  `UnlinkOutcome`.
- `service/src/main/kotlin/ed/unicoach/family/SupporterCeiling.kt` — new.
- `service/src/main/kotlin/ed/unicoach/family/SupporterService.kt` — list,
  disconnect both sides, co-supporters on the home.
- `public-web/src/main/kotlin/ed/unicoach/web/render/InvitePage.kt` — render
  from `SupporterCeiling`.
- `rest-server/src/main/kotlin/ed/unicoach/rest/routing/StudentRoutes.kt` (or a
  new `StudentSupporterRoutes.kt`), `routing/SupporterRoutes.kt`,
  `models/SupporterResponses.kt`, `models/ErrorCode.kt`, `Routing.kt`.
- `api-specs/openapi.yaml`.
- `ios-app/UnicoachiOS/Models.swift`, `StudentClient.swift`,
  `SupporterClient.swift`, `SettingsView.swift`, `SupporterHomeView.swift`, a
  new list/row view file (registered in `project.pbxproj`).
- `ios-app/UnicoachiOSTests/` — mocks, client tests, view-model tests, snapshot
  scenes.

## Implementation Plan

1. `:db` — reads, `LinkSide` unlink, DAO tests.
2. `:service` — `SupporterCeiling`, service methods, tests; `InvitePage`
   repointed.
3. `:rest-server` — four routes, models, error code, OpenAPI, routing tests.
4. Suite (`nix develop -c bin/test`) green before any Swift.
5. iOS — models, clients, Settings section both voices, refresh wiring, tests.
6. `bin/test-ios` and `bin/snapshot-ios`, under system Xcode, with PNGs kept as
   run artifacts.

## Tests

**`:db` — `SupporterStudentLinksDaoTest`**

- `listActiveSupportersOf`: names in link order; excludes a severed link, a
  closed supporter, a supporter whose `users` row is closed, and another
  student's adults.
- `listActiveSupportersOf` over several students tags each adult with their
  student and reaches no student it was not asked about; over none it is empty.
- `unlink` with the student side and the supporter side each severs; the wrong
  side's id is `NotFound` and leaves the row live; a second unlink is
  `NotFound`; a re-link after it inserts a new row (existing test, new
  signature); the unlink is recorded in `_versions`.

**`:service`**

- `supportersOf` serves the student voice and `costReportLinkLive` true and
  false.
- disconnect as student and as supporter; a caller with no supporters row is
  `NotFound`.
- **disconnect leaves both accounts, the student's conversations and the
  supporter's own rows untouched**, and leaves the Family Cost Report share
  live.
- **re-invite after disconnect**: accept through `SupporterInviteService`
  produces exactly one live link (closes RFC 232's service-level gap).
- `InvitePage` renders the same lines as before plus the added one.

**`:rest-server`**

- 401 / 403 unverified on all four routes; 409 `student_profile_required` on the
  student routes for a student-less caller.
- the list never contains an email (asserted on the body).
- DELETE 204 then 404; another student's link id is 404; a supporter cannot use
  the student route on their own link and vice versa.
- a caller who is both a student and a supporter cannot end their supporter link
  through the student door.
- the roster carries `otherSupporterNames`, never the caller's own name.
- OpenAPI field-set pins for the new and widened schemas.

**iOS**

- client tests (`MockURLProtocol`) for the list and both DELETEs;
- view-model/state tests: disconnect removes the row; failure keeps it and shows
  the line; the supporter disconnect calls `refreshHome`;
- snapshot scenes: student settings with two adults; with the confirmation open;
  with the cost-report sentence; empty; supporter settings with a student and
  co-supporters; supporter confirmation open. The three student-only scenes
  (confirmation open, cost-report sentence, empty) photograph the family section
  alone, because the subscription rail would exceed the canvas height.

## Rollback

Revert. No table, column, migration or config key, so there is no state to
unwind. Links severed while the change was live stay severed; re-inviting
restores them.
