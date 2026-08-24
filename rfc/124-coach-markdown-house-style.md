# RFC 124: A Markdown house style for the coach

## Summary

Nothing in the coach's system prompt has ever mentioned Markdown. The seeded
`coach`/`v1` body (`db/schema/0011.seed-coach-system-prompt.sql`) says only "Be
concise and concrete. Ask at most one focused question per reply." Every table,
heading and bold run the student sees today is Claude's unprompted house style —
formatting we neither asked for nor tuned, arriving in a client that until RFC
118 rendered it as literal asterisks and pipes.

RFC 118 and RFC 120 made the iOS client render Markdown properly. This RFC
closes the loop on the other side: a new `coach`/`v2` prompt row that states, in
one paragraph, what formatting we want and which constructs the client cannot
render — and the config flip that activates it.

No code changes. One migration, one config value, and the tests that pin it.

## Why now

Two facts make the current situation a latent bug rather than a neutral default.

**The renderer has a shape, and the model cannot see it.** After RFC 118 and 120
the app renders headings (capped at `dsTitle`), bold/italic/strikethrough,
inline and fenced code, bullet/ordered/task lists with nesting, block quotes,
rules, links, and GFM tables — as a grid when the columns fit and as one block
per row when they do not. It does **not** render images, raw HTML, or footnotes:
those degrade to their literal source text in the bubble. An unsteered model
will eventually emit one of them.

**Wide tables degrade quietly.** RFC 120's stacking is the honest fallback, not
the good outcome: a six-column comparison becomes six stacked label/value blocks
per row and swamps the thread. The renderer cannot make a wide table narrow;
only the author can.

## Detailed Design

### The new prompt body

`coach`/`v2` is `coach`/`v1` **verbatim**, plus one appended paragraph. Keeping
the existing sentences byte-identical is deliberate: formatting is then the only
variable that changed, so any difference in how the coach reads is attributable
to this RFC and nothing else.

The appended paragraph, in reading form (the migration stores it as a single
untrimmed line, per the seed convention):

> Your replies are rendered as Markdown in the student's app; use it to keep
> them scannable. Never write a wall of text: hold paragraphs to two or three
> sentences, break the reply where the thought breaks, and put genuinely
> enumerable content — a set of options, a sequence of steps, a short comparison
> — in a bullet or ordered list rather than a run-on sentence. Equally, do not
> dress up a one-line answer: a short factual reply is a sentence, not a list of
> one. Nest lists at most one level deep; use bold for the occasional term or
> label that lets the eye land, not for every noun; add a heading only to
> separate the sections of a genuinely long reply. Use a table only to compare a
> few things along the same few dimensions — keep it to three columns and short
> cells, and use a list instead when it would need more, because a wide table is
> hard to read on a phone. Write links as [text](url). Never use images, raw
> HTML, or footnotes: they are not rendered, and the student sees the raw source
> instead.

Four things it does, in the order they matter:

1. **Makes scannability the goal, and names both ways to miss it.** The
   requirement is a reply the student's eye can move through on a phone. There
   are two failure modes, not one, and the paragraph rules out each explicitly:
   a **wall of text** — a dense multi-sentence block with no break and no list
   where the content plainly enumerates — and its opposite, **ceremony around
   nothing** — a three-word answer served as a bulleted list or a table. The
   short paragraph cap and the "break where the thought breaks" clause carry the
   first; the "not a list of one" clause carries the second. Structure is not
   rationed, it is matched to content: where there really are four options, four
   bullets is the readable form and one long sentence is not.
2. **Caps table width at three columns.** This is the one hard number in the
   paragraph, because it is the one place the renderer has a cliff (RFC 120's
   stacking) and the model has no way to know where the cliff is.
3. **Names the unrenderable constructs.** Images, raw HTML, footnotes. Stated as
   a prohibition rather than a preference, because the failure is visible
   garbage in the bubble, not a matter of taste.
4. **Leaves everything else alone.** Nesting depth, code blocks, quotes and
   rules all render; they get a light touch or no mention rather than a rule
   each. A prompt that enumerates the renderer is a prompt that goes stale the
   next time the renderer changes.

### Activation is a config change, not just a migration

The runtime resolves the prompt by `(name, version)` from
`coaching.systemPromptName` / `coaching.systemPromptVersion`
(`CoachingService.kt:759` → `SystemPromptsDao.findByNameAndVersion`), never
"latest". Inserting the row changes nothing on its own. `service.conf` moves
`systemPromptVersion` from `"v1"` to `"v2"`, matching how `extraction`,
`synthesis` and the fit-lens prompts were already moved to their v2 rows by
RFC 104.

`system_prompts` is an immutable entity table (RFC 33, `db/schema/0007`) and
`0011` is not touched. The v1 row stays in the catalog, which is what makes the
rollback below a one-line env change.

### Rollback

`COACHING_SYSTEM_PROMPT_VERSION=v1` and a restart. No migration to reverse, no
data to repair — the immutable catalog gives us this for free, and it is the
main reason this change is cheap to make and cheap to unmake.

## How this will be judged

Stated plainly because it is the weakest part of the RFC: **there is no eval
harness for prompt changes in this repo, and this RFC does not build one.**
Building one — a fixed student-prompt corpus, a rubric, a judge model, a score
to regress against — is a real project, and pinning a house style is not enough
reason to start it.

So the honest bar is a human read, made as concrete as it can be without a
harness:

- **Before/after, same inputs.** Six representative student turns (a one-line
  factual question; an open "where should I even start"; a "compare these three
  schools"; a deadline-planning turn; an emotional turn; a follow-up
  mid-thread). Run each against `v1` and `v2` — the env override makes this a
  restart, not a build — and read the pairs side by side in the iOS simulator.
