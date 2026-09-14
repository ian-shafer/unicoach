# Repo feasibility: what unicoach already has for FAFSA support (brief 0012)

Code-grounding report for `product/0012-fafsa-support`. All paths are relative
to the repo root of the read-only worktree
`/Users/ian/Work/unicoach-docs-0012-fafsa` (a clean worktree of `main`, read
2026-09-14). Line numbers are from that tree.

## Method

I read the code directly, no web research (per task): the RFC 159 tool pair
(`service/src/main/kotlin/ed/unicoach/coaching/aid/FederalAidPolicyChatTool.kt`,
`FederalAidPolicyService.kt`), migrations `db/schema/0077`, `0078`, `0083`,
`0087`, `db/schema/create-admission-test-calendar.sql` and the v35 prompt seed;
`db/data/money-vocabulary.json`; the committed seed `db/seed/cds/aid_policy.csv`
(counted with awk); `prompts/coach-system-prompt.md` (grepped for
FAFSA/SAI/Pell/loan/dependency, quoted verbatim); the money-profile model and
tool files; the whole `public-web/src/main/kotlin/ed/unicoach/web/render/`
directory, `Routing.kt` and `LegalPagesTest.kt`; `bin/test-calendar-seed`,
`AdmissionTestAdministration.kt`, `AdmissionTestCalendarSeedTest.kt`,
`TestTimelineService.kt`; `rfc/159`, `rfc/170`, `rfc/197` where cited; and
`product/STATUS.md` + `product/0012-fafsa-support/brief.md`.

Honest limits: **brief 0011's own documents are not in this worktree** —
`product/` contains 0001-0010 and 0012 only, so
`product/0011-beyond-money/spec.md` (with D13/D14 and the `beyond/09` slice
text) could not be read; everything I say about brief 0011 comes from its
STATUS.md row and is marked accordingly. I did not run Gradle, tests or a
database; every count is a static read of committed files. No tool was
unavailable; the web was simply out of scope.

## 1. The `federal_aid_policy` tool (RFC 159)

**Correction to the brief first.** Brief 0012's foundations table says the tool
reads "`aid_policy_facts` table (schema 0083)". It does not. The tool reads
**`policy_parameters`** (created and seeded in
`db/schema/0077.create-and-seed-policy-parameters.sql`, via
`PolicyParametersDao.listAll` — `FederalAidPolicyService.kt:178`). The 0083
`aid_policy_facts` table was a modeled-ahead guess (RFC 158 D9) and was
**DROPPED by schema 0087** ("aid_policy_facts is dropped, not extended (D2)",
`db/schema/0087.create-aid-form-requirements.sql:~300`). It no longer exists.

**What facts exist today, exactly.** `policy_parameters` is
`(award_year INTEGER, parameter TEXT, value INTEGER, source_name, source_url)`,
PK `(award_year, parameter)`, with a closed CHECK of **24 parameter slugs**
(`0077:44-70`) mirrored by the Kotlin `PolicyParameter` enum:

- 5 Pell/SAI parameters: `pell_max_award_usd`, `pell_min_award_usd`,
  `pell_max_agi_dependent_single_parent_pct`,
  `pell_max_agi_dependent_non_single_parent_pct`, `sai_floor`.
- 19 Direct Loan parameters: the dependent/independent annual grid (y1/y2/y3+ x
  total/subsidized), grad annual unsubsidized, and six aggregates.

Seeded rows (`0077:97-185`): award year **2026** (2026-27) carries the five
Pell/SAI rows only — the 2026-27 loan rows are **deliberately absent** (Handbook
Vol 8 unpublished; OBBBA changes lending from July 1 2026, per the migration
header). Award year **2025** carries all 24. So at read time today Pell serves
as `current` and both loan topics as `prior_year`, which the RFC calls shipping
the mechanism exercised (`FederalAidPolicyService.kt` class doc).

