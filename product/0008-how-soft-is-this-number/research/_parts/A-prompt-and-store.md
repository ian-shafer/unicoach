# Part A — the coach prompt v20 and what the money store already models

Read-only static analysis of the live tree at `/Users/ian/Work/unicoach`
(`.claude/worktrees/**` ignored). Every claim below carries a `file:line`. No
gradle, no tests, no writes outside this file.

---

## A1. The v20 seeded prompt: the hedge inventory

### A1.0 How the prompt is stored, and what that costs

The body is one SQL string concatenation inside a single `INSERT`
(`db/schema/0088.seed-coach-system-prompt-v20.sql:34-406`). The seeded row is
immutable and versioned:

> `db/schema/0088.seed-coach-system-prompt-v20.sql:32-33`
>
> ```
> -- Rollback is COACHING_SYSTEM_PROMPT_VERSION=v19 -- the v19 row is immutable
> -- and stays in the catalog.
> ```

**Consequence for this brief:** every hedge sentence listed below is a literal
in a landed, immutable migration. Changing one — or adding one for a fifth
publisher — is a **new numbered migration (0089+), a new version string, and a
config flip**, not an edit. That is the concrete unit of cost behind brief
success criterion 1 ("the hedge is derived, not retyped").

### A1.1 Counting rule (so the count is falsifiable)

I counted a **hedge site** as one contiguous sentence or clause-group in the
seeded body that does one of these four jobs:

- (P) **names a publisher** or instructs attribution to one;
- (O) **assigns ownership** of a number or a silence — the school's, the
  publisher's, or ours;
- (A) **instructs how to speak an absence, an estimate, or an imputation**
  (including "a silence is never a no");
- (I) **forbids inventing / estimating / guessing** a value or a verdict.

Sites were found by reading all 369 body lines (`:38-405`) once, then
re-checking with `grep -n` on the marker strings `report`, `publish`, `estimat`,
`never`, `plainly`, `our`, `source`, `attribute`, `year`.

**Count: 39 distinct hedge sites.** The count is judgment-sensitive at roughly
±3 (whether you split or merge adjacent sentences inside one paragraph — H13,
H23 and H35 are each multi-sentence blocks I kept whole). The **publisher-naming
subset is exactly 5** and is not judgment-sensitive; see A1.3.

### A1.2 The 39 sites, quoted verbatim

Line numbers are into `db/schema/0088.seed-coach-system-prompt-v20.sql`. Quotes
are the SQL literal with `''` unescaped to `'` and the `|| '...'` line joins
removed; the wording is otherwise byte-exact.

