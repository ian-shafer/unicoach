# RFC 236: Close the gap — one process writes the suite record

## Summary

RFC 235 landed a records hand-off in two processes: `ship-run` wrote a record,
and `ship-suite-green` re-read that record and projected three lines out of it
into `<rs>/suite-green.txt`, the file phase 5 reads to decide whether the suite
must run again. Between the two sits a gap, and the whole class of defects this
RFC removes lives in it: **the record a projection reads may not be the record
this invocation wrote.**

`ship-run` gains `-g`. It writes the three lines itself, in the process that ran
the command, from the values it already holds. `ship-suite-green` is deleted.

Two smaller changes ride with it, both in `ship-run`: the invocation ledger
becomes append-only EVENTS — a `start` line before the command runs and an `end`
line after — so a killed invocation is still counted and still visible; and a
`-c <caller>` column names who invoked it, so phase 7's figure is per fix child
rather than per run.

## Motivation: one seam, three repairs, one run

The gap was patched three times inside RFC 235's own run, each patch sound and
each leaving the class intact:

1. The first recipe ended `|| true`. That discards `ship-run`'s refusal status
   as well as the suite's, and a REFUSED invocation writes no record — so the
   PREVIOUS run's green record stood and was projected. Tier 2 found it, graded
   it BLOCKING, and demonstrated it against the two scripts.
2. The repair read the exit status and refused `bin/functions`' reserved band as
   "ship-run refused". Tier 3 found that a wrapped command's OWN usage fault
   exits that same band — `bin/test` uses it — so the guard names the wrong
   cause and discards a record this invocation did write.
3. The repair for THAT took a timestamp before the invocation and refused a
   record whose `started=` was earlier. It is a window, not a fact: `started=`
   carries one-second resolution, so two invocations inside one second are
   indistinguishable, and a finer clock would only narrow the window.

Three repairs, one seam. The fourth patch is not a better guard — it is removing
the thing being guarded. A process that ran the command does not need to ask
whether the record it is about to write belongs to it.

**What this gives up, honestly.** `ship-run` becomes a command wrapper that
knows the name of one repo-specific artifact, `<rs>/suite-green.txt`, and the
three keys RFC 211 puts in it. Tier 1's `design-review-feature-isolation` would
object, and it objected to the JUnit counts on the same grounds. The answer is
the same one: the coupling is a fact about this repository, it is contained in
one function behind one flag, and the alternative — a second process reading the
first one's file — is what produced a BLOCKING defect and two follow-on repairs
in a single run. Correctness before isolation, stated rather than assumed.

## Decisions

### D1 — `ship-run -g` writes the RFC 211 record, in-process

`-g` takes no value: the file is `<rs>/suite-green.txt`, derived from `-s`
exactly as `<rs>/runs/` already is. With `-g`, after the command has run and the
record is written, `ship-run` appends

    exit=<the command's status>
    cmd=<the command, %q-quoted, as in the record>
    tree=<HEAD^{tree} of -d>

from the values in memory. It re-reads nothing.

A RED run is projected too, and says so on stderr: phase 5's rule is that
`exit=0` is required for reuse, so a non-zero line is the honest record of a
suite that ran and failed, and phase 5 re-runs. That is unchanged from
`ship-suite-green`'s behaviour.

### D2 — a projection that cannot state all three keys writes NONE of them

`tree=` is the one key that can fail after a command has run — `-d` may not be a
repository. When it cannot be measured, `-g` writes nothing at all, names the
reason on stderr, and the command's status still propagates. Two lines are worse
than none: `suite-green.txt` is append-only and phase 5 keys on all three, so a
two-line record is a fragment that the next reader cannot tell from a complete
one.

### D3 — `ship-suite-green` is deleted, not kept as a second door

342 lines and one purpose, which is now impossible to need: there is no record
to validate, because nothing re-reads one. Keeping it would leave a second way
to write the gate's file that still has the stale-record class in it.

Its assertions do not die with it. What they were really asserting — three lines
appended, byte-identical to the record; a red run projected with a warning; an
unmeasurable tree projecting nothing and saying so; append-only across repeated
invocations — moves into the `ship-run` section of `bin/ship-scripts-tests` and
is asserted against `-g`.

