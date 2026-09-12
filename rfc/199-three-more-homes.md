# RFC 199 — Three more homes

**Slice:** `concise/09/three-more-homes` (product brief 0009, "Say it once").
**Status:** proposed. **Supersedes nothing.** It moves rules between two homes
and deletes duplicates; no committed decision changes.

RFC 192 gave `college_cost_profile` one home per rule: its `DESCRIPTION` became
a pure schema legend, 46 behaviour rules already stated in the coach prompt were
deleted, and 17 with no home moved to the prompt. RFC 192 D10 named three tools
that still carry the defect. This RFC does those three, in one slice, with one
prompt version.

## The number first, so nobody mis-reads the size of this

**The three tool descriptions go from 3,185 bytes to 2,452; the coach prompt
grows by 196; the net context window shrinks by 537 bytes.** That is a rounding
error beside RFC 192's 9,703, and it is the honest figure. **Bytes are not what
this slice is for.** It is for this: **fourteen behaviour rules stop having two
homes that can drift, and that drift is completely unguarded today.** The cost
tool was the whole problem; these three are the tail of it.

## The problem, measured

The three constants were measured on this run's base, `main@7f469970`, by
rendering the PUBLISHED `description` each tool advertises — not the Kotlin
source text, and not the spec's figures:

| artifact                               | measured (rendered) | the spec said |
| -------------------------------------- | ------------------- | ------------- |
| `FederalAidPolicyChatTool.DESCRIPTION` | **1,389 bytes**     | 1,682         |
| `CollegeListChatTool.DESCRIPTION`      | **867 bytes**       | 1,881         |
| `MoneyProfileChatTool.DESCRIPTION`     | **929 bytes**       | 1,115         |
| total                                  | **3,185 bytes**     | 4,678         |

The spec's 4,678 is Kotlin SOURCE bytes — quotes, indentation and `+` included —
and even on that reading its college-list figure (993 source bytes) does not
match. Every number in this RFC is the rendered string the model is actually
sent. This is the twelfth number in brief 0009 authored ahead of its evidence;
it is reported to `/chart` as SD12, and the committed spec is not edited.

So the whole surface is a **sixth** of the cost tool's 18,580, not a quarter.
The three descriptions and the coach system prompt are sent to the model in one
context window, and fourteen behaviour rules are stated in both — in two
wordings. Nothing catches it when one copy is edited and the other is not: the
only guard any of the fourteen has today is a `description.contains(...)`
assertion in the tool's own test file, which is the file a rewrite of that
description edits.

## Decisions

### D1 — Each description becomes a schema legend, on RFC 192's convention

A description says what the tool does, what its keys mean and what values they
take. A behaviour rule — what to SAY to a family — lives in the coach system
prompt. Deleted where the prompt already says it; moved to the prompt where it
does not.

`federal_aid_policy` returns a payload, so it gets the cost tool's shape: an
opening sentence, then one line per wire key, in payload order.
`update_college_list` and `update_money_profile` are WRITE tools whose keys are
input parameters, and every parameter already carries its own `description`
string inside the JSON schema — legitimate legend content, left untouched. Their
`DESCRIPTION` therefore keeps only what no parameter can say: what the tool
does, which id it is keyed by, the cross-tool fact (a per-school living plan vs
the usual plan), the whole-call input error, and what the result echoes.

### D2 — The before/after rule map

Nineteen items. Every one of them still exists somewhere after this RFC.

**`federal_aid_policy` (`FederalAidPolicyChatTool.kt:256`)**

| #  | item, as the description states it today                                                          | kind      | home after                 |
| -- | ------------------------------------------------------------------------------------------------- | --------- | -------------------------- |
| 1  | what the tool returns (Pell max/min, SAI floor, max-Pell income tests, Direct Loan limits)        | SCHEMA    | legend, kept               |
| 2  | "federal policy, identical at every college, so the tool takes no input"                          | SCHEMA    | legend, kept               |
| 3  | "Use it whenever Pell Grants, federal loans, borrowing limits or the FAFSA's aid outcome come up" | BEHAVIOUR | **prompt, MOVED** (see D3) |
| 4  | every topic carries `award_year` and per-document `sources`                                       | SCHEMA    | legend, kept               |
| 5  | "always say which award year a figure is for and attribute it to the cited source"                | BEHAVIOUR | prompt line 145, DELETED   |
| 6a | `award_year_status` is `prior_year`; the topic then carries `award_year_statement`                | SCHEMA    | legend, kept               |
| 6b | "say its `award_year_statement` plainly rather than presenting the figures as this year's"        | BEHAVIOUR | prompt line 148, DELETED   |
| 7  | "Frame Pell as eligibility and a range, never a promised amount"                                  | BEHAVIOUR | prompt line 146, DELETED   |
| 8  | "state loan limits as caps ... never take a loan or a loan limit off any price"                   | BEHAVIOUR | prompt line 147, DELETED   |
| 9  | `money_profile` echoes the dependency answer                                                      | SCHEMA    | legend, kept               |
| 10 | invite the dependency answer once, answer fully without it, never re-raise a decline              | BEHAVIOUR | prompt line 149, DELETED   |

