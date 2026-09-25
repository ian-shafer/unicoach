# RFC 240 — The coach asks once: offering a parent invite in chat

**Slice:** `family/02.5/the-coach-asks-once` (brief 0014 "Parents in the room",
decision D20). **Lane:** A (rfc) — new behaviour, a new chat tool, a new
commitment lens, a coach prompt version. **Base:** `main@e0b612cb`.

## Why

RFC 232 gave a student a way to invite a parent or supporter: one door, in iOS
Settings, under "Family & Supporters". The coach does not know it exists. The
coach _does_ have a sanctioned moment for the OTHER way to bring a parent in —
the Family Cost Report share link (RFC 155, RFC 160) — so today the only thing
the coach can ever offer a family is the read-only report.

Brief 0014 D20 closes that gap: the coach gains ONE sanctioned moment to offer
connecting a parent properly, **alongside — not instead of** — the report offer,
and **the existing opt-out covers both**. A student who said "stop suggesting
sharing" must not be pestered about invites through a second door.

## What this is, in one line

A thin chat adapter over RFC 232, plus a second deterministic nudge in the
synthesis sweep that reuses RFC 160's machinery rather than inventing a second
kind of "sanctioned moment".

## Detailed Design

### 1. The offer is a commitment row, exactly like the share nudge

RFC 160's "one sanctioned moment" is not a prompt feature. Synthesis (queue
worker, read phase, under the per-student advisory lock) WRITES a `commitments`
row with `lens='share_report'`, `disclosure='explicit'` and a fixed code-written
statement; `CoachingService.startConvo` renders every open explicit commitment
as a bullet in the SYSTEM prompt of a NEW conversation only; a successful first
reply flips the row to `fulfilled`. "At most once" is that row's lifecycle.

This RFC adds a SECOND lens and a second deterministic step beside the first. It
does not touch the share nudge's predicate, statement, or tests.

- `CommitmentLens.INVITE_SUPPORTER("invite_supporter")`
  (`db/src/main/kotlin/ed/unicoach/db/models/CommitmentLens.kt`).
- Migration `db/schema/widen-commitments-lens-invite-supporter.sql` (a slug, no
  number — RFC 180): drop and recreate `commitments_lens_check` over
  `('gap','timing','contradiction','share_report','invite_supporter')`.
- Code-written, never LLM-proposed: the exhaustive `when` in
  `LLM_PROPOSABLE_LENSES` maps the new member to `false`, so the forced-tool
  schema never offers it and `parseOutput` rejects it.

### 2. When the offer fires — and why it can never collide with the share offer

A new `InviteNudgeVerdict` / `InviteNudgeSuppression` pair, mirroring
`ShareNudgeVerdict` / `ShareNudgeSuppression`: a skip is a NAMED outcome, logged
at DEBUG, so "why was my student never asked?" is answerable from the log.

Evaluated in this order, in `SynthesisService.readPhase`, immediately after the
share-nudge step, under the same held advisory lock and before every LLM-phase
gate:

| # | Condition                                                           | Suppression            |
| - | ------------------------------------------------------------------- | ---------------------- |
| 1 | `config.inviteNudgeEnabled` is false                                | `DISABLED`             |
| 2 | no live cost-report share for this student                          | `NO_LIVE_SHARE`        |
| 3 | `ShareEventsDao.hasOptOut(studentId)`                               | `OPTED_OUT`            |
| 4 | any active `supporter_student_links` row for this student           | `ALREADY_LINKED`       |
| 5 | any live (unconsumed, unrevoked, unexpired) `supporter_invites` row | `LIVE_INVITE`          |
| 6 | open commitments at `maxOpenCommitments`                            | `OPEN_SET_AT_CAP`      |
| 7 | an open `invite_supporter` commitment exists                        | `OPEN_INVITE_NUDGE`    |
| 8 | latest `invite_supporter` younger than `inviteNudgeCooldown`        | `COOLDOWN_NOT_ELAPSED` |

**Condition 2 is the design's load-bearing choice.** The share nudge is
suppressed _because_ a live share exists (`LIVE_SHARE`); this nudge REQUIRES
one. The two predicates are therefore mutually exclusive by construction: no
synthesis pass can ever write both, and no opening can carry both bullets. That
is "alongside, not instead of" enforced in code rather than asked of the model.

It is also the honest reading of D20 — the report link stays the low-friction
first step, and the account invite is offered "when a student wants ongoing
support or after a parent has opened the report". We offer it to a student who
has ALREADY taken the first step. We do not condition on the parent having
opened the report: `cost_report_shares` records the share, not the read, and a
figure we do not hold is not one we will guess at.

