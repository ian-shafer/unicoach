# RFC 216 — The coach answers the FAFSA process questions, with its sources

Product slice: `fafsa/06/fafsa-process-answers` (brief 0012 — FAFSA support).
Lane A. No new table, no migration (brief 0012 D3).

## Context

Brief 0012 D1 splits FAFSA support into a public page and coach answers. This
slice is the coach half that is not about dates: who files, the contributor and
consent model, divorced or separated parents, a contributor with no Social
Security number, dependency, and what happens after submission. D3 decided these
are prose at the tool edge plus prompt routing — `policy_parameters` keeps
carrying the numbers and nothing here adds a table.

Two things force the shape.

**Every rule the coach states must carry the document it came from.** The rules
were harvested and line-checked into
`.scratch/ship/rfc-216/findings/rule-inventory.md` from
`product/0012-fafsa-support/research/fafsa-domain.md` (§1, §3, §4) and
`research/support-landscape.md` (§5, §6): 34 rules with a publisher, a document,
an https URL and a fetch date; 9 further claims with no citation, which this RFC
does not ship at all.

**The divorced-parent rule is the more-than-half-support rule.** It is NOT "the
custodial parent". The legacy web is saturated with the old heuristic and
families repeat it, so the corpus states the correct rule AND names the wrong
one as wrong.

## Detailed Design

### 1. A sibling tool, not a bigger `federal_aid_policy`

`FederalAidPolicyChatTool` takes **no arguments** (`noArgumentToolDefinition`,
`additionalProperties = false`) and returns its whole payload on every call.
Hanging 30 process rules off it would put ~7 KB of FAFSA prose on every Pell and
loan answer, and mix a store-backed numbers tool with a curated prose corpus.

So this RFC adds a sibling in the same package:

- `FafsaProcessCopy.kt` — the one home for the rules and their citations.
- `FafsaProcessChatTool.kt` — tool name `fafsa_process`, no arguments, closed
  schema, returns the whole rulebook grouped by topic.

No argument, for the same reason `federal_aid_policy` has none, plus one of its
own: a topic argument lets the model fetch `dependency` and answer a
divorced-parent question from memory. One call returns everything the coach may
say, so a half-answer is not reachable.

### 2. The cited-rule type

Mirrors the house pattern at this edge — `AdmissionTestCopy` + `checked_on`
(RFC 197) — not `public-web`'s `ExplainerSource`, which `:service` cannot reach.

```kotlin
data class FafsaRule(
  val topic: FafsaProcessTopic,
  val statement: String,   // one plain sentence a parent could read
  val citedAs: String,     // publisher + document, e.g.
                           // "Federal Student Aid, 2025-26 FSA Handbook, AVG Ch. 2"
  val url: String,         // https, the publisher's own page
  val checkedOn: LocalDate // the day WE read it: 2026-09-14
) {
  init {
    require(url.startsWith("https://"))
    require(statement.isNotBlank() && citedAs.isNotBlank())
  }
}
```

`init` is the guard: a rule with no source does not construct. `checkedOn` is
the day we read the page, which is a different date from the document's own
edition year — the `AdmissionTestTimelineChatTool` legend already draws that
distinction and this tool's legend repeats it.

No expiry and no dated build failure. Brief 0012 D7 asks dated copy to rot
loudly; the research classifies every rule below as STABLE STATUTORY, so a
timebomb here would fire on facts that did not move. The dated material — the
2024 rollout audit, the corrections deadlines, the no-SSN redesign status — is
deliberately NOT shipped as coach copy (§4).

### 3. The wire

```json
{
  "topics": [
    {
      "topic": "divorced_or_separated_parents",
      "rules": [
        {
          "statement": "...",
          "cited_as": "...",
          "url": "https://...",
          "checked_on": "2026-09-14"
        }
      ]
    }
  ],
  "coverage_note": "...",
  "source_note": "..."
}
```

Six topics, in this order: `who_files`, `divorced_or_separated_parents`,
`contributor_without_an_ssn`, `dependency`, `after_you_submit`,
`the_form_is_free`. `DESCRIPTION` is a legend over the keys (RFC 199) — one line
per key, no behaviour rules, which live in the prompt.

### 4. The 30 rules that ship

Every one is STABLE STATUTORY in the research classification, and every one
carries a publisher, an https URL and `checked_on = 2026-09-14`.

**`who_files` (8)** — a contributor is a person who must add their own
information; each contributor needs their own StudentAid.gov account; accounts
can be made before the form opens; consent to the IRS data exchange is required
for federal aid; nobody at a school can give that consent for a family; one
contributor refusing consent stops the student's federal aid, Pell included; the
tax information pulled from the IRS is not shown on the family's own summary;
the student needs a Social Security number (Freely Associated States excepted).
Sources: Dear Colleague Letter GEN-23-11; 2025-26 FSA Handbook AVG Ch. 1 and
Ch. 2.

