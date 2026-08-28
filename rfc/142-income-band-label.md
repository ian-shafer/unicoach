# RFC 142: Name the income band in dollars, never in source jargon

Status: Draft

Brief 0003 slice M1.1 (`product/0003-clear-money-language/spec.md`), added
post-gate after M1's first phone test.

## Summary

RFC 141 gave the coach a money vocabulary. Its first live conversation produced
"**Q5 net price**" — College Scorecard's internal quintile label, in front of a
parent.

The cause is ours: `college_search` puts `net_price_q1..q5` in the model's
context on every result and tells it to cite the matching band. This RFC removes
that jargon from the wire and from the tool's own instructions, makes the band's
dollar range travel with the band from **one construct** at every emitter, and
generalises the rule in coach prompt **v6** (never name a data source's internal
buckets, codes or field names).

## Motivation

**Corrected during review.** This RFC's first draft asserted that nothing in
unicoach emits "Q5" — that the `net_price_qN` names never leave `CollegesDao`
and the model must have supplied the word from its own knowledge of the
Scorecard. That was wrong, and it was wrong because the diagnosis grepped the
cost-tool path (`service/**/costs`) and not the whole model-facing surface.

`college_search` — live in the coach's tool set — serialises
`net_price_q1`…`net_price_q5` as literal JSON keys in **every** result, and its
own description hand-types "net_price_q1..q5 for incomes $0-30k / … / $110k+ …
so cost answers can cite the band matching the family's income". We told the
model to cite a band and named that band `q5`. The observed "Q5 net price" is
most probably ours, not the model's.

Three emitters therefore sent a Scorecard bucket to the model with no phrase
attached:

| Emitter                               | What it sent                                                             |
| ------------------------------------- | ------------------------------------------------------------------------ |
| `college_search` result + description | `net_price_q1..q5`, the literal jargon                                   |
| `MoneyProfileChatTool` echo           | `income_band: "over_110k"` — right after the student states their income |
| `CoachingService` session prompt      | `- household income band: answered (over_110k)`                          |

The general shape stands, and is the reason this is not fixed by a longer ban
list: **a ban list can only forbid terms we predicted.** RFC 141 banned the
wrong words we could name in advance. The durable fix is to leave no vacuum —
emit the phrase the model should say, everywhere a bucket is named, so it never
has to improvise. The prompt rule is the belt to that braces.

Because the invariant is "a band never reaches the model as a bare code", it is
enforced by **one construct** — `putIncomeBand` — rather than by every call site
remembering to write two `put`s together. Two adjacent puts, duplicated per
site, is exactly the convention that had already failed at the two sites above.

## Detailed Design

### The wire carries the phrase, from one construct

`JsonObjectBuilder.putIncomeBand(band)` lives beside `IncomeBand` and emits the
pair together — `income_band` (the stable code the tool contract documents and
`update_money_profile` accepts as input) and `income_band_label` (the dollar
range, from `IncomeBand.bracket`). It is used at every site that names a band:

- `CollegeCostChatTool.netPriceObject`, on the `NetPrice.BandSpecific` case only
  — the overall-average case has no band, and the type already makes that
  unrepresentable (RFC 135).
- `CollegeCostChatTool.moneyProfileObject`, whenever the band is present.
- `MoneyProfileChatTool`'s post-write echo.

`college_search` is the leak this RFC exists to close. Its five `net_price_qN`
keys collapse into one `net_price_by_income_band` array whose entries carry the
band, its label and the amount; absent amounts are omitted, matching the
existing `putOrNull` behaviour. Its description renders `IncomeBand.bracket`
dynamically rather than hand-typing the ranges, so display copy keeps a single
home and the jargon disappears from the instruction as well as the payload.

`CoachingService`'s session prompt block renders the band's dollar range instead
of the bare code.

### The labels are rewritten to be said aloud

`bracket` is currently a spreadsheet label. It becomes a phrase a coach can read
into a sentence:

| Band          | Was            | Becomes               |
| ------------- | -------------- | --------------------- |
| `under_30k`   | `$0-30k`       | `$0 to $30,000`       |
| `30k_to_48k`  | `$30,001-48k`  | `$30,001 to $48,000`  |
| `48k_to_75k`  | `$48,001-75k`  | `$48,001 to $75,000`  |
| `75k_to_110k` | `$75,001-110k` | `$75,001 to $110,000` |
| `over_110k`   | `$110k+`       | `$110,000 or more`    |

`MoneyProfileChatTool`'s description inherits the improvement for free — it
already renders `bracket`.

### Coach prompt v6

v6 is v5 with **one sentence appended to the money paragraph**, before the
college-list paragraph. Unlike v5 (which replaced a paragraph), this is an
insertion at a known interior boundary, so the structural contract is again a
byte-identical prefix and a byte-identical suffix:

    v6 == <v5 up to the college-list paragraph> + <new sentence> + <v5 college-list paragraph>

The appended sentence:

> Never name a data source's internal buckets, codes or field names — no
> quintiles, no Q numbers, no NPT codes, no column names. When a price is
> specific to an income band, say the band's dollar range, which the tool gives
> you alongside the band: for families earning $110,000 or more.