**Deliberate divergence from the share nudge's re-fire rule.** The share nudge
re-fires on cooldown AND a changed college list ("the re-nudge must have
something new to point at"). The invite nudge has no list-change half. Its
"something new" is condition 5: a re-offer is only reachable once the previous
invite lapsed unused, was revoked, or was consumed-then-unlinked — each of which
is itself the new fact. Adding a list-change test would suppress the one student
who most needs asking again: the one whose first invite expired untouched.

Statement text, a constant, in the same register as the share nudge's:

> They have already sent a parent the family cost report. When the moment is
> right, offer to connect that parent properly — their own account and their own
> coach workspace, and the student can end one supporter's access without ending
> anyone else's.

### 3. The tool: `invite_supporter`

`service/src/main/kotlin/ed/unicoach/coaching/family/InviteSupporterChatTool.kt`,
modelled line for line on `ShareCostReportChatTool` (RFC 155):

- `StudentScopedChatTool`, `noArgumentToolDefinition(TOOL_NAME, DESCRIPTION)`,
  first line
  `unknownFieldsReason(input, emptySet())?.let { return errorObject(it) }`.
- Total: a deployment-level "cannot do this now" is a RESULT with
  `link_created: false` and a reason, never a throw.
- One result key `supporter_invite`, with `link_created` present in BOTH shapes,
  `url`, `expires_at`, and the same ceiling the `/invite` page shows the
  recipient (`InvitePage.inviteCeiling`), RESTATED for speech as named
  constants. It is a restatement and not the page's own strings: `inviteCeiling`
  is private and lives in another Gradle module, so nothing binds the two, and
  the day they diverge a student is told something the recipient's page
  contradicts. A single home for that copy is a change this RFC does not make;
  it is named as an open item.
- The raw token is never returned or logged separately; it rides inside `url`.

**The one id hop.** `StudentScopedChatTool.execute` hands a `StudentId`;
`SupporterInviteService.create` wants a `UserId`. A new
`SupporterInviteService.createForStudent(studentId, relationship)` resolves
`students.user_id` inside the transaction `create` already opens, so the tool
stays a thin adapter over one service call and no tool holds a `Database`.

**No relationship argument, and an unstated relationship is `supporter`.** Ian's
call at the gate: the sentence a recipient reads is "invited you to connect as
their **parent or supporter**".

Neither door states a relationship today. iOS sends `nil`, and the coach has no
honest way to ask. RFC 232's absence arm stored `parent` for that case, so the
page told every recipient they had been named a parent when nobody had named
them anything — the same shape as the defect RFC 232 fixed on the unreadable
arm, one step milder.

Three edits, no migration, no nullable column:

- The absence arm of the REST door and the coach tool both mint
  `SupporterRelationship.SUPPORTER`, D22's deliberate catch-all — "a person who
  supports a student is described by what they do". The unreadable arm is
  untouched: `{"relationship":"uncle"}` is still a 400 naming the word and the
  legal set, and absence and unreadability stay separately decided.
- `InvitePage.recipientLabel(SUPPORTER)` renders **"parent or supporter"**. The
  label lives at the boundary by the schema convention in CLAUDE.md, so the
  stored token stays the vocabulary word and only the sentence a human reads
  changes. `PARENT`, `GUARDIAN` and `GRANDPARENT` keep their own labels for the
  day a door actually asks.
- The link row an accept creates inherits the same word, so a supporter whose
  relationship nobody stated is recorded as a supporter rather than as a parent.

This changes behaviour the iOS door already ships, deliberately: a word nobody
stated should not read as a claim about a family. `relationship` is not part of
the link's uniqueness rule and is correctable in place (D22), and no surface but
this page renders it.

**Repeat asks mint a fresh link.** `supporter_invites` stores only a SHA-256
hash of the token, so the previous link is unrecoverable by construction — the
coach can never re-hand it, the way `share_cost_report` re-hands the report
link. Multiple live invites are legal by design (RFC 232 §2: two parents). So
the tool mints a new single-use link on every ask, and the DESCRIPTION and the
result say plainly that each link is for one person, works once, and dies on the
date given. The nudge's condition 5 is what stops the COACH volunteering a
second one; a student who ASKS is never refused.

### 4. The opt-out covers both offers (D20), at two enforcement points

The opt-out is an append-only `share_events` row of kind `opted_out`. It is read
in exactly two production places today, and this RFC touches both:

1. `SynthesisService` — condition 3 above. This is the guarantee the acceptance
   criterion names, and it is enforced where the offer is WRITTEN, not asked of
   the model.
2. `CostReportShareService.stopOffers` — its `dropOpenShareNudges` helper today
   filters `lens == SHARE_REPORT`; it widens to drop open `INVITE_SUPPORTER`
   rows too, under the same `share_offers_opted_out` drop reason. An
   already-written invite nudge must not outlive the opt-out, including one that
   raced in after a first opt-out (the helper already runs on the
   `AlreadyStopped` path).

The tool is NOT gated by the opt-out, mirroring the standing rule for
`share_cost_report`: a student who said "stop offering" can still ask, and the
door still works. The opt-out silences the OFFER, not the capability.

No new opt-out tool and no rename: `stop_cost_report_offers` keeps its name and
its behaviour, and the prompt says in one added sentence that it covers both.
Renaming a registered tool changes the model's contract and would break the
acceptance criterion that the share offer is unchanged.

### 5. Prompt prose, and the version it mints

`prompts/coach-system-prompt.md`, one sentence per line, a new paragraph placed
after the existing share-nudge paragraph, plus ONE appended sentence to the
stop-offers paragraph. The pinned paragraph OPENERS of the existing regions do
not change, so the share offer's prose behaviour is unchanged.

New paragraph, verbatim:

> Connecting a parent to their own account is a different thing from sharing the
> family cost report, and you offer it separately. When your opening reflections
> include one about connecting a parent, that reflection is the sanctioned
> moment for it: raise it once, naturally, where it fits the conversation, and
> only call invite_supporter if the student says yes. Never raise it in an
> opening about money, and never make anything you do conditional on it.
> invite_supporter returns a private link the student sends to one person: it
> works once, and it stops working after the date the result names. Say plainly
> what connecting gives that the report link does not: the parent gets their own
> account and their own coach workspace, and the student can end one supporter's
> access without ending anyone else's. Say plainly what it does not give: the
> parent never reads the student's coach conversations, and cannot change the
> student's college list, profile or tasks. If the student declines or defers,
> the topic is closed for this conversation: let it go without residue, and let
> it change nothing about what you do next. If the student asks to invite
> someone, call invite_supporter whenever they ask — asking is never refused,
> and a student may invite more than one person.

Appended to the stop-offers paragraph:

> That stop covers both offers: a student who has told you to stop suggesting
> sharing is not asked about connecting a parent either.

The version label, the seed migration and the `systemPromptVersion` pin are
GENERATED by `bin/prompt-seed` and hand-written by nobody. The author appends
one comment-log entry above the pin with no version label. The rollback version
is the catalog tip at land time, named in the report.

### 6. Config and rollback

Beside the share-nudge keys, in the `synthesis` block:

    inviteNudgeEnabled = true            # SYNTHESIS_INVITE_NUDGE_ENABLED
    inviteNudgeCooldownDays = 14         # SYNTHESIS_INVITE_NUDGE_COOLDOWN_DAYS

Rollback is config plus a prompt version, not a flag invented for the purpose:
`SYNTHESIS_INVITE_NUDGE_ENABLED=false` stops the offer ever being written, and
re-pinning the previous coach prompt version returns the coach's prose. The tool
remains registered and reachable on an explicit student ask. A full backout is a
revert.

### 7. What this RFC does NOT do

- No new table, and no column added to any table. The only DDL is one CHECK
  widening on `commitments`.
- No parent-side surface: an accepted invite still lands a supporter in the
  product `family/03` will build. The "own coach workspace" sentence is RFC
  232's own landed page copy, repeated here so the coach and the page say one
  thing; `family/06` is what makes it true.
- No door for revocation. `SupporterInviteService.revoke` still has no
  production caller (RFC 232 left it that way); giving the coach an undo is a
  slice, not a detail, and it is named as an open item.
- No rate limit on minting. There is none on the REST door either; restraint
  lives in the offer discipline, and a student who asks repeatedly is a student
  inviting several people.

## Files Modified

**New**

- `db/schema/widen-commitments-lens-invite-supporter.sql` — CHECK widening.
- `service/src/main/kotlin/ed/unicoach/coaching/family/InviteSupporterChatTool.kt`
- `service/src/test/kotlin/ed/unicoach/coaching/family/InviteSupporterChatToolTest.kt`

**Changed**

- `db/src/main/kotlin/ed/unicoach/db/models/CommitmentLens.kt` — new member.
- `db/src/main/kotlin/ed/unicoach/db/dao/SupporterInvitesDao.kt` — new
  `findLiveByStudent(session, studentId)` read over the existing partial active
  index (today every read is by token hash).
- `service/src/main/kotlin/ed/unicoach/coaching/synthesis/SynthesisService.kt` —
  the second nudge step, its verdict/suppression types, the statement constant,
  the `LLM_PROPOSABLE_LENSES` arm.
- `service/src/main/kotlin/ed/unicoach/coaching/synthesis/SynthesisConfig.kt` —
  two fields.
- `service/src/main/resources/service.conf` — two keys; the coach prompt
  comment-log entry (the pin line itself is the generator's).
- `service/src/main/kotlin/ed/unicoach/family/SupporterInviteService.kt` —
  `createForStudent`.
- `service/src/main/kotlin/ed/unicoach/coaching/report/CostReportShareService.kt`
  — the opt-out drops open invite nudges too.
- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt` — one entry in
  the single tool list.
- `rest-server/src/main/kotlin/ed/unicoach/rest/models/SupporterInviteRequests.kt`
  — the ABSENCE arm mints `supporter`, not `parent`; the unreadable arm is
  untouched.
- `public-web/src/main/kotlin/ed/unicoach/web/render/InvitePage.kt` — the
  recipient label for `supporter` becomes "parent or supporter".
- `prompts/coach-system-prompt.md` — the new paragraph and the added sentence.
- `service/src/test/kotlin/ed/unicoach/coaching/SystemPromptCatalogTest.kt`,
  `service/src/test/kotlin/ed/unicoach/coaching/synthesis/SynthesisServiceTest.kt`,
  the cost-report share service test, and the routing test that proves the tool
  is advertised.
- `db/schema/ORDER` and `db/schema/seed-coach-system-prompt-v<N>.sql` —
  GENERATED by `bin/prompt-seed` / `ship-order`.

## Implementation Plan

0. The relationship word (gate decision 4): the absence arm, the recipient
   label, and their tests. Independent of everything below, so it lands green
   before the coach work starts.
1. `CommitmentLens` member + the CHECK-widening migration; `db-migrate` green.
2. `SupporterInvitesDao.findLiveByStudent` + its DAO test.
3. `SupporterInviteService.createForStudent`.
4. `InviteSupporterChatTool` + its test; register it in `Application.kt`; the
   reachability assertion.
5. The synthesis step: verdict types, predicate, statement, config, and the full
   suppression-by-suppression test set, headed by the opted-out case.
6. `CostReportShareService.stopOffers` widening + test.
7. Prompt prose, then `bin/prompt-seed -b "$BASE_SHA"`, then
   `SystemPromptCatalogTest` entries, then the suite.

Order matters at step 7 only: the catalog test cannot be green until the seed is
generated, and the generator must not be hand-anticipated.

## Tests

**The criterion this slice exists for**

- `SynthesisServiceTest`: a student with a live share and an `opted_out` share
  event gets NO `invite_supporter` commitment, and the verdict is `OPTED_OUT`.
- `CostReportShareServiceTest`: `stopOffers` drops an OPEN `invite_supporter`
  commitment with reason `share_offers_opted_out`, on both the first-stop and
  already-stopped paths.

**The offer predicate**

- One test per suppression reason (`DISABLED`, `NO_LIVE_SHARE`, `OPTED_OUT`,
  `ALREADY_LINKED`, `LIVE_INVITE`, `OPEN_SET_AT_CAP`, `OPEN_INVITE_NUDGE`,
  `COOLDOWN_NOT_ELAPSED`) plus the eligible case.
- Mutual exclusion: one pass over a student with no live share writes the share
  nudge and no invite nudge; one pass over a student WITH a live share writes
  the invite nudge and no share nudge.
- The share nudge's existing tests are untouched and must stay green.

**The tool**

- No-argument contract: an unknown field returns an error object, not a throw.
- A real mint against a real DB: the result carries `url` and `expires_at`, the
  row's `token_hash` is not the raw token, and no field of the result is the raw
  token.
- Two calls mint two independently live invites (the two-parent case).
- A closed inviter endpoint returns `link_created: false` with a reason.
- An opted-out student who ASKS is still minted a link: the opt-out silences the
  offer, not the capability. The synthesis step reads the same `opted_out` event
  and declines to offer; a gate added at this door would take the way to invite
  a parent away from the student who asked for it.

**Reachability**

- An assertion that the production tool registry advertises `invite_supporter`.
  Dropping a tool from `Application.kt`'s list breaks no test today; a slice
  whose whole value is "the coach can now offer this" must not be able to ship
  unreachable.

**The relationship word (gate decision 4)**

- `SupporterInviteRoutingTest`: a mint with NO relationship field stores
  `supporter`; a mint with `{"relationship":"uncle"}` is still a 400 that names
  the word and the legal set and writes no invite; an explicit
  `{"relationship":"parent"}` still stores `parent`.
- `InviteRoutingTest` (public web): the page for an invite whose relationship is
  `supporter` reads "invited you to connect as their parent or supporter"; the
  page for an explicit `grandparent` invite still reads "grandparent".
- The accept path records the invite's own relationship on the link row.

**Prompt**

- `SystemPromptCatalogTest`: the new paragraph's opener, the tool name read from
  the tool class (not retyped), and the pinned body byte-equal to the authored
  file.
