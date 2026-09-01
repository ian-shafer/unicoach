# RFC 155 — The Family Cost Report

Status: proposed\
Slice: `first-value/05/family-cost-report` (brief 0001, S5)\
Base: `main@75260390` · Branch: `pipeline/rfc-155`

## Summary

Everything brief 0001 has built so far — income-band net price, the money
profile, the component split, the comparison contract, cited merit practice — is
locked inside one student's chat window. The person who actually writes the
cheque never sees it.

This RFC gives that work a door for the parent: a **student-initiated,
revocable, tokenized web page** at `https://app.uni.coach/report?token=<raw>`
that renders the student's college list as a per-school cost table — tuition and
fees, housing and food, the published price, the likely price after a financial
aid offer, merit practice, and debt context — with the five comparison
assumption lines stated above it, exactly as the coach must say them in chat. No
login, no parent account, no PDF.

The student mints and revokes the link by asking the coach. One new table
(`cost_report_shares`), one new public route, two new chat tools, coach prompt
v15.

This slice deliberately stops short of the nudge: `first-value/06` owns the
share CTA, the RFC 93 commitment trigger, and share-event tracking. RFC 155
builds the surface those attach to.

## Decisions

### D-A. The page renders live, not a frozen snapshot

The token addresses a _student_, not a stored copy of a table. Every view
recomputes from the current college list, the current money profile and the
current pinned Scorecard vintage.

Live wins on both honesty and cost. A snapshot silently ages: the student adds
two schools and answers the residency question, and the parent is still reading
last week's numbers with no way to know. It also doubles the degradation surface
— a frozen JSON blob would need its own versioning the moment
`CollegeCostService` changes shape, which it has done three times in three weeks
(RFCs 135, 149, 151).

The cost of live is that the parent's view changes under them. That is the
correct behaviour for a list the student is still building, and the page says so
in one line: _"This report is live — it updates as your student updates their
list."_

Rejected: snapshot-at-share (stale-by-design, needs a payload version column);
PDF (D7 of brief 0001 already ruled it out for v1).

### D-B. One live share per student; revoking mints nothing

`cost_report_shares` carries a partial unique index on
`student_id WHERE
revoked_at IS NULL`. Asking to share twice returns _the same
link_, so the student can re-send it without wondering which of three links
their mother has. Revoke sets `revoked_at`; the next share mints a fresh secret,
and the old link is dead forever.

This is what makes revocation a promise rather than a gesture: "revoke" means
every link you have ever sent is now dead, not "the most recent one".

**The token is derived, not stored (Ian, at the gate).** The first draft of this
RFC promised both "the same link comes back" and "only the SHA-256 hash is
stored", which cannot both hold: a hash is not reversible, so the server cannot
reproduce a link it minted. The implementation surfaced the contradiction before
writing it.

The resolution keeps both promises:

    raw token = base64url( HMAC-SHA256(shareTokenSecret, <row id>) )

The row stores only `TokenHash.fromRawToken(raw)`, exactly as
`verification_tokens` does, so a database leak yields no working links — but the
link is recomputable from the row id plus a secret held outside the database, so
re-sharing returns the same URL forever. Only `:service` needs the secret; the
view path in `public-web` hashes the presented token and looks it up, so the
public surface holds no secret at all.

Why this and not "store the raw token": D-A makes the page **live**, so the
token is not a link to a table someone already saw — it is a standing
subscription to the student's evolving list and their family's income band, with
no expiry. Storing that capability in plaintext means a database read leak
exposes perpetual live access for every sharing family. The email-verification
precedent hashes a credential that grants account takeover; this one is closer
to that than the first draft assumed.

Secret rotation is the one visible edge, and it is handled honestly rather than
silently: after a rotation the recomputed hash no longer matches the stored one,
which means every previously sent link is _already_ dead. The share tool then
revokes the stale row, mints a fresh link, and returns
`previous_link_no_longer_works` so the coach says plainly that the old link
stopped working. A rotation is therefore a global revoke — the right behaviour
during an incident. A link is never swapped silently.

