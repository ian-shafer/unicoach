# RFC 245 — Your field in search

**Slice:** `beyond/08/your-field-in-search` (brief `product/0011-beyond-money`)
**Status:** proposed

## Summary

A student who has told us what they want to study gets search results that SAY
whether each school teaches it, and a coach that leads with the ones that do. No
school is ever removed because of a stored field. Only an explicit filter the
model writes may remove schools, and when it does, the result now says how many
it removed.

RFC 205 already stores the answer (`intended_subjects`, up to three subject
slugs, four statuses). Nothing on the search path reads it. This RFC wires the
stored answer into the two search doors — `search_colleges` and
`similar_colleges` — as a LABEL and an OFFER, and changes no ranking anywhere.

## Context

- The index already speaks the same vocabulary:
  `college_search_index.subject_slugs` is materialised from the SAME `subjects`
  taxonomy the student picks from (`CollegesDao.kt:3083-3095`,
  `WHERE s.status = 'active'` at `:3089`), so no code-to-word step and no
  4-digit/6-digit widening question arises. The spec called that question MOOT
  and it is.
- The column is TRI-STATE (`0064.create-search-index-and-subjects.sql:178-179`,
  comments `:222-230`): `NULL` = this school has no programme census at all
  (unjudgeable); `{}` = census present and none of its programs belongs to a
  taxonomy subject (a judged no); non-empty = the subjects it is recorded as
  teaching. `cip_codes` has no `{}` state, which is why the label reads
  `subject_slugs` and nothing else.
- Both search doors already share ONE adapter that resolves family context and
  merges an offer: `ResidencyScopedChatTool` (`:31-51`) — one read, one call,
  one merge, built for RFC 169's residency offer. The field offer is the same
  shape, so it reuses that seam rather than growing a second one.
- A row is a fixed literal-key JSON object (`CollegeMatchRow.kt:247-321`). The
  `ScorecardOutcomeCitations.Citation` registry governs STAMPED FIGURES — a
  number with a publisher, a population and a vintage — not every key
  (`pell_share`, `median_earnings_10y_after_entry_usd`, `name`, `control`,
  `programs` are all outside it). `teaches_your_field` is a WORD, not a figure,
  so it needs no `Citation` entry — and it must not be a number, or
  `BareSourceCodeGuard`'s numeric contract (`CollegeMatchRow.kt:213-232`) would
  either refuse it or advertise it as an unstamped figure
  (`CollegeSearchTool.kt:261-264`).

## Decisions

Inherited from the brief and NOT re-litigated here: **D21** (a stored field
never cuts; it orders and labels; an explicit typed filter may cut and must say
how many schools it removed; reuse RFC 169's offer verbatim), **D22/C15** (the
field is never a distance dimension; the SUBJECTS axis stays out of `DEFAULTS`),
**C14** (the tri-state above), **D20** (no stored field means the result renders
exactly as it does today), **D23** (the caveat budget), **D3/D4** (no scores, no
chancing), **0001 D11** (guided, not gated), **0001 D12** (value before ask).

Decided in this RFC:

**A1 — the stored field labels; it does not re-rank in SQL.** The label is a new
row key; the ORDER BY is untouched, and so is the LIMIT. The coach is told, in
one prompt line, to lead with the schools whose label says yes. Promoting
matching schools inside the SQL sort would let a stored field push a school out
of the returned page — a removal by LIMIT, which is exactly what "a stored field
never cuts" forbids — and would quietly break a requested sort ("cheapest ten"
would stop being the cheapest ten). "Context orders" is honoured where the
answer is composed, not where the page is chosen.

**A2 — both doors carry the label; the offer is merged once, in the shared
adapter.** `ResidencyScopedChatTool` becomes `SearchScopedChatTool`: it resolves
the family's residency AND the student's stored subjects, hands both to the
chat-free `:college` tool, and merges both offers. One read, one call, one
merge, unchanged in shape.

**A3 — the label names WHICH of the student's subjects matched.** A student may
store up to three. `teaches_your_field: "yes"` alone would force the coach to
guess which one, and guessing is the thing the whole brief forbids. The row
carries `your_subjects_taught` — the intersection, in the student's own subject
words — present only when the label is `yes`.

