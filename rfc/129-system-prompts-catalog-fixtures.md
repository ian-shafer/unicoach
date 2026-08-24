# RFC 129: The system_prompts catalog is migration-seeded, not fixture-restored

## Problem

Seventeen test classes across `db`, `service`, and `rest-server`
`TRUNCATE
system_prompts` in `@BeforeEach` and then hand-restore a list of
`(name, version)` rows. The lists are hard-coded and stale: eleven of them
restore `extraction`/`synthesis` at `v1` while `service.conf` pins `v2`, and the
two fit-lens classes restore only the fit-lens pair, leaving no `coach` row at
all.

That makes the shared test database order-dependent. RFC 124 found it the hard
way: flipping `coaching.systemPromptVersion` to `v2` broke 24 `rest-server`
tests in a module the change never touched, because those tests never seed a
coach prompt and had been silently reading a row some other module's fixture
happened to leave behind.

The defensive machinery this grew is now visible in five places, and each of
them names the same cause:

- `OfflineCoachingE2eTest` re-inserts four prompts with
  `ON CONFLICT DO
  NOTHING`, commenting that "a sibling module's DAO test on
  the shared DB may have wiped the migration seed by the time this class runs".
- `SystemPromptsDaoTest.ensureSeed` re-asserts the migration 0011 seed before
  every case, because "a sibling DAO test truncates `system_prompts` in its own
  setup, and JVM test ordering is unspecified".
- `AdminTestSupport.fitLensPromptId` is a find-or-create instead of a lookup,
  "so a self-healing lookup keeps the fit-lens seeders robust under the
  full-suite run order".
- `ConvosDaoTest` and `FitLensRunsDaoTest` already took the opposite route under
  RFC 124 — they stopped truncating `system_prompts` entirely.

So two fixture styles coexist on `main`, and the older one is the bug. This RFC
converges them on the newer one.

## Detailed Design

### The catalog does not need restoring, because it is never legitimately empty

`system_prompts` is a migration-seeded, insert-only, immutable catalog (RFC 33,
`db/schema/0007`). Its rows are created by `db/schema/0011`, `0020`, `0026`,
`0032`, `0037`, and `0044`, and never updated. `bin/test` runs
`postgres-up -> db-reset -> db-tests` before every Gradle invocation, and
`db-reset` is drop -> create -> migrate. **Every suite run therefore begins with
the full catalog present, containing exactly the rows the migrations seed** —
which is, by construction, a superset of what `service.conf` can pin, since a
new pin requires a new seed migration to have a row to point at.

The only thing that empties the catalog mid-run is the fixtures themselves. Stop
truncating it and the restore lists have nothing left to do.

That is the whole fix. The three decisions the brief asked for follow from it.

### Decision 1: where the helper lives — nowhere; there is no helper

The brief proposed a shared seeder reading the pinned pairs from `service.conf`
at runtime. This RFC declines it, for two reasons.

First, it would be dead code. Once nothing truncates, the pinned rows are
already present before the first test runs; a seeder would execute six
`ON CONFLICT DO NOTHING` inserts that never insert. A fixture that is a no-op in
every case it runs is not a fixture, it is a comment with a JDBC connection.

Second, siting it is unsolvable as posed. The `db` test source set must not
learn about `service.conf` to test DAOs; a `service`-side helper is unreachable
from `db`; and a SQL-only seeder taking the pairs as arguments only relocates
the hard-coding into every caller's argument list — the exact staleness this RFC
exists to kill, one indirection further away.

The acceptance criterion is met more strongly without a helper. "A version bump
in `service.conf` requires zero fixture edits" becomes true because no fixture
mentions a version at all, rather than because a helper reads them for us.

Note what a version bump actually costs after this change: a new seed migration
(where the new body must live regardless — the catalog is immutable, so a `v3`
is a new row) plus the one-line `service.conf` edit. That is exactly the shape
RFC 124 had: `0044` plus the pin. Zero fixtures.

One class of test edit survives, deliberately. `CoachingConfigTest` and
`FitLensConfigTest` assert the _packaged default values_ —
`assertEquals("v2",
coaching.systemPromptVersion)`. Those are not fixtures and
not staleness: their whole subject is what `service.conf` ships, so a pin change
is precisely the event they exist to notice. Changing the shipped value and the
assertion about the shipped value in the same commit is the assertion working.
The defect this RFC removes is a _fixture_ in an unrelated module encoding the
pin behind the runtime's back.

### Decision 2: these classes stop truncating the catalog

Generalise RFC 124's route to all seventeen. `system_prompts` leaves every
`TRUNCATE` list; the hand-restore blocks are deleted.

