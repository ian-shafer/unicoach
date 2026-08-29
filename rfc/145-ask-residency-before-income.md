# RFC 145: Ask where they live before asking what they earn

Status: Draft

Brief 0003 (clear money language), slice M1.2. Follows RFCs 141 (money
vocabulary, prompt v5), 142 (income-band label, prompt v6) and 143 (no bare
source code reaches a tool result).

## Summary

Residency is the biggest lever on the stable half of a college's cost, and the
cheapest question we can ask. Coach prompt v6 never asks it. This RFC seeds
prompt **v7** (residency offered first, and offered at all) and gives
`college_cost_profile`'s `precision_offer` a **residency case**, so the coach is
cued by the tool result rather than by memory.

No schema change beyond one seed row. No new tables.

## Motivation

Measured on our own `colleges` table (brief 0003, 2026-08-28):

| Question                            | What knowing it corrects                                   | Median size                                             |
| ----------------------------------- | ---------------------------------------------------------- | ------------------------------------------------------- |
| What state do you live in?          | in-state vs out-of-state tuition at a public (n=1,689)     | **$6,300/yr** (in-state $5,507 vs out-of-state $11,010) |
| What is your household income band? | net price vs the overall average we already show (n=3,223) | **$1,376/yr** for a middle-band family                  |

Roughly **4.5x** the correction, from the far less sensitive question. The
asymmetry is sharper than the arithmetic: residency selects a **published
price** we can state exactly, while the income band selects an **average within
a bracket** — a statistic about other families. Brief 0001 D12 (value before
ask) points the same way.

And the gap is worse than an ordering problem. Prompt v6 mentions the household
income band three times and the family's state **zero** times, even though
`money_profiles.residency_state` has existed since RFC 134,
`update_money_profile` already accepts it, and `TuitionApplicable` is `UNKNOWN`
without it. The machinery is all there; nothing ever asks.

The honest limit: residency only moves tuition at **public** colleges (1,689 of
3,612 reporting tuition). A list of only private schools gains nothing from it,
and there the income band is the whole story. This is a re-ordering and a
repair, not a demotion of the income band.

## Detailed Design

### `precision_offer` becomes a list of offers

Today the field is a single string constant, emitted per college exactly when
the income band is unanswered and that college reports bracket columns. It grows
a second case, so the key becomes an **array**, ordered:

```json
"precision_offer": [
  { "field": "residency_state",
    "offer": "This is a public school, so its published tuition and fees depend on where the family lives. ..." },
  { "field": "income_band",
    "offer": "This net price is the overall average. ..." }
]
```

The sentences are constants, so they are per-college only in the sense that the
result carries them; neither interpolates the college's own state. The residency
sentence promises no more than the data supports — which of this school's
published prices applies to them, and to say plainly when the school does not
report the one that does.

The key is **omitted entirely when there is nothing to offer** — an absent key,
never an empty array — so its mere presence stays meaningful to the model.

The key name stays `precision_offer` (one key, one concept: the upgrade
invitations available on this result). Each entry names the money-profile
**field** it would fill and carries the sentence the coach may say. **Residency
sorts first, always** — the ordering is the product decision, expressed where
the coach reads it rather than only in the prompt.

The derivation stays in `CollegeCostProfile`, still derived and never stored,
and gains a residency term. Each case owns its own rule (`PrecisionOffer`'s
`appliesTo`) and the derivation is `PrecisionOffer.entries.filter { … }`, so
declaration order **is** wire order and a new member cannot compile without
deciding when it applies:

- **income band** — unchanged: `income_band_status == unanswered` AND the
  college reports at least one bracket column.
- **residency** — the college's control is `Public` with
  `tuition_applicable: "unknown"`, AND `residency_status == unanswered`, AND the
  college reports at least one published tuition figure.

