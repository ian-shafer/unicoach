# RFC 254 — Claim from the report

**Slice:** `family/10/claim-from-the-report` (brief 0014, _parents in the
room_). **Status:** proposed. **Base:** `main@ba3ddb26`.

**Supersedes RFC 155 D-G** (the upgrade cue does not cross to the page) **in
part**, and **RFC 155 D-H** (the report's response headers) **in one header**.
D-F (the page shows no student identity) is **upheld** and shapes the whole
design.

A parent opens the Family Cost Report link their student sent. Today the page
ends after the sources section, and the parent closes the tab. After this slice,
the page ends with an offer: create your own unicoach account. Signing up
succeeds on its own and lands the parent in the parent product. It also asks the
student who shared the report to approve a connection, in one tap, from the
student's own Family & Supporters list. Until the student approves, the new
account sees nothing of the student's.

## Context

Seven facts from the code decide this design. Research is under
`.scratch/ship/rfc-254/research/` (not committed).

1. **No pending link exists.** `supporter_student_links` has two states: live
   (`deleted_at IS NULL`) and severed
   (`db/schema/supporters-and-student-links.sql:56-89`). RFC 241 left the
   pending state to this slice by name (`rfc/241-the-parent-account.md:62`).
2. **The existing parent-to-student invite is not a request.** A
   supporter-minted `supporter_invites` row has `student_id` NULL
   (`db/schema/supporter-invites.sql:37-40`). It is a bearer link that the
   parent delivers. It names no student, so no student can see it in the app.
3. **The report knows its student only inside one DB session.**
   `ServiceCostReportSource.getOutcome` resolves `share.studentId`
   (`public-web/.../report/ServiceCostReportSource.kt:86-103`), and
   `CostReportOutcome.Found` carries only the cost profile
   (`CostReportSource.kt:52-54`). The route already resolves the viewer for the
   header (`Routing.kt:1029-1031`).
4. **`?token=` on `/register` and `/sign-in` already means an INVITE token.**
   Both POST handlers pass `TOKEN_FIELD` to `acceptPresentedInvite`
   (`Routing.kt:360-418,580,610`). A report token on that key would be read as
   an invite and refused.
5. **Forms on the link-holder pages are refused in a real browser.**
   `setLinkHolderPrivacyHeaders()` sends `Referrer-Policy: no-referrer`
   (`web-common/.../http/PrivateResponse.kt:55`) on `/report`, `/register`,
   `/sign-in` and `/invite` (`Routing.kt:474,561,1013,1066`). Under the Fetch
   standard, a POST from a `no-referrer` document sends `Origin: null`. The
   same-origin guard refuses a present-but-unparseable `Origin` and does not
   fall back to `Referer` (`SameOriginGuard.kt:174-184`). **Measured on this
   machine, 2026-10-11, headless Chrome:** a form POST from a page served with
   `Referrer-Policy: no-referrer` arrived with `Origin: null` and no `Referer`.
   The same page with `same-origin` sent the real origin and a same-origin
   `Referer`. No test catches this, because `formPost` sets `Origin` by hand
   (`WebAuthTestSupport.kt:400-408`). So web sign-in, web register and web
   invite-accept are very likely broken for real users today. The claim flow
   needs `/register` to work.
6. **The only channel to a person is queued email.** There is no push, inbox or
   badge (`rfc/252-the-deletion-partition.md:40-43`). `LinkEndedNotice`
   (`service/.../family/LinkEndedNotice.kt`) is the pattern: a required enqueue
   in the caller's transaction, to a verified, live recipient only.
7. **Opt-out silences only the coach today.** `ShareEventsDao.hasOptOut` is read
   by the two synthesis nudges and nothing else (`SynthesisService.kt:304,476`).
   No web path reads it.

## Why D-G is superseded, and why D-F is not

RFC 155 D-G, verbatim: _"`CollegeCostProfile.precisionOffersFor()` is an in-chat
prompt for the student to answer a question ('tell me your state and I can price
tuition exactly'). A logged-out parent cannot answer it, and a page that asks
them to would be selling, not informing."_

That reasoning is right, and this RFC keeps it. The precision offer stays off
the page. What D-G implied, and what the page has enforced by test ever since
(`CostReportProgramReturnTest.kt:312-334`, "links each filing and the College
Scorecard once, and nothing else"), is a wider rule: the page offers the reader
nothing. That wider rule is what this RFC supersedes. The argument is about who
is asked for what:

- **The suppressed cue asked the parent to act for the student.** It asked a
  reader who is not the student to supply the student's missing data. The reader
  cannot answer honestly, because they would be answering as someone else. And
  the data goes to the student's record. Both halves are wrong: useless, and
  taking from the reader something for someone else.
- **The signup offer asks the reader to act for themselves.** It asks nothing
  about the student and adds nothing to the student's record. It offers the
  reader their own account, for their own questions. A reader who ignores it
  loses nothing, and the report above it is unchanged.

So the test is: **does the copy ask the reader for anything about the student?**
If it does, it is the cue D-G suppressed, and it stays off the page. The CTA
copy below passes that test. A future edit that asks the reader for the
student's state, income, list or anything else fails it, and D-G still forbids
it.

**D-F is upheld, before and after the CTA.** The link is not proof of who sent
it, so:

- the CTA names no student, and neither does any page after it;
- the page's markup does not change with any fact about the student (see
  decision 3);
- claiming does not link the claimant to the student. It creates a **request**
  the student approves (D19).

## What this slice does NOT do

- **No parent product on the web.** public-web has no supporter surface
  (`AccountPage.kt:41-63`). The parent product is the iOS parent home (RFC 241).
  The web account page says so and points the parent to the iPhone app. A web
  parent home is brief 0013's work.
- **No relationship question.** D22: asking for the relationship word is new
  copy that needs its own decision. An approved request stores the catch-all
  `supporter`, the same convention as RFC 240 for an invite whose door stated no
  relationship.
- **No change to the share link itself.** It stays multi-use, unexpiring and
  revocable all-or-nothing (X12).
- **No coach change.** The RFC 240 invite nudge does not see pending requests.
  That is an open item, not this slice.
- **No email to the claimant** on approve or decline. The parent sees the
  student appear on their roster, or sees a waiting line.
- **No Google or Apple sign-in on the web.** That is `web/11/sso`.

## Detailed Design

### 1. The request record (`:db`) — new table, Ian approves the DDL

Migration `supporter-link-requests.sql` (slug, no number, RFC 180).

```sql
CREATE TABLE supporter_link_requests (
  id                   UUID        NOT NULL PRIMARY KEY DEFAULT uuidv7(),
  created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
  row_created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  supporter_id         UUID        NOT NULL REFERENCES supporters(id) ON DELETE CASCADE,
  student_id           UUID        NOT NULL REFERENCES students(id) ON DELETE CASCADE,
  cost_report_share_id UUID        NOT NULL REFERENCES cost_report_shares(id) ON DELETE CASCADE,
  notified_at          TIMESTAMPTZ NULL,
  resolution           TEXT        NULL,
  resolved_at          TIMESTAMPTZ NULL,
  link_id              UUID        NULL REFERENCES supporter_student_links(id),
  CONSTRAINT supporter_link_requests_resolution_check
    CHECK (resolution IN ('approved', 'declined', 'cancelled')),
  CONSTRAINT supporter_link_requests_resolved_pair_check
    CHECK ((resolution IS NULL) = (resolved_at IS NULL)),
  CONSTRAINT supporter_link_requests_link_check
    CHECK ((link_id IS NOT NULL) = (resolution IS NOT DISTINCT FROM 'approved'))
);

-- at most one PENDING request per pair; resolved rows are history
CREATE UNIQUE INDEX supporter_link_requests_pending_pair_idx
  ON supporter_link_requests (supporter_id, student_id) WHERE resolution IS NULL;
CREATE INDEX supporter_link_requests_student_pending_idx
  ON supporter_link_requests (student_id) WHERE resolution IS NULL;
```

Guards, by trigger, in the `supporter_invites` shape (CAS-only, not versioned —
a request is written once and resolved once):

- no physical delete;
- `supporter_id`, `student_id`, `cost_report_share_id` and `created_at` are
  frozen;
- a resolved row is frozen: `resolution`, `resolved_at` and `link_id` change
  only from NULL, once;
- a self-request (the supporter's user is the student's user) is refused, named
  `supporter_link_requests_not_self`, mirroring
  `supporter_student_links_not_self_link`.

Each of this table's own refusals names the request's id; a frozen-column or
resolved-row refusal also names the old value and the value the write attempted,
so a bulk statement that trips one says which row and what write.

States: **pending** (`resolution IS NULL`), **approved** (a link row exists and
`link_id` names it), **declined**, **cancelled** (withdrawn while pending:
either account closed, or the report link it came from was turned off).
`notified_at` records when the student's notice was DECIDED: the email was
enqueued, or the run decided not to send one (decision 3; an opted-out,
unverified or closed student), so the notice is sent at most once. It is not
proof that an email went out, and the column comment and the Kotlin field both
say so.

Kotlin: `SupporterLinkRequestResolution(val value: String)` with `fromValue` in
`db/.../models` (schema conventions), and a `SupporterLinkRequest` model whose
resolution is one sealed `SupporterLinkRequestState` — `Pending`,
`Approved(resolvedAt, linkId)`, `Declined(resolvedAt)`, `Cancelled(resolvedAt)`
— built by the loader, so a row that breaks the CHECKs is located corruption
rather than a value a caller holds. `SupporterLinkRequestsDao` has `create`,
`findPendingPair`, `hasDeclinedPair`, `listPendingVisibleToStudent` (joins the
claimant's live `users` row and requires `email_verified`),
`countPendingForSupporter`, `listUnnotifiedForSupporter`, `markNotified`,
`approve(id, studentId, linkId)` and `decline(id, studentId)` — one typed write
per student decision over a private CAS whose WHERE carries `student_id`, so the
caller's side IS the authorisation (the RFC 249 `unlink(linkId, LinkSide)`
shape) and an approval without its link cannot be written — and
`cancelAllPendingFor(side)` and `cancelAllPendingFromShare(shareId)`. The
`LinkSide` → column mapping moves out of `SupporterStudentLinksDao` into one
`internal` file both DAOs read.

### 2. The service (`:service`, `ed.unicoach.family`)

`SupporterLinkRequestService` is the one writer of the table.

- **`claimFromReport(userId, rawReportToken): Result<ClaimOutcome>`.** In its
  own transaction (a lost race minting the `supporters` row retries once on a
  new one):
  1. Resolve the token as `/report` does: well-formed, live share, student not
     closed. Otherwise `NotClaimed(reason)`.
  2. The claimant is the student → `NotClaimed(OWN_REPORT)`. The claimant's
     `supporters` row is closed (an ALL-scope read) →
     `NotClaimed(SUPPORTER_CLOSED)`.
  3. `SupporterService.getOrCreate(session, userId)` mints the claimant's
     `supporters` row. **This happens even when no request is created**, so the
     account always lands in the parent home (D18: signup succeeds on its own).
  4. A live link already exists for the pair → `AlreadyConnected`. A pending
     request exists → `AlreadyRequested`. A declined request exists →
     `NotClaimed(DECLINED_BEFORE)` (decision 4). This ladder is one private
     `standingOf(supporter, student)`, which the report page's `claimView` reads
     too, after step 2's refusals (one private `refusalBeforeMint`), so the page
     never offers what a claim refuses: a reader whose own `supporters` row is
     closed is offered nothing.
  5. Insert the pending request. If the claimant's email is already verified,
     call `notifyStudent` (below) now.
- **`releaseOnVerified(session, userId)`.** Called inside the email-verify
  transaction. For each un-notified pending request whose supporter is this
  user, call `notifyStudent`. **A request from an unverified claimant is
  invisible to the student and sends nothing.** Without this, anyone could
  register as `mum@example.com`, and the student would see "mum@example.com
  asked to connect" and approve an impostor.
- **`notifyStudent(session, request)`.** Sends the new `LINK_REQUESTED` email to
  the student's verified address through the `LinkEndedNotice` pattern — a
  required enqueue in the same transaction. A student closed by either shape
  (`students` row or `users` row, as `ReportShares.lookup` reads it) is sent
  nothing. A failed enqueue throws an error naming the request, the student and
  the claimant, with the queue's error as its cause. **If the student has opted
  out of share offers (`ShareEventsDao.hasOptOut`), no email is sent** (decision
  3). Either way `notified_at` is set, so the decision is made once.
- **`approve(studentId, requestId)`.** One transaction: lock the pending row
  (`findPendingVisibleToStudentForUpdate` — this student's, claimant verified
  and live), the same `findActive ?: create` link write as
  `SupporterInviteService` (relationship `supporter`), then resolve to
  `approved` with `link_id` in one statement. A closed endpoint fails at the
  link insert's closed-endpoint trigger and maps to the uniform not-found. The
  not-found outcome carries a log-only `RequestNotFoundReason` (`NOT_VISIBLE`,
  `STUDENT_CLOSED`, `SUPPORTER_CLOSED`); the wire answer is one 404 for all. A
  link that an invite accept wrote between the read and the insert is retried
  once onto that link.
- **`decline(studentId, requestId)`.** CAS-resolve to `declined`. Nothing is
  sent.
- **Turning the report link off.** `CostReportShareService`'s one revoke helper,
  which both the student's revoke and a stale-row reissue call, cancels the
  pending requests made from the revoked share in the same transaction
  (`cancelAllPendingFromShare`). The email's remedy for a forwarded link is to
  turn it off; without this, a stranger who claimed unverified would still reach
  the student by verifying afterwards.
- **`StudentSupporters`** (the RFC 249 read) gains `requests`: the claimant's
  name and email, and when they asked. **`SupporterHome`** gains
  `pendingRequests: Int`.
- **Account closure.** `AccountClosureService.severLinksAndTellOthers` gains one
  line per side: `cancelAllPendingFor(student)` and
  `cancelAllPendingFor(supporter)`. This is the third two-party table the
  closure KDoc warns about (`AccountClosureService.kt:35-38`), and its KDoc list
  is updated to name it.

The email (`LinkRequestedEmailRenderer`, registered in the queue worker through
`emailRenderers`, which a test holds to `EmailTemplate.entries`):

> **Subject:** Someone asked to connect with you on unicoach
>
> <Name> (<email>) opened the Family Cost Report link you shared, created a
> unicoach account, and asked to connect with you as a parent or supporter.
>
> Nothing of yours is shared with them unless you approve. To approve or
> decline, open unicoach and go to Settings → Family & Supporters.
>
> If you don't know this person, decline. Your report link may have been
> forwarded. You can ask your coach to turn the link off.

### 3. The email-verify hook (`:auth`, `:service`, `:admin-web`)

Every production path that marks an address verified takes the same required
argument, `afterVerified: (SqlSession, UserId) -> Unit`, and hands it to
`recordEmailVerified` (`:db`) inside its own transaction. That one function is
the sequence: mark verified, burn the account's outstanding mailed links, then
the hook. It lives in `:db` because `:service` and `:admin-web` do not depend on
`:auth`. The operator's path did not burn the links before; now it does, so a
mailed link left spendable cannot run the hook a second time:

- **the mailed link** — `DbEmailVerifier` (`:auth`), in public-web and
  rest-server;
- **an SSO sign-in** — `AuthService.markVerifiedIfEmailMatches` (`:service`), in
  rest-server, which serves the SSO doors (public-web and admin-web build an
  `AuthService` with both SSO slots fail-closed, and pass the hook anyway);
- **an operator's "Mark email verified"** — `UsersResource` (`:admin-web`). The
  route classifies only its user lookup (a missing user is its 404). A failure
  in the sequence or the hook throws to the admin status-pages net, which logs
  it with the request URI, and the mark rolls back with it.

Each process passes `SupporterLinkRequestService::releaseOnVerified`. It is
required, not defaulted, so no path can silently forget it. `:auth` gains no
dependency on `:service`: it takes a function.

The fixture loader's `markEmailVerified` (`WorldApplier`) is not a path: it
verifies a user it created a statement earlier, who has no `supporters` row and
so no request to release.

### 4. public-web

**The CTA on `/report`.** After the `report` section, inside `main`, one
`aside.report-claim` block (an `aside`, not a `section`, so it is outside
`reportSectionOf` and the report's own pins keep measuring the report). Its
content depends on the **viewer only**, never on the student:

| Viewer                                       | What the block shows                                                                                                          |
| -------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------- |
| Signed out                                   | heading, body, **Create your account** → `/register?report=<t>`, **Already have an account? Sign in** → `/sign-in?report=<t>` |
| Signed in, not connected, no pending request | heading, body, **Ask to connect** — a POST form to `/report/connect` with the token in a hidden `report` field                |
| Signed in, request pending                   | one line: "Your request to connect is waiting for the student to approve it."                                                 |
| Signed in, connected, or declined before     | nothing                                                                                                                       |
| Signed in, own `supporters` row closed       | nothing                                                                                                                       |
| Signed in as the student who shared it       | nothing                                                                                                                       |

To choose a row, `ServiceCostReportSource` returns a viewer state computed in
its existing session (`CostReportOutcome.Found(profile, claim: ClaimView)`).
`ClaimView` is an enum. It carries no student id, so the renderer still cannot
print one.

Copy (constants in `CostReportPage.kt`, so tests assert the constant):

> **Keep going with your own account**
>
> unicoach is a college coach. You can have an account of your own, to look up
> what any college costs a family like yours and to keep track of what comes
> next.
>
> If you sign up here, we'll ask the student who shared this report to approve
> connecting with you. Nothing of theirs is shared with you until they do. They
> will see your name and email.

It contains no `@`, no "award", and no student identity. That keeps the existing
whole-body sweeps (`CostReportPageTest.kt:664-676,751-756`) passing unchanged.
The anchor-list pin (`CostReportProgramReturnTest.kt:312-334`) measures the
report section, so it also stays as it is. A new test pins the CTA's own two
links.

**Carrying the token through signup.** A separate key, `report`, never `token`
(fact 4). It is secret-scoped (`secretQueryParams`) on `/register`, `/sign-in`
and `/report/connect`, and it rides as a hidden field on the register and
sign-in forms, and on their "switch to the other form" links, exactly as the
invite token does. Only one value in the mint shape (`ShareToken.isWellFormed`)
is carried; anything else is dropped at the door, never echoed or redirected.
The report token is reused, not replaced by a short-lived successor. It is
already a bearer secret in the parent's URL bar, it never leaves our own origin,
and a successor would mean a second token table for no gain.

- `POST /register` and `POST /sign-in`: after the account is created or signed
  in, call `claimFromReport` in its own transaction. This is the same "not one
  transaction with register" shape as `acceptPresentedInvite`
  (`Routing.kt:351-358`). A failed claim never fails the signup. Register then
  goes to `/verify-email?notice=claim-pending`, and sign-in goes to
  `/account?notice=claim-sent` (or `claim-not-sent`).
- `POST /report/connect` (SIGNED_IN, not VERIFIED, like `POST /invite`): call
  `claimFromReport`, then 303 to `/account?notice=…`.
- **Signed-in landing.** `installSignedInLanding` 302s a signed-in GET of
  `/register` or `/sign-in` to `/account`, which would drop the token. A parent
  who opened the link from Messages can arrive looking signed out, because the
  session cookie is `SameSite=Strict`. When such a GET carries `report`, it
  redirects to `/report?token=<t>` instead, where the signed-in block offers
  **Ask to connect**.
- **Notices.** `claim-pending`: "Your account is ready. Once you confirm your
  email, we'll ask the student who shared the report to approve connecting with
  you." `claim-sent`: "We've asked the student who shared the report to approve
  connecting with you." `claim-not-sent`: "Your account is ready, but we
  couldn't send a request to connect from that report link." One uniform
  sentence covers every refusal, so the notice reveals nothing about the
  student.
- **`/account` for a supporter account** gains one paragraph: "unicoach for
  parents and supporters is in the iPhone app. Sign in there with this email to
  search colleges and see who you're connected to."

**The referrer policy** (decision 2, superseding RFC 155 D-H's
`Referrer-Policy: no-referrer`). `setLinkHolderPrivacyHeaders()` sends
`Referrer-Policy: same-origin`. That keeps D-H's purpose: the token never
reaches an outbound link target, because every outbound link is cross-origin and
gets no `Referer` at all. It also lets a same-origin POST carry its real
`Origin`, so the guard can admit it (fact 5). `Cache-Control: no-store` and
`noindex` are unchanged. The one new place the token travels is a same-origin
`Referer` to our own server. The guard already reduces `Referer` to its origin
before it logs it (`SameOriginGuard.kt:185-198`). The request log is checked for
a `Referer` line in tests.

### 5. REST (`:rest-server`)

- `GET /api/v1/students/me/supporters` gains
  `requests:
  [{requestId, name, email, requestedAt}]`.
- `POST /api/v1/students/me/supporter-requests/{requestId}/approve` → `200` with
  the new `PublicLinkedSupporter`.
- `POST /api/v1/students/me/supporter-requests/{requestId}/decline` → `204`.
- Both answer `404 supporter_request_not_found` for "not yours", "never
  existed", "already resolved", "the claimant closed" and "the claimant has not
  verified" alike, so the doors cannot probe ids. Both read the id through
  `pathId` (RFC 249). Both stay in `bin/test-fuzz`.
- `GET /api/v1/supporters/me` gains `pendingRequests`.
- OpenAPI updated.

### 6. iOS

- **Student: Settings → Family & Supporters.** `FamilyConnectionsSection` shows
  pending requests above the connected adults, in a row kind of their own:
  "<Name> asked to connect", the email under it, and **Approve** and
  **Decline**. Decline asks for a second tap in the view, the same disclosure as
  Disconnect (`confirmingOnAppear`). Approve takes one tap (D19: "approves in
  one tap"). The empty-list branch (`FamilyConnectionsSection.swift:71-73`) is
  changed so that a student with zero links and one request sees the request and
  the ceiling notes.
- **Parent: home.** With `pendingRequests > 0` and no linked student, the home
  says "Your request to connect is waiting for your student to approve it." in
  place of the first "No student linked yet" line.
- New snapshot scenes: a student with a request and no links, a student with a
  request and one link, the decline confirmation, and the parent home waiting.

### Reachability — the door

- **Parent:** the public-web report page — the door parents already walk through
  today. The CTA sits after the report.
- **Student:** side menu → Settings → "Family & Supporters", one level deep,
  plus the email.

### Value before ask (standing D12)

The CTA is after the report, never in front of it. A reader who ignores it loses
nothing, and the report stays exactly as it is for someone with no account.
Signing up never waits on the student. A parent whose request is never approved
keeps a full parent account. The student can approve, decline, or do nothing.
Doing nothing changes nothing.

## Files Modified

- `db/schema/supporter-link-requests.sql` (new), `db/schema/ORDER` (one line,
  placed by `ship-order` at land)
- `db/src/main/kotlin/ed/unicoach/db/models/SupporterLinkRequest.kt`,
  `SupporterLinkRequestResolution.kt` (new)
- `db/src/main/kotlin/ed/unicoach/db/dao/SupporterLinkRequestsDao.kt`,
  `LinkSideColumns.kt`, `EmailVerifiedWrite.kt` (new),
  `SupporterStudentLinksDao.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/report/CostReportShareService.kt`
  (a revoke cancels the link's pending requests),
  `service/src/main/kotlin/ed/unicoach/EmailRenderers.kt` (new)
- `auth/src/main/kotlin/ed/unicoach/auth/DbEmailVerifier.kt`
- `service/src/main/kotlin/ed/unicoach/auth/AuthService.kt` (the SSO verify
  hook)
- `admin-web/src/main/kotlin/ed/unicoach/admin/Application.kt`,
  `resources/UsersResource.kt` (the operator's verify hook)
- `email/src/main/kotlin/ed/unicoach/email/EmailTemplate.kt`
- `service/src/main/kotlin/ed/unicoach/family/SupporterLinkRequestService.kt`,
  `LinkRequestedNotice.kt` (new)
- `service/src/main/kotlin/ed/unicoach/family/SupporterService.kt`
  (`StudentSupporters.requests`, `SupporterHome.pendingRequests`)
- `service/src/main/kotlin/ed/unicoach/account/AccountClosureService.kt`
- `queue-worker/src/main/kotlin/ed/unicoach/worker/Application.kt` (renderer
  registration)
- `web-common/src/main/kotlin/.../http/PrivateResponse.kt` (referrer policy)
- `public-web/src/main/kotlin/ed/unicoach/web/Routing.kt`, `Application.kt`,
  `auth/WebAuthGate.kt`, `report/CostReportSource.kt`,
  `report/ServiceCostReportSource.kt`, `render/CostReportPage.kt`,
  `render/RegisterPage.kt`, `render/SignInPage.kt`, `render/AccountPage.kt`,
  `render/FormControls.kt`, `PresentedToken.kt` (moved out of `Routing.kt`)
- `public-web/src/main/resources/static/site.css` (`.report-claim`)
- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt`, `Routing.kt`,
  `routing/StudentSupporterRoutes.kt`, `models/SupporterResponses.kt`,
  `models/ErrorCode.kt`, and the OpenAPI document
- `ios-app/UnicoachiOS/FamilyConnectionsSection.swift`,
  `FamilyConnectionsViewModel.swift`, `StudentClient.swift`,
  `SupporterHomeView.swift`, and the supporter model and client
- Tests beside each, listed below

## Implementation Plan

Two children in parallel, as RFC 249 did:

1. **Server and web:** migration, DAO, service, verify hook, closure, email,
   REST doors, public-web CTA, token carry, signed-in landing, referrer policy.
   Then the suite through `ship-run -g`.
2. **iOS:** client, view model, request rows, parent waiting line, snapshot
   scenes, against the wire shape in §5. It runs under system Xcode.

## Tests

- **DAO:** pending-pair uniqueness, frozen columns, resolve-once, self-request
  refused by name, `decline` with another student's id changes nothing, an
  approval without its link refused by the table, `listPendingVisibleToStudent`
  hides an unverified or closed claimant, `cancelAllPendingFromShare` touches
  only that link's pending requests.
- **Service:**
  - **the forwarded-link case** — a stranger holding the link claims; they get
    an account and a `supporters` row, and **no link row exists** until the
    student approves;
  - claim by the owning student, by an already-connected or already-pending
    supporter, after a decline, from a revoked link, and from a closed student;
  - an unverified claimant: no email and not listed, then verify → one email,
    and verify again → no second email — through the verify door (public-web),
    an SSO sign-in, and an operator's mark-verified (admin-web);
  - an opted-out student: request listed, no email, `notified_at` set;
  - approve → link with `supporter`, idempotent on an existing link; a closed
    student → not found at the link insert's trigger; a closed claimant (`users`
    or `supporters` row) → not listed and not found;
  - closure from either side cancels pending requests;
  - a claimant whose `supporters` row is closed → `SUPPORTER_CLOSED`, nothing
    written;
  - turning the link off cancels its pending requests, so an unverified claimant
    who verifies afterwards reaches nobody;
  - every `EmailTemplate` has exactly one renderer;
  - an operator's mark-verified burns the outstanding mailed links.
- **public-web:**
  - the CTA renders after the report section for each viewer row in the table;
  - the CTA carries no student identity, and the page's bytes are the same for
    an opted-out and an opted-in student;
  - `/register?report=` → POST → request created and the `claim-pending` notice;
    `/sign-in?report=` likewise;
  - `report` is not read as an invite token, and `token` is not read as a report
    token;
  - signed-in GET `/register?report=` → `/report?token=`;
  - **the referrer policy header is `same-origin`** on all four routes;
  - a guard test that a form POST carrying a real `Origin` passes and
    `Origin: null` is still refused;
  - the token never appears in a log line.
- **REST:** the doors, the uniform 404s, OpenAPI conformance, fuzz.
- **iOS:** view-model tests for approve and decline, and the four snapshot
  scenes.
- **Every new negative test is proved by deleting the guard it pins and reading
  a named red.**

## Rollback

A revert. No config key. The table can stay if a revert lands after rows exist.
Nothing reads it once the code is gone.

## Deviations

### iOS

- **Decline confirmation copy.** §6 asks for a second tap but gives no words.
  The row says: "Decline <Name>? They can't ask to connect with you again, and
  nothing of yours is shared with them. We don't send them a message." Each
  clause states a decision already made: decline is final for the pair (gate 4),
  nothing is shared before approval, and no email goes to the claimant.
- **Per-row failure copy.** "Approving didn't work. Try again." and "Declining
  didn't work. Try again.", in the shape of Disconnect's line.
- **A `404 supporter_request_not_found` re-reads the list.** The 404 does not
  say who answered, so the request may have been approved on another device.
  Approve success moves the person from the approve's own answer, without a
  re-read, and does not list an adult twice.
- **The waiting line replaces the title.** It takes the "No student linked yet"
  title slot and style. The three lines under it stay.
- **`requestedAt` is not decoded.** Nothing on iOS shows it.

### Server and web

- **Approve writes the link before it resolves the request.** The approved draft
  ordered it resolve, then link, then set `link_id`. The table's own
  `supporter_link_requests_link_check` refuses an approved row with no link, so
  approve LOCKS the pending row (`findPendingVisibleToStudentForUpdate`, a
  one-row form of `listPendingVisibleToStudent` with the same visibility rule),
  writes the link, then resolves with `link_id` in one statement. Decline uses
  the same locked read, so a request from a closed or unverified claimant is the
  same 404 on both doors.
- **Approve retries once on a lost link race.** An invite accept can link the
  pair between approve's `findActive` and its insert; the insert's active-link
  index refuses, and one retry finds that link and resolves the request onto it
  (the `claimFromReport` retry shape). Without it the race is a 500 on a one-tap
  approve.
- **Every verification path runs the hook, not only the mailed link.** The draft
  wired `DbEmailVerifier` alone. An SSO sign-in and an operator's mark-verified
  also verify an address, and a request waiting on it would stay silent forever,
  so both take the same required function (§3).
- **`claimFromReport(userId, rawToken)` owns its transaction.** Both web callers
  run it in its own transaction anyway, and a lost race minting the claimant's
  `supporters` row poisons the transaction, so it retries once on a new one (the
  `SupporterService.ensure` shape). A second refusal means the row is closed:
  `NotClaimed(SUPPORTER_CLOSED)` — decided by an ALL-scope read of that row
  before the mint, so the retry covers only a true race. `create` answers null
  on a pending-pair race (`ON CONFLICT DO NOTHING`), read as `AlreadyRequested`.
- **One token resolver.** `ReportShares.lookup` in `:service` is what `/report`
  and a claim both use to turn a token hash into an open share, so they cannot
  disagree about which links are live. It is a stateless `object` over the
  caller's session, the DAO shape, not an injected class: everything below it is
  a static DAO, so an injected seam would let a test stub nothing. `ClaimView`
  lives in `:service`, which computes it; the port is
  `getByShareToken(rawToken, viewer)`.
- **An unverified reader's notice lands on the hold screen.** `/account` sends
  an unverified caller to `/verify-email` and drops the query, so a claim from
  sign-in or `POST /report/connect` by an unverified reader goes to
  `/verify-email?notice=claim-pending`. A verified reader goes to `/account`, as
  §4 says. `AlreadyRequested` gets the same notice as a new request;
  `AlreadyConnected` and every refusal get `claim-not-sent`. If one POST carries
  both an invite token and a report token, both are processed and the invite's
  notice is shown.
- **The signed-in landing stamps the log redaction itself.** It answers before
  routing, where the doors' `secretQueryParams` never runs, so
  `GET /register?report=<t>` by a signed-in reader wrote the raw token to the
  request log. The landing now stamps `token` and `report` as secret, which also
  closes the same pre-existing leak for an invite token. The set is one value,
  `SIGNUP_DOOR_SECRET_PARAMS`, that the two doors' `secretQueryParams` read too,
  and the landing reads the report token through the doors' own
  `presentedToken`, moved to its own file so `auth/` can reach it.
