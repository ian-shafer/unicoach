# RFC 148: Admissions intelligence in chat — the cited `college_admissions_profile` tool

Status: Draft

Product brief 0001, slice **S4b** — the second half of the Admissions
Intelligence Layer, whose schema half landed as RFC 140. Standing context:
gate-2 decisions D7–D12 in `product/0001-v1-differentiator/spec.md` (D10: no new
table here, so nothing needs DDL sign-off; D12: value before ask, so nothing
this slice adds may gate an answer), plus brief 0003's money-language rules
(RFCs 141, 142, 143, 145) — this tool's output is a money surface.

## Summary

S4a landed three cited CDS reference tables, the collegedata.fyi seed, the
ingest, and the coverage report. Nothing reads them. This RFC builds the read
side: (1) `college_admissions_profile`, a student-scoped chat tool over
`college_merit_aid`, `college_admission_factors` and `college_deadlines`,
mirroring `college_cost_profile`'s shape with a **per-college, per-section**
citation carrying the school's own CDS document and the corpus archive copy; (2)
a **merit-aid feed** into S3's cost answers, a purely additive `merit_aid`
sub-object on `college_cost_profile`; (3) **coach prompt v8**, one appended
paragraph naming the new tool and carrying the honest denominator sentence; and
(4) the RFC 139 provenance wiring RFC 140 left open, putting the CDS sources and
row counts into the `college_index_build` row. No new table, no new column, no
API or iOS change.

## Motivation

The coach can already cite what a school costs. It cannot cite anything the
school itself published about how it admits and how it pays — the layer families
argue about at the kitchen table: does this school hand money to a student who
will not qualify for need-based aid, how much, what does the admission office
weigh, and when is everything due. Every selective school publishes those facts
in its Common Data Set, we have 415 of them loaded and cited, and today they are
dead weight in Postgres.

This is worth a slice rather than a prompt paragraph because the failure mode of
an LLM asked about merit aid or admissions weighting is not silence — it is
fluent invention. Ask a model what percentage of freshmen get merit money at a
mid-selective private and it will produce a number, sourced from nothing. Our
answer has been the same since RFC 135: real figures, from a named source, with
the gaps stated as gaps. This slice extends that promise to a second, messier
source.

Messier is the whole design problem. Scorecard is a federal file with uniform
coverage; the CDS corpus is 368 merit rows, 375 factor grids and 1031 deadline
rows of school-authored reporting whose interesting columns are frequently
blank, occasionally zero, and split across two cycles. This tool earns its keep
only if every one of those states renders as an honest sentence rather than an
estimate, a silence, or a lie of arithmetic.

## Detailed Design

### The honest denominator (binding, D4)

The product spec asked for "X% of freshmen without need got merit here, avg $Y".
That sentence cannot be said, and the reason is the single most tempting mistake
in this slice.

CDS section H2A reports two numbers about non-need aid: `H.2A01`, the count of
first-time full-time freshmen who had **no financial need** and were awarded
institutional non-need (merit) aid, and `H.2A02`, the average of those awards.
It reports no count of no-need freshmen. The only population figure in the
section is `H.201` — **all** degree-seeking first-time full-time freshmen, needy
or not — which is what `college_merit_aid.freshmen_ft_total` holds, as migration
0054's own column comment says.

So the tempting phrasing divides a no-need numerator by an all-freshmen
denominator, then labels the quotient with the numerator's population. At a
school where half the class demonstrates need, "23% of freshmen without need
received merit aid" understates the real rate by roughly a factor of two, and
the family reading it is told something no one measured. It is not a rounding
problem or a caveat problem: it is a different statistic wearing the right
statistic's name.

