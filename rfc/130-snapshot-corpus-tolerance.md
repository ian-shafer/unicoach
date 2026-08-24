# RFC 130: The snapshot corpus is stable to a tolerance, not to the byte

## Summary

The RFC 128 run reported two snapshot scenes as non-deterministic:
`conversation-list-empty-dark` and `conversation-list-populated-dark` produced
different PNGs from an unchanged tree. That report was **half right and
dangerously worded**. The bytes do differ. The picture does not, and neither
does the gate's own verdict: `bin/snapshot-ios -b` reports _no scene moved_
between any two of those captures.

What was actually flaky was the **comparison method** — an ad-hoc `md5`/pixel
-exact diff invented during review, applying a stricter rule than the gate
contracts for, to a surface the platform does not promise to reproduce bit for
bit.

This RFC therefore does not chase a bug. It **names the contract**, makes the
gate say which rule it applied, and makes a sub-tolerance drift _visible_
instead of silently swallowed — so the next person to diff two corpora reaches
the right conclusion without re-running the investigation.

## The diagnosis

Six full `bin/snapshot-ios` captures of one unchanged tree, 46 scenes:

| scene                              | distinct PNGs / 6 | max per-channel delta | pixels moved (byte rule) |
| ---------------------------------- | ----------------- | --------------------- | ------------------------ |
| `conversation-list-empty-dark`     | 3                 | 2 / 255               | 0.416%                   |
| `conversation-list-populated-dark` | 6 — every run     | 2 / 255               | 0.419%                   |
| the other 44, both light variants  | 1                 | 0                     | 0                        |

Under the gate's own rule — a pixel counts as changed only when a channel moves
by more than `SnapshotBaseline.epsilon` (8) — **zero pixels move in any scene**.

Every differing pixel in both scenes lies in a 47×44pt disc at the top-right of
the navigation bar: the compose button's **Liquid Glass capsule**. Title, glyph,
rows and empty-state text are bit-identical. The difference is not geometric —
every one-pixel shift hypothesis is rejected — it is a uniform **+1 on the blue
channel** across ~57% of the capsule. The material's resolved colour lands one
quantisation step apart between runs.

The mechanism was proved, not inferred, with a temporary in-process probe:

| probe                                                            | result                   |
| ---------------------------------------------------------------- | ------------------------ |
| the full scene, dark                                             | bistable                 |
| the full scene, light                                            | 6/6 identical            |
| settle raised 0.4s → 3.0s                                        | still bistable           |
| a **minimal** `NavigationStack` + one glass toolbar button, dark | bistable, full amplitude |
| the same minimal view with `.toolbar` **removed**, dark          | 6/6 identical            |

That rules out every alternative that mattered: async load timing (the minimal
probe has no view model, no client and no `.task`), animation in flight (a 7.5×
longer settle does not converge it), scroll indicators (the _empty_ scene has no
scroll view and flakes identically), date formatting (the empty scene has no
dates, and in the populated scene no timestamp pixel moves), unstable ordering
(row order is byte-stable), and generic GPU nondeterminism — 44 scenes are
bit-exact across six process launches, and the no-toolbar control is bit-exact
in the same process seconds from the flaking one.

**Chrome is not the discriminator; the glass _button_ is.** Twelve of the 46
captures render a navigation bar, and ten of them are byte-identical — so "has
chrome" predicts nothing. `ConversationListView` is the only corpus scene with a
toolbar **item**, and in the two captures that wobble the bar, the title and the
glyph are byte-stable while every differing pixel lies inside the compose
button's capsule. That is the answer to "why is everything else stable", and it
is narrower than it first looks: an earlier draft of this RFC said "nothing else
has glass chrome", which is false and would have sent the next reader looking at
the wrong thing.

**The dark-only asymmetry is amplitude, not kind.** The minimal probe wobbles in
light too. The material's float output differs by the same trivial amount in
both schemes; against a bright capsule it rounds to the same 8-bit value, and
against the near-black dark capsule (base values 9–35) it straddles a rounding
boundary and flips blue by one. Dark is simply where the quantisation grid is
unlucky — light is not immune in principle, and must not be documented as if it
were.

**Redrawing is not a cure.** A probe at 1, 2 and 3 `drawHierarchy` passes: two
passes gave 6/6 identical, **three** put the first capture 5304 subpixels away
again. The state is bistable, not converging, so a settle-until-stable loop
would either spin forever or exit arbitrarily. This RFC deliberately does not
add one.

## What is wrong, then

Nothing in the app: `ConversationListView` and its view model are exonerated by
the minimal control, and there is no user-visible defect. Nothing in the scenes:
no settle length, seeding or fixture pinning touches this. Nothing in the
comparator: epsilon-8 already answers correctly.