**`divorced_or_separated_parents` (7)** — the parent who provided more than half
the student's support in the last 12 months is the one on the form; "the parent
you lived with" is the OLD rule and is wrong now; if neither passed half, the
parent with the greater income and assets; child support paid counts toward the
payer's share; if that parent has remarried, the stepparent is also a
contributor; the other parent is not a FAFSA contributor; if that parent dies,
dependency moves to the surviving biological parent, not the stepparent.
Sources: FSA Handbook AVG Ch. 2 (2024-25 and 2025-26 editions).

**`contributor_without_an_ssn` (3)** — a parent or spouse with no Social
Security number CAN create a StudentAid.gov account and be a contributor; they
prove identity through a knowledge-based check instead of a Social Security
match; the paper-form and ITIN mechanics exist but live in a practitioner
handbook families never read, so the school's financial aid office is the place
to ask. Source: FSA Handbook AVG Ch. 1 and Ch. 2.

**`dependency` (5)** — under 24, unmarried and an undergraduate means dependent
even if you live alone and are not claimed on a tax return; independence comes
only from the statutory criteria, which are named; parents refusing to pay or to
give their information does not by itself make a student independent; unusual
circumstances go through a documented dependency override, which is a school
decision; a separated student is not counted as married. Sources: AVG Ch. 2 and
Ch. 5; GEN-23-11.

**`after_you_submit` (4)** — you get a FAFSA Submission Summary, which replaced
the Student Aid Report; the Student Aid Index is calculated and every college
listed receives the data electronically; the Student Aid Index replaced the
Expected Family Contribution and can go as low as -1,500; some applications are
selected for verification and information that came straight from the IRS
generally does not have to be proved again. Sources: GEN-23-11; AVG Ch. 1 and
Ch. 4.

**`the_form_is_free` (3)** — nobody may charge a family for collecting,
processing or delivering the FAFSA; federal aid eligibility may be determined
only from the FAFSA; a paid preparer must identify themselves, faces the same
penalties for false information, must tell the family the form is free, and may
not imply an affiliation with the Department of Education. Sources: 20 U.S.C. §
1090(d)(3) and § 1090(d)(2), Cornell LII.

**A citation correction this RFC makes.** Brief 0012 D6 cites 20 U.S.C. §
1090(d)(2) for the preparer role — correct, and unchanged. The FREE-FORM rule is
§ 1090(d)(3); the preparer's duty to TELL a family the form is free is §
1090(d)(2)(C). The shipped rules cite each to its own subsection.

**What is deliberately NOT shipped**, and why, one line each:

- The 2024 rollout audit figures, including "nearly three-quarters of calls went
  unanswered" (GAO-24-107407) — a dated measurement of one rollout, not a
  standing fact, and not something a family needs told.
- The no-SSN redesign status "as of March 2025" — in-progress and dated. The
  acceptance criterion is that the coach does not guess at the current process
  beyond what is cited, so it states the account rule and sends the family to a
  named office.
- The Federal Register corrections and verification deadlines — dated, and
  `fafsa/01`–`fafsa/03` own dates.
- Nine claims the research marks UNVERIFIED or leaves without a URL: the CSS
  Profile remark, the undocumented-parent data-exposure fear, the "own
  timetable" packaging sentence, the scams page, enforcement actions, the
  preparer-role legal reading, and the "74%" rendering, which is the research
  author's rounding of a quoted "nearly three-quarters" and also collides with
  an unrelated 74% in the same corpus.

### 5. Per-school forms: surfaced harder, never promised

`required_forms` already ships in the `aid_policy` section of
`college_cost_profile`, backed by `aid_form_requirements`. Coverage today is
**219 of 366 CDS-filing colleges** — measured from `db/seed/cds/aid_policy.csv`
on 2026-09-14, and NOT observable at runtime: there is no count key anywhere.

This RFC therefore prints no coverage number. A hardcoded 219 would be a promise
that rots the next time the seed moves, and the runtime truth is per school
anyway: prompt lines 207-210 already say that a school with no filing is said to
have no filing and that an unreadable answer is said to be unreadable. The new
prompt paragraph points at that tool by name when a family asks "which forms
does this school need", and `coverage_note` in the payload says in one sentence
that the corpus holds a filing for some colleges and not all, without a figure.

### 6. The prompt paragraph

One new paragraph in `prompts/coach-system-prompt.md`, one sentence per line,
placed immediately after the `FEDERAL_AID` paragraph (lines 173-179) and before
`SHARE_NUDGE`, because it is the same subject seen from the process side.
Content, in order:

1. Route: when a family asks who files, whose information goes on the form,
   dependency, a contributor without a Social Security number, or what happens
   after submitting, answer from the `fafsa_process` tool rather than from
   memory.
2. Every rule it returns carries the document it came from and the day we read
   that document — give both with the rule.
3. The divorced-or-separated answer is the more-than-half-support rule, and
   never "the parent the student lived with", which is the old rule.
4. Name the stepparent consequence whenever that rule applies.
5. Never state a dependency answer the tool does not carry.
6. Say early that the FAFSA is free, that unicoach never fills it in, submits
   it, or asks for any StudentAid.gov login.
