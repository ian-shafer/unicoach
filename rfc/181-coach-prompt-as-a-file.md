# RFC 181 — the coach prompt is an ordinary file, and its migration is generated at land

## Problem

Every coach prompt version is a new file,
`db/schema/NNNN.seed-coach-system-prompt-vNN.sql`. Two runs that both touch the
prompt therefore produce two unrelated file ADDS of the same logical thing. Git
sees an add/add conflict with no common ancestor and cannot merge it, because
there is nothing to merge: the two files share no history. The resolution is
always the same manual surgery — take the other run's body, find your paragraph,
and retype it into their text.

In one recent stretch this happened three times running (v19 -> v20 -> v21 ->
v22), and each time a paragraph was rebuilt by hand on somebody else's body. A
hand-rebuilt 25,000-character string is exactly the kind of edit that loses a
sentence silently.

The repo already knows this is the risk, and pays for it with assertions.
`service/src/test/kotlin/ed/unicoach/coaching/SystemPromptCatalogTest.kt` is
2,434 lines and 51 tests, and its load-bearing primitives —
`appendedParagraph()` (14 callers), `insertedSpan()`, `revisedMiddle()`, and
three whole-body equality tests of the form _"coach v21 keeps the whole v20
body, byte for byte, as its prefix"_ — exist to police, by assertion, the
ancestry git would have tracked for free. They are a manual diff engine, written
out longhand, for a file format that denied git the chance to be one.

And the rule they encode is no longer true. Both RFC 166 and RFC 177 edited
INTERIOR spans, so no prefix survived; each had to add a bespoke
_reconstruction_ test instead
(`v23 must be v22 with exactly these two spans rewritten and nothing
else changed`,
`SystemPromptCatalogTest.kt:1629`), which pins the old wording as string
literals in Kotlin. The corpus now describes a discipline the prompt stopped
following two versions ago.

The cause is a format choice, not a process failure. The body is stored as one
continuous run of text — 25,130 characters, no newlines, no tabs, not one double
space — assembled from ~412 hand-wrapped SQL string fragments concatenated with
`||`, wrapped at arbitrary columns mid-sentence. There is no unit for git to
diff. Give the prompt a line-based source file that is edited in place, and
every one of these problems is a solved problem that git solves.

## Decision

The coach prompt body becomes **one authored text file, edited in place**, and
the seed migration becomes a **generated artifact** — deleted and rewritten at
land, after the final rebase, from the tree that is actually landing.

The database does not change shape as a catalog. One immutable row per version,
`convo_requests.system_prompt_id` still pinning every request to the exact row
that served it, rollback still `COACHING_SYSTEM_PROMPT_VERSION=<previous>`. What
changes is only where the body is AUTHORED and how the row is PRODUCED.

### D1 — the source file: `prompts/coach-system-prompt.txt`

Plain UTF-8 text, outside `db/schema/` so it is not an applied migration and can
be edited forever. `.txt`, not `.md`, so `bin/format`'s `**/*.md` glob does not
reflow the body under the author.

The join rule is one line and exactly reversible:

    body = lines.map(String::trim).filter(nonEmpty).joinToString(" ")

Strip each line, drop blank lines, join the rest with a single space. That
reproduces today's body byte-for-byte, which is verified in this RFC's work and
not assumed: the v23 body contains no newline, no tab, no double space, and no
leading or trailing whitespace, so no information is lost in either direction.

The file is authored **one sentence per line**. This is the whole point of the
change and deserves saying plainly: merge quality is a function of line
granularity. One sentence per line means two runs editing different paragraphs
touch disjoint, distant line ranges, and git merges them without a prompt.

The initial extraction is flat — 158 sentences, no blank lines — because today's
body carries no paragraph markers to recover, and guessing at them would be this
slice inventing structure it promised not to invent. Blank lines ARE permitted
and are dropped on join, so a later edit may introduce them at zero cost and
with no effect on the body.

`bin/prompt-seed` refuses a source file that would not round-trip: a tab
character, or a line with interior double spaces. It does not refuse trailing
whitespace; it strips it, because that is what a stray space at end-of-line
deserves.

### D2 — `bin/prompt-seed`, the generator

    bin/prompt-seed [-b <base-ref>] [-n]

