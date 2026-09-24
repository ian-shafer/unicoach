# RFC 231 — The major answer: what a student's major earns and owes

- Slice: `beyond/05/the-major-answer` (brief 0011 "beyond money")
- Lane: A (design-bearing)
- Status: design, awaiting approval

## Summary

The coach today answers "what does this school cost my family?" It cannot answer
the next question a family actually asks: "what do graduates of MY major at THIS
school earn, and what do they owe?" The data landed in `beyond/03`
(`program_money_stats`) and the student's answer to "what do you want to study?"
landed in `beyond/04` (`intended_subjects`). This slice joins the two: the cost
conversation becomes a return conversation, in the family's own major.

The answer rides in the existing `college_cost_profile` chat tool (the widening
choice — see Decisions). With a stored field, the tool's result gains a
`program_return` section: per stored subject, per college, per MATCHED PROGRAM
(the publisher's own grain), the three published program figures — Treasury
median earnings four years after entry, NSLDS median federal loan debt of
completers, and the publisher's own ten-year monthly payment — each with its own
publisher, cohort years and silence arm. With no stored field, the section is
simply absent and the answer degrades to the school-level median earnings and
median debt the tool already returns, with their limits stated, and the coach
does not nag.

## Decisions

1. **Widen `college_cost_profile` rather than add a new tool.** (See the
   approval gate for the trade.) The program figures ride in a new
   `program_return` section of the existing payload.

## Detailed Design

### Read path (one snapshot, batched)

`CollegeCostService.readInSession` already reads the student's list, money
profile, merit aid, aid policy, borrowing and the canonical money store on one
connection. It gains three reads, each batched over the colleges already
selected:

1. **The intended-subjects answer** —
   `IntendedSubjectsDao.readAnswer(session,
   studentId)`. This is a total
   read: `unanswered` is the answer of a student nobody has asked, and
   `declined` / `undecided` are real answers, so nothing is a 404.
2. **The matched programs**, only when the answer is `ANSWERED` —
   `ProgramMoneyDao.bachelorProgramsBySubject`: which (subject, college, CIP)
   triples run a bachelor's program whose `cip_code` starts with one of that
   subject's stored prefixes. This is the PROGRAM question, and it is the only
   read that can answer it.
3. **The program figures**, on the same condition —
   `ProgramMoneyDao.programMoneyBySubject`: the same match plus
   `program_money_stats`, one row per (subject, college, program, measure). One
   query each, batched over the selected college ids and the resolved prefixes —
   never one query per subject, per college or per program.

**The two reads are separate because a missing figure is not a missing
program.** A section built from `program_money_stats` alone cannot see a
`college_programs` row we hold no money for, so it would say "this school does
not run that program" about a school that runs it — a gap in OUR data spoken as
a statement about a school. The program read is what keeps the two apart. Both
stand on ONE copy of the prefix-match SQL inside `ProgramMoneyDao`, because two
transcriptions are two prefix rules free to drift.

**One measure, one answer, chosen in the query.** `program_money_stats`' natural
key is (program, measure, cohort), so two cohorts of one measure is a supported
state: a snapshot that re-dates a column adds rows beside the old ones. An
unbounded read served that measure TWICE and which one a family saw depended on
row order. The read is `DISTINCT ON (subject, college, cip_code,
measure)`
ordered by `cohort_last_year DESC, cohort_first_year DESC` — the most recent
cohort, deterministically, with its own years carried on the row.

Credential level is fixed at bachelor's (`credential_level = 3`): unicoach
coaches high-school students applying to US undergraduate study, so an
associate's or a certificate's figure is the wrong cohort to lead with. The
4-digit code is the grain the Scorecard field-of-study file publishes at, and
prefix matching is the established idiom (`subjects.cip_prefixes` exists for
exactly this; brief 0011 D38).

### The `program_return` payload section

Present **only** when the intended-subjects status is `answered`; absent, never
empty, when it is `unanswered`, `declined` or `undecided` — the payload's own
absent-never-empty convention. Its shape:

```
program_return: [
  {
    "subject_slug": "nursing",
    "subject_name": "Nursing",              // resolved at read time, RFC 205 D39
    "colleges": [
      {
        "college_id": "...",
        "name": "...",
        "programs": [                        // one entry per MATCHED program, in cip_code order
          {
            "cip_code": "5138",              // which program this entry is about
            "figures": [                     // ALWAYS one entry per measure, in this order
              {
                "measure": "median_earnings_4yr_after_entry",
                "amount_usd": 61000,         // present only on the two value-bearing statuses
                "status": "reported | imputed_by_publisher | suppressed_by_publisher | not_reported_by_institution | not_applicable | not_collected_by_us",
                "publisher": "Treasury",     // from ScorecardFieldOfStudyColumns, not the store's source column
                "cohort_first_year": 2017,   // THIS ROW's cohort, not the registry's
                "cohort_last_year": 2018,
                "cohort_sentence": "Treasury AY2017-2018, AY2018-2019 pooled earnings cohort measured in CY2022, CY2023, inflation adjusted to 2024 dollars",
                "inflation_base_year": 2024  // earnings only; absent on both debt figures
              },
              {
                "measure": "median_debt_at_completion",
                "measure_availability": {    // instead of status + provenance, when we hold no usable row
                  "basis": "no_figure_held",
                  "statement": "We hold no published figure for that measure of that program at this school."
                }
              },
              ...
            ]
          },
          {
            "cip_code": "5139",
            "program_figures_availability": {  // instead of "figures", when we hold none of them
              "basis": "no_published_figures",
              "statement": "This school runs that program and we hold no published figures for it."
            }
          }
        ]
      },
      {
        "college_id": "...",
        "name": "...",
        "program_availability": {          // instead of "programs", when no program matched
          "basis": "no_program",
          "statement": "This school does not run that program."
        }
      },
      ...
    ]
  },
  ...
]
```

The three `*_availability` objects are the payload's established availability
idiom (`aid_policy_availability`, `borrowing_availability`): a `basis` code and
the `statement` that says it. An absence with no stated basis is the silence
this section exists to prevent, so every level that carries no data says WHY
rather than merely omitting a key.

**The payload is keyed by PROGRAM, because the publisher's grain is the
program.** A college entry carries `programs[]`, one entry per matched
bachelor's program with its own `cip_code` and its own `figures`. A college that
runs two programs a subject's prefixes both reach therefore carries two entries.
The earlier shape hung `figures` directly off the college and could not say
which major a figure was about; worse, the read behind it deduplicated by VALUE,
so two DIFFERENT programs whose figures coincided collapsed into one and one of
the two majors vanished. That was a data-loss defect, and carrying the program's
identity through the projection is the fix.

Per college, **four** outcomes are possible, and each is distinguished by its
own KEY rather than by the absence of another:

- **The school runs it and we hold figures** — the college entry carries
  `programs[]`, and the program entry carries `figures[]`. Each figure's own
  `status` then says which kind of number or silence it is (`reported`,
  `imputed_by_publisher`, `suppressed_by_publisher`,
  `not_reported_by_institution`, `not_applicable`, `not_collected_by_us`) with
  its publisher and cohort beside it. `suppressed_by_publisher` (`PS`) and
  `not_reported_by_institution` (`NA`) stay two different sentences (C8), and
  **never substitute the school-level median earnings or debt in place of a
  suppressed figure** (the slice's acceptance criterion).
- **The school runs it and we hold no figure for ONE measure** — that measure
  still gets an entry in `figures[]`, carrying `measure_availability` with basis
  `no_figure_held` instead of a status. `figures[]` is never short: an entry
  that simply vanished would be an absence with no reason, indistinguishable
  from a suppressed one.
- **The school runs it and we hold no figures at all** — the program entry
  carries `program_figures_availability` with basis `no_published_figures`
  instead of `figures[]`. OURS, at the program.
- **No program at all** — no `college_programs` row at bachelor's level matched
  the subject's CIP prefixes at this school: the college entry carries
  `program_availability` with basis `no_program` and no `programs[]` key (the
  slice's acceptance criterion).

The three `basis` codes are three distinct strings at three distinct keys and
three distinct nesting levels, on purpose. Only the last is a statement about a
school's offering; the other two are gaps in what WE hold about a program the
school does run, and folding any two of them together turns one into the other
in front of a family.

### Every legal stored status gets a wire answer

`program_money_stats.status` is
`TEXT NOT NULL REFERENCES
figure_statuses(slug)`, so the WHOLE six-member
vocabulary is storable, and the table's only status CHECK (`amount_iff_status`)
explicitly contemplates `imputed_by_publisher`. The renderer therefore speaks
all six, each as itself, and refuses none: one legal row must not take an entire
cost answer down with it. `imputed_by_publisher` carries its amount like
`reported`; `not_applicable` and `not_collected_by_us` reuse the words this
payload's existing status legend already uses for them ("does not apply there,
source says so"; "OURS, not gathered yet"). The seam is two exhaustive `when`s
with no `else` over the value-bearing and absent halves of the partition, so a
SEVENTH status fails to compile here; the test drives the same seam from
`FigureStatus.entries` so it also fails loudly rather than silently finding a
crash path. No CHECK constraint narrows the column: the fix belongs in code,
because the store is right to hold what the vocabulary allows.

### The cohort sentence agrees with the row, or the figure says nothing

The two cohort years come off the ROW and the verbatim cohort sentence comes off
the registry (`ScorecardFieldOfStudyColumns`). A registry re-pin can therefore
leave a stored row whose years the sentence does not describe — and a figure
served beside a sentence about a different cohort contradicts itself in front of
a family. So `ProgramFigureProvenance` EXISTS only where the two agree
(`ofAgreeingCohort`), and a row that cannot be stamped takes the
`no_figure_held` arm for that measure. It is never refused and never made
unreadable: a re-pin is our edit, and it must not cost a family a figure it can
still read, nor take the answer down. Today's ingest writes the registry's own
years (`ProgramMoneyLoader`), so ordinary rows take the agreeing path — asserted
directly, because an implementation that quietly routed every figure into the
no-figure arm would otherwise look like a working one.

### Provenance: the publisher, not the store's source column

`program_money_stats.source` is `scorecard` — the file we ingested. The
publisher of each figure is per-variable and lives once in
`ScorecardFieldOfStudyColumns` (`Treasury` for earnings, `NSLDS` for the two
debt figures), reached through `source_variable`. The rendering reads
`ScorecardFieldOfStudyColumns.ofOrNull(source_variable)` for the publisher, the
cohort sentence and the dollar base, exactly as the schema's own column comment
directs. The store's `source` column is not spoken: saying "Scorecard" for a
figure Treasury published would misattribute it.

Two more rows cannot be STAMPED, and — like a re-dated one — neither is refused:
a `source_variable` the registry does not carry, and a row whose stored
`measure` is not the one that variable publishes (which would otherwise serve a
debt figure under the earnings publisher and the 2024 dollar base). Both take
the same `no_figure_held` arm. Neither is corruption we may refuse on:
`program_money_stats.source_variable` is `TEXT NOT NULL` under a non-empty CHECK
alone, so a fourth ingested Scorecard column is a SCHEMA-LEGAL value this
registry has not learned yet — a widening — and a variable/measure disagreement
is the registry disagreeing with the store, which is our edit exactly as a
re-pin is. Refusing either took a family's whole `college_cost_profile` answer
down over one row we could not attribute, which is the rule above ("refuses
none") read at the grain it was written for.

To a FAMILY these are one fact with the third absence — we hold no published
figure for that measure — so they carry no fourth `basis` code: a family cannot
act on which of our own gaps it is, and the wire would be stating our ingest's
internals. The OPERATOR is owed the cell, and gets it: ALL THREE unstampable
rows — the unlearned `source_variable`, the variable/measure disagreement and
the re-dated cohort — are a located `WARN` naming the column and the row's
natural key (school, major, program, measure), so an ingest that starts writing
a column this registry cannot attribute is loud where it can be acted on and
silent where it cannot. The cohort case is the one that must NOT be the quiet
one: a registry re-pin hits every stored row of that variable at once, so a
silent third arm would blank a figure for every student with no signal but a
family asking why.

### The three figures and RFC 198

Earnings (`EARN_MDN_4YR`, Treasury, AY2017-18+18-19, 2024 dollars) and the
ten-year payment (`DEBT_ALL_STGP_EVAL_MDN10YRPAY`, NSLDS, AY2018-19+19-20) are
**two publishers and two cohorts**. They may sit in ONE answer, never in one
table row-set (RFC 198 prompt line 105). The prompt rule this run cuts states
this for the major answer explicitly. No debt-to-earnings ratio is computed
anywhere (D27) — the payment is served because the publisher computed it, never
derived from the debt figure.

### Degrade: no stored field

With no stored field (`unanswered`, `declined` or `undecided`), the
`program_return` section is absent and the answer is the school-level figures
`college_cost_profile` already returns (`median_earnings_10y_after_entry_usd`,
`median_debt_at_completion_usd`) with their existing source/tier/status limits.
The coach does not nag for the field: the prompt v37 intended-subjects paragraph
already forbids re-asking a declined or undecided student, and this run's
paragraph adds the same for the unanswered case in this conversation — the
school-level figure is the answer, not a hook for a question.

### The prompt paragraph (one paragraph, one line each)

Added to `prompts/coach-system-prompt.md`, authored one sentence per line. The
authored file is the source of truth for this copy, so the six lines are quoted
here VERBATIM as they are authored — a paraphrase here would be a second, wrong
copy of what the model is actually told:

- When the student asks what graduates of their major earn or owe at a school,
  read the program_return section of college_cost_profile.
- With a stored subject, say each figure with its publisher and its cohort years
  in the publisher's own words, each beside the figure it covers.
- The publisher suppressing a figure and the school not running that program are
  two different sentences: never put the school-level figure in place of a
  suppressed one.
- Say the field-of-study coverage limit once in an answer, not once per figure.
- With no stored subject, answer with the school-level median earnings and
  median debt with their limits stated, and do not ask for the subject.
- Never put the Treasury earnings figure and the NSLDS payment in one table
  row-set.

The copy says "subject", the name of the thing the store holds and the word the
rest of the prompt uses, and "do not ask for the subject" rather than "do not
nag" — the concrete act, which is what a rule has to name to be followed.

The version label is NOT named here, and that is deliberate: `bin/prompt-seed`
cuts it at land as one past the highest coach seed on the base, so which label
this paragraph gets is decided by land order among the runs open at the time. A
literal written now would be a guess, and a wrong one half the time.

The rollback is one environment variable — `COACHING_SYSTEM_PROMPT_VERSION` set
to the version immediately BEFORE the one this run cuts — and it is a PARTIAL
rollback: the pin removes the paragraph, while the `program_return` payload and
its legend still ship. That is safe because the payload is data and the legend
carries no behavioural rule, so a section the prompt no longer routes to simply
goes unread.

### The description legend

`CollegeCostChatTool.DESCRIPTION` is a pure schema legend (RFC 192); the
behavioural rules live in the prompt above, never in the description. The legend
gains one entry describing the `program_return` keys and their values, in
payload order. RFC 203 deleted the byte ceiling: the size of the legend is
judged at the approval gate, where the value of the bytes can be weighed.

## Files Modified

- `rfc/231-the-major-answer.md` (this document)
- `db/src/main/kotlin/ed/unicoach/db/dao/ProgramMoneyDao.kt` — two batched
  reads, each one query for the whole answer, each at credential level 3 with
  the subject's CIP prefixes expanded inside the query and ONE shared copy of
  that prefix match: the program money rows for the selected colleges (most
  recent cohort per measure, chosen with `DISTINCT ON`), and the program
  question asked apart from the money question — which (subject, college, CIP)
  triples run a matching bachelor's program at all.
- There is **no** `CollegeProgramsDao.kt`, and this document used to name one.
  `college_programs` has no DAO of its own: the prefix match is a `JOIN` inside
  each of the two reads above, and `ProgramMoneyDao` is the nearest existing
  home.
- `db/src/main/kotlin/ed/unicoach/db/dao/ProgramMoneyRows.kt` — the read row the
  answer renders (`ProgramReturnStat`, now carrying the matched program's
  `cip_code`), the matched-program key both reads answer with
  (`SubjectProgramMatch`), and the ONE spelling of a row's address that every
  diagnostic on this path prints: `ProgramMoneyStatColumn`,
  `PROGRAM_MONEY_STATS_TABLE`, `programMoneyStatColumn`,
  `ProgramMoneyStatLocation` and `programMoneyStatNaturalKey`. All five are
  public, and two of them (`ProgramMoneyStatColumn` and
  `programMoneyStatNaturalKey`) are reached from `:service` today, because the
  address is printed on BOTH sides of the module boundary — `:db`'s own row
  mapping refuses a corrupt `measure`, `status` or `amount_usd` with it, and
  `:service` logs an unstampable row's location with it — and a second spelling
  of "which row" is how two operators end up grepping for different strings
- `db/src/testFixtures/kotlin/ed/unicoach/db/dao/SubjectTaxonomyFixture.kt`
  (**new**) — the `subjects` / `college_programs` write shape, published from
  `:db` because both `:db`'s DAO tests and `:service`'s costs fixture seed it
- `service/src/main/kotlin/ed/unicoach/coaching/costs/ProgramReturn.kt`
  (**new**) — the section's payload vocabulary and its assembly: the four
  levels, the seven figure arms, and the cohort-agreement rule. Its own file,
  like every other section of this payload
- `service/src/main/kotlin/ed/unicoach/coaching/costs/CollegeCostService.kt` —
  read the intended-subjects answer, the matched programs and the program
  figures; carry the section on `CollegeCostProfile`, **not** on `CollegeCost`:
  it is keyed by SUBJECT first (`subject_slug` -> colleges -> programs ->
  figures), so no per-college object can carry that order
- `service/src/main/kotlin/ed/unicoach/coaching/costs/CollegeCostChatTool.kt` —
  render `program_return`; extend the DESCRIPTION legend
- `common/src/main/kotlin/ed/unicoach/common/util/CalendarYear.kt` (**new**) — a
  dollar base is a calendar year and a cohort is an academic year; while the
  first was an `Int`, speaking one as the other type-checked. Range-guarded like
  `AcademicYear`, with its own bound: a dollar base is transcribed from a
  workbook cell, and a type that only stops the year being MISNAMED would still
  let `CalendarYear(0)` be spoken as "in 0 dollars"
- `db/src/main/kotlin/ed/unicoach/db/models/CipCode.kt` (**new**) — a CIP code
  and a subject slug are two `TEXT` facts that share one representation and sit
  as adjacent parameters on `SubjectProgramMatch` and on this path's address, so
  transposing them compiled. The READ path carries the type; the ingest key
  (`ProgramKey`) keeps a `String`, because its neighbours are two `Int`s and
  there is nothing there to transpose it with. `subjects.slug`'s own type is a
  later slice: it would ripple through `subjects`, the search index and the
  intended-subjects answer, and typing ONE of the pair already makes the
  transposition a compile error
- `service/src/test/kotlin/ed/unicoach/coaching/costs/CostsTestDb.kt` — the
  subjects, the stored answer and the program money the costs fixture now owns
- `service/src/test/kotlin/ed/unicoach/coaching/costs/CollegeCostServiceTest.kt`
- `service/src/test/kotlin/ed/unicoach/coaching/costs/CollegeCostChatToolTest.kt`
- `db/src/test/kotlin/ed/unicoach/db/dao/ProgramMoneyDaoTest.kt`
- `service/src/test/kotlin/ed/unicoach/coaching/SystemPromptCatalogTest.kt` —
  the new prompt version and its paragraph
- **DELETED**:
  `service/src/test/kotlin/ed/unicoach/coaching/costs/MoneyAttributionNamesNoPublisherTest.kt`
  — RFC 177 D7's source-text sweep. It matched the alias "Scorecard" inside the
  CLASS NAME `ScorecardFieldOfStudyColumns` (an import line and a type
  reference, neither an attribution string), and its hit count grew from two to
  six BECAUSE this run stopped hand-typing publisher names and started deriving
  them from the registry — a test inversely correlated with the property it
  claims to protect. Ian's standing rule is that tests never do static analysis;
  a bad one is deleted, not repaired. D7 keeps the half that does the work:
  `MoneySourceCopy`'s `when`s with no `else`, which still force a new publisher
  to be spoken before it compiles. The **ten** now-vestigial
  `// money-attribution-exempt:` marker lines elsewhere in `main`, across
  **six** files (`CollegeCostService.kt` x2, `CollegeCostChatTool.kt` x3,
  `CollegeAdmissionsChatTool.kt` x2, `SchoolScoreBands.kt`,
  `SchoolTestPolicy.kt`, `FafsaProcessCopy.kt`), are left in place and recorded
  as a follow-up — but the comment beside each one now SAYS the marker is
  vestigial and names what survives of D7, in all six files. A marker is a
  recorded token; a comment asserting that a deleted test enforces something is
  simply false, and one of them was carrying a live naming decision
  (`SchoolScoreBands`'s "a guard that has to be waived six times"), which is
  restated on its own maintenance merits. Counted with
  `grep -rn money-attribution-exempt` over the `src/main` trees, which is the
  live list: ten marker lines and seven prose mentions.
- `prompts/coach-system-prompt.md` — the new paragraph, plus
  `db/schema/seed-coach-system-prompt-v42.sql` and `db/schema/ORDER` (the seed
  migration is regenerated at land by `bin/prompt-seed`) and
  `service/src/main/resources/service.conf`
- `college/src/main/kotlin/ed/unicoach/college/ScorecardFieldOfStudyColumns.kt`
  — drops `internal`, and `FieldOfStudyVariable.inflationBaseYear` becomes a
  `CalendarYear?` rather than an `Int?`: the registry is the per-variable
  publisher / cohort cell / dollar-base fact, and the renderer that needs it
  lives in `service`, one module out. The re-typing changes every reader of the
  registry, which is the point — while the dollar base was an `Int`, speaking it
  as a cohort year type-checked
- `service/src/main/kotlin/ed/unicoach/coaching/StudentCollegeSelection.kt` —
  `collegeOf` stops being `private`: the major answer resolves a selected
  college's name through the SELECTION's own lookup rather than rebuilding a map
  beside it, so a selected id with no `colleges` row still fails naming the
  student and the college. The widening is to a member of an `internal` class,
  and its one new caller is `CollegeCostService.programReturnFor`
- `public-web/src/test/kotlin/ed/unicoach/web/FakeCostReportSource.kt` — passes
  `programReturn = null`: the new `CollegeCostProfile` field is required, so
  every construction site states the degrade explicitly, including this fake in
  another module
- `college/src/test/kotlin/ed/unicoach/college/ScorecardFieldOfStudyPinTest.kt`
  — the pinned dollar base is now a `CalendarYear`

No new table; no migration beyond the regenerated prompt seed; no iOS change.

## Implementation Plan

1. Add both batched reads to `ProgramMoneyDao` — the money read and the program
   lookup, in the same DAO because `college_programs` has none of its own, over
   ONE shared copy of the prefix-match SQL. The money read selects `cip_code`
   and keys `DISTINCT ON (subject, college, cip_code, measure)` so the program's
   identity survives and the most recent cohort is chosen in the query; the
   program lookup returns `SubjectProgramMatch(subject, college, cip_code)`.
2. Extend `CollegeCostService` to read the intended-subjects answer and, when
   `answered`, BOTH the matched programs and the program figures — the program
   read is what tells "this school does not run that program" apart from "we
   hold no figures for it". The status decision is an exhaustive `when` over
   `AnswerStatus` with no `else`, so a fifth status fails to compile here rather
   than falling silently into the degrade.
3. Model the result as the sealed/typed `ProgramReturnSubject` /
   `ProgramReturnCollege` / `ProgramReturnProgram` / `ProgramReturnFigure`
   rather than free JSON, in `ProgramReturn.kt` beside its assembly. Four
   levels, each ABSENT-never-empty by construction, and by construction means by
   the TYPE: `WithPrograms` holds a head program; `WithFigures` has a private
   constructor and one factory (`ofHeldFigures`) that totals the measure set
   itself and answers `NoPublishedFigures` where nothing was held; and
   `ProgramFigureProvenance` has a private constructor and reads its cohort
   years off the registry entry, so a pair of years the registry's sentence does
   not describe is not a value anything can spell. No runtime `require` carries
   any of the four, and no public door answers one of these states with an
   exception while a factory answers it with `null`.
4. Speak every legal `figure_statuses` value: six `Held` arms plus `NoFigure`,
   routed by two exhaustive `when`s over the value-bearing and absent halves of
   the partition. No status raises.
5. Extend `CollegeCostChatTool` to render `program_return` — subject -> colleges
   -> `programs[]` -> `figures[]`, with the three absence arms at their three
   levels — and extend the legend, in payload order. The `CollegeCostProfile`
   object gains a `programReturn` field, which is `null` on the degrade path;
   the field is REQUIRED, so each construction site states that null explicitly,
   including `public-web`'s cost-report fake one module out.
6. Write the prompt paragraph (one sentence per line).

## Tests

- **DAO**: prefix matching (2/4/6-digit against 4-digit codes), credential level
  3 only, batched over colleges, and the status/amount pairing — for the money
  read; and, for the program read, the prefix match, bachelor's level only, one
  query for two colleges and two subjects, and that it answers about a program
  the publisher gave us no figure for.
- **DAO, program identity**: two matched programs at ONE college whose figures
  are BYTE-IDENTICAL — same measure, amount, status, cohort years and
  `source_variable`, differing only in `cip_code`. Both rows survive AND are
  told apart by their code. That fixture is the point: two programs whose
  figures differ would pass a value-based deduplication too, so only the
  identical pair is evidence that identity is carried. The converse is asserted
  beside it — one program reached through two of a subject's own prefixes is
  still ONE row.
- **DAO, two cohorts**: two cohorts stored for one measure of one program (a
  state the natural key admits). The read answers once, with the most recent
  cohort's own years and its own figure, and the selection is the query's.
- **Service**: the `when` over the intended-subjects status is exhaustive with
  no `else`, so adding a fifth status fails to compile; the BEHAVIOUR is
  asserted at the service seam, one call per `AnswerStatus`, on the section's
  presence (`answered`) or absence (every other status) — never by reading the
  source text. `answered` with no matching program renders `NoProgram`; a
  matched program with no money row renders `NoPublishedFigures`; a program with
  one stored measure renders all three, two of them `NoFigure`; the store's
  `source` column never reaches the payload as a spoken publisher; the earnings
  dollar base is present and the debt dollar base absent; a stored
  `source_variable` the registry does not carry and a row whose `measure` that
  variable does not publish each take the `no_figure_held` arm while every
  figure beside them still answers and the whole cost answer still returns. Both
  rows are written with raw SQL, from what the COLUMN permits rather than
  through today's narrower ingest: a fixture that could only produce them
  through the loader would be asserting the loader's narrowness, which is the
  thing that changes.
- **Service, the shape's own doors**: the two factories are asserted on their
  ANSWERS, because the states the old `require`s rejected are no longer
  constructible and there is no throw left to observe —
  `WithFigures.ofHeldFigures` totals the measure set in declaration order from
  one held figure and answers `NoPublishedFigures` from none, and
  `ProgramFigureProvenance.ofAgreeingCohort` answers `null` for a cohort the
  registry's own cell does not describe. The absent-never-empty claim at the
  other two levels is a head PARAMETER, which the compiler asserts and no test
  can.
- **Service, the status vocabulary**: driven from `FigureStatus.entries`, one
  stored row per member — each gets a wire answer, each is spoken as itself, the
  amount rides on exactly the value-bearing members, and none refuses. A seventh
  status fails at this seam rather than finding a crash path.
- **Service, the cohort sentence**: the rows the ingest actually writes take the
  AGREEING path (asserted, because an implementation that routed every figure
  into the no-figure arm would otherwise look like a working one), and a row
  re-dated out from under the registry takes the `no_figure_held` arm while
  every figure beside it still answers and the read does not refuse.
- **Tool/payload**: `program_return` absent when not `answered`; present with
  the right shape when it is; two identical-figure programs render as two
  `programs[]` entries each carrying its own `cip_code`; the three absence arms
  render at their three levels under three distinct `basis` codes, and a row we
  cannot stamp renders the measure-level one — told apart from a publisher's
  silence (no `status`, no `publisher`) and from a school that does not run the
  program (no `program_availability`), with the figures beside it intact; the
  legend lists the new keys in payload order and names every legal status; the
  "legend, not a rulebook" tripwire still passes (no behavioural phrase added to
  the description).
- **Prompt**: the new paragraph exists once; no SAY rule in the DESCRIPTION.