**The binding phrasing** is therefore: _of ALL full-time freshmen, X% received
non-need (merit) aid; the average award was $Y_. The denominator rides with the
number everywhere — in the wire key, in a label emitted from the same construct
as the number (RFC 142's "leave no vacuum" rule), in the tool description, and
in the coach prompt. `product/STATUS.md`'s binding constraint and
`CollegeMeritAid`'s own kdoc already agree; this RFC makes the code agree, and
the spec's earlier wording is superseded.

The data forces three more rules, each measured against the committed seed:

- **The share is emitted only when both numbers are present.**
  `freshmen_ft_total` is NULL in **96 of 368 rows (26%)** and
  `no_need_merit_count` in **30 (8%)**, so a quarter of the corpus cannot
  produce a share at all. Those colleges still answer — the average award is an
  independent fact and may appear alone — and the missing share is reported
  missing, never reconstructed from a peer group, a prior cycle, or a guess.
- **Zero is a real value.** `no_need_merit_count` is exactly 0 in 9 rows and
  `no_need_merit_avg` in 6; Amherst reports 480 freshmen, 0 merit awards, $0
  average. "This school awarded no non-need aid to freshmen" is one of the most
  useful sentences the tool can say, so nullability is the only test for
  missing, never falsiness.
- **A missing row is not a zero and not an error.** A college with no
  `college_merit_aid` row still appears, with `merit_aid` named in
  `data_availability` so the coach says the school does not report it —
  `no_need_merit_avg` alone is NULL in 18% of rows, so silence is common and
  must be speakable.
- **A row with no merit measure is silence too.** `freshmen_ft_total` is the
  share's DENOMINATOR, not a merit fact, and **28 of the 368 rows carry only
  it**. Emptiness therefore means both merit measures are NULL — not all three
  columns. Counting the total as data would render, for 7.6% of the corpus, a
  Common Data Set citation with no merit fact beneath it AND keep those schools
  out of `data_availability`, making their merit silence unsayable: the exact
  failure the binding constraint above exists to prevent. `MeritPractice.from`
  returns null for such a row, so both tools call it silence from one rule.
- **A zero freshman class is the same silence.** `freshmen_ft_total >= 0` is in
  domain and the `no_need_merit_count <= freshmen_ft_total` CHECK then forces 0
  recipients, so a mangled extraction can report a school with no freshman class
  at all. Rendered, that is two bare zeroes, no share, no `share_label` and
  nothing in `data_availability` — a fourth state nobody declared. It is ruled
  on exactly like the denominator-only row: `MeritPractice.from` returns null,
  and the coach reads the school's merit silence where every other silence is
  named.

**The share is a type, not a bare `Double`.** `Share`
(`common/src/main/kotlin/ed/unicoach/common/util/Share.kt`, on the `DataSize`
precedent) owns the ratio -> percent conversion, the one-decimal rounding rule
and the spoken form. Before it, those were three unnamed literals (`1000.0`,
`10.0`, `"%.1f"`) split across two files — so the payload NUMBER and its SPOKEN
label, the two things this section requires to agree, were free to drift apart.
The repo also runs two incompatible rate scales on bare doubles already
(`College.pctPell` is 0-1, the IPEDS disability figure is 0-100), kept apart by
a comment. The wire payload is byte-identical:
`share_of_all_full_time_freshmen_pct` still carries the same number.

The dollar figure beside it is rendered by `WholeDollars.spoken`
(`common/src/main/kotlin/ed/unicoach/common/money/WholeDollars.kt`), so
`common/money` — this repo's home for money display — owns the one `"$%,d"`
format instead of a renderer inventing a second one. Both formatters, and
`CdsCitation.cycleLabel`, pin `Locale.US`: under a non-US default locale the
ambient formatter would speak `5,2` and `$12.500` beside payload numbers of
`5.2` and `12500`, which is exactly the label-number disagreement this section
forbids.

### The tool (D1)

`CollegeAdmissionsChatTool` in a new package `ed.unicoach.coaching.admissions`
(module `:service`), with `CollegeAdmissionsService` beside it and an
`AdmissionsField` wire vocabulary — the `costs` package's shape, mirrored rather
than invented. The package ships five files: the tool, the service,
`AdmissionsField`, `MeritAidWire` (which owns the merit sub-object's wire
rendering so the cost tool can emit the same object from the same one home, D7),
and `CdsCitation` (the per-section citation and its renderer, D2).

It extends `StudentScopedChatTool`, so the model never supplies a student id,
and it is a **thin adapter**: `execute` only does parse -> read -> render, and
every composition rule lives in the service. Input is one optional `college_ids`
array of UUID strings, resolved only against the student's own active college
list. Absence means the whole active list; `[]` means literally zero schools and
is never normalised back to "all"; the cap is `MAX_COLLEGE_IDS = 50`; ids not on
the list come back in `unknown_college_ids` while the known ids still answer.
Malformed input returns a structured `{"error": ...}` object, never a throw.

`CancellationException` is rethrown, never folded into `Result.failure`, in this
service AND in the sibling `CollegeCostService`: a chat turn the caller
abandoned is not a database fault, and treating it as one would log a false
failure, tell the model "college admissions read failed", and stop the
cancellation propagating.

**Mirroring the cost tool means sharing its scaffolding, not copying it.** Both
tools advertise the same `college_ids` input and must reject the same input the
same way, so the schema, the cap, the unknown-field rejection and the per-entry
uuid parse live on `StudentScopedChatTool`, which already declares itself the
one home for this family's input scaffolding (`readCollegeIds`,
`putCollegeIdsSchema`, `MAX_COLLEGE_IDS`). Both services likewise start from the
same read — the active list, the optional subset split, the batched `listByIds`,
and the invariant that a listed id always has a `colleges` row — so that read is
one type, `ed.unicoach.coaching.StudentCollegeSelection`, used by
`CollegeCostService` and `CollegeAdmissionsService` alike.
`college_cost_profile` behaves exactly as it did; only the duplication is gone.
The shared `input_schema` also declares `additionalProperties: false`, so the
PUBLISHED boundary states the same closed set the one parser enforces instead of
inviting a key that is then refused at runtime.

Two correctness rules ride on that shared code. The per-entry parse wraps the
UUID parse ALONE, so a future `CollegeId` validation failure cannot be
relabelled "not a uuid", and the discarded cause is logged rather than dropped.
And a read that fails logs the `college_ids` subset it was called with (null
meaning the whole active list): the wire error is deliberately opaque, so the
log is the only place the failing call can be reproduced from.

The result is one object per college — `college_id`, `name`, `city`, `state`,
`list_status` — carrying up to three optional sections, `merit_aid`,
`admission_factors` and `deadlines`, plus a `data_availability` array naming the
`AdmissionsField` wire names this college does not report. Null measures are
**omitted**, never rendered as null; optional keys are absent rather than empty,
so the presence of a key stays meaningful. Registration is one appended
`ToolRegistry` entry in `rest-server/.../Application.kt` — the list's own
comment says new tools append there, and nothing else in the loop changes.

### Citation: per college, per section (D2)

`college_cost_profile` carries a single payload-level `source` string, because
every figure in it comes from one federal file. CDS facts do not: each row is a
different school's own document, in its own cycle, with its own archive copy. So
each of `merit_aid`, `admission_factors` and `deadlines` carries its own
citation object:

```json
"source": {
  "cited_as": "Amherst College's 2024-25 Common Data Set",
  "url": "https://www.amherst.edu/.../cds.pdf",
  "archive_url": "https://www.collegedata.fyi/schools/amherst/2024-25"
}
```