What is wrong is that **the contract is implicit**. `SnapshotBaseline.epsilon`
is a constant in a Swift file; the run prints
`no scene moved beyond 0.1% of
pixels` and never mentions the tolerance it
applied; `TESTING.md` and `bin/snapshot-ios -h` do not say that byte equality is
not the promise. So a reader who compares the corpus by the obvious means —
`md5`, `cmp`, a pixel -exact script — gets a contradiction between the tool and
their own eyes, and the plausible conclusion is "the gate is lying to me."

That is the defect: a gate whose verdict a reasonable person will not trust is
worth less than one that says nothing.

There is a second, quieter problem in the same place. Reporting only
_moved-beyond-epsilon_ means a **systematic sub-epsilon shift is invisible**. If
a token changed `#6E6E6E` to `#6A6A6A`, every affected pixel would move by 4 and
the gate would report no movement at all — indistinguishable from this RFC's
1-of-255 noise. The tolerance is right; reporting nothing below it is not.

## Detailed Design

**0. One owner for a scene's identity.** The corpus filename was derived in
three places — the renderer's private convention and both the write and
read-back sides of the walk — which is what made the round-trip below able to
silently miss its own file. A `SnapshotSceneID` owns the name and both URLs, the
renderer returns the URL it actually wrote, and a test pins the agreement, so a
divergence fails instead of degrading. The renderer also stops discarding its
write: an unwritten PNG is a defect the read-back would otherwise discover a
loop iteration later, in a different place, wearing a different meaning.

**1. The contract, stated where it is read.** One sentence in
`ios-app/UnicoachiOSTests/TESTING.md`, in `bin/snapshot-ios -h`, and in
`SnapshotBaseline`'s own doc comment: _the corpus is reproducible to `epsilon`
per channel, not byte for byte; compare it with `bin/snapshot-ios -b` and never
with `md5`, `cmp`, or a pixel-exact diff._ Each carries the one-line reason — a
platform backdrop material that quantises bistably — so the rule reads as a
finding rather than as a fudge.

**2. The run says which rule it applied.** The baseline line becomes explicit
about both halves of the verdict — the per-channel tolerance and the fraction —
rather than only the fraction, so the output cannot be mistaken for a claim of
byte equality.

**3. Both sides of a comparison must travel the same colour path.** The
comparator originally measured the **in-memory capture** against the **decoded
baseline PNG**. Those are two different paths, and the asymmetry alone puts a ±1
floor under _every_ scene: the first run of the new figures reported max delta 1
on all 46, including scenes whose PNG files were byte-identical. Drift figures
dominated by a codec artifact are worse than no figures, because they look like
data. The walk therefore re-decodes the PNG it has just written and compares PNG
to PNG, so the only difference either side can express is the one being
measured.

This was not anticipated when this RFC was drafted; it was found by running the
evidence bullet below and getting a result the RFC said was impossible. It is
recorded here because the same trap waits for anyone who adds a second
comparison path later.

**4. Sub-tolerance drift is reported, not swallowed.** Alongside the moved
fraction, each compared scene reports its **maximum observed per-channel delta**
and how many pixels differ at all.

A comparison is therefore a **sum type**, not a record with optional fields:
either a scene was _measured_ — carrying the moved fraction, the drift figures
and the mask together — or it was _not comparable_, carrying the two raster
sizes that disagreed. A record with a nullable drift group admits
`movedFraction: 0.5` beside no measurement, and it let an earlier draft of this
change fabricate a maximal `255/255` row for a resized scene, which reads as a
catastrophic colour change when it means nothing was measured. Evidence that can
impersonate a measurement is the same defect this RFC exists to end, one layer
down.

The sum type also removes a real memory-safety path rather than guarding it. The
diff overlay is reachable only from the measured case, whose mask carries its
own raster size; an earlier draft let a resized scene reach the overlay writer
with an empty mask and the _baseline's_ dimensions over a buffer decoded from
the _capture_, which an AddressSanitizer reproduction confirms is a heap read
past the end of the allocation. Making the state unrepresentable is why there is
no longer a branch for a future reader to mistake for dead code and delete —
which is exactly how it was deleted.

The figures are measured on the analysis downscale RFC 122 uses to survive its
own memory limits, whose divisor varies with scene height, so they are **not
comparable between scenes** and the report says so where it prints them. A scene
at max-delta 2 is this RFC's known noise; a scene at max-delta 7 across a broad
area is a real change hiding under the tolerance, and today nothing would say
so. It is **reported, never failed** — that is RFC 122's existing posture for
baseline comparison and this RFC does not change it.

**5. The contract becomes an assertion.** A test captures one scene **twice in
the same process**, round-trips both through PNG data at full resolution — the
corpus is PNGs, and item 3 is the proof that the round trip changes the numbers
— and asserts the two are equal _under the epsilon rule_, and additionally that
the observed maximum delta is below `epsilon`. The first half is the promise
this RFC makes; the second is an early warning — if a future OS widens the
wobble toward the tolerance, that assertion fails while there is still headroom,
instead of the corpus quietly becoming untrustworthy.