**Citations.** Every row carries `source_name` (the SPOKEN document name, e.g.
"Federal Student Aid Dear Colleague Letter GEN-26-01") and `source_url` (the
primary fsapartners.ed.gov page). The service groups them per distinct document
(`sourcesOf`, `.distinct()`) and the tool renders a `sources` array of
`{cited_as, url}` objects per topic — the CdsCitation shape
(`FederalAidPolicyChatTool.kt:putSources`).

**Award-year resolution and staleness.** `currentAwardYear()` owns the July 1
rule: before July the running award year started last calendar year
(`FederalAidPolicyService.kt:~200`). Per topic, `latestServedYear` picks the
greatest award year **at or before** current that carries **every** parameter of
the group; a future-published year is never served, a partially seeded year
never eclipses the last complete one, and a group with no servable year resolves
to `null` (topic key absent from the payload, with an operator-facing WARN
naming which of three absences it is). When the served year trails the current
one, the topic carries `award_year_status: "prior_year"` plus a rendered
sentence ("These are the 2025-26 award year figures; Federal Student Aid has not
yet published figures we have verified for the 2026-27 award year"). **There is
no expiry and no build failure for staleness**: a prior year is served forever,
labeled — the opposite discipline from RFC 197's calendar, where a stale row
fails CI. Corrections land as a NEW migration upserting on the natural key,
never an edit (`0077` header).

**Could FAFSA-process facts extend this store?** The shape says no, mostly:
`value` is a single `INTEGER NOT NULL` with a CHECK that only `sai_floor` may be
negative — it holds whole dollars, percent points, or an index. There is **no
prose value column and no date column**. Prose the family reads (framing
statements, the prior-year sentence, the max-Pell AGI gloss) is deliberately
**Kotlin consts at the tool edge**, not data (`FederalAidPolicyChatTool.kt`
companion: `PELL_FRAMING_STATEMENT`, `LOAN_FRAMING_STATEMENT`, `SOURCE_NOTE`).
So:

- A FAFSA fact that IS a number for an award year (nothing obvious today) would
  fit as new parameter slugs — one CHECK widening + enum members + a group.
- FAFSA **process prose** ("who files", "what happens after submission",
  dependency rules in words) does NOT fit `policy_parameters`. Its two native
  homes in this codebase are (a) prompt sentences in a new seeded prompt
  version, and (b) tool-edge consts served beside data — both citation-carrying
  only if the copy embeds the citation. A cited prose-fact table would be a NEW
  table (the 0077 precedent gives the shape: natural key + per-row
  source_name/source_url + authored-in-migration seed), not an extension.
- FAFSA **dates** (open date, deadlines) fit neither: `value INTEGER` cannot
  hold a date honestly, and RFC 197's calendar shape (section 6) is the
  established home for dated rows with expiry.

## 2. `aid_form_requirements` (RFC 170, schema 0087)

**Exact columns** (`0087:~245-270`): `id UUID PK`, `college_id -> colleges`,
`aid_form TEXT -> aid_forms(slug)`, `applicant_group TEXT` CHECK in
(`domestic_first_year_aid_applicants`, `nonresident_first_year_aid_applicants`),
`academic_year academic_year` (a SMALLINT domain, start-year convention: 2025 IS
2025-26), `is_required BOOLEAN NULL`, `status TEXT -> figure_statuses(slug)`,
`source money_source`, `source_variable TEXT` (the raw CDS field id,
H.801-H.807), `source_document_id UUID NOT NULL -> source_documents(id)`,
timestamps. Natural key
`(college_id, aid_form, applicant_group, academic_year)`. Value-iff-status
CHECK; `is_required` is **never FALSE today** — no source publishes a negative
(D5), enforced by the loader, not a CHECK. Citations go through the
`source_documents` entity (one row per college x publisher x year, with
`source_url` + optional `archive_url`) rather than per-fact URL copies (D13).

The form vocabulary is the 7-member `AidForm` enum
(`db/src/main/kotlin/ed/unicoach/db/models/AidForm.kt`) pinned both ways to
`aid_forms` rows authored in `db/data/money-vocabulary.json`: `fafsa`,
`css_profile`, `noncustodial_css_profile`, `institutional_form`,
`state_aid_form`, `business_farm_supplement`, `other_institutional_form`.

**Coverage — three numbers in the tree disagree, so here is what each is:**

- RFC 170 (`rfc/170-need-and-forms.md:292-294`), the seed-floor spec: "FAFSA
  278, CSS Profile 74, noncustodial 47 — of 417 seeded colleges".
- `product/STATUS.md:1687-1690` (and brief 0012): "338 of the 417 seeded
  colleges carry a filing ... FAFSA for 272, and the CSS Profile answer exists
  for only 73".
- **Measured by me on today's committed seed** (`db/seed/cds/aid_policy.csv`,
  awk over the `fact` column): `fafsa_required` rows for **219 distinct
  colleges** (100 rows keyed source_year 2024, 119 keyed 2025; 216 of the 219
  with status `reported`), `css_profile_required` 59, `state_aid_form_required`
  36, `noncustodial_css_profile_required` 34, across **366 distinct colleges**
  in that seed file. I could not reconcile 272 vs 219 from the static files
  alone — the documented numbers predate later corpus refreshes, and the
  per-year split means a college's FAFSA answer may exist for one year only. The
  safe sentence for the spec is "roughly 55-65% of CDS-filed schools have a
  FAFSA answer; a missing school reads as _not listed in this school's CDS_,
  never _not required_". The exact live count is UNVERIFIED without a DB run.

