# RFC history of the coach prompt's length & attribution rules

Scope: RFC 175/177/179/181/183/185,
`service/src/main/resources/service.conf:8-177`,
`prompts/coach-system-prompt.md` (158 lines), `SystemPromptCatalogTest.kt`.

Method: I extracted each seed body from
`db/schema/*seed-coach-system-prompt-v*.sql` and found, for each of the 158
prompt lines, the FIRST version containing it.
`" ".join(prompt lines) == v26 body` exactly, so the mapping is mechanical, not
remembered.

## 1. Per-RFC: what it produced

### RFC 175 — `rfc/175-borrowing-at-graduation.md` -> prompt **v21** (`db/schema/0090`)

Substantive prose. ADDED, appended only ("v20 byte-identical prefix plus one
appended paragraph — the additive-only rule", `175:355`). The 7 new sentences
are now `prompts/coach-system-prompt.md:142-148`, e.g. `:143` "Every figure
there is about the students who GRADUATED from that school in the year the
section names -- not this year's freshmen and not all its undergraduates -- so
say that cohort out loud with its year." `:144` "The school reported these
figures about itself, so name the school as the one saying it." `:146` "Never
add two kinds of loan together, and never present one of them as a total." RFC
text: "say federal and private separately; ... never add loan types together;
never subtract a loan from a price; a debt figure is never a price"
(`175:356-359`). Its attribution rule is D10 (`175:437`, `:127` "sentence
attributes the figure ("Amherst College reports that…"), never states" an
unattributed CDS figure).

### RFC 177 — `rfc/177-one-hedge-seam.md` -> prompt **v23** (`db/schema/0093`)

Substantive prose, but **only two sentences**, and it is an INTERIOR edit, not
an append (`177:139-141`: "It is an **interior edit**, so it follows the
`0086`/v19 migration shape, not the additive one"). Those two sentences are the
ONLY prompt lines v23 owns: `prompts/coach-system-prompt.md:32` and `:107`. See
§3 for exact before/after text.

### RFC 179 — `rfc/179-assurance-tiers.md` -> **NO prompt version at all**

`179:54` "No DDL. No new prompt version." and `179:658` "**No prompt version, no
`service.conf` change, no DDL, no seed migration.**" D8 (`179:234-242`) explains
why: "`agentlessStatementOf`'s six sentences are byte-frozen by the immutable
v19 seed row ... Nothing in v23 becomes false: the prompt tells the coach to say
a `figure_statuses` entry's own sentence, and `assurance_statement` is one more
sentence carried by the same entry and described by the tool." So RFC 179 added
zero words to the prompt; it added copy to the TOOL DESCRIPTION (`179:229-232`,
"That is the prompt-facing contract and the one home for that copy (RFC 142)").
Mechanism/data only, from the prompt's point of view.

### RFC 181 — `rfc/181-coach-prompt-as-a-file.md` -> prompt **v24**

Mechanism ONLY, zero prose change. `service.conf:146-147`: "RFC 181: v24 is v23
with NOT ONE WORD CHANGED — the body is byte-identical, and a test asserts
exactly that." `181:201-207`: "### D5 — this slice emits v24, with a body
byte-identical to v23 ... `assertEquals(v23Body, v24Body)`". It created the
authored file (`181:54` D1, one sentence per line; `181:62`
`body = lines.map(String::trim).filter(nonEmpty).joinToString(" ")`) and DELETED
the old "append only, never edit the interior" ancestry tests (`181:245-248`).

### RFC 183 — `rfc/183-the-year-we-cite.md` -> prompt **v25/v26**, body unchanged

Mechanism only, and an acknowledged accident. `183:164` "**D5. No coach prompt
CHANGE — but this run does land a regenerated seed.**"; `183:168-169` "No seeded
prompt body contains an academic year — the immutable `system_prompts` rows say
"Say the academic year the tool gives"" (that is
`prompts/coach-system-prompt.md:67`); `183:178` "**The body is byte-identical to
v25's**".

### RFC 185 — `rfc/185-the-coach-prompt-is-a-markdown-file.md` -> prompt **v27** (live pin)

Mechanism + whitespace only. `185:78-80`: "No word, no sentence and no ordering
changes; the diff is whitespace only, and the acceptance test asserts exactly
that — v27 with every newline replaced by a space equals v26, byte for byte." It
renamed the file to `.md` and made the body the file VERBATIM (`185:27` "**The
body is the file.**"), which makes Markdown structure authorable (`185:41`
"Everything Markdown does, the prompt now does — paragraphs, headings, bullet
and ..."). D6 stops the accidental version bumps: `185:92-93` "That the label
moved twice under slices that changed no prose is a DEFECT, and D6 below fixes
it: v26 is the last version this catalog gains without a human having written
one." Also `185:210-213`: "contents byte-identical (158 lines, one sentence
each, no blank lines). Structure arrives when someone edits the prompt for
content; this slice invents none".

## 2. Prompt line -> owning RFC -> honesty guarantee?

Owner = the first seed version containing that exact line. "Pinned" = the line's
text appears as a string literal in `SystemPromptCatalogTest.kt` (89 of 158
lines are).

| prompt lines | version | RFC | what it is                                                                                                   | honesty guarantee?                                              |
| ------------ | ------- | --- | ------------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------- |
| 1-6          | v1      | 33  | persona, "Be concise and concrete." (`:3`), never-invent (`:5`)                                              | `:5` YES; rest style                                            |
| 7-13         | v2      | 124 | Markdown house style, incl. `:8` "Never write a wall of text..."                                             | NO — style/length rules                                         |
| 14-15        | v3      | 135 | bring cost in early; use the tool not memory (`:15`)                                                         | `:15` YES (anti-memory)                                         |
| 16-21        | v5      | 141 | money vocabulary + loan rule (`:19`) + net-price basis (`:21-22`)                                            | YES (vocabulary is the honesty contract)                        |
| 22-24, 28-31 | v7      | 145 | precision_offer / residency_state / income_band, "No answer of yours is ever gated on either field." (`:31`) | mostly consent rules, YES                                       |
| 25-27        | v19     | 166 | in-district tier                                                                                             | YES                                                             |
| 32           | v23     | 177 | **the money attribution sentence**                                                                           | YES                                                             |
| 33-34        | v6      | 142 | no source jargon; say the band's dollar range                                                                | YES                                                             |
| 35-39        | v4      | 136 | college-list tool etiquette                                                                                  | NO (behaviour, not honesty)                                     |
| 40-46        | v8      | 148 | admissions/CDS; `:41` "attribute them to it and never to memory"                                             | YES                                                             |
| 47-51        | v9      | 149 | living-arrangement split, four lines in order (`:48`)                                                        | mixed: `:48-49` ordering is mechanical, `:50` is honesty        |
| 52-56        | v19     | 166 | the at-home $0 assumption, ours not the school's                                                             | YES (strong)                                                    |
| 57-62        | v9      | 149 | missing parts, no-housing schools, COA vs total, one-year rule (`:62`)                                       | YES                                                             |
| 63-69        | v10     | 150 | subject search, total_matches, excluded-unknown (`:66`)                                                      | YES; `:68-69` re-states the money words = repetition            |
| 70-77        | v11     | 151 | comparison table shape                                                                                       | mixed: `:73` table shape is style; `:74-77` honesty             |
| 78-83        | v12     | 154 | find_college id discipline                                                                                   | YES (`:79`, `:83`)                                              |
| 84-90        | v13     | 153 | similar_colleges axes, distance not a percentage                                                             | YES                                                             |
| 91-98        | v14     | 152 | living_plan                                                                                                  | YES (`:95-96`); `:97` re-states money words = repetition        |
| 99-106       | v15     | 155 | share_cost_report consent + link disclosure                                                                  | YES (`:102`, `:104`); `:106` re-states money words = repetition |
| 107          | v23     | 177 | **the residency attribution sentence**                                                                       | YES                                                             |
| 108-112      | v16     | 157 | in-state/out-of-state basis                                                                                  | YES                                                             |
| 113-118      | v17     | 159 | federal aid; `:114` award year + source                                                                      | YES                                                             |
| 119-123      | v18     | 160 | share-nudge reflection                                                                                       | consent, not honesty                                            |
| 124-133      | v19     | 166 | the six figure_statuses sentences, verbatim                                                                  | YES — and byte-frozen (RFC 179 D8)                              |
| 134-141      | v20     | 170 | aid_policy, meet-full-need, required_forms                                                                   | YES                                                             |
| 142-148      | v21     | 175 | borrowing at graduation                                                                                      | YES                                                             |
| 149-158      | v22     | 169 | search price ruler                                                                                           | YES                                                             |

Accidental / mechanical repetition worth attacking in 0009 (all measured, not
guessed):

- The four money words are re-listed at `:16-17`, `:68`, `:97`, `:106` (and
  partially `:71-72`, `:107`, `:109`, `:111`). `"tuition and fees"` occurs on 12
  lines, `"housing and food"` on 9.
- "never subtract" occurs on 9 lines: `:19, 43, 61, 69, 77, 96, 116, 147, 156`.
- "never raise ... again" occurs on `:30, 98, 118, 151`.
- The "a school that does not report is not a no / say so plainly" idea recurs
  at `:32, 44, 57, 66, 74, 86, 140, 141, 148, 158`. These are per-tool
  restatements of ONE rule, produced by the append-only convention (one RFC =
  one self-contained paragraph). That convention, not the rules, is the length
  driver.

## 3. The attribution rules of RFC 177 and RFC 179

RFC 177 title (`177:1`): "One hedge seam: attribution derived **per figure**,
not typed per site". D6 (`177:136-139`): "**D6. Coach prompt v23 rewrites
exactly the two money publisher-naming sentences** (`0092:117-120`, `:325-328`)
to "attribute each figure to the source the tool names beside it" — the agnostic
form the same prompt already uses at `0092:348`."

Before (v22 body, from `db/schema/0092`):

- "Always attribute cost figures to the U.S. Department of Education College
  Scorecard, and when a school doesn't report a figure, say that plainly rather
  than estimating."
- "... the school reports them for the families of its own state, and the U.S.
  Department of Education publishes them on that basis."

After (v23 .. v27, live text):

- `prompts/coach-system-prompt.md:32` "Always attribute **each cost figure** to
  the source the tool names beside it, and when a school doesn't report a
  figure, say that plainly rather than estimating."
- `prompts/coach-system-prompt.md:107` "... the school reports them for the
  families of its own state, and **the source the tool names** publishes them on
  that basis."

RFC 179 sets NO prompt attribution rule (`179:54`, `:658`). Its attribution
content is D9 (`179:244-248`): "**D9. The tier sentences name no publisher —
deliberately**", i.e. the assurance sentence must NOT name a source; the
publisher stays with RFC 177's seam. It also records (`179:651-654`) that
aggregate surfaces "cannot honestly carry a per-figure tier".

**Does the corpus demand PER-FIGURE attribution in the ANSWER?** Read carefully:
RFC 177's "per figure" is about the DERIVATION (which publisher object is
attached to which number in code), not about how many times the coach must say a
name out loud. The prompt sentence says "attribute **each** cost figure to the
source the tool names beside it". The word "each" does bind per-figure at the
sentence level, so "state the source once per answer" is a CHANGE to `:32`'s
literal wording — but it is compatible with the RFC's stated purpose, which is
only that the named publisher must be the one that actually won (`177:13-14`
"the publisher we name is, for most figures, not the publisher that won"). A
once-per-answer rule stays honest ONLY while every figure shares one source; RFC
177 D5 already handles the multi-source case with `spokenListOf` and a roll-up
"The cost, price and federal debt figures come from ..." sentence
(`177:255-258`) — precedent for a once-per-answer form. `:32` is a TEST-PINNED
string, so changing it also edits `SystemPromptCatalogTest.kt`. I did not verify
that no OTHER test pins the "each ... figure" phrasing; grep before spec.

Other attribution sentences, for contrast: `:41` (CDS, per RFC 148) "attribute
them to it and never to memory"; `:114` (federal aid, RFC 159) "Always say which
award year a figure is for, and attribute it to the source the tool cites";
`:135` (RFC 170) "give the year and the source with the figure"; `:144`
(RFC 175) "name the school as the one saying it". Four separate per-tool
attribution sentences saying nearly the same thing = the clearest compression
target.

## 4. Rollback convention

`service.conf` states it identically after every version, e.g. `:113-114`: "#
Rollback is COACHING_SYSTEM_PROMPT_VERSION=v20 — the v20 row is immutable\n# and
stays in the catalog." Live pin: `service.conf:176`
`systemPromptVersion = "v27"`; current rollback `:173-175` "Rollback is
COACHING_SYSTEM_PROMPT_VERSION=v26 — that row is immutable and stays in the
catalog. It restores the single-line body; since the two differ only in
whitespace, the rollback changes no word the model is sent." RFC form:
`181:49-52` "One immutable row per version, `convo_requests.system_prompt_id`
still pinning every request to the exact row that served it, rollback still
`COACHING_SYSTEM_PROMPT_VERSION=<previous>`."

Enforcing test: `SystemPromptCatalogTest.kt`. For a NEW version it asserts:

- `:612` `the pinned coach prompt body is exactly the authored source file` —
  `assertEquals(authored, served)` against `../prompts/coach-system-prompt.md`
  (`:378`); failure message says "the seed is a GENERATED artifact, so edit the
  file and regenerate: nix develop -c bin/prompt-seed" (`:628`).
- `:665`
  `the line-broken coach prompt differs from its space-joined predecessor in whitespace only, never a word`
  — v27 flattened == v26 (RFC 185 acceptance). `:658` says a later slice LEAVES
  THIS ALONE: "authoring prose adds a version above the pair and says nothing
  about it."
- `:711`
  `every paragraph opener still starts a line of the authored source file` —
  `CoachParagraph` openers are Kotlin literals; rewording a paragraph's FIRST
  sentence fails here (`:718`).
- `:753` `the pinned coach version is the catalog's highest coach version`;
  `:358` every pinned version exists in the seeded catalog.
- Immutability of old rows is enforced as "vN stays selectable as v(N+1)'s
  rollback target" tests — nine of them, kept deliberately (`181:250-254`) —
  e.g. `:1687` `coach v26 stays selectable so the v27 rollback is real`, which
  also asserts v26 contains no line break (`:1692`) and still carries the money
  paragraph (`:1695`). The DB itself is insert-only ("`system_prompts` is
  insert-only", `:113`, `:654`).
- ~89 of the 158 prompt lines have their text pinned as string literals by
  content tests (`the served money paragraph ...` `:837-940`,
  `the served figure-status paragraph recites every shipping status sentence`
  `:1983`, etc.). Any compression must move those literals in the same commit.

## 5. RFC text that already complains about verbosity — almost none in 175-185.

The only length rules in the corpus are older:

- `prompts/coach-system-prompt.md:3` "Be concise and concrete." (v1, RFC 33).
- `:8` "Never write a wall of text: hold paragraphs to two or three
  sentences..." and `:9` "Equally, do not dress up a one-line answer: a short
  factual reply is a sentence, not a list of one." — both v2, from
  `rfc/124-coach-markdown-house-style.md:49`, `:68`, `:123`.
- I grepped `rfc/*.md` for verbos|wall of text|repeats itself|too many
  words|prompt is long: no hit in 175/177/179/181/183/185. **No RFC in this
  window complains about the PROMPT's own length or about answer repetition.**
  The nearest thing is RFC 185's complaint about accidental version churn
  (`185:140-155`) and RFC 181's complaint about hand-rebuilt paragraphs
  (`181:13-14`).

Caveat: the `0092`/`0093` line numbers inside RFC 177 are that RFC's own
references; I verified the quoted v22 strings by extracting the v22 body, not by
reading those lines.
