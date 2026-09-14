# Brief 0014 — spec: parents in the room

Gate 2 artifact. Slice handle: **`family`** (IDs `family/NN.S/name`).

Gate-1 decisions D1-D9 are recorded in `brief.md` and are binding here. This
file adds the DISCOVER corrections, the slices, and gate-2 decisions D10-D17.

## Preamble — what DISCOVER corrected (the load-bearing 12)

Three agents read the code (`discover/auth-and-registration.md`,
`discover/coaching-stack.md`, `discover/surfaces-and-deletion.md`, 1,031 lines,
every claim cited `path:line`). The brief's foundations table was accurate about
what EXISTS and wrong about what that existence LETS YOU DO. Ranked:

| #   | The brief (or a gate-1 decision) assumed                             | The code says                                                                                                                                                                                                                                                | Consequence                                                                                                                                |
| --- | -------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------ |
| X1  | D8: "billing is per-user, unchanged"                                 | Billing is **per-STUDENT**: `subscriptions.student_id` FK (`db/schema/0042:33`), `/subscriptions/verify` 409s a student-less caller (`SubscriptionRoutes.kt:49`), and the LLM cost spine is `student_llm_cost.student_id NOT NULL`                           | **D8 is a blocker, not a no-op.** A parent cannot subscribe, cannot hold a meter, cannot have spend recorded. Gate-2 **D10** decides this. |
| X2  | D9: "extends brief 0002's deletion work"                             | **Brief 0002 was never implemented** — no spec, no slices, no ledger. What exists is RFC 31's `DELETE /students/me`: two-row soft delete, no purge, no traversal, **no in-app UI at all** (Apple 5.1.1(v) currently unmet)                                   | D9 **builds** the partition rather than extending one. Sized accordingly in `family/09`.                                                   |
| X3  | The cost-report share link is "the invite wedge that already exists" | It carries **no identity and cannot learn one**: multi-use, forwardable, no expiry, one live row per student, token re-derivable from `(secret, id)`. RFC 155 D-F/D-G **forbid in code** the claim CTA an on-ramp needs                                      | The invite needs its **own** table. The claim on-ramp needs a new RFC superseding RFC 155 — deferred, see **D12**.                         |
| X4  | A parent is "representable today" as a user with no `students` row   | Representable in the DB, **unusable in the app**: iOS routes any user without a student profile to `.onboarding`, the STUDENT form (`AppViewModel.swift:217`, `UnicoachiOSApp.swift:27-33`), from 7 call sites                                               | Parent-first signup is a new client state, not a skipped screen. `family/03`.                                                              |
| X5  | Parent visibility is "a per-surface authorization decision"          | **There is no seam to decide at.** `resolveStudent(user)` = `getStudentForUser(user.id)` (`routing/CallerResolution.kt:33,50`); no code path resolves a student other than the caller's own, and **no client ever sends a student id** (everything is `/me`) | A whole slice exists just to create the seam: `family/05`.                                                                                 |
| X6  | D5: a 13+ DOB gate                                                   | **No date of birth is stored anywhere** — not in `students`, not in onboarding, and the Google/Apple sign-in routes cannot carry one                                                                                                                         | New column + migration + API field + iOS UI. `family/08`.                                                                                  |
| X7  | Invite tokens copy `verification_tokens`                             | `verification_tokens.user_id` is **NOT NULL**; an invite is sent to someone who may have no account. `cost_report_shares` (0073) is the better hashed-token precedent                                                                                        | Invite table modelled on the share-link shape, not the verification shape. `family/02`.                                                    |
| X8  | A parent could just accept an invite after signing up                | `EmailVerificationGate` is a **global interceptor** (`plugins/EmailVerificationGate.kt:32-42`) blocking every authenticated-but-unverified caller                                                                                                            | Invite-accept must be explicitly exempted, or acceptance deadlocks. `family/02`.                                                           |
| X9  | The parent coach is "a conversation with a different prompt"         | `convos.student_id` is **NOT NULL with no user/party column** (`db/schema/0006:57`), and the system prompt is selected **globally from config** (`CoachingService.kt:811-820`), with no per-conversation input                                               | `convos` gains a party discriminator + author user; prompt selection gains a per-party axis. `family/06`.                                  |
| X10 | The parent's coach would take a "which student" tool parameter       | 11 of 12 chat tools are `StudentScopedChatTool` whose **whole invariant** is that the id comes from the dispatcher and never from the model; `ToolRegistry` advertises every tool every turn with no subsetting                                              | The subject is dispatcher-injected, never model-supplied, and the parent gets a **separate read-only registry**. `family/06`.              |
| X11 | `convo_requests.kind` could carry a parent marker                    | `kind` is the **tool-result loop discriminator**; overloading it breaks the loop                                                                                                                                                                             | Party lives on `convos`, not on `convo_requests`.                                                                                          |
| X12 | D2: "either side unlinks at any time" covers the cost-report link    | On that surface there is **no per-parent lever**: revoke kills the single live link for every holder at once                                                                                                                                                 | Unlink governs the account link; the share link keeps its own all-or-nothing revoke. Stated in `family/04`.                                |

