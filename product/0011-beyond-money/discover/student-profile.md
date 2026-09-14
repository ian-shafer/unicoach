# DISCOVER — student profile: storing intended fields (D5/D15/D16/D17) and D6's inputs

Verification only, against the code on `main`, 2026-09-11. Every claim was read
from the cited file. Two areas (iOS/REST seams, Scorecard subgroup columns) were
read by delegated workers and spot-checked against the files before being
repeated here.

---

## Corrections

### C1 (blocking for D6) — the Scorecard income cut points do NOT match `IncomeBand`

D6 says "reuse `money_profiles` income band and dependency. No new student
field." The income half works only after a rewording.

| `IncomeBand` (`db/src/main/kotlin/ed/unicoach/db/models/IncomeBand.kt:23-36`) | bracket text served today | Scorecard completion group | distinct figure? |
| ----------------------------------------------------------------------------- | ------------------------- | -------------------------- | ---------------- |
| `under_30k`                                                                   | `$0 to $30,000`           | `LO_INC_`                  | yes              |
| `30k_to_48k`                                                                  | `$30,001 to $48,000`      | `MD_INC_`                  | **shared**       |
| `48k_to_75k`                                                                  | `$48,001 to $75,000`      | `MD_INC_`                  | **shared**       |
| `75k_to_110k`                                                                 | `$75,001 to $110,000`     | `HI_INC_`                  | **shared**       |
| `over_110k`                                                                   | `$110,000 or more`        | `HI_INC_`                  | **shared**       |

Scorecard publishes completion by three FAFSA family-income groups ($0–30,000 /
$30,001–75,000 / $75,001+), not five. The `IncomeBand` cuts are the **NPT4N
net-price** brackets — a different publisher series, documented as such in the
enum KDoc (`IncomeBand.kt:23,26,29,32,35`). The two schemes nest cleanly (30k
and 75k are boundaries in both), so no stored band straddles a Scorecard cut,
but **five stored answers collapse to three served figures**. Rendering
`IncomeBand.bracket` beside an `MD_INC_` figure would label a $30k–$75k number
"$48,001 to $75,000" — a false attribution, and exactly the class of error D3
forbids. D6 must serve the Scorecard tercile label, not the stored band's.

### C2 (blocking for D6) — `dependency` is a near-noise axis, and the coach cannot see it

- The columns exist: `DEP_COMP_ORIG_YR{2,3,4,6,8}_RT` / `IND_COMP_ORIG_YR*_RT`,
  1:1 onto `money_profiles.dependency`
  (`db/schema/0078.add-money-profile-dependency-status.sql:28-33`).
- They are the **thinnest** subgroup family: 70.9% of predominantly-bachelor's
  institutions, against 79–82% for Pell / first-gen / income.
- Measured on the 1,380 bachelor's schools carrying both: median `DEP − IND` gap
  **+2.7 pp**, p25–p75 **−3.9 to +9.3 pp**, gap positive at only **60.4%** of
  schools. `HI_INC − LO_INC` is **+12.3 pp**, positive at **94.9%**.
- Separately: `dependency` is **not rendered into the coach system prompt**.
  `composeSystem` renders income band, residency and living plan only
  (`service/src/main/kotlin/ed/unicoach/coaching/CoachingService.kt:868-903`);
  the field is read only by `FederalAidPolicyService`
  (`service/.../coaching/aid/FederalAidPolicyService.kt:210`).

Recommendation: D6's second axis should be **Pell**, not dependency.

### C3 — "already in the repo" is false: the Scorecard and IPEDS bulk files are gitignored

The brief's PRIORITISE synthesis (point 3) says "`db/seed/ipeds/adm2023.csv` is
committed in-tree" and "Seven of eleven recommendations are 'read a column from
a file already in the repo'".

