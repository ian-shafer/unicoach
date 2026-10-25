# Landing product docs

Two sessions writing `product/**` in one shared checkout collide in two ways.
`bin/pre-commit` validates the whole working tree rather than the commit, so one
session's unformatted file fails another session's commit — after that commit
may already have spent minutes running tests. And `git add` takes any path it is
handed, so a commit can carry changes the committing run does not own. Both are
one cause: two writers in one tree. So each writer gets its own tree. `$DOCS` is
`.prime/agent/skills/chart/scripts` in the original checkout.

    nix develop -c $DOCS/docs-claim -s <slug>          # from the ROOT checkout
    # ... write product/** in the worktree it prints as CODEBASE_ROOT ...
    nix develop -c git commit -m "docs: ..."           # the hook, in that worktree
    nix develop -c $DOCS/docs-land -s <RUN_SCRATCH>    # prints the landed SHA on stdout

`-s <slug>` is the brief directory for a chart phase (`0011-beyond-money`) or
`rfc-<n>-docs` for a /slice close-out. The branch is `docs/<slug>` and the
worktree is `<repo>-docs-<slug>`. Deliberately NOT `pipeline/rfc-<n>` or
`ship/<slug>`: the first is how `ship-claim` computes the next free RFC number,
so a docs branch parked there would burn a number for a change that has no
design; the second is what `ship-status` lists as live engineering work.

`docs-land` asserts that the branch's net diff against `main` touches nothing
outside `product/`, and that is the whole safety argument: **a branch that can
only land `product/` paths cannot land an untested build input, because
`product/` is not a build input.** It replays onto a base that moved and takes
no land lock — doc traffic is frequent and cheap.

The commit still goes through the hook. A commit whose staged paths are all
under `product/` skips the test half of the gate (RFC 194), so it costs the
format check rather than a full test run — which is what makes a branch per
ledger line affordable. Run `nix develop -c bin/format` before committing so the
check passes first time.

## Adopting mid-flight

A session that already has `product/**` changes sitting in the root checkout is
refused at its next commit, with the recipe in the refusal text. Nothing is lost
— the files stay exactly where they are. Move only what is YOURS, by name:

    $DOCS/docs-claim -s <slug> -a product/0011-beyond-money/spec.md -a product/STATUS.md

`-a` acts on the paths named and on nothing else. Do not reach for a glob or a
`git checkout -- product/`: the root checkout is shared, so acting on the set
you think you own destroys work that is not yours. `docs-claim` names any dirty
`product/` path it finds in the root checkout and leaves it alone, because it
may be another session's.

## What `STATUS.md` must say after a land

Five edits, all of them, every time a slice lands:

- the **TL;DR** — rewritten, most important first, never "see below";
- the **`Updated:` line** — date, what landed, and the next-free RFC number,
  recomputed (`ls rfc`, `pipeline/rfc-*` branches, `git worktree list`), never
  copied;
- the **work table** — this slice's row, and the next slice's honest state;
- the landed feature's **user-manual entry** under "The product today", which
  **names its door**, what it does, how it degrades, and any rollback knob;
- the **wave board** — the landed slice leaves it, and any slice it unblocked
  moves to the ready wave.