**`update_college_list` (`CollegeListChatTool.kt:545`)**

| #   | item                                                                                 | kind      | home after                                                                |
| --- | ------------------------------------------------------------------------------------ | --------- | ------------------------------------------------------------------------- |
| 11  | what the list is (considering / applying / admitted / rejected)                      | SCHEMA    | legend, kept                                                              |
| 12  | "when the student asks for it or agrees to it"                                       | BEHAVIOUR | prompt line 40, DELETED                                                   |
| 13  | offer to add on real interest, saying what tracking unlocks                          | BEHAVIOUR | prompt line 39, DELETED                                                   |
| 14  | "never add, change, or remove an entry without the student's say-so", never push     | BEHAVIOUR | prompt lines 40, DELETED                                                  |
| 15  | status changes and removals honoured immediately and without comment                 | BEHAVIOUR | prompt lines 40-42, DELETED                                               |
| 16  | "Use the college id from a college search or cost result"                            | SCHEMA    | legend, kept (keyed by `college_id` as the lookups give it)               |
| 17a | a school may carry its OWN living plan; `living_plan_clear` returns to the usual one | SCHEMA    | legend, kept                                                              |
| 17b | "set it only when the student says so"                                               | BEHAVIOUR | prompt lines 125-126 AND the `living_plan` parameter description, DELETED |
| 18  | "The result echoes the full list after the write"                                    | SCHEMA    | legend, kept                                                              |

**`update_money_profile` (`MoneyProfileChatTool.kt:295`)**

| #  | item                                                                          | kind      | home after                            |
| -- | ----------------------------------------------------------------------------- | --------- | ------------------------------------- |
| 19 | what is recorded, and why (four fields, shared or declined)                   | SCHEMA    | legend, kept                          |
| 20 | ask only when cost comes up, never open with it, always name what it unlocks  | BEHAVIOUR | prompt lines 24, 28, 29, 123, DELETED |
| 21 | this plan is the USUAL plan; a per-school correction goes to the college list | SCHEMA    | legend, kept                          |
| 22 | "If the student declines, record the decline and accept it without pushing"   | BEHAVIOUR | prompt lines 30, 129, DELETED         |
| 23 | "Never re-ask a declined field unless the student reopens the topic"          | BEHAVIOUR | prompt line 30, DELETED               |
| 24 | setting a value and declining the same field in one call is an error          | SCHEMA    | legend, kept                          |
| 25 | "The result echoes the full profile after the write"                          | SCHEMA    | legend, kept                          |

Fourteen deletions, one move, ten schema items kept.

### D3 — One sentence moves to the prompt, and it is an ADD

Item 3 is the only rule with no other home. Prompt line 144 already routes "the
Pell Grant or federal student loans" to this tool; it does not route the FAFSA's
own aid outcome, and "borrowing limits" is only implicitly a loan question. One
sentence is APPENDED to the end of the federal-aid paragraph (after line 149):

> `Reach for that same tool when a family asks how much they are allowed to borrow, or about what the FAFSA works out for them — the Student Aid Index — rather than answering either from memory.`

No existing prompt line is reworded, deleted or moved. No paragraph opener is
touched. The nine byte-frozen sentences are untouched. This is an ADD/ADD with
the sibling runs editing the same file; a rebase keeps both sides' lines.

### D4 — Ceilings are the measurement, exactly, with no headroom

Each tool test gains a `LEGEND_BYTE_CEILING` asserted against the PUBLISHED
`description` — the string the model is sent, not the constant — set at the
measured size of the legend that ships, with no headroom (RFC 192 D9; brief 0009
SD9). Adding a key means consciously raising the number.

### D5 — The pins move OUT of the artifact under change

