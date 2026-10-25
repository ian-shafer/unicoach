# Close out

The run is not finished when the code lands. It is finished when the product
docs say what the product now is.

**Claim a docs worktree first, and write there — never in the root checkout.**
One command before anything else in this phase, and one after (RFC 201):

    nix develop -c $DOCS/docs-claim -s rfc-<n>-docs    # from the ROOT checkout
    # ... write a, b and c below in the worktree it prints as CODEBASE_ROOT ...
    nix develop -c bin/format
    nix develop -c git commit -m "docs: close out <slice-id> (RFC <n>)"
    nix develop -c $DOCS/docs-land -s <RUN_SCRATCH>    # prints the landed SHA

The slug is free-form kebab-case. `rfc-<n>-docs` is the convention for one slice
with an RFC; a slice that landed without one uses its /work run id
(`ship-<slug>-docs`); a close-out covering two landed slices takes ONE claim
with a slug of its own (`wave-2-docs`) rather than two claims racing on one
`STATUS.md`. The commit is `product/**`-only, so it skips the test half of the
gate (RFC 194): it costs the format check, not a full test run.

## a. The brief's ledger

`product/NNNN-*/brief.md`, one appended line in the house format — slice ID, old
letter if any, what it landed as, the code SHA, date, one-line what:

    search/04/similar-colleges (S4) LANDED as RFC 149 (main@<code>,
      2026-08-31) — query-time similar_colleges tool: weighted distance over
      the index percentile columns, axes and constraints named in every
      response.

A slice /work landed at spec 0–2 has no RFC. Say so, naming the run instead, so
the ledger never implies a design doc that does not exist:

    money/01.2/the-label LANDED without an RFC (/work ship-the-label, spec 2,
      main@<code>, 2026-10-25) — one-line what.

`slice-board` reads `LANDED` and the ID; the RFC number is optional to it.
Append; never edit a landed line. Record a PREFER override here too.

The close-out commit's own SHA is deliberately NOT in the line. It is only known
after `docs-land`, so recording it took a second docs commit per slice (11 of
the 14 close-outs before 2026-10-25 carry one), and git already holds it:
`git log -- product/NNNN-*/brief.md`. Older lines that carry
`main@<code> + <doc>` are left as they are.

## b. `product/STATUS.md` — five edits, all of them

- the **TL;DR** — rewritten, most important first, never "see below";
- the **`Updated:` line** — date, what landed, and the new next-free RFC number,
  recomputed now:

      ls rfc | grep -Eo '^[0-9]+' | sort -n | tail -1
      git branch -a --format='%(refname:short)' | grep -Eo 'rfc-[0-9]+' | sort -t- -k2 -n
      git worktree list

  Next free = one more than the highest of the RFC files and any
  `pipeline/rfc-<n>` branch or worktree. Never copy the number from the previous
  `Updated:` line;
- the **work table** — this slice's row, and the next slice's honest state;
- the landed feature's **user-manual entry** under "The product today", which
  **names its door** (how a real user reaches it), what it does, how it
  degrades, and any rollback knob;
- the **wave board** — the landed slice leaves it, and any slice it unblocked
  moves to the ready wave. `slice-board` is still the computed answer; the table
  is for the reader.

When `STATUS.md` and a brief ledger disagree, the ledger wins and `STATUS.md`
gets fixed.

## c. Re-check the next slice

Against what actually landed. The code wins over the spec; a drifted next-slice
instruction is a defect to report to /chart now, not a surprise at its kickoff.
Run `$BOARD` once more: it must exit 0 and list this slice under LANDED.