**A4 — the cut report is measured for the `subject` filter only.** When, and
only when, the model wrote a `subject` filter, the result carries
`removed_by_filter: { "subject": N }`: the number of schools that matched every
OTHER filter and were removed by this one. It is measured with the machinery
that already exists for exactly this question
(`createMatchClauseExcluding`/`bindPredicateExcluding`,
`CollegesDao.kt:1023-1040`), as a second `count(*) FILTER (...)` in the count
statement the search already runs. Generalising it to all 21 filter predicates
is not this slice's job and is recorded as an open item.

**A5 — no schema change.** No new table, no new column, no migration. Nothing to
approve as DDL: the answer store landed with RFC 205 and the index column landed
with 0064.

## Detailed Design

### 1. The label

One pure rule, one home, no database needed to test it. A SEALED TYPE and not an
enum, because the subjects that produced a `yes` are part of the yes, so "yes,
but I cannot name which" — the statement D23/A3 exists to prevent — is not a
value a caller can hold:

```kotlin
sealed interface TeachesYourField {
  val value: String

  /** NEVER empty: the overlap that produced the yes. */
  data class Yes(val subjectsTaught: List<String>) : TeachesYourField

  /** The census is ON FILE and names none of their subjects: a judged no. */
  data object No : TeachesYourField

  /** The question could not be judged, and WHOSE silence it is. */
  data class Unknown(val cause: Cause) : TeachesYourField

  enum class Cause { NO_CENSUS, SUBJECT_NOT_JUDGEABLE }

  companion object {
    fun of(
      indexedSubjectSlugs: List<String>?,
      storedSubjectSlugs: Set<String>,
      taxonomy: SubjectTaxonomy,
    ): TeachesYourField? { ... }
  }
}
```

The three WIRE WORDS are still `yes` / `no` / `unknown`; `Cause` rides inside
`Unknown` without adding a fourth.

`NULL` is `Unknown(NO_CENSUS)` and is never spoken as a no — the same rule the
search already holds for every other unjudgeable column (`excluded_unknown`,
prompt line 123). The judged `{}` is a NO, because that school HAS a census and
none of it is this subject.

