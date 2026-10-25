# Dials

The coordinator sets **spec** and **scrutiny**; **approval** and **tests** are
derived. State each in words with its reason in the first message.

## spec: 0–4

Pick the lowest level at which the implementer cannot plausibly build the wrong
thing. A level is about how much of the shape is still open, not about size: a
large rename is level 0, a two-line change to how money is counted may be 3.

| Level | Written before code                                                                  | Choose when                                                                 |
| ----- | ------------------------------------------------------------------------------------ | --------------------------------------------------------------------------- |
| 0     | nothing; the instruction is the spec                                                 | the instruction fully determines the diff                                   |
| 1     | a failing test that reproduces the bug or pins the behaviour                         | a bug, or behaviour a test can state completely                             |
| 2     | `<rs>/plan.md`: the four Tier 0 headings, a few lines each                           | one obvious shape, but more than one file or a choice worth recording       |
| 3     | `rfc/<n>-<slug>.md` with Detailed Design, Files Modified, Implementation Plan, Tests | a new surface, table, migration, or prompt change with one reasonable shape |
| 4     | a full RFC to the `/rfc-design` standard: alternatives, decisions with defaults      | more than one reasonable shape and the wrong one is costly to reverse       |

Levels 3–4 claim with `-l rfc` so the RFC number is reserved; the RFC is
committed with the code in the one land commit and is immutable afterwards
(`rfc/README.md`). Level 2's plan is not committed; it is archived with the run.

Tier 0 (`impl-review-*`) runs at levels 2–4 against whatever spec exists, and is
skipped at 0–1 with the reason `no spec in this run`.

## scrutiny: `none` | `targeted` | `full`

- `none` — no reviewer runs; the suite and the hook are the review. Docs-only
  changes, and a one-file fix with a failing test.
- `targeted` — every lens the applicability table allows; only `BLOCKING` is
  fixed, `SHOULD-FIX` is held and reported. A fix touching fewer than four code
  files.
- `full` — every lens the table allows; `BLOCKING` and `SHOULD-FIX` fixed.
  **Required** at spec 3–4, and whenever the diff touches `db/schema/`,
  `prompts/`, money, auth, or more than three code files.

Scrutiny never drops below what the real diff requires: if implementation
produced a diff the rule above puts at `full`, raise it and say so.

## approval: derived

**Required** when spec is 3–4, or the instruction or changed paths touch
anything irreversible:

- `db/schema/**`, `db/seed/**`
- `prompts/**`
- money: `service/**/budget/**`, `service/**/subscriptions/**`, `appstore/**`,
  StoreKit, the paywall
- auth, account or data deletion
- public copy: `PRODUCT.md`, `HomePage`

Otherwise skipped. Derived twice: at the start from what the instruction names
(migration, billing, auth, …), and after implementation from the real diff. A
diff that trips a rule the instruction did not predict stops the run before
review. An implementer's irreversible question makes approval required for the
rest of the run. A reversible choice is stated as a default and reported `FYI`.

## tests: derived through `bin/gate-scope`

The same script the hook uses decides which harnesses a diff can break. The hook
is the authority; /work runs `nix develop -c bin/test` unscoped once in verify
and leaves the shell harnesses to the hook. Never scope `bin/test` by module and
never pass `-f` to verify a change (CLAUDE.md).

## Which lenses can fire

Classify each changed path, then dispatch only the lenses whose group can fire
on a class present. Skip the rest with one `ship-lens skip` per lens and one
shared reason per group per tier, naming the classes present.

Path classes: `prompts/**` → prompt; by extension `.kt .kts` kotlin, `.swift`
swift, `.ts .tsx .js .mjs` ts, `.py` python, `.sql` sql, `.sh .bash` shell,
`.md` markdown; any other extension → config; no extension → read the shebang
(python, shell) else config.

| Group | Fires on                                      | Lenses                                                                                                                                                                                                                                                                                                                                  |
| ----- | --------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| typed | kotlin, swift, ts, python                     | `code-review-execution-context`, `code-review-immutable-returns`, `code-review-structured-payloads`, `code-review-unit-encapsulation`, `code-review-lossless-domain-mapping`, `code-review-no-sentinels`, `code-review-no-leaks`, `design-review-impossible-misuse`, `design-review-domain-outcomes`, `design-review-feature-isolation` |
| shell | shell                                         | `code-review-semantic-output`                                                                                                                                                                                                                                                                                                           |
| code  | kotlin, swift, ts, python, sql, shell, config | every other `code-review-*` and `design-review-*` lens                                                                                                                                                                                                                                                                                  |
| impl  | any code class, and spec 2–4                  | `impl-review-*`                                                                                                                                                                                                                                                                                                                         |

`markdown` and `prompt` fire nothing: a run whose every path is one of those
runs no lens at any scrutiny, and the first message says so. A lens in
`tiers.md` that this table does not name belongs to `code` until the table is
updated between runs.