- **What would count as a failure.** A `v2` reply that is _less_ scannable than
  its `v1` twin — a wall of text, or enumerable content buried in a run-on
  sentence — a one-line answer dressed up as a list, a table still wider than
  three columns, or any unrenderable construct surviving. Any of those and we do
  not flip the config; the row stays in the catalog unused, which costs nothing.
- **What the automated suite covers, and what it does not.** `bin/test` proves
  the migration applies, the config resolves, and the service finds the row. It
  says nothing about whether the coach reads better. Nobody should quote a green
  suite as evidence for the copy.

The claim this RFC makes is therefore narrow and defensible: **the coach's
formatting becomes a decision we own rather than a default we inherited, and
constructs the client cannot render are ruled out.** "It reads better" is a
judgement, and the person making it is Ian, once, on the six pairs above.

## Files Modified

| File                                                      | Change                                                                                                                                                                              |
| --------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `db/schema/0044.seed-coach-system-prompt-v2.sql`          | **New.** Inserts `('coach','v2')` — v1's body verbatim plus the paragraph.                                                                                                          |
| `service/src/main/resources/service.conf`                 | `systemPromptVersion` `"v1"` → `"v2"`, with the rollback noted inline.                                                                                                              |
| `service/src/test/kotlin/.../CoachingConfigTest.kt`       | Packaged-defaults assertion `"v1"` → `"v2"`.                                                                                                                                        |
| `service/src/test/kotlin/.../CoachingServiceTest.kt`      | Asserts against the pinned version: `coachV1PromptId` → `coachPromptId` (queries `config.systemPromptVersion`), and the expected system text moves to a `pinnedCoachBody` constant. |
| 13 further test fixtures (`db`, `service`, `rest-server`) | Restore `coach/v2` alongside `coach/v1` after truncating `system_prompts` — see below.                                                                                              |
| `rfc/124-coach-markdown-house-style.md`                   | This RFC.                                                                                                                                                                           |

### The fixture defect this uncovered

Flipping the pinned version broke 24 `rest-server` tests that this change never
touched, and the reason is worth recording because it is a live trap for the
next version bump of any prompt family.

`system_prompts` is migration-seeded, but the modules share one test database
and a dozen test classes `TRUNCATE ... system_prompts CASCADE` in `@BeforeEach`.
Most of them restore the seeds afterwards — **restoring `v1` only**, hard-coded.
So the runtime's pinned row survived a cross-module run by coincidence: `v1`
happened to be what both the fixtures wrote and the config asked for. Move the
pin and the coincidence evaporates, in a module whose own code did not change.

Two classes — `ConvosDaoTest` and `FitLensRunsDaoTest` — truncated the catalog
and restored **nothing**, which is the sharper version of the same bug. They now
leave `system_prompts` alone entirely: they need their own rows, not a clean
catalog, and destroying another module's fixtures to get them was never the
intent. Their fixture versions become unique per row (a per-instance counter
collided once the table stopped being cleared).

Everywhere else the restore block gains the `coach/v2` row next to `coach/v1`.
This is a floor, not a fix for the class of bug: the same trap is armed for
`extraction`, `synthesis`, and the fit-lens prompts, whose fixtures also restore
`v1` while `service.conf` pins `v2` — they survive today only because no
cross-module test resolves them. See **Open items**.

## Implementation Plan

1. Write `db/schema/0044.seed-coach-system-prompt-v2.sql` in the house form of
   `0011`/`0026`/`0037`: a header comment naming this RFC and stating the body
   is v1 verbatim plus the formatting paragraph, then one `INSERT` whose body is
   a `||`-concatenated single line with `''`-escaped apostrophes.
2. Flip `systemPromptVersion` in `service.conf`.
3. Update `CoachingConfigTest` and the `OfflineCoachingE2eTest` self-heal.
4. `nix develop -c bin/test` — the migration runs as part of the harness's
   `db-reset`, so a malformed seed fails loudly rather than at deploy.
5. Diff the v2 body against the v1 body programmatically (not by eye) to confirm
   the shared prefix is byte-identical.

## Tests

Automated, all existing mechanisms — no new test file:

- **Migration applies.** `bin/test` re-inits and migrates the test DB every run;
  a syntax error or a `(name, version)` collision fails the harness.
- **Config resolves to v2.** `CoachingConfigTest` asserts `"v2"` from the
  packaged defaults.
- **The service resolves the pinned row.** `CoachingServiceTest` and
  `OfflineCoachingE2eTest` drive the real
  `SystemPromptsDao.findByNameAndVersion` path against the migrated DB; a pin
  with no matching row fails them, as it did before the fixtures were fixed.
- **The v1 prefix is byte-identical.** Checked directly against the migrated
  catalog rather than by eye: `SELECT (v2 body) LIKE (v1 body) || ' %'` → true
  (470 → 1480 chars).
- **Immutability holds.** `0011` is untouched and the v1 row stays resolvable.

Not automated, by explicit decision: whether the copy improves the replies. See
**How this will be judged**.

## Open items

- **The other prompt families carry the same fixture trap.** `extraction`,
  `synthesis`, `fit_lens_query` and `fit_lens_reason` are pinned at `v2` in
  `service.conf` while the test fixtures restore only their `v1` rows. Nothing
  resolves them across modules today, so nothing fails — but the next family
  whose consumer moves module will fail exactly the way `coach` just did. The
  durable fix is a single shared restore helper that seeds what the config pins;
  this RFC deliberately did not build it.
- **The before/after read has not happened yet.** The config flip lands
  activated. If the six pairs disappoint, the rollback is
  `COACHING_SYSTEM_PROMPT_VERSION=v1`, and a `v3` row carries any revision.