| Path                                                                                               | On disk here | Tracked by git              |
| -------------------------------------------------------------------------------------------------- | ------------ | --------------------------- |
| `db/seed/ipeds/*.csv` (adm2023, C2023_a, HD2023, IC2023, ic2023_ay, sfa2223)                       | yes          | **no** — `.gitignore:41-43` |
| `db/seed/scorecard/Most-Recent-Cohorts-Institution.csv` (100,102,932 B, 6,273 rows, 3,308 cols)    | yes          | **no** — `.gitignore:54-56` |
| `db/seed/scorecard/Most-Recent-Cohorts-Field-of-Study.csv` (153,479,459 B, 227,980 rows, 178 cols) | yes          | **no** — `.gitignore:54-56` |
| `db/seed/scorecard/{PROVENANCE.json,FETCH-NOTES.md,dictionary-variable-sources.csv}`               | yes          | yes                         |

`git ls-files db/seed` returns only the CDS csvs, the codebook zips, and the
provenance/notes files. The bulk data is a **local fetch artefact**
(`bin/fetch-scorecard`). A slice must not plan around "the file is in the tree";
it must plan around "the fetch script already knows how to get it".

### C4 — the tri-state parse ladder is NOT duplicated in Kotlin

`product/STATUS.md:1973-1977` says "the tri-state parse ladder now exists in
three copies across two surfaces… the extraction belongs on
`StudentScopedChatTool`". Against the code today that is stale:

| Seam            | Reality                                                                                                                      |
| --------------- | ---------------------------------------------------------------------------------------------------------------------------- |
| Decode          | one site — `AnswerStatus.fromValue` at `db/src/main/kotlin/ed/unicoach/db/dao/MoneyProfilesDao.kt:63-70`                     |
| Chat-tool parse | one generic — `getDeclinableUpdate`, `service/.../coaching/MoneyProfileChatTool.kt:208-247`, called four times at `:249-262` |
| Domain fold     | one site — `mapFieldUpdate`, `service/.../coaching/moneyprofile/MoneyProfileService.kt:130-135`                              |
| SQL fold        | one site — `fieldValue`/`fieldStatus`, `MoneyProfilesDao.kt:163-176`                                                         |
| Prompt render   | one site — `renderMoneyField`, `CoachingService.kt:914-935`                                                                  |

What _is_ repeated is the **SQL**: three CHECKs per field, hand-written per
column (`0046:41-55`, `0070:23-31`, `0078:31-38`).
`product/0010-when-do-i-test/discover.md:531-543` already recorded this
correction; brief 0011 should inherit it rather than re-discover it.

### C5 — the REST money-profile surface already lags the schema by one field

`money_profiles` has four fields; `PublicMoneyProfile`
(`rest-server/src/main/kotlin/ed/unicoach/rest/models/MoneyProfileResponse.kt:13-23`)
and `UpdateMoneyProfileRequest` (`.../UpdateMoneyProfileRequest.kt:9-19`) expose
three — `dependency` is absent from both.
`YourDetailsViewModel.MoneyProfileField`
(`ios-app/UnicoachiOS/YourDetailsViewModel.swift:8-11`) exposes **two**; the
transport carries `livingPlan` but the screen does not ask it. So D17's "the
same field on both doors" is a **new** commitment, not the existing pattern:
today the chat tool and the screen do NOT expose the same set.

### C6 — the field-of-study CIPCODE is _always_ 4 digits, and `college_programs` is already at D15's grain

- `Most-Recent-Cohorts-Field-of-Study.csv`: 227,980 rows, `CIPCODE` string
  length is **4 for every row** (computed from the file).
- `college_programs.cip_code` takes it raw
  (`college/src/main/kotlin/ed/unicoach/college/CollegeScorecardLoader.kt:1604,1646`);
  the format CHECK admits 2/4/6 digits
  (`db/schema/0021.relax-college-programs-cip-format.sql:15-19`). So
  `college_programs` holds **4-digit families** today.
