# Brief 0013 — Unicoach on the web

Status:

- Phase: EXECUTE (gate 2 CLOSED 2026-09-14; D1-D22 committed) (gate 1 CLOSED
  2026-09-14; D1-D14 committed)
- Gate 1: PASSED — D1-D11 committed, D4 amended three times by Ian at the gate
- Gate 2: PASSED — 12 slices in spec.md, D15-D22 committed
- Ledger: (empty — `web/01/the-shell` dispatching)

Brief handle: `web` (declared here per spec convention). Slice IDs use the
handle, not the number, so none of them changed below.

**Renumbered 0012 -> 0013 on 2026-09-14, before landing.** This brief was
drafted as 0012; while it was in its docs worktree another session landed
`product/0012-fafsa-support`. Both runs had computed "highest existing + 1" from
different base commits, which is a race the numbering convention does not
prevent. The number here is the one that survives on `main`; nothing else about
the brief changed.

## The question

Duplicate all functionality (within reason — some features may not transfer)
from the iOS app into the public web, so students and parents can use Unicoach
from a desktop browser, an Android phone, or a tablet without the app. Fully
responsive: no 100% guarantee across all sizes, but at least 90% of browser
widths and heights must work.

Ian's binding constraints (verbatim, from the kickoff prompt):

> I strongly prefer server-side rendering. We will have some javascript
> (typescript preferred) for client-side interactions e.g. I'm sure we'll need
> this for the chat view. Quality of engineering must be HIGH. difficult
> interactions with the chat view. I care a lot about forms. We must clearly
> store all form related data in the session so as not to lose data on e.g. form
> errors or page navs. The goal is to allow students and parents to be able to
> use the iOS app, an android phone, a tablet, or a desktop computer seamlessly
> to interact with Unicoach.

Ian handed the answer, not a question, so FRAME captures the why and the repo's
today, and PRIORITISE collapses to gate 1 over the decisions below (brief 0002
pattern).

## What the repo does today (grep-verified; full detail in research/)

- **The backend is already browser-shaped.** Auth is an HttpOnly
  `UNICOACH_SESSION` cookie (SameSite=Strict, domain-wide) — no bearer tokens;
  the iOS app itself uses the cookie. No device attestation exists; the only
  client gate is a static `X-Unicoach-Client-Key` header. Chat streaming is SSE
  over HTTP POST with buffered non-streaming twins. 26 routes, all inventoried.
  (research/backend-and-auth.md)
- **The iOS app surface is 11 areas, 23 transfer rows**: auth (email+password,
  Google, Apple), email verification, onboarding (graduation date + optional
  money answers), chat-first shell + slide-over menu, SSE streaming + Markdown
  rendering, conversation list (archive/delete), college list CRUD + search,
  Your details (money profile), StoreKit paywall + usage meter, settings
  (appearance, change email, logout). No cost report, no account deletion, no
  push, no App Check in the iOS client. (research/ios-feature-inventory.md)
- **public-web exists and deploys**: Ktor 3.4.2 + kotlinx.html SSR at
  `app.<domain>` with ALB rule, systemd unit, daemon scripts, static mount, test
  harness, and `:service`/`:db`/`:auth` dependencies. admin-web has the reusable
  form pattern (values-map re-render + PRG). Sessions are DB-backed
  (`db/schema/0002.create-sessions.sql`). Deno 2.7.12 is in the flake and has a
  working `deno bundle` (verified in the dev shell 2026-09-14).
  (research/web-foundation.md)
- **Known gaps** (things the web needs that do not exist): web payment rail
  (StoreKit-only today; a non-subscriber gets a $5.00 lifetime free coaching
  allowance, then 402), subscription-state GET, password reset route, CORS (moot
  if same-origin), rate limiting, form-state store.

## Success criteria for this brief

1. A student or parent can do everything the iOS app does (minus the excluded
   rows in D10) from a browser, discovering it at `app.<domain>`.
2. No form ever loses typed data to a validation error or a page navigation.
3. Pages render server-side; TypeScript only where interaction demands it.
4. Layouts work from small phones to desktop (>=90% of sizes; D9 defines the
   evidence).

## Gate 1 — decisions

Defaults pre-chosen; approving costs one word. Answers recorded verbatim below
each decision after Ian replies.

**D1 — grow public-web, no new deployable.** The web app is built inside the
existing `public-web` module (hostname, deploy path, test harness already
exist). COMMITTED (gate 1, Ian: "I agree with all other decisions").