| #   | Lines   | Kind                 | Verbatim                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| --- | ------- | -------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| H1  | 42-44   | I                    | "Never invent facts about the student, or about specific colleges, deadlines, or requirements — say plainly when you don't know."                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| H2  | 65-68   | O                    | "tuition and fees, the price the school sets and publishes, and living costs — housing and food, books, travel, and everyday spending — which the school only estimates and which the student's own choices move."                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| H3  | 78-82   | A                    | "When a school's result gives a net price based on the student's income band, lead with that family-specific number... When the basis is the overall average, say so plainly."                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| H4  | 96-97   | A/I                  | "Where the publisher does not separate an in-district price, say so in words rather than guessing at one."                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| H5  | 107-110 | **P**/A              | "Always attribute cost figures to the U.S. Department of Education College Scorecard, and when a school doesn't report a figure, say that plainly rather than estimating."                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| H6  | 111-115 | P                    | "Never name a data source's internal buckets, codes or field names — no quintiles, no Q numbers, no NPT codes, no column names."                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| H7  | 132-134 | **P**                | "the figures come from each school's own Common Data Set, so attribute them to it and never to memory."                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| H8  | 135-142 | A                    | "State the merit share exactly as the tool frames it... a share and an average describe last year's class, not a promise to them, so state them and stop"                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| H9  | 142-144 | O/A                  | "When a field is named in a result's data_availability, that school does not report it: say so plainly rather than estimating it."                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| H10 | 144-147 | O                    | "When a round is flagged as not offered, that is the school saying it does not offer that round — a reported fact, not missing data — so tell the student the school does not offer it."                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| H11 | 147-149 | P                    | "Talk about all of it in plain words: never read a source's internal codes or field names out to the student."                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| H12 | 156-159 | O                    | "Tuition and fees is the price the school sets and publishes; the other lines are the school's own estimates of living costs, so say they are estimates and that the student's own choices move them."                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| H13 | 159-170 | **O**                | "The at-home total counts no housing and food, and that zero is ours rather than the school's: no source publishes what living at home costs, so we assume it. Say the assumption in the same breath, in these words. Living at home, we count no food-and-housing cost: eating at home is not free, but it is not a new cost that enrolling creates, so the at-home total counts it as zero. That is our assumption, not a figure any school published. Never let that zero look like the school's own figure, and never say the school reported it."                                                                                                                                                                                                                                                                                                                                                                                                                        |
| H14 | 171-173 | A                    | "When an arrangement carries no total, the school did not report one of its parts: say which part is missing, and never add up the parts that are there and call the result the total."                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| H15 | 173-176 | O/A                  | "A school flagged as offering no on-campus housing has no residence halls: say so, rather than calling a missing on-campus figure unreported."                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| H16 | 176-178 | O                    | "If such a school still shows an on-campus price, it published that price itself and the two sources disagree — say both, and never hide either."                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| H17 | 178-181 | A                    | "The published cost of attendance is a separate figure: an older average blended across all three arrangements, so never present it as one arrangement's total and never compare the two."                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| H18 | 184-187 | A                    | "The tool gives each academic year together with the figures it covers: say a number with the year that lists it, never with the other year, and never add figures from two different years together."                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| H19 | 192-193 | P                    | "Never write a data source's own code, and never say one aloud to a student."                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                 |
| H20 | 193-195 | A                    | "When you say how many schools match, say the tool's total number of matches, not how many you happened to list."                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             |
| H21 | 195-200 | **A**                | "When the tool reports that some schools could not be judged on a filter, say so in plain words -- for example, sixty-one schools could not be judged because they do not report an admission rate -- because a school that does not report something has not been shown to fail it, and treating a silence as a no would quietly shrink the student's options."                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
| H22 | 200     | A                    | "Say the academic year the tool gives for the figures it returns."                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |
| H23 | 205-212 | A                    | "the cost tool gives you a comparison_basis: say those five lines first... Say who the figures describe, which residency every tuition and fees figure assumes, which way of living every school is priced for, which academic year each figure comes from, and what a net price already counts as aid."                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| H24 | 216-220 | A                    | "When a school does not report a part, leave that cell blank and label it as not reported: never write a zero, never carry a neighbour's number across, and never add up the parts that are there and call it the total."                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| H25 | 220-221 | O                    | "A school with no residence halls has none — say so, rather than calling it unreported."                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| H26 | 251-255 | **A**                | "Say which axes the answer ranked on -- the response names them -- and say any axis it had to drop, together with the reason it gives: a school that reports nothing on an axis was not judged on it, and a silence is never a no."                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| H27 | 255-258 | A                    | "Each school comes back with axes_scored, the axes it was actually judged on: a school judged on one axis is a weaker claim than one judged on three, so say which."                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| H28 | 258-261 | A                    | "The distance the tool returns is a rank aid and nothing more. Never say it as a percentage, never call one school a percentage similar to another, and never read a smaller number as a better school."                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| H29 | 279-284 | A                    | "When a school is not priced for the chosen way of living, say the reason plainly — it has no residence halls, or it did not publish that figure — and never quote a different arrangement in its place"                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| H30 | 306-310 | A                    | "Say plainly that the report is live: it updates as the student updates their list, so what a parent sees today is not what they will see next week."                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| H31 | 314-318 | **P**/O              | "At a public school, the published price and the price after a financial aid offer are figures for students paying in-state tuition and fees: the school reports them for the families of its own state, and the U.S. Department of Education publishes them on that basis."                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                  |
| H32 | 319-329 | A                    | "say plainly that the other two figures are on a residency basis that is not theirs... Never hide a price because that question is unanswered, and never make an answer wait on it."                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| H33 | 336-340 | **P**                | "with the award year and the Federal Student Aid source each figure comes from, and they are the same at every college. Always say which award year a figure is for, and attribute it to the source the tool cites."                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| H34 | 343-345 | A                    | "When the tool marks a group of figures as coming from a prior award year, say that plainly rather than presenting them as this year's."                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| H35 | 366-381 | **O/A**              | "When a figure has no amount, the result says why in a figure_statuses entry, and every entry carries the sentence to say: This school did not report this figure. This figure does not apply at this school, and the source says so. This figure is withheld by the publisher for privacy. We have not collected this figure yet. That last sentence is ours and not the school's — it is our gap, so never tell a family the school failed to report a figure we have not gathered. Say the entry's own sentence rather than the status word beside it. A figure the publisher estimated still has an amount, so show it and say the entry's own sentence beside it: This is the publisher's own estimate for this school, not a figure the school reported. That holds even when the estimate is zero. A figure that is simply reported needs none of these sentences: give the number plainly. None of these is ever a zero, and none of them is ever a reason to guess." |
| H36 | 382-387 | **P** _(new in v20)_ | "Every figure there comes from that school's own Common Data Set for the year its citation names, so give the year and the source with the figure."                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| H37 | 392-396 | I _(new in v20)_     | "No school publishes a 'we meet full need' answer, so never give one of your own: give the two cited figures and let the family draw the conclusion."                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| H38 | 399-402 | O _(new in v20)_     | "A form that is NOT on the list is not listed in that filing, which is not the school saying it is not required: say that plainly and send the family to the school's financial aid office to confirm."                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       |
| H39 | 402-405 | O _(new in v20)_     | "When a school has no filing in our corpus, aid_policy_availability says so -- pass that on rather than estimating it from another school."                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   |

Also in H36's paragraph, structurally an ownership hedge on the _cohort_
(`:388-392`): "Two numbers answer the need question and neither of them is a yes
or a no... Say each one the way its own label says it -- both are about the
students that school gave need-based aid to, never about every freshman and
never about this student." I folded it into H36/H37 rather than counting it
separately; splitting it would make the count 40.