Reads `prompts/coach-system-prompt.txt`, computes the next version label from
the catalog tip, deletes this run's own un-landed seed if one exists, and writes
`db/schema/seed-coach-system-prompt-v<N>.sql` in exactly today's shape — the
same `INSERT INTO system_prompts (name, version, body) VALUES (...)` with
`||`-joined single-quoted fragments and `''` escaping. The generated SQL is not
a new format; a reader diffing it against 0093 sees the same file.

House style is `bin/db-reset`'s: source `bin/common`, `getopts ":b:d:nh"` with
the two reserved usage-error arms, help text to stdout, every diagnostic to
stderr, `bin/functions`' exit band.

**The tip, and what is deletable.** The version label is
`max(landed label) + 1`, where _landed_ means a
`db/schema/*seed-coach-system-prompt-v*.sql` present in `<base-ref>` (default
`main`). A seed file in the worktree that is NOT in `<base-ref>` is this run's
own, generated, and therefore deletable — that single rule is what makes
regeneration safe. It reads correctly only while `<base-ref>` is the tip this
run lands on: a base ref that is BEHIND ITS UPSTREAM reports another run's
already-landed seed the same way, so such a base is refused
(`EXIT_INVALID_ARG_VALUE`) rather than acted on, and no applied migration is
ever touched. `ship-land` passes a SHA, which tracks nothing, so the land path
is unaffected. Delete-then-write, never edit in place, so a stale label cannot
survive — with the new seed INSTALLED FIRST and the target skipped by the delete
loop, so the corpus never passes through a state carrying no coach seed at all.

Post-RFC-180 the file carries **no number**: `<kebab-slug>.sql`, with
`db/schema/ORDER` declaring apply order. `bin/prompt-seed` appends its line to
`ORDER` if absent, and `ship-order` re-places it at land like any other
migration. No numbers are allocated by this RFC.

`-n` is a dry run: writes nothing, exits **3** when the tree's seed differs from
what it would generate, 0 when clean. Same contract and same status as
`ship-order -n`, deliberately, because it is used the same way.

Because the label depends only on `<base-ref>` and the body only on the source
file, the output is byte-identical whether produced locally or at land, given
the same source and the same tip. The wrap rule is part of that guarantee, so it
is stated in BYTES: fragments are greedily filled to at most 65 body bytes under
`LC_ALL=C`, measured before `''` escaping, and broken at spaces only. Characters
would not do — `gawk`'s `length()` counts characters and most other `awk`s count
bytes, so a character rule wraps one way on the author's machine and another way
at land, and `-n` would refuse a seed that is in fact correct. Breaking at
spaces only keeps a multi-byte character (an em dash) whole.

### D3 — regeneration at land

`bin/prompt-seed` runs in the land sequence between the final rebase and the
squash — the same seam `ship-order` occupies, for the same two reasons. It must
be **after** the last rebase, because only then is the tip the tree that is
actually landing; a label computed before the rebase is the stale-number bug
this RFC exists to remove. It must be **before** the squash and the commit, so
the regenerated seed is part of this run's staged diff and lands **inside the
hook-verified tree**. Doing it in `ship-land` instead would need a `--no-verify`
commit, which is a hole in the one gate phase 6 exists to protect.

    ship-lock acquire
    ship-rebase
    bin/prompt-seed          # <- new: regenerate from the landing tree
    ship-order
    ship-squash
    bin/format
    git commit               # the gate
    ship-land

`ship-land` re-runs `bin/prompt-seed -n` before the fast-forward, next to its
existing `ship-order -n` check, and refuses with that script's own status. A
skipped regeneration is otherwise invisible — the seed exists, only its label is
wrong — which is precisely the argument RFC 180 made for the `ship-order` check.

**The two-run case, end to end.** Runs A and B both add a paragraph. A lands
`v24`. B rebases: the source file merges cleanly, because the two paragraphs are
different lines; B's own `v24` seed is not in the new base, so `bin/prompt-seed`
deletes it and writes `v25` from the merged source — containing both paragraphs,
with no hand editing anywhere.

### D4 — the seed row records NO source hash

An earlier draft of this RFC added a `source_sha256 TEXT` column to
`system_prompts`, had the generator insert `sha256(source file bytes)`, and had
a Kotlin test recompute it. That was implemented, and then removed during
review. The reasoning, recorded here because the code is what the reviewers
read:

An immutable catalog row cannot stay accurate about a mutable file. Every row of
`system_prompts` is immutable by trigger — that is the guarantee the table
exists for — while `prompts/coach-system-prompt.txt` is edited by hand. Hashing
the RAW FILE BYTES therefore fails the build on a harmless re-wrap, an added
blank line, or a trailing newline, and fails it PERMANENTLY: the row cannot be
updated, so the only way back to green is a whole new prompt version for a
whitespace change. The join rule exists precisely so that those edits do not
change the body; a raw-byte digest reintroduces the fragility the rule removes.

Hashing the JOINED BODY instead removes the false failure, and buys nothing: the
value is then computable from `body` in the same row, which this repo's schema
convention forbids in as many words — "derived figures (shares, percentages,
anything computable from stored columns) are computed at read time and labeled
as derived — never stored".

Recording the digest and NOT asserting it is worse than not recording it. A
provenance column nobody checks goes stale silently, and its presence invites a
reader to trust it.

The guarantee the column was meant to provide — a stale or hand-edited seed
fails the build — is already delivered, with no false-failure mode, by the
body-equality test in D6: the SERVED row's body must equal the join of the
authored source file. That test catches a hand-edited seed, a source edited
without regenerating, and a seed generated from a different source, and it
catches them by comparing the two things that actually have to agree.

So `system_prompts` is unchanged by this RFC: no new column, no new migration,
and the generated `INSERT` names `(name, version, body)`.

### D5 — this slice emits v24, with a body byte-identical to v23

The mechanism is not real until a row is produced by it. `bin/prompt-seed`
generates `v24` from the source file, `service.conf` pins `v24`, and the source
file is derived from the v23 body, so:

    assertEquals(v23Body, v24Body)

is a true statement and the acceptance test for "no wording changed in this
slice". Rollback to v23 is unaffected — the row is immutable and still
selectable.

The alternative, landing the mechanism without a row, was rejected: it leaves
the generator and the land-time regeneration both unexercised by anything that
actually ran, which is the same as not knowing whether they work.

### D6 — what replaces the byte-identical-prefix tests

**Retired**: `appendedParagraph()`, `insertedSpan()`, `revisedMiddle()`, the
three whole-body prefix-equality tests (v20/v19, v21/v20, v22/v21), the two
reconstruction tests (v19/v18, v23/v22), and the twelve
`coach vN preserves ... verbatim` ancestry tests. The retirement is therefore
WIDER than a first reading of this list: every test whose subject is "version N
against version N-1" goes, not only the ones named first.

It is also NARROWER in one place. The 14 `appendedParagraph()` callers are KEPT
— they are content assertions — and re-pointed at one new, non-ancestral
locator, `paragraphAt(paragraph)`, which slices the SERVED body (the row
`service.conf` pins) from a paragraph's opening words to the next paragraph's,
with the openers listed once in the closed `CoachParagraph` enum (see Detailed
Design). It compares no two versions and quotes no previous version's wording. A
locator is REQUIRED, not a convenience: several kept content assertions are
paragraph-SCOPED negatives (`assertFalse(contains("room and board"))`,
`sticker`) which the whole body fails, because the glossary paragraph states
those phrases in order to retire them. Body-wide, they would have to be deleted.
One test pins the index against the file, so an edited opening sentence fails
loudly instead of silently widening someone else's scope. The suite therefore
goes 51 -> 41, not 51 -> 33.

Say plainly why. Those tests answer one question — _did this version change
anything it did not mean to change?_ — and they answer it by reimplementing diff
in Kotlin against string literals of the previous wording. From this RFC on,
that question is answered by `git diff prompts/coach-system-prompt.txt`, on a
line-based file, reviewed in the diff of the run that changes it. A reviewer
sees the changed sentences and nothing else. The old rule described a discipline
(append only, never edit the interior) that the prompt already stopped following
in v19 and again in v23; keeping assertions that encode a retired rule teaches
the next author a convention nobody follows.

**Kept, all of them**: every content assertion about the body we SHIP (a
sentence is present, a forbidden phrase is absent, every `FigureStatusCopy`
sentence survives), the nine "stays selectable as rollback target" tests, and
the config-pin test. Those assert what the prompt SAYS and what the catalog
GUARANTEES, which no file format makes redundant.