**D2 — one login, same origin.** public-web shares the rest-server
`UNICOACH_SESSION` cookie contract (same `AuthService.resolveSession`), and the
browser talks ONLY to `app.<domain>` — page loads and TS fetches alike. The web
tier reaches the API/service layer server-side, holding its own client key. No
CORS work, no public client key, domain-wide SSO with the API. COMMITTED (gate
1, Ian: "I agree with all other decisions").

**D3 — chat via fetch streaming.** The chat view is a TypeScript client using
fetch + ReadableStream against same-origin SSE-over-POST endpoints (EventSource
cannot POST). Everything else on the chat page is SSR with the buffered
non-streaming twins as the no-JS fallback. COMMITTED (gate 1, Ian: "I agree with
all other decisions").

**D4 — form state rides in `sessions.metadata`, which STAYS JSONB.** The
mechanism moved three times at gate 1; the evolution is recorded because the
reasoning matters. Ian first rejected a dedicated table:

> `form state lives in a database table` No. I think protobufs stored in the
> session should work and be a better fit.

then set the column direction:

> We should take the JSONB data and turn it into protobuf, extend the `metadata`
> column size limit, and use it for it's current use (not sure how it's used
> today) and for forms.

then reversed the encoding on queryability grounds:

> Oh. Hold up. We may want to keep `metadata` as JSONB. This would allow
> postgres to query it. It would not be able to query protobuf. And protobuf can
> be written as JSONB (check this).