Today the only guard on each of these fourteen rules is a
`description.contains(...)` assertion in the tool's own test — the file this RFC
rewrites. An artifact cannot be its own witness. `SystemPromptCatalogTest` gains
one test that asserts every deleted rule, and the moved one, against the SERVED
prompt body, per paragraph, exactly as RFC 192's equivalent test does. The tool
tests keep only legend assertions, the byte ceiling, and a "not-a-rulebook"
tripwire naming the phrases that must never come back.

### D6 — One new prompt version; rollback is a HALF

`bin/prompt-seed` cuts the version at land because the authored body differs.
The catalog tip at design time is **v31**, so this run expects **v32** — the
label is computed at land and the report states the one actually cut. Rollback
is `COACHING_SYSTEM_PROMPT_VERSION=<the previous tip>`, and it is a **HALF
rollback**: the tool legends are ordinary Kotlin and revert only with a code
revert, so flipping the version back leaves the moved sentence in NEITHER home.
That is the honest limit of the knob.

**No DDL.** No table, no column, no hand-written migration, and no seeded row
beyond the generated coach-prompt seed this version mint requires.

### D7 — The measured result

Measured after the rewrite, the same way as before it:

| artifact                               | before | after  | change   |
| -------------------------------------- | ------ | ------ | -------- |
| `FederalAidPolicyChatTool.DESCRIPTION` | 1,389  | 1,359  | -30      |
| `CollegeListChatTool.DESCRIPTION`      | 867    | 472    | -395     |
| `MoneyProfileChatTool.DESCRIPTION`     | 929    | 621    | -308     |
| the three together                     | 3,185  | 2,452  | **-733** |
| `prompts/coach-system-prompt.md`       | 33,317 | 33,513 | +196     |
| **net context window**                 |        |        | **-537** |

The federal legend barely moves, and that is the interesting row. A full payload
legend is more SCHEMA than the old prose carried, so that description was never
mostly duplication: it was a short gloss plus five behavioural rules. Replacing
the rules with real key entries buys 30 bytes. The other two fall by 45% and
33%, because almost everything they said was behaviour the prompt already
carried.

The three ceilings are set at 1,359 / 472 / 621 — the measurements themselves,
no headroom (D4).

## Files Modified

- `service/src/main/kotlin/ed/unicoach/coaching/aid/FederalAidPolicyChatTool.kt`
  — `DESCRIPTION` becomes a payload legend.
- `service/src/main/kotlin/ed/unicoach/coaching/collegelist/CollegeListChatTool.kt`
  — `DESCRIPTION` becomes a schema legend.
- `service/src/main/kotlin/ed/unicoach/coaching/MoneyProfileChatTool.kt` —
  `DESCRIPTION` becomes a schema legend.
- `prompts/coach-system-prompt.md` — ONE appended sentence (D3).
- `service/src/test/kotlin/ed/unicoach/coaching/aid/FederalAidPolicyChatToolTest.kt`,
  `.../collegelist/CollegeListChatToolTest.kt`,
  `.../MoneyProfileChatToolTest.kt` — behaviour assertions out, legend +
  ceiling + tripwire in.
- `service/src/test/kotlin/ed/unicoach/coaching/SystemPromptCatalogTest.kt` —
  the new served-prompt test.
- Generated at land: `db/schema/seed-coach-system-prompt-v<NN>.sql`,
  `db/schema/ORDER`, the `systemPromptVersion` pin in
  `service/src/main/resources/service.conf`.

## Implementation Plan

1. Re-measure the three rendered descriptions on the rebased tree.
2. Rewrite the three `DESCRIPTION` constants as legends (D1).
3. Append the one prompt sentence (D3).
4. Rewrite the three tool tests: legend assertions, `LEGEND_BYTE_CEILING` set to
   the measured size, the not-a-rulebook tripwire.
5. Add the `SystemPromptCatalogTest` served-prompt test covering all fifteen
   moved-or-deleted rules (D5).
6. Re-measure; rewrite D7's table from the real numbers.
7. `nix develop -c bin/format`, then the full `nix develop -c bin/test`.

## Tests

- `FederalAidPolicyChatToolTest`, `CollegeListChatToolTest`,
  `MoneyProfileChatToolTest`: the published description carries each legend
  fact; it stays within `LEGEND_BYTE_CEILING`; and none of the named behaviour
  phrases reappears.
- `SystemPromptCatalogTest`: every rule deleted from the three descriptions, and
  the one moved into the prompt, is stated in the SERVED prompt body, asserted
  per paragraph.
- The existing paragraph-opener, byte-frozen-sentence and catalog-immutability
  tests are unchanged and must stay green — they are what proves the prompt edit
  was an addition.