### D4 — the ledger records EVENTS, not outcomes

`<rs>/runs/ledger.tsv` is append-only, one line per event. The TWO-EVENT shape
is `scripts/ship-lens`' precedent — that ledger has recorded `start` / `done` /
`skip` since RFC 234, with a VERB column and the rest of the line read in the
light of it:

    <instant>	start	name=<n>	caller=<c>	cmd=<cmd>
    <instant>	end	name=<n>	caller=<c>	exit=<status>

EVERY FIELD AFTER THE VERB NAMES ITSELF. That is RFC 235's rule for what
`ship-run` writes, and moving from one line per invocation to two events does
not suspend it: `…	0	…` leaves a reader unable to say whether the `0` is a
status, a count or a flag, and nothing in the line recovers the answer. So this
PRESERVES RFC 235's rule rather than reversing it — the fields are the same
`name=`, `caller=`, `cmd=`, `exit=` that RFC 235 named, redistributed across two
events, with `cmd=` on the `start` and `exit=` on the `end`.

The instant and the verb are the two bare fields, and neither is an exception to
the rule so much as outside it. An ISO-8601 instant is unambiguous on sight. The
verb is the line's own LEGEND: it is what says the last field is `cmd=` rather
than `exit=`, so keying it would be keying the key. RFC 235 spelled the instant
`started=`; it is bare here because it is no longer one line's start time but
each EVENT's own instant, and `started=` on an `end` line would be a lie.

`caller=` is always answered, because `-c` is required (D5).

The `start` line is written BEFORE the command runs. That is the whole point: a
run killed mid-command currently appends nothing, so the count is lowest exactly
for the invocations that went wrong. A `start` with no `end` is now a fact on
disk — the same signal `ship-lens missing` reads as `STALLED`.

Counting invocations is counting `start` lines.

### D5 — `-c <caller>` names who invoked it

**Required**, `[a-z0-9][a-z0-9-]*`, written into both ledger lines and nowhere
else. `ship-run` cannot discover its caller, so the dispatch templates in
`SKILL.md` and `references/review.md` tell a fix child to pass its own name, and
the script refuses an invocation that does not.

**Amended in tier 2** (`code-review-no-sentinels`,
`design-review-minimum-context`, `code-review-allowlist`). It was OPTIONAL, and
an invocation that gave no `-c` wrote the token `caller=-`. The reason it is
required now is that **an unnamed invocation and a named one must not look alike
in a file whose only job is counting**: `-` is a caller nobody can be, readable
only to someone carrying the option's grammar in their head, and a `uniq -c`
over the column reports a caller called `-` that made runs. The same script
refuses that shape everywhere else it writes — `tree_absent=` and
`junit_omitted=` are keys of their own precisely so no placeholder ever sits in
the field it explains — and the ledger was the one file that did not keep the
rule. Every production caller is a named child, so requiring it costs nothing
and removes the sentinel entirely.

Phase 7's figure becomes per caller, and `SKILL.md` says so.

### D6 — four advisory findings from RFC 235's review, carried here

All four are in these same two files and were held as advisory in that run:

1. **A wrapper fault after the command ran must not eat the command's status.**
   Once the command has run, every later step is best-effort with a NAMED
   omission — the excerpt, the counts, the projection — and the exit status is
   the command's. A wrapper that cannot finish its bookkeeping says so on stderr
   and still answers for the command.
2. `absolute_dir` runs `cd "$1"`, not `cd -- "$1"`: a directory whose name
   begins with `-` is read as an option.
3. `state_value` returns `""` for both an absent key and an empty one. Absent is
   a missing state file key and empty is a broken one; they are refused
   separately, by name.
4. The excerpt keeps the FIRST 200 matching lines when it caps, and the LAST 50
   when no pattern matched, and the record does not say which. It gains
   `failing_kept=head|tail`.

## Detailed Design

### `scripts/ship-run`