**How `college_cost_profile` renders it**
(`service/src/main/kotlin/ed/unicoach/coaching/costs/AidPolicyWire.kt`): the
`aid_policy` section emits `required_forms` as an array of form slugs (only
forms the CDS lists), `forms_note` — one sentence covering every unlisted form —
and a separate `forms_we_could_not_read` + note for `not_collected_by_us` cells
(OUR extraction gap, said as ours, D7). There is deliberately **no "not
required" key**. The prompt (line 194, quoted below) tells the coach to name the
forms and add the FAFSA sentence.

## 3. Coach prompt v35: every FAFSA-adjacent sentence, verbatim

`prompts/coach-system-prompt.md` is 223 lines; the served row is seeded by
`db/schema/seed-coach-system-prompt-v35.sql` (rollback is
`COACHING_SYSTEM_PROMPT_VERSION=v34` etc., rows immutable). Every line touching
FAFSA, SAI, Pell, loans or dependency (grep on 2026-09-14):

- **:19** "Every time you mention a loan, use the word loan and say it is money
  paid back with interest — and never subtract loans or work-study from a price,
  because they do not make a school cheaper, they change who pays and when."
- **:105** "Never call a loan aid that lowers a price, and never subtract loans
  from a price."
- **:124** "...say that a loan is money paid back with interest every time you
  name one." (inside the say-it-once licence paragraph)
- **:160** "When a family asks about the Pell Grant or federal student loans,
  answer from the federal_aid_policy tool rather than from memory: it returns
  the federal policy parameters — the Pell Grant maximum and minimum, the
  Student Aid Index floor, the income tests for a maximum Pell Grant, and the
  Direct Loan annual and aggregate limits — with the award year and the Federal
  Student Aid source each figure comes from, and they are the same at every
  college."
- **:162** "Pell is an eligibility and a range, never a promised amount:
  describe who qualifies and what the range is, and never promise a family a
  specific Pell amount."
- **:163** "A loan limit is a cap on borrowing, not aid that lowers a price: say
  every loan as money paid back with interest, and never subtract a loan or a
  loan limit from any price."
- **:165** "Whether a student is dependent or independent for federal aid picks
  which loan limits and which Pell income test apply: when that answer is not on
  file, you may ask it once, saying what it unlocks — most students applying
  straight from high school are dependent — and if the family would rather not
  say, answer fully anyway with both sets of figures and never raise it again
  yourself."