Truncating a table that other modules read, on a database they share, to get a
clean slate that none of these tests actually want, is the defect. Not one of
the seventeen asserts anything about the catalog's _contents as a whole_ — they
each want _their own_ rows to exist, which insert-only semantics give them
without a wipe. `SystemPromptsDaoTest` is the sole class that is about the
catalog itself, and it already works by creating uniquely-named rows.

Removing `system_prompts` from a `TRUNCATE ... CASCADE` list is safe: every
table that would have been reached by the cascade (`llm_requests`,
`llm_responses`, `convo_requests`, `extraction_runs`, `synthesis_runs`,
`fit_lens_runs`, `observations`) is already named explicitly in the same
statement.

**Fixture rows must become collision-proof.** Eight classes generate a version
from a per-instance counter (`"p${promptCounter++}"`). JUnit constructs a fresh
instance per test, so the counter restarts at zero each time and only truncation
kept `(name, 'p0')` unique. These move to a UUID-derived version — the pattern
`ConvosDaoTest` and `FitLensRunsDaoTest` already adopted under RFC 124:

```kotlin
private fun createSystemPrompt(): UUID {
  val id = UUID.randomUUID()
  // Unique per row rather than a per-instance counter: system_prompts is not
  // truncated between tests, and JUnit builds a fresh instance per test, so a
  // counter would collide on (name, version).
  ... INSERT ... VALUES (?, 'coach', 'v-$id', 'be a coach')
}
```

Accumulating a few hundred throwaway catalog rows across a suite run costs
almost nothing: they are dropped with the database at the next `db-reset`. It is
not entirely free, though, and the one place it bites is worth stating rather
than discovering. admin-web's `/system-prompt` list is `ORDER BY name ASC` and
paged at 50, and `SystemPromptsResourceTest`'s
`list omits the body but shows
name and version` seeds a row and asserts its
name appears. Once the catalog legitimately carries every other module's
throwaway `coach`/`extraction` fixtures, page one is all `coach` rows and the
seeded `rfc63-*` row is well past it. That case walks the pager to the page
carrying its own row instead of assuming page one — a fix that is more honest
than the assumption it replaces, since the page-one assumption was only ever
true by accident of truncation.

**Body assertions read the seeded body.** `CoachingServiceTest` hard-codes
`pinnedCoachBody = "You are Uni, a warm coach who writes Markdown."` — its own
fixture's stand-in body, asserted `assertEquals`-style in five cases. With the
migration seed in place the real body is `0044`'s. The field becomes a lazy
lookup of the body at the pinned `(name, version)`, alongside the
`coachPromptId()` lookup already there for the same reason. The assertions then
say what they mean: _the request carried the prompt the config pins_.

### Decision 3: a missing pinned row fails loudly, once

Today a pin with no catalog row surfaces as a 500 deep inside a routing test.
The brief proposed a one-line assertion in shared test setup. This RFC sites it
differently: as **one dedicated contract test**, not a per-class setup
assertion.

Per-class is the wrong shape once nothing truncates. It would run seventeen
times to check a fact that cannot change during a run, and it would re-introduce
into `db`'s tests the `service.conf` dependency decision 1 just refused.

Instead, `service/src/test/.../SystemPromptCatalogTest.kt` — the `service`
module already loads `service.conf` and depends on `db` — resolves every pinned
pair through `SystemPromptsDao.findByNameAndVersion` and names the failure:

```
coaching.systemPromptVersion pins system_prompt (coach, v3), which the
migration-seeded catalog does not contain. Add a seed migration under
db/schema/ before pinning it.
```

Five pairs — ten config keys — read from config, never hard-coded:
`coaching.systemPromptName/Version`, `extraction.promptName/Version`,
`synthesis.promptName/Version`, `fitLens.queryPromptName/Version`,
`fitLens.reasonPromptName/Version`. `fitLens.enabled` is `false` by default and
is deliberately ignored — the pin must resolve whether or not the feature is on,
which is precisely the case RFC 124 broke.

This is the durable guard. It converts "24 rest-server tests fail with 500s"
into one named failure in the module that owns the pin.

### The defensive machinery is removed, not left in place

Each of these exists only to survive a truncation that will no longer happen,
and leaving them would preserve the false belief that the catalog is volatile:

- `OfflineCoachingE2eTest`'s four `ON CONFLICT DO NOTHING` re-inserts — deleted.
- `SystemPromptsDaoTest.ensureSeed` — deleted; the
  `findByNameAndVersion returns
  the seeded coach prompt` case reads the
  migration seed directly, which is what it was always meant to test.