Two corrections **do not** change a decision and are recorded so no slice
re-litigates them: a student-less caller today gets a clean
`409
student_profile_required` on nine routes and a 404 on four — **nothing
500s**, so parent-shaped callers are safe to introduce incrementally; and
college search plus vocabularies already work with no student row, so an
unlinked parent has a non-empty product from day one.

## Two sibling briefs, read and reconciled

Landed on `main` while this brief sat between its gates; neither changes a
decision here, and both would be defects to ignore.

- **`0012-fafsa-support` (handle `fafsa`)** coaches the FAFSA. This brief's
  DEFERRED `family/11/the-parent-deadline-track` is the **parent-contributor
  half of that same subject** — the FSA ID, the parent's own section and IRS
  consent, verification, and the mixed-status path. Do not spec it from here
  without reading that brief; the honest outcome may be that `fafsa` absorbs it
  outright, which is a better result than two briefs coaching one form.
- **`0013-unicoach-on-the-web` (handle `web`)** duplicates the iOS app on the
  web. Every slice below names an **iOS** door, because that is the door that
  exists today, and `family/02/invite-and-accept` deliberately puts the accept
  page on public-web already (`ClientKeyGate` guards REST). A web client widens
  the doors; it does not change what is behind them. If `web` lands first, the
  parent surfaces should be built on both, and that is a sequencing question for
  the wave board, not a new decision.

## Standing product decisions carried in (from other briefs)

- **D10/brief 0001 (standing)** — Ian approves every new database table, with
  visible DDL at the /ship approval gate. Four slices below add tables and each
  says so in its own text.
- **D12/brief 0001 (standing)** — value before ask: invite, allow
  start/stop/resume/later, degrade gracefully on decline, never gate on
  completion. Written into the acceptance criteria of every user-facing slice
  here.

---

# Slices

### `family/01/the-link`

**Needs:**

- — (nothing: it is the spine every other slice authorises against, and no slice
  in this brief has to land before it)

**What and why.** The relationship itself: one table linking a parent user to a
student, many-to-many, role-typed. Nothing user-facing lands here; this is the
spine every later slice authorises against. It exists as its own slice because
getting the shape wrong is the one mistake this brief cannot cheaply undo.

**Foundations.** `users` (0001), `students` (0005), the schema conventions in
`CLAUDE.md` (own enumerations are `TEXT` + `CHECK IN (...)` plus one Kotlin
`enum class` with `fromValue`), and the versioned-aggregate pattern used by
`money_profiles` (0046).

**Decided here.** Many-to-many, both directions (D1, D2). Role column carrying
`parent` only (D6). A link is created only by accepting an invite (D2) — no API
mints one directly. Unlink is a soft delete and re-linking is allowed, so the
uniqueness index must be **partial** (`WHERE deleted_at IS NULL`) — unlike
`students_user_id_unique_idx`, which is deliberately total. Getting that
backwards makes "re-create at any time" impossible, which is an explicit
requirement.

