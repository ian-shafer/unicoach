# Dials

The model sets `design` and `scrutiny`; `ship_driver/dials.py` derives
`approval` and `tests` (RFC 256 D3, D4). The code wins.

## design: `none` | `rfc`

`rfc` when the change has more than one reasonable shape and the wrong one is
costly to reverse, or it creates new behaviour, a new surface, a table, a
migration, or a prompt change. `none` when the instruction or a failing test
gives the shape.

## scrutiny: `none` | `targeted` | `full`

- `none` — no lens runs; the suite and the hook are the review. For docs-only
  changes and a one-file fix with a failing test.
- `targeted` — the lenses the applicability map allows; only `BLOCKING` is
  repaired, `SHOULD-FIX` is held and reported. For a fix under four code files.
- `full` — every lens the map allows; `BLOCKING` and `SHOULD-FIX` repaired.
  Whenever `design=rfc`, or the diff touches `db/schema/`, `prompts/`, money,
  auth, or more than three code files.

Tier 0 runs only with `design=rfc`; otherwise the `impl-review-*` lenses are
skipped with `no RFC in this run`.

**Narrowing** (`Dials(narrow=True)`, `targeted` only, off by default): one call
to `deepseek/deepseek-v4-pro` with the diff and the remaining lens descriptions.
It may only remove lenses; each removal is a `skip` whose reason starts
`narrowed:`, and the report counts them.

## approval: derived

Required when `design=rfc`, or when the instruction or changed paths touch
anything irreversible: `db/schema/**`, `db/seed/**`, `prompts/**`, money
(`service/**/budget/**`, `service/**/subscriptions/**`, `appstore/**`, StoreKit,
the paywall), auth, account or data deletion, public copy (`PRODUCT.md`,
`HomePage`). Otherwise skipped.

It is derived twice: at `start()` from the paths and words the instruction names
(migration, billing, auth, …), and after implementation from the diff. A diff
that trips a rule the instruction did not predict stops the run before verify.
An implementer's irreversible question (`Ask('decision')`) makes approval
required for the rest of the run. A reversible choice is stated as a default and
reported `FYI`.

## tests: derived through `bin/gate-scope`

The same script and table the hook uses, run over the run's base-to-HEAD name
set without touching the real index. Re-derived after every rebase into
`DIAL_TESTS`. From its answer verify runs `bin/test` when `gradle=yes`; each
harness `harnesses=` names, or all of them when it says `ALL`. The map lives in
`bin/gate-scope` only. The hook stays the authority.

## Which lenses can fire

`ship_driver/applicability.py` is the one table. It classifies each changed path
— `kotlin`, `swift`, `ts`, `python`, `sql`, `shell`, `markdown`, `config`,
`prompt` — by extension, or by shebang when there is none (otherwise `config`),
and each lens names the classes it can fire on. Three shapes of row:

- typed-language rules (`execution-context`, `immutable-returns`,
  `structured-payloads`, `no-sentinels`, …) fire on kotlin, swift, ts, python;
- `semantic-output` fires on shell only;
- every other code and design lens fires on any code class; `impl-review-*` also
  needs `design=rfc`.

`markdown` and `prompt` fire nothing: a run whose every path is one of those
runs no lens at any scrutiny, and the first message says so. Every lens the map
removes is skipped with one reason per group per tier naming the classes
present, so the tier's accounting still closes. A lens with no row is
dispatched, and a test fails until the row is added.

## Retired lenses

A lens is retired in the corpus, by `/principle`: its directory and its
`tiers.md` row are deleted, so `ship-lens missing` never names it and the driver
never dispatches it. Its `LENS_GROUP` row in `applicability.py` is then deleted
too; the map keeps no retired set. `ship_driver.rank_lenses(archive_dir)`
regenerates the ranking from `.scratch/ship-archive`, so the next such decision
reads fresh figures.