- `AdminTestSupport.fitLensPromptId`'s find-or-create — becomes a plain lookup.

Their comments are replaced by the standing note the RFC-124 classes carry, so
the next reader learns the rule rather than the workaround.

## Files Modified

Test sources only. **No production code, no schema, no config changes.**

Drop `system_prompts` from the `TRUNCATE` list and delete the restore block:

- `db/src/test/kotlin/ed/unicoach/db/dao/ObservationsDaoTest.kt` (+ counter)
- `db/src/test/kotlin/ed/unicoach/db/dao/ExtractionRunsDaoTest.kt` (+ counter)
- `db/src/test/kotlin/ed/unicoach/db/dao/CommitmentsDaoTest.kt`
- `db/src/test/kotlin/ed/unicoach/db/dao/SynthesisRunsDaoTest.kt` (+ counter)
- `db/src/test/kotlin/ed/unicoach/db/dao/ClaimSupportDaoTest.kt` (+ counter)
- `db/src/test/kotlin/ed/unicoach/db/dao/StudentLlmCostDaoTest.kt` (+ counter)
- `db/src/test/kotlin/ed/unicoach/db/dao/CommitmentSupportDaoTest.kt`
- `db/src/test/kotlin/ed/unicoach/db/dao/CollegeListEntrySupportDaoTest.kt` (+
  counter)
- `db/src/test/kotlin/ed/unicoach/db/dao/ClaimsDaoTest.kt`
- `service/src/test/kotlin/ed/unicoach/coaching/collegelist/CollegeListServiceTest.kt`
  (+ counter)
- `service/src/test/kotlin/ed/unicoach/coaching/synthesis/SynthesisHandlerTest.kt`
- `service/src/test/kotlin/ed/unicoach/coaching/synthesis/SynthesisServiceTest.kt`
- `service/src/test/kotlin/ed/unicoach/coaching/CoachingServiceTest.kt` (+ body
  lookup)
- `service/src/test/kotlin/ed/unicoach/coaching/fitlens/FitLensHandlerTest.kt`
- `service/src/test/kotlin/ed/unicoach/coaching/fitlens/FitLensServiceTest.kt`
- `service/src/test/kotlin/ed/unicoach/coaching/extraction/ExtractionServiceTest.kt`
- `service/src/test/kotlin/ed/unicoach/coaching/budget/BudgetServiceTest.kt` (+
  counter)

Remove the now-obsolete self-healing:

- `rest-server/src/test/kotlin/ed/unicoach/rest/OfflineCoachingE2eTest.kt`
- `db/src/test/kotlin/ed/unicoach/db/dao/SystemPromptsDaoTest.kt`
- `admin-web/src/test/kotlin/ed/unicoach/admin/AdminTestSupport.kt`

New:

- `service/src/test/kotlin/ed/unicoach/coaching/SystemPromptCatalogTest.kt`

## Implementation Plan

1. Add `SystemPromptCatalogTest`. Confirm it passes against `main`'s catalog
   (all seven pins already resolve) before touching any fixture — a guard that
   only starts passing after the change proves nothing.
2. Remove `system_prompts` from all seventeen `TRUNCATE` lists and delete the
   restore blocks, replacing each with the standing note.
3. Convert the eight counter-based prompt-version generators to UUID-derived
   versions.
4. Replace `CoachingServiceTest.pinnedCoachBody` with a lookup of the body at
   the pinned pair.
5. Delete `OfflineCoachingE2eTest`'s re-inserts and `SystemPromptsDaoTest`'s
   `ensureSeed`; reduce `AdminTestSupport.fitLensPromptId` to a lookup.
6. Run `nix develop -c bin/test -f` — forced, because the shared test database
   is an input Gradle does not model and the defect is a cross-module ordering
   effect, so an incremental green proves nothing.

## Tests

The change _is_ test code, so the verification is behavioural, not additive.

- **Full forced suite**: `nix develop -c bin/test -f`, executed count reported.
- **The acceptance criterion, executed**: in a scratch commit, add a seed
  migration creating `coach`/`v3` and flip `coaching.systemPromptVersion` to
  `v3`. The suite must stay green **with zero test-file edits**. Revert both.
  This is the claim the RFC makes, so it is run, not asserted.
- **The guard fires**: in a second scratch commit, flip the pin to a version
  with no row and confirm `SystemPromptCatalogTest` fails with the named message
  rather than the suite failing with 500s elsewhere. Revert.
- **Order independence**: the forced full-suite run exercises the real
  cross-module ordering on one shared database, which is the condition RFC 124's
  breakage required.