"Kept" is bounded to the served body deliberately. Two assertions were true only
of superseded rows — the v5/v7 `U.S. Department of Education College Scorecard`
attribution, a phrase v23 deliberately removed and which is absent from v24.
Those pin history, not shipped copy, so they go with the ancestry tests rather
than being re-homed.

**Added**:

1. `the pinned coach prompt body is exactly the authored source file` — reads
   the source, applies the join rule, compares to the row read from the DB. Per
   D4 this is the SOLE guarantee that the shipped seed still corresponds to the
   file, so its failure message names both artifacts, the character they part
   at, an excerpt from each side, and the command that regenerates the seed.
2. `the pinned coach prompt is byte-identical to its predecessor, because RFC 181
   changed no wording`
   — this slice changed no wording. Both labels are derived from the pin, never
   written as literals.
3. `every paragraph opener still starts a sentence of the authored source file`
   — the drift guard on `CoachParagraph` described above.
4. `the authoring join rule keeps a non-ASCII space, because the generator does`
   — the whitespace class pinned by example (see **One whitespace class**).
5. `the pinned coach version is the catalog's highest coach version` — the
   pin-versus-catalog relation that replaced `CoachingConfigTest`'s `"v24"`
   literal, now that the generator owns the pin line.

Each title says `coach`: this file also pins the extraction, synthesis and two
`fitLens` prompts, so an unqualified "the pinned prompt" names nothing.

Together these are strictly stronger than what was retired: the old tests pinned
version N against version N-1's literal, and left the file on disk unchecked;
the new ones pin the shipped row against the file a human edits.

### D7 — ordering against other work