It is deliberately general. "Q5" is one instance of a class that also contains
NPT41, `net_price_q3`, CONTROL=1 and CIP codes — every one of which is reachable
by a model that has read the same federal documentation we did.

## Files Modified

| File                                                                                                       | Change                                                                                                                                      |
| ---------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------- |
| `db/src/main/kotlin/ed/unicoach/db/models/IncomeBand.kt`                                                   | `bracket` strings rewritten as spoken phrases; `putIncomeBand` JSON helper beside it — the one construct that emits code and label together |
| `db/src/test/kotlin/ed/unicoach/db/models/IncomeBandTest.kt`                                               | pins the five phrases — `bracket` is display copy that reaches the wire, so an abbreviation regression would otherwise be silent            |
| `service/src/main/kotlin/ed/unicoach/coaching/costs/CollegeCostChatTool.kt`                                | `putIncomeBand` at both sites; description names the label and says to speak the range, interpolating `bracket` rather than hand-typing it  |
| `service/src/main/kotlin/ed/unicoach/coaching/MoneyProfileChatTool.kt`                                     | `putIncomeBand` in the post-write echo                                                                                                      |
| `college/src/main/kotlin/ed/unicoach/college/CollegeSearchTool.kt`                                         | `net_price_q1..q5` -> `net_price_by_income_band` with labels; description renders `bracket` and names no q-codes                            |
| `service/src/main/kotlin/ed/unicoach/coaching/CoachingService.kt`                                          | session prompt renders the band's dollar range                                                                                              |
| `db/schema/0050.seed-coach-system-prompt-v6.sql`                                                           | NEW — seeds v6                                                                                                                              |
| `service/src/main/resources/service.conf`                                                                  | pin `v6`; `v5` is the rollback                                                                                                              |
| tests: `SystemPromptCatalogTest`, `CoachingConfigTest`, `CollegeCostChatToolTest`, `CollegeSearchToolTest` | the v6 contract, the pin, the label assertions, and the source-jargon guard pointed at the surface that was actually dirty                  |
| `rfc/142-income-band-label.md`                                                                             | this document                                                                                                                               |

No schema change to any table but the insert-only `system_prompts` catalog. No
API, no iOS.

## Implementation Plan

1. Rewrite the five `IncomeBand.bracket` strings; add `putIncomeBand` beside
   them; pin the phrases in `IncomeBandTest`.
2. Use `putIncomeBand` at all three cost/money-profile JSON sites.
3. Collapse `college_search`'s five `net_price_qN` keys into
   `net_price_by_income_band`; render `bracket` in its description.
4. Render the dollar range in `CoachingService`'s session prompt block.
5. Seed v6 as v5 + the appended sentence, reusing the v5 body byte-for-byte; pin
   it in `service.conf`.
6. Extend the tests, including pointing the q/NPT guard at `college_search`.
7. `nix develop -c bin/test` (full suite, no scoping).

## Tests

- **`income_band_label is emitted with every band-specific net price`** — a
  band-specific result carries both `income_band` and a non-empty
  `income_band_label` equal to that band's `bracket`.
- **`an overall-average net price carries no band label`** — the
  `NetPrice.OverallAverage` case emits neither field.
- **`the money-profile echo labels an answered band and omits the label otherwise`**
  — asserts both the unanswered and the declined case.
- **`no qN-style source code reaches the wire`** — the rendered `college_search`
  payload AND its description, plus the `college_cost_profile` payload, match no
  `\bq[1-5]\b` (case-insensitive) and contain no `NPT4`. Pointed at the surface
  that was actually dirty: the original guard covered only a payload that
  structurally could never carry a q-code, certifying the clean surface while
  the leaking one shipped.
- **`search results label every income band`** — each `net_price_by_income_band`
  entry's label equals that band's `bracket`.
- **`coach v6 is v5 plus the source-jargon sentence`** — byte-identical v5
  prefix and college-list suffix, with the appended sentence between them,
  carrying "no quintiles" and "the band's dollar range".
- Existing `every pinned prompt exists in the catalog` covers the v6 pin with no
  edit.

## Deliberately not fixed here

- **`college_search` still emits the raw IPEDS `control` integer**
  (`"control":
  2`) with no phrase beside it, while the sibling cost tool
  already emits `"public"` / `"private_nonprofit"` from `CollegeControl`, "the
  one home for the code -> label vocabulary". It is the same defect class this
  RFC names, and the new jargon guard does not catch it. Left out because it is
  a second vocabulary with its own home in a different module, and widening once
  more inside an already-widened run trades review quality for scope. It is
  recorded as an open item on brief 0003 and should be the first thing M2 or M3
  picks up.
- **`IncomeBand`'s numeric boundaries now exist only inside display prose.** A
  future "which band is $52,000 in?" caller would have to parse English or
  hand-type a second copy of the thresholds. Typed `minInclusive`/`maxInclusive`
  with `bracket` derived from them is the fix when a caller needs it.
- **`putIncomeBand` names model-facing JSON keys from inside
  `ed.unicoach.db.models`.** Placement only — the formatting still happens at
  the serialization boundary — but wire-naming authority for three tools in two
  other modules now sits one module inward.