- **:166** "Reach for that same tool when a family asks how much they are
  allowed to borrow, or about what the FAFSA works out for them — the Student
  Aid Index — rather than answering either from memory."
- **:194** "required_forms lists the aid forms that school's filing says are
  required of first-year applicants -- name them, and say that the FAFSA is the
  federal form nearly every student who applies for aid files."
- **:201-202** "Each kind of loan is its own figure: give the federal one and
  the private one separately, and say plainly when the private figure is not in
  that school's filing rather than letting the federal figure stand as the whole
  story." / "Never add two kinds of loan together, and never present one of them
  as a total."

**Collision surface for a FAFSA-support prompt change.** Lines 160/166 already
own the routing for FAFSA-adjacent questions ("what the FAFSA works out for them
— the Student Aid Index"); a new FAFSA-process capability must either widen
these sentences or add a sibling routing sentence, and both live in the
paragraph RFC 199 moved rules INTO (the prompt is now the one home for
behavioural rules; tool descriptions are schema legends — STATUS.md, RFC 199
row). Line 165's ask-once dependency rule is exactly the pattern a "have you
filed the FAFSA" ask would imitate — and would sit beside. Many prompt sentences
are pinned byte-for-byte against the SERVED body in `SystemPromptCatalogTest`
(RFC 199/202 rows in STATUS.md), so edits mint a new seed migration (v36) plus
catalog-test updates, never an in-place edit.

**Scheduling.** The one-prompt-editor-at-a-time rule: I could not read brief
0011's own text (not in this worktree — UNVERIFIED as that brief's wording), but
the rule is live precedent in `product/STATUS.md:260-269`: brief 0010's wave 2
was "HELD by cross-brief agreement" against brief 0009's `concise/03` deletion
pass on the same prompt file, "a deletion rebased across additions can silently
drop a just-landed sentence and no test catches it", and RFC 192 D7 records the
collision arriving through a tool DESCRIPTION anyway. Brief 0011 (gates
approved, **nothing landed**, nine slices incl. prompt-touching ones) must be
assumed to claim the prompt file; any FAFSA prompt slice needs explicit
serialisation with it at gate time.

## 4. Money profile: fields today, and where a FAFSA status would ride

`money_profiles` (schema 0046, + 0070 living plan, + 0078 dependency) is one
versioned row per student with **four tri-state fields**
(`db/src/main/kotlin/ed/unicoach/db/models/MoneyProfile.kt`): `income_band`,
`residency_state`, `living_plan`, `dependency` — each a nullable value column
plus a status column CHECKed to `('unanswered','answered','declined')` with a
value-iff-answered CHECK (`0078:29-38` is the worked example:
`dependency TEXT NULL` +
`dependency_status TEXT NOT NULL DEFAULT 'unanswered'`). OCC `version`,
soft-delete `deleted_at`. The chat tool is `update_money_profile`
(`service/src/main/kotlin/ed/unicoach/coaching/MoneyProfileChatTool.kt`): every
field optional, a value key + a decline key per field, decline must be `true`
when present, value+decline in one call is an error, omission means "leave it
alone". The federal tool then echoes the answer tri-state
(`money_profile.dependency_status` in its payload) and the prompt (line 165)
owns the ask-once ethos.

**A `fafsa_status` field rides this mechanism cleanly** (the RFC 134 pattern,
extended exactly as RFC 159 extended it with `dependency`): one migration adding
`fafsa_status`/`fafsa_status_status`-style column pair (0078 shows how to dodge
the stutter: name the value column for the fact, e.g. `fafsa_filing` +
`fafsa_filing_status`), one Kotlin enum with `fromValue`, DAO + model +
`MoneyProfileEdit`/`NewMoneyProfile` fields, tool field pair, REST/admin
response models, and a prompt sentence saying what the answer unlocks. All
mechanical, all precedented. The brief's own D12 caution stands: the value the
ask unlocks must exist first (sequencing after C1/C2), and the "never open a
conversation with money" sentence (RFC 202) governs when the coach may raise it.

