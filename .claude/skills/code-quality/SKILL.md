---
name: code-quality
description: >-
  Teaching loop that raises code quality by codifying knowledge into the
  code-quality infrastructure of the codebase. Takes a single target (file,
  class, method, concept, or cross-cutting concern), distills the preferred
  production-quality pattern with the architect, folds that pattern into the
  codebase's code quality infrastructure by updating or creating skills,
  processes, invariants, etc. It then fixes the one example, and spawns a
  codebase-wide sweep for other places to apply it. Use when a user asks to
  "improve code quality", "codify this pattern", "teach the LLM to write this",
  turn a review nit into a durable rule, or invokes /code-quality.
---

# Code Quality

This loop takes a concrete target, distills the production-quality pattern it
should embody, and **codifies that pattern into the codebase's quality
infrastructure** — so next time the generator writes the preferred code, and the
reviewer catches it when the code is malformed. It then proves the pattern by
fixing the target. The goal is to keep quality high as the codebase grows by
continually **teaching and persisting** what production-quality code looks like.

Runs inline in this session, in a dedicated git worktree branched off `main`.
All git/toolchain commands run via `nix develop -c` per CLAUDE.md.

Persistence comes for free: the review chains discover their lenses by scanning
the `code-review-*` / `design-review-*` skills on disk, so a newly-codified lens
**auto-joins every future review with no extra wiring**.

## Invocation Parameters

- **Target** (required): what to teach from — a file, class, method, domain
  concept, or cross-cutting concern. If the user invoked the skill without a
  target, ask before doing anything else.
- **Principle** (optional): if the user already knows the rule they want
  codified, they may state it; otherwise it is distilled interactively in
  Phase 2.

Whether a target is worth a lesson is the **user's judgment call** — do not
screen or second-guess the request; start immediately.

## Phase 1 — Set up

1. Create an isolated worktree on a fresh branch off `main`, where `<slug>` is a
   short kebab-case summary of the lesson (e.g. `inject-hashers`):

   ```sh
   nix develop -c git worktree add ../unicoach-quality-<slug> -b quality/<slug> main
   ```

   Do all work in that worktree for the rest of the run. The main checkout is
   left untouched, so its working tree does not need to be clean.
2. Read the target and enough surrounding code to understand the current pattern
   and why it exists. Do not judge yet — understand first.

## Phase 2 — Diagnose with the architect (inline)

Work with the user, who wears the **architect** hat, as a real back-and-forth in
this conversation. The output is a crisp, reusable **principle** — the nucleus
of both the codified lesson and the code fix.

1. State the gap: the **current pattern**, the **preferred pattern**, and — most
   importantly — the **why** (what breaks, or what is harder, without it).
2. Distill it into a principle that is **general enough to apply to future
   code**, not over-fit to this one file (echoing `agent-learning`). Capture it
   as **Rule + Why + a before/after snippet**. Do not proceed until it is sharp.
3. **Classify** the principle — this decides where it is codified in Phase 3:
   - **Checkable review lens** — a reviewer could mechanically catch violations
     → a `code-review-*` / `design-review-*` micro-skill.
   - **Generative idiom** — guidance the generator reads while writing, not
     cleanly checkable → a baseline skill (`coding`, `kotlin-coding`,
     `general-design`, etc.).
   - **Durable guarantee (invariant)** — a rule that must stay true as the code
     evolves. Invariants are **human-gated** and originate at RFC design time
     (see CLAUDE.md), so this loop does not write `INVARIANTS.md` directly:
     propose the invariant to the user and route it through `/rfc-pipeline` so
     it lands with the human review it requires.

## Phase 3 — Codify (update-first)

**Prefer to update existing infrastructure over creating new.** Before writing
anything new, find the closest existing home for the principle and sharpen _it_:

1. **Search first.** Look across the quality infrastructure for something that
   already owns this concern — an existing review lens (`code-review-*` /
   `design-review-*`), a baseline guidance skill (`coding`, `kotlin-coding`,
   `general-design`), a process/orchestration skill, or a directory's
   `INVARIANTS.md`. Grep the skills for adjacent language.
