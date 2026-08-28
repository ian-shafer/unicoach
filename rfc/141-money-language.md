# RFC 141: The money language standard

Status: Draft

Brief 0003 slice M1 (`product/0003-clear-money-language/spec.md`), gate 1 D1-D9
and gate 2 D10-D19 approved by Ian 2026-08-28.

## Summary

Every cost figure the coach gives is already honest — RFC 133 landed income-band
net prices, RFC 134 the money profile, RFC 135 the `college_cost_profile` tool
with its explicit `basis` label. The words around those figures are improvised:
one paragraph of coach prompt v4 is the entire money style guide, and it says
nothing about which nouns to use.

This RFC lands the vocabulary. Coach prompt **v5** replaces v4's cost paragraph
with a money-language paragraph carrying a fixed glossary, an explicit ban list,
and three rules; everything else in v4 stays byte-identical. No schema beyond
the seed row, no new tool, no behaviour change to any number.

## Motivation

The measured harm in this domain is **term variance**, not term difficulty:
uAspire/New America found 136 distinct names for a single federal unsubsidised
loan across 455 colleges, 24 of which never used the word "loan"; GAO-23-104708
found 91% of colleges omit or understate net price, half of them partly by
counting loans as aid. A family reading our chat and a school's financial aid
offer is already translating between two vocabularies. We can at least be the
consistent one.

Two consequences shape the copy:

1. **The axis is price vs estimate, not billed vs unbilled** (brief 0003 D2).
   Tuition and fees is a price the school sets. Housing and food, books, travel
   and everyday spending are allowances the school _estimates_ and the student's
   choices move. The College Cost Transparency Initiative's "costs payable to
   the school" bucket deliberately includes on-campus housing and meals, so
   adopting it would file room and board as stable and defeat the brief.
2. **"Room and board" is retired vocabulary.** The FAFSA Simplification Act
   rewrote the statute's phrase as "living expenses, including food and housing
   costs", and NCES renamed the IPEDS field to "food and housing" in the 2023
   collection. We say **housing and food**.

Landing the words first, ahead of the component-cost split (slice M2), means the
feature already live in production improves on the next deploy, with no data
work in the way.

## Detailed Design

### The prompt version

`system_prompts` is insert-only and immutable (RFC 33, `db/schema/0007`), so a
copy change is a new seed row, never an edit. `db/schema/0049` seeds
`('coach', 'v5', ...)`; `service.conf` pins `systemPromptVersion = "v5"`, and
`COACHING_SYSTEM_PROMPT_VERSION=v4` remains a clean rollback because the v4 row
stays in the catalog.

**v5 differs from v4 by replacement, not append.** v2→v3 and v3→v4 each appended
one paragraph, and their tests assert a byte-identical prefix. v5 cannot: the
paragraph being rewritten sits in the middle, followed by v4's college-list
paragraph. The structural contract therefore becomes a prefix assertion _and_ a
suffix assertion, with the new paragraph in between:

    v5 == <v4 up to the cost paragraph> + <money paragraph> + <v4 college-list paragraph>

Both boundaries are located by the first words of the paragraphs they open
(`" When the student has schools on their college list"` and
`" The student's college list is theirs"`), so the test never hard-codes copy
that the seed migration owns.

### What the paragraph says

Carried over from v4 unchanged in substance, because RFC 135's ethos rules are
correct and this slice is not reopening them: use the tool rather than
remembered figures; lead with the band-specific net price; name an overall
average as such; make the `precision_offer` invitation and accept a decline
permanently; attribute every figure to the College Scorecard; say plainly when a
school does not report something rather than estimating.

Added — the glossary, stated **contrastively** as instructions to use these
words and not others:

| Say                   | Never say           |
| --------------------- | ------------------- |
| tuition and fees      | tuition, on its own |
| housing and food      | room and board      |
| the published price   | the sticker price   |
| a financial aid offer | an award            |

plus two positive statements with no banned counterpart, because the coach has
no habitual wrong word to displace: **grants and scholarships are money they
never pay back**, and a **loan** is always called a loan and always described as
money paid back with interest.

