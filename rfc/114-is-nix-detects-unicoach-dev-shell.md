# RFC 114: `bin/is-nix` detects unicoach's dev shell, not any Nix shell

## Executive Summary

`bin/is-nix` decides "are we inside the dev shell" from `IN_NIX_SHELL` being
non-empty. Nix sets that variable for **any** flake's shell, so the predicate
answers a broader question than the one every caller asks. An agent or tool
running inside some other project's `nix develop` gets a false positive: the iOS
scripts (`build-ios`, `install-ios`, `release-ios`) refuse to run under a shell
that never shadowed `xcrun`, and `bin/pre-commit` concludes the unicoach
toolchain is present when no JDK, Postgres, or ktlint is on `PATH`.

Measured on the current tree — a throwaway flake with an empty `mkShell`:

    unicoach : UNICOACH_DEV_SHELL=[1]  IN_NIX_SHELL=[impure]
    other    : UNICOACH_DEV_SHELL=[]   IN_NIX_SHELL=[impure]

`IN_NIX_SHELL` cannot separate those two columns; nothing keyed on it can.

This RFC makes the predicate specific by having unicoach's own `devShell`
declare a marker — `UNICOACH_DEV_SHELL=1`, set as a `mkShell` attribute in
`flake.nix` — and having `is-nix` key on that marker instead. The signal becomes
one this repo owns rather than one Nix hands out to everybody.

The exit-code contract is unchanged (`0` inside, `1` outside, `-q`/`-v` as
before), so all four callers are untouched. The stderr status line is sharpened
to name what was actually detected, including a distinct third message for the
case this RFC exists to fix: **a** Nix shell is active, but not unicoach's.

This supersedes RFC 51's "Detection signal: `IN_NIX_SHELL` non-empty" decision.
RFC 51's reasoning is left standing as committed; the change is carried here.

## Detailed Design

### The marker

`flake.nix` gains one attribute on `devShells.default`:

```nix
devShells.default = pkgs.mkShell {
  # Identifies THIS flake's shell. bin/is-nix keys on this rather than on
  # IN_NIX_SHELL, which nix sets for any flake's shell and so cannot tell
  # unicoach's toolchain from an unrelated project's.
  UNICOACH_DEV_SHELL = "1";

  packages = [ ... ];
```

An unrecognised `mkShell` attribute becomes an environment variable in the
shell's derivation environment, so it is exported by both `nix develop` and
`nix develop -c <cmd>`. This was verified against the real flake before writing
this RFC (the measurement above), not assumed.

Declaring it as an attribute rather than an `export` in `shellHook` keeps it
declarative and keeps it out of the diagnostic banner's business. The banner
stays on stderr and stays as-is.

### Why a repo-owned marker, and not the alternatives

RFC 51 considered and rejected two signals. Neither objection applies here:

- **`DEVELOPER_DIR`** — rejected as a _consequence_ of the shell and a
  legitimate system mechanism, so false-positive-prone. `UNICOACH_DEV_SHELL` is
  not a consequence of anything; it exists only because this flake declares it,
  and nothing else in the ecosystem sets it.
- **Matching `/nix/store` inside a path, or inspecting `nativeBuildInputs`** —
  rejected as a heuristic coupled to the flake's current package set. The marker
  is coupled to the flake's _identity_, not its contents: adding or dropping a
  package never touches it.

RFC 51's stated virtue of `IN_NIX_SHELL` — "trivially simulable in tests by
exporting one variable" — is preserved exactly. The tests still simulate by
exporting one variable; only its name changes.

### The predicate

`is-nix` reads `UNICOACH_DEV_SHELL` where it read `IN_NIX_SHELL`, and gains a
diagnostic branch. It still sources only `bin/functions`, still reports through
the exit code, still prints to stderr via `log-info`.

| `UNICOACH_DEV_SHELL` | `IN_NIX_SHELL` | exit | status line                                                       |
| -------------------- | -------------- | ---- | ----------------------------------------------------------------- |
| non-empty            | any            | `0`  | `unicoach dev shell active`                                       |
| empty                | non-empty      | `1`  | `unicoach dev shell NOT active (a different Nix shell is active)` |
| empty                | empty          | `1`  | `unicoach dev shell NOT active (no Nix shell)`                    |

`IN_NIX_SHELL` survives **only** in the middle row, and only to word the
message. It is never load-bearing for the exit code — the whole point is that it
cannot be. That row is the payload of this RFC: an operator who hits the bug now
reads why, instead of a bare "nix NOT enabled" from inside a visibly active Nix
shell.

Every line names the state that decided it, and **no line is a prefix of
another** — hence `(no Nix shell)` on row 3, which would otherwise be a prefix
of row 2 and make a substring assertion pass on either. The states are disjoint
by construction rather than by a test-side matching discipline; tests still use
whole-line matches, but correctness no longer depends on their doing so.

Row 1 is tested unconditionally first because in a real `nix develop` shell
**both** variables are set. If the order were reversed, every developer in the
actual dev shell would be told a foreign shell was active. That precedence is
pinned by its own test rather than left to reading order.

`-q` suppresses all three lines. `-v` still lists packages parsed from
`nativeBuildInputs`, still only in the active case; that parsing is a display
concern and stays untouched.

### Blast radius on callers

All four call sites use `is-nix -q` and branch on the exit code, so none change:

- `bin/build-ios`, `bin/install-ios`, `bin/release-ios` — refuse **inside**;
  they now refuse only inside unicoach's shell, which is the boundary they
  actually care about (a Nix-shadowed `xcrun`/`xcodebuild` from this flake).
