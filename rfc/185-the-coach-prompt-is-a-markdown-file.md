# RFC 185 — the coach prompt is a Markdown file

## Problem

RFC 181 made the coach prompt an ordinary file and, without saying so at the
gate, made one thing impossible: **the body cannot contain a line break.**

The join rule is `strip each line -> drop blank lines -> join with one space`,
so every newline collapses. The prompt the model receives is a single
25,130-character run of prose. Paragraphs, lists and headings are not unused —
they are unrepresentable.

The obvious fix is to call the file `.md`. But Markdown is a _block_ format: a
heading, a list item and a table row are all lines whose line break is load
bearing. A join rule that collapses newlines cannot carry them. The intermediate
position — call it `.md`, support paragraphs, and refuse the rest — was drafted
and rejected: **a Markdown file that does not support Markdown is not a Markdown
file.** It also fails in the worst direction available, because a `- item` line
joined into surrounding prose reaches the model as
`... previous sentence. - item next sentence ...`, and nothing downstream can
see it — the seed and the source are equally wrong.

The join rule is the problem. So remove it.

## Decision

**The body is the file.** `prompts/coach-system-prompt.md`, verbatim, byte for
byte. No trimming, no joining, no dropping, no rule.

One byte is not body: the file's FINAL line terminator. The body is the file's
LINES joined by newline — the text minus at most one trailing newline. This is
not an exception to "verbatim", it is the emitter's exact INVERSE: the generator
emits one fragment per awk RECORD, and a record does not carry the newline that
ends it, so a body that ended in `chr(10)` could never be produced from any
file. Reading it back therefore gives the file minus that one byte, and the
round trip closes. It is also the right rule on its own terms — that byte ends
the last line rather than being a line, and whether an editor leaves it is
invisible in every diff, so making it body would make an unreviewable byte load
bearing under D6, where adding or removing it would mint a catalog version.

Everything Markdown does, the prompt now does — paragraphs, headings, bullet and
ordered lists, block quotes, tables, fenced code, and anything CommonMark grows
later — because nothing interprets the file. Support is not a feature list; it
is the absence of a transform.

This is a smaller design than the one it replaces, and that is the argument for
it. Consider what disappears:

- **The join rule**, and with it the three separate whitespace classes that
  produced RFC 181's worst defects. The U+2000 trap, the bare-CR trap and the
  tab/double-space refusals were all consequences of a lossy transform needing
  to be reversible. A verbatim copy is reversible by construction.
- **The double entry.** RFC 181's Kotlin oracle restated the join rule in a
  third language precisely because it was a rule. It becomes
  `assertEquals(sourceFile.readText(), servedBody())` — an oracle with nothing
  to drift from.
- **The wrap arithmetic.** `FRAGMENT_WIDTH=65`, the byte-versus-character
  portability trap, and the awk column maths all go: the generated SQL now emits
  **one fragment per source line**, joined by `chr(10)`. The seed's diff becomes
  line-for-line the source's diff, which is the property this whole line of work
  has been chasing.

### D1 — line breaks are content, and that is what makes merges work

Under RFC 181 a newline was free, so sentence-per-line was pure merge
granularity. Now a newline is body text. That sounds like a loss and is not: the
file is authored **one sentence per line** exactly as before — the "semantic
linefeeds" style — and two runs editing different sentences still touch disjoint
lines. The difference is that the model now receives those newlines too.

### D2 — the served body changes, once, and this slice owns it

Every sentence-joining space becomes a newline. That is a real change to what
the model is served, so it gets a real version: **v27**, seeded the ordinary
way, with `service.conf` pinned to it by the generator and v26 still selectable
for rollback.

Say what the change is and is not. No word, no sentence and no ordering changes;
the diff is whitespace only, and the acceptance test asserts exactly that — v27
with every newline replaced by a space equals v26, byte for byte. Whether an LLM
reads a newline differently from a space between two sentences is not a question
this repo can answer with evidence, so the RFC does not claim it is free; it
claims it is inert on the text and verifiable on the bytes.