- `college_programs.cip_title` **is** `CIPDESC`
  (`db/schema/0015.create-colleges.sql:109`;
  `CollegeScorecardLoader.kt:1605,1788,1838`). D15's claim "`CIPDESC`, already
  loaded into `college_programs`" is **TRUE**.
- Stale-comment note: `0021...sql:3-6` asserts the file "encodes codes at
  2-digit series, 4-digit family and 6-digit detail granularity". Not true of
  the current release; harmless, but do not design against that sentence.

### C7 — the counts in D15 and D19 are slightly off

| Brief claim                                 | Measured                                                                                                                                        |
| ------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| D15: "311 covered families"                 | **391** distinct 4-digit families at `CREDLEV=3`; **308** have a non-suppressed `EARN_MDN_HI_2YR`; **306** have `DEBT_ALL_STGP_EVAL_MDN10YRPAY` |
| D19: "61.5% of bachelor's rows are `PS`"    | 39.2% of the 71,664 bachelor's rows carry an earnings figure, i.e. **60.8% suppressed** — close enough to stand                                 |
| D12: `DEBT_ALL_STGP_EVAL_MDN10YRPAY` exists | **TRUE**, present in the 178-column header                                                                                                      |

### C8 — citation drift in "Foundations confirmed for D21/D22"

`college_search_index.cip_codes TEXT[]` / `subject_slugs slug[]` are at
`db/schema/0064.create-search-index-and-subjects.sql:178-179` (the cited
`:169-177` is the comment block above them). Column comments at `:222-231`.
`subjects.cip_prefixes` is at `:48-63` as cited — correct.

### C9 — the `subjects` taxonomy is 181 entries, and it is 2/4/6-digit mixed

`db/data/subjects.json` holds **181** subjects, each with `cip_prefixes` of 2, 4
or 6 digits (e.g. `neuroscience` = `["2615","3010","422706"]`). Loaded to
`subjects` (`0064:48-63`), proven against `cip_codes` fatally by `SubjectLoader`
(`college/.../IngestApplication.kt:662`). `cip_codes` itself is **six-digit**,
1,710 rows (`db/schema/0060.create-codebook-reference-tables.sql:307-329`), from
IPEDS C2023_A — a **different** vocabulary from the 391 4-digit Scorecard
families.

### C10 — the vocabularies endpoint is not built for a 300–1,700-entry list

D15 says "serve it through `GET /api/v1/vocabularies`, exactly as income band
and residency are served". That works at the contract level and fails at the
shape level:

| Property             | State                                                                          | Evidence                                                                                  |
| -------------------- | ------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------- |
| Registry             | `List<(SqlSession) -> Result<Vocabulary>>`, 2 suppliers; a third is ~1 line    | `service/src/main/kotlin/ed/unicoach/vocabulary/VocabularyService.kt:107-111,142-143`     |
| Entry shape          | `value`, `label`, free-form `extras`; **no order field** — order is list order | `VocabularyService.kt:20-23`; `rest-server/.../models/VocabulariesResponse.kt:9-11,33-37` |
| Paging / filter      | **none** — handler takes zero query params                                     | `rest-server/.../routing/VocabularyRoutes.kt:41-51`                                       |
| Server cache         | **none** — every GET re-runs both suppliers and re-hashes the whole document   | `VocabularyService.kt:120-125,257-293`                                                    |
| HTTP cache header    | **none, deliberately**                                                         | `VocabularyService.kt:248-256`                                                            |
| Response compression | **no `Compression` plugin installed**                                          | `rest-server/.../Application.kt` (absent)                                                 |
| Client cache         | **none** — refetched per screen appearance                                     | `ios-app/UnicoachiOS/VocabularyClient.swift:19-23`                                        |
| Today's payload      | 5 bands + 59 jurisdictions = 64 entries                                        | `rest-server/src/test/.../VocabularyRoutingTest.kt:146`                                   |