`CdsCitation` and the `putCitation` renderer live together in their own file,
`admissions/CdsCitation.kt` — one home for the type and its wire shape, so the
factors and deadlines sections do not reach into the merit section's wire object
to cite themselves.

`cited_as` is a **spoken string** the coach can read aloud verbatim, and it is
COMPUTED: `CdsCitation` holds the college name and the `source_year`, and
renders the sentence from a cycle label (2024 -> "2024-25"). Holding the parts
rather than the finished string is what makes "never a bare year" a property of
the type instead of a convention every construction site has to remember. The
bare year is never emitted under a bare key: a lone `2024` is a number with no
measure meaning, exactly the shape RFC 143's guard exists to catch. `source_url`
is the school's own publication and is wildly heterogeneous — PDFs, `.xlsx`,
Google Drive downloads, signed Box CDN URLs over a kilobyte long — so no
renderer may assume it is short or human-readable. `archive_url` is the tidy
corpus permalink (`.../schools/<slug>/<YYYY-YY>`), non-null in every committed
seed row but nullable in schema, so the code still handles null. The cost tool's
payload-level `source` string is **not** changed here.

### Latest cycle, resolved per table per college (D3)

`CdsAdmissionsDao` today offers only single-college, single-cycle reads that
take an explicit `sourceYear`. The seed mixes cycles — merit aid 191 rows at
2024 and 177 at 2025, factors 221/154, deadlines 464/567 — and a college can
hold a 2024 factor grid beside a 2025 merit row, because S4a picks the newest
document that actually reports each fact group. Any hardcoded year would
silently drop roughly half the corpus. So the DAO gains three batch reads —
`listLatestMeritAid`, `listLatestAdmissionFactors`, `listLatestDeadlines` — each
taking a `List<CollegeId>` and using `DISTINCT ON` over `source_year DESC` to
resolve the latest cycle **per table per college** (deadlines additionally keyed
by `round`). The reads take a `Collection<CollegeId>`, not a `List`: the caller
already holds a set-like selection and nothing in the SQL depends on order. The
existing single-cycle methods stay: they are the right API for an audit or a
backfill. No year is written down anywhere in `:service`.

The table name and the `DISTINCT ON` keys reach SQL as text, so the permitted
set is a private `LatestSource` enum on the DAO rather than three call-site
string literals: a table or key outside the set is unrepresentable, not merely
unused. The keys ride as a `List<String>`, and the helper writes both the
`DISTINCT ON` clause and the leftmost `ORDER BY` terms from that one list —
Postgres requires them to agree, and a caller should not have to remember it.

**A deadline section renders ONE cycle.** The DAO resolves the latest cycle per
`round`, so a school that dropped a round between cycles could hand the service
a 2025 Early Decision row beside a 2024 Regular Decision row. The service takes
the newest cycle present and renders only the rounds from it. The reason is D2:
a section carries **one** citation, so mixing cycles under it would make that
citation name a document that did not produce every round beneath it — the
citation would be false for at least one round. A round only an older document
mentions is not claimed as current. The breadth cost is nil in practice: 0 of
the 316 seeded colleges hold rounds from two cycles.

The wire keeps one cycle; the LOG records what that cost. A dropped round reads
to a coach exactly like "this school does not run ED1", which is the opposite of
the truth, so the service logs the college, the rounds and the cycle it filtered
out. The same rule is applied to the other two silent drops in this slice: an
`offered = false` row that carries dates logs the college, the round and the
suppressed dates, and a cited row that yields no fact logs its college, cycle
and source URL. The honesty guarantee stays in the TYPE and the audit trail goes
in the LOG, rather than one being traded for the other.

### Deadlines: a flag is a fact (D5)

`offered` is `NOT NULL` and is the reliable bit in this corpus — 732 true and
**299 false** of 1031 rows. A false row is a reported fact and renders as one:
"this school does not offer Early Decision 2". Rendering it as silence would
convert a school's clear statement into our missing data.

Dates are the opposite: `closing_month` is NULL in 617 rows (60%),
`notification_month` in 846 (82%), and dates are cycle-relative with no year. So
month and day render as a phrase, "January 15" (412 rows); a month with no day
renders as "January, day not reported" — legal CDS reporting, rare here (2
rows), never completed with a guessed day; an offered round with no date carries
the flag alone. There is a **fourth case**: a round with `offered = false` that
nevertheless carries a date renders the flag and **not** the date. That rule is
carried by the TYPE rather than by a runtime check at the renderer:
`DeadlineRound` is a sealed pair of `Offered` (which alone holds the two dates)
and `NotOffered` (which holds none), so "a date under a round the school does
not run" is unrepresentable rather than filtered out, and the tool renders the
two cases exhaustively. The flag is what the school reported about running the
round; a date under a round it says it does not offer is a contradiction, and
rendering it would state a deadline the school never set. The seed says the flag
is the trustworthy half: all 10 affected rows are `round = regular` — a round no
US college truly fails to offer — so `offered = false` there is a source-parsing
artifact rather than a statement, and 8 of the 10 carry only a notification
month, not a closing date at all. A `{"month": 1, "day": 15}` object is never
emitted: two bare numbers would trip the RFC 143 guard, and rightly, because
that is raw source shape rather than a date a coach can say. The round rides as
`ApplicationRound.value` (a string, so guard-safe) beside a spoken
`ApplicationRound.label` ("Early Decision 2"), emitted together from one
construct so no call site can put the code in the model's context without the
words.

### Factors: omitted is not "not considered" (D6)

