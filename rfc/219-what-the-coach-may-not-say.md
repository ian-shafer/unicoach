# RFC 219: What the coach may not say

**Slice:** `beyond/01/what-the-coach-may-not-say` (brief 0011, "beyond money").
**Lane:** A (rfc) — new coach copy, a new prompt version, a rollback knob.
**Base:** `main@bc37e1fe` (rebased forward during verify; every measurement
below was re-checked against it and none moved).

## Executive Summary

The coach prompt has never carried a **prediction rule**. Nothing in 252
authored lines stops the model calling a school a **reach, a match or a safety**
for this student, or giving a school a **fit score, a grade or a star rating**
of its own. Both are admission predictions, and an admission prediction is a
judgement the school's own admissions office makes, not one we make for it.
Brief 0011 **D3** and **D4** say so. This RFC states the rule, in two lines.

**The slice was cut in half at its approval gate, and the half that was cut is
the interesting part.** As designed, it also carried **D24**: the coach could
rank and filter on a school's completion rate, admission rate and undergraduate
headcount, but never quote them, because none of the three arrives with a year
or a publisher. Ian's question was "I'm not sure why we want to suppress what
the coach knows? what's the risk?", and the answer to it is that **suppression
was the wrong instrument**. See "Why the figure rule is gone". Those four lines
are dropped entirely — not softened, not kept in a weaker form — and the fix
they were reaching for moves to `beyond/11/stamp-the-search-figures`, which
gives those figures their publisher and period.

So what lands here is **two authored lines, one generated seed, one version pin,
one paragraph registration, two tests**. No migration, no table, no new data, no
payload change, no tool-description change, and **no change to any search
figure**.

## Why the figure rule is gone

Worth writing down, because the reasoning outlives this slice.

The risk in those three figures is not that they are wrong. It is that two of
the three are **true about a population that is not this student**:

- **Completion rate** (`C150_4`) counts **first-time, full-time** students from
  a **Fall 2018** cohort. It runs a median **12.3 points lower** for low-income
  students at the same school — so the school-level number is not the number a
  Pell-eligible family should plan against.
- **Admission rate** blends early and regular decision into one figure. It does
  not describe the door a regular-decision applicant actually walks through.
- **Undergraduate enrolment** carries almost no risk at all. It is a headcount.

Silence does not fix any of that. The sentence that makes such a number safe to
say is its **attribution** — "of students who started full-time in 2018, 93%
finished within six years" tells a family both the figure and who it is about,
and the second half is the part that was missing. A coach that cannot answer an
ordinary question about public data teaches families to ask somewhere else, and
somewhere else does not stamp anything.

So the instrument is **attribution, not suppression**, and it belongs in the
slice that has the vintages to attribute with. Nothing in this RFC touches a
search figure, which is also why the `CONFLICTS` edge this slice used to carry
against `beyond/11/stamp-the-search-figures` is **spent, not merely dormant**:
the two slices no longer write the same rule, the same copy or the same keys.
`beyond/11` — now RFC 226, in flight as this lands — owns those four figures
alone, and makes them quotable WITH their publisher, period and population. A
future run should read no warning here: there is nothing left for the two to
collide over.

## What the code says (measured 2026-09-17, on `main@bc37e1fe`, after rebase)

Read, not remembered. Re-measured after this run rebased 17 more commits
forward: a sibling run (RFC 227, one date seam) landed two date-shape lines and
cut **v39** while this run sat in review, so every figure below is the
post-rebase one. Each claim was re-measured on `bc37e1fe` and still holds — the
pin is `v39`, the authored body is 252 lines, and the five forbidden words still
have zero matches in the body this run started from.

