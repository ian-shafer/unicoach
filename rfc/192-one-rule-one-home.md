# RFC 192 — One rule, one home

**Slice:** `concise/03/one-rule-one-home` (product brief 0009, "Say it once").
**Status:** implemented. **Supersedes nothing.** It moves rules between two
homes and deletes duplicates; no committed decision changes.

## The problem, measured

The `college_cost_profile` tool DESCRIPTION and the coach system prompt are both
sent to the model in one context window, and they largely say the same things.
Every figure-reading rule, every "never subtract", every "say that sentence
rather than writing your own" exists twice — once as ordinary Kotlin in
`CollegeCostChatTool.DESCRIPTION`, and once as authored copy in
`prompts/coach-system-prompt.md`. Five RFCs each appended their own
self-contained paragraph to both, because an append is the safe edit and a merge
is not.

Measured on the INVENTORY base, `main@493dd34f` — the tree the 163 items were
classified against — not copied from the brief:

| artifact                          | measured                    | the brief said |
| --------------------------------- | --------------------------- | -------------- |
| `CollegeCostChatTool.DESCRIPTION` | **18,386 bytes**            | 16,383         |
| `prompts/coach-system-prompt.md`  | **29,297 bytes, 183 lines** | 158 lines      |

The run then rebased onto the LAND base, `main@d2fc8bdf`, where RFC 191 had
added three lines to the same constant: **18,580 bytes**. Both bases are named
wherever a number is quoted, because the saving differs by 194 bytes depending
on which one you measure from, and a single unlabelled figure is how the brief's
own numbers went stale.

Both brief numbers were stale: RFC 189 and RFC 190 grew the DESCRIPTION after
the brief was written, and coach prompt v28 (RFC 186) and v29 (RFC 187) added 7
and 18 lines to the prompt. Every number in this RFC was measured after the
rebase onto `main@493dd34f`, which is also why the deletion pass is safe: it was
decided against the tree that includes v28 and v29, not against the snapshot the
brief described.

The DESCRIPTION was inventoried item by item — **163 items: 90 SCHEMA, 58
BEHAVIOUR, 14 both, 1 frozen copy**. Of the behaviour-bearing items, **46 are
already stated in the coach prompt** and exist in the tool for no reason other
than that nobody deleted them; **16 have no home but the tool**.

## Decisions

### D1 — The DESCRIPTION becomes a schema legend, and the byte bar is the one we measured

The DESCRIPTION's job is to say what each key means and what values it takes.
Every behavioural rule leaves: deleted where the prompt already says it, moved
to the prompt where it does not.

**The brief's "≤ 4 KB" bar is withdrawn, on two measurements.** A legend
compressed hard enough to fit 4,096 bytes measures 4,072 — **24 bytes of
headroom, 0.6%** — so the next engineer who adds one key fails a byte test that
has nothing to do with their change. That is a brittle test, and a brittle test
is a worse outcome than a slightly larger legend. Worse, hitting 4,096 requires
stripping the prose out of the one artifact in this repository whose only reader
is a language model: compressing English to hit an invented byte count, in a
document whose whole job is to be understood, is backwards.

So the legend is written to READ, and then measured, and the ceiling is set at
the real measured size with deliberate headroom. The test's KDoc says what
`CostReportPageTest.WORD_CEILING` says: adding a key means consciously raising
the number, with the same question asked — is this key worth its bytes?

**The measured result: 6,845 bytes, from 18,386 at the inventory base
`main@493dd34f` and 18,580 at the land base `main@d2fc8bdf` — down 62.8% and
63.2% respectively.** Both bases are named because RFC 191 added three lines to
the constant between them (D7). The ceiling is `LEGEND_BYTE_CEILING = 6845` —
the measurement ITSELF, not a budget with headroom (D9). The number is higher
than the 5,479-byte draft the research pass measured, and the reason is worth
recording rather than hiding: that draft used short display names where the code
renders the real wire constants, quoted no value codes, and carried none of the
schema sentences the surviving assertions pin. With those restored at the same
prose density the legend lands at 6,845. Two earlier drafts measured 10,660 and
9,129 bytes and were cut back rather than shipped. So the honest reading of the
brief's bar is that it was wrong by a factor of 1.6 even against an unreadable
legend, and by 1.65 against the one that shipped.

This is the **fifth** number in brief 0009 to be authored ahead of its evidence
and corrected by measurement (SD1, SD2, SD4, and gate-1 D4 before them). It is
recorded as SD6 against the spec.

### D2 — The prompt's duplicate SAT sentence goes; the testing-block one stays

Two lines state one rule. Verbatim, side by side:

> **line 64** (coach prompt **v29**, RFC 187, slice
> `test/02/does-this-school-want-a-score`):
> `Name the SAT and the ACT together and in the same breath: the SAT is never the default noun, because in a dozen states the ACT is the test the student will actually sit.`
>
> **line 183** (coach prompt **v28**, RFC 186, slice
> `test/01/the-coach-knows-today`):
> `Never treat the SAT as the default test: the SAT and the ACT are both college entrance tests, and in a dozen states the ACT is the one the student will actually sit.`

Same rule, same reason, same dozen states. **Line 64 survives** — it sits inside
the testing block the rule belongs to and is pinned by
`SystemPromptCatalogTest`; line 183 sits in the graduate-scope paragraph, where
the SAT is not the subject. Line 183's only unique content is the gloss "both
are college entrance tests", which is framing, not a rule.

**Line 183 arrived with v28, from brief 0010's slice
`test/01/the-coach-knows-today`**, days before this slice ran. It is named here
so that nobody learns their sentence was deleted by finding it missing. Deleting
it re-points exactly one assertion, `CoachingServiceTest`'s scope-refusal test,
at line 64's wording; RFC 186 D13 stays pinned.

### D3 — Fourteen proven restatements stay, because deleting them costs a committed guard

The prompt really does restate itself. The money vocabulary is written out four
times; the loans rule five. **But almost none of it is deletable**, and this is
worth stating plainly rather than implying a win that is not there: each
repetition either carries unique per-paragraph content — so deleting the line
would trim a clause, which this slice's deletion-only bar forbids — or is pinned
by a test assertion SCOPED to its own paragraph, so deleting the line means
deleting a committed guard as well.

**The real prompt saving is 491 bytes, 1.7% of the file.** Four lines: 46, 82,
95, 183.

These fourteen lines are real duplicates and are deliberately left, each with
the paragraph-scoped assertion that holds it: 86, 87, 91, 92, 93, 115, 116, 124,
130, 136, 138, 139, 159, 166. A later run may revisit them as a conscious trade
of a test for bytes. This slice is not authorised to make that trade.

### D4 — The prompt net grows, and that is the slice working