The C7 grid has an explicit `not_considered` rating, and NULL means the school
did not report that row. These are different facts and the corpus keeps them
apart: `interview` is NULL in 20.8% of grids and `applicant_interest` in 14.4%,
while `rigor` is NULL in only 2.7%. Rendering NULL as "not considered" would
manufacture an admissions statement for one school in five on the softest,
most-asked-about factors. **NULL factors are omitted entirely.**

Each reported row emits the factor code, the factor's spoken label and the
rating's spoken label — `factor`, `factor_label` and a `"very important"`
importance string, never a bare `very_important` — from **one** construct, so a
renderer cannot put a code in front of a family without the words beside it.
That is exactly RFC 142's rule and exactly the treatment the rounds get in D5
(`ApplicationRound.value` beside `ApplicationRound.label`). The labels live on
the enums themselves (`AdmissionFactor.label`, `FactorRating.label`, each beside
the existing `value`), which is the one home for them.

`AdmissionFactor` is a new model enum in `:db`: the 18 C7 rows in grid order,
each pairing the stored column name with its spoken label and the accessors that
read its rating off a stored `CollegeAdmissionFactors` row and off a
`NewCollegeAdmissionFactors` row about to be written. It carries **no**
`fromValue` parser — nothing accepts a factor code back as input, so a parser
would be speculative API.

It is also the **one** statement of that column list: `CdsAdmissionsDao`'s
`FACTOR_COLUMNS` bind table is derived from `AdmissionFactor.entries` rather
than re-listing the same eighteen names, and a `:db` test pins the enum against
the columns the migration actually created. A nineteenth factor added to only
one of the three would otherwise compile clean and be ingested but never
rendered, or rendered but never bound — silently, in both directions.

**A row with no facts is unreported, not a citation with nothing under it.** A
`merit_aid` row with neither merit measure (see D4) or an `admission_factors`
row with no answered row is dropped, and the section is named in
`data_availability` instead. A citation is a claim that the document says
something; with no facts beneath it, it says nothing.

Each section's factory owns that rule and RETURNS NULL — `MeritPractice.from`,
`AdmissionFactorGrid.from`, with each type's `isEmpty` private — so the two
tools that must agree about a school's silence cannot each keep their own copy
of the test. `CollegeAdmissions.notReported` is then **derived** from the three
nullable sections rather than passed in beside them: "did this school report
this" is one fact with one home, so a section can never be absent from the
payload yet missing from `data_availability`.

### The merit feed into `college_cost_profile` (D7, D12)

`CollegeCost` gains a nullable `meritAid: MeritPractice?`, set in `costOf`, and
`CollegeCostChatTool.collegeObject` gains an optional `merit_aid` sub-object,
present only when a row exists, carrying the same D4 share rules and its own D2
citation — merit is not a Scorecard fact and must never fold into the payload's
Scorecard `source` string. The read happens inside the **same**
`database.withConnection` block as the existing cost read, batched through
`listLatestMeritAid` over the already selected ids: one extra query for the
whole answer, no N+1, no new transaction.

The feed is **purely additive** (D12, value before ask): a college with no merit
row produces exactly the cost answer it produces today, and no merit sentence
requires the money profile. Concretely, `merit_aid` does not join `CostField`,
whose `data_availability` means "this college does not report this **Scorecard**
cost field"; mixing a second source's silence into that list would misattribute
which source is quiet. Merit silence is reported by the admissions tool, which
owns that source.

### Money language governs the vocabulary (RFCs 141, 142, 143, 145)

Everything user-visible this slice ships — the tool description, the labels on
the wire, the prompt paragraph — obeys the money glossary. It says **a financial
aid offer, never "an award"**; the merit label reads "average non-need (merit)
aid" and says plainly that grants and scholarships are money they never pay
back. It **never subtracts merit aid from a published price**: a share and an
average are not an offer to this student, so the tool states them and stops, and
RFC 141's rule against netting money out of a price binds harder here because
neither figure is even a promise to a person. It **never names a source's
internal buckets, codes or field names** (RFC 142) — no `H.2A01`, no `C.701`, no
`cds_source_year` — and where a code exists the human phrase is emitted beside
it from one construct (`MeritAidWire.shareLabel` under `MeritAidWire.objectOf`,
and the round- and factor-label emitters), so no call site can leave a code in
the model's context without the words it should say instead. And **ordering is a
product decision on the wire** (RFC 145): sections are emitted in the order the
coach should reach for them, `merit_aid`, then `admission_factors`, then
`deadlines`.

### Coach prompt v8 (D8)

`system_prompts` is insert-only, so a copy change is a new seed row, never an
edit of `db/schema/0053`. Migration `0058.seed-coach-system-prompt-v8.sql` seeds
a body that is v7 **byte-identical as a prefix**, joined by a single space to
exactly one appended paragraph — the additive shape (v2->v3, v3->v4), correct
here because nothing in the money or college-list paragraphs changes meaning,
and it preserves RFC 142's source-jargon sentence verbatim at its interior
position for free.

The appended paragraph names `college_admissions_profile`, says when to reach
for it, carries the D4 denominator sentence in full, and states the two silences
plainly: a field in `data_availability` means the school does not report it, and
a `false` round flag is the school saying it does not offer that round.
`service.conf` pins `systemPromptVersion = "v8"` with the rollback knob
documented (`COACHING_SYSTEM_PROMPT_VERSION=v7`), and `SystemPromptCatalogTest`
gains v8's structural contract. **Numbering is re-checked immediately before
commit:** brief 0003's M2 also plans a prompt version and several RFC branches
are in flight, so if the number or the version is claimed by a sibling worktree
first, this moves with no other change. That happened: `0057` was taken by the
`unit_id` -> `ipeds_unit_id` rename that landed on `main` first, so this seed is
`0058`; `v8` was unclaimed and stands.

