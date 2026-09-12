# RFC 187: does this school want a score? — the test-policy section of `college_admissions_profile`

Product slice `test/02/does-this-school-want-a-score` (brief
`product/0010-when-do-i-test`).

## Summary

`college_admissions_profile` (RFC 148) tells a student what a school gives in
merit money, what its admission office says it weighs, and when each round is
due. It does not tell her the one fact that decides whether any of the testing
conversation applies to her at all: **does this school want a test score?**

The data is already in the database. `bin/fetch-ipeds` fetches ADM2023,
`IpedsLoader` reads `ADMCON7` from it, and it is stored as
`college_ipeds.test_policy SMALLINT NULL CHECK (test_policy IS NULL OR
test_policy IN (1, 3, 5))`
— 1 required, 3 test-blind, 5 test-optional. The codebook vocabulary is loaded
into `admission_test_policies` and typed as
`ed.unicoach.college.AdmissionTestPolicy`, and the column is already a **search
filter** (`CollegeSearchTool`'s `test_policy`). What does not exist is the
per-school READ: nothing in the coach's context can say what ONE named school on
the student's list does.

So this RFC adds a **fourth section** to the admissions read. **No DDL, no new
fetch, no new table, no new column.** One DAO read, one section type, one copy
object, one wire object, one prompt block.

Two honesty rules shape every line of it, and they come from the brief's gate
decisions rather than from taste:

- **D17 — a policy claim always carries its year.** `ADMCON7` is a 2023
  collection, and the schools most likely to have changed since are exactly the
  ~50 a student is most likely to ask about. We do not refuse a 2023 policy —
  refusing would leave 1,936 schools with no policy answer at all — we date it,
  and we say in the same breath that a school can change its policy and its own
  admissions page is where to confirm it.
- **An absent policy says something about US, never about the school.** IPEDS
  has no ADM row for most of the corpus. "We do not have this school's policy"
  is the sentence; "this school does not require a test" is a fabrication, and
  it is the one this section must make unrepresentable.

A test-policy fact carries **no `FigureStatus` and no RFC 179 assurance tier**.
That seam belongs to the canonical money tables, whose rows record a publisher's
own suppression and imputation flags. IPEDS ADM publishes none of that for
`ADMCON7`; borrowing the vocabulary would claim a provenance model this datum
does not have.

## Detailed Design

### `AdmissionsField` gains a fourth member

```kotlin
enum class AdmissionsField(val wireName: String) {
  MERIT_AID(MeritAidWire.KEY),
  ADMISSION_FACTORS("admission_factors"),
  TEST_POLICY("test_policy"),
  DEADLINES("deadlines"),
}
```

Declaration order IS wire order (RFC 145). The new member sits **after**
`ADMISSION_FACTORS` and **before** `DEADLINES`, because the ordering rule is the
order a coach reaches for the facts: the money first, then what the school
weighs, then when it is all due. "Does this school want a score" is a
what-it-weighs fact, and it belongs immediately beside the C7 grid — whose
`test_scores` row is the same school's own opinion of the same subject, from a
different publisher.

`test_policy` is also the wire key the search tool already uses for this axis,
so a student who filtered on it and then asked about one school reads the same
word twice.

### The section: `SchoolTestPolicy`

A new file `service/.../coaching/admissions/SchoolTestPolicy.kt`:

```kotlin
data class SchoolTestPolicy(
  val policy: AdmissionTestPolicy,
  val source: IpedsAdmissionsCitation,
)
```

Three properties, and the type is the reason each of them can only be said one
way:

- `policy` is the loaded codebook member
  (`ed.unicoach.college.AdmissionTestPolicy`), never the raw `1`/`3`/`5`. A code
  that is not one of the three published values cannot reach this type: the read
  maps through `AdmissionTestPolicy.fromCode` and a null mapping is treated
  exactly like a missing row — the section is absent, and the college is named
  in `data_availability`. The DB CHECK already forbids a fourth code; this is
  what happens if it is ever relaxed.
- The **statement** the coach reads is computed from `policy` by
  `TestPolicyCopy`, below — not stored, not passed in, so the sentence and the
  code cannot drift (the `CdsCitation.citedAs` precedent).
- `source` carries the survey year, so the year in the sentence is the year of
  the row that produced it.

### The copy: `TestPolicyCopy`

One object, one exhaustive `when` with no `else`, mapping each published code to
the words a family hears. Past tense, because a 2023 collection is a statement
about 2023 and not a promise about this cycle:

| code | `statement`                                                                                     |
| ---- | ----------------------------------------------------------------------------------------------- |
| 1    | this school **required** an admission test score to be considered for admission                 |
| 5    | this school did **not require** an admission test score, but considered one if it was submitted |
| 3    | this school did **not consider** admission test scores at all, even if they were submitted      |

These are our words, deliberately, not IPEDS's `label_raw` ("Not considered for
admission, even if submitted (Test Blind)"). A parenthesised source label with a
survey's own jargon in it is exactly what the tool boundary exists to translate
(CLAUDE.md schema conventions: source-defined codes stored raw, human-readable
labels only at the boundary). The slug — `required`, `test-optional`,
`test-blind` — still rides the wire beside the sentence, because it is the same
word the search filter accepts and is a plain English word, not a source code.

The copy object also owns two standing sentences, so no caller can state a
policy without them:

- `CONFIRM_WITH_SCHOOL` — "A school can change its test policy from year to
  year, so the school's own admissions page is where to confirm it before acting
  on it." Named by the section, not by the tool description, so it rides in the
  model's context with the fact it qualifies (D17).
- `NOT_REPORTED` — the sentence for an absent policy, carried on the tool
  DESCRIPTION and the prompt: we do not have this school's policy, which is a
  statement about our data and not about the school.

### The citation: `IpedsAdmissionsCitation`

`CdsCitation` cannot be reused: every CDS fact is a different school's own
document at its own URL in its own cycle, and this is one federal survey with no
per-school document.

```kotlin
data class IpedsAdmissionsCitation(val surveyYear: Int) {
  val citedAs: String
    get() = "the U.S. Department of Education's IPEDS admissions survey, collected in $surveyYear"
}
```

`surveyYear` comes from the row (`college_ipeds.survey_year`), never from a
literal `2023`: when the ingest is re-run against ADM2025 the sentence moves
with the data instead of lying in a constant. The wording deliberately echoes
`MoneySourceCopy.labelOf` ("the U.S. Department of Education's IPEDS survey of
college costs") so a family hears one publisher named one way — but it is a
separate type, in the admissions package, because importing the money seam's
copy object is how a testing figure would end up inside the money vocabulary.

**No URL key.** IPEDS publishes no per-school page for `ADMCON7`, and a survey
landing page invented here would be a citation nobody can check the school's own
answer in. The year is IN the sentence, so no bare numeric key reaches the wire
and `NUMBERS_BY_CONTRACT` is unchanged — the RFC 143 guard keeps its meaning (a
lone `2023` under a `collection_year` key is precisely a number with no
measure).

### The read

A new batched read on `CollegeIpedsDao`, modelled line for line on
`housingFlagsByIpedsUnitId` (RFC 149), including its three-state discipline:

```kotlin
fun testPoliciesByIpedsUnitId(
  session: SqlSession,
  ipedsUnitIds: Collection<Int>,
): Result<Map<Int, CollegeTestPolicyRow?>>
```

- an **absent key** = IPEDS has no `college_ipeds` row for that school;
- a key with a **null** value = the school has a row that does not report
  `ADMCON7`;
- a key with a value = the raw code and the survey year.

Both absences render identically on the wire (the section is missing and
`test_policy` is named in `data_availability`), but they are different facts and
the second is the ingest owner's diagnosis, so the read does not weld them
together in SQL — the exact defect RFC 149 fixed in the housing read.

`CollegeTestPolicyRow` (in `:db` models) holds `testPolicy: Int` and
`surveyYear: Int` — the RAW code, because `:db` does not and must not depend on
`:college`, where the codebook vocabulary lives. The code becomes an
`AdmissionTestPolicy` at the service boundary, which is where every other raw
IPEDS code is translated.

The read is keyed by `ipeds_unit_id` because that is `college_ipeds`'s natural
key; `College.ipedsUnitId` is already on the row the service holds, so no extra
lookup is needed.

### Wiring it into `CollegeAdmissionsService`

`readInSession` gains one read. The private `LatestCdsFacts` holder is renamed
`AdmissionsFacts` and gains a fourth field: its doc comment already says a
fourth table is one field here rather than a fourth copy of the idiom, but the
name would then be wrong — IPEDS ADM is not the Common Data Set, and a holder
called `LatestCdsFacts` carrying an IPEDS map is the kind of small lie that
makes a later reader attribute the policy to the school's own CDS.

The batching contract is preserved and is assertable: one query per table for
the whole answer. A five-college list costs SIX statements where it cost five,
not five more — the list selection is two of them (`college_list_entries`, then
`colleges`), the three CDS tables are three, and the policy read is the sixth.
The count is asserted absolutely, against a named constant, so a sixth read
added without a doc update fails rather than passing an "equal to itself" test.

`CollegeAdmissions` gains `testPolicy: SchoolTestPolicy?`, and `notReported`
gains the one derived line:

```kotlin
if (testPolicy == null) add(AdmissionsField.TEST_POLICY)
```

— derived from the nullable section exactly like the other three, never passed
in.

A row whose code maps to no known member is LOGGED (college, code, survey year)
before the section is suppressed, on the `meritOf`/`factorsOf` precedent: the
wire is right either way, but an operator can only find a corpus defect if the
run says which school it came from.

### The wire

```json
"test_policy": {
  "policy": "test-optional",
  "statement": "this school did not require an admission test score, but considered one if it was submitted",
  "confirm_with_school": "A school can change its test policy from year to year, so the school's own admissions page is where to confirm it before acting on it.",
  "source": { "cited_as": "the U.S. Department of Education's IPEDS admissions survey, collected in 2023" }
}
```

Every value is a string. `putCitation` is not reused — it is `CdsCitation`'s
renderer and requires a `url` — so this section renders its own source object in
`CollegeAdmissionsChatTool`, whose ONE key is `cited_as`.

The tool DESCRIPTION gains the ethos, in the same voice as the rest: the policy
is what that school told IPEDS in the survey year the citation names, a school
can change it, and `test_policy` in `data_availability` means WE do not have the
school's policy and never that the school requires nothing.

### The prompt (`prompts/coach-system-prompt.md`)

One block, authored one sentence per line, appended to the
`college_admissions_profile` paragraph (lines 40-46) — a different region from
the one `test/01/the-coach-knows-today` edits, so the two runs rebase rather
than conflict. It carries three things:

**1. The per-school rule (D17, and the brief's list-anchoring).** State a policy
only from that school's own recorded policy; always say the year the tool's
citation names and the school-can-change sentence with it; never generalise one
school's policy to another school on the list or to the list as a whole; when
`test_policy` is in `data_availability`, say we do not have that school's policy
and send the student to the school's page, never that the school does not
require a score.

**2. The explain-once block (D4, second arm).** The first time testing comes up
in a conversation, the coach explains **once, in its own plain words** — not as
a list of facts, and not as a lecture that delays the answer — what these tests
are for, when a student **needs** one (a school on the list requires it; a
scholarship requires a score even where admission does not; an NCAA or state
programme rule requires one), when they would **want** one anyway (a strong
score can only help at a test-optional school, it can move real money at schools
with automatic merit grids, it can offset a weaker transcript), and when they
are better off **not** sitting one (every school on the list is test-optional or
test-free; a score below a school's own reported range is usually better
withheld where withholding is allowed; the cost, the Saturdays and the stress
are real). Then it answers the actual question. It is given once, not at every
mention. With an empty college list it may give the general picture, labelled as
general rather than as advice about their schools.

**3. The standing scope rules (D9, D13).** Never test prep, never a predicted
score, never odds of admission. The SAT is never the default noun: the SAT and
the ACT are named symmetrically, because in a dozen states the ACT is the test
the student will actually sit.

`bin/prompt-seed` cuts the version at land (RFC 181/185); this run hand-writes
no seed migration and does not touch `systemPromptVersion`.

## Files Modified

| File                                                                                   | Change                                                                                                                                                      |
| -------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `service/src/main/kotlin/ed/unicoach/coaching/admissions/AdmissionsField.kt`           | fourth member `TEST_POLICY`, between factors and deadlines                                                                                                  |
| `service/src/main/kotlin/ed/unicoach/coaching/admissions/SchoolTestPolicy.kt`          | **new** — the section type, `TestPolicyCopy`, `IpedsAdmissionsCitation`                                                                                     |
| `service/src/main/kotlin/ed/unicoach/coaching/admissions/CollegeAdmissionsService.kt`  | `CollegeAdmissions.testPolicy` + its `notReported` line; `LatestCdsFacts` → `AdmissionsFacts` with the fourth read; `testPolicyOf` with its suppression log |
| `service/src/main/kotlin/ed/unicoach/coaching/admissions/CollegeAdmissionsChatTool.kt` | `testPolicyObject`, its source renderer, and the DESCRIPTION ethos                                                                                          |
| `db/src/main/kotlin/ed/unicoach/db/dao/CollegeIpedsDao.kt`                             | `testPoliciesByIpedsUnitId`                                                                                                                                 |
| `db/src/main/kotlin/ed/unicoach/db/models/CollegeIpeds*.kt`                            | **new** `CollegeTestPolicyRow(testPolicy, surveyYear)`                                                                                                      |
| `prompts/coach-system-prompt.md`                                                       | the test-policy block, the D4 explain-once block, the D9/D13 scope rules                                                                                    |
| `service/src/test/.../admissions/AdmissionsTestDb.kt`                                  | a `seedIpedsTestPolicy` seeder; `college_ipeds` added to the truncation list                                                                                |
| `service/src/test/.../admissions/CollegeAdmissionsServiceTest.kt`                      | the composition tests below                                                                                                                                 |
| `service/src/test/.../admissions/CollegeAdmissionsChatToolTest.kt`                     | the wire tests below                                                                                                                                        |
| `db/src/test/.../dao/CollegeIpedsDaoTest.kt`                                           | the three-state read tests                                                                                                                                  |
| `service/src/test/.../coaching/SystemPromptCatalogTest.kt`                             | the authored-prompt assertions below (D9/D13)                                                                                                               |

No migration. No change to `db/schema/ORDER`. No new table, no new column.

## Implementation Plan

1. `:db` — `CollegeTestPolicyRow` and
   `CollegeIpedsDao.testPoliciesByIpedsUnitId`, with its DAO tests. Smallest
   independently verifiable step.
2. `:service` — `SchoolTestPolicy.kt` (section, copy, citation) and the
   `AdmissionsField` member.
3. `:service` — wire it into `CollegeAdmissionsService`: the renamed holder, the
   fourth read, `testPolicyOf`, the `notReported` line.
4. `:service` — the chat tool's section renderer and DESCRIPTION.
5. `prompts/coach-system-prompt.md` — the three blocks, one sentence per line.
6. Tests at each step, then `nix develop -c bin/test`.

## Tests

**`CollegeIpedsDaoTest`**

- each of the three codes round-trips with its survey year;
- a school with a row but a NULL `test_policy` comes back as a present key with
  a null value; a school with no row at all is an ABSENT key — the two are
  distinguishable (the RFC 149 rule);
- an empty id collection short-circuits without a query;
- one query for a five-school read.

**`CollegeAdmissionsServiceTest`**

- a required / test-optional / test-blind school each yields the section with
  the matching member and the survey year off the row (a fixture seeded with
  survey year 2024 cites 2024, proving no literal `2023`);
- a school with no IPEDS row, and a school with a row reporting no policy, both
  yield `testPolicy == null` and `TEST_POLICY` in `notReported`;
- a school present in every other section still reports the policy silence — the
  four sections are independent;
- the batching assertion is extended: a five-college list costs the same
  statement count as a one-college list.

**`CollegeAdmissionsChatToolTest`**

- the section renders `policy`, `statement`, `confirm_with_school` and
  `source.cited_as`, and the section key sits between `admission_factors` and
  `deadlines`;
- the statement for each of the three codes is the sentence `TestPolicyCopy`
  owns — asserted against the copy object, not a retyped literal;
- `confirm_with_school` is present on EVERY rendered section (D17 — the standing
  sentence cannot be optional);
- `cited_as` contains the survey year, and the payload carries no bare numeric
  year key (the existing RFC 143 numeric guard, unchanged
  `NUMBERS_BY_CONTRACT`);
- a school with no policy emits NO `test_policy` object and lists `test_policy`
  in `data_availability`;
- no raw `1`, `3` or `5` and no `ADMCON7` string reaches the payload.

**`SystemPromptCatalogTest` / prompt**

- the authored prompt body is the file verbatim (existing assertion, unchanged
  by this run);
- the test-policy block names both the SAT and the ACT (D13 symmetry) and
  contains no score-prediction or test-prep instruction (D9) — both halves as a
  LICENCE scan over every mention, not as one positive assertion, so a later
  sentence that recommends prep or predicts a score fails here.

**The unmappable code**

- `AdmissionTestPolicy.fromCode` returns null for a code outside the published
  set, so the suppression arm in `testPolicyOf` is decidable. It cannot be
  driven end to end: `college_ipeds_test_policy_domain_check` admits only 1, 3
  and 5 and fires on UPDATE as well as INSERT, so a fixture storing a 2 is
  refused before the service reads it. The guard is pinned at the mapping, and
  the published set is asserted against the enum so relaxing the CHECK cannot
  quietly change what an unknown code renders.
