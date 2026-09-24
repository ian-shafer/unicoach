# RFC 230 — `FafsaDeadlineService`: one answer for both FAFSA doors

Slice `fafsa/03/one-answer-for-both-doors` (brief 0012). Lane A.

The one read service the public `/fafsa` page (`fafsa/04`) and the coach
(`fafsa/05`) both render, so the two doors cannot disagree (D4). It reads the
seeded `fafsa_filing_deadlines` table (RFC 215, RFC 223), decides the live set
per jurisdiction (D23), refuses with one of three named arms when there is no
live answer, and returns the region grouping and the jurisdictions we hold
nothing for. It renders nothing: every value is typed, no English sentence is
authored here.

## Detailed Design

`FafsaDeadlineService` in `:service`, package `ed.unicoach.coaching.aid`, beside
`FederalAidPolicyService`. Constructor
`(database: Database, clock: InstantSource
= InstantSource.system())`; the day
is computed in the ZONE THE DEVICE SUPPLIES, and the read is
`suspend fun get(zone: ZoneId? = null): Result<FafsaDeadlines>`. A null zone —
the device did not say — falls back to `America/Los_Angeles`. The zone is
resolved in ONE home (the service's `FALLBACK_ZONE` constant), and the day is
`LocalDate.ofInstant(clock.instant(), zone ?: FALLBACK_ZONE)`, passed to
`FafsaFilingCycle.currentFilingYear(LocalDate)`.

The read is `Result<FafsaDeadlines>` in one `SqlSession`, exactly the
`TestTimelineService`/`FederalAidPolicyService` shape: `CancellationException`
re-thrown, every other failure a `Result.failure`, no English sentences, no
rendering. That session read is a second, `internal`, member —
`internal fun readInSession(session: SqlSession, zone: ZoneId?): FafsaDeadlines`
— and `get` is exactly it plus the connection and the `Result` wrapper. It is
declared here because it is the seam the `READ_STATEMENTS = 2` assertion below
counts statements through; a reader of this design would otherwise not know the
surface has two members.

### The answer

A sealed live-or-refusal, the `TestTimeline` shape (no nullable "expired" flag
that can sit stale beside a date):

```kotlin
enum class NoDeadlinesReason {
  EMPTY,             // the table is empty; "out of date" would be a false statement
  SUPERSEDED_CYCLE,  // rows are healthy and unexpired, but every one belongs to a cycle before the one being filed
  EXPIRED,           // the live cycle's rows, no longer believed: expires_on has passed
}

sealed interface FafsaDeadlines {
  data class NoDeadlines(
    val heldAwardYears: List<AcademicYear>,  // distinct cycles we hold, oldest first; empty for EMPTY
    val reason: NoDeadlinesReason,
  ) : FafsaDeadlines

  data class Resolved(
    val asOf: LocalDate,                    // today, in the caller's device zone (fallback America/Los_Angeles)
    val filingYear: AcademicYear,           // the cycle the clock names; NOT a claim the other cycle is shut (D22)
    val window: FafsaDeadlineWindow,        // non-nullable: a window that exists is one we are inside
    val federal: List<FafsaDeadline>,       // the federal jurisdiction's live rows
    val states: List<StateDeadlines>,       // one entry per served state, region-attached
    val missingJurisdictions: List<DeadlineJurisdiction.State>, // the served jurisdictions we hold nothing for; never a silent gap
  ) : FafsaDeadlines
}

data class FafsaDeadlineWindow(
  val cycles: List<AcademicYear>,  // distinct cycles present in the live set, oldest first; several present are all named
  val expiresOn: LocalDate,        // earliest expires_on over the live rows
)

data class FafsaDeadline(
  val awardYear: AcademicYear,     // the cycle THIS row names (D23: name the cycle in the row)
  val program: String,
  val event: AidDeadlineEvent,
  val deadline: PublishedDeadline, // the sealed published value, reused as-is
  val publisher: String,
  val sourceUrl: String,
  val archiveUrl: String?,
  val checkedOn: LocalDate,        // the day we read it; the page's "as of"
)

data class StateDeadlines(
  val jurisdiction: DeadlineJurisdiction.State,  // the typed USPS code
  val name: String,                               // us_states.name
  val region: String,                             // ipeds_regions.name, the group label the page scans by
  val deadlines: List<FafsaDeadline>,             // this state's live-cycle rows, in DAO order
)
```

Jurisdiction is carried by the container, not repeated per row: `federal` rows
are all federal, and `StateDeadlines.jurisdiction` names the state. The renderer
therefore never re-derives the region map — it is attached here — which is the
"page and coach never re-derive anything the service decided" rule from the
slice.

### The live set (D23)

Rows are committed only when verified (D9), so "newest cycle we VERIFIED per
jurisdiction" is, in the store, "newest `award_year` per jurisdiction". For each
jurisdiction the service keeps only its newest cycle's rows. With the committed
corpus that is 11 states on 2027-28 and 36 on 2026-27, plus 4 federal rows — and
every row names its own cycle (`awardYear`), so a mixed-cycle page is honest
rather than a rot nobody can see.

The three arms are decided in this order:

1. No rows at all → `EMPTY` (held cycles empty).
2. `max(award_year over all rows) < currentFilingYear(today)` →
   `SUPERSEDED_CYCLE`. This is the acceptance criterion: the filing cycle has
   moved past the seeded corpus, so serving last cycle's dates is refused rather
   than rendered. The seed guard (RFC 223) fails the build first; this is the
   runtime backstop.
3. The live rows' earliest `expires_on` is BEFORE today → `EXPIRED`
   (`heldAwardYears` = the cycles the TABLE holds, as on every arm, never the
   live subset alone: one field name, one question, one answer). The expiry day
   ITSELF still answers: a row good "through 31 July" is believed on 31 July,
   which is `TestTimelineService`'s own predicate (`today > expiry`) for the
   same question and what `expires_on`'s own schema comment states ("Past
   `expires_on` the coach REFUSES to name a date" — past, not on).

Otherwise `Resolved`, with `window.expiresOn` = the minimum `expires_on` over
the live rows (the `TestTimelineService` rule: an answer is only as current as
its least current row) and `window.cycles` = the distinct live cycles, oldest
first.

Every refusal is LOGGED, one `warn` per arm, carrying the arm, `today`, the
filing year, the held cycles and the earliest live expiry as slf4j properties
(the `FederalAidPolicyService` precedent). An absent answer is an operator
problem — "load the corpus", "seed the next cycle" and "re-verify the corpus"
are three different fixes — and nothing else records which one it is. The SERVED
type does not change: those facts reach the operator's log, never the wire.

`missingJurisdictions` = every `us_states` row whose `jurisdiction_kind` is
`state` or `federal-district` (the 50 states + DC, D13) whose USPS code has no
live row, in `us_states.name` order. Today that is AR, DE, NC, NH. Territories
(PR/GU/VI) are deliberately not in this list: they are out of scope (D13), a
different fact the page states, not a gap.

It is TYPED — `DeadlineJurisdiction.State`, the same value
`StateDeadlines.jurisdiction` carries — so the two halves of one answer are
comparable without a convention and a door cannot mis-key a gap with a state
name or a lowercased code. The typing is TOTAL, not a narrowing:
`db/schema/seed-published-codebooks-r1.sql` seeds exactly the 59
`us_states.usps_code` values `UsStateCodes.ALL` names, and RFC 229 keyed the
state columns to `us_states`, so every code that can reach this list is one
`DeadlineJurisdiction.State.of` admits. A `us_states` code it did not admit
would be the codebook and the model having diverged, and the service throws
saying so rather than serving a code no door can key.

The same D13 rule is applied to the SERVED half too:
`DeadlineJurisdiction.State` admits all 59 codes, so a deadline row for a
territory would otherwise be rendered as a served state while this list insists
territories are out of scope. Such a row is a harvest error, and the service
refuses it by name.

### DAO changes

`FafsaFilingDeadlineDao.listAll(session)` — one statement returning every row,
`ORDER BY state ASC NULLS FIRST, award_year DESC, program, event`. The
"deliberately no `listAll`" note is narrowed, not reversed: the service is the
one reader that may see all cycles, because it is the one that DECIDES the live
set (D23); every renderer still reads only what the service returns. The table
is append-only and small (128 rows today), so one statement reading it whole is
the honest cost of newest-per-jurisdiction.

`CodebooksDao.usStatesWithRegion(session)` returning a new
`UsStateRegion(uspsCode, name, jurisdictionKind, region)` — one INNER JOIN of
`us_states` to `ipeds_regions`, `ORDER BY name`. The existing `usStates` read
deliberately omits the region (RFC 165); a new read rather than widening that
one, because `UsState` is the served vocabulary type and must not grow a field
no vocabulary reader wants. The `UsStateRegion` type lives beside `UsState` in
`db/src/main/kotlin/ed/unicoach/db/dao/CodebooksDao.kt`.

The service's answering path is therefore **2 statements** (the deadline read
plus the region read), asserted as `READ_STATEMENTS = 2`; the three refusal arms
return after the deadline read and never pay for the region read.

