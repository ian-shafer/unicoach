# Work queue — 2026-08-25

Four sessions to kick off, two items that need no session. Kickoff prompts are
paste-ready: open a new Prime Agent session in /Users/ian/Work/unicoach and
paste one. Each /ship run claims its own worktree, so parallel runs are safe and
ship-rebase handles a moved base (including a renumbered migration) — no manual
sequencing needed. YOU are the approval gate in each session.

Live-run discovery from any checkout:
.prime/agent/skills/ship/scripts/ship-status

| # | Work                     | Kind            |
| - | ------------------------ | --------------- |
| 1 | S3.5 update_college_list | /ship, lane A   |
| 2 | iOS college-list screen  | /ship, lane A   |
| 3 | Delete account (0002)    | /ship, lane A   |
| 4 | Product-layer skill      | skill authoring |
| 5 | Deploy S1-S3 to prod     | ops, after #6   |
| 6 | Phone test               | Ian, no session |

Note (not tracked as work): bin/ingest-colleges has no change summary and no
header assertion, so a stale jar and a column-less CSV fail identically —
silent, zero rows changed, exit 0. Cost an hour on 2026-08-25. Ian will fold
this in when convenient; it matters most before S4's data build.

---

## 1. S3.5 — update_college_list chat tool

PASTE: Ship S3.5 from product brief 0001: an update_college_list chat tool so
the coach can add, restatus, and remove college-list entries conversationally.
RFC 91's schema and REST CRUD already exist; there is NO student-facing door to
the list today (no chat tool, no iOS UI), which makes the S1-S3 cost feature
unreachable for a real user — that gap is why this slice exists. Mirror
MoneyProfileChatTool/StudentScopedChatTool exactly. Honour the value- before-ask
ethos: the coach offers, never nags, and a student can always change or remove
an entry. Decide in design whether the coach prompt needs a v4 to know it can
now write the list, and say why. Record the gap in
product/0001-v1-differentiator/spec.md as slice S3.5.

## 2. iOS college-list screen

PASTE: Ship an iOS college-list screen: the student's list, add/remove/restatus,
reading the existing /api/v1/students/me/college-list REST surface (RFC 91). The
app is chat-first (RFC 117) so decide and justify where this lives in
navigation. Follow the iOS motif (RFC 116) and the snapshot-gate conventions
(RFC 122/130); iOS scripts run under SYSTEM Xcode, never inside the nix dev
shell. An update_college_list chat tool may be landing in parallel — same
domain, different surface; rebase and reconcile rather than duplicating
semantics.

## 3. Delete user account (brief 0002)

PASTE: Run product brief 0002 (product/0002-account-data-deletion/brief.md):
account data deletion. The brief is FRAMED and awaiting gate 1 — bring me its
six decisions (D1-D6) with defaults before any code. Legally required (GDPR Art.
17, CCPA, App Store 5.1.1(v) in-app deletion) and it gives us repeatable
clean-slate testing. The schema actively refuses deletion today (users has
prevent_physical_delete; students.user_id and verification_tokens.user_id have
no ON DELETE; users_versions is RESTRICT; ~14 append-only tables carry
prevent_delete guards; the LLM cost ledger is reachable only by traversing
convo_requests -> convos.student_id and the *_runs tables) — all mapped in the
brief. I approve every new table personally, with visible DDL at the gate.

## 4. Write the product-layer skill

PASTE: Codify the product layer as a Prime Agent skill, from the three completed
runs it just drove (RFCs 133-135) and the conventions in product/. Read
product/0001-v1-differentiator/{brief,spec}.md and
product/0002-account-data-deletion/brief.md — those are the worked examples. The
skill owns FRAME -> PRIORITISE -> DISCOVER -> SPEC&SLICE -> EXECUTE(/ship) ->
LEARN, numbered immutable briefs under product/, exactly two human gates
(prioritisation, spec), parallel research subagents writing cited reports, and a
ledger mapping slices to landed SHAs. Hard-won lessons that MUST be in it: (a)
product judgement is spent in SPEC&SLICE so /ship receives instructions whose
acceptance criteria already embody the ethos — /ship never reasons about product
value; (b) ALWAYS ask "can a user actually reach this?" — brief 0001 shipped
three slices behind an unreachable college list; (c) the ethos "never force a
user through a step whose value they don't yet understand" belongs in every
slice's acceptance criteria; (d) Ian approves every new table, with visible DDL
at the gate. Do not modify .claude/**.

## 5. Deploy S1-S3 to production [after the phone test]

PASTE: Deploy the landed S1-S3 work (RFCs 133-135) to prod and make the data
real there. Prod will hit the same trap dev did: the college dist must be
REBUILT before ingest (a stale jar silently no-ops the entire load — it did
here), migrations 0044-0047 must run, and the Scorecard re-ingest must actually
bump colleges.version to prove the band columns backfilled. Verify with: SELECT
count(net_price_q1), count(median_debt), max(version) FROM colleges. Read
bin/deploy and CONFIGURATION.md first; report the plan before touching prod.

## 6. Phone test [Ian, no session]

Dev stack ready: migrations applied, bands backfilled (q1 4945, debt 4777,
version 2), college jar rebuilt, rest-server restarted (PID 84587), coach v3
pinned, fresh account fresh1@test.local. Blocked until #1 lands unless the list
is seeded by REST. Watch for: does the coach raise cost unprompted; does the
invitation feel like an offer or an ask; is "not reported" honest or evasive;
does a decline stay declined. Rollback if coaching misfires:
COACHING_SYSTEM_PROMPT_VERSION=v2.
