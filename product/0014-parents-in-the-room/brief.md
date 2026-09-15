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
- Ledger: (empty — nothing specced, nothing landed)
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