| #  | Claim                                                                                                                                                                                                                                                                                                                                                                         |
| -- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| R1 | The live pin is `systemPromptVersion = "v39"` in `service/src/main/resources/service.conf`; the catalog tip is `db/schema/seed-coach-system-prompt-v39.sql`. The authored body `prompts/coach-system-prompt.md` is **252 lines**. The label this run cuts is computed at land by `bin/prompt-seed`, never written here.                                                       |
| R2 | Nothing in the prompt says **reach**, **match** or **safety** as a prediction, and nothing forbids a score, a grade or a rating of the coach's own: `safety`, `star rating`, `fit score`, `chance` and `likel` have **zero** matches in the authored body.                                                                                                                    |
| R3 | The nearest existing rules are line 5 — "Never invent facts about the student, or about specific colleges, deadlines, or requirements" — and line 67 — "Never coach test prep, never predict a score, and never give odds of admission with a score or without one". The second sits INSIDE the testing paragraph and its subject is a test score. Neither forbids the label. |
| R4 | The fit lens is a separate prompt row (`FitLensConfig.reasonPromptName` = `fit_lens_reason`), but its output reaches a family **only** through the coach: `CoachingService.composeSystem` appends `"I found a school you'd love: … — ${fit.rationale}"` to the coach's own system text. See "The fit-lens question".                                                          |
| R5 | A new paragraph must be registered in the **`CoachParagraph`** enum in `SystemPromptCatalogTest` (test-only). The seed SQL and the `db/schema/ORDER` line are **generated at land** by `bin/prompt-seed` + `ship-order` — never hand-written.                                                                                                                                 |
| R6 | No byte or line budget guards the coach prompt: `SystemPromptCatalogTest` carries no ceiling constant, and RFC 203 deleted the tool-description ceilings. Two added lines cost no ratchet.                                                                                                                                                                                    |
| R7 | `matchObject` (`college/src/main/kotlin/ed/unicoach/college/CollegeMatchRow.kt:155,156,177-180`) puts all six outcome figures on every search row. **This RFC opens that file no more than it opens any other: no key is added, removed or renamed.**                                                                                                                         |

R2 and R3 together are the whole justification for writing anything at all. The
brief's own drift check warned that a restated rule is deleted at review (RFCs
192/199), so the check was run first: the two lines below say nothing the prompt
already says.

## Detailed Design

### The paragraph

Two lines, appended as one block **immediately before** the money paragraph
("When the student has schools on their college list"), i.e. after the opening
conduct-and-formatting lines and before the first tool paragraph. Position is
deliberate: it is a standing "never", a sibling of the existing "never invent
facts" line (R3), and it must be read before the tool copy that hands the coach
the figures a prediction would be built from.

> Never call a school a reach, a match or a safety for this student, and never
> give a school a fit score, a grade or a star rating of your own: each of those
> is an admission prediction wearing another word, and you do not make one.
>
> Say what we do hold instead — what a school costs this family, what it looks
> for in an applicant, and what it reported about the class it admitted — and
> say that the school's own admissions office is where a judgement like that is
> made.

Four things about it, each deliberate:

- **It is an ADDITION.** Nothing is deleted, nothing is reworded, no existing
  paragraph opener moves, and no byte-frozen sentence is touched.
- **It licenses before it forbids.** A refusal with nothing behind it is a worse
  answer than a bad number. The second line names the three things we do hold
  and routes the family to the office that actually decides.
- **It says the figures in plain English**, never the payload key names — the
  prompt's standing rule against reading a field name aloud.
- **It is permanent.** These two lines are policy (D3, D4), not a placeholder
  for missing data. No later ingest retires them, which is exactly what
  distinguishes them from the rule that was cut.

### The fit-lens question, decided

The slice leaves open "whether the fit-lens prose needs its own rule or inherits
this one". **It inherits this one, and no second prompt version is cut.** R4 is
the reason: the fit lens writes a rationale into `fit_suggestions`, and the only
path from there to a family is `CoachingService.composeSystem`, which appends it
to the coach's own system text. The coach is the speaker; the coach's rule
governs what is spoken. Cutting a second version of a second prompt to restate a
rule that already binds the only speaker is the deformation D36/D37 forbids, and
D23 (copy in proportion to how often a limitation bites) argues the same way.

### Prompt version and rollback

`bin/prompt-seed` cuts the next coach version at land from the authored file, so
the label is computed from the tree that lands and is never a literal here. The
catalog tip is **v39** as this is written; this run expects **v40**.

Rollback: `COACHING_SYSTEM_PROMPT_VERSION=<the version below the pin>` in
`service.conf`. It is a **WHOLE** rollback: this run changes no Kotlin outside a
test file, so flipping the pin removes both lines and strands nothing in another
home. The author's append-only comment block above the pin records it;
`bin/prompt-seed` owns the assignment line itself.

### A note for the next prompt author: nobody is measuring the whole file

Not an argument against these two lines — they are justified on their own, and
R6 records that no budget exists to spend. It is an observation that belongs
where the next author will trip over it, because no single RFC is positioned to
see it.

Measured from git on 2026-09-16, `prompts/coach-system-prompt.md`:

| Date       | Lines | What landed                                              |
| ---------- | ----- | -------------------------------------------------------- |
| 2026-09-10 | 158   | the body became a Markdown file (RFC 185)                |
| 2026-09-13 | 208   | answer shape, one-rule-one-home, never open with money   |
| 2026-09-14 | 242   | testing calendar, score bands, school-day test, subjects |
| 2026-09-15 | 250   | the FAFSA process paragraph (RFC 216)                    |
| 2026-09-16 | 252   | date shape: short month name, day, year (RFC 227)        |