No migration-index slice is in flight; RFC 180 landed (it is this run's base)
and `pipeline/rfc-178` is an unrelated, stalled build guard. RFC 181 owns this
area and lands whenever it is ready.

## Detailed Design

**`prompts/coach-system-prompt.txt`** — the v23 body, re-wrapped one sentence
per line, flat: no blank lines (D1 — today's body carries no paragraph markers,
and inventing them is structure this slice promised not to invent). Generated
once, mechanically, from the v23 body extracted from
`db/schema/0093.seed-coach-system-prompt-v23.sql`; the round-trip is asserted,
not eyeballed.

**`bin/prompt-seed`** — `getopts ":b:nh"`, `-b` default `main`.

1. Read and validate the source (exists, non-empty, no tab, no interior double
   space). Reject with `EXIT_INVALID_ARG_VALUE`, naming every offending line in
   one pass.
2. `body` = join rule (D4: nothing else is recorded — no digest).
3. `tip` = highest `vN` among `git ls-tree <base-ref> db/schema/` seed files;
   `next` = `tip + 1`.
4. Delete every worktree seed file not present in `<base-ref>`, and its `ORDER`
   line.
5. Emit `db/schema/seed-coach-system-prompt-v<next>.sql`: a header comment
   naming the generator and forbidding hand edits, then the `INSERT`, with the
   body greedily filled into `||`-joined fragments by a deterministic rule — at
   most 65 body BYTES per fragment under `LC_ALL=C`, measured before `''`
   escaping, broken at spaces ONLY — and `'` escaped as `''`. Bytes rather than
   characters because `gawk`'s `length()` counts characters while most other
   `awk`s count bytes, and the whole point of D2 is output that is
   byte-identical locally and at land, on whichever machine runs it. Spaces
   only, so a multi-byte character (an em dash) is never split across two
   fragments. The emitted column is an OUTPUT of that rule, not the rule: a
   fragment carrying apostrophes ends a few columns further right.
6. Append the filename to `db/schema/ORDER` if absent. Membership, the ORDER
   path and the migration filename grammar are all asked of `bin/common`, the
   declared sole owner of that contract, not re-implemented here: a `grep -Fxq`
   of the file compares bytes, so a CR-terminated line read as ABSENT and a
   duplicate was appended for one migration — refused two steps later by
   `db-migrate`, with no mention of the CR. `bin/common`'s ORDER scan lost its
   `local -A` associative arrays for the same reason `bin/prompt-seed` avoids a
   heredoc inside `$( )`: see **Portability** below.
7. Rewrite `systemPromptVersion = "vNN"` in
   `service/src/main/resources/service.conf` to the label just generated. The
   label is computed at land and the pin was a literal nobody moves, so the two
   drifted the moment a second run landed first — the seed relabelled to `v25`
   while the pin still said `v24`, and the runtime was served a row this run did
   not generate. Two facts that must agree get one writer. The append-only
   comment log above the pin stays the AUTHOR's; the script rewrites the one
   assignment line and passes every other byte through.
8. `-n`: run 1–7 into a temp file, `cmp` against the tree, exit 3 on any
   difference — a seed that should not exist, one that is missing, one whose
   bytes differ, a missing `ORDER` line (a seed `ORDER` does not declare is
   never applied, which is exactly the silent skip this gate exists to catch),
   or a pin that names a version this run did not generate. The independent
   checks are asked INDEPENDENTLY, not as an `elif` chain: an operator hears
   every reason in one run, which is the same rule the source-file validator
   follows. A corrupt `ORDER` is a different answer again and carries
   `EXIT_SCHEMA_ORDER_DRIFT` (9), the status `bin/db-migrate` uses for the same
   corpus; the help text publishes it.

**Routing is EXPLICIT.** The schema directory is a `-d` option defaulting to
`$PROJECT_ROOT/db/schema`; the repository, the source file, the `ORDER` file and
the pin are all derived from it. It is deliberately NOT the inherited
`$DB_SCHEMA_DIR` that `bin/db-migrate` and `bin/db-status` read — `bin/common`
says in as many words that it "is a caller's variable, not a routing channel",
and a stale export in whoever invoked `ship-land` would otherwise make the gate
read and report on ANOTHER checkout while calling the landing tree green without
reading it. `ship-land` and the harness both pass `-d`.

**One whitespace class.** The join rule has three implementations — the
validator, the emitter, and the Kotlin assertion that the shipped row equals the
file. Three implementations only work if they agree on what whitespace IS, and
they did not: a leading U+2000 EN QUAD passed a bash locale trim, was KEPT by
the `LC_ALL=C` awk emitter, and was TRIMMED by Kotlin's `String.trim()` (which
is `Char.isWhitespace()`). The build then failed telling the operator to
"regenerate the seed" while regeneration produced byte-identical output —
unfixable without editing a test. The one class is ASCII whitespace: space, tab,
CR, LF, VT, FF. The validator and the emitter are now one awk `authored_trim()`
used twice rather than two implementations that agree; Kotlin's copy stays
deliberate double entry over the same six characters, pinned by its own test.
Both halves are asserted with a real U+2000.

A CONTROL BYTE is the same trap arriving by another door and is refused, not
accommodated: a bare CR inside a line is an ordinary byte to the `LC_ALL=C`
emitter and a LINE BREAK to Kotlin's `String.lines()`, so the seed would carry
it, the assertion would split on it, and the build would again demand a
regeneration that changes nothing. The validator refuses it on the TRIMMED line,
so an ordinary CRLF checkout is stripped rather than refused.

**No refusal leaves the tree half-written.** Emptiness used to be checked in the
emitter's `END` rule, which runs AFTER the delete loop has removed this run's
seed and its `ORDER` line — so an all-blank source exited 1 having destroyed
exactly the state the refusal existed to protect. Every refusal is now up front,
and everything the script writes is generated into a `mktemp -d` first and moved
into place only once every check has passed. That staging directory lives INSIDE
the schema directory, not in `$TMPDIR`: every install is then a same-filesystem
`rename(2)` rather than a copy-plus-unlink that can fail halfway, and the
script's cleanup trap owns every scratch file it makes — so no `ORDER.tmp` is
ever left beside `ORDER` for a later `git add db/schema` to commit.

**Portability.** `ship-land` invokes `$CODEBASE_ROOT/bin/prompt-seed` directly
and runs OUTSIDE the Nix dev shell, where `/usr/bin/env bash` on macOS is **bash
3.2** — the constraint `.prime/agent/skills/ship/scripts/ship-recover` already
records. So this script must PARSE and RUN under 3.2, or it does not make the
gate lenient, it makes it a syntax error that refuses the very land it exists to
gate. Two consequences: no heredoc inside a `$( )` command substitution (3.2
mis-scans the heredoc body's quotes, and the awk program is nothing but
unbalanced quotes), and `bin/common`'s ORDER scan keeps its name-to-line index
in parallel indexed arrays rather than `local -A`, which 3.2 does not have. A
`/bin/bash -n` assertion on both the script and its harness keeps this from
regressing.