**Left to /ship's design phase.** Whether the link carries its own version
history table; exact column naming; whether `role` gets a Kotlin enum now or on
the second value.

**Acceptance criteria.**

- **ADDS A TABLE — the RFC presents the proposed DDL explicitly at the approval
  gate** (standing D10), not buried in prose.
- A student links to many parents and a parent to many students, proven by test,
  not by schema reading.
- Unlink then re-link succeeds; the partial index is exercised by a test that
  would fail against a total index.
- No API surface and no user-visible change — this slice is reachable by nobody,
  and that is stated in its own RFC as the reason it is not the first beat.

**First-session test.** None — no door. The next slice opens it.

### `family/02/invite-and-accept`

**Needs:**

- BLOCKS family/01/the-link — accepting an invite writes the link row: same
  table, same contract, and there is nothing to write until it exists.

**What and why.** The only way a link is ever created: one side mints a
shareable invite link, the other opens it and accepts. Both directions, one
mechanism. This is where Ian's amendment lives — **the act of sharing the link
IS the inviter's consent** (D2), so acceptance is one click by the recipient and
the inviter is never asked again.

**Foundations.** `cost_report_shares` (0073, RFC 155) is the hashed-token
precedent to copy (hash at rest, raw token only in the link) — **not**
`verification_tokens`, whose `user_id NOT NULL` cannot express an invite to a
non-user (X7). The public-web two-step `GET`+`POST /verify-email`
(`public-web/.../Routing.kt:122-146`) is the accept-page shape, and it is on
public-web because `ClientKeyGate` guards REST.

**Decided here.** An invite is a link, sharable through any channel the inviter
chooses — in-app share sheet, text, email (D7); unicoach email delivery is one
channel, not the mechanism. Invites expire and are consume-once. An invite names
the role and, critically, **states the ceiling in the invite itself** (D3): what
the parent will and will not be able to see and do. The invite works whether or
not the recipient has an account; accepting mid-signup links atomically.
`EmailVerificationGate` is explicitly exempted for accept (X8) — a parent must
be able to accept before verifying email, or the flow deadlocks.

**Left to /ship's design phase.** Token derivation (random vs derived), expiry
window, whether an inviter may hold several live invites at once, the exact copy
of the ceiling statement.

**Acceptance criteria.**

- **ADDS A TABLE — DDL presented at the approval gate** (standing D10).
- Both directions work end-to-end: student invites parent, parent invites
  student.
- **Value before ask** (standing D12): the accept page explains what the link
  gives and what it does not before asking anyone to sign in or sign up;
  declining is a first-class outcome that leaves both accounts intact.
- A consumed, expired, revoked, or unknown token renders the same branded
  refusal — no oracle that distinguishes them (the `/report` precedent).
- Accepting while signed out, then signing up, produces exactly one link and
  never a duplicate.
- **Reachable:** the inviter's door is the iOS share sheet; the recipient's door
  is a public-web page opened from an ordinary link.

**First-session test.** A parent who has never heard of unicoach opens a text
message from their kid, reads what they are being asked to join, and either
accepts or does not — without creating an account to find out what it is.

### `family/03/the-parent-account`

**Needs:**

- BLOCKS family/02/invite-and-accept — a parent who accepts an invite must land
  somewhere, and today iOS routes a student-less user into the STUDENT
  onboarding form (correction X4), which is not a valid client state for them.