- `-g` added to `getopts`; no value; refuses a repeat like every other flag.
- `-c <caller>` added; REQUIRED; grammar `[a-z0-9][a-z0-9-]*`; empty refused.
- `derive_record_paths` also derives `GREEN` (`<rs>/suite-green.txt`).
- `append_ledger` becomes `ledger_event <verb>`, called twice: `start` from
  `run_command` before it executes, `end` from `write_record`.
- `project_green` appends the three lines when `-g` is given and `TREE` is
  known; when it is not, it writes nothing and logs an error.
- `absolute_dir` uses `cd --`; `state_value` distinguishes absent from empty;
  `build_excerpt` records `failing_kept`.
- The fault rule from D6.1 is stated where the steps after the command run.

### `scripts/ship-suite-green`

Deleted.

### `SKILL.md` and `references/review.md`

The phase 4 suite recipe becomes one line:

    scripts/ship-run -s <rs> -n suite -c <caller> -g -- nix develop -c bin/test

No status arithmetic, no second command, no timestamp. The paragraphs that
explained the two-step and its freshness check are replaced by one that says the
record is written by the process that ran the suite. The fix-child command rule
gains `-c`. Phase 7's figure is per caller. The script index drops
`ship-suite-green`.

### `bin/ship-scripts-tests`

The `ship-suite-green` section is deleted; the `ship-run` section gains the
`-g`, ledger-event, `-c` and D6 assertions. Every new assertion is
mutation-verified.

## Files Modified

| file                                                | change                                                     |
| --------------------------------------------------- | ---------------------------------------------------------- |
| `rfc/236-close-the-gap.md`                          | new — this RFC                                             |
| `.prime/agent/skills/ship/scripts/ship-run`         | `-g`, `-c`, ledger events, the four D6 fixes               |
| `.prime/agent/skills/ship/scripts/ship-suite-green` | deleted                                                    |
| `.prime/agent/skills/ship/SKILL.md`                 | the one-line recipe; phase 7's figure; script index        |
| `.prime/agent/skills/ship/references/review.md`     | the fix-child command rule gains `-c`                      |
| `bin/ship-scripts-tests`                            | the suite-green section deleted; new `ship-run` assertions |

## Implementation Plan

1. `ship-run`: `-g` and `project_green`; then `-c` and the ledger events; then
   the four D6 fixes.
2. `bin/ship-scripts-tests`: delete the `ship-suite-green` section, move its
   surviving claims onto `-g`, add the ledger-event, `-c` and D6 assertions,
   mutation-verify each.
3. Delete `scripts/ship-suite-green`.
4. `SKILL.md` and `references/review.md`.

One commit. The two halves of RFC 235 are separable in history; this is one
change to one seam.

## Tests

In `bin/ship-scripts-tests`, each verified by mutation:

1. `-g` appends exactly three lines, and each is byte-identical to the
   corresponding `key=` line in the record the same invocation wrote.
2. Without `-g`, `<rs>/suite-green.txt` is not created or touched.
3. A failing command with `-g` appends `exit=<status>` and warns; the file still
   gains exactly three lines.
4. A `-d` that is not a git repository appends NOTHING, names the reason, and
   still exits with the command's status.
5. Two `-g` invocations append three lines each, in order.
6. A usage fault with `-g` writes no green file at all.
7. `ledger.tsv` gains a `start` line before the command runs: a command killed
   mid-run leaves `start` and no `end`.
8. A normal run leaves `start` then `end`, and `end` carries the command's exit.
9. `-c` writes the caller into both lines; an invocation with no `-c` at all is
   refused in the reserved band and writes nothing.
10. `-c ""` and a `-c` that breaks the grammar are refused with the reserved
    code, and nothing is written.
11. A directory named `-weird` is entered by `absolute_dir` rather than read as
    an option.
12. A state file whose `CODEBASE_ROOT` is absent and one whose value is empty
    are refused with different messages.
13. `failing_kept=head` when the cap trimmed matching lines; `failing_kept=tail`
    when the fallback took the last lines; absent when nothing was excerpted.
14. A fault in the steps after the command ran leaves the command's status
    intact and names the omission.

No assertion reads a document or a script's source. Everything here is asserted
by running `ship-run`.