**The check, run 2026-09-14, both halves confirmed.** (1) Protobuf defines an
official canonical JSON encoding — "ProtoJSON" — precisely so protobuf data can
be shared with systems that do not read the binary wire format
(https://protobuf.dev/programming-guides/json/). (2) More directly useful here:
with `kotlinx-serialization` — already in `gradle/libs.versions.toml` at 1.11.0
with its plugin applied in several modules — **one `@Serializable` class is
encoded to EITHER format at the call site**, and field numbers come from a
`@ProtoNumber` annotation rather than a separate `.proto` file
(https://github.com/Kotlin/kotlinx.serialization/blob/master/docs/formats.md).
So the schema is the Kotlin class, and the storage format is a free choice.

**Decided:** `sessions.metadata` STAYS `JSONB`, so Postgres can query it and an
operator can read a draft while debugging. Its size limit is raised (measured,
see below). One reviewed `@Serializable` schema class defines what may live
there; it holds session metadata and form drafts. Write on every POST (valid or
not), read-through on GET render, clear on successful final submit; PRG + values
re-render unchanged.

**D4b — real protobuf at runtime, JSONB at storage. COMMITTED.** Ian:

> I want protobuf at runtime. JSONB at storage time

and approved the trade below with "lgtm". So:

- A committed `.proto` file is the schema of record — and is the artifact D11
  binds Ian to review, forever.
- `protoc` generates the message classes; they are the runtime representation.
- `JsonFormat` (protobuf's own `util` library) converts message -> canonical
  proto3 JSON on write and back on read
  (https://protobuf.dev/reference/java/api-docs/com/google/protobuf/util/JsonFormat.html).
- The column stays `JSONB`, so Postgres can query it.

The cost, accepted explicitly at the gate: `protoc` is added to the Nix dev
shell (`flake.nix`), the build gains a code-generation step, and generated
sources enter the tree. `kotlinx-serialization` was the free alternative and was
REJECTED on the merits: its runtime object is a Kotlin data class, not a
protobuf message, and it produces no `.proto` file to review — it would have
been protobuf-capable, not protobuf.

Canonical proto3 JSON has fixed representations the spec must write down,
because queries have to match them: 64-bit integers are rendered as STRINGS,
enums as their NAMES, and fields as lowerCamelCase.

**Finding — the column has NO current use, so nothing has to be preserved.**
Grep of every write site in the repo (excluding `build/` and `.claude/`): every
production caller passes `metadata = null`
(`service/src/main/kotlin/ed/unicoach/auth/AuthService.kt:132` and `:639` are
the only two session-mint sites). The only non-null writes anywhere are inside
the DAO's own test —
`db/src/test/kotlin/ed/unicoach/db/dao/SessionsDaoTest.kt:149` (`{"foo":"bar"}`)
and `:173` (an oversized string that exercises the CHECK). Nothing reads it
beyond `SessionsDao`'s row mapper carrying it into the model; admin-web does not
display it. The column is speculative and has never held production data, so the
change needs **no backfill and no compatibility shim**.

Consequences for the spec:

- The column type does not change; only the CHECK limit and the typed shape
  written into it.
- `NewSession.metadata` / `Session.metadata` move from an untyped `String?` to
  the typed schema class, serialized to JSON at the DAO boundary.
- The new size cap is **measured, not guessed**: the spec measures the encoded
  size of the largest form in scope — JSON is more verbose than binary, which is
  the real cost of this choice — and sets the CHECK with stated headroom.
  Overflow behaviour is defined explicitly; a draft that silently vanishes for
  being too big is the data loss this decision exists to prevent.
- Drafts write the authentication row on every form POST. The spec keeps that to
  one UPDATE per POST and states how it interacts with the session-expiry
  refresh.
- No `protoc`, no codegen step, no `flake.nix` change, in this or the binary
  variant.

D4a (which session carries the bytes) is CLOSED: the Postgres auth-session row.
COMMITTED.

**D5 — no anonymous sessions.** Form-state protection applies to logged-in
sessions only; the sessions schema supports `user_id NULL` but nothing mints
anonymous sessions, and this brief does not add that. COMMITTED (gate 1, Ian: "I
agree with all other decisions").

**D6 — no web billing in this brief.** A web-only user gets the existing $5.00
lifetime free coaching allowance; on 402 the web shows an honest page:
subscribing happens in the iOS app for now. iOS subscribers are entitled on the
web automatically (subscription is per-student). A subscription-state read for
the settings page is in scope; a Stripe-class web payment rail is its own future
brief. COMMITTED (gate 1, Ian: "I agree with all other decisions").

**D7 — email+password first, SSO next, reset in scope.** First auth slice ships
email+password login/registration/verification. Google (Identity Services) and
Apple (Sign in with Apple JS) follow as an adapt slice — backend contracts
exist, audiences are config. Password reset (no backend route exists) is in
scope as its own slice: a web product without it strands users. COMMITTED (gate
1, Ian: "I agree with all other decisions").

**D8 — Deno owns the TS pipeline.** TS sources live beside public-web;
`bin/build-web-assets` runs `deno check|lint|test|bundle` (Deno 2.7.12,
verified) emitting hashed bundles into the static mount; wired into the existing
gates. No Node, no npm lockfile. COMMITTED (gate 1, Ian: "I agree with all other
decisions").

**D9 — fluid-first responsive; evidence = mechanisms + device matrix.** Fluid
layout covering 320–1440 px continuously with 2–3 breakpoints; chat uses dvh
units for the composer-pinned layout. Automated tests pin mechanisms (viewport
meta, breakpoint rules, no fixed widths); the 90% claim is evidenced by a manual
device-matrix checklist in the spec. Browser screenshot tooling is optional
later, not a gate. COMMITTED (gate 1, Ian: "I agree with all other decisions").

**D10 — parity scope.** In scope: every yes/adapt row of the transfer table in
research/ios-feature-inventory.md (root routing, login/registration, Google and
Apple sign-in, verification, onboarding, chat shell + streaming + Markdown +
copy, conversation list/archive/delete, college list CRUD + search, money
profile, usage meter + 402 presentation, appearance, change email, logout). Out
of scope: the StoreKit rail (purchase/restore/JWS verify), a web payment rail
(D6), push (does not exist). COMMITTED (gate 1, Ian: "I agree with all other
decisions").

**D11 — every session metadata schema change is reviewed by Ian. STANDING.**
Ian, at gate 1:

> I want to review every change to the protobuf schema from now and forever.
> Similar to how i review DDL.

Ian said "protobuf schema"; the artifact is named precisely here because D4
keeps JSON as the stored format — the rule attaches to the `@Serializable`
schema class that defines the session metadata, in whatever format it is
encoded, and to any `.proto` file if binary is ever adopted.

This is a standing rule, inherited by every future brief without re-asking, and
it sits beside brief 0001's standing D10 (Ian approves every new database table,
with visible DDL at the gate). Any slice that adds, removes, renames, re-types
or re-numbers a protobuf field — or adds a message — says so in its /ship
instruction, and the instruction directs /ship to present the **full proposed
schema diff explicitly at its approval gate**, not buried in the RFC. A
wire-format change is exactly as hard to walk back as a DDL change, which is why
it gets the same gate. COMMITTED (standing).

**D12 — the form-state rule is conditional on a session. STANDING.** Raised as a
gap against D4 (pre-login forms have no session to store drafts in, because D5
mints no anonymous sessions); Ian closed it by restating the rule:

> `your rule is that all form data lives in the session` if there is no session,
> this is moot. So the rule should be: when there is a session, all form data
> should be stored in it

So the binding rule is **conditional, not universal**: wherever a session
exists, every form on that surface stores its data in it — no exceptions, no
per-form judgement call. Where no session exists (sign-in, registration,
password-reset request — D5 mints no anonymous session), the requirement does
not apply and its absence is not a defect to be worked around.

Those pre-session forms still re-render with the user's submitted values on a
validation error; that is ordinary correct form handling, not session storage,
and the spec must not describe it as satisfying D4.

Consequence for slicing: `web/02/sign-in` does NOT depend on the form-state
foundation, which is why the foundation can land after the chat slice instead of
blocking the first login. COMMITTED (standing).

**D13 — the web calls the service layer in-process.** Ian, at DISCOVER:

> The web calls the service layer in-process.

`public-web` already depends on `:service`, `:db`, `:auth`, `:common` and
`:web-common` (`public-web/build.gradle.kts:11-18`), so page handlers invoke
services directly; no HTTP hop to `rest-server`, no JSON round-trip.

What this settles:

- The client-key question is MOOT for the web. The browser never calls
  `api.<domain>`, and the web tier makes no HTTP call that a key would gate.
- The web can offer the money profile's FOURTH field, `dependency`, which the
  REST API does not expose (`MoneyProfile` carries it, `MoneyProfileService`
  accepts it, `UpdateMoneyProfileRequest` omits it). Slice `web/05` decides
  whether to show it; the capability now exists either way.
- The REST error types are NOT reachable (`ErrorCode`, `ErrorResponse`,
  `SessionConfig`, `resolveCaller` all live in `:rest-server`). `FieldError` IS
  reachable in `:common`. So the web renders errors from service-layer results
  plus `FieldError`, and the two REST validation codes (`validation_failed`,
  `validation_error`) are a REST-boundary concern the web does not inherit.
- Session-cookie resolution for the web is new or moved code, not reuse.

**The divergence risk, and why it is bounded.** Web and iOS now reach the
product through different paths, so they CAN drift. They cannot drift on
business rules: the service layer is the single home of those, and both callers
go through it. Drift is therefore confined to the edge — validation messages,
field exposure, error presentation. Any slice that finds itself adding a RULE in
the web tier rather than calling one has mis-placed it, and that is a review
finding, not a judgement call. COMMITTED.

**D14 — living-plan and dependency captions are written as product copy.**
DISCOVER found there is no `living_plans` vocabulary: `VocabularyService`
registers exactly `income_bands` and `residency_states`, and the only
living-plan strings in the tree are coach-SENTENCE fragments
(`LivingArrangement.label` = "renting off campus"), which read as nonsense in a
picker. Ian: "sgtm". So the captions are new user-facing copy, drafted in
`spec.md` and approved by Ian at gate 2 — not invented inside a /ship run, and
not scraped from the coach fragments. COMMITTED.

LEDGER (one line per landed slice, appended as each lands; `slice-board` reads
THIS file, so the line lives here and the spec points at it):

- `web/01/the-shell` LANDED as RFC 210 (main@cf4f22ba + e8249d90, 2026-09-15) —
  the responsive shell every later web page renders into: `site.css` reorganised
  into six labelled sections, `main` fluid from 320px to 1440px with the measure
  held in a `--measure` custom property and a `clamp()` gutter, two breakpoints
  (the existing 640px and a new 1024px, where only tokens change), the header
  navigation WRAPPING instead of overflowing, one tap-target mechanism giving
  the brand, the nav links and the footer links a 44px floor, a typed
  `ShellWidth` seam on the one `siteLayout` (the hook `web/02`'s signed-in shell
  will use), and public-web's first content-security policy — enforcing, no
  `'unsafe-inline'`, set at the routing root so it rides rendered pages, the
  static mount and the 404/503 alike, with the seam parameterised in
  `web-common` so a second web tier adopts it rather than copying the plugin.
  Gate answers (Ian, 2026-09-15): wrapping navigation over a hamburger,
  enforcing over report-only, two breakpoints over three. Per D9 the tests pin
  MECHANISMS only; **the D19 device matrix was NOT run** and is an open item.
  Suite green: 3,372 executed, 0 failures; the pre-commit gate ran at full
  scope.

## Research

- research/ios-feature-inventory.md — every iOS screen/flow, endpoints, 23-row
  transfer table
- research/backend-and-auth.md — route inventory, cookie auth, client-key gate,
  SSE, billing gap
- research/web-foundation.md — public-web/admin-web/web-common today, forms,
  Deno, responsive plan