The registry KDoc states the intended contract in its own words: "a **closed,
small**, user-independent, display-facing list" (`VocabularyService.kt:82-84`).
391 entries is ~25–30 KB uncompressed on every screen open; 1,710 is ~90–140 KB.
Also, the iOS degrade rule discards the **whole** served list if one entry fails
to map (`YourDetailsViewModel.swift:308-324,334-350`) and falls back to a
hand-written Swift list (`ResidencyStates.swift:62-114`) — there is no plausible
shipped CIP fallback.

### C11 — a multi-select is NOT cheap on "Your details"

D17 and the D5 amendment read as if adding a multi-valued field is another
`0078`-shaped column add. It is not.

| Layer       | What exists                                                                                                                                                               | What a 0..3 set + `undecided` needs                                                                              |
| ----------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------- |
| DB          | four scalar columns, `value IFF answered` CHECK per field (`0046:41-55`, `0078:31-38`); history writer copies columns explicitly (`0078:49-60`)                           | child table or array column + its own history machinery; a 4th status value in 2 CHECKs                          |
| Kotlin enum | `AnswerStatus{UNANSWERED,ANSWERED,DECLINED}` (`db/.../models/AnswerStatus.kt:11-17`)                                                                                      | a 4th member; every `when` over it re-checked                                                                    |
| Write model | `MoneyProfileUpsert.FieldWrite{Answer,Declined,Cleared}` (`db/.../models/MoneyProfileUpsert.kt:23-31`), `FieldUpdate{Set,Decline,Clear}` (`MoneyProfileService.kt:20-28`) | a set-shaped variant; `Clear` already means "back to unanswered", which D17 forbids for this field               |
| REST wire   | one value key + two booleans per field, mutually exclusive, 400 on >1 (`UpdateMoneyProfileRequest.kt:9-19`)                                                               | a new grammar (`fieldsOfStudy: [..]` + `…Declined` + `…Undecided`)                                               |
| iOS model   | `FieldAnswer{unanswered, answered(String), declined}` (`YourDetailsViewModel.swift:30-34`); `DetailOption<Value>?` single selection (`:145-150,184-185`)                  | parallel `[DetailOption]` world                                                                                  |
| iOS view    | `DSPickerRow` is `.pickerStyle(.menu)`, single `selection` binding (`DesignSystem/Components.swift:560-676`)                                                              | searchable picker, chip row, per-chip remove, cap state — **no chip/token/search/multi-select component exists** |
| Tests       | 23 VM tests, 16 client tests, 3 your-details snapshot scenes                                                                                                              | ~5–6 new scenes plus the picker sheet as its own scene                                                           |

Also: the in-flight write claim is **per field**, not per value
(`YourDetailsViewModel.swift:192,422-424`), and a second action on a field with
an open write is silently dropped today — which is precisely the add/remove
pattern a chip UI produces.

### Not a correction, but worth recording

`AnswerStatus`'s KDoc (`AnswerStatus.kt:4-6`) still says "three columns… third
by RFC 152"; there are four since RFC 159. Cosmetic.

---

## Verified foundations

### The tri-state pattern, end to end