7. Where the answer belongs to an institution, say which office to call — the
   college's financial aid office — and exactly what to ask.
8. Answer all of this without asking the family for anything.

The paragraph must avoid the word `award` (the standing copy-rule helper bans
it), must not contain a bare Common Data Set code (FAFSA is `H.801`), and any
"subtract" must be preceded by `never`.

### 7. The door

An existing coaching conversation. A family asks the question in the chat they
already have, and the answer arrives cited. No new surface, no new permission,
no setting, nothing to enable, and nothing asked of the family. Rollback is the
system-prompt pin: serving the previous `vNN` removes the routing paragraph, and
the tool then simply goes uncalled.

## Files Modified

| File                                                                                    | Change                                                                          |
| --------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------- |
| `service/src/main/kotlin/ed/unicoach/coaching/aid/FafsaProcessCopy.kt`                  | NEW — `FafsaProcessTopic` enum, `FafsaRule` with its `init` guard, the 30 rules |
| `service/src/main/kotlin/ed/unicoach/coaching/aid/FafsaProcessChatTool.kt`              | NEW — no-argument tool `fafsa_process`, rendering + `DESCRIPTION` legend        |
| `service/src/main/kotlin/ed/unicoach/coaching/**` (tool registry)                       | register the new tool beside `federal_aid_policy`                               |
| `prompts/coach-system-prompt.md`                                                        | +8 lines, one new paragraph after line 179                                      |
| `service/src/test/kotlin/.../aid/FafsaProcessChatToolTest.kt`                           | NEW                                                                             |
| `service/src/test/kotlin/.../SystemPromptCatalogTest.kt`                                | new `CoachParagraph` entry, accessor, per-paragraph test                        |
| `db/schema/seed-coach-system-prompt-vNN.sql`, `service/src/main/resources/service.conf` | GENERATED at land by `bin/prompt-seed`; not hand-edited                         |

No migration. No schema change. No new `AidForm`. No change to
`FederalAidPolicyChatTool`'s payload.

## Implementation Plan

1. `FafsaProcessCopy.kt`: the topic enum, `FafsaRule` with its `init` guard, and
   the 30 rules transcribed from
   `.scratch/ship/rfc-216/findings/rule-inventory.md` — statement from column
   (a), `citedAs`/`url` from column (b), `checkedOn = 2026-09-14`.
2. `FafsaProcessChatTool.kt`: closed empty schema, topic-grouped rendering,
   `coverage_note`, `source_note`, and a `DESCRIPTION` legend built from the key
   consts.
3. Register the tool.
4. `FafsaProcessChatToolTest.kt`.
5. The prompt paragraph, its `CoachParagraph` entry and its test.
6. `nix develop -c bin/test`.

## Tests

`FafsaProcessChatToolTest`:

- **every rule is cited** — for all 30: `url` starts with `https://`, `citedAs`
  is non-blank, `checked_on` parses as a date and is not in the future. This is
  the "a load-bearing sentence with no URL and no fetch date does not ship" rule
  at this edge.
- **the six topics are all present and non-empty**, and each rule's topic key
  matches the group it was emitted under.
- **the divorced-parent rule is the support rule** — the
  `divorced_or_separated_parents` statements contain "more than half" and name
  the old "lived with" rule as no longer correct; no statement anywhere asserts
  "the custodial parent" as the answer.
- **the stepparent consequence is present** in that topic.
- **the no-SSN answer is the account rule and nothing beyond it** — the topic
  says an account can be created, and no statement in the payload contains
  "ITIN-based", "2026-27 cycle" or any redesign-status wording.
- **no dated rollout statistic is anywhere in the payload** — no
  "three-quarters", no "74".
- **the free-form rule cites § 1090(d)(3)** and the disclosure duty §
  1090(d)(2)(C).
- **the schema is closed** — any input key is a structured error; the definition
  has `properties = {}` and `additionalProperties = false`.
- **no bare source code** — `BareSourceCodeGuard` over the rendered payload;
  `H.801` never appears.
- **no coverage number** — the payload contains no "219" and no "366".

`SystemPromptCatalogTest`: the new paragraph is registered, its opener starts a
real line and matches exactly once, the served body equals the authored file
byte for byte, and the paragraph itself asserts the routing sentence, the
more-than-half-support sentence, the refusal of "the parent the student lived
with", the free-and-never-touch-it sentence, and the named office.

One existing guard had to be answered rather than routed around.
`MoneyAttributionNamesNoPublisherTest` bans the literal "Department of
Education" in any live string outside `MoneySourceCopy.kt`, because a hand-typed
publisher name on a money figure is a claim the code cannot check. The free-form
rule names the department as the party a family may NOT be charged by (20 U.S.C.
§ 1090(d)(3)) — a statutory fee ban, not an attribution — so the line carries
that guard's own per-line `money-attribution-exempt` marker with that reason,
which is the escape the guard documents. The sentence was not reworded to dodge
the sweep, and the sweep was not widened.

Executed counts are reported from the JUnit XML, not from `grep -c "@Test"`.