- `bin/pre-commit` — requires **inside**; it now demands the shell that actually
  supplies Gradle's JVM, Postgres, and ktlint.

### Migration

A developer sitting in a shell entered **before** this lands has no marker, so
`bin/pre-commit` will refuse the next commit and tell them to use the dev shell.
Exiting and re-entering fixes it. `nix develop -c …` re-evaluates the flake per
invocation and needs nothing. This is a one-time, self-describing, self-healing
cost, and it is the correct failure direction: a stale shell predates the
guarantee the marker asserts.

### Known limitation (accepted)

A foreign flake's shell entered **from inside** unicoach's shell inherits the
exported marker, so the predicate still reads active. Detecting that would need
the marker tied to the live `PATH`, which reintroduces exactly the package-set
coupling RFC 51 rejected. The reported failure — an agent in an unrelated flake
with no unicoach shell anywhere — is fixed; nesting is out of scope.

## Files Modified

| File                    | Change                                                                                                                                                      |
| ----------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `flake.nix`             | Add `UNICOACH_DEV_SHELL = "1";` to `devShells.default`, with the comment explaining why `IN_NIX_SHELL` is insufficient.                                     |
| `bin/is-nix`            | Key on `UNICOACH_DEV_SHELL`; add the third diagnostic branch; update the header comment and `help()` text.                                                  |
| `bin/ios-scripts-tests` | Simulate via `UNICOACH_DEV_SHELL`; add two cases (foreign-shell false positive, and its distinct message).                                                  |
| `bin/scripts-tests`     | Comment refresh only; the two `is-nix` cases assert unchanged behaviour and must keep passing verbatim.                                                     |
| `bin/INVARIANTS.md`     | The two `is-nix` mentions describe it as the dev-shell predicate; reword to "unicoach dev shell". No rule changes.                                          |
| `bin/pre-commit`        | Drop `-q` from the guard so the operator sees the specific diagnosis; name the shell "unicoach" in the fatal message. Guard logic and exit codes unchanged. |

`bin/pre-commit` drops `-q` (one confirming line on the happy path, the
diagnosis on the failing one) because it is the one caller that must _report_
the distinction rather than merely branch on it: it is the surface an operator
hits while believing they are in a dev shell. The other three callers refuse
_inside_ the shell and print their own message, so the new diagnostic would be
noise there.

Not modified: `bin/build-ios`, `bin/install-ios`, `bin/release-ios` (exit-code
contract unchanged), `ios-app/DEPLOY.md` (prose about running outside the dev
shell, still true), `rfc/51-*.md` (immutable).

## Implementation Plan

1. Add the marker attribute to `flake.nix`.
2. Rewrite the `is-nix` decision block as the three-way branch; update its
   header comment and `help()` so the documented signal matches the code.
3. Update `bin/ios-scripts-tests`: rename the `IN_NIX` plumbing to carry
   `UNICOACH_DEV_SHELL` (the startup `unset`, the three `run_*` wrappers, and
   `run_is_nix`), fix the `-v` case's inline env, and add the two new cases.
4. Refresh the `bin/scripts-tests` comments; leave its assertions alone.
5. Reword the two `bin/INVARIANTS.md` mentions.
6. Drop `-q` from `bin/pre-commit`'s guard so the diagnosis reaches the
   operator.
7. `nix develop -c bin/test`, plus `bin/ios-scripts-tests` under system Xcode.

## Tests

Existing coverage — 9 references in `bin/scripts-tests`, 13 in
`bin/ios-scripts-tests` — is retargeted, not replaced. `bin/scripts-tests`
asserts `is-nix -qv` parses and `is-nix -q` exits `0` inside the dev shell
(which, run under `bin/test`, is now the _unicoach_ shell — a strictly stronger
claim on the same line), and that a stray argument still exits
`EXIT_UNEXPECTED_ARG`, distinct from the operational `0`/`1`.

In `bin/ios-scripts-tests`, the six `is-nix` cases and three dev-shell-guard
cases keep their shape with the marker as the simulated variable:

- `-q` with the marker → exit `0`, no output; without → exit `1`, no output.
- default with the marker → exit `0`, stderr matches
  `unicoach dev shell active`.
- default without, and with `IN_NIX_SHELL` unset → exit `1`, stderr is exactly
  `unicoach dev shell NOT active (no Nix shell)`.
- **both** the marker and `IN_NIX_SHELL` set — the real dev shell — → exit `0`,
  `unicoach dev shell active`. Pins the branch precedence above; without it the
  one state every developer is actually in is the one state untested.
- `-v` with the marker and a fixture `nativeBuildInputs` → package names listed
  with the `<hash>-` stripped.
- unknown option → `unknown option` message, non-zero.
- `build-ios simulator` / `build-ios <missing-env>` / `install-ios device` with
  the marker → refuse, naming system Xcode, before any tool is invoked.

Two cases are **new**, and are the regression test for this RFC:

- `IN_NIX_SHELL=impure` with the marker **unset** → `is-nix -q` exits `1`. Under
  the old predicate this exits `0`. This is the bug, pinned.
- The same, non-quiet → stderr is exactly
  `unicoach dev shell NOT active (a different Nix shell is active)`, and does
  **not** match `unicoach dev shell active`.

The startup `unset` becomes `unset UNICOACH_DEV_SHELL` — and must **not** unset
`IN_NIX_SHELL`, since the harness is itself typically launched via
`nix develop -c`; leaving it set is what makes the foreign-shell case exercise a
realistic environment rather than a synthetic one.