| Seam                                                                                                          | Path:lines                                                                                                                     |
| ------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| Entity table + 4 tri-state pairs                                                                              | `db/schema/0046.create-money-profiles.sql:18-59`                                                                               |
| Partial unique index (one active row per student)                                                             | `0046:58-59`                                                                                                                   |
| History table                                                                                                 | `0046:65-81`                                                                                                                   |
| History writer, redefined per added field                                                                     | `0046:83-95`, `0070:42-56`, `0078:49-63`                                                                                       |
| Five triggers (00 delete-guard, 00a immutable, 00b row_created_at, 01 versioning, 03 updated_at) + 04 history | `0046:103-134`                                                                                                                 |
| Shared trigger functions                                                                                      | `db/schema/0000.shared-functions.sql:1-55`                                                                                     |
| "Add a field" precedent (the one to copy)                                                                     | `db/schema/0070.add-money-profile-living-plan.sql:20-56`, `0078:28-63`                                                         |
| Kotlin row model                                                                                              | `db/src/main/kotlin/ed/unicoach/db/models/MoneyProfile.kt:21-40`                                                               |
| Status enum                                                                                                   | `db/.../models/AnswerStatus.kt:11-22`                                                                                          |
| DAO (upsert `ON CONFLICT`, apply-or-keep flags)                                                               | `db/.../dao/MoneyProfilesDao.kt:198-248`                                                                                       |
| Corrupt-value discipline                                                                                      | `MoneyProfilesDao.kt:63-117`                                                                                                   |
| Domain service (single owner for REST + chat)                                                                 | `service/.../coaching/moneyprofile/MoneyProfileService.kt:85-135`                                                              |
| REST routes                                                                                                   | `rest-server/.../routing/MoneyProfileRoutes.kt:46-49` (`GET`/`PUT /api/v1/students/me/money-profile`), parse+400 at `:133-204` |
| Prompt render                                                                                                 | `service/.../coaching/CoachingService.kt:868-903,914-935`                                                                      |
| Admin read surface (reads every column, incl. history)                                                        | `admin-web/.../resources/MoneyProfilesResource.kt:43,71-72,133`                                                                |

Note: there is **no caller-supplied OCC version** on the write path
(`MoneyProfileService.kt:76-83`); each field update is an absolute statement.

### The per-student child-table precedent

`college_list_entries` (`db/schema/0024.create-college-list.sql:26-125`) is the
only per-student child entity, and `money_profiles` says in its own header that
it "mirrors 0024 exactly" (`0046:7-12`). Its shape: uuidv7 PK, `version`,
4-timestamp split, `deleted_at` soft delete, partial unique index on the natural
key, a `_versions` table, a `log_*_version()` writer, six triggers. A
`student_intended_fields` child table would be the **third** instance of exactly
this shape.

### The chat-tool seam a new tool must implement

| Requirement                                                           | Path:lines                                              |
| --------------------------------------------------------------------- | ------------------------------------------------------- |
| Extend `StudentScopedChatTool`; implement `execute(studentId, input)` | `service/.../coaching/StudentScopedChatTool.kt:33-39`   |
| Never throw — return `{"error": ...}` via `errorObject`               | `:41-42`, `:196-206`                                    |
| Reject unknown input keys                                             | `:44-52` (`unknownFieldsReason`)                        |
| Strict optional reads (`Present`/`Absent`/`Mismatch`)                 | `:54-97`                                                |
| Derive the reject-list from the published schema, never a second list | `MoneyProfileChatTool.kt:104-114`                       |
| One generic declinable-field parse                                    | `MoneyProfileChatTool.kt:208-247`                       |
| Decline flag is literal `true`; `false` is malformed                  | `MoneyProfileChatTool.kt:227-229`, schema at `:317-330` |
| Value + decline in one call is an error                               | `MoneyProfileChatTool.kt:230-232`                       |
| Echo the full post-write profile                                      | `MoneyProfileChatTool.kt:271-286`                       |
| Ethos rides the tool description verbatim                             | `MoneyProfileChatTool.kt:295-307`                       |
| Refusal detection for offer attachment                                | `StudentScopedChatTool.kt:199-206` (`isToolRefusal`)    |

The tool-side echo is still hand-maintained in two files
(`MoneyProfileChatTool.profileObject` and `CollegeCostChatTool`) —
`product/STATUS.md:1983-1986` records this and it is still true.

### CIP / subject vocabularies available today