A stored subject can only be judged against the LOADED taxonomy the index is
materialised through ([SubjectTaxonomy], a type of its own so it cannot be
transposed with the student's own set), so a subject that taxonomy does not
carry — retired, newer than this process's snapshot, or missing because the
codebook never loaded — is `Unknown(SUBJECT_NOT_JUDGEABLE)` and never a `no`:
our silence is never spoken as the school's answer. Those dropped subjects are
also logged once per call, so an unloaded taxonomy is visible to an operator
rather than inferred from unlabelled pages.

`of` returns `null` for exactly ONE case: this student stored no subject at all,
so no question was asked and the row writes no key (D20). "We could not judge"
and "you told us nothing" are different facts and the wire says so.

`CollegeMatch` gains `programmeCensus: ProgrammeCensus` — `NotReported`, or
`OnFile(subjectSlugs)` whose list may be EMPTY — projected by adding
`subject_slugs::text[] AS subject_slugs` to BOTH statements that map a
`CollegeMatch` — the search page select and the peer page select — because one
`mapMatch` reads both, and named there. A `List<String>?` would carry the
distinction by convention only, and the same DAO keeps the opposite convention
one function away (`getStringList` maps NULL to an empty list), so one
`orEmpty()` would turn our silence into a judged no. The similarity ANCHOR
select already reads the column the same way, which is where the cast form comes
from; its own nullable field is pre-existing and not this slice's.

### 2. The row

`matchObject` takes the student's stored subject slugs. When that set is EMPTY
the object is byte-identical to today's — no key, no null, no placeholder (D20).
When it is non-empty the label key is always appended after `programs`, and the
subject list beside it only for a `yes`:

```json
"teaches_your_field": "yes",
"your_subjects_taught": ["nursing"]
```

`your_subjects_taught` is present only when the label is `yes` — the same
discipline `programs` already follows: a key that means "what matched" has no
answer to give when nothing matched, and an empty array reads as a claim about
the school. The emitter branches EXHAUSTIVELY on the label, so a fourth state
cannot take the no-list path with nobody having decided it should.

Both keys are STRINGS. `listNumericFields` skips strings
(`BareSourceCodeGuard.kt:134`), so neither enters the numeric contract, neither
is advertised as an unstamped figure, and no `Citation` entry is owed.

Both search doors render through this one function, so `similar_colleges` gains
the same two keys with no second spelling — and, because a key nothing
advertises is a key the model must guess at, the shared note describing them
lives in `CollegeMatchRow.kt` beside `FIGURE_SOURCES_NOTE` and is spliced into
BOTH tool descriptions. (`similar_colleges` carries neither of the two
figure-key sentences today; this RFC does not fix that, but it does not repeat
it.)

### 3. The offer

`SearchScopedChatTool` (renamed from `ResidencyScopedChatTool`) resolves the
intended-subject answer alongside residency and merges BOTH offers in one pass.
`addResidencyOffer` becomes `addSearchOffers(result, residency, field)` — one
copy of the object, two emitters that each decide whether they have anything to
say — so there is no second merge to keep in step. The field's keys mirror
residency's: `intended_subject_offer` when nobody has asked, and
`intended_subject_unavailable` when the read FAULTED. Four statuses, four named
branches, no `else`:

| `AnswerStatus` | Offer? | Label? | Reported? | Why                                                                                |
| -------------- | ------ | ------ | --------- | ---------------------------------------------------------------------------------- |
| `UNANSWERED`   | yes    | no     | no        | never asked — this is the one offerable state, as residency's `NotAsked` is        |
| `ANSWERED`     | no     | yes    | no        | they told us; the label is the whole point                                         |
| `DECLINED`     | no     | no     | no        | a remembered decline is an answer (RFC 169: the decline is stored, not chat state) |
| `UNDECIDED`    | no     | no     | no        | "I do not know yet" is a real answer (RFC 205), not corruption and not silence     |

A read that FAULTS is the fifth state, and one of the two that report: the
result carries `intended_subject_unavailable`, one sentence keyed by the cause,
the way `ResidencyOffer.UNAVAILABLE_KEY` already does for residency. Without it
a faulted read is indistinguishable from "this student stored nothing", and the
coach would tell a family we hold no subject for them when the truth is that we
do not know. The two stored answers say nothing, because repeating a decline
back is asking again in all but name.

Rows that DISAGREE with each other are the sixth: an `ANSWERED` status holding a
subject set outside 1..`MAX_SUBJECTS`. Both of that table's invariants are
deferred constraint triggers, so a hand-edited row or a second concurrent writer
can land one. It is logged as the corruption it is — with the student, the
status and the slugs — and degrades to `Cause.CORRUPT_ANSWER` with its own
sentence, exactly as `Residency.Cause.CORRUPT_PROFILE` does, because a SIDE read
must never turn a working search into an error. The type that carries a stored
field still refuses the shape in its own `init`, which is then only reachable
from a second construction site.

The emitter branches exhaustively on the three states, so the invitation is made
in exactly one of them by the TYPE rather than by a flag beside it.

`UNDECIDED` is the one state residency does not have, and the reason it is
listed is that it must NOT fall into either default: it is not offerable (asking
again is exactly what prompt line 141 forbids) and it is not a label (there is
no subject to label against).

The offer copy says what the answer would change before it asks, and a failed
read leaves the search working and unlabelled — the same degradation
`ResidencyScopedChatTool`'s `readProfile` seam already has.

### 4. The cut report

In the count statement the search already runs (`CollegesDao.kt:938`), a second
aggregate:

```sql
count(*) FILTER (WHERE <match clause EXCLUDING the subject predicate>)
```

The difference against `total_matches` is `removed_by_filter.subject`, carried
to the tool on `CollegeSearchPage.removedBySubjectFilter` — null, and the key
absent, when no `subject` filter was written. BOTH search doors emit it (A4
restricts the report to the `subject` FILTER, not to one door): the peer list
takes `subject` through the same shared vocabulary, so it can shorten a peer
list, and its own count statement measures the cut over its candidates with the
shared-axis requirement kept inside the arm. Both descriptions say the key
exists: §2's argument, applied to both doors because both render the same row.

This answers a question the search could not answer before: `excluded_unknown`
says "could not be judged", never "your filter removed N". The two are different
facts and not disjoint sets, because this filter also removes the schools it
could not judge: a school with no programme census can sit inside both numbers.
They are now keyed by the same word, too — the `subject` filter's
`excluded_unknown` entry was keyed by the index COLUMN (`subject_slugs`), which
is a name the prompt forbids the coach to say and one the model cannot write
back, so it is reported under the filter field like the cut beside it.

### 5. The prompt

New prose in the intended-subjects section (`prompts/coach-system-prompt.md`
lines 139-144), one sentence per line, authored in that file and seeded by
`bin/prompt-seed` (v48). In substance:

- when a result row carries `teaches_your_field`, it is about the subjects this
  student already stored;
- lead with the schools whose label is `yes`, and say which subject matched from
  `your_subjects_taught` rather than from memory;
- `unknown` means the question could not be judged — we hold no programme census
  for that school at all, or one of their stored subjects is one our loaded
  taxonomy does not carry — so never say the school does not teach the subject;
- a stored subject never shortens the list: every school the filters matched is
  still there;
- when the tool reports `removed_by_filter`, say how many schools that filter
  removed, in the same plain words line 123 already asks for.

Any new paragraph opener needs its `CoachParagraph` entry, or the lines join a
neighbouring paragraph's assertion sweep (`SystemPromptCatalogTest.kt:220-250`).
Adding v48 also re-points RFC 240's parent-invite rollback test: it read the
version below the PIN, which stops being the version that paragraph arrived in
the moment an unrelated RFC seeds a new body, so it breaks for any run that
seeds one. It becomes a claim about the literal v46/v47 boundary, on that
suite's own v17/v18 precedent. None of this copy goes in the
served-outcome-figures paragraph, so `FIGURE_COMPARISON_WORDS`'s guarded
surfaces are untouched — and none of it compares one figure with another.

### 6. The census false-negative — a footnote, once (D23)

A programme a school started last year has no graduates in the completions
census, so it reads as NO. That is a rare limit: it earns ONE sentence, in the
`teaches_your_field` tool-description note, and nothing in the prompt and
nothing per row.

## Files Modified

| File                                                                      | Change                                                                                                                                |
| ------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------- |
| `db/src/main/kotlin/ed/unicoach/db/models/CollegeMatch.kt`                | `programmeCensus: ProgrammeCensus` — the census as a type, so `NULL` and `{}` cannot be merged                                        |
| `db/src/main/kotlin/ed/unicoach/db/dao/CollegesDao.kt`                    | project `subject_slugs` in both page selects; second count aggregate excluding the subject predicate                                  |
| `db/src/main/kotlin/ed/unicoach/db/models/CollegeSearchPage.kt`           | `removedBySubjectFilter: Int?` — the size of the `subject` cut, null when none was written                                            |
| `db/src/main/kotlin/ed/unicoach/db/models/CollegeSimilarityOutcome.kt`    | the same field on the peer page, measured over this door's candidates                                                                 |
| `college/src/main/kotlin/ed/unicoach/college/TeachesYourField.kt`         | NEW — the sealed label type, its causes and its one rule                                                                              |
| `college/src/main/kotlin/ed/unicoach/college/CollegeMatchRow.kt`          | the two keys; the shared description note                                                                                             |
| `college/src/main/kotlin/ed/unicoach/college/CollegeSearchTool.kt`        | stored slugs through to the row; `removed_by_filter`; note spliced                                                                    |
| `college/src/main/kotlin/ed/unicoach/college/SimilarCollegesTool.kt`      | stored slugs through to the row; `removed_by_filter`; both notes spliced                                                              |
| `college/src/main/kotlin/ed/unicoach/college/CollegeQueryVocabulary.kt`   | `SUBJECT_FIELD` — the one spelling of the `subject` filter word the schema, the parse and the cut report all quote; `SubjectTaxonomy` |
| `service/src/main/kotlin/ed/unicoach/coaching/ResidencyScopedChatTool.kt` | renamed `SearchScopedChatTool`; resolves subjects too                                                                                 |
| `service/src/main/kotlin/ed/unicoach/coaching/IntendedSubjectOffer.kt`    | NEW — the offer, mirroring `ResidencyOffer.kt`                                                                                        |
| `service/src/main/kotlin/ed/unicoach/coaching/ResidencyOffer.kt`          | `addResidencyOffer` moves to `addSearchOffers`, which merges both offers, beside the adapter that calls it                            |
| `service/src/main/kotlin/ed/unicoach/coaching/StudentScopedChatTool.kt`   | `isToolRefusal`'s KDoc re-pointed at the renamed merge helper                                                                         |
| `service/.../CollegeChatTool.kt`, `SimilarCollegesChatTool.kt`            | the widened `search(...)` signature                                                                                                   |
| `rest-server/.../Application.kt`                                          | wiring (one more dependency on the two adapters)                                                                                      |
| `prompts/coach-system-prompt.md`                                          | the prose above (v48 via `bin/prompt-seed`)                                                                                           |
| tests                                                                     | below                                                                                                                                 |

No `:college` service changes: the stored slugs reach `matchObject` in the two
tools, and no service and no ranking is touched (A1). There is no
`SimilarCollegesService` — the peer door calls the same `CollegeSearchService`.

No migration. No `db/schema/ORDER` line except the generated prompt seed.

## Implementation Plan

1. `TeachesYourField` + its unit tests (no DB).
2. `CollegeMatch.subjectSlugs` + the projection in BOTH page selects (the search
   one and the similarity one — one row serializer, two statements);
   `CollegesDaoTest` for the three states off the real index.
3. `matchObject` keys + the shared description note; both tool descriptions.
4. Thread the stored slugs through both `:college` TOOLS —
   `CollegeSearchService` produces the page and never sees them, which is what
   keeps the ranking out of reach of a stored field.
5. `removed_by_filter.subject` in the count statement.
6. Rename the adapter, add `IntendedSubjectOffer`, wire it, four status
   branches.
7. Prompt prose; `nix develop -c bin/prompt-seed -b "$BASE_SHA"`; suite.

## Tests

The two the slice names, first, and both written so they FAIL if the claim stops
being true:

- **No school is removed by a stored field.** One search, run twice against the
  same fixture — once for a student with a stored field, once for a student with
  none — asserts the returned `college_id` list and `total_matches` are
  IDENTICAL, and that the only difference between the two payloads is the two
  new keys. Proved non-vacuous by making the stored field narrow enough that a
  filtering implementation would return fewer rows.
- **Similarity ranking is byte-identical with and without a stored field.** Same
  anchor, same axes, run with and without a stored field: the ordered
  `college_id` list, every `distance`, and `axes_used` are equal. Plus the
  standing assertion that `DEFAULTS` is still `[SIZE, SELECTIVITY, SETTING]`
  (`SimilarCollegesToolTest.kt:464`).

Then:

- `TeachesYourFieldTest`: NULL -> unknown, `{}` -> no, disjoint -> no, one of
  three overlapping -> yes with only the matched slug in `your_subjects_taught`;
  a subject the loaded taxonomy does not carry -> unknown and NEVER a judged no;
  and the one absence, a student who stored nothing -> no label at all.
- `CollegeSearchToolTest`: no stored field -> the payload has neither key;
  stored field -> both keys, `your_subjects_taught` absent on a `no` row; a
  stored subject outside the taxonomy -> `unknown` on every row, including the
  school whose census IS on file.
- A stored field the loaded taxonomy cannot judge leaves the search working and
  rows labelled `unknown`; rows that disagree with their own status leave it
  working, unlabelled and reported as corruption rather than as a decline.
- `removed_by_filter`: absent with no `subject` filter; present and correct with
  one — proved by deleting the second aggregate and reading the named red.
- The four offer branches, one test each, including `UNDECIDED` offers nothing
  and labels nothing, and a FAILED subject read leaves the search working.
- `SystemPromptCatalogTest`: the new lines exist, in the intended-subjects
  paragraph, and the pinned body equals the authored file.

## Open items (not this slice)

- **Z2, recorded and deliberately not widened:** the search filter
  (`CollegesDao.kt:757`), the sort (`:2049`) and the tool's sort word
  (`CollegeSearchTool.kt:61`) all still name `completion_rate_150pct_4yr_share`
  while the coach leads with the eight-year figure, so 1,290 colleges with a
  completion figure cannot be reached by a completion-filtered search. This RFC
  touches those files and changes none of that.
- `similar_colleges` advertises neither the stamped nor the unstamped figure
  keys (`SimilarCollegesTool.kt:1124`).
- `removed_by_filter` is measured for `subject` only, at both doors.
- A student may hold a DEPRECATED subject slug
  (`IntendedSubjectsService.kt:258`) while the index carries active slugs only,
  so no school's census can name it. That is FIXED here, not deferred: such a
  subject is unjudgeable and reads `unknown`, never a judged `no` (§1). What is
  still brief 0011's rather than this slice's is the taxonomy's deprecation path
  itself — nothing here offers the student a way to replace a retired subject
  with a current one.