Considered and deliberately deferred: "total cost for a year" (over "COA"),
"Student Aid Index" (over "EFC"), and bans on "gift aid", "self-help", "unmet
need", "out-of-pocket", "miscellaneous" and "incidentals". Every one of those is
jargon the coach has no reason to emit unprompted, and each costs prompt length
in a paragraph that has already doubled. The total-cost label in particular
belongs with slice M2, which introduces the component sum it names and reseeds
this paragraph as v6.

Added — three rules:

1. **Loans and work-study are never subtracted from a price.** They do not make
   a school cheaper; they change who pays and when.
2. **The school sets tuition and fees and estimates everything else** — say
   which is which, so the student can see the part their own choices move.
3. **One vocabulary for students and parents.** The same words, whoever is
   reading; only the pronoun and the time horizon change.

### Contrastive phrasing, and what that costs the test

The glossary is stated **contrastively** — "say housing and food, never room and
board" — rather than positively. Naming the term to avoid, next to its
replacement, is how the instruction actually binds; a positive-only list leaves
the model free to reach for the familiar phrase it has seen a million times in
training data.

The price is that the banned strings necessarily appear in the prompt body, so
"the body contains no banned term" is not a test we can write. The testable
contract is the **presence of each contrastive pair**, which is what the seed
must carry and what a careless future edit would drop. Behaviour — what the
coach actually says in a conversation — is not asserted here, and no test in
this repo can assert it; that is an honest limit of a prompt change, and the
reason the pairs are stated so plainly in the copy.

## Files Modified

| File                                                                      | Change                                                                                                                                                                             |
| ------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `db/schema/0049.seed-coach-system-prompt-v5.sql`                          | NEW — seeds the v5 row, following 0044/0047/0048 (concatenated string literal, `\|\|` as layout only, verbatim body, never edited afterwards)                                      |
| `service/src/main/resources/service.conf`                                 | pin `systemPromptVersion = "v5"`; comment records what v5 is and that `v4` is the rollback                                                                                         |
| `service/src/test/kotlin/ed/unicoach/coaching/SystemPromptCatalogTest.kt` | TWO new tests: the v5 structural contract (byte-identical prefix + suffix + paragraph markers) and the contrastive glossary pairs, over a shared private `moneyParagraph()` helper |
| `service/src/test/kotlin/ed/unicoach/coaching/CoachingConfigTest.kt`      | one string: the packaged-default pin assertion moves `v4` -> `v5`, forced by the `service.conf` change                                                                             |
| `rfc/141-money-language.md`                                               | this document                                                                                                                                                                      |

No Kotlin production code changes. No migration to any table other than the
insert-only `system_prompts` catalog. No API, no iOS.

## Implementation Plan

1. Extract v4's cost paragraph and college-list paragraph boundaries from
   `db/schema/0048` so the v5 body reuses the untouched text byte-for-byte.
2. Write `0049.seed-coach-system-prompt-v5.sql`: header comment explaining that
   v5 REPLACES the cost paragraph (unlike 0047/0048 which appended), then the
   `INSERT` with the concatenated body.
3. Pin `v5` in `service.conf` with the rollback comment.
4. Add the structural + ban-list test to `SystemPromptCatalogTest`.
5. `nix develop -c bin/test` — the catalog tests are DB-backed and the harness
   re-migrates, so a mis-seeded body fails in the module that owns the pin.

## Tests

- **`coach v5 is v4 with the cost paragraph replaced`** — asserts the v4 prefix
  before the cost paragraph is byte-identical, the v4 college-list paragraph is
  a byte-identical suffix, and the replaced middle carries the required markers
  (the tool name, `precision_offer`, the Scorecard attribution, "tuition and
  fees", "housing and food", the loan rule).
- **`coach v5 states each contrastive glossary pair`** — a separate registered
  test, not assertions folded into the structural one: `assertTrue`
  short-circuits, so a single test would let a prefix regression mask every
  glossary assertion behind it and report a drifted pair under the wrong name.
  Both tests share a private `moneyParagraph()` helper. Absence of the banned
  tokens is deliberately NOT asserted: the prompt must name them to forbid them.
- **Existing `every pinned prompt exists in the catalog`** covers the `v5` pin
  with no edit — that is the RFC 129 design working as intended.
- No behavioural test changes: no number, tool, or route moves in this slice.
