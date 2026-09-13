# RFC 202: Never open with money

## Executive Summary

The general rule **"never open a conversation with money"** is no longer stated
anywhere. It never lived in the coach prompt: it lived in the tool description,
and it left with it. RFC 199 deleted the four-clause ethos block from
`MoneyProfileChatTool.DESCRIPTION`, together with the code comment reading _"The
ethos contract rides the tool description verbatim (RFC 134): value before ask,
never force, declined stays declined."_ Its rule map recorded the whole block as
still stated by the prompt.

Read rather than grepped, **three of those four clauses genuinely hold**. Accept
a decline and never re-ask are both at prompt line 31 (_"never raise that field
again yourself — but if they later bring money back up or change their mind,
record it warmly"_). Say-what-it-unlocks is stated per field rather than once:
line 25 for residency (_"say what it unlocks"_), line 30 for the income band
(_"so the numbers become specific to their family"_), line 124 for the living
plan (_"say what it buys"_) — three sentences, not one general one, which is
worth saying exactly because this RFC's subject is the fourth clause having no
such per-field cover. **That fourth clause was missing: "never open with it."**
Nothing became unreachable, and no other claim about RFC 199 is made here.

This RFC adds **one sentence** to `prompts/coach-system-prompt.md` stating that
rule for the first time in this file, in the prompt's own idiom, covering all
three `precision_offer` fields — `residency_state`, `income_band` and
`living_plan`.

It also does the thing RFC 199 did not: **it cites RFC 134 and resolves it.**

## RFC 134, resolved

RFC 134 §Detailed Design says: _"The tool DESCRIPTION carries the ethos contract
verbatim: ask only when cost comes up naturally, accept decline without pushing,
never re-ask a declined field unless the student reopens the topic."_ RFC 199
removed that contract from the description and never cited RFC 134 at all. Per
`rfc/INVARIANTS.md` the earlier RFCs are left as committed; this RFC carries the
change, and two things must be unambiguous to anyone who reads RFC 134
afterwards:

1. **The ethos contract's home is now `prompts/coach-system-prompt.md`, not
   `MoneyProfileChatTool.DESCRIPTION`.** RFC 134's sentence about the
   description is superseded from here. A reader of RFC 134 today would look for
   the contract in the description and not find it; that process gap is real
   independently of any wording question.
2. **RFC 134's SCHEMA half is untouched and still binding.** The tri-state
   `unanswered | answered | declined` on `money_profiles` still makes "asked and
   declined" a fact the system holds rather than a UX aspiration, `declined` is
   still first-class, `unanswered` is still resumable, and no answer of the
   coach's is gated on either field. Only the BEHAVIOURAL half moved.

**Why the move is acceptable.** It is RFC 192's reason, applied by RFC 199: one
rule, one home. Two homes for one rule drift, and the description's copy was
guarded only by a `description.contains(...)` assertion inside the tool's own
test file — the artifact under change acting as its own witness. The prompt is
the stronger home, and this RFC pins the rule there against the SERVED body,
outside anything this slice edits.

## The gap, stated at its real size

It is narrow. Overstating it is how this slice came to exist, and the brief
records that correction against itself (spec 0009, SD13).

Every money question in the prompt is gated on _"Only ask when the result offers
it"_ (line 29 as landed), and a `precision_offer` exists only inside a cost
result, so in practice cost has already come up before any of these fields can
be raised. The rule was therefore still EMERGENT from the structure. That is
weaker than a stated rule in three ways:

1. It rests on a mechanism nobody wrote down as a guarantee.
2. It is explicit for ONE of the three fields only — line 124, the living plan,
   says _"and cost is already what you are talking about"_.
3. Nothing stops the coach calling a cost tool unprompted and then reading the
   offer it manufactured as permission to ask.

## Detailed Design

One sentence is added to `prompts/coach-system-prompt.md`, immediately after the
general `precision_offer` sentence and before the per-field rules it governs:

> Whichever field a result offers, you still never open a conversation with
> money: raise residency_state, income_band or living_plan only once cost is
> already what you are talking about, and never as your way into the topic.

Five things about it, each deliberate:

- **It is an ADDITION.** Nothing is deleted, nothing is reworded, no paragraph
  opener is touched, and none of the nine byte-frozen sentences
  (`FigureStatusCopy` ×6, `AssuranceTierCopy` ×3) is touched. The known-wrong
  score-ranges line owned by brief 0010's `test/04` (line 62 as landed) is left
  alone.
- **It uses the prompt's existing idiom.** Line 153, for the Family Cost Report
  offer, already says _"in every other conversation you still never open with
  the offer"_. One rule gets one phrasing, not two.
- **It names all three fields.** `living_plan` has its own paragraph further
  down; a general rule that listed two of three would narrow itself.
- **It states the condition, not only the prohibition.** _"only once cost is
  already what you are talking about"_ is the living-plan sentence's own clause,
  raised from one field to all three. That narrow clause STAYS at line 124: this
  slice deletes nothing, the living-plan sentence also carries its ordering and
  what the answer buys, and a sentence stating one rule more tightly where it
  applies is not a second home for it.
- **Its position governs the enumeration that follows it.** Placed after the
  per-field rules instead, it would trail the thing it governs, and it would sit
  beside a sentence scoped to _"either field"_ — two scopes for one reader to
  reconcile.

No code changes. No tool description changes. No DDL, no table, no column, no
seeded row beyond the generated prompt seed, no hand-written migration.

### Prompt version and rollback

`bin/prompt-seed` cuts the next coach version at land from the authored file, so
the label is computed from the tree that lands and is never written here as a
literal. The catalog tip is **v32** as this RFC is written; this run expects
**v33**.

Rollback: `COACHING_SYSTEM_PROMPT_VERSION=<predecessor>` in `service.conf`. It
is a **WHOLE** rollback — unlike RFC 192's HALF — because this run changes no
Kotlin: flipping the pin back removes the sentence entirely and strands nothing
in another home.

**There is deliberately no rollback-relation test**, and the difference from RFC
198 is not an oversight. RFC 198 introduced a whole paragraph whose presence
defined a version boundary worth asserting. This is one sentence with a whole
revert and no code, and the failure mode worth guarding is not "the rollback
breaks" — it is "the sentence quietly disappears again", which is precisely what
happened to its predecessor in the tool description. The served-body assertion
below is the guard against that.

## Files Modified

| File                                                                                                       | Change                                                      |
| ---------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------- |
| `prompts/coach-system-prompt.md`                                                                           | +1 line, after the `precision_offer` sentence. Nothing else |
| `service/src/test/kotlin/ed/unicoach/coaching/SystemPromptCatalogTest.kt`                                  | +1 test, over the SERVED money paragraph                    |
| `db/schema/seed-coach-system-prompt-v33.sql`, `db/schema/ORDER`, `service/src/main/resources/service.conf` | GENERATED by `bin/prompt-seed`, re-run at land              |
| `rfc/202-never-open-with-money.md`                                                                         | This RFC                                                    |

## Implementation Plan

1. Insert the sentence into `prompts/coach-system-prompt.md`, after the general
   `precision_offer` sentence.
2. Add the test below to `SystemPromptCatalogTest`, beside the other
   money-paragraph tests.
3. `nix develop -c bin/prompt-seed -b <BASE_SHA>` so the served row carries the
   authored body, then `nix develop -c bin/format` and
   `nix develop -c bin/test`.
4. At land, inside the lock: `ship-rebase`, `bin/prompt-seed -b <BASE_SHA>`
   again (it recomputes the label against the tree that is landing),
   `ship-order`, `ship-squash`, `bin/format`, the full hook, `ship-land`.

## Tests

One test,
`the served money paragraph forbids opening a conversation with money`, in
`SystemPromptCatalogTest`. It reads `moneyParagraph()` — the `COST` paragraph of
the body the runtime is actually SERVED, i.e. the row `service.conf` pins — and
asserts three things:

- the paragraph states _"never open a conversation with money"_;
- it names **every** field `PrecisionOffer` can carry, read off the enum rather
  than typed as three literals, so a fourth offer added later fails this test
  until the rule names it. The rule's scope cannot narrow in silence;
- it states the condition _"only once cost is already what you are talking
  about"_, so the rule is not reduced to a bare prohibition.

`assertParagraphKeepsTheStandingCopyRules` is deliberately NOT called here: the
money paragraph states the retired terms contrastively (_"housing and food,
never room and board"_), which is why no money-paragraph test in this file calls
it.

**P1 — the artifact under change cannot be its own witness.** The assertion is
over the served row, never over `prompts/coach-system-prompt.md` and never over
a constant in a file this slice edits. That is the lesson of the clause this RFC
restores: its only guard was an assertion inside the tool test that a rewrite of
that tool was always going to edit.
