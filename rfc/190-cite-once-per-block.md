# RFC 190 — Cite once per block

**Status:** proposed **Slice:** `concise/04/cite-once-per-block` (brief 0009
"Say it once", gate-1 D2, gate-2 wave 2) **Supersedes:** RFC 175 D10's
per-sentence attribution rule, for the borrowing block. RFC 175's file is left
as committed (`rfc/INVARIANTS.md`).

## The one-line argument

**RFC 175 D10's REASON is unchanged; only the unit the attribution rides in
changes.** A Common Data Set figure is an unaudited claim a school made about
itself, and it must never read as an administrative record. That was true when
the rule was one attribution per SENTENCE and it is true now that it is one
attribution per BLOCK -- because the block is one filing, one school, one cycle,
and its header cannot be separated from the figures beneath it.

And note which way the attribution actually moves: on a mixed-source block this
change **adds** attribution. Today the federal median-debt sentence inside
`report-debt` names no publisher at all and leans on the page's sources section
at the foot. After this change it names its own publisher, derived from the cell
the figure came from. "Cite once" removes restatement; it does not remove
citation, and on the one block where two publishers meet it produces one more
citation than the page has today.

## Why

The debt and borrowing block is about 28% of the Family Cost Report. Every
sentence `BorrowingWire` builds re-interpolates the college name and the Common
Data Set cycle, so one school's block reads:

> Borrowing College reports these borrowing figures for the students who
> graduated from it in 2024-25 -- not this year's freshmen, and not all its
> undergraduates. Borrowing College reports that 394 of the 1000 students who
> graduated in 2024-25 borrowed federal loans -- 39.4%. Borrowing College
> reports that the students who graduated in 2024-25 and borrowed federal loans
> owed $20,747 on average by the time they graduated. ... Source: Borrowing
> College's 2024-25 Common Data Set.

`"2024-25"` appears 21 times across a three-school report and `"reports that"`
12 times. Nothing in there is a second FACT; it is one attribution said five
times.

## The rule this RFC carries, and why a header is still attribution

RFC 175 D10 said every borrowing sentence must name the school as the claimant,
because these are self-reported survey answers with no audit and an unattributed
figure reads as an administrative record. **That reason is unchanged. What
changes is the unit the attribution is carried in**: a titled block whose header
names the claimant, the cycle and the document, with the figures rendered
beneath it.

A scoping header is sufficient attribution when three things hold, and this RFC
makes each of them structural rather than remembered:

1. **The header cannot be separated from the figures.** The sentences are no
   longer reachable without it: `BorrowingWire.blockOf` returns a
   `BorrowingBlock(header, sentences)`, and a caller that wants the sentences
   holds the header in the same value. There is no call that renders one without
   the other, so "the block cannot render without its header" is a property of
   the type, not a convention a later edit can drop.
2. **No figure escapes its block.** Every sentence in the block is from the same
   filing, the same school and the same cycle -- the block IS one citation's
   scope.
3. **A figure whose publisher differs names its own** (below). A header only
   scopes what is genuinely under it.

Per-sentence attribution was never the guarantee; correct attribution was. RFC
177's rule is correctness, not cardinality, and RFC 177 D5 already ships a
once-per-answer roll-up sentence on exactly this reasoning.

## Detailed Design

### 1. The block, as a type

`BorrowingWire.listSentences(coverage): List<String>` becomes
`BorrowingWire.blockOf(coverage): BorrowingBlock`:

```kotlin
data class BorrowingBlock(
  /** Who is claiming every figure below, in which cycle, from which document. */
  val header: String,
  /** The figures, said without re-naming the claimant. */
  val sentences: List<String>,
)
```

`header` is built from `CdsCitation.citedAs` -- the one home of the publisher's
spoken name -- so the block's attribution cannot drift off the citation beside
it:

> Borrowing College's 2024-25 Common Data Set reports these figures for the
> students who graduated from it that year -- not this year's freshmen, and not
> all its undergraduates.

That sentence replaces BOTH today's `cohortLabel` and the page's trailing
`Source: <citedAs>.` line: the header states the document, so a second citation
under the same figures is the repetition this slice exists to remove.

### 2. The sentences drop the frame, and only the frame

Each builder loses `"<College> reports that ..."` and the re-stated cycle, and
keeps the loan type, the cohort, the figure, and the ours-versus-theirs voice
(RFC 166 §6):

| Builder                           | After                                                                                                                 |
| --------------------------------- | --------------------------------------------------------------------------------------------------------------------- |
| `averageDebtLabel`                | "Graduates who borrowed federal loans owed $20,747 on average by the time they graduated"                             |
| `shareLabel`                      | "394 of the 1000 students who graduated borrowed federal loans -- 39.4%"                                              |
| `graduatingClassLabel`            | "1000 students were in the graduating class"                                                                          |
| `privateNotFiledLabel`            | "This filing reports no average for private loans, so these figures are not the whole of what its graduates borrowed" |
| `notReadByUsLabel`                | "This filing answers what its graduates borrowed in a private loan, and WE could not read that answer out of it ..."  |
| `gapLabel`                        | "This filing answers what its graduates borrowed in a private loan and no figure is shown here: <status sentence>"    |
| `graduatingClassNotReadByUsLabel` | "This filing reports the size of its graduating class and WE could not read that answer out of it ..."                |

No sentence gains a claim, none loses a hedge, and no byte-frozen sentence is
touched: the six `FigureStatusCopy` and three `AssuranceTierCopy` sentences are
spliced in, not rewritten, exactly as today.