| Store                                                                    | Grain                                                        | Rows                    | Path                                                                        |
| ------------------------------------------------------------------------ | ------------------------------------------------------------ | ----------------------- | --------------------------------------------------------------------------- |
| `college_programs` (`cip_code`, `cip_title`=CIPDESC, `credential_level`) | **4-digit** (as loaded)                                      | per college             | `db/schema/0015.create-colleges.sql:105-127`; CHECK relaxed at `0021:15-19` |
| `cip_codes` (`code`, `title`, `label_raw`)                               | **6-digit**                                                  | 1,710                   | `db/schema/0060...sql:307-334`                                              |
| `college_programs_census` (`cip_code`, `awards_total`)                   | **6-digit**, IPEDS C_A, `MAJORNUM=1`, bachelor's             | per college             | `db/schema/0055...sql:92-118`                                               |
| `subjects` (`slug`, `name`, `cip_prefixes`)                              | 2/4/6-digit prefixes                                         | 181                     | `db/schema/0064...sql:48-63`; `db/data/subjects.json`                       |
| `college_search_index.cip_codes` / `subject_slugs`                       | **6-digit** / slug, NULL vs `{}` distinction is load-bearing | per college             | `db/schema/0064...sql:178-179,222-231`                                      |
| Scorecard field-of-study file                                            | **4-digit**, `CIPDESC` 1:1 with `CIPCODE`                    | 391 bachelor's families | `db/seed/scorecard/Most-Recent-Cohorts-Field-of-Study.csv` (**untracked**)  |

A stored 4-digit code therefore matches `college_search_index.cip_codes` only by
**prefix**, never by equality. Both widening directions exist as mechanisms
(`subjects.cip_prefixes` already accepts 4-digit); which side widens is a /ship
implementation choice, as the brief says.

### D6's data, as measured (predominantly-bachelor's denominator, n=1,947)

| Column                          | Coverage | Note                                                                            |
| ------------------------------- | -------- | ------------------------------------------------------------------------------- |
| `OMAWDP8_ALL`                   | 97.1%    | D9's proposed headline; best-covered                                            |
| `C150_4`                        | 92.3%    | what `colleges.completion_rate_150pct_4yr_share` holds today                    |
| `TRANS_4`                       | 92.3%    | D9's transfer-out figure                                                        |
| `FIRSTGEN_COMP_ORIG_YR6_RT`     | 81.3%    | no `NOTFIRSTGEN_COMP_*` twin exists — the comparator must be `COMP_ORIG_YR6_RT` |
| `LO_INC_COMP_ORIG_YR6_RT`       | 81.8%    | `MD_INC_` 74.9%, `HI_INC_` 76.3%                                                |
| `PELL_/NOPELL_COMP_ORIG_YR6_RT` | 79.3%    |                                                                                 |
| `DEP_/IND_COMP_ORIG_YR6_RT`     | 70.9%    | thinnest; see C2                                                                |

The suppression sentinel in this release is the literal **`PS`** (not
`PrivacySuppressed`); a `notna()` count reports a false flat 95.5%. The loader
reads **zero** subgroup completion columns today; `C150_4` alone lands, into
`colleges.completion_rate_150pct_4yr_share`
(`db/schema/0059.name-numeric-columns-by-unit.sql:59`, originally `0015:53`).
Adding a column costs a `ScorecardInstitutionColumns.VINTAGE_GROUP_BY_VARIABLE`
entry plus a hand-transcribed row in
`db/seed/scorecard/dictionary-variable-sources.csv` (24 rows today, naming no
`*_COMP_ORIG_*` column).

---

## Open questions for the spec