The predecessor is v26, not v24, and that costs the claim nothing. v25 and v26
were minted by commits `3398491c` (an IPEDS test-fixture change) and `b981fff9`
(RFC 183, on how Scorecard money figures are dated); neither touched `prompts/`.
The generator regenerates the seed at every land and bumps the label whether or
not a word was authored, so v25's and v26's bodies are both byte-identical to
v24's. The comparison therefore holds against v26 exactly as it held against
v24, and the `service.conf` log records this. That the label moved twice under
slices that changed no prose is a DEFECT, and D6 below fixes it: v26 is the last
version this catalog gains without a human having written one.

### D3 — `deno fmt` must not touch this file, and now it is dangerous

`deno.json` sets `proseWrap: "always"` at `lineWidth: 80`. Measured: `deno fmt`
alters no character of this prose — not `*`, `_`, `snake_case` or the em dash —
but it **re-wraps sentences across lines**.

Under RFC 181 that was bad because reflow destroys the line granularity that
makes two prompt edits merge. Under this RFC it is worse: line breaks are body,
so a reflow **silently rewrites the prompt** and mints a new version on the next
land.

So `prompts/` joins `fmt.exclude` in `deno.json`, with the reason written down.
RFC 181 relied on the `.txt` extension keeping the file out of a `**/*.md` glob
— protection by accident, never declared. This declares it, and it must land in
the same commit as the rename or one `bin/format` run reflows the prompt between
them.

### D4 — what the validator still refuses

Almost nothing, because there is no transform to protect. Two refusals survive,
both about bytes that cannot be authored deliberately:

- **A bare CR.** Verbatim means a CRLF checkout would put `\r` into the prompt.
  Refused by line number, as today.
- **A source with no content.** An empty file, and equally a file of nothing but
  whitespace — which would pass a `length(body) > 0` CHECK and is still a prompt
  with nothing in it. `system_prompts.body` has a non-empty CHECK; refusing
  early gives a better message than the database's. Stated over the BODY, so one
  message names the file either way.

Trailing whitespace is no longer stripped — it is content, and it is visible in
review. Tabs, interior double spaces and the U+2000 case stop being refusals and
become ordinary text, because nothing collapses them any more.

### D5 — one fragment per line

The generated seed keeps the `||`-joined single-quoted shape every coach seed
before it uses, with `''` escaping, but the fragment boundary is now the
source's own line boundary and the joiner is `chr(10)`. `chr(10)` rather than a
literal newline inside a quoted fragment: a literal newline in a diff is
indistinguishable from layout, which is the lesson `0011:1-7` already recorded
about `||` being "layout only".

### D6 — a version is cut only when a human authored one

`bin/prompt-seed` generates `tip + 1` **unconditionally**. It never asks whether
anyone authored anything. Phase 6 of the ship SKILL runs it at every land, so
**every landed run mints a new immutable coach-prompt version**, identical to
the one before it.