**No migration.** Per D4 this RFC adds no column to `system_prompts`, so it adds
no `ALTER TABLE` and no `ORDER` line for one.

**`ship-land`** — ONE `run_land_gate` helper, called twice. Step 3c was step 3b
with the names swapped: the same eleven lines, the same status-passing rule
implemented twice and explained twice, and a third gate would have been a third
copy. The helper runs a tool and lets its status decide — 3 means "regenerate
and re-commit", anything else non-zero is the tool's own refusal — and passes
the status through rather than collapsing it into `fatal`'s flat 1. The refusal
is written with `log-error`, not `log-info`: it is the one message that STOPS a
land, so it has to carry a severity marker a log scan can find. The
copy-pasteable remedy command stays on `log-info` and unbracketed, because an
`[ERROR]` prefix and brackets both corrupt a command a human pastes.

The `bin/prompt-seed` call is `-b "$BASE_SHA" -d "$CODEBASE_ROOT/db/schema" -n`
and is guarded on the script EXISTING, so a checkout that predates RFC 181 does
not fail a land on a requirement this check invented. Presence and executability
are asked SEPARATELY: a single `[ -x ]` answers "the file lost its exec bit"
with "this checkout predates RFC 181" and so DELETES the land gate in silence,
with every other check still green. A present-but-not-executable
`bin/prompt-seed` is a loud `fatal` instead.

**`.prime/agent/skills/ship/SKILL.md`** — the phase 6 block gains the
`bin/prompt-seed` line and the paragraph explaining the seam.

**`SystemPromptCatalogTest.kt`** — retire per D6, add the new tests. They locate
the repo root by walking up from `user.dir` for `settings.gradle.kts` unless an
existing precedent in the suite does it another way, in which case follow the
precedent.

Because the script now owns the pin, **no test may assert a hardcoded version
literal** — a literal is invalidated by any land-time relabel, and a literal
that has to be edited by the same step that made it wrong guards nothing. What
is asserted instead is the RELATION: the pin equals the catalog's highest coach
version (numerically, since `v9` sorts after `v24` as text), and the pinned body
is byte-identical to its immediate predecessor's, which is the acceptance
criterion "this slice changed no wording" stated in a form that survives
renumbering. `CoachingConfigTest` keeps only the `vNN` SHAPE check; it is a pure
config test with no session, and the real contract lives beside the catalog.

The paragraph index is a closed
`enum class CoachParagraph(val openingWords: String)` rather than a
`List<String>` plus an open `paragraphAt(opener: String)`: thirteen 50-character
literals were each typed twice — once in the index, once at the accessor — and
the drift guard only watched one copy. An opener outside the closed set now
cannot compile. The drift guard is kept and iterates the enum. The single space
that joins two authored lines is named once as `JOIN_SPACE` and applied in
`openerOf`, rather than typed as an invisible leading space inside each literal
that the drift guard then had to trim back off.

**`bin/prompt-seed-scripts-tests`** — a new shell harness registered in
`bin/shell-tests`, alongside `bin/db-scripts-tests`. The `-scripts-tests` suffix
is load-bearing: `bin/scripts-tests` sweeps `bin/*scripts-tests` and asserts
each one is named in the aggregator, and under its first name
(`prompt-seed-tests`) this was the ONLY harness that sweep could not see —
deleting its single line in `bin/shell-tests` would have silently disabled every
assertion and the land gate they cover. It covers the generator's refusals, the
`-n` exit-3 contract, idempotence (running it twice changes nothing), the
delete-and-relabel path against a moved base, and the **merge demonstration**: a
temp git repo where branch A edits one paragraph and branch B another,
`git merge` succeeds with no conflict, and the regenerated body contains both
edits.

**`service.conf`** — the author writes the entry in the append-only per-version
comment log; `bin/prompt-seed` writes the `systemPromptVersion` line itself.

## Files Modified

