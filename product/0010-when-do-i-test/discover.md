# DISCOVER — brief 0010 "when do I test?"

Ground truth from the code, 2026-09-10. Gate 1 approved; this file exists so the
spec's slices are written against the repo that exists. Every claim carries
`file:line`. Read-only run — nothing in the tree was modified except this file.

---

## A. The prompt seam (slice 1, D2/D3)

**Current signature** —
`service/src/main/kotlin/ed/unicoach/coaching/CoachingService.kt:845-850`:

```kotlin
private fun composeSystem(
  prompt: SystemPrompt,
  pending: List<Commitment>,
  pendingFits: List<FitSuggestionForOpener>,
  moneyProfile: MoneyProfile? = null,
): String {
```

It is `private`. **Exactly two callers**, both inside this file:

- `CoachingService.kt:277` — `startConvo` pre-flight:
  `composeSystem(prompt, pending, pendingFits, moneyProfile)`.
- `CoachingService.kt:339` — `postTurn` pre-flight:
  `composeSystem(prompt, emptyList(), emptyList(), moneyProfile)`.

Both run **inside `database.withConnection { session -> ... }`** (`:250`,
`:312`), so a slice may add one more DAO read on the same session at no extra
connection.

**How `moneyProfile` is loaded and threaded.**
`activeMoneyProfile(session, studentId)` at `CoachingService.kt:830-836` wraps
`MoneyProfilesDao.findActiveByStudent`, mapping `NotFoundException` to `null`.
It is called at `:272` (start) and `:337` (postTurn), and the value is carried
into `Prepared(...)` as the already-composed system STRING — `Prepared` holds
text, not a profile.

**The early return is the first trap.** `CoachingService.kt:851`:

```kotlin
if (pending.isEmpty() && pendingFits.isEmpty() && moneyProfile == null) return prompt.body
```

A block that must **ALWAYS** be present (today's date; the student's graduation
date) cannot be added inside the `buildString` alone: a student with no
commitments, no fit suggestions and no money-profile row gets `prompt.body`
verbatim and would never see it. The slice must either delete this early return
or extend its condition. Deleting it is the honest move — the doc comment at
`:836-844` says "identical to today when nothing is pending", and that sentence
stops being true the moment a date is always appended.

**The student row is NOT loaded on this path.** Only `studentId: StudentId`
reaches `startConvo`/`postTurn`; `grep` finds no `StudentsDao` import in
`CoachingService.kt` (imports are `CommitmentsDao`, `ConvosDao`,
`FitSuggestionsDao`, `MoneyProfilesDao`, `SystemPromptsDao` — `:15-20`).

**Smallest DAO call that gets the graduation date:**
`StudentsDao.findById(session, id, scope)` —
`db/src/main/kotlin/ed/unicoach/db/dao/StudentsDao.kt:72-86`,
`SELECT * FROM students WHERE id = ?`. It returns `Student` whose
`expectedHighSchoolGraduationDate` is a **`PartialDate`**
(`StudentsDao.kt:32-51` reconstructs it from the three columns;
`db/src/main/kotlin/ed/unicoach/db/models/PartialDate.kt` is the sealed
interface: `YearOnly` / `YearAndMonth` / `FullDate`, with `toIso()` giving
`"2028"`, `"2028-06"`, `"2028-06-15"`). Note `PartialDate`'s own doc already
anticipates this brief: "future RFCs can reuse it for birthdates, **test
dates**, and deadlines" (`PartialDate.kt:13-15`).

A corrupt row throws `CorruptPersistedValueException` (`StudentsDao.kt:43-48`) —
the same refusal class `renderMoneyField` uses (`CoachingService.kt:918-923`).
Year is `NOT NULL` (`db/schema/0005.create-students.sql:16`), month/day nullable
with CHECKs at `:20-30`, so **the year is always available and the precision is
variable**.

**Where a `Clock` comes from.** The module does **not** inject one into
`CoachingService` today — the constructor is
`database, llmCallLog, config, budgetService, tools`
(`CoachingService.kt:77-83`). Two sibling services in the same module already
carry the pattern, both as a **defaulted constructor parameter**:

- `service/.../coaching/synthesis/SynthesisService.kt:83` —
  `private val clock: Clock = Clock.systemUTC(),` used as `Instant.now(clock)`
  at `:294` and `:586`. Its KDoc at `:70-72` states the rule: "**[clock] is the
  sole date input the pass needs** ... every lifecycle timestamp is DB-clocked
  through `NOW()` defaults".