The `unanswered` term (not merely "unknown") is what keeps a **decline
permanent**: `TuitionApplicable.UNKNOWN` covers both unanswered and declined, so
keying the offer off it alone would re-raise a closed topic on every cost answer
— exactly the ethos assertion RFC 135 wrote for the income band. The
`tuition_applicable: "unknown"` term is redundant with that status by
construction, and kept deliberately: it binds the offer to the label the same
payload renders, so the coach's cue and its justification cannot diverge. The
published-tuition term is a named twin of `reportsBandPricing` —
`CollegeCost.reportsPublishedTuition` — so both halves of "this college has an
upgrade to promise" read the same way; a college that reports neither tuition
figure has none.

An all-private list therefore produces no residency offer anywhere, for free —
the offer is derived per college and `CollegeControl.Public` is the only case
that can carry residency at all.

The offer copy lives in `CollegeCostChatTool` beside today's constant (the
rendering layer), and the offer identity — an ordered enum of the two cases —
lives in `CollegeCostService` with the rest of the composition. `PrecisionOffer`
is an enum, not a boolean pair, so a third upgrade (M4's living arrangement is
the obvious next one) is a member and a copy string, not another branch in the
renderer.

Because this slice makes `residency_status` **decision-bearing** — it now gates
a cue the coach acts on, not just a label — the read's row-intactness guard is
generalised from auditing `income_band` alone to auditing both status/value
pairs against `db/schema/0046`'s `*_value_iff_answered_check` constraints. An
answered row with a null value is corruption either way, but the residency half
now has a failure mode nothing else catches: the result would render
`tuition_applicable: "unknown"` **and** withhold the residency offer that exists
to resolve it, leaving the coach a state no reply can recover from. It fails the
read with the same `CorruptPersistedValueException`, naming the column and the
row.

### Coach prompt v7

A new immutable seed row (`db/schema/0053`), the 0049/0050 pattern: v6 stays in
the catalog and rollback stays one env var
(`COACHING_SYSTEM_PROMPT_VERSION=v6`); `service.conf` pins v7.

v7 **replaces v6's money paragraph** rather than appending to it — v6's standing
instruction is _"when the result carries a `precision_offer`, offer to record
their household income band"_, which is now wrong for a residency offer. An
appended sentence would leave the coach two rules and let it ask for income when
only residency was offered. The replacement:

- keeps every glossary pair, the attribution rule and the decline ethos
  verbatim;
- keeps RFC 142's source-jargon sentence **byte-identical**, at the same
  interior position;
- makes the `precision_offer` instruction offer-aware and residency-first: when
  the result offers residency, ask what state the family lives in **before**
  raising income, and say what it unlocks — whether they would pay the in-state
  or the out-of-state price;
- restates for residency what v6 says for income: a decline is accepted
  permanently and never re-raised, and no cost answer is ever gated on either
  field (brief 0001 D11/D12).

The structural contract is the existing one: a byte-identical v6 prefix up to
the money paragraph, a byte-identical college-list suffix, and the replaced
paragraph between them.

### DDL at the gate (brief 0001 D10)

The only DDL in this slice is **one `INSERT` into `system_prompts`** — the v7
seed row. No `CREATE`, no `ALTER`, no new table or column. `system_prompts` is
insert-only and immutable (RFC 33, `db/schema/0007`), so the v6 row is untouched
and the rollback is real.

## Files Modified