### The clock

Injected `InstantSource` for the instant (testable), but the ZONE is per
request: the caller detects the device's timezone and passes it, and a null zone
falls back to `America/Los_Angeles` (Ian, at the RFC 230 gate). Both doors must
pass the SAME zone for the SAME device, so page and coach still cannot disagree.
The day boundary — which filing year, and whether a reading has expired — is
therefore the user's own local day. `LocalDate` for `expires_on` comparisons; no
`LocalDate.now` appears in the service.

## Files Modified

- `service/src/main/kotlin/ed/unicoach/coaching/aid/FafsaDeadlineService.kt`
  (new)
- `db/src/main/kotlin/ed/unicoach/db/dao/FafsaFilingDeadlineDao.kt` (add
  `listAll`, narrow its KDoc)
- `db/src/main/kotlin/ed/unicoach/db/dao/CodebooksDao.kt` (add `UsStateRegion` +
  `usStatesWithRegion`)
- `service/src/test/kotlin/ed/unicoach/coaching/aid/FafsaDeadlineServiceTest.kt`
  (new)
- `db/src/testFixtures/kotlin/ed/unicoach/db/dao/FafsaFilingDeadlineFixture.kt`
  (test rows for `:service` suites — see Tests)

## Implementation Plan

1. Add `listAll` to `FafsaFilingDeadlineDao`, with the narrowed rationale.
2. Add `UsStateRegion` and `usStatesWithRegion` to `CodebooksDao`.
3. Write `FafsaDeadlineService` and its sealed types.
4. Write the fixture helper that seeds arbitrary deadline rows plus the
   `CodebookReferenceFixture` state/region rows for `:service` tests.
