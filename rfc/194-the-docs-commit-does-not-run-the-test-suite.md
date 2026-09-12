# RFC 194: The docs commit does not run the test suite

## Summary

`bin/pre-commit` runs two gates concurrently: `bin/format -c` (~8s) and
`bin/test check` (2–4 minutes). When **every staged path is under
`product/**`**, run `bin/format -c` only and skip `bin/test check`. Anything
else — one code path, a mixed set, an empty index, a rename that crosses the
`product/**` boundary — runs the full gate, unchanged.

## Motivation

`product/**` holds the `/chart` product layer: briefs, specs, research reports
and `product/STATUS.md`. Nothing under it is a Gradle input, a resource, a
migration, or a script. The test half of the gate therefore cannot change its
answer for a commit that touches only those files; it is pure waste.

The waste is not theoretical. On 2026-09-11 three concurrent sessions lost
roughly 16 minutes of gate time to doc-commit contention: run rfc-187 lost the
land race four times, beaten each time by a docs-only commit (`33c7abf8`,
`50e44f8d`, `0403f784`). Each of those commits held the tree for 2–4 minutes to
prove something about Kotlin that it had not touched.

Measured, 2026-09-11:

| gate             | cost             | what it covers                                      |
| ---------------- | ---------------- | --------------------------------------------------- |
| `bin/format -c`  | 8.3s, 1169 files | ktlint AND `deno fmt --check` on Markdown           |
| `bin/test check` | 2–4 min          | Gradle + Postgres + installDist + `bin/shell-tests` |

`bin/format -c` keeps running in every case. It is the only Markdown gate in the
repo, and 8 seconds is not worth an exemption.

## Two exemptions, two claims

A concurrent run adds a second gate exemption to `ship-land` (RFC 193). The two
look alike and are not:

- **`ship-land`'s is a REPEAT exemption** (RFC 193, landing concurrently). It
  declines to RE-RUN a check that has ALREADY PASSED on that exact tree. Its
  claim is about history: this work was verified.
- **THIS one is a FIRST-RUN exemption.** It asserts the check never needs to run
  at all for these paths, because they are not build inputs. Its claim is about
  the dependency graph: there is nothing here for the check to observe.

### Same intent, two matchers — and the mechanism must match the matcher

The two runs also express "is this path under a docs root?" with two DIFFERENT
mechanisms, and a later reader comparing the files will be tempted to "fix" one
of them into a bug. They are both right, in opposite directions:

- **Here it is a shell `case` arm, `product/*`.** In a `case` pattern `*`
  matches `/`, so the arm covers nested paths:
  `product/0009-say-it-once/research/user-value.md` is docs-only. The hazard a
  `case` arm avoids is the one a **pathspec** would create.
- **In `ship-land` it is an explicit PREFIX test**, because that predicate runs
  through git's pathspec machinery, where a single-level glob does NOT descend
  into subdirectories — a `product/*` pathspec there would quietly miss every
  nested file and classify a genuine docs commit as code.

Same intent; two matchers with opposite globbing rules. The mechanism has to
match the matcher it runs through, and neither spelling is portable to the other
site.

Both are sound, for different reasons. They happen to share the shape "a glob
decides", and that is the whole of their kinship. This RFC deliberately does
**not** factor a shared path predicate between them: a shared helper would imply
the two questions are one question, and a later change to either claim would
silently move the other. If they ever genuinely converge, that should be a
deliberate finding in a later RFC, not an accident of reuse today.

## Detailed Design

### The predicate

A new function in `bin/functions`:

```sh
staged_changes_are_product_docs_only() {   # <repo-root> -> 0 = yes, 1 = no
  local repo="$1" path found=0
  while IFS= read -r -d '' path; do
    found=1
    case "$path" in
      product/*) ;;
      *) return 1 ;;
    esac
  done < <(git -C "$repo" diff --cached --name-only -z --no-renames)
  [ "$found" -eq 1 ]
}
```

Four properties, each deliberate:

1. **It reads the INDEX, not the working tree.** `git diff --cached` is what the
   commit will contain. A dirty working tree is irrelevant to what lands.
2. **`--no-renames` is what makes renames and copies safe.** With rename
   detection ON, `--name-only` prints only the DESTINATION, so a rename out of
   `product/**` into `bin/` would be classified by its `bin/` side alone — and a
   rename INTO `product/**` would look docs-only while deleting a code file.
   `--no-renames` decomposes every rename into its delete and its add, so BOTH
   paths are classified. Deletions are listed by `--name-only` already. This is
   git's own porcelain answering the question, not a string test over a diff.
3. **`-z` and `read -d ''`** because a pathname may contain a newline, and the
   default `--name-only` output would quote and escape it into something the
   glob no longer matches.
4. **It fails safe in every direction.** An empty index yields `found=0` → not
   docs-only → full gate. A `git` failure (unborn HEAD, a broken index) yields
   no output, which is the same empty case → full gate. The `case` has one
   accepting arm and a catch-all reject arm, so a path the predicate cannot
   place — including a regular FILE named `product` at the root — runs the full
   gate. The default answer is always "run everything".

### The branch in `bin/pre-commit`

`bin/format -c` is started in both paths, exactly as today. `bin/test check` is
started only on the full path. The classification is logged to stderr, by name,
before either gate starts:

```
gate scope: product-docs-only — every staged path is under product/**; running
bin/format -c only, skipping bin/test check (product/** is not a build input).
```