### 3. A mixed-source block names the publisher that differs

`report-debt` holds figures from TWO publishers: the federal median debt at
completion (the College Scorecard's own file) and the school's Common Data Set
borrowing block. The borrowing block's header scopes only the second. So the
federal sentence must name its own publisher, and it does so from DATA:

```kotlin
cost.statusNoteFor(CostField.MEDIAN_DEBT_AT_COMPLETION_USD)?.cell?.source
```

-- the cell the figure actually came from (RFC 184), spoken through
`MoneySourceCopy`, never a hand-typed publisher name. Where the figure carries
no note the sentence is said exactly as today, under the page's own sources
section; a page may not invent a publisher the domain did not resolve.

**RFC 175 D9 is untouched.** The two publishers stay visibly separate -- two
sub-blocks, two attributions, no shared header, nothing summed and nothing
compared. The layout change makes them MORE visibly separate, not less: today
they share a section and only one of them is attributed inside it.

### 4. The merit block, same shape

`meritBlock` moves its `Source: <citedAs>.` line from the foot of the block to
an attribution header under the heading, for the same reason: the claimant is
stated once, before the figures it covers. `MeritAidWire`'s sentences already
carry no attribution, so no copy changes there.

### 5. Chat

The payload carries the same block: `BorrowingWire.KEY`'s object keeps
`cohort_label` -- now the block header, naming the school, its cycle and its
Common Data Set -- and its `source` citation, and its figure labels lose the
per-sentence frame. The coach names the claimant once for the section, which is
what the coach prompt already asks for ("The school reported these figures about
itself, so name the school as the one saying it", v19+ prompt body, unchanged by
this RFC). The tool description's borrowing paragraph gains one clause saying
the section is attributed once, not at every figure.

**No prompt version is minted.** `prompts/coach-system-prompt.md` is not edited.

## Files Modified

- `service/src/main/kotlin/ed/unicoach/coaching/costs/BorrowingWire.kt` -- the
  block type, the header, the de-framed sentences.
- `service/src/main/kotlin/ed/unicoach/coaching/costs/CollegeCostChatTool.kt` --
  one clause in the borrowing paragraph of the tool description.
- `public-web/src/main/kotlin/ed/unicoach/web/render/CostReportPage.kt` --
  `borrowingParagraphs` renders the block header then its sentences and drops
  the trailing source line; `shownDebtSentence` names its own publisher;
  `meritBlock` hoists its citation to a header.
- `public-web/src/test/kotlin/ed/unicoach/web/CostReportPageTest.kt` -- markers,
  the mixed-source test, the header test, the ratcheted `WORD_CEILING`.
- `service/src/test/kotlin/ed/unicoach/coaching/costs/CollegeCostChatToolTest.kt`
  -- the RFC 175 D10 per-sentence test is REPLACED by the block-attribution
  test.

No DDL, no migration, no seeded row, no prompt version.

## Implementation Plan

1. `BorrowingBlock` + `blockOf` in `BorrowingWire`, header from `citedAs`.
2. De-frame the seven sentence builders.
3. Page: `borrowingParagraphs`, `shownDebtSentence`, `meritBlock`.
4. Tool description clause.
5. Tests (below), then measure and ratchet `WORD_CEILING`.

## Reachability check (brief 0009 D1)

Verified on this branch before anything was deleted: `CostReportPage.kt`
contains **no `href` and no url of any kind**, and both `Source:` lines print
exactly `<citation>.citedAs` and nothing else --
`p("report-source") { +"Source: ${merit.source.citedAs}." }` and the borrowing
twin. `CdsCitation.url` and `archiveUrl` never reach this page. The header this
RFC introduces is built from the same `citedAs`, so **nothing on the page
becomes unreachable**: no url, no archive link, no document date is lost,
because none was there.

## Open items (owned elsewhere, not by this RFC)

- `AssuranceTierCopy`'s `VOLUNTARY_SELF_REPORT` sentence ends "We link to the
  college's own file so you can see it", and no surface emits a source document
  url at all, although `source_documents` (RFC 170) stores `source_url` NOT NULL
  and a nullable `archive_url`. We promise a link we never render. The sentence
  is byte-frozen, so it cannot simply be reworded. Raised with Ian as its own
  slice; this RFC does not act on it and does not widen to cover it.

## Tests

- **The block cannot render without its header** -- `blockOf` returns header +
  sentences as one value, and the page test asserts the header renders above
  every sentence the fixture supports.
- **The CDS voice survives** -- the header names the school, the cycle and the
  Common Data Set, so a borrowing figure is still the school's own claim.
- **No figure escapes its attribution** -- every string the borrowing section
  emits is either the header or a sentence under it (swept over the wire's own
  sentences, as the existing marker test does).
- **Mixed source** -- in the debt section, the federal figure names the College
  Scorecard while the borrowing header names the school's Common Data Set; both
  publishers are on the page and neither stands for the other.
- **RFC 175 D9** -- the existing no-sum, no-comparison assertions stay green.
- **REPLACED:**
  `every borrowing sentence names the school as the one claiming
  the figure`
  becomes `every borrowing figure is covered by the block's one
  attribution`.
  It is not deleted; the new rule's test takes its place.
- **Word budget** -- `CostReportPageTest.WORD_CEILING` ratcheted from 1530 to
  the number this change actually leaves the page at, measured on the same three
  verbose fixtures. No second counter.