5. Write `FafsaDeadlineServiceTest` (below); run the module suite, then the full
   suite.

## Tests

`FafsaDeadlineServiceTest` in `:service`, pinning an `InstantSource` and passing
an explicit `ZoneId` per test. `CodebookReferenceFixture.seed()` supplies the 51
states/regions; deadline rows are written through
`FafsaFilingDeadlineDao.upsert` from a small fixture helper.

- **The acceptance criterion, the named failure mode**: seed only 2026-27 rows
  and clock to after 1 October (filing year 2027); the read returns
  `SUPERSEDED_CYCLE` with `heldAwardYears == [2026]` — never the 2026 dates.
- **The sibling that makes it mean something**: same rows, clocked before 1
  October, returns `Resolved` with those dates.
- **`EMPTY` vs `EXPIRED`**: empty table → `EMPTY` (empty held cycles); rows of
  the live cycle whose `expires_on` is yesterday → `EXPIRED` (held cycles
  named); one day earlier the same rows answer.
- **D23 mixed-cycle live set**: a state with 2026-27 and 2027-28 rows returns
  only its 2027-28 rows; a state with only 2026-27 returns 2026-27; each row
  carries its own `awardYear`; `window.cycles` names both cycles oldest-first.
- **The federal jurisdiction**: federal rows come back on `Resolved.federal` and
  never inside `states`, and they get the same newest-cycle-per-jurisdiction
  rule (D23) the states get.
- **Region grouping**: the state's `region` equals its `ipeds_regions.name` from
  the fixture; `states` ordered by region then name.
- **`missingJurisdictions`**: seed rows for 47 states; the list is exactly the 4
  absent codes (AR/DE/NC/NH by fixture), never silent and never the territories.
- **Statement budget**: `READ_STATEMENTS == 2` on the answering path and
  `REFUSAL_STATEMENTS == 1` on a refusal (no region read), each asserted against
  its own named constant rather than against a literal in a test body.
- **Zone**: a null zone falls back to `America/Los_Angeles`; an instant just
  before the October turnover in a zone still on the old day serves the old
  filing year, while the same instant in a zone already over the line serves the
  next one — the day, not the instant, decides.
- **Cancellation/error**: `CancellationException` re-thrown; a DB fault is a
  `Result.failure`.

No door is wired here. `fafsa/04` builds `FafsaDeadlineService(database)` at its
`Application.kt` and adds `get("/fafsa")`; `fafsa/05` wraps it in a chat tool.
Both are separate slices that `BLOCKS`-depend on this one.