**158 to 252 lines in six days**, 94 of them added by eight runs across five
briefs, each of which was individually right and none of which read the file as
a whole. This run makes it 254. Every one of those runs could truthfully say
"two lines", and R6 is true every time it is checked: there is no ceiling, so
nothing ever refuses.

The risk is not size; it is that a long prompt dilutes its own standing rules,
and no run that adds one line is the run that can tell. What is needed is a
measurement of the WHOLE file — does the model still obey line 5 at 252 lines? —
and that is a product question for `/chart`, not something an implementation
slice may decide for itself. Written here because this is the file the next
prompt author reads before adding their own two lines.

## Files Modified

| File                                                                      | Change                                                                   |
| ------------------------------------------------------------------------- | ------------------------------------------------------------------------ |
| `prompts/coach-system-prompt.md`                                          | +2 lines, one block, before the money paragraph. Nothing else            |
| `service/src/test/kotlin/ed/unicoach/coaching/SystemPromptCatalogTest.kt` | +1 `CoachParagraph` entry, +1 accessor, +2 tests                         |
| `service/src/main/resources/service.conf`                                 | +comment block above the pin (author's). The pin LINE is written at land |
| `db/schema/seed-coach-system-prompt-v<N>.sql`, `db/schema/ORDER`          | GENERATED by `bin/prompt-seed` / `ship-order` at land                    |
| `rfc/219-what-the-coach-may-not-say.md`                                   | This RFC                                                                 |

No payload file is opened: **no figure key is deleted from any tool payload**,
which is one of the slice's acceptance criteria and is verifiable from this
table (R7).

## Implementation Plan

1. Insert the two lines into `prompts/coach-system-prompt.md`, immediately
   before the money paragraph.
2. Add the `CoachParagraph` entry, the accessor and the two tests below.
3. Write the `service.conf` comment block for the new version.
4. `nix develop -c bin/prompt-seed -b <BASE_SHA>`, `nix develop -c bin/format`,
   `nix develop -c bin/test`.
5. At land, inside the lock: `ship-rebase`, `bin/prompt-seed -b <BASE_SHA>`
   again (it recomputes the label against the tree that is landing),
   `ship-order`, `ship-squash`, `bin/format`, `git add` the enumerated paths,
   the full hook, `ship-land`.

## Tests

Both are in `SystemPromptCatalogTest`, over the **served** row — the body
`service.conf` pins — never over `prompts/coach-system-prompt.md`, because the
artifact under change may not be its own witness.

1. **`the served prediction paragraph refuses a label and offers what we hold
   instead`**
   — reads the new paragraph through the existing `paragraphAt` scoping and
   asserts, with `assertParagraphStates`:
   - it forbids the labels — but by asserting the REASON,
     `is an admission prediction wearing another word, and you do not make
     one`,
     and not the three labels themselves. The labels are already the
     `CoachParagraph.PREDICTION` opener that `paragraphOf` must match to find
     this paragraph at all, so an assertion on them could never fail;
   - it forbids an invented score: `fit score, a grade or a star rating`;
   - it names the alternative rather than only refusing:
     `what a school costs
     this family` and
     `the school's own admissions office`. A rule that kept only the prohibition
     would be a different decision from the one approved — the refusal on its
     own is the failure mode this slice was narrowed to avoid;
   - it sits BEFORE the money paragraph in the served body. `paragraphAt` finds
     an opener anywhere, so without this the block could move below the tool
     copy and every assertion above would stay green — and position is a claim
     this RFC makes (see "The paragraph"), so it is a claim a test owns;
   - `assertParagraphKeepsTheStandingCopyRules` over the paragraph.

2. **`coach v39 stays selectable and does not carry the prediction paragraph`**
   — the rollback target this run documents is still in the insert-only catalog,
   is still a whole coach prompt, and does **not** carry the new opener. The
   literal version is deliberate: `system_prompts` rows are immutable, so this
   assertion stays true forever, where a test keyed on "the version one below
   the pin" would start failing the moment a LATER prompt run lands on top of
   this one. The generic `the version one below the pin is still selectable`
   test already covers the moving half.

**No assertion counts lines, bytes or paragraphs.** There is no budget on this
file (R6) and this run does not invent one.

**No test asserts anything about a search figure**, in either direction. This
run holds no opinion on how the coach quotes a completion rate; that is
`beyond/11`'s subject, and a test here would pin its answer before it is made.