The evidence is on `main`, and it fired TWICE while this very RFC was in review.
Commit `3398491c` ("test-fixtures: derive IPEDS IC_AY column names instead of
typing them") never touched `prompts/`. It added
`db/schema/seed-coach-system-prompt-v25.sql` whose body is byte-identical to
v24's, moved the live `systemPromptVersion` pin onto v25, and added no
`service.conf` comment-log entry — because its author never knew a version had
been cut. Hours later, with this run still in review, commit `b981fff9` ("money:
date Scorecard figures as the publisher dates them", RFC 183) did the same thing
again: a change about money figures, not one byte under `prompts/`, and out came
`seed-coach-system-prompt-v26.sql` — a third copy of the v24 body, pinned live,
unlogged. The catalog now holds v24, v25 and v26 carrying the identical
space-joined body, two of them minted by runs that authored nothing. D2 above
notices the first of these and calls it "the generator working as designed".
That reading was wrong, and this decision replaces it: the design was wrong. A
defect that reproduces itself twice inside one review is not a hypothetical.

The cost is one immutable row per land, forever, each saying exactly what the
row before it said, with `convo_requests` foreign keys pinning real traffic to
churn — so the catalog stops being a record of what anyone wrote, and a rollback
target picked by version number means nothing.

**The rule: generate a new version if and only if the authored BODY differs from
the BODY of the catalog tip in `<base-ref>`.** Otherwise no-op — no seed, no
`ORDER` line, no repin — and exit 0 saying plainly that nothing was authored. A
run that had cut a version and then had its edit reverted takes its own seed,
`ORDER` line and pin back out, so the rule converges instead of merely
declining.

**This was a defect in RFC 181's design, not in its implementation.** RFC 181 is
committed and stays exactly as written; the correction lands here, in a
higher-numbered RFC, per `rfc/README.md`.

At the level of the **body**, and not of the file, because the two cheaper tests
are both wrong and in opposite directions:

- _"Did the source file change?"_ would refuse **this RFC**. The `git mv` to
  `.md` leaves the contents byte-identical while the body genuinely changes:
  joined-with-spaces becomes verbatim-with-newlines. v27 is owed and a
  source-comparison rule would not cut it.
- _"Do the two SQL files differ?"_ would mint a version for a change to the
  generator's own **layout** — its wrapping, its comment header — with no body
  touched. v26's own file shares almost no bytes with what this generator emits,
  and says the same thing.

So both sides are read back to text and compared as bodies: the tip is read from
`<base-ref>` (never from the worktree, which may hold this run's own seed), its
`''` unescaped and its `chr(10)` joiner turned back into a newline, and the
result compared with the body this run would emit. The reader is an awk
transliteration of the `body_of` re-reader the shell harness already carries —
awk and not `python3` because `ship-land` invokes this script outside the Nix
dev shell, where a `python3` stub would turn the land gate into an install
prompt.

A tip that cannot be read is refused loudly, because the expensive failure is
the silent one: an unreadable tip read as "identical" never cuts the version a
human did author. A base carrying no coach seed at all is refused as it already
was, for the same reason.

**The consequence worth naming:** a land where nobody edited the prompt now
touches neither `service.conf` nor `db/schema/`. That is what finally makes
`ship-land`'s `-n` re-check meaningful. Today it can only ever answer "current",
because the generator has just written the very thing `-n` is about to check.

## Detailed Design

**`prompts/coach-system-prompt.md`** — `git mv` from `.txt`; contents
byte-identical (158 lines, one sentence each, no blank lines). Structure arrives
when someone edits the prompt for content; this slice invents none, per RFC 181
D1's refusal to guess topic boundaries.

**`bin/prompt-seed`** — delete `authored_trim`, the join, `FRAGMENT_WIDTH` and
the wrap awk. Read the file; emit one `'<escaped line>'` fragment per line
joined by `|| chr(10) ||`. Keep every RFC-181 refusal that is not about the
join: base-ref staleness, ORDER corruption, the pin guard, the atomic staged
install. Reduce the validator to D4. `SOURCE_FILE` default becomes the `.md`
path. Add the D6 authorship gate: a `BODY_AWK` reader that recovers a seed's
body, `resolve_authorship` comparing the tip's body with this run's, and the two
no-op paths — `write_nothing_authored_tree` and
`assert_nothing_authored_tree_is_current` — that the write half and the `-n`
half take when nothing was authored.

**`SystemPromptCatalogTest.kt`** — `joinSourceLines` and its regression tests
are deleted; the oracle becomes a direct file-to-row equality. Add the v27/v26
whitespace-only acceptance test. Keep the rollback, pin and content assertions.

**`deno.json`** — `prompts/` in `fmt.exclude`, with the reason as a comment.

**`db/schema/seed-coach-system-prompt-v27.sql`** — generated, not hand-written.

## Files Modified

| File                                                     | Change                                                                              |
| -------------------------------------------------------- | ----------------------------------------------------------------------------------- |
| `prompts/coach-system-prompt.txt` -> `.md`               | rename, contents unchanged                                                          |
| `bin/prompt-seed`                                        | join and wrap deleted; one fragment per line; validator reduced; D6 authorship gate |
| `bin/prompt-seed-scripts-tests`                          | assertions follow the rule change; the D6 assertions                                |
| `.prime/agent/skills/ship/SKILL.md`                      | phase 4 and phase 6 prose: `.md`, and a land that authored nothing writes nothing   |
| `deno.json`                                              | explicit `fmt.exclude` for `prompts/`                                               |
| `db/schema/seed-coach-system-prompt-v27.sql`             | NEW, generated                                                                      |
| `db/schema/ORDER`                                        | one line                                                                            |
| `service/src/main/resources/service.conf`                | pin v27 (written by the generator) + log entry                                      |
| `service/src/test/kotlin/.../SystemPromptCatalogTest.kt` | oracle simplified; whitespace-only test; the v26 rollback-target test               |
| `service/src/test/kotlin/.../CoachingConfigTest.kt`      | comment: `.txt` -> `.md`; no assertion changes                                      |
| `.prime/agent/skills/ship/scripts/ship-land`             | help text: `.txt` -> `.md`; the `-n` re-check itself is unchanged                   |
| `rfc/185-*.md`                                           | this RFC                                                                            |

Not modified: the `system_prompts` schema, every applied migration,
`SystemPromptsDao`, the admin resource. `ship-land`'s `-n` re-check keeps its
BEHAVIOUR — only the file name in its help text changes — and D6 is what makes
that check able to say something.

## Implementation Plan

1. `git mv` to `.md` **and** add the `deno.json` exclusion in the same step; run
   `nix develop -c bin/format` and confirm `git diff -- prompts/` is empty. This
   protects every step after it.
2. Strip the join, wrap and now-dead refusals from `bin/prompt-seed`; emit one
   fragment per line joined by `chr(10)`.
3. Generate v27 with the tool; let the generator move the pin.
4. Simplify the Kotlin oracle; add the whitespace-only acceptance test; delete
   the join-rule regression tests that no longer describe anything.
5. Add the D6 authorship gate to `bin/prompt-seed`: read the tip's body out of
   `<base-ref>`, compare it with the body this run would emit, and take the
   no-op path when they agree — in the write half and in `-n` alike. Confirm v27
   is still generated, since its body DOES change.
6. Correct the ship SKILL's phase 4 and phase 6 prose, which still tells an
   author to edit a `.txt` and still claims a promptless land "regenerates the
   same bytes and changes nothing".
7. Assertions per the Tests table.

## Tests

| Case                                                                        | Test                                                                                                                                                                                           |
| --------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| The served body IS the file, byte for byte, minus its final line terminator | Kotlin: `assertEquals(sourceFile.readText().removeSuffix("\n"), servedBody())`                                                                                                                 |
| No word or ordering changed                                                 | Kotlin: v27 with every `\n` replaced by a space equals v26                                                                                                                                     |
| A Markdown heading, list, table and fenced block survive intact             | shell: fixture containing each; body compared byte for byte                                                                                                                                    |
| A blank line survives as a blank line                                       | shell fixture                                                                                                                                                                                  |
| Trailing whitespace is preserved, not stripped                              | shell fixture                                                                                                                                                                                  |
| A bare CR is refused by line number                                         | shell fixture                                                                                                                                                                                  |
| An empty source is refused                                                  | shell fixture                                                                                                                                                                                  |
| `deno fmt` does not touch the prompt                                        | shell: a copy of `deno.json` and the prompt in a temp dir, formatted with `bin/format`'s own `deno fmt "${MARKDOWN_FMT_PATHS[@]}"` invocation, plus the positive control without the exclusion |
| Rollback to v26 still serves the old body                                   | Kotlin: `coach v26 stays selectable so the v27 rollback is real`, the rollback-target family extended to v26 with a literal label                                                              |
| Every applied seed untouched                                                | no `db/schema/00*.sql` or v26 seed in the diff                                                                                                                                                 |
| A land that authored nothing mints NO version (D6)                          | shell: no seed, `ORDER` unchanged, pin unmoved, exit 0                                                                                                                                         |
| ...and says so, rather than exiting 0 in silence                            | shell: the run names "nothing was authored"                                                                                                                                                    |
| ...and `-n` on that tree exits 0 without demanding a seed                   | shell fixture                                                                                                                                                                                  |
| ...and `-n` REFUSES that tree when it holds a seed, or a pin off the tip    | shell: exit 3 in each case, naming the seed and naming the tip                                                                                                                                 |
| Reverting an authored edit takes the seed, `ORDER` line and pin back out    | shell fixture                                                                                                                                                                                  |
| One edited sentence mints exactly one version, and only one                 | shell: seeds absent from the base number 1, twice over                                                                                                                                         |
| A tip with the same body in a different SQL layout mints nothing            | shell: a landed seed written in a foreign dialect                                                                                                                                              |
| An unchanged source whose BODY changed still mints (this RFC's own case)    | shell: joined-with-spaces tip, source untouched against the base                                                                                                                               |
| A tip whose body cannot be read is refused by name                          | shell fixture                                                                                                                                                                                  |
| A base carrying no coach seed at all is refused, never restarted at v1      | shell fixture                                                                                                                                                                                  |