| #   | Question the code does not settle                                                                   | Recommendation                                                                                                                                                                                    | Why                                                                                                                                                                                                                             |
| --- | --------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Q1  | Child table vs `TEXT[]` column for the 0..3 CIP set.                                                | **Child table** `student_intended_fields (student_id, cip_code)` + the status on `money_profiles`.                                                                                                | The set is small, but an array column has no per-value history and no per-value FK to `cip_codes`; the `0024`/`0046` shape is the repo's only per-student precedent.                                                            |
| Q2  | Does the status live on `money_profiles` or on a new `student_profiles` parent?                     | **On `money_profiles`**, as a fifth pair `intended_fields_status`.                                                                                                                                | The brief's own expected shape; a second profile table would fork "the student's answers" into two rows with two version counters. Accept that the table name then under-describes it, and say so in the migration header.      |
| Q3  | Is `undecided` a fourth `AnswerStatus` member, or a separate column?                                | **Fourth member of `AnswerStatus`**, guarded so the three existing fields cannot take it.                                                                                                         | A parallel enum forks the one decode site (`MoneyProfilesDao.kt:63-70`) and every `when`. But note: widening `AnswerStatus` widens the _shared_ CHECK vocabulary, so the three money CHECKs must be re-added explicitly narrow. |
| Q4  | What does "remove the last value" do, given `FieldUpdate.Clear` already means "back to unanswered"? | Add a distinct `Undecided` write; **do not reuse `Clear`** for this field, and do not expose `Clear` on it at all.                                                                                | D17 forbids returning to `not asked`; reusing `Clear` would do exactly that through the existing DAO fold (`MoneyProfilesDao.kt:163-176`).                                                                                      |
| Q5  | Is the picker vocabulary 391 bachelor's families, 1,710 `cip_codes`, or 181 `subjects`?             | **391 four-digit families from `college_programs.cip_title`** — the grain the earnings file publishes at, and already in the DB.                                                                  | D15 binds the stored grain to the published grain; `cip_codes` (6-digit) and `subjects` (181 slugs) are both a different vocabulary and would need a lossy map.                                                                 |
| Q6  | Served through `GET /api/v1/vocabularies` as-is, or a searchable route?                             | **A new `GET /api/v1/fields-of-study?q=` route** on the `CollegeRoutes.kt:42-87` `q`/`limit` precedent; register the vocabulary name in `VocabularyService` only if it stays under ~100 entries.  | C10: no paging, no filter, no cache, no compression, client refetch per screen — 391 entries taxes every vocabulary consumer on every screen open.                                                                              |
| Q7  | Which completion figure does D6 actually serve, and with which label?                               | **`{LO,MD,HI}_INC_COMP_ORIG_YR6_RT` with the Scorecard tercile label**, plus Pell; drop dependency.                                                                                               | C1 and C2. A stored `48k_to_75k` must be rendered as "families earning $30,001–$75,000" or the citation is false.                                                                                                               |
| Q8  | Does the new field render on "Your details" in the first slice, or later?                           | **Land DB + chat tool first; the screen is its own slice.**                                                                                                                                       | C5 shows DB and API already land separately (`dependency` is DB-only), and C11 prices the screen at a searchable picker + chip component that does not exist.                                                                   |
| Q9  | Does the intended field enter the coach system prompt?                                              | **Yes, through `renderMoneyField`** — extended, not copied.                                                                                                                                       | `dependency` was landed without a prompt line (C2) and is therefore invisible to the coach; repeating that would make D5's "the coach may ask" unreachable.                                                                     |
| Q10 | Where does the 4-digit ↔ 6-digit widening happen for D21/D22?                                       | **Widen the stored 4-digit code to a `LIKE '<cip>%'`/prefix test against `college_search_index.cip_codes`**; do not re-grain the index.                                                           | The index is materialised from the 6-digit IPEDS census (`0064:178-179`) and re-graining it forces a full rebuild; `subjects.cip_prefixes` already proves prefix matching is the house mechanism.                               |
| Q11 | What is the vintage stamp for a completion/earnings figure (D7)?                                    | Carry the Scorecard release id already recorded in `db/seed/scorecard/PROVENANCE.json`, and add the `*_COMP_ORIG_*` / field-of-study rows to `dictionary-variable-sources.csv` in the same slice. | That file is the repo's only in-tree record of which variable came from which dictionary; today it has 24 rows and names none of these columns, so the vintage of every new figure would be unverifiable.                       |

---

Out of my area, left to siblings: the coach prompt body and guardrails
(D2/D3/D8), the Family Cost Report weaving (D12/D18-D20), `public-web`
(D13/D14). Read-only throughout: greps, file reads, and pandas over the two
untracked CSVs. No tests run, and no file outside this one was changed.