### The RFC 143 guard moves to test fixtures (D9)

RFC 143 wrote the "no bare source code reaches a tool result" guard twice and
said explicitly that a **third** tool is the trigger to unify it. This is the
third tool. The guard moves to a new `java-test-fixtures` source set on `:chat`
— `chat/src/testFixtures/kotlin/ed/unicoach/chat/BareSourceCodeGuard.kt` — which
both `:service` and `:college` already depend on for `ChatTool`, following the
`testFixtures(project(":appstore"))` precedent. `bareSourceCodes` takes the
allowlist as a parameter, since the two existing copies have already diverged on
exactly that point: the cost copy derives from `CostField.entries`, the search
copy names its eight fields. Each tool's test keeps its own allowlist and its
own exact-list positive control; only the walker, the `qN` regex and the `NPT4`
check are shared.

`bareSourceCodes` returns a `BareSourceCode` ADT — `QuintileToken`,
`Npt4ColumnFamily`, `BareNumberField` — with one `describe` renderer for the
assertion message. The guard is RFC 143's enforcement point and is now read by
three tools with a fourth due; three positive controls asserting on literal
English would make the sharpest check in the suite brittle exactly where it must
stay sharp. The three exact-list positive controls are unchanged in what they
prove: they now name kinds and fields instead of sentences. The new tool
contributes `AdmissionsField.entries` plus its numeric measure keys. Everything
else it emits is a string by construction — spoken ratings, spoken round labels,
date phrases, the cycle label — which is the point of the rendering rules above
rather than a coincidence.

The same rule applies inside `:service`'s own test source set: the costs and
admissions suites shared connection/session plumbing, a statement-counting
session, the student and college-list seeders, and — the one that matters — a
seeder for the SAME `college_merit_aid` table both tools read. Those live once,
in `ed.unicoach.coaching.CoachingTestDb`; `CostsTestDb` and `AdmissionsTestDb`
keep only their own domain seeders and their own truncation list. One module, so
no `java-test-fixtures` source set is needed for it.

### RFC 139 provenance: CDS enters the build row (D10)

RFC 140 left `db/seed/cds/PROVENANCE.json` as the CDS provenance of record,
because RFC 139 landed only at rebase time. The result is a real hole:
`CollegeScorecardLoader.ingest` writes the `college_index_build` row in its
`"provenance"` phase and returns, and only then does `IngestApplication.main`
run `CdsSeedLoader.load`. By construction the build row can never mention CDS,
and `PROVENANCE.json` is a fetcher artifact no Kotlin code reads.

The fix is ordering plus payload. `CdsSeedLoader.load` runs **before** the
provenance phase in the same ingest run; its three source files are digested
into `sources` the way IPEDS's are; its three `TableSummary` counts join
`rowsIngestedJson`; and `METHOD_VERSION` bumps to 4 (RFC 146 took 3). The CDS
load keeps its own single transaction — a fatal still rolls all three tables
back as a unit — and a failed load still writes no build row, so 0052's "one row
per successful run, at the very end" contract holds. `college_index_build`
becomes the provenance of record; `PROVENANCE.json` is what it always was,
fetcher audit output.

Two small rewrites in `IngestApplication` are part of this and are intended, not
drift. The CLI's source list becomes one `namedSources` construct, so the three
CDS files are digested and named exactly the way the Scorecard and alias files
already are rather than by a fourth hand-written spelling; and the run log gains
`logCdsRun`, one function that prints the three table summaries by iterating
`LoadResult.tableSummaries` instead of repeating the same three lines. The role
each CDS file fills is named on `CdsSources.namedFiles`, where the field and its
role are both known, rather than computed with `removePrefix("--")` and zipped
positionally against a flag list in another file — the same silent-drop `zip`
this section rejects for the summaries.

`ingest`'s provenance step is `phase("provenance") { insertBuildRow(...) }`, at
the same altitude as `aliases`, `cds` and `name-words`, rather than a
thirty-line row literal spelled out in the run's top-level flow. Both exist
because the CDS phase would otherwise have duplicated code that already had one
home.

**The summaries are keyed by their table, not by position.**
`LoadResult.tableSummaries` returns `List<Pair<Table, TableSummary>>`, and
`CdsSeedLoader.Table` carries every name anything downstream calls it by: the
defect `label`, the `rows_ingested.cds` `wireKey`, and the run log's `logLabel`.
The alternative — a positional list of summaries zipped against separate literal
label lists in the loader and the CLI — mislabels the whole provenance row if
the order ever changes, and `zip` would silently DROP a fourth table rather than
fail. It would be the build row, the thing this section exists to make
trustworthy, that lied.

**All ten headers are asserted before phase one.** `CdsSeedLoader.assertHeaders`
is public and is called in `ingest` beside the IPEDS assertion, so a renamed
column in the third seed file cannot be discovered only after institutions,
fields, aliases and the two IPEDS phases have each committed — the half-written
snapshot the up-front check exists to prevent. `load` keeps its own assertion:
it is the loader's contract, and `load` is also callable on its own. The
assertion takes the `SourceFile`, not a bare `File`, so a `HeaderMismatch`
reports `(path, sourceArg, missing)` like the other seven files of the same
up-front check — `bin/ingest-colleges` downloads an `s3://` source into a temp
dir, and a basename names a file the operator never typed. `main` gives the
defect its own typed handler beside the sibling aborts, so the message states
the write state ("before any write") that every other abort states. The loader
itself is injected into `CollegeScorecardLoader` (the `ioDispatcher` precedent
in the same constructor) rather than constructed mid-`ingest`.