**What and why.** A parent-shaped account that works: parent-first signup, a
home surface that is not the student onboarding form, and the working-student
switcher — prominent with several students, quiet with one (Ian's requirement).

**Foundations.** `AppViewModel.resolveProfileState` (`:217`) and the seven call
sites that route `student_profile_required` to onboarding; `SettingsView` for
invite/unlink entry points; `PublicUser` already decodes on the client.

**Decided here.** Signup does not ask "are you a parent?" as a first question;
an account becomes parent-shaped by holding a parent link or by choosing the
parent path explicitly. A parent with no linked student still has a working
product — college search and vocabularies already work without a student row —
and the empty state says plainly what linking adds (the competitor report's
"pending link" lesson). The switcher is a persistent, visible control when the
parent supports more than one student, and recedes to near-invisible with one.

**Left to /ship's design phase.** Whether parent-ness is a column, a derived
property of the link table, or a client-side state; the exact switcher
placement; whether a `parents/me` route is needed at all.

**Acceptance criteria.**

- A user with no `students` row never sees the student onboarding form.
- With two or more linked students, the current subject is unambiguous on every
  screen that shows student data, and switching is at most two taps.
- With exactly one linked student, the switcher does not clutter the UI.
- **Value before ask** (standing D12): a parent can use the product before
  linking, and is invited — not forced — to link.
- **Reachable:** the iOS app, from signup through to a parent home screen.

**First-session test.** A parent signs up before their kid has ever heard of
unicoach, gets something useful in that session, and is shown — not made — to
invite their student.

### `family/04/parents-supporting-you`

**Needs:**

- BLOCKS family/01/the-link — this slice reads and soft-deletes link rows, so
  the table and its partial uniqueness index must exist first.
- CONFLICTS family/03/the-parent-account — both edit the iOS settings surface
  and the same new link client. A rebase risk, not an order: whichever lands
  second rebases.

**What and why.** The student's side of the relationship: a list of every parent
supporting them, what each can see, and a two-tap unlink. Ian's requirement, and
the competitor report's strongest recommendation — make the student the
sovereign of the link rather than burying it in settings.

**Decided here.** Both sides can unlink at any time and re-invite afterwards
(D2). The list states per parent what that parent can see — the ceiling made
visible, not just promised at invite time (D3). Unlinking is immediate, needs no
counter-approval, and never deletes the other account. With several parents
linked, each parent can see that the others exist (the Greenlight
duplicate-action lesson). **The cost-report share link keeps its own
all-or-nothing revoke** (X12) — it is not per-parent and this slice does not
pretend otherwise.

**Acceptance criteria.**

- A student sees every linked parent and unlinks any of them in two taps.
- A parent sees their linked students and can unlink from their side too.
- Unlink is immediate; the other party's account, data and own conversations
  survive untouched.
- Re-invite after unlink works, producing one live link.
- **Reachable:** iOS, from the drawer or settings, one level deep.

**First-session test.** A student who has just linked a parent can see exactly
what that parent can now see, and undo it without asking anyone.

### `family/05/the-subject-seam`

**Needs:**

- BLOCKS family/01/the-link — the authorisation check this slice introduces IS a
  live-link-row lookup, so there is no check to write without the table.

**What and why.** Today no code path can resolve a student other than the
caller's own, and no client ever sends a student id (X5). This slice creates
that seam once, correctly, for the read surfaces a parent is allowed: the
student's college list and the derived cost picture. It is the single
highest-risk slice in the brief — it converts "authorisation is structurally
impossible" into "authorisation is a check that must be right every time."

**Decided here.** The subject is explicit in the request; the server authorises
it against a live link row on every call, with no caching of the decision.
**View only** (D3) — a parent-authorised subject grants reads and never writes,
enforced at the service layer, not merely by omitting UI. Money follows the
FAFSA partition (D4): derived outputs are visible to both parties, raw
parent-supplied figures are not shown to the student, and each party owns its
own inputs. Coach conversations are **not** in this seam at all and never become
subject-addressable (D4).

**Left to /ship's design phase.** Path parameter vs subject header; whether the
existing `/students/me/...` routes stay as aliases; the shape of the
authorisation helper that replaces `resolveStudent(user)`.

**Acceptance criteria.**

- A parent with a live link reads the student's college list and cost picture; a
  parent whose link was severed gets the not-found outcome on the very next call
  — existence is never confirmed to an unauthorised caller (the
  `CoachingService` precedent).
- Every write path refuses a parent subject, proven per route by test.
- No route accepts a subject the caller has no link to, proven by a test that
  enumerates the routes rather than sampling them.
- A student's own `/me` behaviour is byte-for-byte unchanged.
- **Reachable:** nothing new is user-visible here; `family/06` is the door. This
  slice's RFC says so.

**First-session test.** None directly — but a parent opening their student's
college list in `family/06` is reading through this seam.

### `family/06/the-parent-coach`

**Needs:**

- BLOCKS family/05/the-subject-seam — the parent's tools read the student's list
  and cost picture through that seam; without it no code path can resolve a
  student other than the caller's own (correction X5).
- BLOCKS family/03/the-parent-account — the parent needs a home surface to chat
  from and a working-student selection to chat ABOUT.
- PREFER family/03.5/who-pays — a coach with no meter and no way to charge is an
  uncapped cost leak. Product judgement, not technical: Ian may override and
  ship the coaching before the billing answer.

**What and why.** The beat this brief exists for: the parent gets a coach of
their own that knows which student they are working with, reads that student's
list and cost picture, and coaches on money, timelines, and how to support
without directing. This is where the parent's value stops being potential.

**Foundations.** `convos` (0006), `CoachingService`, `SystemPromptCatalog`
(coach v35), `ToolRegistry`, `coaching_memory` (0019), and the money/cost tools
built by briefs 0003, 0005, 0006, 0008.

**Decided here.** `convos` gains a party discriminator plus an author user, with
`student_id` keeping its meaning of "about whom" so the cost spine and every
existing index survive (X9, X11). The parent gets a **separate read-only tool
registry** — the subject is injected by the dispatcher and never supplied by the
model (X10). Four cut points are mandatory and each is a named test: the
student's conversation content never reaches the parent's coach; coaching memory
derived from student chats never reaches it; the parent's turns never write to
student-owned state; and no extraction or commitment inferred from a parent turn
lands on the student's record. **The coach coaches the inversion explicitly** —
when a parent asks it to push a ranking or take over a task, it redirects to
money, logistics and emotional support (D3, and the parent-needs report's
evidence that selectivity does not predict outcomes).

**Left to /ship's design phase.** The parent prompt's full text and version pin;
which seven-ish tools the parent registry carries; whether the parent gets their
own coaching memory row space.

**Acceptance criteria.**

- **MAY ADD A TABLE and CHANGES `convos` — DDL presented at the approval gate**
  (standing D10).
- A parent asks about paying for their student's list and gets a cited,
  source-backed answer using the existing money machinery.
- Each of the four leak cut points has its own failing-first test.
- The coach never instructs the parent on how to direct the student; a
  prompt-level test covers the redirect.
- **Value before ask** (standing D12): the parent's first session delivers
  something before any profile is requested.
- **Reachable:** the parent's chat screen on iOS.

**First-session test.** A parent's first conversation ends with a plain answer
about what this list will cost their family and what they personally must do
next — not a form.

### `family/03.5/who-pays`

**Needs:**

- BLOCKS family/03/the-parent-account — a parent account must exist before it
  can be billed or metered. Decimal step because DISCOVER surfaced it after the
  order was set.

**What and why.** X1: billing is per-STUDENT, so a parent can neither subscribe,
nor hold a coaching meter, nor have spend recorded — and a Family-Sharing Apple
ID returns the same original transaction id, already owned by the student, so it
409s with no path to pay. Whatever gate-2 **D10** decides, it lands here.

**Acceptance criteria.** Set by D10; the slice is not startable until D10 is
answered. Must include: a parent's LLM spend is attributable, and a shared Apple
ID no longer hard-fails.

**First-session test.** A parent never meets a dead end that says their account
cannot pay.

### `family/07/the-money-plan`

**Needs:**

- BLOCKS family/06/the-parent-coach — the plan is a deliverable the parent's
  coach produces, so the coach must exist to produce it.
- PREFER family/04/parents-supporting-you — product judgement: finish the
  family-facing link surface before adding depth on the parent's side.

**What and why.** The parent-needs report's top-ranked finding: parents fund
roughly half the bill, yet fewer than 44% of families have discussed what all
four years cost, and sticker shock is the single largest stressor. The parent's
coach produces a written four-year money plan for the current list and a
scripted conversation to have with their student.

**Decided here.** The plan is derived from the existing canonical money store —
no new figures, no LLM free-association about numbers, every figure cited as
briefs 0003/0005/0008 already require. The scripted conversation covers the
three under-discussed topics the research names: all-years cost, field starting
salary, and money that does not have to be repaid.

**Acceptance criteria.**

- The plan names a total for all years, not one year, and says which figures are
  published and which are derived.
- It refuses honestly where the data cannot support a number (the standing bar
  from brief 0011).
- **Reachable:** produced in the parent's chat and retrievable afterwards.

**First-session test.** A parent finishes their first real session holding a
number for four years and a conversation to have this week.

### `family/08/the-age-floor`

**Needs:**

- — (nothing: correction X6 found no birthdate anywhere, so this is net-new
  column, migration, API field and iOS UI, touching no slice in this brief)

**What and why.** A neutral date-of-birth gate at student signup, refusing
under-13, which keeps unicoach entirely outside COPPA's
verifiable-parental-consent regime. Plus the written actual-knowledge escalation
path — suspend, confirm, delete — because the parent channel will generate "my
12-year-old" signals.

**Acceptance criteria.**

- **ADDS A COLUMN — DDL at the approval gate** (standing D10).
- Under-13 is refused at student creation on every signup path, including Google
  and Apple, which cannot carry a birthdate today (X6).
- The escalation path is written down and linked from the RFC.
- **Reachable:** the iOS onboarding form.

**First-session test.** A 12-year-old cannot create a student account, and is
told why in words a 12-year-old understands.

### `family/09/the-deletion-partition`

**Needs:**

- BLOCKS family/01/the-link — deletion must sever link rows, which requires the
  table.
- BLOCKS family/03/the-parent-account — there must be a parent account to
  delete; today the delete route 404s for a caller with no student row.

**What and why.** D9, resized by X2: brief 0002 was never implemented, so this
builds author-partitioned deletion rather than extending it. Today's delete is a
two-row soft delete with no traversal and **no in-app UI at all**, which leaves
Apple's guideline 5.1.1(v) unmet for both roles.

**Decided here.** Deleting either account severs every link, notifies the other
party that the link ended without saying why, and leaves the other party's own
account, conversations and inputs intact (D9). In-app deletion exists for both
roles.

**Acceptance criteria.**

- A parent can delete their account in-app; today the route 404s for them.
- A student's deletion leaves each linked parent's own data intact, and
  symmetrically, proven per table.
- Link severance notifies without disclosing a reason.
- **Reachable:** iOS settings, both roles.

**First-session test.** Not a first session — but a parent who wants out gets
out, in the app, without email.

## Deferred, with reasons (not dependencies)

- **`family/10/claim-your-account`** — turning the cost-report share link into a
  parent-signup on-ramp. **Status: DEFERRED** by D12: the link carries no
  identity and cannot learn one, and RFC 155's D-F/D-G forbid the CTA in code,
  so this needs a new RFC superseding them. Valuable; not first.
- **`family/11/the-parent-deadline-track`** — the parent's own FAFSA-contributor
  deadlines (FSA ID, contributor section and IRS consent, CSS Profile
  financials, verification, the PLUS decision), including the mixed-status and
  no-SSN path. **Status: DEFERRED** by D13 — real, evidenced, and large enough
  to deserve its own beat after the spine lands.
- **`family/12/the-plus-guardrail`** — an ability-to-pay check before any Parent
  PLUS borrowing discussion. **Status: DEFERRED** by D13. The research ranks
  this the highest-stakes single intervention available; it is deferred on
  sequencing, not on doubt.
- **`family/13/the-private-entry`** — a per-entry student veil on the college
  list. **Status: DEFERRED** by D4, which sets list visibility to on by default
  for the first beat.

## Wave board

Computed from the `Needs:` edges above, not asserted beside them — slices in one
wave are safe to run in parallel.

| Wave | Slices                                                                                         | Note                                                                     |
| ---- | ---------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------ |
| 1    | `family/01/the-link`, `family/08/the-age-floor`                                                | The only two with no unmet edge; fully independent of each other         |
| 2    | `family/02/invite-and-accept`, `family/05/the-subject-seam`                                    | Both need only `01`; they touch different layers                         |
| 3    | `family/03/the-parent-account`                                                                 | Needs `02` — a parent who accepts must land somewhere                    |
| 4    | `family/04/parents-supporting-you`, `family/03.5/who-pays`, `family/09/the-deletion-partition` | `04` CONFLICTS `03` on the iOS settings surface — a rebase, not an order |
| 5    | `family/06/the-parent-coach`                                                                   | The beat: value becomes live end-to-end here                             |
| 6    | `family/07/the-money-plan`                                                                     | The parent's first-session payoff                                        |

**The aha is `family/06/the-parent-coach`** — the first moment a parent gets
something no other product gives them. Everything before it is the machinery
that makes it honest.

---

# Gate 2 decisions (APPROVED 2026-09-14)

_Approved by Ian 2026-09-14, all on their defaults. Verbatim: "approve but no
D16 ammendment" — he was offered an amendment splitting D16 into a launch cut
(waves 1-4) and a value cut (waves 5-6) and declined it, so D16 stands as
written: one beat, ending at `family/07/the-money-plan`._

- **D10 — who pays for a parent.** X1 makes this unavoidable: billing and
  metering are keyed to `student_id`, so a parent today cannot subscribe, cannot
  be metered, and a shared Apple ID hard-fails. Options: (a) **parent coaching
  rides the linked student's subscription** — a parent linked to a subscribed
  student is covered, an unlinked parent gets a small free allowance; (b) the
  parent buys their own subscription, which requires making billing per-user
  first; (c) parent coaching is free for now, metered but never charged.
  **Default: (a)**, because the family is buying one product, it needs no
  billing refactor to ship the beat, and it prices the invite as a benefit
  rather than an upsell. Whichever is chosen lands in `family/03.5/who-pays`.
- **D11 — deletion builds, not extends.** Accept X2: `family/09` implements
  author-partitioned deletion and in-app deletion for both roles from scratch,
  and this brief therefore closes Apple guideline 5.1.1(v), which is currently
  unmet. **Default: yes.**
- **D12 — the claim-your-account on-ramp is deferred.** The existing share link
  cannot become an invite (X3), and the CTA it would need is forbidden in code
  by RFC 155. **Default: deferred to its own slice with its own superseding
  RFC**, rather than expanded into this beat.
- **D13 — the parent deadline track and the PLUS guardrail are deferred to a
  follow-on beat.** Both are top-ranked in the research; both are big enough to
  starve the spine if pulled in now. **Default: deferred**, recorded as named
  slices with reasons so they are not silently lost.
- **D14 — the subject is explicit and authorised per call.** No implicit
  "current student" server-side session state; the request names the subject and
  the server checks a live link every time. **Default: yes** — implicit
  server-side subject state is how a parent ends up reading the wrong child's
  data.
- **D15 — the parent's coach is a separate read-only tool registry**, with the
  subject dispatcher-injected and never model-supplied, preserving the existing
  safety invariant (X10). **Default: yes.**
- **D16 — the first beat ends at `family/07/the-money-plan`.** Waves 1-5 above;
  everything else is a later beat. **Default: yes.**
- **D17 — slice order as the wave board states**, with `family/08/the-age-floor`
  in wave 1 because it is independent and cheap, and the legal floor should not
  arrive after the parents do. **Default: yes.**