Four lines leave the prompt and the sixteen behaviour rules with no other home
arrive, so the prompt gets **longer**: 183 lines and 29,297 bytes become **196
lines and 31,329 bytes**, up 2,032 bytes (the seventeenth line is RFC 191's
rule, D7). The DESCRIPTION gets **11,735 bytes shorter** — 18,580 bytes become
**6,845** against the land base, down 63.2%, or 18,386 -> 6,845 and 62.8%
against the inventory base. Both artifacts sit in the same window, so the NET is
what matters and it is smaller than the gross: **the context window gets 9,703
bytes shorter**. The DESCRIPTION's saving is not the window's saving. The rule
count is unchanged; the DUPLICATE count falls. The model stops being told the
same thing twice in one window. Read "the prompt got longer" as the slice
failing and you have read the wrong number.

### D5 — Deletion only, and no opener is touched

No prompt sentence is reworded — ~89 of its lines are pinned as string literals,
nine sentences are byte-frozen against the immutable v19 row, and a
`CoachParagraph` opener test fails when a paragraph's first sentence moves.
Every change to the prompt in this RFC is a whole-line deletion or a whole-line
addition. All 18 paragraph openers are untouched.

### D6 — One new prompt version, v30, rollback v29

`bin/prompt-seed` cuts the version at land because the authored body differs
(RFC 185 D6). Rollback is `COACHING_SYSTEM_PROMPT_VERSION=v29`: that row is
immutable, stays in the catalog, and restores both halves of this slice at once
— the prompt without the moved rules. The tool legend is ordinary Kotlin and its
revert is a code revert, not a version flip, so a rollback to v29 leaves the
legend in place; that is the honest limit of the knob and it is named here
rather than discovered later.

**No DDL.** No table, no column, no hand-written migration, no seeded row beyond
the generated coach prompt seed this slice's own version mint requires.

### D7 — RFC 191's grouping rule is carried forward by hand, and this is the hazard the slice was held for

This slice is a DELETION pass over two artifacts that other runs are ADDING to.
While it was open, `main` advanced twice, and one of those commits — RFC 191,
`bb581547`, the sibling slice `concise/05/tiers-grouped-not-repeated` — added
three lines to the very constant this slice deletes:

> `Say each distinct tier sentence ONCE for the SET of figures on it, naming that set, never once per figure - where several entries carry the same tier, that is one sentence covering all of them.`

The rebase conflicted inside `DESCRIPTION`, and **resolving it by taking the
legend side would have dropped that rule with no test to notice** — the only
assertion that held it was `description.contains(...)` in
`CollegeCostChatToolTest`, which this slice was already rewriting. That is the
exact failure mode the slice was HELD from wave 1 to avoid, arriving from the
direction nobody guarded: the tool description rather than the prompt. It was
caught at Tier 0 review, by a reviewer checking the RFC's numbers against the
tree.

The rule is BEHAVIOUR, so it goes where every other behaviour rule went — the
prompt, as a seventeenth appended sentence in the figure-status paragraph:

> `Say each distinct sentence about the kind of number once for the SET of figures it covers, naming that set, never once per figure — where several figures share one kind, that is one sentence covering all of them.`

RFC 191's two description assertions are replaced by one assertion against the
SERVED prompt body, which is the stronger pin: it says the rule reaches the
model, not that a Kotlin constant mentions it. Item #35 in the map below is the
PRE-191 wording of the same rule, and RFC 191 refined it from "the default
group" to "every distinct tier"; the prompt now carries both halves, one
sentence each.

**The general lesson, and it is the one worth keeping:** a deletion pass must
re-derive its inventory after every rebase, not only before the first one.
"Rebase first" was followed and was not sufficient, because the base moved again
mid-run. What caught it was a reviewer re-verifying the RFC's measured numbers
against the actual tree rather than reading the RFC.

### D8 — A legend that does not match the schema is not a legend

Tier 2 found the defect that matters most in this RFC, and it is worth more
space than its MINOR label. The legend's `assurance` entry listed three ENGLISH
GLOSSES — "federal administrative record | required edit-checked survey | a
figure the college published about itself that nobody checks" — where the
payload carries `AssuranceTier`'s tokens: `administrative_record`,
`mandatory_survey`, `voluntary_self_report`. **No token in the legend matched
the payload**, and no test pinned the legend to the enum.

That is not cosmetic. This slice's whole premise is that the description becomes
a schema legend, so a legend that does not match the schema fails the slice's
own thesis — in the one artifact whose entire job is to explain the payload. The
fix interpolates the three `.value`s with the gloss in parentheses, exactly as
the `status` entry already did, and the test that pins the legend TO THE ENUM is
the half that must never be dropped: without it the same defect returns the next
time a tier is added.

**The general rule, stated for the corpus: a legend must be DERIVED FROM, or
PINNED TO, the thing it describes.** Hand-typing the vocabulary of an enum into
a document about that enum is a copy that cannot be kept true, and it is a near
relative of — not the same as — the rule that a guard must live outside the
artifact it guards (D7).

### D9 — The ceiling IS the measurement, not a budget with headroom