- `service/.../coaching/aid/FederalAidPolicyService.kt:150` — same default, used
  as `LocalDate.now(clock)` at `:194-198` (`currentAwardYear()`).

**DI wiring**: neither is passed a clock in production —
`queue-worker/src/main/kotlin/ed/unicoach/worker/Application.kt:155`
(`SynthesisService(database, llmCallLog, synthesisConfig, budgetService)`) and
`rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt:393`
(`FederalAidPolicyService(database)`) both take the default; only tests pass a
fixed clock. `CoachingService` is constructed once in production at
`rest-server/.../Application.kt:406`. So the slice's move is: add
`private val clock: Clock = Clock.systemUTC(),` as the **last** constructor
parameter of `CoachingService`, touching no production call site, and let tests
inject `Clock.fixed(...)`. Compare the deliberate NON-default of `budgetService`
(`CoachingService.kt:75`: "The parameter is undefaulted: a root cannot ship an
ungated coach by omission") — a clock is not that kind of parameter and a
default is correct.

Timezone caution: both precedents use `Clock.systemUTC()`. "Today" for a US
student is not UTC-today after 19:00 ET. The slice must decide and say so;
nothing in the repo settles it.

---

## B. How a prompt change ships

**The source of truth is `prompts/coach-system-prompt.md`** — one file, the only
entry in `prompts/`. RFC 185
(`rfc/185-the-coach-prompt-is-a-markdown-file.md:26-31`): "**The body is the
file.** `prompts/coach-system-prompt.md`, verbatim, byte for byte. No trimming,
no joining, no dropping, no rule." The one byte that is not body is the final
line terminator (`185:28-38`; the Kotlin oracle is
`readText().removeSuffix("\n")`, `bin/prompt-seed:22-29`).

**A prompt edit becomes a DB row at LAND, not at edit time**, via
`bin/prompt-seed`, run in phase 6 of the ship skill between `ship-rebase` and
`ship-squash` (`.prime/agent/skills/ship/SKILL.md:292-298`):

```
nix develop -c bin/prompt-seed -b "$(scripts/ship-state -s <rs> get BASE_SHA)"
```

The script deletes this run's own seed, recomputes the label as one past the
highest coach seed in `BASE_SHA`, rewrites
`db/schema/seed-coach-system-prompt-vNN.sql` from the source file (one SQL
fragment per source line, joined by `chr(10)` — RFC 185 D5, `185:~130`), appends
the `ORDER` line, and **rewrites the pin** in
`service/src/main/resources/service.conf` (`bin/prompt-seed:78` — `PIN_REL`).

**RFC 185 D6 is the rule that matters to a slice author**: a version is cut
**iff the authored BODY differs from the BODY of the catalog tip in
`<base-ref>`** (`rfc/185:...D6`, restated at `bin/prompt-seed:34-58`). A run
that touches no prompt writes nothing — no seed, no `ORDER` line, no repin
(`SKILL.md:333-336`). So: **edit the Markdown file and do nothing else**; do not
hand-write a seed migration, do not hand-edit `service.conf`.

**`COACHING_SYSTEM_PROMPT_VERSION`** is the env override beside the literal pin
— `service/src/main/resources/service.conf:176-177`:

```
systemPromptVersion = "v27"
systemPromptVersion = ${?COACHING_SYSTEM_PROMPT_VERSION}
```

**The CURRENT live version is `v27`** (`service.conf:176`; tip of
`db/schema/ORDER` is `seed-coach-system-prompt-v27.sql`). **Rollback** = set
`COACHING_SYSTEM_PROMPT_VERSION=v26` in the environment; every seeded row is
immutable and insert-only (`db/schema/0007.create-system-prompts.sql`, RFC 33),
so the older row is still selectable with no deploy of code.
`bin/prompt-seed:544` emits that rollback line into the generated seed's header.

**`deno fmt` must not touch `prompts/`** — `deno.json:11` lists `"prompts/"` in
`fmt.exclude`, with the reason at `deno.json:5`: line breaks are BODY. A reflow
would silently rewrite the prompt and mint a version (RFC 185 D3).

**Test gate**:
`service/src/test/kotlin/ed/unicoach/coaching/SystemPromptCatalogTest.kt`
asserts every `(name, version)` pin in `service.conf` exists as a catalog row,
and pins tool names and copy constants into the prompt text (`:215` references
`CollegeAdmissionsChatTool.TOOL_NAME`). A slice that adds a tool or new coach
vocabulary should expect to extend this test.

---

## C. The admissions read seam (D5/D6)

**The tool**:
`service/src/main/kotlin/ed/unicoach/coaching/admissions/CollegeAdmissionsChatTool.kt`
(231 lines), registered in production at
`rest-server/.../Application.kt:383-386`, wrapping
`CollegeAdmissionsService(database)`.

**Wire shape.** Input schema is `putCollegeIdsSchema()` (`Tool.kt` helper) — an
optional `college_ids` array; null means the whole active list (`:48-52`,
`:57-61`). Output (`profileObject`, `:84-95`):
`{ colleges: [...], count: N, unknown_college_ids?: [...] }`. Each college
(`collegeObject`, `:102-114`) carries `college_id`, `name`, `city`, `state`,
`list_status`, then **three optional sections in declared wire order** —
`merit_aid`, `admission_factors`, `deadlines` — plus `data_availability: []`
naming the sections this school does not report. The section keys live in one
enum, `AdmissionsField` (`AdmissionsField.kt:18-23`), and **declaration order IS
wire order**.

**Citation.** Each section carries its own citation, not the payload
(`CollegeAdmissionsChatTool.kt:39-40`: "Each of the three sections carries its
own citation"). `CdsCitation` (`CdsCitation.kt:28-33`) =
`collegeName, sourceYear, url,
archiveUrl`; `citedAs` is **computed** ("Amherst
College's 2024-25 Common Data Set", `:35`). The renderer `putCitation`
(`CdsCitation.kt:51-55`) emits `cited_as`, `url`, and omits `archive_url` when
null. Constructed at `CollegeAdmissionsService.kt:125`, `:170`, `:456` from
`row.sourceUrl` / `row.archiveUrl`, which come from the CDS tables' own
`source_url TEXT NOT NULL` / `archive_url TEXT NULL` columns
(`db/schema/0054.create-cds-admissions-tables.sql:129-130`).

**Where a test-policy field and a score RANGE would attach.** The natural home
for a CDS- shaped per-college policy is `college_admission_factors`
(`db/schema/0054.create-cds-admissions-tables.sql:107-134`) — it already holds
`test_scores cds_factor_rating NULL, -- C.704` at `:114`, is
`UNIQUE (college_id,
source_year)` (`:133`) and carries its own
`source_url`/`archive_url`. **But the policy is NOT missing — it is already
stored in `college_ipeds.test_policy` from IPEDS ADMCON7 (see D below)**, and D5
says that is the source — and that table's whole identity is "one CDS filing,
one cycle, one source_url". Putting an IPEDS-sourced value in it would make one
citation cover two publishers, which is exactly the defect RFC 177 killed. **The
existing `college_ipeds` row is the honest home** (a `colleges` column is the
shape only for a Scorecard-sourced percentile band); the wire can still merge
them into one `admission_factors`-adjacent section, but each section must carry
its own citation, per `CdsCitation`'s stated rule (`CdsCitation.kt:8-12`).

Read plumbing to extend: `LatestCdsFacts`
(`CollegeAdmissionsService.kt:347-366`) is the one construct that batches "one
query per table for the whole answer" — its own KDoc says "a fourth table is one
field here instead of a fourth copy of the idiom" (`:353-357`). That is the
seam. Assembly is `admissionsOf` (`:375-392`), and each section factory owns the
"a citation with no facts under it is not data" rule (`:386-388`); absence goes
to `notReported`/`data_availability`, never an empty object.

**FigureStatus / assurance tier (RFC 179) — does a testing figure need one?
NO.** `FigureStatus`
(`db/src/main/kotlin/ed/unicoach/db/models/FigureStatus.kt:11-38`: `REPORTED`,
`IMPUTED_BY_PUBLISHER`, `NOT_REPORTED_BY_INSTITUTION`, `NOT_APPLICABLE`,
`SUPPRESSED_BY_PUBLISHER`, `NOT_COLLECTED_BY_US`) is a **column on the canonical
MONEY fact tables only** — `price_figures` and `cohort_money_stats`
(`db/schema/0083.create-canonical-money-tables.sql`; readers in
`CanonicalMoneyReadDao.kt`, `CanonicalMoneyDao.kt:367-373`). `AssuranceTier`
(`db/src/main/kotlin/ed/unicoach/db/models/AssuranceTier.kt`) is a **pure
function of `(MoneySource, source_variable)`** — RFC 179 summary: "**No
migration, no column, no seeded table, and nothing on `MoneySource`**"
(`rfc/179-assurance-tiers.md:~20`), read as a PAIR with `FigureStatus`. The
admissions tool is outside that seam entirely: it emits ratings, dates and
counts, not `price_figures` rows, and has **no** `FigureStatus` anywhere.

So a test-policy or score-range value is **not** a money figure and must not be
given a `FigureStatus` — it would need a value in a vocabulary table it does not
belong to. Its honesty mechanism is the one the admissions tool already has:
**present-or-named-in- `data_availability`**, with a per-section citation. The
exception is **D8 (score-gated merit dollars)**: if a slice ever puts a dollar
amount into `price_figures`/`cohort_money_stats` it inherits `FigureStatus` +
tier and must map its source; a hand-curated merit grid has no `MoneySource`
member today, and adding one is a compile-level change at the copy seam (RFC
177/179).

---

## D. Ingest (D5/D6)

### D5 — test policy is NOT a new fetch, and NOT a new ingest. It is already live.

**This corrects the brief.** The brief's table says test policy is "**Absent**"
and D5 says ADMCON7 is a new IPEDS ingest. Both are wrong against the code:

- **The ADM survey is already fetched.** `bin/fetch-ipeds:258` —
  `Artifact("ADM2023.zip", "adm2023.csv", "ipeds-adm2023-fixture.csv", "-A", SURVEY_YEAR),`
  (one of six artifacts, `bin/fetch-ipeds:256-274`: HD2023, IC2023, ADM2023,
  C2023_a, IC2023_AY, SFA2223). The extracted CSVs are gitignored
  (`.gitignore:41`); only `db/seed/ipeds/PROVENANCE.json` is committed.
- **ADMCON7 is already the ONLY thing ADM is read for.**
  `college/src/main/kotlin/ed/unicoach/college/IpedsLoader.kt:992` —
  `private const val COL_ADMCON7 = "ADMCON7"`; `:1032` —
  `internal val REQUIRED_ADM_COLUMNS = listOf(COL_UNITID, COL_ADMCON7)`. Mapped
  in `mapAdm` (`IpedsLoader.kt:540-559`), which keeps only codes in
  `TEST_POLICY_CODES` and logs a coercion for anything else.
- **It is stored raw**, per the schema convention:
  `db/schema/0055.create-college-ipeds-and-programs-census.sql:42` —
  `test_policy SMALLINT NULL, -- ADM.ADMCON7 1 req / 3 blind / 5 optional`, with
  `CHECK (test_policy IS NULL OR test_policy IN (1, 3, 5))` (`:65-66`). Written
  by `CollegeIpedsDao.kt:72/100/155`.
- **It already has a human vocabulary.** `admission_test_policies`
  (`db/schema/0060.create-codebook-reference-tables.sql:292-299`) maps code ->
  slug; the header at `:287-289` says: "The slugs are the words humans already
  use — `'required'`, `'test-blind'`, `'test-optional'`." Seeded from
  `db/data/codebooks.json` (`:2220`), generated by
  `bin/fetch-codebooks:717-739`.
- **It is already in the search index and already exposed to the coach as a
  FILTER.** `db/schema/0064.create-search-index-and-subjects.sql:141-142` —
  `test_policy slug NULL REFERENCES admission_test_policies (slug)`, filled by
  the `LEFT JOIN admission_test_policies pol ON pol.code = ci.test_policy` at
  `db/src/main/kotlin/ed/unicoach/db/dao/CollegesDao.kt:3000`; filtered at
  `CollegesDao.kt:702-703`; parsed from the model's words at
  `college/.../CollegeQueryVocabulary.kt:271-272`; and described to the model at
  `college/src/main/kotlin/ed/unicoach/college/CollegeSearchTool.kt:243-244`:
  "`test_policy` (whether admission tests are required, optional or not
  considered)".

**So what is actually missing for D5 is a READ, not an ingest.** The coach can
_search_ for test-optional schools but cannot _ask_ what a named school on the
student's list requires: `college_admissions_profile` emits only `merit_aid`,
`admission_factors`, `deadlines` (`AdmissionsField.kt:18-23`) and never touches
`college_ipeds`. The slice is: add a fourth section (or a field) to
`CollegeAdmissionsService` — a fourth `LatestCdsFacts`-style read
(`CollegeAdmissionsService.kt:347-366`) against `college_ipeds` — with its own
IPEDS citation, because a CDS `CdsCitation` would name the wrong publisher.
**Zero DDL, zero fetch.**

Caveat the spec must state: the corpus is **ADM2023**, i.e. the 2023 collection
year, and FairTest's 2026-27 survey moved since. The policy we speak is what
IPEDS recorded, cited as such and dated, or it is a stale assertion of exactly
the kind briefs 0008/0009 exist to prevent.

### D6 — the percentile bands

**The Scorecard institution CSV is NOT committed.** `.gitignore:54` ignores
`db/seed/scorecard/*`;
`git check-ignore -v db/seed/scorecard/Most-Recent-Cohorts-Institution.csv`
confirms. Only `PROVENANCE.json`, `FETCH-NOTES.md` and
`dictionary-variable-sources.csv` are committed. The file is present locally,
fetched by `bin/fetch-scorecard` (release `06102026`). The brief's phrase
"already sitting unused in the committed Scorecard seed CSV" is **half wrong**:
the data is in hand and is free, but it is a fetched artifact, not a committed
one. The only committed Scorecard CSVs are test fixtures, e.g.
`college/src/test/resources/scorecard-institutions-real-fixture.csv` (4 rows).

**All six columns exist.** Header positions (0-based, 3308 columns):
`SATVR25`=38, `SATVR75`=39, `SATMT25`=40, `SATMT75`=41, `ACTCM25`=47,
`ACTCM75`=48, `SAT_AVG`=59, `ADM_RATE`=36, `ADMCON7`=2392.

**Real non-null counts**, parsed over the whole file (nulls = empty, `NA`,
`NULL`, `PrivacySuppressed`), **6273 data rows**:

| Column                                               | Non-null | %     |
| ---------------------------------------------------- | -------- | ----- |
| SATVR25 / SATVR75 / SATMT25 / SATMT75                | 949 each | 15.1% |
| ACTCM25 / ACTCM75                                    | 920 each | 14.7% |
| SAT_AVG (already ingested)                           | 1037     | 16.5% |
| ADM_RATE                                             | 1910     | 30.4% |
| ADMCON7 (Scorecard copy; unused — we read IPEDS ADM) | 1936     | 30.9% |

**Coverage finding the spec must carry:** the percentile bands cover **fewer**
colleges (949/920) than `SAT_AVG` does (1037), and **about half** of what test
policy covers (1936). So D6 cannot simply replace the average everywhere — for
~88 colleges the average exists and the band does not. The rule "prefer the
range" needs a stated fallback: say the band where we have it, say nothing where
we do not, and do **not** silently fall back to the average under a range's
framing.

**What it takes to surface them.** `colleges` columns are the pattern; follow
`SAT_AVG`:

1. **Schema** — born as `sat_avg INTEGER NULL, -- SAT_AVG` at
   `db/schema/0015.create-colleges.sql:48` with a `>= 0` CHECK at `:71`; renamed
   by `db/schema/0059.name-numeric-columns-by-unit.sql:54`.
2. **Loader** — `CollegeScorecardLoader.kt:1759` (`COL_SAT_AVG`), listed in
   `REQUIRED_INSTITUTION_COLUMNS` (`:1806`, `:1820`) so a missing column is
   **fatal before any write**, mapped at `:1571` via `intInDomainOrNull(...)`.
3. **Models** — `db/.../models/College.kt:33` and `NewCollege.kt:26`.
4. **DAO** — `CollegesDao.kt:187` (read), `:343/:359/:367/:373` (upsert column
   list, EXCLUDED set, change detection).
5. **Search index** — the RAW score is deliberately **not** carried; only a
   derived percentile is (`db/schema/0064...:184-190`,
   `sat_average_percentile_share` with a `BETWEEN 0 AND 1` CHECK at `:197-198`,
   computed by `percent_rank()` at `CollegesDao.kt:3128`, written at `:3142`).

**A new migration is required, and the versioning trigger MUST be restated
again.** `db/schema/0094.drop-publisher-money-columns.sql:69-89` restates
`log_college_version()` in full, enumerating **both** the `colleges_versions`
column list and the `NEW.*` value list. A new `colleges` column not added to
that body is simply never versioned. `0094:66-68` states the trap verbatim:

> "A plpgsql body is stored as TEXT and is not dependency-tracked, so DROP
> COLUMN neither fails nor rewrites it: a stale body passes this migration and
> then kills the very next write to `colleges` with
> `42703 record "new" has no field ...`".

So one migration, one transaction, **three** statements:
`ALTER TABLE colleges ADD COLUMN
...`,
`ALTER TABLE colleges_versions ADD COLUMN ...` (the mirror maintained since
0023), and `CREATE OR REPLACE FUNCTION log_college_version()` with the new names
on both lists. Use `CREATE OR REPLACE`, never DROP/CREATE — `0094:67`: "the
existing `trigger_04_log_college_version` binds by name and must not be re-wired
(the 0023/0045/0051/0057/0059/0062 pattern)". The trigger itself is
`CREATE TRIGGER trigger_04_log_college_version` at
`db/schema/0023.version-colleges.sql:189` and has never been re-created since.
Add-a-column precedents:
`db/schema/0045.add-college-income-band-net-price.sql:33`,
`db/schema/0062.add-college-cost-components.sql:80/:96`.

**Naming, per section E: the new file is a SLUG, not `0095.*`** — e.g.
`add-college-test-score-percentiles.sql`, plus its `db/schema/ORDER` line. RFC
180 D5 retired the four-digit prefix; only the legacy committed files keep
theirs. (The ingest scan initially proposed `0095.*.sql`; that grammar is
refused by `db-migrate`.)

**Six columns or one alternative:** `IpedsLoader.kt:1032`'s
`REQUIRED_ADM_COLUMNS` could instead be widened to read IPEDS ADM's own SAT/ACT
percentile fields into `college_ipeds` — also no new download, and it keeps the
score bands beside the policy under one publisher. Which of the two is right is
a spec decision; both are cheap, and the second avoids touching `colleges` and
its trigger at all.

### `bin/ingest-colleges` for context

A thin bash launcher: one `getopts` grammar, args resolved (local or `s3://`),
then exec of the prebuilt installDist launcher, never Gradle —
`bin/ingest-colleges:388` (`LAUNCHER="$PROJECT_ROOT/$COLLEGE_DIST/bin/college"`)
and `:394`. Entrypoint `main` at
`college/src/main/kotlin/ed/unicoach/college/IngestApplication.kt:515`, which
calls one orchestrator at `:534`:
`CollegeScorecardLoader(database).ingest(institution=, fields=, aliasesFile=, ipeds=, sfa=, cds=, codebooks=, subjects=, moneyVocabulary=)`.

| Loader                                                       | file:line                      | Loads                                                                         |
| ------------------------------------------------------------ | ------------------------------ | ----------------------------------------------------------------------------- |
| `CollegeScorecardLoader`                                     | `CollegeScorecardLoader.kt:59` | Scorecard institution + field-of-study CSVs -> `colleges`, `college_programs` |
| `CodebookLoader` / `SubjectLoader` / `MoneyVocabularyLoader` | `:70` / `:46` / `:52`          | `db/data/*.json`                                                              |
| `CdsSeedLoader`                                              | `CdsSeedLoader.kt:102`         | CDS seed CSVs (`-m/-a/-d/-p`)                                                 |
| `IpedsLoader`                                                | `IpedsLoader.kt:113`           | IPEDS HD + IC + **ADM** + C_A -> `college_ipeds`, `college_programs_census`   |
| `IpedsChargesLoader`                                         | `IpedsChargesLoader.kt:120`    | IPEDS IC_AY -> `college_ipeds_charges`                                        |
| `CollegeSfaLoader`                                           | `CollegeSfaLoader.kt:292`      | IPEDS SFA -> `college_sfa`                                                    |
| `CanonicalMoneyLoader`                                       | `CanonicalMoneyLoader.kt:92`   | `price_figures`, `cohort_money_stats`                                         |

Derived phases run last: `name-words` -> `canonical-money` -> `search-index` ->
`provenance` (`CollegeScorecardLoader.kt:862` ->
`CollegesDao.rebuildSearchIndex`, `CollegesDao.kt:2892`). **Any new `colleges`
column or index field needs a full re-ingest before a family sees it.**

---

## E. Migrations — the CURRENT rule (RFC 180)

Numbers are gone. `rfc/180-migration-order-manifest.md` D5: "**New migrations
are `<kebab-slug>.sql`**; existing files are untouched forever." Two grammars
are accepted by `db-migrate`: legacy `^[0-9]{4}\.<slug>\.sql$` (immutable,
committed) and new `^<slug>\.sql$` where `<slug>` is
`[a-z0-9]([a-z0-9-]*[a-z0-9])?`. **No timestamps** — "the same predicted-order
mistake with more digits" (180 D5).

`db/schema/ORDER` (D1) is a plain list of filenames, one per line, in apply
order — no numbers, no comments, no blank lines, no header, trailing newline. It
lives inside `$DB_SCHEMA_DIR` (`bin/common:95`). `cat db/schema/ORDER` **is**
the apply order. Current tail: `0094.drop-publisher-money-columns.sql`, then the
four unnumbered `seed-coach-system-prompt-v24..v27.sql` files.

`db-migrate` walks `ORDER` and **fatals before the first `BEGIN`** on any drift:
a `*.sql` on disk absent from `ORDER`, an `ORDER` line with no file, a duplicate
line, a blank line, a missing/empty `ORDER` with `*.sql` present, or an illegal
filename (`180` D2; exit `EXIT_SCHEMA_ORDER_DRIFT`). `db-status` reports the
same drift as payload (`[UNORDERED]`, `[MISSING]`, `[UNKNOWN]`) instead of
refusing (D3). `schema_migrations` keys on `filename`; `version_id` is dropped
(D4).

**Who appends.** The implementing run appends its own line when it adds the
migration (its tests must pass during implementation). **Land re-places it**:
`scripts/ship-order -s <rs>` runs between `ship-rebase` and `ship-squash`
(`SKILL.md:296`, RFC 180 D6) and rewrites `ORDER` as the base branch's `ORDER`
verbatim + this run's own lines. `ship-land` re-runs it as `-n` and refuses with
`ship-order`'s own status (3 stale, 1 corrupt) (`SKILL.md:300-308`).
`.gitattributes` marks `db/schema/ORDER` `merge=union` (D7) — **do not resolve
`ORDER` by hand and do not reorder landed lines** (`SKILL.md:359-365`).

---

## F. Residency (D10)

Stored on the **money profile**, not on the student:
`db/schema/0046.create-money-profiles.sql:38-55`:

```sql
residency_state    TEXT     NULL,
residency_status   TEXT     NOT NULL DEFAULT 'unanswered',
CONSTRAINT money_profiles_residency_state_format_check
    CHECK (residency_state IS NULL OR residency_state ~ '^[A-Z]{2}$'),
CONSTRAINT money_profiles_residency_status_check
    CHECK (residency_status IN ('unanswered','answered','declined')),
...
    CHECK ((residency_state IS NOT NULL) = (residency_status = 'answered'))
```

So: **a two-letter uppercase USPS code, or NULL, and a value exists exactly when
the status is `answered`.** The status tri-state is the Kotlin enum
**`AnswerStatus`**
(`db/src/main/kotlin/ed/unicoach/db/models/AnswerStatus.kt:12-22`):
`UNANSWERED("unanswered")`, `ANSWERED("answered")`, `DECLINED("declined")`, with
`fromValue`. There is **no** state enum — `residencyState` is a plain `String?`
on the model (`db/src/main/kotlin/ed/unicoach/db/models/MoneyProfile.kt:26-27`).
A slice that keys a ~50-row state table must therefore validate the code itself;
the schema's only guarantee is the `^[A-Z]{2}$` regex, which admits `XX`.

**It reaches `composeSystem` today**, rendered verbatim —
`CoachingService.kt:886-889`:

```kotlin
appendLine(
  "- state of residency: " +
    renderMoneyField(moneyProfile.residencyStatus, moneyProfile.residencyState, "residency_state", moneyProfile),
)
```

`renderMoneyField` (`:914-940`, KDoc `:907-913`) renders `answered (CA)` |
`declined` | `unanswered`, and throws `CorruptPersistedValueException` for
`ANSWERED` with a null value. Note the prompt therefore already carries the raw
two-letter code to the model, unlike income band and living plan, which are
deliberately de-jargoned (`:871-885`, `:895-900` — RFC 142/152 comments).

---

## G. RFC number — computed live

**Next free RFC number is `186`.**

- `ls rfc/*.md` on `main`: max numeric prefix = **185**
  (`185-the-coach-prompt-is-a-markdown-file.md`).
- All local + remote refs (`git for-each-ref refs/heads refs/remotes`,
  `git ls-tree rfc/`): max = **185**.
- All live worktrees (`git worktree list`): `unicoach-rfc-178` max **178**;
  `unicoach-code-review-skills` **107**;
  `unicoach-quality-centralize-primitives` **94**; the three
  `.claude/worktrees/*` max **109**. None exceeds 185.

Nothing in flight claims 186 as of this scan. `/ship` claims the number live at
design time; re-check before use if another run lands first.

---

## H. Traps a slice author would get wrong

1. **The early return in `composeSystem`** (`CoachingService.kt:851`). An
   always-present block does not work inside the `buildString` alone. See A.
2. **`postTurn` passes empty lists deliberately** (`:330-334` comment):
   commitments and fit suggestions open a conversation only. A date/grade block
   must be composed on **both** paths — like the money block, which the comment
   explicitly says "IS composed on every turn".
3. **The tri-state parse ladder is NOT duplicated in code — it is duplicated in
   SCHEMA.** `AnswerStatus.fromValue` has exactly one decode site,
   `db/src/main/kotlin/ed/unicoach/db/dao/MoneyProfilesDao.kt:68`. What repeats
   is the three-CHECK pattern per field in SQL:
   `<x>_status IN ('unanswered','answered','declined')` plus the
   `value IS NOT NULL) = (status = 'answered')` pairing, written once per column
   (`0046:38-55` for residency, `0070.add-money-profile-living-plan.sql` for
   living plan, which says so at `:4`). **A new invited/declinable field (D12,
   stored test scores) must restate both CHECKs**, and the renderer must go
   through `renderMoneyField` (`CoachingService.kt:914-940`) rather than a
   fourth hand-written ladder — a bare `status == ANSWERED` check would silently
   render `null` into the prompt.
4. **DDL is a human gate.** D7, D10 and D12 each add a table. `/ship` presents
   DDL at its approval gate (brief 0010:203, "standing D10"); do not land schema
   a human did not see. Schema conventions (CLAUDE.md): own enumerations are
   `TEXT` + `CHECK IN (...)` plus one Kotlin `enum class Foo(val value: String)`
   with `fromValue`; source-defined codes (IPEDS `ADMCON7`) are **stored raw as
   the source publishes them**, with labels only at the tool/API boundary;
   **derived figures are never stored** — so "is this student a junior" is
   computed at read time from the graduation date and today, never a column.
5. **Async: none of this is queue work.** `ASYNC_WORK.md:15-25` defaults
   third-party network I/O and latency-unbounded side-effects to the queue.
   Slice 1 is an in-transaction DB read plus string building on an existing
   request path; the calendar/policy slices are DB reads of seeded rows.
   **Nothing here is async-capable**, so nothing may be enqueued for
   convenience. D11 defers reminders precisely because a dated nudge WOULD be
   queue work (the `SynthesisService` / periodic-task machinery, RFC 97) and a
   promise the product must keep.
6. **`bin/` conventions if a slice adds a fetch/seed script** (CLAUDE.md,
   invariants): option parsing is **`getopts`, short options only** — no
   hand-rolled `while`/`case`, no long options; reserved usage exits come from
   `bin/functions` (`EXIT_UNKNOWN_OPTION` 10, `EXIT_OPTION_REQUIRES_VALUE` 11,
   `EXIT_UNEXPECTED_ARG` 20, `EXIT_MISSING_REQUIRED_ARG` 21,
   `EXIT_INVALID_ARG_VALUE` 22); a python script uses the stdlib `getopt`. **All
   logging goes to stderr**; stdout carries only what the caller asked for, so a
   script whose product is files writes nothing to stdout — and that emptiness
   is worth asserting. `bin/prompt-seed` additionally must parse under **macOS
   bash 3.2** (`bin/prompt-seed:64-71`).
7. **Never hand-write the coach seed migration or the `service.conf` pin.** See
   B. Also never run `deno fmt` expecting it to touch `prompts/` — it is
   excluded, and a reflow would rewrite the prompt body.
8. **`Clock.systemUTC()` is the repo default and "today" is not UTC-today for a
   US family.** Unresolved in the code; the spec must state the timezone rule.
9. **Testing.** CLAUDE.md: default to the **full** `nix develop -c bin/test`
   with no module and no `-f`. `bin/test check` (the hook) also runs
   `bin/shell-tests`; a `bin/` edit can look green under bare `bin/test` and
   fail at the hook.
10. **The prompt-version label is claimed at LAND**, not at design. Do not write
    "v28" into an RFC as a fact — write it into the report after
    `bin/prompt-seed` has run.