**Verified on the committed seed.** The real ingest ran on this branch
(`bin/ingest-colleges -m/-a/-d`) and wrote exactly one `college_index_build`
row, `method_version` 4, whose `sources` array carries all six files with
independent `sha256` digests and whose `rows_ingested.cds` reads: `merit_aid`
366 upserted / 2 skipped, `admission_factors` 374 / 1, `deadlines` 1022 / 9.
Every table's upserted + skipped equals its seed size — 368, 375 and 1031 — so
no row was silently dropped. The skips are two IPEDS unit ids the colleges table
does not hold, 229407 and 231970, and they are recorded in each table's
`unmatched_ipeds_unit_ids` rather than lost: a seed row for a school we do not
index is reported, never inferred away.

## Files Modified

| File                                                                                                                                                    | Change                                                                                                                                                             |
| ------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `service/src/main/kotlin/ed/unicoach/coaching/admissions/CollegeAdmissionsChatTool.kt`                                                                  | NEW — the tool: definition, input parse, JSON render, citation and date/label copy                                                                                 |
| `service/src/main/kotlin/ed/unicoach/coaching/admissions/CollegeAdmissionsService.kt`, `AdmissionsField.kt`                                             | NEW — chat-free composition (list x CDS reads, the D4 share rule, the domain data classes) and the wire vocabulary shared by the JSON keys and `data_availability` |
| `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt`                                                                                           | one appended `ToolRegistry` entry; nothing else in the loop changes                                                                                                |
| `db/src/main/kotlin/ed/unicoach/db/dao/CdsAdmissionsDao.kt`                                                                                             | `listLatestMeritAid` / `listLatestAdmissionFactors` / `listLatestDeadlines`, `DISTINCT ON` per table                                                               |
| `db/src/main/kotlin/ed/unicoach/db/models/FactorRating.kt`, `ApplicationRound.kt`                                                                       | a `label` beside `value` — the one home for the spoken rating and round copy                                                                                       |
| `db/src/main/kotlin/ed/unicoach/db/models/AdmissionFactor.kt`                                                                                           | NEW — the 18 C7 rows in grid order, each pairing the stored column name with its spoken label and its rating accessor (D6)                                         |
| `service/src/main/kotlin/ed/unicoach/coaching/costs/CollegeCostService.kt`                                                                              | `CollegeCost.meritAid`; the batched merit read inside the existing `withConnection`                                                                                |
| `service/src/main/kotlin/ed/unicoach/coaching/costs/CollegeCostChatTool.kt`                                                                             | the optional `merit_aid` sub-object with its own citation; the denominator rule in `DESCRIPTION`                                                                   |
| `service/src/main/kotlin/ed/unicoach/coaching/admissions/MeritAidWire.kt`                                                                               | NEW — the merit sub-object's wire keys, `shareLabel` and `objectOf`, so both tools render merit from one home                                                      |
| `common/src/main/kotlin/ed/unicoach/common/util/Share.kt`                                                                                               | NEW — the ratio -> percent conversion, the one-decimal rule and the spoken percent in one type (the `DataSize` precedent); the wire number is unchanged (D4)       |
| `common/src/main/kotlin/ed/unicoach/common/money/WholeDollars.kt`                                                                                       | NEW — the one `"$%,d"` spoken form of a source-reported whole-dollar figure, beside `Nanodollars` in `common/money`                                                |
| `service/src/main/kotlin/ed/unicoach/coaching/admissions/CdsCitation.kt`                                                                                | NEW — the per-section citation and its `putCitation` renderer in one home; `citedAs` computed from college name + `source_year` (D2)                               |
| `service/src/main/kotlin/ed/unicoach/coaching/StudentCollegeSelection.kt`                                                                               | NEW — the active-list read, subset split, batched `listByIds` and list-entry invariant, shared by the cost and admissions services                                 |
| `service/src/main/kotlin/ed/unicoach/coaching/StudentScopedChatTool.kt`                                                                                 | the `college_ids` schema, cap and parse for the whole tool family — one home, not a copy per tool                                                                  |
| `service/src/test/kotlin/ed/unicoach/coaching/CoachingTestDb.kt`                                                                                        | NEW — the DB plumbing, statement counter and shared seeders the costs and admissions suites both use                                                               |
| `db/schema/0058.seed-coach-system-prompt-v8.sql`, `service/src/main/resources/service.conf`                                                             | NEW — v7 body byte-identical plus one appended admissions paragraph; pin `systemPromptVersion = "v8"` (rollback knob: `v7`)                                        |
| `college/src/main/kotlin/ed/unicoach/college/IngestApplication.kt`                                                                                      | run the CDS load before the provenance phase; pass its result into the payload builders                                                                            |
| `college/src/main/kotlin/ed/unicoach/college/CollegeScorecardLoader.kt`                                                                                 | CDS sources in `sourcesJson`, CDS counts in `rowsIngestedJson`, `METHOD_VERSION` -> 4                                                                              |
| `chat/build.gradle.kts`, `chat/src/testFixtures/kotlin/ed/unicoach/chat/BareSourceCodeGuard.kt`                                                         | apply `java-test-fixtures`; NEW — the RFC 143 guard, allowlist parameterised (D9)                                                                                  |
| `service/build.gradle.kts`, `college/build.gradle.kts`                                                                                                  | `testImplementation(testFixtures(project(":chat")))`                                                                                                               |
| `service/src/test/kotlin/ed/unicoach/coaching/admissions/CollegeAdmissionsChatToolTest.kt`                                                              | NEW — the tool's tests, including its guard copy and exact-list positive control                                                                                   |
| `service/src/test/kotlin/ed/unicoach/coaching/admissions/CollegeAdmissionsServiceTest.kt`, `AdmissionsTestDb.kt`                                        | NEW — the share, latest-cycle and missing-row rules, over a `CostsTestDb`-shaped fixture                                                                           |
| `service/src/test/kotlin/ed/unicoach/coaching/admissions/MeritAidWireLocaleTest.kt`                                                                     | NEW — the spoken share, average and cycle label under a non-US default locale (D4)                                                                                 |
| `service/src/test/kotlin/ed/unicoach/coaching/costs/CollegeCostChatToolTest.kt`, `college/src/test/kotlin/ed/unicoach/college/CollegeSearchToolTest.kt` | `merit_aid` cases; both guard copies now import the shared walker, behaviour unchanged                                                                             |
| `service/src/test/kotlin/ed/unicoach/coaching/costs/CollegeCostServiceTest.kt`, `CostsTestDb.kt`                                                        | the merit read and additive-degradation cases; `college_merit_aid` seeders and truncation                                                                          |
| `service/src/test/kotlin/ed/unicoach/coaching/SystemPromptCatalogTest.kt`, `CoachingConfigTest.kt`                                                      | v8's structural contract and markers; the packaged-defaults pin moves to `v8`                                                                                      |
| `college/src/test/kotlin/ed/unicoach/college/CdsSeedLoaderTest.kt`                                                                                      | CDS sources and counts land in the build row; ordering                                                                                                             |
| `product/0001-v1-differentiator/brief.md`, `product/0001-v1-differentiator/spec.md`, `product/STATUS.md`                                                | ledger, the SPLIT IN DESIGN block, and the S4b kickoff prompt on landing                                                                                           |
| `rfc/148-admissions-intelligence-in-chat.md`                                                                                                            | this document                                                                                                                                                      |