2. **Update when one fits.** Extend the existing artifact: tighten its criteria,
   add the new ❌/✅ example, or broaden its scope. One principle per skill —
   keep the SRP; do not bundle unrelated rules into an existing lens just
   because it is nearby.
3. **Create only when nothing fits.** Add a new `code-review-<name>` micro-skill
   in the **house format** — copy a sibling (e.g. `code-review-constructor-di`)
   exactly:
   - frontmatter: `name`, `description`, `implementation_summary`;
   - an adversarial reviewer body scoped to this **one** principle;
   - `## 🎯 Code Examples` with paired ❌ / ✅ snippets;
   - the standard `## 📋 Output Format` verdict block. A new `code-review-*`
     skill auto-joins the review chain; a new `design-review-*` skill auto-joins
     the design chain. No other wiring is needed.
4. **Recommended:** draw the codified skill's ✅ / positive example from the
   **actual fix** you apply in Phase 4, so the lesson and its example stay in
   sync. Not required — but a good default.

## Phase 4 — Fix the example (target only)

1. Apply the pattern to the **named target only**. Do not sweep siblings here —
   the codebase-wide application is Phase 6.
2. Honor the baseline skills (`coding`, `kotlin-coding`, `general-design`,
   `shell-scripts` — whichever apply) and every touched directory's
   **INVARIANTS.md**. Do not edit `INVARIANTS.md` — it is human-gated; if the
   fix would affect an invariant, tell the user.
3. Run the suite through the harness: `nix develop -c bin/test` — the whole
   thing, unscoped, per CLAUDE.md. Gradle executes what the change invalidated
   and no-ops the rest, which is both faster than it sounds and wider than any
   module you would have named. For non-Kotlin targets, run whatever check
   exercises them (`deno fmt --check`, the script's own tests, etc.).

## Phase 5 — Verify & land

1. **Consistency check.** Invoke the new/updated lens against the fix (the
   specific `code-review-*` skill on the diff). It MUST pass. If the lens flags
   its own demonstration, the lesson and the example disagree — reconcile before
   landing.
2. Present to the user in one message: the changed files, the distilled
   **principle**, **which skill/artifact was created or updated**, the
   consistency-check outcome, and the two proposed commit messages (imperative
   subject ≤ 50 chars, wrapped body — per `git-commits`). **Wait for approval**
   — this is the single confirmation gate. The user may pre-authorize ("land
   it") in the invocation.
3. Land as **two adjacent commits** through the full gate (no `--no-verify`; the
   checkpoint carve-out does not apply here), the lesson before its
   demonstration — mirroring `agent-learning`'s isolated-commit precedent:

   ```sh
   # 1. the lesson — the codified skill / infrastructure change
   nix develop -c git add <skill files>
   nix develop -c git commit ...
   # 2. the demonstration — the code fix
   nix develop -c git add <code files>
   nix develop -c git commit ...
   ```

4. Fast-forward the commits onto `main` and tear the worktree down:

   ```sh
   nix develop -c git merge --ff-only quality/<slug>   # from the main checkout
   nix develop -c git worktree remove ../unicoach-quality-<slug>
   nix develop -c git branch -d quality/<slug>
   ```

   If `main` advanced and the fast-forward fails, rebase the branch onto `main`
   in the worktree, re-run the affected tests, and retry. If the rebase
   conflicts, stop and report — do not force anything.

## Phase 6 — Seed the codebase-wide sweep

The lesson now protects **future** code (via the review chains) and demonstrates
the pattern **once**. To realize it across **existing** code, spawn a separate,
self-contained task — do not grow this diff:

1. Use `spawn_task` to file a codebase-wide application of the lens. The prompt
   must stand alone: name the codified skill, state its **Rule + Why**, and
   instruct the spawned session to find other instances of the same violation,
   fix the mechanical ones in small reviewed batches (each through
   `/quick-change` or the normal gate), and flag the risky ones rather than
   forcing them.
2. Report the landed commit hashes, the codified skill, and the spawned sweep
   task id.

## Proactive use (future)

This loop is the unit the proactive quality strategy is built from: an agent
that watches diffs — or periodically sweeps the tree — for recurring violations
that **lack** a codified lens can file each as a `/code-quality` **Target**,
feeding this same loop automatically. Keep the loop cheap and inline so it is
worth running often.