versus

```
gate scope: full — running bin/format -c and bin/test check concurrently.
```

The log line is not decoration. A commit that skipped the test half and one that
ran it must be distinguishable afterwards from the hook output alone — the same
reason RFC 183 logs its withholding counters rather than silently withholding.

### What the docs-only path does NOT do

**It does not record the `ship-verified` marker.** The marker means "the full
pre-commit gate passed on this tree", and recording it after skipping the tests
would put a lie in the one file that stops unverified code landing. A marker
that can lie is worse than no marker.

The failure this accepts is the safe one: a `/ship` run whose branch TIP were a
product-docs-only commit would be refused at land, and one re-commit fixes it.
No current workflow produces such a tip. `/ship`'s branch tip at land time is
the `rfc:` doc commit, whose path is `rfc/**` — never docs-only under this
predicate — sitting on the code commit that went through the full hook; and the
`/chart` and `/slice` ledger commits are made on `main` after the land, not as a
branch tip.

The contract-fuzz block is untouched: it triggers on `api-specs/openapi.yaml`
being staged, and a commit containing that path is not docs-only by definition.

## Known limitations — what this does NOT fix

**The hook validates the TREE, not the COMMIT.** `bin/format -c` checks the
whole working tree, not the staged set, so an unformatted file that is unstaged,
uncommitted and belongs to ANOTHER session fails YOUR commit. This happened for
real on 2026-09-11: a session's commit died on a Markdown file that was not in
its commit and not its own — after `bin/test check` had already passed, so four
minutes of gate were spent and thrown away.

This RFC does not close that. The fast path keeps `bin/format -c`, and
`bin/format -c` is still whole-tree. After this change, "my commit failed
because of someone else's unstaged file" costs **8 seconds to discover instead
of four minutes** — a large improvement in the COST of the failure and NO
improvement in its FREQUENCY. A reader who sees "docs commits are now fast" must
not conclude that docs commits are now reliable.

Fixing it is a separate question with a real argument on both sides: a tree-wide
check catches what you are about to commit next, while a staged-only check is
what "this commit is clean" actually means. It is deliberately not decided here.

### Three hazards of parallel work, two of them closed

1. **A doc-only land re-runs a gate that already passed on that tree**, and
   serialises behind the land lock to do it — closed by RFC 193.
2. **A doc commit pays a gate it cannot affect** — closed here.
3. **The hook validates the tree, not the commit** — OPEN. Cheaper to hit after
   this RFC, no rarer.

## Files Modified

- `bin/functions` — add `staged_changes_are_product_docs_only()`.
- `bin/pre-commit` — classify the staged set, log the classification, run
  `bin/test check` only on the full path, skip the `ship-verified` record on the
  docs-only path; update the `-h` help text.
- `bin/scripts-tests` — a new section driving the predicate over a throwaway git
  repository.
- `CLAUDE.md` — document the exemption where the gate rule is stated, and
  distinguish its justification from the `--no-verify` RFC-doc commit's.

## Implementation Plan

1. Add the predicate to `bin/functions`.
2. Branch `bin/pre-commit` on it; log the classification to stderr.
3. Extend `bin/scripts-tests`.
4. Update `CLAUDE.md`.
5. `nix develop -c bin/shell-tests`, then the full `nix develop -c bin/test`.

## Tests

`bin/scripts-tests` (the harness that already covers `bin/pre-commit`'s nix
guard). The predicate is a pure function of one repository's index, so the tests
build a throwaway repository under `mktemp -d`, stage a set, and assert the
classification — no cluster, no Gradle, no daemons, well under a second.

Cases, each an `assert_success`/`assert_failure`:

| staged set                                       | expected  |
| ------------------------------------------------ | --------- |
| a `product/**` file modified                     | docs-only |
| a DEEPLY nested `product/**` file                | docs-only |
| only a code file                                 | full gate |
| MIXED: `product/**` + a code file                | full gate |
| EMPTY index                                      | full gate |
| `git mv product/…/brief.md bin/brief.md`         | full gate |
| `git mv bin/thing product/…/thing`               | full gate |
| `git rm` under `product/**`                      | docs-only |
| `git rm` of a code file                          | full gate |
| staged code file + an UNSTAGED `product/**` edit | full gate |
| root paths `product-notes`, `productx`           | full gate |
| a `product/**` pathname containing a NEWLINE     | docs-only |

The two `git mv` rows are the ones that pass under a naive `--name-only` with
rename detection left on. MIXED and EMPTY are the fail-safe defaults; the
prefixed-root row is the catch-all arm. The newline row is the one whose WRONG
answer is also a safe answer — read line-wise the path splits and the run falls
back to the full gate — which is exactly why it needs an assertion rather than
trust.

Two more assertions guard the contract rather than the classification: the
predicate writes nothing to stdout (its answer is its exit status), and
`bin/pre-commit` names BOTH gate scopes in its logging.

The fixture calls `strip_git_hook_env` around every git call and around the
predicate. This harness can run INSIDE a git hook (`git commit` →
`bin/pre-commit` → `bin/test check` → `bin/shell-tests` → here), where
`GIT_DIR`/`GIT_INDEX_FILE` are exported and outrank `git -C`; without the strip
every case would classify THIS repository's index and pass for the wrong reason.
The production caller deliberately does not strip: under `git commit` the
exported index IS the set being committed.