## Implementation Plan

1. **DAO first.** The three `listLatest*` batch reads plus their tests, against
   fixtures that deliberately mix 2024 and 2025 per college and per table; then
   `FactorRating.label` / `ApplicationRound.label`, the spoken copy at its one
   home, before any renderer needs it.
2. **`CollegeAdmissionsService`** — selection splitting, the three reads, the D4
   share rule, `data_availability`, the citation composition; chat-free, and
   tested chat-free. Then **`CollegeAdmissionsChatTool`** — parse, render, the
   definition and its ethos contract — registered in `Application.kt`.
3. **The guard unification (D9)** — move the shared walker to `:chat` test
   fixtures, parameterise the allowlist, repoint both existing copies, prove
   they still fail on their doctored payloads.
4. **The cost feed** — `CollegeCost.meritAid`, the batched read in the existing
   `withConnection`, the `merit_aid` sub-object, the `DESCRIPTION` sentence.
5. **Prompt v8** — seed `0058` as v7 plus one appended paragraph; pin
   `service.conf`; extend `SystemPromptCatalogTest`. Re-check the migration
   number and the version against sibling worktrees immediately before commit.
6. **Provenance (D10)** — reorder the CDS load ahead of the provenance phase,
   thread sources and counts, bump `METHOD_VERSION`; re-run
   `bin/ingest-colleges -m/-a/-d` against the committed seed and capture the
   build row as a run artifact.
7. `nix develop -c bin/test` (full suite, no module scoping, no `-f`).

## Tests

**The tool.**

- **`the definition carries the ethos contract`** — the description names the
  Common Data Set, says "never estimate", and carries the denominator words.
- **`an absent college_ids reads the whole active list`** vs
  **`an empty array
  reads nothing`** — two different results, never folded
  together; ids outside the list come back in `unknown_college_ids` while known
  ids still answer.
- **`malformed input is a structured error, never a throw`** — one test over the
  three refusals (a non-uuid entry, more than fifty ids, an unknown input
  field), because they share a fixture and one shape of assertion; the error
  message names `MAX_COLLEGE_IDS` for the cap case.
- **`sections are emitted in the coach's reading order`** — asserted as a `List`
  of keys, because the order is the product decision (RFC 145).

**The honest denominator (D4).**

- **`the share is emitted only when both counts are present`** — a row with
  `freshmen_ft_total` NULL emits the average and no share, and does not divide.
- **`the share label names all full-time freshmen`** — asserted on the payload,
  and asserted absent of the phrase "without need".
- **`zero merit awards is a reported fact`** — the Amherst shape (480 / 0 / $0):
  the share renders as 0%, the average as $0, and neither key is omitted.
- **`a college with no merit row still appears`** — `merit_aid` absent from the
  object, `merit_aid` present in `data_availability`.
- **`a zero freshman total is silence, not a section of bare zeroes`** — the
  in-domain `0` denominator: no `merit_aid` section and `merit_aid` named in
  `data_availability`, so the fourth D4 state is decided and pinned rather than
  reachable and untested.
- **`the spoken share, average and cycle read the same under a non-US default
  locale`**
  — the JVM default FORMAT locale is set to de-DE for the length of the test:
  `5.2%`, `$12,500` and `2024-25` must not become `5,2%` and `$12.500`, which
  would disagree with the numbers the payload carries.
- **`a cancelled read is rethrown, never reported as a read failure`** — in the
  admissions service and in the cost service: the coroutine is started
  UNDISPATCHED and cancelled while the read is in flight, and the test asserts
  the `CancellationException` propagates and that no `Result` is produced.
- **`a row carrying only the freshman total is a silence`** — the 28-of-368
  corpus shape: no `merit_aid` section, and `merit_aid` named in
  `data_availability`. Asserted in the service, in the tool, and in the COST
  tool, because both tools read the same `MeritPractice` and must call the same
  row silence. Its complement —
  **`a single reported merit measure is still a
  section`** — keeps the rule
  from being tightened into dropping schools that do report merit aid.