| File                                                                            | Change                                                                                                                     |
| ------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------- |
| `db/schema/0053.seed-coach-system-prompt-v7.sql`                                | NEW — the v7 seed row (v6's body with the money paragraph rewritten)                                                       |
| `service/src/main/resources/service.conf`                                       | pin `coaching.systemPromptVersion = "v7"` (rollback knob: `v6`)                                                            |
| `service/src/main/kotlin/ed/unicoach/coaching/costs/CollegeCostService.kt`      | `PrecisionOffer` enum; `precisionOffersFor(college)` replaces `precisionOfferFor`; row-intactness guard audits both fields |
| `service/src/main/kotlin/ed/unicoach/coaching/costs/CollegeCostChatTool.kt`     | render the offer array; offer copy; updated tool description                                                               |
| `service/src/test/kotlin/ed/unicoach/coaching/costs/CollegeCostChatToolTest.kt` | offer-array cases; the RFC 143 guard still passes                                                                          |
| `service/src/test/kotlin/ed/unicoach/coaching/SystemPromptCatalogTest.kt`       | v7's structural contract and markers                                                                                       |
| `service/src/test/kotlin/ed/unicoach/coaching/costs/CollegeCostServiceTest.kt`  | offer-list derivation; the residency row-intactness case                                                                   |
| `service/src/test/kotlin/ed/unicoach/coaching/costs/CostsTestDb.kt`             | the shared money-profile seeders both costs test classes use                                                               |
| `service/src/test/kotlin/ed/unicoach/coaching/CoachingConfigTest.kt`            | the packaged-defaults pin moves to `v7`                                                                                    |
| `rfc/145-ask-residency-before-income.md`                                        | this document                                                                                                              |

No API change, no iOS change, no `CostField` change (an offer is not a cost
figure), no new table or column.

## Implementation Plan

1. `PrecisionOffer` + `precisionOffersFor` in `CollegeCostService`; the
   residency term with its `unanswered` and published-tuition conditions.
2. Render the array in `CollegeCostChatTool`, residency first; write the two
   offer sentences; update `DESCRIPTION` to describe the array, the two fields,
   and `residency_status` as the authority on whether to raise residency.
3. Seed `db/schema/0053` as v6's body with the money paragraph replaced, the
   jargon sentence preserved byte-identically; pin v7 in `service.conf`.
4. Tests: service derivation, tool payload, prompt structure.
5. `nix develop -c bin/test` (full suite, no scoping).

## Tests

- **`a public college with residency unanswered offers residency first`** — both
  offers present, residency at index 0.
- **`an all-private list makes no residency offer`** — the acceptance criterion,
  asserted on the payload.
- **`a declined residency is never re-offered`** — and the same for a declined
  income band, unchanged.
- **`an answered residency makes no residency offer`** — and
  `tuition_applicable` is `in_state`/`out_of_state`, not `unknown`.
- **`a public college reporting no tuition figure makes no residency offer`** —
  the term that stops an empty promise.
- **`a college with nothing to offer carries no precision_offer key`** — the key
  is absent, not an empty array; asserted on the raw key rather than through a
  helper that folds absence into an empty list.
- **`every offer field is a parameter update_money_profile accepts`** — the
  offer's promise is that the coach can hand the field to that tool, so the
  names are checked against its own input schema rather than retyped.
- **`an answered residency with a corrupt null stored state fails the read`** —
  the generalised intactness guard, the twin of the existing income-band case.
- **`no bare source code reaches a tool result`** — RFC 143's guard, unchanged
  and still passing over the payload that now carries the offer array.
- **`coach v7 is v6 with the money paragraph replaced`** — prefix/suffix
  byte-identity through the existing guarded extractor, plus markers: it names
  residency, says what it unlocks, and asks it before income.
- **`coach v7 preserves the v6 source-jargon sentence verbatim`** — RFC 143's
  rule survives the rewrite; the sentence is extracted from v6 at runtime, never
  retyped.
- Every cost answer still works with residency unanswered — the existing
  `unknown`-basis assertions carry this and must pass unedited.

## Deliberately not done here

- **One question or two.** Residency is a legal test, not an address ("have you
  lived there a while?"). v7 asks the single state question; the durational
  follow-up would add a field and a status to `money_profiles` and belongs with
  M4's DDL, not in a copy-only slice.
- **A residency term in `data_availability`.** An unanswered residency is a
  profile gap, not a college's silence; conflating them would misreport the
  source.
- **Living arrangement (M4).** The third upgrade offer is a member of the new
  enum away, and stays out of this slice by design.