The first version of the byte test set `LEGEND_BYTE_CEILING` to 6,800 — the then
measured 6,743 rounded up — while its KDoc claimed
`CostReportPageTest.WORD_CEILING`'s contract. Those two things cannot both be
true: `WORD_CEILING` is set at its measurement EXACTLY, and that exactness is
what makes "you must consciously raise this number" a real statement. 57 bytes
of headroom is not a rounding — it is a free behavioural sentence ("Never
estimate a missing figure." is 32 bytes), which is precisely what the ceiling
exists to stop from creeping back. The ceiling is therefore set at the exact
measured size of the PUBLISHED description — 6,845 bytes, which is the first
legend's 6,743 plus the 102 bytes D8's enum tokens and the consistent value
quoting cost — and the rounding sentence is deleted. The string is built only
from compile-time constants, so an exact ratchet cannot flake.

Worth recording, because it is the failure this brief keeps finding: the
instruction that produced the rounded ceiling asked for BOTH "the real measured
size with a little headroom" AND "the same contract `WORD_CEILING` carries".
Those two halves contradict each other, and the contradiction survived until a
reviewer checked the sibling constant instead of believing the KDoc.

### D10 — The honest scope: "one rule, one home" was applied to ONE tool

Three other tool descriptions carry the same defect and are unguarded:
`FederalAidPolicyChatTool` restates prompt lines about the award year, loan
limits and not re-raising a declined question; `CollegeListChatTool` and
`MoneyProfileChatTool` restate their own prompt paragraphs. Neither the byte
ceiling nor the legend test is generalised to them. This RFC fixes the largest
offender — 18,580 bytes of it — and names the rest rather than quietly widening
its own scope. Three more slices of this shape exist, and that is a product
decision, not a thing a run should absorb.

## Detailed Design

### The legend

`CollegeCostChatTool.DESCRIPTION` is rewritten as a key legend: one entry per
wire key, giving its meaning and its legal values, in payload order. Three rules
hold it honest:

1. **Key names and value codes stay interpolated from their constants**, never
   hand-typed — `$SOURCE_KEY`, `${CostField.NET_PRICE.wireName}`,
   `${FigureStatus.IMPUTED_BY_PUBLISHER.value}`, and the example band range from
   `IncomeBand.OVER_110K.bracket`. That is the existing rule in this file and
   the reason `DESCRIPTION` is not `const`: a renamed key must move the legend
   with it, and a hand-typed band range would outlive the copy it quotes.
2. **A legend entry may say how to READ a key** — which key a total already
   includes, that an entry's own value wins over the default, that a `statement`
   is the sentence to speak. That is the key's meaning, not advice about the
   family.
3. **A legend entry never says what to say to the family.** Those rules live in
   the prompt.

### Where each behaviour rule lands

- **46 rules already in the prompt are deleted from the tool.** Each is listed
  in the map below against the prompt line that keeps it, so none becomes
  unreachable. This is gate-1 D1 applied to instructions instead of figures.
- **16 rules have no home but the tool.** Each becomes one appended sentence in
  the prompt paragraph that owns its subject, placed at the END of that
  paragraph so no opener moves. They are listed below.
- **One tool NAME left the file with its sentence.** Map row #133's rule — a
  corrected living plan is recorded as that school's own plan — survives at
  prompt lines 38 and 101, but the tool name `update_college_list` no longer
  appears in the cost tool's description at all. That is correct: the cost tool
  is not the tool that records a plan, and the prompt names the recording tool
  twice. It is recorded here because a dropped identifier is the kind of thing
  that looks like an accident later.

- **The frozen copy is untouched.** `AT_HOME_ASSUMPTION_STATEMENT` is already in
  the prompt verbatim at lines 72-73, so the tool's rendered copy of it goes;
  `FigureStatusCopy` and `AssuranceTierCopy` sentences are never quoted by
  either artifact — the legend says only that a `statement` key exists and the
  prompt says to speak it as written.

### The seventeen rules that needed a new home

Sixteen from the inventory, plus RFC 191's grouping rule carried forward at the
rebase (D7). These are the sentences that SHIPPED, read back from
`prompts/coach-system-prompt.md`, not the drafts the inventory proposed:

| item      | prompt line | the sentence                                                                                                                                                                                                         |
| --------- | ----------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| #74       | 33          | Where a school publishes only one tuition price, never say it applies to everybody — read the tuition key beside it to see which residency that price is for.                                                        |
| #76       | 34          | Where we hold no tuition price for a school at all, say we do not have it and never name a tuition figure for that school.                                                                                           |
| #80       | 35          | A fees-only figure is already inside that school's tuition and fees line: never add it to a total, and never subtract one of those prices from the other.                                                            |
| #126      | 83          | Where a result does not say whether a school has residence halls, say nothing about it either way: a missing on-campus price is not an answer.                                                                       |
| #151      | 84          | Median debt and median earnings carry no academic year: say those figures without one rather than borrowing a year from another figure.                                                                              |
| #154      | 98          | Where a comparison names which schools a year covers, match on the school's id and never read a year onto a school it does not name.                                                                                 |
| #159      | 99          | Where a comparison gives one basis sentence for every school, say it once and never invent a difference between them.                                                                                                |
| #11       | 156         | Two things in a cost result are never on the school's own tier: a line we assumed ourselves, and a field with no number at all — never read a publisher or a kind of number onto either.                             |
| #15       | 157         | A figure the result does not carry has no source and no kind of number either: say there is no figure, and never attach a publisher or a tier to it.                                                                 |
| #27       | 158         | Where we hold a figure only for an earlier year, say that year as the result gives it, and never fold that figure into a total beside figures from another year.                                                     |
| #35       | 159         | Say what kind of number a school's figures are once for the whole school, in the sentence the result gives you, rather than beside every figure.                                                                     |
| #36       | 160         | Where one figure carries a different kind of number from the rest, say that figure's own sentence after its status sentence.                                                                                         |
| #191 (D7) | 161         | Say each distinct sentence about the kind of number once for the SET of figures it covers, naming that set, never once per figure — where several figures share one kind, that is one sentence covering all of them. |
| #37       | 162         | Say the sentence about the kind of number exactly as the result writes it, and never rank the three kinds or turn them into a score.                                                                                 |
| #88       | 171         | Where a school's filing answered the aid-forms question and we could not read the answer, say that we could not read it, never that the school does not require the form.                                            |
| #93       | 179         | Name the school and the graduating year once for the borrowing figures, rather than beside each one.                                                                                                                 |
| #99       | 180         | The federal median debt figure and a school's own borrowing figures cover different students: say them separately, and never add or compare them.                                                                    |

### The four prompt deletions

| line | text                                                                                                                                          | the rule's surviving home                                                            |
| ---- | --------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------ |
| 46   | `Talk about all of it in plain words: never read a source's internal codes or field names out to the student.`                                | line 33, the `SOURCE_JARGON` paragraph opener, which states it for every source      |
| 82   | `Never write a data source's own code, and never say one aloud to a student.`                                                                 | line 33; the search paragraph's own `contains("code")` pin still passes from line 81 |
| 95   | `Grants and scholarships are what a net price already counts; never subtract loans, work-study, or a net price from any figure in the table.` | lines 18, 19, 79, 87, 89                                                             |
| 183  | `Never treat the SAT as the default test: ...`                                                                                                | line 64 (D2 above)                                                                   |

Side effect worth naming for a later reader: after line 95 goes, the comparison
paragraph states no subtraction ban of its own. The rule is stated four times
elsewhere and the licence scan over the whole body still holds, so the rule is
not weakened — but a future paragraph-scoped assertion about subtraction in the
comparison paragraph would have to be written against the body, not the
paragraph.

## Files Modified

| file                                                                            | change                                                                                                       |
| ------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------ |
| `service/src/main/kotlin/ed/unicoach/coaching/costs/CollegeCostChatTool.kt`     | `DESCRIPTION` rewritten as the schema legend                                                                 |
| `prompts/coach-system-prompt.md`                                                | 4 lines deleted, the rules with no other home appended to their own paragraphs                               |
| `service/src/test/kotlin/ed/unicoach/coaching/costs/CollegeCostChatToolTest.kt` | legend byte-ceiling test added; behaviour assertions removed; schema assertions re-pointed at legend wording |
| `service/src/test/kotlin/ed/unicoach/coaching/SystemPromptCatalogTest.kt`       | the migrated behaviour rules asserted against the SERVED prompt body                                         |
| `service/src/test/kotlin/ed/unicoach/coaching/CoachingServiceTest.kt`           | one literal re-pointed from line 183's wording to line 64's                                                  |
| `service/src/main/resources/service.conf`                                       | the v30 comment naming the v29 rollback; the pin itself is rewritten by `bin/prompt-seed`                    |
| `db/schema/seed-coach-system-prompt-v30.sql`, `db/schema/ORDER`                 | GENERATED at land by `bin/prompt-seed` and `ship-order` — never hand-written                                 |

## Implementation Plan

1. Rebase onto `main@493dd34f` FIRST, before deciding what is redundant. A
   deletion pass decided against a stale copy deletes the sentences that landed
   minutes earlier, and no test catches it.
2. Inventory the DESCRIPTION item by item and classify each as schema or
   behaviour; for each behaviour item, find the prompt line that already states
   it or write the one sentence that will.
3. Rewrite `DESCRIPTION` as the legend, constants interpolated.
4. Delete prompt lines 46, 82, 95, 183 by exact text; append the new sentences
   at the end of their own paragraphs.
5. Move the 22 behaviour assertions from `CollegeCostChatToolTest` to
   `SystemPromptCatalogTest`; keep the 32 schema assertions, re-pointed at the
   legend's wording.
6. Run `bin/prompt-seed -b <BASE_SHA>` so the generated seed and the pin match
   the authored file, and write the append-only v30 comment above the pin.
7. Measure the legend and set the ceiling constant from the measurement.
8. `nix develop -c bin/test`, then the hook.

## Tests

- **`the tool description stays within its legend byte ceiling`** (new,
  `CollegeCostChatToolTest`) — asserts the rendered `DESCRIPTION` is at most the
  measured ceiling, and its KDoc states that adding a key means consciously
  raising the number, on `CostReportPageTest.WORD_CEILING`'s precedent.
- **`the description is a legend, not a rulebook`** (new) — asserts the legend
  does NOT contain the behavioural phrases that moved, so a future append to the
  tool that re-states a prompt rule fails here rather than silently recreating
  the second home.
- **The 32 surviving schema assertions** in `CollegeCostChatToolTest` still pin
  every key, value code and availability fact the legend must carry.
- **The 22 migrated behaviour assertions** now read the SERVED prompt body in
  `SystemPromptCatalogTest`, which is the stronger pin: it asserts the rule
  reaches the model, not that a Kotlin constant mentions it.
- **`every paragraph opener still starts a line of the authored source file`**
  and **`the pinned coach prompt body is exactly the authored source file`** are
  the two standing guards that this slice did not reword an opener and did not
  leave a stale seed. Both run unchanged.
- **The whole suite**, `nix develop -c bin/test`, with no module argument: the
  executed counts are reported in the run report, not predicted here.

## Rollback

`COACHING_SYSTEM_PROMPT_VERSION=v29` restores the prompt half with no deploy.
Every prior coach row stays selectable; `system_prompts` is insert-only. The
legend half is a code revert of one constant.

## Review findings applied, and the two declined

Tier 0 verified every measured number in this RFC against the tree rather than
against the RFC, and that is how the committed conflict markers and the RFC 191
near-miss (D7) were found. **An RFC is a claim; the tree is the fact.** Seven
Tier-0 findings and eight Tier-1 findings followed.

Applied, beyond the number corrections:

- **The rollback comment says it is a HALF rollback.** Flipping
  `COACHING_SYSTEM_PROMPT_VERSION=v29` restores the prompt without the seventeen
  moved rules while the legend that no longer states them is still served — the
  coach would be told those rules in NEITHER home. The comment now says to
  revert the code commit too, or neither. This was the one MAJOR finding of Tier
  1 and it is a real hole that D6's prose admitted and the operator-facing
  comment did not.
- **`prompts/README.md` states the boundary where a prompt author will meet
  it.** The two-homes rule was stated only on the tool side, under
  `coaching/costs/`, which a prompt author never opens.
- **The `assurance` entry now interpolates the enum** instead of hand-typing
  three English glosses for tokens the payload does not carry (D8), and a test
  pins the legend to the enum.
- **The byte ceiling is the exact measurement**, not a rounded budget (D9).
- **The legend's blacklist test says what it is.** It matches phrasings, so new
  words walk past it; the comment now names `LEGEND_BYTE_CEILING` as the guard
  that actually bites and the list as a tripwire on sentences that have already
  been here once — one of which, RFC 191's, was re-added and nearly shipped
  twice.
- **The byte budget measures the PUBLISHED description**, not the Kotlin
  constant. A budget on a string the model is never sent is the one thing a
  budget must not be.
- **One key per legend line**, and the `assumed_by_unicoach` entry no longer
  names itself twice or restates the `data_availability` entry eight lines above
  it.
- **The no-academic-year rule moved to the borrowing paragraph**, beside the
  other rule about the same two figures; it had been appended to the
  living-arrangement paragraph.

Declined, with the reason, because a declined finding that is not recorded is a
finding that will be re-litigated:

- **The figure-status paragraph now carries a second subject.** Four of the
  seven sentences appended there are about the KIND of number a figure that HAS
  an amount is, under an opener that announces figures with no amount. The fix
  would be a new paragraph and a new `CoachParagraph` entry, which means
  reordering lines in a DELETION-ONLY slice whose whole bar is that no existing
  line moves. The subject is wider than the opener; it is recorded here, and
  splitting the paragraph is a clean, small follow-up for a slice that is
  allowed to move lines.
- **Three other tool descriptions have the same defect, unguarded.**
  `FederalAidPolicyChatTool`, `CollegeListChatTool` and `MoneyProfileChatTool`
  still restate prompt rules in their own descriptions, and neither the ceiling
  nor the blacklist is generalised to them. That is three more slices of the
  same shape, not this one — and it is the honest scope limit of "one rule, one
  home": this RFC fixes the biggest offender and names the rest.

## The before/after rule map — every rule, and where it now lives

This is the slice's acceptance deliverable: gate-1 D1 applied to instructions
rather than to figures. Every one of the 163 items the DESCRIPTION states today,
with the home it keeps. `prompt Lnn (already)` means the prompt states the rule
and the tool sentence is deleted; `prompt (new)` means the rule had no other
home and one sentence was appended; `LEGEND` means it is schema and survives in
the legend.

| #   | rule as the DESCRIPTION states it today                                                                                | kind                    | home today                         | surviving home                                |
| --- | ---------------------------------------------------------------------------------------------------------------------- | ----------------------- | ---------------------------------- | --------------------------------------------- |
| 1   | Read the real cost facts for the colleges on the student's list: sticker cost, tuition, the net price their family wo… | SCHEMA                  | tool description                   | LEGEND (opening line)                         |
| 2   | Every result names the publishers its own figures came from in "source".                                               | SCHEMA                  | tool description                   | LEGEND                                        |
| 3   | attribute figures to the source this payload names, never to one you assume                                            | BEHAVIOUR               | IN-PROMPT L32                      | prompt L32 (already)                          |
| 4   | when a field appears in data_availability there is no number for it in this result                                     | SCHEMA                  | tool description + prompt L44      | LEGEND                                        |
| 5   | say so plainly, never estimate                                                                                         | BEHAVIOUR               | IN-PROMPT L44 (also L32)           | prompt L44 (already)                          |
| 6   | That happens for one of two reasons ... either the college does not report the field, or it is a figure that does not… | SCHEMA                  | tool description                   | LEGEND                                        |
| 7   | Never say a school reported nothing when the entry is ours.                                                            | BEHAVIOUR               | IN-PROMPT L146                     | prompt L146 (already)                         |
| 8   | figure_provenance says what KIND of number this college's figures are and who published them, once for the whole coll… | SCHEMA                  | tool description                   | LEGEND                                        |
| 9   | It is the DEFAULT for every figure this college's own publishers reported.                                             | SCHEMA                  | tool description                   | LEGEND                                        |
| 10  | figure_statuses then carries an entry only for a field that has something of its OWN to say - a status sentence, a ye… | SCHEMA                  | tool description                   | LEGEND                                        |
| 11  | Two kinds of field are NOT on that default and you must never put them there.                                          | SCHEMA+BEHAVIOUR        | NOT-IN-PROMPT                      | LEGEND (exception fact) + prompt (new)        |
| 12  | A line named by assumed_by_unicoach is OURS, not a figure any publisher reported: it is on no publisher's tier and ha… | SCHEMA                  | tool description                   | LEGEND                                        |
| 13  | say it as our own assumption and never attribute it to the publisher figure_provenance names                           | BEHAVIOUR               | IN-PROMPT L70, L74 (at-home case … | prompt L74 (widen wording)                    |
| 14  | A field listed in data_availability has no number here to resolve at all.                                              | SCHEMA                  | tool description                   | LEGEND                                        |
| 15  | say there is no figure, and never read a tier or a publisher onto it                                                   | BEHAVIOUR               | part IN-PROMPT L44; 'never read a… | prompt (new)                                  |
| 16  | An entry carries a provenance key only where it DIFFERS from the default.                                              | SCHEMA                  | tool description                   | LEGEND                                        |
| 17  | a figure's assurance is its own entry's when the entry has one and figure_provenance's otherwise                       | SCHEMA                  | tool description                   | LEGEND                                        |
| 18  | its source is its own entry's when the entry has one and figure_provenance's otherwise                                 | SCHEMA                  | tool description                   | LEGEND                                        |
| 19  | assurance and assurance_statement always travel together - never one without the other.                                | SCHEMA                  | tool description                   | LEGEND                                        |
| 20  | An entry says WHY a field has no number, carrying a status and the statement to say it in.                             | SCHEMA                  | tool description                   | LEGEND                                        |
| 21  | say that sentence rather than writing your own                                                                         | BEHAVIOUR               | IN-PROMPT L147                     | prompt L147 (already)                         |
| 22  | "not_reported_by_institution" and "not_applicable" are the school's own answer - it reported nothing, or the figure d… | SCHEMA                  | tool description                   | LEGEND                                        |
| 23  | "suppressed_by_publisher" is the publisher withholding a figure the school did report, for privacy                     | SCHEMA                  | tool description                   | LEGEND                                        |
| 24  | "not_collected_by_us" is OURS - we have not collected it yet                                                           | SCHEMA                  | tool description                   | LEGEND                                        |
| 25  | so for neither of those may you say the school failed to report it                                                     | BEHAVIOUR               | IN-PROMPT L146 (partial: L146 nam… | prompt L146 (widen to the suppressed case)    |
| 26  | A "not_collected_by_us" entry that ALSO carries held_academic_year is the narrower case: this school DID publish that… | SCHEMA                  | tool description                   | LEGEND                                        |
| 27  | Say which year we hold it for, read from held_academic_year rather than from the sentence                              | BEHAVIOUR               | NOT-IN-PROMPT                      | prompt (new)                                  |
| 28  | never carry that figure into a total beside figures from another year                                                  | BEHAVIOUR               | IN-PROMPT L80                      | prompt L80 (already)                          |
| 29  | "imputed_by_publisher" is not a blank at all: the figure IS shown, and it is the publisher's own estimate rather than… | SCHEMA                  | tool description                   | LEGEND                                        |
| 30  | so quote it with that said - including when it is a zero                                                               | BEHAVIOUR               | IN-PROMPT L148, L149               | prompt L148-149 (already)                     |
| 31  | A "reported" entry that is shown carries no statement at all                                                           | SCHEMA                  | tool description                   | LEGEND                                        |
| 32  | a plainly reported figure is shown plainly and you say nothing about its status                                        | BEHAVIOUR               | IN-PROMPT L150                     | prompt L150 (already)                         |
| 33  | assurance is what KIND of number a figure is ... a federal administrative record, a survey the college was required t… | SCHEMA                  | tool description                   | LEGEND                                        |
| 34  | assurance_statement is the sentence to say that in                                                                     | SCHEMA                  | tool description                   | LEGEND                                        |
| 35  | say the tier ONCE for the figures on the default                                                                       | BEHAVIOUR               | NOT-IN-PROMPT                      | prompt (new)                                  |
| 36  | name a figure's own tier where its entry carries one, after the statement sentence where there is one                  | BEHAVIOUR               | NOT-IN-PROMPT                      | prompt (new)                                  |
| 37  | Never re-word that sentence, rank the three or turn them into a score.                                                 | BEHAVIOUR               | NOT-IN-PROMPT                      | prompt (new)                                  |
| 38  | Every college carries applies_to_this_family_basis, which says which of three states it is in                          | SCHEMA                  | tool description                   | LEGEND                                        |
| 39  | applies means the published price and the price after a financial aid offer are this family's there                    | SCHEMA                  | tool description                   | LEGEND                                        |
| 40  | withheld means they are withheld                                                                                       | SCHEMA                  | tool description                   | LEGEND                                        |
| 41  | basis_stated means we cannot say whose they are - either we have not asked where the family lives, or the data does n… | SCHEMA+BEHAVIOUR        | SCHEMA new-in-legend; BEHAVIOUR I… | LEGEND + prompt L129 (already)                |
| 42  | applies_to_this_family rides beside it as a true/false only when the answer is known.                                  | SCHEMA                  | tool description                   | LEGEND                                        |
| 43  | Each net_price is an object carrying amount_usd (whole US dollars per academic year) and a basis                       | SCHEMA                  | tool description                   | LEGEND                                        |
| 44  | your_income_band means it is specific to the student's answered household income band                                  | SCHEMA                  | tool description                   | LEGEND                                        |
| 45  | overall_average means the band is not on file and the figure is the all-family average                                 | SCHEMA                  | tool description                   | LEGEND                                        |
| 46  | say which it is                                                                                                        | BEHAVIOUR               | IN-PROMPT L21, L22                 | prompt L21-22 (already)                       |
| 47  | When a net price is band-specific it also carries income_band_label, the band's dollar range in plain words (e.g. "$1… | SCHEMA                  | tool description                   | LEGEND                                        |
| 48  | say that range when you name the band aloud, never the income_band code and never a data-source bucket name            | BEHAVIOUR               | IN-PROMPT L33, L34                 | prompt L33-34 (already)                       |
| 49  | A net_price with no amount_usd but a reason is one WE withheld, never the school's silence: it keeps its basis and it… | SCHEMA                  | tool description                   | LEGEND                                        |
| 50  | the statement beside the reason is the one to say                                                                      | BEHAVIOUR               | IN-PROMPT L147 (say the result's … | prompt L147 (already)                         |
| 51  | When a college's result carries precision_offer, it is a list of upgrade invitations for that result, each naming the… | SCHEMA                  | tool description                   | LEGEND                                        |
| 52  | Raise them in the order given                                                                                          | BEHAVIOUR               | IN-PROMPT L23                      | prompt L23 (already)                          |
| 53  | residency_state sorts first because it is the cheaper question and the bigger correction - it selects which of the sc… | SCHEMA+BEHAVIOUR        | IN-PROMPT L24                      | LEGEND (order) + prompt L24                   |
| 54  | income_band makes the net price family-specific                                                                        | SCHEMA                  | IN-PROMPT L29                      | LEGEND                                        |
| 55  | living_plan narrows three price pictures to one                                                                        | SCHEMA                  | IN-PROMPT L110                     | LEGEND                                        |
| 56  | money_profile.residency_status / income_band_status / living_plan_status is the authority on whether to raise each fi… | SCHEMA                  | tool description                   | LEGEND                                        |
| 57  | declined means the student said no - never re-raise it yourself                                                        | SCHEMA+BEHAVIOUR        | BEHAVIOUR IN-PROMPT L30            | LEGEND (code) + prompt L30                    |
| 58  | answered means the field is already on file                                                                            | SCHEMA                  | tool description                   | LEGEND                                        |
| 59  | A college result may also carry merit_aid, from that school's own Common Data Set and cited separately from the cost … | SCHEMA                  | tool description                   | LEGEND                                        |
| 60  | share is a share of ALL full-time freshmen at that school                                                              | SCHEMA                  | tool description                   | LEGEND                                        |
| 61  | never a share of the students with no financial need, which no school reports                                          | BEHAVIOUR               | IN-PROMPT L43                      | prompt L43 (already)                          |
| 62  | average is what last year's recipients averaged, not an offer to this student                                          | SCHEMA+BEHAVIOUR        | BEHAVIOUR IN-PROMPT L43            | LEGEND + prompt L43                           |
| 63  | so never subtract it from any price here                                                                               | BEHAVIOUR               | IN-PROMPT L43                      | prompt L43 (already)                          |
| 64  | Its absence means only that this school does not report it.                                                            | SCHEMA                  | tool description                   | LEGEND                                        |
| 65  | Published tuition and fees ride one key per residency tier                                                             | SCHEMA                  | tool description                   | LEGEND                                        |
| 66  | tuition_and_fees_in_state_per_year_usd, tuition_and_fees_out_of_state_per_year_usd, and - only at the schools that pu… | SCHEMA                  | tool description                   | LEGEND                                        |
| 67  | residency_tiers says which tiers this school publishes, as a basis code with the statement to say it in                | SCHEMA                  | tool description                   | LEGEND                                        |
| 68  | "three_tiers_published" names all three                                                                                | SCHEMA                  | tool description                   | LEGEND                                        |
| 69  | "two_tiers_published" is the ordinary state pair with no district tier to mention                                      | SCHEMA                  | tool description                   | LEGEND                                        |
| 70  | "publisher_does_not_separate_in_district" means our source never separated a district price from the state price       | SCHEMA                  | tool description                   | LEGEND                                        |
| 71  | so the in-state figure is shown exactly as it was published, and you must never call it an in-district price or claim… | BEHAVIOUR               | IN-PROMPT L27, L172                | prompt L27/L172 (already)                     |
| 72  | "in_district_and_one_other_tier" means a district price and one other tier, and not the full three                     | SCHEMA                  | tool description                   | LEGEND                                        |
| 73  | "single_published_price" means one tuition price is all we hold                                                        | SCHEMA                  | tool description                   | LEGEND                                        |
| 74  | never that one price applies to everybody, because that one figure can itself be a residency-specific price, and the … | BEHAVIOUR               | NOT-IN-PROMPT                      | prompt (new)                                  |
| 75  | "no_published_tuition" means we hold NO tuition price for this school at all                                           | SCHEMA                  | tool description                   | LEGEND                                        |
| 76  | there is no tuition key to read and you must never name a tuition figure for it                                        | BEHAVIOUR               | NOT-IN-PROMPT                      | prompt (new)                                  |
| 77  | A district is never asked about: the family's state answer cannot select a district price                              | BEHAVIOUR               | IN-PROMPT L26                      | prompt L26 (already)                          |
| 78  | so the in-district figure is a labelled tier you may quote and never the tuition line inside a total                   | BEHAVIOUR               | IN-PROMPT L25                      | prompt L25 (already)                          |
| 79  | fees_only_in_state / fees_only_out_of_state / fees_only_in_district are the fees PART of the matching combined figure… | SCHEMA                  | tool description                   | LEGEND                                        |
| 80  | never add one to a tuition and fees figure or to any total, and never subtract one from the other                      | BEHAVIOUR               | NOT-IN-PROMPT                      | prompt (new)                                  |
| 81  | A college result may also carry aid_policy, also from that school's own Common Data Set and cited there                | SCHEMA                  | tool description                   | LEGEND                                        |
| 82  | need_met_share and fully_met_share are about the freshmen that school gave need-based aid to                           | SCHEMA                  | tool description                   | LEGEND                                        |
| 83  | never about every freshman and never about this student - say each one the way its label says it                       | BEHAVIOUR               | IN-PROMPT L155                     | prompt L155 (already)                         |
| 84  | Neither figure means the school meets full need: no school publishes that, so answer 'do they meet full need?' with t… | BEHAVIOUR               | IN-PROMPT L156                     | prompt L156 (already)                         |
| 85  | required_forms lists the aid forms that school's Common Data Set says are required of first-year applicants            | SCHEMA                  | tool description                   | LEGEND                                        |
| 86  | a form that is not in the list is not listed in that filing, which is NOT the school saying it is not required         | SCHEMA+BEHAVIOUR        | BEHAVIOUR IN-PROMPT L158           | LEGEND (absence semantics) + prompt L158      |
| 87  | forms_note says that in words and you must not contradict it                                                           | SCHEMA+BEHAVIOUR        | BEHAVIOUR IN-PROMPT L158 (partial) | LEGEND (key) + prompt L158                    |
| 88  | forms_not_collected is a different thing again: that school's filing answers those forms and WE could not read the an… | SCHEMA+BEHAVIOUR        | SCHEMA new; BEHAVIOUR NOT-IN-PROM… | LEGEND + prompt (new)                         |
| 89  | When the section is absent, aid_policy_availability says why.                                                          | SCHEMA                  | tool description (behaviour half … | LEGEND                                        |
| 90  | A college result may also carry borrowing_at_graduation, from the same filing and cited there: what the students who … | SCHEMA                  | tool description                   | LEGEND                                        |
| 91  | Say that cohort out loud - those graduates, in that year, never this year's freshmen and never all its undergraduates  | BEHAVIOUR               | IN-PROMPT L161                     | prompt L161 (already)                         |
| 92  | and name the school as the one reporting it, because these are the school's own answers about itself and not an audit… | BEHAVIOUR               | IN-PROMPT L162                     | prompt L162 (already)                         |
| 93  | Attribute the section ONCE, in the sentence cohort_label carries, not at every figure.                                 | BEHAVIOUR               | NOT-IN-PROMPT                      | prompt (new)                                  |
| 94  | Each loan type is a separate figure: keep federal and private apart                                                    | BEHAVIOUR               | IN-PROMPT L163                     | prompt L163 (already)                         |
| 95  | never add two loan types together and never present one as a total                                                     | BEHAVIOUR               | IN-PROMPT L164                     | prompt L164 (already)                         |
| 96  | and where the school filed no private figure say so rather than leaving federal as the whole story                     | BEHAVIOUR               | IN-PROMPT L163                     | prompt L163 (already)                         |
| 97  | Every figure here is money owed after graduating, never a price: never subtract any of it from a published price, a n… | BEHAVIOUR               | IN-PROMPT L165                     | prompt L165 (already)                         |
| 98  | It is also a different figure from median_debt_at_completion_usd above, which is a federal-only median over a differe… | SCHEMA                  | tool description                   | LEGEND                                        |
| 99  | say the two separately, and never add or compare them as though they measured one thing                                | BEHAVIOUR               | NOT-IN-PROMPT                      | prompt (new)                                  |
| 100 | When the section is absent, borrowing_availability says why, as a basis code with the statement to say it in.          | SCHEMA                  | tool description (behaviour half … | LEGEND                                        |
| 101 | A college result may also carry cost_by_living_arrangement, the published price split into the parts a family can act… | SCHEMA                  | tool description                   | LEGEND                                        |
| 102 | on_campus, off_campus, with_family                                                                                     | SCHEMA                  | tool description                   | LEGEND                                        |
| 103 | Each arrangement carries the school's published allowances for that way of living, and total_per_year_usd only when e… | SCHEMA                  | tool description                   | LEGEND                                        |
| 104 | when there is no total, say the parts and say a part is missing, never add up what is there and call it the total      | BEHAVIOUR               | IN-PROMPT L75                      | prompt L75 (already)                          |
| 105 | The tuition and fees figure that applies to this student is NOT repeated inside each arrangement                       | SCHEMA                  | tool description                   | LEGEND                                        |
| 106 | cost_by_living_arrangement carries tuition_field_in_totals, which NAMES the top-level key of this same college whose … | SCHEMA                  | tool description                   | LEGEND                                        |
| 107 | Read the amount from that named key, and every total is that amount plus the allowances listed beside it.              | SCHEMA                  | tool description                   | LEGEND                                        |
| 108 | with_family carries a housing_and_food_with_family_per_year_usd line of 0, and that zero is OURS                       | SCHEMA                  | tool description                   | LEGEND                                        |
| 109 | [AT_HOME_ASSUMPTION_STATEMENT] Living at home, we count no food-and-housing cost: ... That is our assumption, not a f… | BEHAVIOUR (frozen copy) | IN-PROMPT L72, L73 (quoted verbat… | prompt L72-73 (already); DELETE from the tool |
| 110 | Say that assumption in the same breath as the at-home total.                                                           | BEHAVIOUR               | IN-PROMPT L71                      | prompt L71 (already)                          |
| 111 | No school and no publisher collects a with-family food-and-housing figure at all, so never say this school reported a… | BEHAVIOUR               | IN-PROMPT L70, L74                 | prompt L70/L74 (already)                      |
| 112 | The arrangement names the line under assumed_by_unicoach, which lists every key in that arrangement whose amount is o… | SCHEMA                  | tool description                   | LEGEND                                        |
| 113 | sticker_cost_of_attendance_per_year_usd is a separate figure - an average blended across all three arrangements, from… | SCHEMA                  | tool description                   | LEGEND                                        |
| 114 | so never compare it with an arrangement total and never present one as the other                                       | BEHAVIOUR               | IN-PROMPT L78                      | prompt L78 (already)                          |
| 115 | net_price is on that same in-state basis at a public school.                                                           | SCHEMA                  | tool description                   | LEGEND                                        |
| 116 | Never offer either of them to a family from another state as their price                                               | BEHAVIOUR               | IN-PROMPT L126                     | prompt L126 (already)                         |
| 117 | at a public school outside the family's state neither figure is in this result at all                                  | SCHEMA                  | tool description                   | LEGEND                                        |
| 118 | the arrangement totals in cost_by_living_arrangement, built from that school's out-of-state tuition and fees, are the… | BEHAVIOUR               | IN-PROMPT L127                     | prompt L127 (already)                         |
| 119 | A private school publishes one price, so the question does not arise there.                                            | SCHEMA                  | IN-PROMPT L128                     | prompt L128 (already); DELETE from tool       |
| 120 | When the state the family lives in is not on file, both figures ARE shown: say what basis they are on and never gate … | SCHEMA+BEHAVIOUR        | BEHAVIOUR IN-PROMPT L129, L130     | LEGEND (shown) + prompt L129-130              |
| 121 | Aid applies to the whole price and not to any one part of it, so never subtract net_price from tuition or from any of… | BEHAVIOUR               | IN-PROMPT L79, L95                 | prompt L79/L95 (already)                      |
| 122 | When a college carries "offers_on_campus_housing": false, IPEDS reports that school has no residence halls             | SCHEMA                  | tool description                   | LEGEND                                        |
| 123 | say so, and do not treat the absent on_campus arrangement as unreported data                                           | BEHAVIOUR               | IN-PROMPT L76, L93                 | prompt L76/L93 (already)                      |
| 124 | If that college nevertheless carries an on_campus arrangement, the school published those figures itself and the two … | SCHEMA+BEHAVIOUR        | BEHAVIOUR IN-PROMPT L77            | LEGEND (conflict can occur) + prompt L77      |
| 125 | "offers_on_campus_housing": true means IPEDS reports the school does have on-campus housing, so a missing on_campus a… | SCHEMA                  | tool description                   | LEGEND                                        |
| 126 | When the key is absent altogether IPEDS does not say either way - never read a present or missing on_campus arrangeme… | SCHEMA+BEHAVIOUR        | SCHEMA new; BEHAVIOUR NOT-IN-PROM… | LEGEND + prompt (new)                         |
| 127 | A college result may also carry chosen_living_arrangement: the one way of living the family has said they plan on at … | SCHEMA                  | tool description                   | LEGEND                                        |
| 128 | Lead with it, name it in the student's own words from its label.                                                       | BEHAVIOUR               | IN-PROMPT L111                     | prompt L111 (already)                         |
| 129 | Its source says where the plan came from                                                                               | SCHEMA                  | tool description                   | LEGEND                                        |
| 130 | per_college means the student said it about THIS school                                                                | SCHEMA                  | tool description                   | LEGEND                                        |
| 131 | profile_default means it is their usual plan being assumed here                                                        | SCHEMA                  | tool description                   | LEGEND                                        |
| 132 | so name the assumption in the same breath - never assume living at home for a school silently                          | BEHAVIOUR               | IN-PROMPT L113                     | prompt L113 (already)                         |
| 133 | and if they correct you, record it as that school's own plan with update_college_list                                  | BEHAVIOUR               | IN-PROMPT L113                     | prompt L113 (already)                         |
| 134 | Its pricing says which case this school is in, and you read that code rather than guessing from which keys are there   | SCHEMA                  | tool description                   | LEGEND                                        |
| 135 | "priced" carries the total_per_year_usd to lead with                                                                   | SCHEMA                  | tool description                   | LEGEND                                        |
| 136 | "not_priced_here" carries a reason and no price, because this school is not priced for that way of living at all       | SCHEMA                  | tool description                   | LEGEND                                        |
| 137 | say the reason plainly, never quote a different arrangement in its place, never carry a neighbour's figure across      | BEHAVIOUR               | IN-PROMPT L114                     | prompt L114 (already)                         |
| 138 | "no_total_here" means this school shows that way of living but no total is settled for it, and its no_total_reason sa… | SCHEMA                  | tool description                   | LEGEND                                        |
| 139 | "awaiting_residency_answer", "tuition_applicability_unknown" and "part_not_collected_by_us" are OUR gaps               | SCHEMA                  | tool description                   | LEGEND                                        |
| 140 | so never say this school published no price                                                                            | BEHAVIOUR               | IN-PROMPT L146 (our-gap rule, sta… | prompt L146 (widen)                           |
| 141 | the last of those means WE have not collected a part, or hold it only for another academic year, and the school may p… | SCHEMA                  | tool description                   | LEGEND                                        |
| 142 | "part_not_published" is a part this school does not publish                                                            | SCHEMA                  | tool description                   | LEGEND                                        |
| 143 | in every one of those cases quote the parts of it in cost_by_living_arrangement, say which part is missing, and never… | BEHAVIOUR               | IN-PROMPT L75                      | prompt L75 (already)                          |
| 144 | Its statement says the same case in words, every time.                                                                 | SCHEMA                  | tool description                   | LEGEND                                        |
| 145 | The other ways of living stay in cost_by_living_arrangement and stay true - answer a "what if we lived at home instea… | SCHEMA+BEHAVIOUR        | BEHAVIOUR IN-PROMPT L115           | LEGEND (they stay) + prompt L115              |
| 146 | When the key is absent the family has said nothing about where they will live: show every way of living the school pu… | SCHEMA+BEHAVIOUR        | BEHAVIOUR IN-PROMPT L116, L65-L67  | LEGEND (absence) + prompt L116                |
| 147 | published_price and blended_average each carry an academic_year and the figures it dates                               | SCHEMA                  | tool description                   | LEGEND                                        |
| 148 | and the year is that SCHOOL's, so two schools in one answer may carry two different years                              | SCHEMA                  | tool description                   | LEGEND                                        |
| 149 | quote a number with the year of the key that lists it, never with the other one, and never add figures from the two d… | BEHAVIOUR               | IN-PROMPT L80                      | prompt L80 (already)                          |
| 150 | A figure named by neither key has no academic year - median_debt_at_completion_usd and median_earnings_10y_after_entr… | SCHEMA                  | tool description                   | LEGEND                                        |
| 151 | so say those numbers without a year rather than borrowing one from another figure                                      | BEHAVIOUR               | NOT-IN-PROMPT                      | prompt (new)                                  |
| 152 | When the result carries two or more colleges it also carries comparison_basis, the assumptions a side-by-side holds c… | SCHEMA                  | tool description                   | LEGEND                                        |
| 153 | each one naming under colleges the schools it is true of, by college_id and name, because two schools in one comparis… | SCHEMA                  | tool description                   | LEGEND                                        |
| 154 | so match on college_id and never read a year onto a school it does not name                                            | BEHAVIOUR               | NOT-IN-PROMPT                      | prompt (new)                                  |
| 155 | Each one carries a basis code and the statement you may say                                                            | SCHEMA                  | tool description                   | LEGEND                                        |
| 156 | say those sentences as ordinary copy above the table, never as a footnote under it                                     | BEHAVIOUR               | IN-PROMPT L88                      | prompt L88 (already)                          |
| 157 | and never build a column from two different bases - one residency and one way of living per column, or say it as two … | BEHAVIOUR               | IN-PROMPT L94                      | prompt L94 (already)                          |
| 158 | A by_college array appears inside one of those facts only where the schools differ on it or a school has something of… | SCHEMA                  | tool description                   | LEGEND                                        |
| 159 | where it is absent the statement above it is true of every school in the call, so say it once and never narrate a dif… | SCHEMA+BEHAVIOUR        | SCHEMA (absence) in tool; BEHAVIO… | LEGEND + prompt (new)                         |
| 160 | Put the tuition and fees line above the estimated living costs and say which block is which.                           | BEHAVIOUR               | IN-PROMPT L90                      | prompt L90 (already)                          |
| 161 | Rows are schools; keep the table to three columns and short cells, or say it as a list.                                | BEHAVIOUR               | IN-PROMPT L91 (also L11)           | prompt L91 (already)                          |
| 162 | When a school does not report a part, leave that cell blank and label it as not reported: never a zero, never a neigh… | BEHAVIOUR               | IN-PROMPT L92                      | prompt L92 (already)                          |
| 163 | Read-only: this tool changes nothing.                                                                                  | SCHEMA                  | tool description                   | LEGEND                                        |

Counts: **103 items survive in the legend**, **46 are deleted from the tool
because the prompt already says them**, **16 become a new prompt sentence**. No
item is dropped without a named surviving home.