## 5. public-web: adding a page, and what the first explainer must build

The whole pattern, from the tree:

- **Route**: one line in `public-web/src/main/kotlin/ed/unicoach/web/Routing.kt`
  (`:117-118`: `get("/terms") { call.respondTermsPage() }`,
  `get("/privacy") { call.respondPrivacyPage() }`).
- **Render**: one file in `.../web/render/`, a suspend
  `ApplicationCall.respondXxxPage()` building kotlinx.html inside
  `siteLayout("Title") { ... }` (`PrivacyPage.kt` is 55 lines: `section` +
  `h1`/`h2`/`p`). `Layout.kt` (79 lines) supplies the shared `site-header` nav
  and `site-footer` chrome; the copy is static but renders dynamically so it
  inherits the chrome.
- **Test**: `LegalPagesTest.kt` —
  `testApplication { application {
  testPublicWebModule() } }`, GET the path,
  assert 200 + text/html, assert a body marker string ("proves this body
  rendered") and both `class="site-header"` / `class="site-footer"` ("proves it
  rendered through Layout").

**Confirmed: `beyond/09` has NOT landed.** `render/` contains exactly
`CostReportPage.kt, ErrorPages.kt, HomePage.kt, Layout.kt, PrivacyPage.kt,
TermsPage.kt, VerifyEmailPage.kt`
(+ `INVARIANTS.md`) — no who-pays page, and `grep -ri "who.pays" public-web/` is
empty. STATUS.md's brief-0011 row says "GATES 1+2 APPROVED (2026-09-10/11);
NOTHING LANDED YET".

**What the first explainer page builds that the second inherits.** Nothing in
`public-web` today renders a _cited claim_: the legal pages have no source URLs,
no "as of" dates, no external links. So whichever of `beyond/09` (who pays) and
the FAFSA page lands first must build: (a) a citation/`as of` rendering helper
(claim + public URL + fetch date, per brief 0011 D14 as restated in brief 0012 —
D14's own text UNVERIFIED, spec not in this worktree); (b) the CSS for an
explainer/content section (only `legal` exists); (c) the decision whether the
page joins the header nav or footer; (d) the test pattern extension asserting
citations render. The second page is then route + render-file + test — the
Privacy/Terms marginal cost, roughly a day-scale slice instead of a week-scale
one.

## 6. The RFC 197 seeded-calendar pattern (the template a FAFSA calendar copies)

From `db/schema/create-admission-test-calendar.sql`, `bin/test-calendar-seed`,
`AdmissionTestAdministration.kt`, `AdmissionTestCalendarSeedTest.kt`,
`TestTimelineService.kt`:

1. **Hand-typed CSV is the source** — no machine feed exists, so a human types
   rows from the publisher's page into
   `db/seed/test-calendar/administrations.csv` (16 lines = 15 rows + header)
   with a `PROVENANCE.json`, reviewable in a diff (brief 0010 D19).
2. **A generator, not an ingest**: `bin/test-calendar-seed` generates one seed
   migration per cycle from the CSV, so rows exist in every migrated database
   including the test DB (`bin/test` never ingests). It re-validates every DDL
   CHECK on the CSV first, reporting all faults in one pass with line numbers;
   `-n` fails (exit 3) if the committed SQL differs from what a run would
   generate, so a hand-edited or stale seed is caught. Never hand-edit the
   generated SQL; corrections are a new generated seed upserting on the natural
   key.
3. **Rows that cannot be typos**: CHECKs encode domain truths — registration
   closes before the test; a score-release date and its source URL are
   NULL-together (a citation with no fact is not data; an empty string is not a
   citation); the cycle label's second half must be first+1 mod 100.
4. **Every row expires**: `expires_on DATE NOT NULL`. Past expiry the coach
   **refuses to name a date** and points at the publisher —
   `TestTimelineService` models the calendar as a sealed either
   (live-calendar-with-`expiresOn` | located refusal), with no nullable
   `expired` flag that could sit stale beside a date.
5. **Staleness is a BUILD FAILURE** (brief 0010 D20):
   `AdmissionTestCalendarSeedTest` fails when any committed row still AHEAD of
   today is within
   `AdmissionTestAdministration.CALENDAR_REFRESH_LEAD =
   Period.ofDays(90)` of
   its `expires_on` (the constant is the one home for the number). Rows whose
   test date has passed are exempt, so the bar is never permanently unclearable.

A FAFSA-deadline calendar (federal open date, federal deadline, ~50 state
deadlines, each with `checked_on`/`expires_on`/source URL) copies every one of
these mechanisms; the marginal design work is the row shape (state axis; "as
early as possible / until funds run out" states that are words, not dates) and
the yearly ~50-row hand-typing bill the brief already flags.

## 7. Feasibility table (C1-C5 from `product/0012-fafsa-support/brief.md`)

| #  | Candidate                                                     | What exists                                                                                                                                                                                                      | What is missing                                                                                                                                                                                                                                                               | Size                                                                   |
| -- | ------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------- |
| C1 | Coach answers FAFSA process questions                         | `federal_aid_policy` tool + prompt routing (:160/:166) already carry Pell/loans/SAI with per-document citations; dependency echo; prompt-seed machinery (v36 is one migration)                                   | A home for **cited prose process facts** — `policy_parameters` is INTEGER-valued and `aid_policy_facts` is dropped, so either a new prose-fact table (0077 precedent) or prompt/tool-edge copy; a bounded fact vocabulary; serialisation with brief 0011's prompt edits       | **M** (prompt-only) to **L** (new fact store + tool topic)             |
| C2 | Public FAFSA explainer page                                   | Full page pattern: `Routing.kt` one-liner, `siteLayout` chrome, `LegalPagesTest` test shape; D14-style content rules exist as standing decisions                                                                 | The **cited-claim rendering layer** (source URL + "as of" per claim) exists nowhere in `public-web`; `beyond/09` has NOT landed, so no sibling to copy — first-mover builds the pattern; yearly-change-proof copy discipline                                                  | **M** (L-leaning if it lands before `beyond/09`; S-M if after)         |
| C3 | FAFSA calendar (open date, federal + state deadlines)         | The complete RFC 197 template: CSV + PROVENANCE + generator with mirrored CHECKs + expiry refusal + 90-day failing test; `bin/test-calendar-seed` as the worked example                                          | Everything FAFSA-specific: new table/DDL, new generator (or generalisation), ~50 hand-typed state rows/yr with citations, non-date deadline states ("until funds run out"), a coach door (tool or timeline extension), the yearly refresh commitment                          | **L**                                                                  |
| C4 | Per-school FAFSA answer ("does THIS school need it, by when") | The "needs it" half is SHIPPED: `aid_form_requirements` + `AidPolicyWire.required_forms` + prompt :194; coverage honest-by-construction (no-row = "not listed", never "not required")                            | The "by when" half: no FAFSA/aid **deadline** data anywhere (`college_deadlines` is CDS admission deadlines); priority-deadline data per school is thin and unverified; coverage is partial (measured 219 colleges in the seed vs 272 documented — reconcile before speccing) | **S** (surface the shipped half harder) / **L** (per-school deadlines) |
| C5 | FAFSA status in the money profile                             | The exact mechanism, four times over: tri-state column pair (0078 worked example), `update_money_profile` value+decline grammar, tool echo pattern, ask-once prompt ethos (:165), REST/admin plumbing precedents | The enum + migration + tool field + prompt sentence + response models (all mechanical); the D12 sequencing case for asking at all; what the coach DOES with the answer (depends on C1/C3 existing)                                                                            | **S-M**                                                                |

Cross-cutting: every candidate that touches `prompts/coach-system-prompt.md`
(C1, C4, C5, and C3's coach door) contends for the one-prompt-editor slot
against brief 0011's nine unlanded slices; that is a scheduling fact for gate 1,
not an implementation cost.