### A1.3 The one thing that is NOT judgment-sensitive: 5 publisher-named sites

Exactly **five** sites hard-code a publisher's name or attribution rule:

| Site | Line    | The literal                                                     |
| ---- | ------- | --------------------------------------------------------------- |
| H5   | 108-109 | "the U.S. Department of Education College Scorecard"            |
| H7   | 133-134 | "each school's own Common Data Set"                             |
| H31  | 317     | "the U.S. Department of Education publishes them on that basis" |
| H33  | 336-337 | "the Federal Student Aid source each figure comes from"         |
| H36  | 385-386 | "that school's own Common Data Set"                             |

Counted with
`grep -n "Scorecard\|Common Data\|Department of Education\|Federal Student Aid" db/schema/0088*.sql`
restricted to the body lines 38-405.

Note the asymmetry that is the heart of the brief: **`MoneySource` has four
members but the prompt names three publishers**, and it never names IPEDS at
all. IPEDS SFA and IPEDS IC_AY figures reach a family under the Scorecard
sentence at H5 ("Always attribute cost figures to the U.S. Department of
Education College Scorecard"), which since RFC 161/162 is **stale by
construction** — `ORDERED_SOURCES` puts `IPEDS_SFA` first, so the number the
coach attributes to the Scorecard is routinely an IPEDS number
(`college/src/main/kotlin/ed/unicoach/college/CanonicalMoneyLoader.kt:1117`).
That is an existing, live provenance defect the prompt cannot fix without a new
migration, and it is direct evidence for C5 (a derived renderer) over C0.

### A1.4 What a FIFTH publisher would break, site by site

Adding a fifth `MoneySource` member (say a state system's tuition schedule)
requires a new migration to `money_source`
(`db/schema/0087.create-aid-form-requirements.sql:119-122`) and a new enum
member (`db/src/main/kotlin/ed/unicoach/db/models/MoneySource.kt:23-43`). What
then breaks in the prompt:

**Breaks outright (says something false) — 5 sites, all in A1.3.**

- **H5 (`:107-110`)** — "**Always** attribute cost figures to the U.S.
  Department of Education College Scorecard." A price from the fifth publisher
  is attributed to the wrong publisher, with the word "Always" forbidding the
  correct behaviour. Already broken today for IPEDS (A1.3).
- **H7 (`:132-134`)** and **H36 (`:382-387`)** — "the figures come from each
  school's own Common Data Set" / "Every figure there comes from that school's
  own Common Data Set". If the fifth publisher fills any `aid_policy` or merit
  cell, "Every figure there" becomes false and the coach cites the wrong filing.
- **H31 (`:314-318`)** — the in-state residency basis is asserted as a property
  of _the U.S. Department of Education's_ publication. A fifth publisher with a
  different residency convention makes the sentence a false claim about that
  publisher's basis.
- **H33 (`:336-340`)** — bounded to Federal Student Aid; hedges nothing about a
  fifth publisher, but "attribute it to the source the tool cites" is the one
  publisher-agnostic form already present in the corpus and is the template a
  derived renderer would generalise.

**Silently loses the distinction (says something true but not the point) — 3
sites.**

- **H35 (`:366-381`)**, the figure-status paragraph. This is the only fully
  data-driven hedge: "every entry carries the sentence to say", and the sentence
  text ships from
  `service/src/main/kotlin/ed/unicoach/coaching/costs/canonical/FigureStatusCopy.kt:47-72`.
  A fifth publisher needs **no prompt change here** — which is precisely the
  proof of concept for C5. But it also means the fifth publisher's figures
  inherit `reported` → "A figure that is simply reported needs none of these
  sentences: give the number plainly" (`:378-381`). A voluntary unaudited
  self-report and a federal administrative record both arrive as `reported` and
  are both spoken _plainly, with no sentence at all_. **This is Ian's question,
  located exactly.**
- **H12 (`:156-159`)** and **H13 (`:159-170`)** — the school-publishes /
  school-estimates / we-assume three-way split is written as if there were one
  publisher of living costs. A fifth publisher of living costs cannot be placed
  on that axis without new prose.

**Unaffected — the remaining 31 sites.** H1, H3, H4, H6, H8-H11, H14-H30, H32,
H34, H37-H39 are keyed on the _tool payload shape_ (`data_availability`,
`axes_scored`, `comparison_basis`, `aid_policy_availability`, `precision_offer`)
or on a general rule ("a silence is never a no"), not on a publisher identity.
They are already publisher-agnostic. This is the good news for C5: **~79% of the
hedge corpus is already derived from payload structure**; what is not derived is
exactly the publisher-identity layer and the assurance layer, which do not exist
as data at all.

### A1.5 Diff against v19 (`db/schema/0086.seed-coach-system-prompt-v19.sql`)

I extracted both SQL string bodies programmatically (all `'...'` literals after
the version literal, `''`→`'`, concatenated) and compared:

- v19 body: **20,745 chars**. v20 body: **22,153 chars**.
- `v20.startswith(v19)` → **True**. v20 is v19 plus a **1,408-char suffix and
  nothing else**. Zero interior edits.

This matches the migration's own claim, which is therefore verified rather than
trusted: `db/schema/0088...:24-30` — "This body is v19 VERBATIM plus exactly one
appended paragraph".

**New hedge sentences in v20 — 4 sites (H36-H39), all in the appended
`aid_policy` paragraph (`:382-405`):**

1. `:385-387` "Every figure there comes from that school's own Common Data Set
   for the year its citation names, so give the year and the source with the
   figure." — a **new publisher-named attribution rule** (the 5th of 5).
2. `:393-396` "No school publishes a 'we meet full need' answer, so never give
   one of your own: give the two cited figures and let the family draw the
   conclusion." — a **new no-verdict rule**.
3. `:399-402` "A form that is NOT on the list is not listed in that filing,
   which is not the school saying it is not required" — a **new
   absence-of-evidence rule**, the third instance of the "silence is never a no"
   pattern (with H21 `:198-199` and H26 `:254-255`).
4. `:402-405` "When a school has no filing in our corpus,
   aid_policy_availability says so -- pass that on rather than estimating it
   from another school." — a **new our-gap rule**, the second instance of the
   pattern H35 `:371-374` already carries.

Read together: **v20 added four hedge sentences, and three of the four are
re-statements of patterns already present elsewhere in the same prompt.** That
duplication rate — 3 of 4 — is the strongest single piece of evidence in Part A
for the brief's thesis that the hedge is being retyped rather than derived.

---

## A2. What the store already models

### A2.1 `db/schema/0083.create-canonical-money-tables.sql` — 5 vocabularies + 2 fact tables + 1 dropped table

**`residency_bases`** (`:27-34`) — `slug TEXT NOT NULL PRIMARY KEY`,
`description TEXT NOT NULL`, `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`,
`updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`; CHECK
`residency_bases_slug_format_check (slug ~ '^[a-z0-9]+(_[a-z0-9]+)*$')`.

**`arrangements`** (`:50-58`) — `slug TEXT PK`, `description TEXT NOT NULL`,
**`is_living_arrangement BOOLEAN NOT NULL`**, `created_at`, `updated_at`; slug
format CHECK.

**`figure_statuses`** (`:80-88`) — `slug TEXT NOT NULL PRIMARY KEY`,
`description TEXT NOT NULL`, **`value_bearing BOOLEAN NOT NULL`**,
`created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`,
`updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`; CHECK
`figure_statuses_slug_format_check`. **Four columns of substance. There is no
fifth.** Rows come from `db/data/money-vocabulary.json:51-82`.

**`price_concepts`** (`:111-119`) — `slug TEXT PK`, `description TEXT NOT NULL`,
**`arrangement_varies BOOLEAN NOT NULL`**, `created_at`, `updated_at`; slug
format CHECK.

**`income_bands`** (`:145-158`) — `slug TEXT PK`, `min_usd INTEGER NOT NULL`,
`max_usd INTEGER NULL`, `bracket_label TEXT NOT NULL`,
`sort_order SMALLINT NOT NULL UNIQUE`, `created_at`, `updated_at`; CHECKs
`min_usd >= 0`, `max_usd IS NULL OR max_usd > min_usd`, slug format.

**`price_figures`** (`:184-217`) — full column list:

| Column                      | Type                                           | Constraint                                                |
| --------------------------- | ---------------------------------------------- | --------------------------------------------------------- |
| `id`                        | `UUID`                                         | `NOT NULL PRIMARY KEY DEFAULT uuidv7()`                   |
| `college_id`                | `UUID`                                         | `NOT NULL REFERENCES colleges(id)`                        |
| `price_concept`             | `TEXT`                                         | `NOT NULL REFERENCES price_concepts(slug)`                |
| `residency_basis`           | `TEXT`                                         | `NOT NULL REFERENCES residency_bases(slug)`               |
| `arrangement`               | `TEXT`                                         | `NOT NULL REFERENCES arrangements(slug)`                  |
| `academic_year`             | `TEXT` → **`academic_year` (SMALLINT domain)** | `NOT NULL`; retyped at `0087:44-47`                       |
| `amount_usd`                | `INTEGER`                                      | **`NULL`**                                                |
| `status`                    | `TEXT`                                         | `NOT NULL REFERENCES figure_statuses(slug)`               |
| `source`                    | `TEXT` → **`money_source` domain**             | `NOT NULL`; retyped at `0087:388-390`                     |
| `source_variable`           | `TEXT`                                         | `NOT NULL`, `<> ''`                                       |
| `publisher_flag`            | `TEXT`                                         | **`NULL`** — "raw IPEDS X-code, when one exists" (`:195`) |
| `created_at` / `updated_at` | `TIMESTAMPTZ`                                  | `NOT NULL DEFAULT NOW()`                                  |

Table constraints:
`price_figures_natural_key UNIQUE (college_id,
price_concept, residency_basis, arrangement, academic_year)`
(`:198-200`);
`price_figures_value_iff_status_check CHECK ((amount_usd IS NOT NULL) = (status
IN ('reported', 'imputed_by_publisher')))`
(`:206-208`); `price_figures_amount_nonneg_check` (`:212-213`);
source/source_variable non-empty (`:214-216`). Later added:
`price_figures_residency_basis_price_keyable_check` and
`price_figures_arrangement_price_keyable_check` (`0085:316-320`).

**`cohort_money_stats`** (`:247-285`) — full column list:

| Column                      | Type                                | Constraint                                                                              |
| --------------------------- | ----------------------------------- | --------------------------------------------------------------------------------------- |
| `id`                        | `UUID`                              | `NOT NULL PK DEFAULT uuidv7()`                                                          |
| `college_id`                | `UUID`                              | `NOT NULL REFERENCES colleges(id)`                                                      |
| `measure`                   | `TEXT`                              | `NOT NULL`, CHECK IN (16 values as of `0087:316-327`)                                   |
| `population`                | `TEXT`                              | `NOT NULL`, CHECK IN (9 values, `0087:340-349`)                                         |
| `residency_scope`           | `TEXT`                              | `NOT NULL`, CHECK IN `('in_state_rate_paying','all')`                                   |
| `aid_scope`                 | `TEXT`                              | `NOT NULL`, CHECK IN (10 values, `0087:356-364`)                                        |
| `income_band`               | `TEXT`                              | **`NULL` REFERENCES income_bands(slug)** — NULL means "the overall figure" (`:308-312`) |
| `vintage`                   | `TEXT` → **`academic_year` domain** | **made `NULL`able at `0087:68-73`**                                                     |
| `value`                     | `NUMERIC`                           | **`NULL`**                                                                              |
| `status`                    | `TEXT`                              | `NOT NULL REFERENCES figure_statuses(slug)`                                             |
| `source`                    | `TEXT` → **`money_source`**         | `NOT NULL` (`0087:392-394`)                                                             |
| `source_variable`           | `TEXT`                              | `NOT NULL`, `<> ''`                                                                     |
| `publisher_flag`            | `TEXT`                              | **`NULL`**                                                                              |
| `source_document_id`        | `UUID`                              | **`NULL` REFERENCES source_documents(id)** — added `0087:416-419`                       |
| `created_at` / `updated_at` | `TIMESTAMPTZ`                       | `NOT NULL DEFAULT NOW()`                                                                |

Plus `cohort_money_stats_share_range_check` (`0087:332-338`),
`cohort_money_stats_value_iff_status_check` (`:279-281`), and
`cohort_money_stats_cds_cites_document_check CHECK (source <> 'common_data_set'
OR source_document_id IS NOT NULL)`
(`0087:418-419`). Natural key is a `NULLS NOT DISTINCT` unique index
(`:288-291`).

**`aid_policy_facts`** (`:324-350`) was created here and **DROPPED** at
`0087:305`. It does not exist in the live schema.

`college_index_build` gains `price_figure_rows INTEGER NULL`,
`cohort_money_stat_rows INTEGER NULL`, **`canonical_money_summary JSONB NULL`**
(`:367-374`).

### A2.2 `db/schema/0084.create-college-ipeds-charges.sql`

**`college_ipeds_charges`** (`:20-62`): `id UUID NOT NULL PK DEFAULT uuidv7()`;
`college_id UUID NOT NULL REFERENCES colleges (id) ON DELETE CASCADE`;
`charge_variable TEXT NOT NULL` (CHECK `~ '^[A-Z0-9]+$'`);
`academic_year TEXT NOT NULL` → `academic_year` domain (`0087:54-57`);
`amount_usd INTEGER NULL`; **`imputation_flag TEXT NOT NULL`** with CHECK
`IN ('R','C','G','J','K','L','N','P','Z','B','D','H','A')` (13 codes, `:46`);
`created_at`/`updated_at`. Plus
`college_ipeds_charges_value_iff_flag_check CHECK ((amount_usd IS NOT NULL) =
(imputation_flag IN ('R','C','G','J','K','L','N','P','Z')))`
(`:60-61`).

Also here (`:102-108`) `source` first becomes an owned enumeration:
`price_figures_source_domain_check` / `cohort_money_stats_source_domain_check`
`CHECK (source IN ('ipeds_ic_ay', 'scorecard'))`. The rationale comment
(`:88-99`) is directly load-bearing for this brief:

> "Deliberately NOT a sixth vocabulary table: the five RFC 158 seeded are
> unicoach concepts; **a source is external identity and provenance.**"

### A2.3 `db/schema/0085.create-sfa-staging-and-population-counts.sql`

**`college_sfa`** (`:35-61`): `id UUID NOT NULL PK DEFAULT uuidv7()`;
`ipeds_unit_id INTEGER NOT NULL` (`> 0`); `aid_year_start SMALLINT NOT NULL`
(`BETWEEN 1980 AND 2100`); `variable TEXT NOT NULL` (`~ '^[a-z][a-z0-9_]*$'`);
`aid_year TEXT NOT NULL` → `academic_year` domain (`0087:98-101`);
`value NUMERIC NULL`; **`publisher_flag TEXT NOT NULL`** CHECK IN
`('A','B','C','D','G','H','J','K','L','N','P','R','Z')` (`:52-53`);
`created_at`/`updated_at`; plus
`college_sfa_not_applicable_has_no_value_check CHECK (publisher_flag <> 'A' OR
value IS NULL)`
(`:59-60`).

**`cohort_population_counts`** (`:117-163`): `id UUID NOT NULL PK`;
`college_id UUID NOT NULL REFERENCES colleges(id)`; `population TEXT NOT NULL`
(CHECK IN 9 values as of `0087:369-377`);
`residency_basis TEXT NOT NULL REFERENCES residency_bases(slug)`;
`arrangement TEXT NOT NULL REFERENCES arrangements(slug)`;
`vintage TEXT NOT NULL` → `academic_year` domain, still `NOT NULL`
(`0087:81-84`); `headcount INTEGER NULL`;
`status TEXT NOT NULL REFERENCES figure_statuses(slug)`; `source TEXT NOT NULL`
→ `money_source` (`0087:396-398`); `source_variable TEXT NOT NULL` (`<> ''`);
**`publisher_flag TEXT NULL`**;
`source_document_id UUID NULL REFERENCES source_documents(id)` (added
`0087:421-424`); `created_at`/`updated_at`.

### A2.4 `db/schema/0087.create-aid-form-requirements.sql`

**`academic_year` DOMAIN** (`:31-32`):
`CREATE DOMAIN academic_year AS SMALLINT CONSTRAINT academic_year_check CHECK (VALUE BETWEEN 2000 AND 2100);`

**`money_source` DOMAIN** (`:119-122`) — verbatim:

```sql
CREATE DOMAIN money_source AS TEXT
    CONSTRAINT money_source_check
        CHECK (VALUE IN ('ipeds_sfa', 'ipeds_ic_ay', 'scorecard',
                         'common_data_set'));
```

Its comment (`:124-127`): "The publisher of a stored money fact or document (RFC
170, D8), mirrored by the Kotlin MoneySource enum and pinned to it by test. A
DOMAIN, not a per-table CHECK: the list is one list, wherever a source column
appears." And `:118`: "**A fifth publisher is now one ALTER DOMAIN.**"

**`source_documents`** (`:139-157`) — full column list, verbatim shape:

| Column                      | Type            | Constraint                                                            |
| --------------------------- | --------------- | --------------------------------------------------------------------- |
| `id`                        | `UUID`          | `NOT NULL PRIMARY KEY DEFAULT uuidv7()`                               |
| `college_id`                | `UUID`          | `NOT NULL REFERENCES colleges(id)`                                    |
| `source`                    | `money_source`  | `NOT NULL`                                                            |
| `academic_year`             | `academic_year` | `NOT NULL`                                                            |
| `source_url`                | `TEXT`          | `NOT NULL` — "the school's own publication"; CHECK `source_url <> ''` |
| `archive_url`               | `TEXT`          | **`NULL`** — "our mirror, when one exists"                            |
| `created_at` / `updated_at` | `TIMESTAMPTZ`   | `NOT NULL DEFAULT NOW()`                                              |

`CONSTRAINT source_documents_natural_key UNIQUE (college_id, source, academic_year)`
(`:151-152`). **Seven columns. No quality, assurance, checked-by, retrieved-at,
or coherence column exists.**

**`aid_forms`** (`:208-215`): `slug TEXT NOT NULL PK`,
`description TEXT NOT NULL`, `created_at`, `updated_at`, slug format CHECK. Four
columns.

**`aid_form_requirements`** (`:244-270`):
`id UUID NOT NULL PK DEFAULT uuidv7()`;
`college_id UUID NOT NULL REFERENCES colleges(id)`;
`aid_form TEXT NOT NULL REFERENCES aid_forms(slug)`;
`applicant_group TEXT NOT NULL` (CHECK IN
`('domestic_first_year_aid_applicants','nonresident_first_year_aid_applicants')`);
`academic_year academic_year NOT NULL`; **`is_required BOOLEAN NULL`**;
`status TEXT NOT NULL REFERENCES figure_statuses(slug)`;
`source money_source NOT NULL`; `source_variable TEXT NOT NULL` (`<> ''`);
`source_document_id UUID NOT NULL REFERENCES source_documents(id)`;
`created_at`/`updated_at`; plus `aid_form_requirements_natural_key` and the
value-iff-status CHECK.

Also `college_index_build` gains `aid_form_requirement_rows INTEGER NULL`
(`:440-443`).

### A2.5 `MoneySource.kt` — members and per-member metadata

`db/src/main/kotlin/ed/unicoach/db/models/MoneySource.kt:23-50`. The constructor
has **exactly one property**:

```kotlin
enum class MoneySource(
  val value: String,
) {
  /** IPEDS SFA, the Student Financial Aid survey: net prices, aid mixes and the cohort headcounts under them (RFC 162). */
  IPEDS_SFA("ipeds_sfa"),

  /** IPEDS IC_AY, the published-charges survey: three residency tiers, fees split from tuition. */
  IPEDS_IC_AY("ipeds_ic_ay"),

  /** The College Scorecard institution file (RFC 158's v1 source). */
  SCORECARD("scorecard"),

  /**
   * The school's own Common Data Set filing, read through the collegedata.fyi
   * corpus (RFC 170, D8). ...
   */
  COMMON_DATA_SET("common_data_set"),
  ;
```

> **Correction to the brief.** `product/0008.../brief.md` says of C1:
> "`MoneySource` already carries per-member Kotlin metadata." **That is false.**
> `MoneySource` carries only `value: String`
> (`db/src/main/kotlin/ed/unicoach/db/models/MoneySource.kt:24`, verified with
> `grep -n "val "` → two hits, the second being the private `BY_VALUE` map at
> `:46`). The enum that carries per-member metadata is **`FigureStatus`**
> (`valueBearing: Boolean`). C1/C2 therefore _introduce_ the first per-member
> `MoneySource` attribute rather than reusing an existing pattern — a smaller
> claim than the brief makes, though still cheap.

Two design comments in that file bear directly on C1/C2:

- `:14-18` — "Deliberately NOT a sixth vocabulary table beside the five RFC 158
  seeded: those are unicoach CONCEPTS... A source is external identity and
  provenance, not a concept, and precedence stays a code list guarded by the
  unmapped-source fatal rather than becoming operator-editable data."
- `:20-21` — "Declaration order is NOT precedence --
  `CanonicalMoneyLoader.ORDERED_SOURCES` is the one place that decides who
  wins."

An assurance tier added here would be a **second** per-member attribute on an
enum whose own doc comment argues it is identity-and-provenance — which is
exactly the right home for an assurance axis, and the file already says so in
those words.

### A2.6 `FigureStatus.kt` — members and per-member metadata

`db/src/main/kotlin/ed/unicoach/db/models/FigureStatus.kt:12-38`, verbatim:

```kotlin
enum class FigureStatus(
  val value: String,
  /** TRUE exactly when a figure with this status carries a value. */
  val valueBearing: Boolean,
) {
  /** The institution reported it and the source published it. */
  REPORTED("reported", true),

  /** The source publisher imputed it rather than receiving it. */
  IMPUTED_BY_PUBLISHER("imputed_by_publisher", true),

  /** The institution reported nothing for this cell (blank / NULL sentinel). */
  NOT_REPORTED_BY_INSTITUTION("not_reported_by_institution", false),

  /** The cell cannot apply to this institution, and the source says so. */
  NOT_APPLICABLE("not_applicable", false),

  /** The publisher suppressed a reported figure (Scorecard `PrivacySuppressed`). */
  SUPPRESSED_BY_PUBLISHER("suppressed_by_publisher", false),

  /** A source we ingest carries the cell and we deliberately skip it; reserved -- the v1 Scorecard fill never emits it (P7). */
  NOT_COLLECTED_BY_US("not_collected_by_us", false),
  ;
```

Two per-member properties: `value` and `valueBearing`. The seeded row
descriptions are the third piece of per-member metadata and live in
`db/data/money-vocabulary.json:51-82`; `reported` there is "The institution
reported this figure and the source published it."
(`db/data/money-vocabulary.json:54`) — **the exact conflation the brief names.**

### A2.7 `CanonicalMoneyLoader.ORDERED_SOURCES`, verbatim

`college/src/main/kotlin/ed/unicoach/college/CanonicalMoneyLoader.kt:1117`:

```kotlin
internal val ORDERED_SOURCES = listOf(MoneySource.IPEDS_SFA, MoneySource.IPEDS_IC_AY, MoneySource.SCORECARD)
```

With its doc comment (`:1099-1116`), the sibling constant, and the init guard:

```kotlin
    internal val SOURCES_FILLED_ELSEWHERE = setOf(MoneySource.COMMON_DATA_SET)   // :1127

    init {                                                                        // :1129
      val unranked = MoneySource.entries - ORDERED_SOURCES.toSet() - SOURCES_FILLED_ELSEWHERE   // :1135
      check(unranked.isEmpty()) { ... }                                           // :1136-1140
      check(ORDERED_SOURCES.size == ORDERED_SOURCES.toSet().size) { ... }         // :1141-1143
    }
```

The doc comment's own words on why the order is what it is (`:1100-1115`):

> "IPEDS SFA is FIRST (RFC 162 D5): the Scorecard's NPT4 band series is a copy
> of SFA's own NPIS4x/NPT4x, so the publisher's number displaces the copy rather
> than being averaged with it." ... "IPEDS IC_AY is next (RFC 161) because it
> carries the three residency tiers as separate first-class variables, while the
> Scorecard collapses in-district into 'in' -- so for the 269 institutions where
> the two disagree, the Scorecard's `TUITIONFEE_IN` is the in-DISTRICT price
> wearing an in-state label." ... "This list, not the [MoneySource] declaration
> order, is precedence."

**Reading for this brief:** precedence today is ordered by _closeness to the
publisher of record and by axis fidelity_ — "the publisher's number displaces
the copy". That is a **provenance** argument, already, in the one place that
ranks sources. It is not an assurance argument (nothing here says "audited" or
"self-reported"), and it deliberately excludes `COMMON_DATA_SET` from ranking
altogether (`:1119-1127`). So an assurance tier would be a **new, orthogonal**
axis, not a reinterpretation of `ORDERED_SOURCES`.

Note also the second consumer:
`college/src/main/kotlin/ed/unicoach/college/CollegeScorecardLoader.kt:761` —
`CanonicalMoneyLoader.ORDERED_SOURCES + listOfNotNull(MoneySource.COMMON_DATA_SET.takeIf { cds != null })`.

---

## A3. Where an assurance / softness attribute could go today

Searched the whole non-build tree for any existing softness concept:
`grep -rn --include=*.kt --include=*.sql --include=*.json --include=*.swift -i
"self_report\|selfReported\|assurance\|unaudited\|attestation" db college service
rest-server queue-worker ios-app/UnicoachiOS`
→ **zero relevant hits.** (Three noise hits: two CIP program names in
`db/data/codebooks.json:3777,10563` and one unrelated code comment in
`service/src/test/kotlin/ed/unicoach/coaching/costs/CollegeCostServiceTest.kt:637`.)
**Nothing in the repo models assurance today. Plainly: it does not exist.**

Room available, ranked by cost:

| Home                                                                            | Room today?                                                                                                                                                                                                                                                                                                    | Cost of adding assurance                                                                                                                                                                                                                                                                                                                       | Evidence                               |
| ------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------- |
| **`MoneySource` enum (C1/C2)**                                                  | Yes — a new constructor property. **No migration.** The `money_source` DOMAIN stores only the slug, so a Kotlin-side attribute needs no DDL.                                                                                                                                                                   | Lowest. One property + 4 member values + one exhaustive-`when` renderer.                                                                                                                                                                                                                                                                       | `MoneySource.kt:23-25`; `0087:119-122` |
| **`figure_statuses` vocabulary (a 7th status, or a 3rd column)**                | A **new column** is a migration; `figure_statuses` has exactly 4 columns (`0083:80-88`) and no spare. A 7th _status_ would be worse: it is enumerated **literally inside 4 `value_iff_status_check` CHECKs** (`0083:206-208`, `0083:279-281`, `0085:150-152`, `0087:265-267`) and is pinned by test both ways. | High and wrong-axis: status is _why a value is present/absent_, not _how it was produced_.                                                                                                                                                                                                                                                     | as cited                               |
| **`source_documents` (C3)**                                                     | **No spare column.** All 7 columns are cited above. `archive_url` is the only NULLable one and means "our mirror".                                                                                                                                                                                             | One `ALTER TABLE ADD COLUMN`. Nullable is cheap; `NOT NULL` needs a value for every filing. Note the RFC 170 precedent for a _per-source_ mandatory column: `cohort_money_stats_cds_cites_document_check` (`0087:418-419`) makes a column NULLable in general and mandatory for `common_data_set` only — **the exact pattern C3 would reuse.** | `0087:139-157`, `0087:414-419`         |
| **`price_figures` / `cohort_money_stats` / `cohort_population_counts` per-row** | `publisher_flag TEXT NULL` already exists on all three and already carries a **per-row publisher assertion about how the cell was produced** (the IPEDS X-code: R reported, P/N imputed, Z implied zero).                                                                                                      | This is the closest existing thing to a per-row softness attribute. But it is deliberately raw and source-defined (`0083:237-240`, `0085:98-102`), and it is **NULL for the Scorecard and for CDS** — so it is not a universal axis.                                                                                                           | `0083:195`, `0083:260`, `0085:128`     |
| **`college_index_build.canonical_money_summary JSONB NULL`**                    | The **only JSONB / free-form metadata column** in the whole money layer. It is build provenance, per-status row counts — not per-figure.                                                                                                                                                                       | Wrong grain for a per-figure or per-filing attribute; right grain for C4's _counts_ of coherence findings.                                                                                                                                                                                                                                     | `0083:370`, `0083:382-386`             |
| **A new coherence-findings table (C4)**                                         | Does not exist.                                                                                                                                                                                                                                                                                                | New table, Ian approves DDL at the gate.                                                                                                                                                                                                                                                                                                       | —                                      |

**One further finding for C5.** The derived renderer the brief calls "the seed
of exactly this" already exists and is better than the brief credits:
`service/src/main/kotlin/ed/unicoach/coaching/costs/canonical/FigureStatusCopy.kt`
holds `statementOf` (`:47-72`), `ownerOf` (`:75-91`), `isSchoolsOwnSilence`
(`:101`), `noTotalReasonOf` (`:118-132`) and `yearGapStatementOf` (`:144-149`),
and it already carries the **ownership** concept as a first-class enum:

```kotlin
enum class FigureGapOwner {   // :16-25
  /** The school did not report it, or the cell does not apply to it. */
  SCHOOL,
  /** The publisher withheld it, or estimated it rather than receiving it. */
  PUBLISHER,
  /** OURS: we have not collected it. Never an `ArrangementGap`. */
  UNICOACH,
}
```

Its doc comment (`:36-39`) states the exhaustiveness discipline a C2/C5 slice
would inherit for free: "Every `when` here is exhaustive with NO `else`: a
seventh status added to [FigureStatus] must fail to compile at both sites rather
than ship a code with no words."

So the honest shape of the gap is narrow and specific: **`FigureStatusCopy`
derives the hedge from `FigureStatus` and does it well. There is no equivalent
file deriving anything from `MoneySource`, and `MoneySource` carries nothing to
derive from.** That is the whole hole.