Rejected: re-minting on every ask (silently kills the link the parent already
saved, which is the exact failure this decision exists to prevent); refusing to
show the link a second time (the coach cannot answer "what was that link
again?", the most likely second question).

### D-C. No expiry

The email-verification precedent is single-use with a 24-hour TTL because it is
a credential in flight. This is the opposite: a link a parent keeps in a text
thread and opens again in March when the aid offers arrive. A TTL would expire
it exactly when it becomes useful, and the student would have no idea it had.

Revocation is the control, and it is student-driven and immediate. The table
still carries `revoked_at`, and the DAO consume path is a compare-and-swap that
returns nothing for a revoked or unknown token.

Because the page is live (D-A) and the link does not expire, the ongoing
visibility must be said out loud rather than inferred. The page carries one line
— _"This report is live — it updates as your student updates their list."_ — and
prompt v15 tells the coach to say the same sentence when it hands the link over.
A parent must not read it as a fixed document, and a student must know that
adding a school on Tuesday is visible on Tuesday.

### D-D. The token travels as a query param, `?token=`, not a path segment

Two reasons, both mechanical. The `verify-email` link is already `?token=<raw>`,
so the shape is one a support conversation can recognise. And `web-common`'s
`Route.secretQueryParams(...)` seam redacts **query params only** from the
request log — a path segment would write the live secret into the access log of
every request. RFC 155 is that seam's first caller.

Rejected: `/report/<token>` (prettier, unloggable-safely today).

### D-E. `public-web` gains `implementation(project(":service"))`, behind a local port

The cost computation is already pure domain code:
`CollegeCostService.getForStudent(studentId)` returns a `CollegeCostProfile`
with no chat, no JSON and no LLM in it. `CollegeCostChatTool` is a renderer over
it. So the page needs no new computation — only a caller.

`public-web` today depends on `:common`, `:web-common`, `:db`, `:auth`. The
documented rationale for `:auth`'s `EmailVerifier` is _in-process rather than
one service hopping to the other over HTTP_ — a rule about calling style, not a
ban on `:service`. `admin-web` already depends on `:service`.

So: add the module edge, and mirror the `EmailVerifier` shape with a narrow port
owned by `public-web` —

```kotlin
interface CostReportSource {
  suspend fun getByShareToken(rawToken: String): Result<CostReportOutcome>
}
```

— implemented by
`ServiceCostReportSource(CollegeCostService, CostReportShares
DAO)` and faked in
tests, so the page's rendering and degradation tests stay fast and DB-free,
exactly as `FakeEmailVerifier` does today.

Rejected: extracting `coaching/costs` into a db-only module. It is the
architecturally cleaner end state and it stays available later, but the package
is under active edit by `pipeline/rfc-152` (`money/04/where-youll-live`) right
now, and moving files under a live run buys a guaranteed conflict for a benefit
that is jar weight. Recorded as a follow-up, not done here.

Rejected: an HTTP call to `rest-server` (explicitly rejected by the
`EmailVerifier` doc comment).

### D-F. The page shows no student identity, and states the family's income band

No name, no email, no user id — the link is not proof of who sent it, so the
page must not assert it. It opens as _"Your student's college list"_.

It does state the income band, in dollars, as one of the assumption lines,
because the whole point of a likely price is that it is a price _for a family
like this one_ — a net price whose band is unstated is the exact dishonesty
brief 0003 exists to prevent. A student who has not answered the money question
gets the overall published price and net price, labelled as such, and the page
says which question is unanswered. Same tri-state degradation as chat; no
feature is gated on a completed profile (0001 D11/D12).

### D-G. The upgrade cue does not cross to the page

`CollegeCostProfile.precisionOffersFor()` is an in-chat prompt for the _student_
to answer a question ("tell me your state and I can price tuition exactly"). A
logged-out parent cannot answer it, and a page that asks them to would be
selling, not informing. The page therefore suppresses the offer copy and keeps
only its honest half: the labelled statement of what is missing and why the
number is coarser.

### D-H. Not indexed, not cached, not referred

`X-Robots-Tag: noindex, nofollow` plus a `<meta name="robots">`,
`Cache-Control: no-store`, and `Referrer-Policy: no-referrer` on the report
route, so the token does not leak to an outbound link target and no shared proxy
keeps a copy. `GET` stays side-effect-free (the anti-prefetch rule that shaped
`/verify-email` applies doubly to a link designed to be pasted into iMessage).

### D-I. The door is chat; iOS share sheet is a follow-up

The slice's AC says "student triggers share from chat/iOS". Chat satisfies it
today: two tools plus prompt v15, live on the next `service` deploy. `ios-app`
has no share affordance of any kind (no `ShareLink`, no
`UIActivityViewController`) and adding one costs an App Store release, which is
not on this slice's critical path. Reported to /chart as a candidate follow-up
slice.

### D-J. One public-web origin; every page link derives from it

Before this RFC, `service.conf` carried one full URL base, written out in full:

```
emailVerification.verifyUrlBase = "http://"${APP_DOMAIN}":"${PUBLIC_WEB_PORT}"/verify-email"
```

This RFC's first draft added the obvious sibling — `costReport.shareUrlBase`,
the same scheme, the same host, the same port, a different path — and
`.env.prod` grew a second line restating the same origin next to the first. That
is the moment the pattern becomes a duplication: one instance is a value, two is
a convention nobody wrote down, and the third public page would have copied it
again. So it is unified here, in the RFC that created the second instance.

`service.conf` now states the public-web origin exactly **once** and each page
appends only its own path:

```
publicWeb {
  urlBase = "http://"${APP_DOMAIN}":"${PUBLIC_WEB_PORT}
  urlBase = ${?PUBLIC_WEB_URL_BASE}
}

emailVerification.verifyUrlBase = ${publicWeb.urlBase}"/verify-email"
emailVerification.verifyUrlBase = ${?EMAIL_VERIFICATION_VERIFY_URL_BASE}

costReport.shareUrlBase = ${publicWeb.urlBase}"/report"
costReport.shareUrlBase = ${?COST_REPORT_SHARE_URL_BASE}
```

`.env.prod` correspondingly drops its two per-link lines for one:
`PUBLIC_WEB_URL_BASE=https://app.$APP_DOMAIN`. `bin/gen-deployed-env` ships that
one key instead of two. A third public-web page adds a `.conf` line and **no**
dotenv key in any environment.

This works because HOCON resolves substitutions **after** the merge, not at
parse time: an environment that sets `PUBLIC_WEB_URL_BASE` replaces the origin,
and both derived values are computed from the replacement. That is a property of
the config library, not of this file, so it is asserted rather than assumed —
one test sets the origin and reads both links back.

**Backward compatibility is not optional here.**
`EMAIL_VERIFICATION_VERIFY_URL_BASE` and `COST_REPORT_SHARE_URL_BASE` remain
supported and still **win** when set. Only the DEFAULT under them moved. Email
verification is a live, shipped surface and some deployed environment may
already export its variable; a config refactor that silently retargeted those
emails would be the one unacceptable outcome of this change, and "the deploy
files in this repo do not set it" is not evidence about every environment that
exists. The precedence is pinned three ways: origin only, per-link override
only, and both set at once. The escape hatch also stays meaningful — an
environment whose two pages genuinely do not share an origin can still say so,
one page at a time. They are deliberately not in `DEPLOY_VAR_NAMES`, so using
one on the deploy path is an explicit act: add it there and in the `.env.<env>`
together.

**One trailing-slash rule.** Deriving links by string concatenation makes
`PUBLIC_WEB_URL_BASE=https://app.uni.coach/` compose
`https://app.uni.coach//report`, which is a different URL. HOCON cannot
normalize, and teaching each reader its own defence would recreate the
duplication this decision removes — so the rule lives once, in
`ed.unicoach.common.config.normalizeUrlBase`, and both typed readers apply it to
the base they read. It drops surrounding whitespace, then parses the base with
`java.net.URI` and collapses slash runs **in the path only**, dropping a
trailing path slash — so appending `"?token="` composes exactly one link. A
per-link override written with a trailing slash is normalized by the same rule.

The parse is the platform's, not ours. A hand-rolled split on the first `://`
rewrote every slash after it, so a base carrying a URL in its query came back
with that URL's own scheme separator collapsed, and a scheme-relative base
(`//app.uni.coach`) was demoted to a path — a host silently turned into a path
segment. Scheme, authority, query and fragment now pass through untouched, and a
base that is not a URL at all throws, which both typed readers already fold into
`Result.failure`: an unparseable configured origin failing at boot is the honest
outcome.

The **query key** lives in the same file as `TOKEN_QUERY_PARAM`, with a
`tokenLink(base, rawToken)` composer beside it. Both halves of the link contract
read it: `:service` mints with `tokenLink`, and `public-web`'s
`REPORT_TOKEN_PARAM` **is** that constant. The key was previously a literal
inside the minting body and a named constant in the serving module, which is a
contract spelled twice.

## Detailed Design

### The table: `cost_report_shares` (migration 0073)

A hashed credential row, modelled on `verification_tokens` (0014) and
`sessions`: no OCC version, no `_versions` history, because its only mutation is
setting `revoked_at` once under a compare-and-swap.

```sql
-- RFC 155: the student's revocable share link for the Family Cost Report.
-- A hashed credential, not a versioned aggregate: its only mutation is setting
-- revoked_at once, guarded by a compare-and-swap UPDATE. Modeled on
-- verification_tokens (0014) -- only the SHA-256 hash is stored; the raw token
-- exists only in the link the student sends. Unlike a verification token this
-- one is long-lived and multi-use: there is no expires_at and no consumed_at,
-- because a parent re-opens the link months later. Revocation is the control.
-- The raw token is DERIVED, not random: HMAC-SHA256(shareTokenSecret, id), so a
-- re-share reproduces the same link from this row while the database still holds
-- nothing that grants access. Rotating the secret is a global revoke.

CREATE TABLE cost_report_shares (
  id             UUID PRIMARY KEY DEFAULT uuidv7(),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  row_created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

  student_id UUID  NOT NULL REFERENCES students(id) ON DELETE CASCADE,
  token_hash BYTEA NOT NULL,            -- SHA-256 of the raw token; raw never stored
  revoked_at TIMESTAMPTZ NULL           -- set once; a revoked link is dead forever
);

CREATE UNIQUE INDEX cost_report_shares_token_hash_idx
  ON cost_report_shares (token_hash);

-- At most one live share per student (D-B): re-sharing returns the same link,
-- and "revoke" is a promise about every link ever sent, not the latest one.
CREATE UNIQUE INDEX cost_report_shares_one_live_per_student_idx
  ON cost_report_shares (student_id) WHERE revoked_at IS NULL;
```

`ON DELETE CASCADE` on `student_id`: a deleted student's share link must die
with them (brief 0002 is parked, but nothing new should owe it work).

### The read path

```
GET /report?token=<raw>
  -> secretQueryParams("token")                  (request log redaction)
  -> CostReportSource.getByShareToken(raw)
       -> TokenHash.fromRawToken(raw)
       -> CostReportSharesDao.findLiveByTokenHash(session, hash)   -- revoked_at IS NULL
       -> CollegeCostService.readInSession(session, studentId)     -- same connection
  -> CostReportOutcome.Found(profile) | NotFound(reason)
  -> respondCostReportPage(profile) | respondNotFoundPage()
```

The route reads **exactly one** `token` parameter
(`getAll(...)?.singleOrNull()`): two presented credentials is a request we have
no reading of, not a request carrying one credential and some noise. The adapter
then admits only the shape the mint side produces — `ShareToken.isWellFormed`,
43 base64url characters, published beside `ShareTokenDeriver` — so a string this
server could never have issued is refused **before** it is hashed and before any
connection is taken. The previous gate was "not blank", which turned `?token=`
plus a megabyte of newlines into a SHA-256 and an indexed lookup.

One connection, one round of batched statements, list-size independent.
`CollegeCostService.readInSession(session, studentId)` is a **public**
two-argument entry point, published for this caller: the adapter already holds a
session for the token lookup, and the reason it must reuse it is **one read, one
snapshot** — two connections are two points in time, so the share could be
revoked, or the list edited, between resolving the token and reading the
figures, and the page would render a report the second read no longer agrees the
first read authorised. (Nesting a second pool checkout inside the first is also
untidy, but this page serves roughly one request per second, so a claim about
pool exhaustion would not be an honest reason.) The three-argument form carrying
the college-id filter stays `internal`, because a filtered read is a chat
concern and the report always reads the whole list.

`findLiveByTokenHash` returns `Result<CostReportShare?>` — an unknown or revoked
hash is an ABSENCE, not a failed read, so the adapter reads a null rather than
folding a `NotFoundException` back into one by hand. The three share reads
(`findLiveByStudent`, `findLiveByTokenHash`, `revokeLive`) all follow the repo's
`orNullOnNotFound()` idiom for the same reason.

The route's three privacy headers (D-H) are installed **route-scoped**, beside
`secretQueryParams("token")`, as `setLinkHolderPrivacyHeaders()` in
`web-common/.../http/PrivateResponse.kt`. They are a standing property of the
route, so they ride the page, the 404 and the 503 alike, and a handler added
under `/report` later cannot forget them. The one `noindex, nofollow` string is
`ROBOTS_NOINDEX` there, read by both the header and the `<meta>` in the shared
layout, so the head and the body cannot disagree.

`/report` and the `token` query key are named constants (`REPORT_PATH`,
`REPORT_TOKEN_PARAM`) in `Routing.kt`, and a `public-web` test pins the
**packaged** `costReport.shareUrlBase` default against them. That pairing is a
contract the compiler cannot see: a link already in a parent's text thread
cannot be updated, so renaming either side alone would 404 every live link with
nothing failing.

An unknown, malformed or revoked token renders the existing branded 404 — never
"revoked", never "expired", because distinguishing them tells a stranger that a
token _was_ real. The **rendered body is byte-identical** for every one of them,
and a test asserts it. The DOMAIN is not blind, though:
`CostReportOutcome.NotFound` carries a `MissReason` (blank, repeated parameter,
malformed, no live share) that is **logged only** and never rendered, because an
operator answering "the link I sent my wife 404s" otherwise cannot tell a
truncated URL from a revoked share.

A read fault is still the branded 503 — and the `StatusPages` `exception`
handler now **logs the cause** with the request method and
`call.request.path()`. The path, never the uri and never the query: `/report`'s
query carries a live share token, and the log-redaction seam is not reachable
from that handler. A 5xx with no log entry anywhere was the one thing this
funnel guaranteed before.

Both `:service` and the `public-web` port publish their own failure type
(`CostReportShareFailedException`, `CostReportReadFailedException`), so a Hikari
checkout timeout no longer reaches Ktor as a bare `java.sql.SQLException` and
the ports' declared failure is not "anything at all".

### The page

`kotlinx.html` inside the existing `siteLayout`, so the report wears the site
chrome. Structure, top to bottom:

1. **Title** — "Your student's college list", and the live-report sentence.
2. **The assumption lines**, as ordinary sentences above the table, rendered as
   a WHOLE LIST from the domain rather than named one by one:
   `ComparisonBasis.statements` for a comparison,
   `SingleSchoolBasis.of(cost, residency).statements` for a one-school report,
   then the family's own income-band line
   (`MoneyBasis.of(moneyProfile).statement`). `SingleSchoolBasis` is a typed
   object like its comparison twin — it takes the `CollegeResidencyBasis` the
   page already holds rather than a sentence flattened from it — and
   `MoneyBasis` is a sealed vocabulary (`AnsweredBand` / `Declined` /
   `Unanswered`) carrying a code beside each statement. Its boolean ladder used
   to fold ANSWERED-with-a-null-band into the "unanswered" copy, telling a
   family that answered the income question that it never did; that pair is now
   refused with `CorruptPersistedValueException`, exactly as its residency twin
   in this same slice refuses the analogue. The money line is the FLOOR: a
   college list that somehow reaches the page with no basis object still states
   whose price these figures are. The sentences are not rewritten here — and
   because the page prints every statement the domain hands it, a sixth honesty
   statement added to the domain reaches the parent instead of reaching only the
   coach. The comparison shape is present only when the list carries two or more
   colleges, exactly as in chat; below that the domain builds no comparison and
   `SingleSchoolBasis` states the population, residency, arrangement, year and
   aid basis for that one school instead. All of this copy lives in the cost
   domain (`:service`), not in the renderer: brief 0003's money vocabulary is
   domain-owned. The per-school residency sentence is derived ONCE per page and
   passed down, so the assumption line and the school's own line are one fact.
3. **The cross-school summary table** — rows are schools, shown only when the
   list carries two or more, which is exactly when the assumption lines above
   are present. Without it the page would state five comparison assumptions over
   a set of separate tables and assert a comparison it never makes. Columns: the
   way of living held constant, tuition and fees for that way of living, the
   published price, and the likely price after a financial aid offer. The
   arrangement held constant is `ArrangementBasis.comparable`'s own first entry
   — the same one the assumption line above names — and when the domain finds
   none comparable the column is absent and the page says why, because an
   invented "held constant" is the dishonesty those lines exist against. Every
   degradation rule below applies here too: a labelled blank, never a zero,
   never the neighbouring school's number, and no total summed from parts.
4. **The per-school detail** — one table per school, kept below the summary
   because it is the only shape that can carry an arrangement axis: columns are
   the ways of living that school is priced for, so a total belongs to exactly
   one column and no figure is read across two. Rows: tuition and fees (the
   stable block) above housing and food and the other estimated components, then
   that arrangement's total, then the published price, then the likely price
   after a financial aid offer. A missing part is a labelled blank, never a
   zero, never a neighbour's number; an arrangement missing a part shows no
   total. Component rows are keyed by `ComponentRole`, which lives beside
   `CostField` in the cost domain and is mapped by an exhaustive `when` with no
   `else` — a seventh published component must fail to COMPILE rather than
   silently render no row while still being counted in a total. Every figure on
   the page goes through ONE renderer (`figureBody`) over a sealed
   `SchoolFigure` — `Amount(amountUsd, note)` or `Blank(label)`, built by the
   one `figureOf(...)` constructor: the dollars in a styled span, or the
   labelled blank in `report-blank`. The record it replaces packed both states
   in one value and two of its four combinations were nonsense. A figure's basis
   note (for example "an overall average across all families") now belongs to
   the case that HAS a figure, so it cannot be attached to a blank at all —
   under a blank it described a number that is not there. Wide-screen table, and
   a stacked card-per-school layout below the phone breakpoint — the summary
   stacks the same way — because a five-column table on a phone is unreadable,
   and the parent is on a phone.
5. **Merit practice**, where reported: "X% of all full-time freshmen received
   non-need aid, average $Y", citing the school's own Common Data Set and cycle.
   The wire key stays `share_of_all_full_time_freshmen_pct`; the page never says
   "without need". A row with NO measure under it — recipients on file but no
   freshman headcount to make a share out of and no average — renders no section
   at all: a heading and a "Source:" line with nothing between them is a section
   claiming a school reported something while showing nothing it reported.
6. **Debt context**, undated because no source we hold dates it.
7. **Sources and what this is not** — a short block naming the U.S. Department
   of Education College Scorecard and the CDS, stating that these are averages
   and not an offer, and that only a school's own financial aid offer is a price
   for this family.
8. **Two honest asides**, both user-visible and both load-bearing. A list with
   no college on it says so instead of rendering an empty table — the page is
   live (D-A), so a parent can open it before the student has added a school.
   And each table carries one line saying the published price is the school's
   own blended average, so it is never read as the sum of the parts beside it.

Money vocabulary is brief 0003's, without exception: _tuition and fees_,
_housing and food_, _the published price_, _a financial aid offer_. Loans and
work-study are never subtracted from anything; the aid-basis line says so.

New CSS in `static/site.css` (`report-*` classes). No new asset pipeline.

### The chat door

Two `StudentScopedChatTool`s, registered in `rest-server` `Application.kt`'s
`ToolRegistry`:

- **`share_cost_report`** — returns the live link for this student, minting one
  if none exists. Idempotent by D-B. The payload keys are `link_created` (true
  in every link case); `url`; `newly_created` (so the coach says "the same link
  as before" rather than implying the old one has been replaced); `live_report`,
  the D-A sentence said every time the link changes hands; `who_can_see`, the
  plain sentence about the link's reach; and `previous_link_no_longer_works`,
  with `previous_link_note` present only when that flag is true. The two
  sentences ride the payload rather than the prompt alone, so a prompt rollback
  to v14 cannot strand the link without them.

  The service returns a THREE-CASE union — `Existing`, `Minted`, `Reissued`,
  each carrying the RAW TOKEN and the base rather than a formatted string — and
  both wire booleans are decided by ONE exhaustive `when` over those three
  cases. Two independent booleans spanned a fourth combination that is nonsense
  ("I handed back your old link, and your old link is dead"), and it compiled
  and was speakable; deriving them by negation (`!is Existing`) was the same
  `else` in disguise, and a fourth case would have shipped
  `newly_created = true` silently.

  **Two shares at once** is an ordinary outcome, not a failed write. The
  one-live-share index refuses the loser's insert, and the loser's student has a
  perfectly good link — the winner's — so the service re-reads once on the
  unique-violation SQLSTATE and hands that link back. The mint path also no
  longer revokes unconditionally: it revokes only the row it KNOWS is stale, so
  a lost race cannot silently kill the link a parent already holds.

  An unconfigured share-token secret (`ShareCostReportOutcome.Unavailable`)
  comes back as a RESULT, not through the error channel: `link_created = false`
  plus the `statement` the coach can say. A deployment with no secret is not a
  fault of this student's request, and the model must be able to tell "there is
  nothing to try" from "the write failed, try again".
- **`revoke_cost_report_share`** — revokes the live share. The service returns a
  typed `RevokeCostReportOutcome` — `Revoked(share)`, carrying the row and its
  `revoked_at` stamp, or `NothingLive` — and the tool derives the wire `revoked`
  boolean from the case, beside the `statement` the coach can say. Safe to call
  twice; a second call is an outcome, not an error. Its `RESULT_KEY` reads its
  sibling's constant rather than retyping the wire key, so two doors cannot end
  up answering under different names.

The link base is config, mirroring `emailVerification.verifyUrlBase`:

```
publicWeb {
  urlBase = "http://"${APP_DOMAIN}":"${PUBLIC_WEB_PORT}
  urlBase = ${?PUBLIC_WEB_URL_BASE}
}

costReport {
  shareUrlBase = ${publicWeb.urlBase}"/report"
  shareUrlBase = ${?COST_REPORT_SHARE_URL_BASE}
}
```

`costReport.shareTokenSecret` is read through `Config.optionalString(path)` in
`:common` — one shared reading of "unset", because an env var exported as `""`
leaves the key present and saying nothing — and is carried as
`ShareTokenSecret`, a value class with a private constructor and a validating
factory. Absence is the null; a value shorter than `ShareTokenSecret.MIN_LENGTH`
(32) does not exist either — a present-but-too-short secret is a
misconfiguration, not an unset deployment, and it is refused loudly through the
reader's own `Result.failure` rather than signing every family's link. The share
URL base can no longer type-check as the HMAC key. `ShareTokenDeriver` is built
by the composition root (`rest-server` `Application.kt`) and injected into
`CostReportShareService`, which is null exactly when no secret is configured —
and when it IS null the composition root **warns once at boot**, naming
`CostReportConfig.SHARE_TOKEN_SECRET_PATH` and which tool declines. Sharing that
disables itself for every student forever previously said so only to a student,
inside a chat turn.

still overridable by `COST_REPORT_SHARE_URL_BASE`, but no longer set per-link in
a cloud env: `.env.prod` sets the one origin,
`PUBLIC_WEB_URL_BASE=https://app.${APP_DOMAIN}`, and this link derives from it
(D-J).

**Coach prompt v15** (migration 0074) — the current v14 body byte-identical plus
one appended paragraph: the coach may offer the report when a cost comparison
has actually happened, must say plainly that anyone with the link can see it and
that the student can revoke it at any time, must never share it without the
student asking, and must not nudge (that is `first-value/06`). Rollback is one
env var: `COACHING_SYSTEM_PROMPT_VERSION=v14`.

Value before ask (0001 D12): the report is offered after value has been
delivered, is never required, and declining changes nothing about what the coach
will do next.

## Files Modified

**New**

- `db/schema/0073.create-cost-report-shares.sql`
- `db/schema/0074.seed-coach-system-prompt-v15.sql`
- `db/src/main/kotlin/ed/unicoach/db/models/CostReportShareId.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/CostReportShare.kt`
- `db/src/main/kotlin/ed/unicoach/db/models/NewCostReportShare.kt`
- `db/src/main/kotlin/ed/unicoach/db/dao/CostReportSharesDao.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/report/CostReportShareService.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/report/ShareCostReportChatTool.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/report/RevokeCostReportShareChatTool.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/report/CostReportConfig.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/report/ShareTokenDeriver.kt` —
  the HMAC derivation D-B decided, in one place
- `public-web/src/main/kotlin/ed/unicoach/web/report/CostReportSource.kt` (port)
- `public-web/src/main/kotlin/ed/unicoach/web/report/ServiceCostReportSource.kt`
- `public-web/src/main/kotlin/ed/unicoach/web/render/CostReportPage.kt`
- `service/src/main/kotlin/ed/unicoach/coaching/costs/SingleSchoolBasis.kt` —
  the one-school basis copy, the family's income-band line (`MoneyBasis`) and
  the Scorecard attribution (`CostSources`), all in the domain that owns the
  vocabulary
- `web-common/src/main/kotlin/ed/unicoach/web/common/http/PrivateResponse.kt` —
  `setLinkHolderPrivacyHeaders()` and the four named header constants beside
  `ROBOTS_NOINDEX`
- `common/src/main/kotlin/ed/unicoach/common/config/UrlBase.kt` —
  `normalizeUrlBase`, the ONE trailing-slash rule for a derived link base (D-J),
  plus `TOKEN_QUERY_PARAM` and `tokenLink(base, rawToken)`: the one composer
  both the minting side and the serving side read
- `common/src/test/kotlin/ed/unicoach/common/config/UrlBaseTest.kt`
- `common/src/main/kotlin/ed/unicoach/common/util/Phrase.kt` — `phraseOf`, moved
  out of `ComparisonBasis` now that two modules say lists of domain words
- `service/src/test/kotlin/ed/unicoach/coaching/report/ReportTestDb.kt` — the
  one fixture both report suites build a service from
- `service/src/test/kotlin/ed/unicoach/coaching/report/CostReportConfigTest.kt`
- `public-web/src/test/kotlin/ed/unicoach/web/ReportLinkContractTest.kt` — pins
  the packaged `shareUrlBase` default against `REPORT_PATH` /
  `REPORT_TOKEN_PARAM`

**Changed**

- `public-web/build.gradle.kts` — `implementation(project(":service"))`
- `public-web/src/main/kotlin/ed/unicoach/web/Application.kt` — build the
  source, inject it
- `public-web/src/main/kotlin/ed/unicoach/web/Routing.kt` — `GET /report`,
  `secretQueryParams("token")`, `setLinkHolderPrivacyHeaders()`, and the
  `REPORT_PATH` / `REPORT_TOKEN_PARAM` constants
- `public-web/src/main/kotlin/ed/unicoach/web/render/Layout.kt` — optional
  `noindex`, reading `ROBOTS_NOINDEX`
- `public-web/src/main/resources/static/site.css` — `report-*` classes
- `service/src/main/kotlin/ed/unicoach/coaching/costs/CollegeCostService.kt` —
  publish the two-argument `readInSession` the report's read path uses, plus the
  `tuitionLineOf` / `applicableTuitionFor` / `reportedOf` derivations the
  report's own fixtures must not re-decide
- `service/src/main/kotlin/ed/unicoach/coaching/costs/CostField.kt` —
  `ComponentRole` and the exhaustive `CostField.componentRole` mapping
- `service/src/main/kotlin/ed/unicoach/coaching/costs/ComparisonBasis.kt` —
  `statements`, and `phraseOf` read from `:common`
- `service/src/main/kotlin/ed/unicoach/coaching/StudentScopedChatTool.kt` —
  `noArgumentToolDefinition`, shared by both report tools
- `common/src/main/kotlin/ed/unicoach/common/config/AppConfig.kt` —
  `Config.optionalString(path)`
- `db/src/main/kotlin/ed/unicoach/db/dao/SqlSessionQueries.kt` —
  `SqlSession.nextUuidV7()`, the generic id read `nextId` is now sugar over
- `bin/scripts-tests` — the full-cloud-env fixture carries the one origin
  `PUBLIC_WEB_URL_BASE` that `bin/gen-deployed-env` now requires, and asserts
  neither retired per-link key is shipped (D-J)
- `service/src/main/resources/service.conf` — `publicWeb.urlBase` (D-J),
  `emailVerification.verifyUrlBase` and `costReport.shareUrlBase` derived from
  it, `costReport.shareTokenSecret`, `systemPromptVersion = "v15"`
- `service/src/main/kotlin/ed/unicoach/auth/EmailVerificationConfig.kt` — reads
  its base through `normalizeUrlBase` (D-J); the value and its override contract
  are otherwise unchanged
- `infra/` + `bin/gen-deployed-env` + `CONFIGURATION.md` —
  `COST_REPORT_SHARE_TOKEN_SECRET` as an SSM-sourced secret under
  `/unicoach/<env>`, mirroring the App Store credential trio. Never committed;
  `.env.prod` carries no value. Only `:service` reads it
- `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt` — register the
  two tools, and warn once at boot when no share-token secret is configured
- `db/src/main/kotlin/ed/unicoach/db/dao/CostReportSharesDao.kt` — `create` maps
  the `23505` one-live-share violation to `ConstraintViolationException`, so the
  mint path can tell a lost race from a fault without reading SQLSTATE strings
- `.env`, `.env.template`, `.env.prod`, `bin/gen-deployed-env`,
  `CONFIGURATION.md` — the one public-web origin `PUBLIC_WEB_URL_BASE` (D-J),
  replacing the two per-link keys in the cloud env and in the shipped
  `DEPLOY_VAR_NAMES` set. Not a value in `.env`: that layer is env-**neutral**,
  and the origin differs per environment — `.env` only documents that
  `PUBLIC_WEB_PORT` feeds the committed local default
- every existing `public-web` test that calls `publicWebModule(...)`
  positionally, and every existing `rest-server` test that calls
  `appModule(...)` positionally — both signatures gain a parameter, so the
  call-site churn is forced rather than chosen

## Implementation Plan

1. Migration 0073 + the `CostReportShare` model, id, and DAO (`nextId` —
   `SELECT uuidv7()`; `create` over an id-carrying `NewCostReportShare`;
   `findLiveByStudent`; `findLiveByTokenHash`; `revokeLive` as a CAS
   `UPDATE ... WHERE student_id = ? AND revoked_at IS NULL RETURNING`). The id
   is minted BEFORE the insert rather than by the column default because D-B
   derives the token from the row id — the id is the HMAC input, so it has to
   exist before the hash stored beside it. That is a consequence of D-B, not a
   deviation from it, and the alternative writes a row that is briefly not a
   valid credential.
2. `CostReportShareService` in `:service` — mint-or-return and revoke, over
   `ShareTokenDeriver` (HMAC-SHA256 of the row id, D-B) + `TokenHash`,
   re-deriving the same raw token on every share and revoking the row when the
   derivation no longer matches the stored hash. No `TokenGenerator`: a random
   token cannot be re-derived, so D-B's "the same link comes back" forbids one.
3. The two chat tools + registration + `costReport.shareUrlBase` config.
4. Migration 0074: prompt v15 = v14 body + one appended paragraph; pin
   `systemPromptVersion`.
5. `public-web`: the `:service` edge, the `CostReportSource` port, the
   `ServiceCostReportSource` adapter.
6. `CostReportPage.kt` + CSS + the `GET /report` route with redaction and the
   three headers.
7. Env/config/docs wiring.

## Tests

- **DAO** (`CostReportSharesDaoTest`, real DB, `VerificationTokensDaoTest`
  shape): create; `findLiveByTokenHash` hit; revoked token misses; unknown hash
  misses; the one-live-per-student index rejects a second live row; revoke is a
  CAS that returns nothing the second time; and the cascade on student delete,
  asserted twice — the `ON DELETE CASCADE` clause read off `pg_constraint`, and
  the cascade itself EXECUTED (insert a student and a share, delete the student,
  the share is gone) inside a transaction that disables the physical-delete
  trigger and is then rolled back, since no student row can be physically
  deleted in production.
- **Service** (`CostReportShareServiceTest`): mint returns a raw token that is
  not stored anywhere on the row; sharing twice returns **the same link** and
  the `Existing` case; revoke-then-share `Minted`s a different token and the old
  hash no longer resolves; a rotated secret revokes the stale row and returns
  `Reissued`; a **missing secret** degrades to `Unavailable` rather than a
  crash; and **six concurrent shares** for one student all hand back the SAME
  link, leaving exactly one row — the lost-race path, which used to be reported
  to the coach as a failed write while a live link existed. Both report suites
  build their service through the one `ReportTestDb` fixture, which delegates
  its connection and student plumbing to `CoachingTestDb` on the `CostsTestDb`
  precedent. That fixture builds its HOCON with `ConfigFactory.parseMap` (a
  secret containing a quote no longer parses to a different secret) and reads a
  minted token back through `java.net.URI`, refusing a link that carries none —
  the previous `substringAfter` returned the whole url when the marker was
  absent, which is a silent pass.
- **Config** (`CostReportConfigTest`, the `CoachingConfigTest` /
  `EmailVerificationConfigTest` shape): `CostReportConfig.from` reads the
  PACKAGED `service.conf` — the call `startServer` makes, which no other test in
  this slice exercises — and an unset `COST_REPORT_SHARE_TOKEN_SECRET` reads as
  `null` rather than failing, which is what "the server still starts" means. The
  absent-secret half resolves the packaged file offline, with the system
  environment switched off, because this repo's own `.env.dev` sets that
  variable for local development. A blank secret reads as unset too — an env var
  exported as `""` is a key that is present and says nothing — and a configured
  one is a `ShareTokenSecret`, which has no empty value to construct — nor any
  value shorter than `MIN_LENGTH`: a short secret is refused with a message
  naming the config path, and the floor itself is accepted, so the boundary is
  pinned from both sides.
- **Token shape** (`ShareTokenTest`): the reader accepts exactly what the
  deriver mints, and refuses a blank, a short, a long, a padded, an
  out-of-alphabet and a newline-carrying token — before any of them is hashed.
- **Chat tools**: happy path payload shape, including `newly_created` true on
  the first share and false on the second; idempotence; revoke with nothing
  live; a surplus input field refused by name; the tools never return a raw
  token for another student; and an unconfigured secret arriving as a RESULT
  (`link_created = false` plus the statement, no `error` key, no invented
  `url`).
- **Page rendering** (`CostReportPageTest`, `FakeCostReportSource`, no DB):
  200 + `text/html` + site chrome for a live token; branded 404 for unknown,
  revoked and blank tokens, with identical bodies; `no-store`, `X-Robots-Tag`,
  `Referrer-Policy` present; the five assumption sentences present for a
  two-school list and absent for one school; the cross-school summary table
  present for two schools and absent for one, holding the same way of living its
  assumption line names, keeping the per-school detail below it, and rendering a
  labelled blank rather than a neighbour's figure; a missing component renders a
  labelled blank and the arrangement shows no total; `with_family` shows no
  housing and food line; an unanswered money profile renders the overall figures
  with the labelled basis and no band claim; every blank on the page — including
  a whole-school one — is the SAME styled `report-blank` span; a basis note is
  printed only where there is a figure to describe; and a merit row with no
  measure under it opens no merit section at all.
- **Vocabulary guard**: the rendered body never contains "room and board",
  "sticker price", "award" or "without need".
- **Log redaction**: a request to `/report?token=SECRET` logs `token=[redacted]`
  and never the raw value.
- **Miss and fault logging** (`CostReportRoutingTest`): a repeated `token`
  parameter is a miss and never reaches the port; the 404 body is byte-identical
  across an unknown token, an absent one and a repeated one, while the
  `MissReason` for each is logged; and a read fault logs its CAUSE with
  `path=[/report]` and never the token.
- **Prompt seed** (`SystemPromptsSeedTest` shape): v15 exists, v14 row is
  unchanged and still selectable. Its append extraction, and the seven older
  ones, go through one `appendedParagraph(base, revised)` helper that asserts
  the prefix before removing it — `removePrefix` is a silent no-op on a
  mismatch, which would let every downstream `contains` pass vacuously.
- **Link contract** (`ReportLinkContractTest`, `public-web`): the PACKAGED
  `costReport.shareUrlBase` still ends at `REPORT_PATH`, and the route still
  reads `REPORT_TOKEN_PARAM`. Renaming either side alone would 404 every link
  already sent, with nothing else failing.
- **One public-web origin** (`ServiceConfTest`, D-J): the packaged default
  derives BOTH links from one local origin; `PUBLIC_WEB_URL_BASE` propagates
  into both (HOCON resolves after the merge — asserted, not assumed); a trailing
  slash on it doubles no separator; and each per-link escape hatch still WINS,
  tested three ways — origin only, per-link only, and both set at once. The
  email-verification half is the load-bearing one: it is a shipped surface, so a
  default that quietly retargeted it is the failure this group exists to
  prevent.
- **The trailing-slash rule** (`UrlBaseTest`, `:common`): one rule, eight cases
  — doubled separator, already-clean, the scheme's own `://`, a trailing slash
  on the composed base, dotenv whitespace, three-or-more slashes, a URL inside
  the QUERY keeping its own scheme separator, and a scheme-relative base keeping
  its host. The last two are the ones the hand-rolled parser got wrong.
- **Shell** (`bin/shell-tests`): `bin/gen-deployed-env` now requires
  `PUBLIC_WEB_URL_BASE`, so the full-cloud-env fixture in `bin/scripts-tests`
  carries it — and the happy-path test asserts the origin is emitted resolved
  and that neither retired per-link key is shipped.
- Full `nix develop -c bin/test` is the gate.

## Open items

- Extracting `coaching/costs` into a db-only module (D-E) — deferred while
  `pipeline/rfc-152` is live.
- `/verify-email` does not call `secretQueryParams("token")` today, so its raw
  token is written to the request log. Out of scope here; reported.
- A shared `runCatchingCancellable` in `:common`: three sites in this repo now
  hand-write the same "rethrow `CancellationException`, fold everything else to
  `Result.failure`" wrapper. It is a genuine repo-wide follow-up and does not
  ride a feature branch; the local wrappers stay.
- `first-value/06` adds the share CTA, the RFC 93 commitment trigger and share
  events on this surface.
- An iOS share sheet (D-I) is a candidate follow-up slice for /chart.