**Deadlines (D5).**

- **`an offered round with month and day renders a date phrase`** — "January
  15", and no `month` or `day` key anywhere in the payload.
- **`a month with no day says the day is not reported`** — the literal "January,
  day not reported"; and an offered round with no date carries the flag alone.
- **`offered false is rendered as a reported fact`** — the school does not offer
  that round, distinct from the round being absent from the result entirely.
- **`rounds are rendered in ApplicationRound's declared order`** — seeded in
  reverse, so an order that merely echoed the writes would fail. The enum's
  declaration order is coach-facing (it is the order a family hears their
  deadlines in), which its own kdoc now states, and this pins it.

**Factors (D6).**

- **`a null factor is omitted, never rendered as not considered`** — the fixture
  carries both a NULL `interview` and an explicit `not_considered`
  `alumni_relation`, and the two render differently.
- **`ratings are spoken labels`** — "very important" on the wire, the raw
  `very_important` code absent from the payload, emitted beside `factor` and
  `factor_label` from the one construct D6 describes.

**Citation (D2).**

- **`each section carries its own citation`** — three sections, three `cited_as`
  strings, each naming that college and its own cycle; a null `archive_url`
  omits the key and the school's own url still cites.
- **`the cycle is spoken, never a bare year`** — "2024-25 Common Data Set" is
  present and no field carries a bare `2024`.
- **`the latest cycle is resolved per table`** — a college seeded with a 2024
  factor grid and a 2025 merit row cites 2024-25 under factors and 2025-26 under
  merit aid, in one result.

**The RFC 143 guard (D9).**

- **`no bare source code reaches a tool result`** — over a fixture that
  populates **every** optional field, so the allowlist is exercised rather than
  vacuously satisfied, plus the exact-list assertion that every
  `AdmissionsField` wire name is a key of the rendered college object.
- **the positive control** — a doctored payload asserting the exact reason list,
  including a planted bare `month` to prove the raw month/day shape would be
  caught — and **`the malformed-input error carries no source code`**, since an
  error envelope is a model-facing result too.
- Both existing copies (`CollegeCostChatToolTest`, `CollegeSearchToolTest`) pass
  unchanged in behaviour against the fixture-hosted walker, each keeping its own
  allowlist and its own positive control.

**The cost feed (D7, D12).**

- **`a cost answer with no merit row is unchanged`** — the college object's
  WHOLE key set is asserted against the pre-feed vocabulary (derived from
  `CostField`, so a future cost field costs no test edit), not just the absence
  of `merit_aid`, so a key the feed adds anywhere fails it; and
  **`a merit
  sentence never requires the money profile`** with the profile
  entirely unanswered: the feed is additive.
- **`merit_aid rides its own citation, not the Scorecard source string`** — the
  payload-level `source` still names the College Scorecard only.
- **`the merit read adds no query per college`** (and its admissions twin) —
  batching asserted as a **statement count**, not as a name: each service
  extracts an `internal readInSession(session, ...)` seam, the test drives it
  with a session that records every `prepareStatement`, and one college and five
  colleges must prepare the same number of statements. A per-college loop in
  either service fails here and nowhere else.

**Prompt and provenance.**

- **`coach v8 is v7 plus one appended admissions paragraph`** — byte-identical
  prefix, joined by a single space, naming `college_admissions_profile`; and
  **`coach v8 carries the honest denominator`** — it says all full-time
  freshmen, never "without need".
- **`coach v8 preserves the v7 source-jargon sentence verbatim`** — extracted
  from v7 at runtime, never retyped; plus the existing catalog contract, now
  over the v8 pin.
- **`a bad CDS header aborts the run before any phase commits`** — the CDS files
  are the last three of ten, so this proves the up-front assertion: the run
  fails with a `HeaderMismatch` and `colleges` is still empty, no phase having
  run. Its sibling, **`a failed CDS load writes no build row at all`**, now uses
  a ROW-level defect — the failure that can only be found mid-load — and still
  rolls all three CDS tables back as a unit.
- **`the schema columns, AdmissionFactor and the DAO bind table are one
  vocabulary`**
  (`:db`) — the enum pinned against the columns the migration really created and
  against `CdsAdmissionsDao.FACTOR_COLUMNS`, so a nineteenth factor cannot be
  half-added; plus
  **`every factor accessor reads its own
  column, in both directions`**, which
  catches a swapped pair of accessors that no name check can see.
- **`the build row records the CDS sources and row counts`** — after a run with
  `-m/-a/-d`, `sources` carries the three digests and `rows_ingested` the three
  table counts, at `method_version` 4; a Scorecard-only run still writes its
  build row, with the CDS keys absent rather than zero.
- **The real seed, not only fixtures.** The committed seed was ingested on this
  branch and the build row read back: one row, `method_version` 4, six digested
  sources, `merit_aid` 366 upserted / 2 skipped, `admission_factors` 374 / 1,
  `deadlines` 1022 / 9 — upserted + skipped equal to the seed sizes 368 / 375 /
  1031, with IPEDS unit ids 229407 and 231970 named in
  `unmatched_ipeds_unit_ids`.

## Deliberately not done here

- **Restructuring `college_cost_profile`'s payload-level `source`.** Per-fact
  citation is the right end state there too, but it changes a shipped contract
  for a different source, and a merit sub-object carrying its own does not need
  it.
- **Any net-price-calculator automation** (brief 0001 D2), and **admissions
  figures beyond the three tables** — acceptance rate, yield, test ranges are
  not in the S4a schema, so surfacing them means new columns and a D10 gate.
- **A ranking or fit score over factors.** The tool reports what each school
  says it weighs; scoring that is a product decision no one has made.