It lives in a **test class of its own**, not beside the corpus walk. Capture is
what `bin/snapshot-ios` runs, and an assertion about platform drift that could
abort capture would turn an early warning into an outage, reported under a
message about the wrong subject entirely.

Deliberately **not** asserted: byte equality, which is precisely the claim this
RFC retires.

## What this does not do

- **No settle-until-stable loop**, and no extra redraw passes. Proved above to
  reshuffle the bistability rather than resolve it.
- **No change to the app to suit the test.** Removing or restyling the toolbar's
  glass would make the corpus photograph something the student never sees.
- **No change to `epsilon` or `threshold`.** 8 is 4× the observed amplitude,
  which is the headroom that makes the verdict meaningful; item 3 addresses what
  a tolerance necessarily hides without moving it. Tightening it toward 2 would
  buy sensitivity by making the corpus flap on exactly this noise.
- **Drift never fails the build.** Baseline comparison stays advisory (RFC 122):
  a scene that moved is reported, and reporting is all it does.

  **A comparison that could not be performed is a different thing, and it does
  fail.** A baseline PNG that exists but cannot be read or decoded is not drift
  — it is a broken input — and the two must not share an answer. Silently
  dropping such a scene would shrink the compared-scene count this report now
  presents as headline evidence, which is a quieter version of the same defect
  this RFC exists to end: a number that looks like a measurement and is not. A
  _missing_ baseline stays ordinary and is merely noted, because a new scene has
  no baseline by definition.

## Files Modified

**Modified — tests (`ios-app/UnicoachiOSTests/`)**

- `SnapshotHost.swift` — a scene's identity gains one owner (`SnapshotSceneID`)
  and the renderer returns the URL it wrote; a comparison becomes a sum type
  carrying either a measurement (moved fraction, drift figures, mask) or the two
  raster sizes that disagreed; `SnapshotBaseline`'s doc states the contract
- `SnapshotTests.swift` — the reporting line states both halves of the rule and
  the sub-tolerance figures; the new repeatability assertion
- `TESTING.md` — the contract, and the diagnosis in two sentences

**Modified — scripts**

- `bin/snapshot-ios` — `-h` and the comparison log line state the contract, and
  the failure path names both of the causes it can now have and still emits the
  corpus path a caller captured stdout for
- `bin/ios-scripts-tests` — pins the contract prose (never the numeral, which
  would defeat naming `SnapshotBaseline.epsilon` as the single authority) and
  the script's stdout-on-failure behaviour
- `ios-app/DEPLOY.md` — carried its own copy of the "never fails the run" claim

## Implementation Plan

1. Give `compare` a sum-typed answer: a measured comparison carrying the maximum
   per-channel delta, the differing-pixel count, the pixel total they are read
   against and the mask; or a not-comparable one carrying the two raster sizes.
   Keep the moved-fraction rule exactly as it is. A size mismatch keeps its
   existing "total move" verdict and reports **no** per-pixel figures, because
   none were taken.
2. Compare **PNG to PNG**: re-decode the file just written rather than measuring
   the in-memory capture against a decoded baseline. Only under `-b` — the
   ordinary capture run must not pay a decode it never reads.
3. Report them from `SnapshotTests`, and make the verdict line name the
   tolerance as well as the fraction.
4. Add the two-captures-in-one-process repeatability assertion, in a test class
   of its own so that a future OS widening the wobble reports a failed assertion
   rather than killing corpus capture.
5. State the contract in `TESTING.md`, `bin/snapshot-ios -h`, and
   `SnapshotBaseline`'s doc comment.
6. Run `bin/test-ios`; then `bin/snapshot-ios -b` against a corpus captured from
   the same tree, and **keep the report** as the run's evidence rather than
   quoting a summary of it.

## Tests

- **The repeatability assertion** above — the contract, executed. It must not be
  written as a byte comparison, which is the very thing that produced the false
  report.
- **The comparator's new figures are unit-tested** where they can be: two
  synthesized rasters differing by a known amount per channel must produce that
  maximum delta and that differing-pixel count, including the case where every
  difference is below `epsilon` — the case where the moved fraction is 0 and the
  new figures are the only signal.
- **`bin/ios-scripts-tests`** covers `bin/snapshot-ios`'s help text as it covers
  the rest of that family.
- **Evidence, not assumption**: a `-b` run against a same-tree corpus, showing a
  max delta of 1–2 confined to the two known scenes and 0 for the other 44.

  Two properties of that evidence are worth stating so a future reader is not
  surprised by it. The corpus comparison decodes at `SnapshotRaster`'s 1024
  downscale — a memory constraint inherited from RFC 122 — so its figures are
  measured on a 2×-averaged raster: deltas of 2 survive that, but a single stray
  subpixel can average away. The repeatability assertion deliberately does
  **not** downscale, because averaging 2×2 blocks is exactly what would hide the
  1/255 shift it exists to watch. And the wobble is bistable, so **which** of
  the two dark scenes carries it varies per run; both are named because both are
  the same mechanism and neither is reliably the one.
