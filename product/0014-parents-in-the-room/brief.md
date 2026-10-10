# Brief 0014 — Parents in the room: linked accounts, and a coach for the parent too

Status:

- Phase: **DISCOVER — gate 1 APPROVED by Ian 2026-09-14 (D1-D9, one amendment to
  D2), discovery agents dispatched.**
- Gate 1 (prioritisation): **APPROVED by Ian 2026-09-14.** Verbatim:
  "`A link
  exists only after invite + explicit accept, both directions` yes,
  and the act of sharing a link is acceptance. So a student can share a link
  with a parent, and the parent can click link and accept. That's all that needs
  to happen; the student does not need to do more. agree with all other
  decisions" — D2 amended accordingly (the invite IS the inviter's consent; no
  counter-acceptance step); D1, D3-D9 approved on defaults.
- **Post-gate decisions (Ian, 2026-09-14): D18, D19, D20** — recorded in
  `spec.md`. **D18 SUPERSEDES D12**: parent signup from the Family Cost Report
  is a REQUIREMENT, not a deferred nicety ("otherwise the report is a dead end
  for the parents"), so `family/10/claim-from-the-report` is scheduled in wave
  4, inside the first beat. **D19** answers the consent question D18 forces: the
  report link is multi-use, forwardable and unexpiring, and RFC 155 D-F says it
  "is not proof of who sent it", so claiming an account from it is immediate but
  the LINK is a request the student approves — the one place this brief does not
  apply "sharing is consent", because that rule was written for a link minted
  for one named recipient. **D20** closes a spec defect the brief shipped with:
  the coach's existing share moment was never mentioned, so nothing said which
  of two doors it should offer; new slice `family/02.5/the-coach-asks-once`.
- Gate 2 (spec): **APPROVED by Ian 2026-09-14 — 8 decisions (D10-D17), all on
  their defaults.** Ian was asked whether an MVP for launch (parents register
  and link with students) was in scope, was shown that it completes at the end
  of wave 4, and was offered an amendment to D16 naming a separate "launch cut"
  and "value cut". He declined it: verbatim "approve but no D16 ammendment".
  **D16 therefore stands as committed — the first beat ends at
  `family/07/the-money-plan`, one beat, not two cuts.**
- Handle: **`family`** (slice IDs `family/NN.S/name`).
- **Post-/ship decisions (Ian, 2026-09-15): D21, D22** — recorded in `spec.md`,
  taken at RFC 221's approval gate and SUPERSEDING D6. **D21**: the adult side
  is its own entity (`supporters`, one row per adult user, mirroring
  `students`), and a link references that entity, never a user account — the
  parent coach (`family/06`) and the billing answer (`family/03.5`) both need a
  home on it, and it settles `family/03`'s open question about where parent-ness
  lives. **D22**: the schema vocabulary is neutral and the relationship word is
  honest — `supporter_student_links.relationship` carries `parent`, `guardian`,
  `grandparent`, `supporter`, with `supporter` as the catch-all rather than
  `other`. Ian's framing: "It's not necessarily parents. But most of the time it
  will be parents." No user-facing copy changes; every surface still says
  "parent" until a slice specifies otherwise.
- Ledger:
  - `family/01/the-link` LANDED as RFC 221 (`main@27b447f4` + `f650f058`,
    2026-09-15) — the link spine: `supporters` (one row per adult user) and
    `supporter_student_links` (many-to-many onto students, four relationship
    words, soft-delete unlink with a PARTIAL active-pair unique index so
    re-linking works), both versioned with history tables, plus database guards
    refusing a self-link, a link to a closed account, a physical delete, a
    frozen column change and an update that does not advance version. No door:
    nothing reaches these tables until `family/02/invite-and-accept`.
  - `family/08/the-age-floor` LANDED as RFC 222 (`main@0925c195` + `22e6b091`,
    2026-09-15) — a neutral date-of-birth question on the iOS onboarding form
    and a 13+ floor enforced in Kotlin at student creation, so every sign-in
    path is covered; under-13 is refused with `403 age_requirement_not_met` and
    writes nothing, and the under-13 actual-knowledge runbook
    (`policy/under-13-actual-knowledge.md`) is written down.
  - `family/02/invite-and-accept` LANDED as RFC 232 (`main@8c4d7dca`,
    2026-09-23) — a student can now invite a parent or supporter, and the person
    they invite can accept without installing anything. In the iOS app, Settings
    gains a **"Family & Supporters"** section; the invite action mints a private
    link and opens the normal iPhone share sheet with the message already
    written. The recipient taps the link, lands on a WEB page naming who invited
    them, and is connected either by signing in to an existing account or by
    creating one in the same visit. The link is single-use and expires after 14
    days; all five refusal causes — expired, used, revoked, self-sent, closed
    account — show the SAME neutral message, so the page never reveals whether a
    link existed. New table `supporter_invites` stores the token only as a
    SHA-256 hash, with a test proving the raw token is absent from the row;
    migration `supporter-invites.sql` (no number, RFC 180). Rollback is config,
    not a flag: `SUPPORTER_INVITE_TOKEN_TTL` shortens the window and
    `SUPPORTER_INVITE_URL_BASE` moves the destination; backing the feature out
    is a revert. Review caught a defect its OWN earlier fix had introduced,
    found independently by eight lenses: an unreadable relationship word
    silently became `parent` while the 400 arm was unreachable dead code, so
    `{"relationship":"uncle"}` returned 201 Created with the wrong relationship
    recorded about a family. Absence and unreadability are now decided
    separately and a misspelling is refused by name. Revocation is built end to
    end but reaches no user — a door for it is a new slice. Phase 5 3,583
    executed, 0 failures; the gate ran the full check in 802s, 0 failures.
  - `family/03/the-parent-account` LANDED as RFC 241 (`main@60aae041`,
    2026-09-25) — a parent now has an account of their own. An account holding
    neither a student profile nor a supporter row is asked ONCE, inside the app,
    "who are you here for?" — "I'm the student" or "I'm supporting a student" —
    where before iOS routed it into the STUDENT onboarding form from eleven call
    sites (correction X4). Choosing the supporter path mints the `supporters`
    row and lands on a parent home: what linking adds, the invite action,
    college search (which works with no student row), and settings. With one
    linked student the home names them quietly; with two or more it carries a
    switcher built from the design system's own pick-one control, and the
    selection is held per user id ON THE DEVICE, because D14 forbids server-side
    "current student" state. Both role-choice paths are reversible while
    unanswered. Two new doors and **no schema change at all** — no table, no
    column, no migration: `POST /api/v1/supporters` (idempotent, 201 then 200,
    one refusal `409 supporter_closed`) and `GET /api/v1/supporters/me` (the
    supporter and the roster, or 404). The roster read is ONE statement that can
    return only the link id, the student id, the relationship word and the
    student's display name — never a date of birth or a graduation date — and a
    response test plus the published OpenAPI schema both assert it, because
    reading a linked student's data is `family/05` and this slice must not
    decide it by accident. `CallerAuthentication` was SPLIT out of
    `CallerResolution` so the supporter doors cannot send
    `student_profile_required` at all. **D21's open question is answered in
    code**: parent-ness is the `supporters` row, minted by the invite accept or
    now by the explicit choice, and `SupporterService` is its single writer.
    Also repairs a pre-existing break RFC 232 left: the iOS test target did not
    compile on `main`, so nothing iOS could be tested or photographed. Review
    ran 41 lenses over four tiers and applied 40 fixes; five mattered, and all
    five were invisible to a green suite — a sign-out during a roster refresh
    could resurrect the home, "I'm the student" was a one-way door with no
    return, a fifth relationship word would have cost a parent the WHOLE home, a
    closed supporter row answered `400 "already exists"` about a row that does
    not exist, and the roster join did not check the `users` row it renders the
    name from. Two held decisions were challenged under the dissent clause: "no
    refresh on appear" was OVERTURNED by two lenses independently and became a
    fix; the `defer` on the re-resolution latch was upheld with its reason
    written at the line. Gate: full check, 1,135s, 0 failures, verified tree
    recorded. iOS 722 tests, 0 failures; 84 snapshots, 0 drift on the 74
    pre-existing scenes.
  - `family/03.5/who-pays` LANDED as RFC 244 (`main@04260df0` + `1b3e7506`,
    2026-10-10) — a parent's account can now be paid for. Billing and metering
    moved from the STUDENT to the ACCOUNT: `subscriptions.user_id` replaces
    `student_id` on the live table and on `subscriptions_versions`, the gate
    reads ONE account-keyed cost view (`account_llm_cost`, a union over the
    student and supporter spines) and a parent's LLM spend finally has a home
    (`supporter_llm_costs` + `supporter_llm_cost`), so an allowance can be
    enforced rather than asserted. `POST /api/v1/subscriptions/verify` no longer
    refuses a caller for having no student profile; new door
    `GET /api/v1/supporters/me/coaching-usage` (404 when the caller holds no
    `supporters` row, no dollars and no tokens on the wire, `coverage.kind` =
    `own_subscription` | `free_allowance`); and the parent home gained a
    "Coaching" block that says how their coaching is paid for. On the student
    side the shared-Apple-ID wall stops lying: it names the situation, says to
    use a different Apple ID, withdraws a Subscribe button that cannot succeed —
    and gives that fact its own rendered state, so it does not vanish with the
    next refresh — while every refusal logs one searchable
    `subscription_apple_id_already_bound` line with both account ids. **D10 is
    SUPERSEDED by D23** (below): Ian rejected "the parent rides the student's
    subscription" at the approval gate — "I thought that we'd have two
    subscriptions always" — so every account pays for itself, and the
    shared-Apple-ID coverage path was deliberately NOT built but IS counted.
    Three migrations, no new configuration key, and no new entitlement basis.
    Review: 41 lenses over four tiers, 4 fix passes; it caught a corrupt
    `db/schema/ORDER` (the rebase re-introduced the cut design's lines through
    union merge, so the corpus could not migrate and every earlier green run was
    meaningless), a raw `java.sql.SQLException` crossing out of `:db` on a
    billing write, a test that could not fail, a dangling role reference
    published as `404` instead of failing closed, "which statuses entitle"
    written in four places and declared in none, and BOTH iOS dead ends — the
    refusal dropped on the app-open path, and its mirror image, a withdrawal
    that outlived its premise so a parent who switched Apple ID or subscribed
    could not buy until relaunch. Gate: full scope, verified tree recorded.
    Final suite 4,203 executed, 0 failures; iOS 748 executed, 0 failures, 44
    snapshot scenes, 0 pixels moved.
  - `family/02.5/the-coach-asks-once` LANDED as RFC 240 (`main@4f337d78` +
    `f6eda807`, 2026-09-25) — the coach now has ONE sanctioned moment to offer
    connecting a parent to an account of their own, ALONGSIDE the existing
    Family Cost Report share offer (D20, the spec defect this slice closes). The
    mechanism is RFC 160's, copied rather than reinvented: the nightly synthesis
    sweep writes a `commitments` row carrying the new `invite_supporter` lens,
    `CoachingService.startConvo` renders it as a private reflection in the
    SYSTEM prompt of a NEW conversation only, and a successful first reply marks
    it fulfilled — so **"once" is that row's lifecycle, not a sentence asking
    the model to behave**. **"Alongside, not instead of" is likewise enforced in
    code:** the report-share nudge is suppressed BECAUSE a live share exists and
    this one REQUIRES one, so the two predicates are mutually exclusive by
    construction, no synthesis pass can write both and no conversation can open
    with both. Eight named suppression reasons, each logged at DEBUG and each
    tested: DISABLED, NO_LIVE_SHARE, OPTED_OUT, ALREADY_LINKED, LIVE_INVITE,
    OPEN_SET_AT_CAP, OPEN_INVITE_NUDGE, COOLDOWN_NOT_ELAPSED. New chat tool
    `invite_supporter` mints a single-use link that expires in 14 days over RFC
    232's service and returns the url, the expiry and the ceiling sentences the
    recipient's page states; a closed or missing student endpoint is a typed
    RESULT (`link_created: false`) with its cause named in the log, never a
    throw, and the raw token is never returned separately. A repeat ask mints a
    FRESH link, because the token is stored only as a SHA-256 hash and the old
    link is unrecoverable by construction — unlike the report link, which the
    coach can re-hand; multiple live invites are legal by design (two parents).
    **One "stop asking me" covers BOTH offers**, with no new opt-out tool and no
    rename: `stop_cost_report_offers` keeps its name and behaviour and the
    prompt gains one sentence saying it covers both, `ShareEventsDao.hasOptOut`
    suppresses the invite nudge in synthesis, and
    `CostReportShareService.stopOffers` now drops open `invite_supporter`
    commitments too under the same `share_offers_opted_out` reason — the
    silenced set being derived through an EXHAUSTIVE `when` over
    `CommitmentLens`, so a third kind of offer cannot compile until somebody
    decides whether the stop covers it. The tool itself is NOT gated by the
    opt-out, mirroring `share_cost_report`: the opt-out silences the OFFER, not
    the capability, and a test proved non-vacuous holds that boundary. **Ian's
    gate call fixed the relationship word** ("should be 'connect as their parent
    or supporter'"): an invite for which NO door stated a relationship stores
    the catch-all `supporter` — D22's deliberate catch-all, NOT a fifth
    vocabulary word — and the recipient page reads "invited you to connect on
    unicoach as their parent or supporter", which also fixes the landed iOS
    button that has always sent no relationship and therefore always told a
    recipient they were named a "parent"; the reverse direction (a supporter
    inviting a student) keeps a single word in its apposition and now has the
    test it never had. Blocks 1-3 of the gate took their recommended defaults:
    the coach mints the link in chat, it may only offer after the student
    already shared the report, and a repeat ask mints a fresh link. Migration
    `widen-commitments-lens-invite-supporter.sql` (slug, no number, RFC 180)
    widens `commitments_lens_check` to include `invite_supporter` — no new table
    and no new column. 35 files changed, +2,634 / -100. Rollback:
    `COACHING_SYSTEM_PROMPT_VERSION=v46` (v47 is what this slice minted),
    PARTIAL — the pin removes the paragraph but the tool stays registered and
    callable, so a student who ASKS is still served;
    `SYNTHESIS_INVITE_NUDGE_ENABLED=false` stops any new invite offer being
    written, and `SYNTHESIS_INVITE_NUDGE_COOLDOWN_DAYS` (default 14) tunes the
    wait. **One open item is Ian's to decide, not engineering's:**
    `SupporterRelationship.SUPPORTER` now carries TWO meanings — "nobody stated
    a relationship" and "stated: supporter" — and the `code-review-no-sentinels`
    dissent showed the remedy is a NULLABLE column (not a fifth vocabulary word,
    so D22 stands untouched) and that the case IS reachable through the REST
    door (`{"relationship":"supporter"}`), though no shipped client sends it; it
    was escalated at the land gate, the default was taken — ship and record —
    and it wants a follow-up slice or a D22 amendment. Three smaller open items
    are written down rather than fixed: the ceiling copy has two hand-written
    homes (the coach's spoken sentence and `InvitePage.inviteCeiling`) which
    cannot be bound because the page's function is private in a module `service`
    does not depend on; revocation still has no door (inherited from RFC 232,
    `SupporterInviteService.revoke` has no production caller); and the invite
    row still models its one endpoint as a role plus two nullable ids (RFC 232,
    untouched). Gate: the full `bin/test check` passed on the landed tree — both
    installDist builds, every shell harness, `bin/format -c` — with verified
    tree `3f1671b7`; the phase-5 suite before it exited 0 at tests=3914
    failures=0 and tests=2193 failures=0 under Gradle's own incremental scoping.
    41 review lenses over four tiers, 21 fixes applied, 70 advisory items held
    with written reasons, 0 contradictions and 0 halted items; **tier 3 was ALL
    CLEAN, zero findings**, and two lens dissents were taken against the
    coordinator's held list — one upheld and fixed (`OFFER_LENSES` became an
    exhaustive `when`), one escalated to Ian. `family/02.5` unblocks NOTHING: no
    slice in any brief has a `Needs:` edge on it.
- **Renumbered 0012 -> 0014 on 2026-09-14, before landing.** Two sibling
  sessions took `0012-fafsa-support` and `0013-unicoach-on-the-web` while this
  brief was between its gates. Slice IDs are unaffected — they key off the
  handle `family`, which collides with neither `fafsa` nor `web`.
- **Two sibling briefs materially touch this one; both were read, neither
  changes a decision here:** `0012-fafsa-support` (handle `fafsa`) coaches the
  FAFSA, and this brief's DEFERRED `family/11/the-parent-deadline-track` is the
  PARENT-CONTRIBUTOR half of that subject — whoever specs a parent FAFSA
  deadline must read both, and the honest outcome may be that `fafsa` absorbs
  it. `0013-unicoach-on-the-web` (handle `web`) duplicates the iOS app on the
  web; every slice below names an iOS door because that is the door that exists
  today, and a web client widens each of them without changing what is behind
  the door.

## The question

Ian handed the answer rather than a question (the brief-0002 pattern), so this
brief captures the why, what the repo does today, and the decisions Ian must
make. Verbatim framing from Ian, 2026-09-14:

> I want to allow parents to sign up to support a student. by "support" I'm not
> sure exactly what I mean. Let's be clear, parents will not direct students —
> that's the point of the coach; the coach should lighten the load for parents.
> The coach should also coach parents. Especially around money. And around
> timelines, and how best to support their student.
>
> Unicoach should support both student-first registration (then inviting the
> parent to join), and vice versa: parent-first signup, then inviting the
> student to sign up.
>
> Students and parents should be linked together. A student can be linked to
> multiple parents. A parent can be linked to multiple students. All APIs and
> the UI must accommodate this fact. E.g. the UI should allow parents to change
> what student they're working with in the UI. This should be made very clear
> when the parent has multiple students that they're supporting. It does not
> have to be as clear when the parent only has a single student. Students should
> see a list of parents that are supporting them. Any user can delete a
> relationship at any time. And they can re-create them at any time as well. In
> the future (not this brief), I could imagine a paid person who is not a parent
> acting as support, giving the AI coach direction, and the student as well.

Fixed constraints, restated as requirements this brief treats as **given** (they
are Ian's words, not open decisions):

1. **Parents do not direct students.** The coach lightens the parent's load and
   coaches the parent — especially around money, timelines, and how to support
   their student.
2. **Both registration orders work**: student-first then invite parent, and
   parent-first then invite student.
3. **Many-to-many.** A student links to multiple parents; a parent links to
   multiple students. Every API and UI accommodates this.
4. **Parent UI has a working-student switcher**, prominent with multiple
   students, quiet with one.
5. **Students see the list of parents supporting them.**
6. **Either side can delete a relationship at any time, and re-create it at any
   time.**
7. **Future, explicitly not this brief:** a paid non-parent supporter role.
   (Consequence now: the relationship carries a role, so a second role is an
   enum value later, not a schema change.)

## What the repo already has (grepped, not guessed)

| Foundation                                                                                                                                    | Where                                                            | What it means for this brief                                                                                                                  |
| --------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------- |
| `users` — pure account: email, name, auth (password / Google / Apple via `user_auth_identities`), `email_verified_at`, admin flag             | `db/schema/0001`, `0018`, `0043`                                 | A parent account is just a `users` row. Nothing in `users` says "student".                                                                    |
| `students` — 1:1 with `users` (TOTAL unique index on `user_id`, even across soft-deletes); holds expected HS graduation date                  | `db/schema/0005`                                                 | The student is already a separate entity from the account. A parent is a user with NO `students` row — representable today.                   |
| Student creation is a separate `POST /students` after registration, not part of signup                                                        | `rest-server .../StudentRoutes.kt:78`, `CreateStudentRequest.kt` | Parent-first signup needs no auth-flow surgery: register, then simply do not create a student. The gap is what such a user CAN do afterwards. |
| Everything a family cares about hangs off `student_id`: coaching convos, college list, money profile, fit lens, synthesis, cost-report shares | `0006`, `0024`, `0046`, `0031`, `0025`, `0073`                   | Parent visibility into student data is a per-surface authorization decision, not one flag. Each surface needs an explicit read decision.      |
| Coaching conversations are strictly student-owned; ownership check is `convo.studentId != studentId` -> not-found                             | `service .../CoachingService.kt`                                 | A parent coach conversation needs an owner shape decision: convos keyed by user? by relationship? A parent convo is NOT a student convo.      |
| The coach prompt already speaks parent-aware money language ("use the same words with parents and students")                                  | `db/schema/0049+` seeds, currently v35                           | The coach's vocabulary is ready; what is missing is a parent on the other end and parent-specific coaching context.                           |
| Parents today reach unicoach exactly one way: the anonymous Family Cost Report share link — hashed token, revocable, no account               | `0073` (RFC 155), `share_events` (0081), public-web render       | The share link is the invite wedge that already exists: the parent is already holding a unicoach URL from their student.                      |
| Verification tokens — hashed single-use email tokens with expiry                                                                              | `db/schema/0014`                                                 | The invite-token mechanics (hash at rest, expiry, consume-once) have an in-repo precedent to copy.                                            |
| Email infrastructure — `email_sends`, queue-backed                                                                                            | `db/schema/0008`, `email/` module                                | Invites by email are buildable today.                                                                                                         |
| Account deletion (brief 0002) — cascade rules per table                                                                                       | brief 0002, RFCs landed                                          | Deleting a student or parent account must define what happens to the relationship rows and to the other party's view.                         |
| Subscriptions — per-user, Apple IAP                                                                                                           | `0042`, `SubscriptionRoutes.kt`                                  | Who pays when a parent joins (or joins first) is an open money/product decision.                                                              |
| iOS app + public web                                                                                                                          | `ios-app/`, `public-web/`                                        | The doors. Reachability criterion: every slice names which of these the parent/student actually touches.                                      |

**The three structural gaps**, in one sentence each:

- **No relationship.** There is no table linking a parent user to a student —
  the core artifact of this brief is one new many-to-many relationship table
  (DDL at the /ship gate per standing D10).
- **No non-student experience.** A user without a `students` row can register
  today but the product assumes student-ness everywhere downstream; the parent
  needs a home surface, a student switcher, and a coach that knows who they are.
- **No invitation.** Nothing lets one account cause another account to become
  linked; both directions need an invite flow (and the consent story matters: a
  link is mutual — created by invitation + acceptance, never unilaterally).

## Success criteria for this brief's decision

1. A parent can hold a real account in either registration order, linked
   many-to-many to students, with both sides able to see, delete, and re-create
   links — and the first landed beat is reachable through a named door.
2. The parent gets genuine value in their FIRST session — coaching on money,
   timelines, and how to support — not an empty dashboard waiting for the
   student.
3. Nothing about the link lets a parent direct the student or silently read what
   the student considers theirs; visibility defaults are an explicit,
   Ian-approved decision, not a side effect of a JOIN.
4. The relationship model survives the future paid-supporter role without a
   migration that rewrites it.

## Candidates

Ian chose the bet; the candidate table is therefore about SCOPE of the first
beat, not which bet to make. The genuinely open product decisions are listed as
gate-1 decisions (D1-D9, next section after research lands).

| # | Scope candidate                 | What lands                                                                                                                                                                                         | Risk                                                             |
| - | ------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------- |
| A | Linking spine only              | Relationship table + invites both directions + student's parent list + parent's student switcher; parent coach chat exists but with minimal context                                                | Parent's first session is thin                                   |
| B | Spine + parent coaching context | A, plus the parent coach conversation is first-class: coach knows which student, sees an Ian-approved slice of student data (e.g. college list + cost picture), coaches on money/timelines/support | Bigger surface; needs the visibility decisions settled at gate 1 |
| C | B + share-link graduation       | B, plus the existing anonymous cost-report link becomes an on-ramp: "claim this" -> parent account pre-linked to the sharing student                                                               | Touches RFC 155 semantics; could be a later slice                |

## Research (PRIORITISE input)

Three parallel research agents, one per genuinely independent question; each
writes `research/<topic>.md` with method and citations:

- `research/competitor-parent-accounts.md` — how college-planning products do
  parent accounts, linking, invites, multi-student, permissions; what works and
  what fails.
- `research/consent-privacy-safety.md` — minors + linked adult accounts: legal
  floor (COPPA edge, state teen-privacy laws, app-store rules), consent norms,
  sensible visibility defaults, deletion interplay.
- `research/what-parents-need.md` — evidence on what parents of applicants
  actually need coaching on (money, timelines, role support), and where
  parent-facing college tools deliver or fail; grounds the parent's
  first-session value.

## Research synthesis (read the reports; this is the ranked view)

The three reports converge on one shape from three independent directions:

| # | Finding                                                                                                                                                                              | Competitor report                                                                                                          | Consent report                                                                                                                           | Parent-needs report                                                                                                                      |
| - | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------- |
| 1 | **Mutual-consent link lifecycle is the proven shape**: either side invites by email, the other side explicitly accepts, either side unlinks anytime, unlink never deletes an account | Scoir does exactly this and it works; every failure mode is a deviation (school-owned links, no links, parent-owned links) | No law requires acceptance at 13+, but FAFSA's contributor model and FERPA's age-18 rights flip make it the norm families already expect | FERPA-shaped student control matches the legal regime the family enters at enrollment                                                    |
| 2 | **The permission ceiling is view + suggest + own workspace** — parents never edit the student's plan                                                                                 | Industry standard: Common App advisors are read-only; Scoir parents suggest only. State the ceiling in the invite itself   | Silent or over-broad access is the fastest way to break the product's own thesis                                                         | Snowplow behaviors are majority behaviors (76% deadline-reminding); the coach redirects, the UI must not enable directing                |
| 3 | **The parent account leads with money value, not a view of the student**                                                                                                             | Going Merry proves parents sign up just for aid tools; BigFuture serves parents only content — the gap                     | Parent typically SUPPLIES household finances: author-owned inputs, shared derived outputs (FAFSA partition)                              | Parents fund ~50% of college; <44% of families discuss all-years cost; top stressor is sticker shock; PLUS has no underwriting           |
| 4 | **Coach conversations are private per party, absolutely**                                                                                                                            | —                                                                                                                          | Non-shareable by design, not by setting; the trust core                                                                                  | Students and parents research in different places; the teen who thinks a parent reads the transcript won't say the true thing            |
| 5 | **Legal floor is thin and cheap**: 13+ DOB gate, in-app deletion (Apple 5.1.1(v)), never sell/share for ads                                                                          | —                                                                                                                          | COPPA only bites under 13; FERPA doesn't bind us; state teen laws mostly enjoined; CCPA under-16 opt-in mooted by never-sell             | —                                                                                                                                        |
| 6 | **Parent-owned deadlines are a real unserved gap**                                                                                                                                   | Nobody surfaces them                                                                                                       | FAFSA contributor role is a formal legal role with its own account and consent                                                           | FSA ID early, contributor section + IRS consent, CSS Profile, verification, PLUS decision — 56% of completing families needed FAFSA help |

Scope verdict against the candidates: **B** (linking spine + first-class parent
coaching context). A alone gives the parent a thin first session — the exact
failure the needs report warns against; C's share-link graduation is real but
severable, and touches RFC 155 semantics best changed in its own slice later.

## Gate 1 decisions (APPROVED 2026-09-14)

_Approved by Ian 2026-09-14; verbatim answer quoted in the Status block. Labels
per the chart convention; the full text below each label is binding._

- **D1 — spine plus parent coach.** The first beat lands the linking spine
  (relationship table, invites both directions, student's parent list, parent's
  student switcher) PLUS a first-class parent coach: the coach knows which
  student the parent is working with and can see that student's college list and
  derived cost picture, and coaches on money, timelines, and how to support.
  Share-link graduation (C) becomes a later slice, not dropped.
- **D2 — invite is consent; accept completes.** A link exists only after an
  invite from one side and an explicit accept from the other, in both directions
  — and **the act of sharing the invite link IS the inviter's consent** (Ian's
  amendment): a student shares a link with a parent, the parent clicks it and
  accepts, and the link exists — the student does not need to do anything more.
  Same shape parent-first. Either side unlinks at any time; unlink never deletes
  anyone's account or data; re-invite and re-accept recreates the link. No
  silent linking, ever.
- **D3 — view, suggest, own workspace.** A parent never edits the student's data
  — not the list, not the profile, not tasks. Parents get read access (per D4),
  a structured way to suggest, and their own workspace (own coach conversations,
  own money inputs, own deadline track). The invite itself states this ceiling —
  it markets the product's point.
- **D4 — private chats; FAFSA money partition.** (a) Coach conversations:
  private to each party, non-shareable BY DESIGN — no toggle exists to share a
  transcript, stated as a commitment. (b) College list: visible to linked
  parents by default; a per-entry student veil is a later slice, not in the
  first beat. (c) Money: the FAFSA partition — each party owns and sees its own
  inputs; derived outputs (net prices, cost pictures, affordability conclusions)
  are visible to both; a parent's raw figures are not shown to the student by
  default.
- **D5 — thirteen-plus floor.** Student signup gains a neutral date-of-birth
  gate; under-13 is refused, keeping unicoach wholly outside COPPA's
  verifiable-parental-consent regime. A written actual-knowledge escalation path
  (suspend, confirm, delete) is part of the beat.
- **D6 — role-typed link table.** The link table carries a role (TEXT + CHECK,
  per schema conventions), with `parent` as its only value now, so the future
  paid supporter is an enum value and a policy decision later, not a schema
  rewrite. No supporter features in this brief.
- **D7 — hashed shareable invite tokens.** An invite is a hashed token on the
  verification-token precedent (hash at rest, expiry, consume-once),
  materialized as a **shareable link** — the inviter sends it however they like
  (in-app share sheet, text, email; email through unicoach is one delivery
  channel, not the mechanism). It works whether or not the invitee has an
  account yet; accepting mid-signup links atomically. The app remains fully
  usable without completing any invite (Apple 5.1.1(v)). D2's amendment applies:
  creating and sharing the link is the inviter's consent.
- **D8 — billing unchanged.** A parent account rides the existing per-user
  subscription model; no family plan, no shared billing. A future brief owns
  family pricing.
- **D9 — author-partitioned deletion.** Account deletion (in-app, both roles)
  severs all links and notifies the other party without stating a reason; each
  party's own conversations, inputs, and account survive the other's deletion.
  Extends brief 0002's deletion work to the new tables.