| File                                                     | Change                                         |
| -------------------------------------------------------- | ---------------------------------------------- |
| `prompts/coach-system-prompt.txt`                        | NEW — the authored body                        |
| `bin/prompt-seed`                                        | NEW — generator, `-b`/`-d`/`-n`                |
| `bin/prompt-seed-scripts-tests`                          | NEW — shell harness incl. merge demonstration  |
| `bin/shell-tests`                                        | register the new harness                       |
| `bin/common`                                             | ORDER membership helpers; bash 3.2 index       |
| `bin/functions`                                          | `strip_git_hook_env`, `EXIT_SEED_WOULD_CHANGE` |
| `bin/db-scripts-tests`, `bin/ship-scripts-tests`         | use `strip_git_hook_env`                       |
| `db/schema/seed-coach-system-prompt-v24.sql`             | NEW — generated, not hand-written              |
| `db/schema/ORDER`                                        | one line                                       |
| `.prime/agent/skills/ship/scripts/ship-land`             | `run_land_gate`; re-check `bin/prompt-seed -n` |
| `.prime/agent/skills/ship/SKILL.md`                      | phase 6 sequence + rationale                   |
| `service/src/main/resources/service.conf`                | log entry (the pin is generated)               |
| `service/src/test/kotlin/.../SystemPromptCatalogTest.kt` | retire D6 set, add the new tests               |
| `service/src/test/kotlin/.../CoachingConfigTest.kt`      | pin SHAPE, not a literal                       |

Not modified, deliberately: `db/schema/0007.create-system-prompts.sql` and every
existing seed `0011`..`0093`; `SystemPromptsDao`; `ConvosDao`; the admin
`SystemPromptsResource`; every prompt word.

## Implementation Plan

1. Extract the v23 body, write `prompts/coach-system-prompt.txt`, and prove the
   round-trip reproduces 25,130 bytes exactly before writing any other code.
2. `bin/prompt-seed` + `bin/prompt-seed-scripts-tests`; iterate with
   `nix develop -c bin/shell-tests`.
3. `bin/prompt-seed` to emit `v24`; its `ORDER` line; `nix develop -c bin/test`
   to apply it. No migration is added (D4).
4. `service.conf` pin, `CoachingConfigTest`.
5. `SystemPromptCatalogTest`: add the new tests first, watch them pass, then
   retire the D6 set. Report the `@Test` count before and after against the
   JUnit XML `tests=` attribute, not the source — 51 before, 41 after.
6. `ship-land` + `SKILL.md`.

## Tests

| Acceptance criterion                                    | Test                                                                                                                                                                                                   |
| ------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Two branches editing different paragraphs merge cleanly | `bin/prompt-seed-scripts-tests` merge demonstration (temp git repo, real `git merge`)                                                                                                                  |
| A stale or hand-edited seed fails the build             | `bin/prompt-seed -n` exit 3 (shell) + `the pinned coach prompt body is exactly the authored source file` (Kotlin, runs under bare `bin/test`) — the sole guarantee, per D4                             |
| Generated seed identical locally and at land            | idempotence test + label-from-`-b` test in `bin/prompt-seed-scripts-tests`                                                                                                                             |
| Regeneration happens after the last rebase              | `bin/prompt-seed -n` refuses a seed generated against a moved base (`bin/prompt-seed-scripts-tests`); `bin/ship-scripts-tests` asserts `ship-land` still wires that call and passes its status through |
| One immutable row per version                           | existing `bin/db-system-prompts-tests` trigger/UNIQUE assertions, unchanged                                                                                                                            |
| FK still pins each request to its row                   | existing `ConvosDaoTest` / `0007` FK, unchanged                                                                                                                                                        |
| Rollback is `COACHING_SYSTEM_PROMPT_VERSION=<previous>` | the nine kept "stays selectable as rollback target" tests                                                                                                                                              |
| Every applied seed untouched                            | `git diff` shows no change under `db/schema/00*.sql`; `ship-order`'s existing refusal to delete a base `ORDER` line                                                                                    |
| admin-web prompt view still works                       | existing `SystemPromptsResourceTest` (10 tests), unchanged                                                                                                                                             |
| No wording changed                                      | the pinned body equals its immediate predecessor's, both labels derived from the pin                                                                                                                   |
