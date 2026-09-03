# 0005 research — data & index feasibility for an out-of-state price axis

Scope: READ-ONLY. Brief 0005, the slice RFC 157 D-G reported to /chart.
Question: **can the search index carry an out-of-state price axis at all, and
what does it cost?**

## METHOD

Four evidence sources, named so each claim can be re-run:

1. **Repo reading** — `db/schema/*.sql`,
   `db/src/main/kotlin/ed/unicoach/db/dao/CollegesDao.kt`,
   `db/src/main/kotlin/ed/unicoach/db/models/College.kt`,
   `service/src/main/kotlin/ed/unicoach/coaching/costs/*`, and the rebuild test.
   Every repo claim below carries `file:line` at the state of the working tree
   read on this run.
2. **The committed real fixture** —
   `college/src/test/resources/scorecard-institutions-real-fixture.csv`, read
   with pandas. It holds **4 rows**. Stated as unusable for coverage, not
   extrapolated.
3. **A real national Scorecard snapshot** — downloaded fresh for this research
   (not committed): `Most-Recent-Cohorts-Institution_05192025.csv`, 6,429 rows,
   resolved through the data.ed.gov CKAN API
   (`package_search?q=College Scorecard`) to
   `https://ed-public-download.scorecard.network/downloads/Most-Recent-Cohorts-Institution_05192025.zip`.
4. **The live local dev database** — `unicoach-dev-unicoach`, which already
   holds this repo's pinned snapshot: 6,273 `colleges` rows and 6,273
   `college_search_index` rows. Queried through the project's own harness
   (`nix develop -c bin/db-query`, read-only role; `nix develop -c bin/db-write`
   for two timing runs wrapped in `BEGIN … ROLLBACK`).

**The dev DB was not modified.** Checksum before and after the two timing runs:
`select md5(string_agg(t::text,'|' order by college_id)), count(*) from college_search_index t`
→ `512900069a62f1b98dbc873c751fd88b|6273` both times.

**What was NOT available, stated honestly:**

- The live Scorecard API (`api.data.gov`) has no key in this repo
  (`grep -i key .env.template` shows client keys only). `DEMO_KEY` worked for 8
  calls and then returned `429 OVER_RATE_LIMIT`, so the aggregate coverage
  counts below come from the **downloaded snapshot and the dev DB**, not from
  API count queries. Two `DEMO_KEY` calls that did land are cited where used.
- Web search is not configured in this session (`websearch` returns "no Serper
  API key"), so no external claim rests on a search result.
- **End-to-end rebuild wall time was not measured.** Statement 2 of the rebuild
  is a Kotlin string with `$CONTROL_CASE` / `$SECTOR_CASE` interpolations
  (`CollegesDao.kt:2305-2385`); re-running it honestly means re-running the
  ingest, which writes. Statement 3 — the percentile pass, the statement a new
  ladder actually changes — WAS measured verbatim. Said plainly rather than
  guessed.

---

## 1. The price-bearing columns `colleges` holds today

Thirteen money columns, in three groups.

### The two blended, in-state-only figures (the defect)

| column                            | Scorecard                   | schema                                                                                                                                                                                    | Kotlin                                       |
| --------------------------------- | --------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------- |
| `cost_of_attendance_per_year_usd` | `COSTT4_A`                  | `db/schema/0015.create-colleges.sql:49` (as `cost_attendance`), renamed `0059.name-numeric-columns-by-unit.sql:55`, comment restated `0075.name-blended-figure-residency-basis.sql:37-44` | `College.kt:30` `costOfAttendancePerYearUsd` |
| `net_price_per_year_usd`          | `NPT4_PUB` else `NPT4_PRIV` | `0015:50`, renamed `0059:56`, comment `0075:49`                                                                                                                                           | `College.kt:31` `netPricePerYearUsd`         |

`0075:41-44` now states the basis in the schema itself: _"IN-STATE BASIS: at a
PUBLIC institution this is reported for students paying the in-state … tuition
rate, so it does NOT describe a family from another state."_

### The two residency-selected tuition columns

| column                                       | Scorecard        | schema                       | Kotlin          |
| -------------------------------------------- | ---------------- | ---------------------------- | --------------- |
| `tuition_and_fees_in_state_per_year_usd`     | `TUITIONFEE_IN`  | `0015:51`, renamed `0059:57` | `College.kt:41` |
| `tuition_and_fees_out_of_state_per_year_usd` | `TUITIONFEE_OUT` | `0015:52`, renamed `0059:58` | `College.kt:42` |

**These are the only two residency-bearing columns in the schema**, which is RFC
157's settled finding restated from the code side, not re-litigated.

### The cost components — SIX, not seven

`db/schema/0062.add-college-cost-components.sql:33-39`, and `0062:15-16` says so
in its own words: _"Six, not seven: the Scorecard publishes no
`ROOMBOARD_FAM`."_

| column                                     | Scorecard          | Kotlin          |
| ------------------------------------------ | ------------------ | --------------- |
| `housing_and_food_on_campus_per_year_usd`  | `ROOMBOARD_ON`     | `College.kt:54` |
| `housing_and_food_off_campus_per_year_usd` | `ROOMBOARD_OFF`    | `College.kt:55` |
| `books_and_supplies_per_year_usd`          | `BOOKSUPPLY`       | `College.kt:56` |
| `other_expenses_on_campus_per_year_usd`    | `OTHEREXPENSE_ON`  | `College.kt:57` |
| `other_expenses_off_campus_per_year_usd`   | `OTHEREXPENSE_OFF` | `College.kt:58` |
| `other_expenses_with_family_per_year_usd`  | `OTHEREXPENSE_FAM` | `College.kt:59` |

**Correction to the task brief, evidence-backed:** the brief says "seven cost
components". There are six component columns. An _arrangement total_ has three
or four LINES — a tuition line plus that arrangement's own components — and the
arrangement→component map is `CostBreakdown.kt:31-51`:

- `ON_CAMPUS` = housing-on + books + other-on (3 components + tuition = 4 lines)
- `OFF_CAMPUS` = housing-off + books + other-off (4 lines)
- `WITH_FAMILY` = books + other-family (2 components + tuition = **3 lines**, no
  housing line, and `CostBreakdown.kt:19-22` says that is data, not an omission)

A total exists only when the tuition line is present AND every one of that
arrangement's own components is present — `CostBreakdown.kt:170-172`:
`if (tuitionLine != null && componentLines.size == arrangement.components.size) lines.sumOf { … } else null`.
"All seven components" in RFC 157's test is the union across arrangements (6
components + the applicable tuition), which is the shape of the fixture
invariant, not a column count.

### The NPT41..45 income bands

`db/schema/0045.add-college-income-band-net-price.sql:24-28`
(`net_price_q1..q5`), renamed to `net_price_per_year_income_q1..q5_usd` at
`0059:62-66`; Kotlin `College.kt:36-40` (`netPricePerYearIncomeQ1Usd` …
`Q5Usd`); comments restated in-state at `0075:66-…`.
`0022.drop-college-net-price-nonneg-check.sql:16` is why net price may be
negative — relevant below, because a price ladder cannot assume non-negative.

### What the index carries

`db/schema/0064.create-search-index-and-subjects.sql:138` —
`net_price_per_year_usd INTEGER NULL` — and `0064:191` —
`net_price_percentile_share DOUBLE PRECISION NULL`. **No tuition column and no
component column is in the index at all.** Confirmed by absence:
`grep tuition db/schema/0064*` returns nothing.

---

## 2. COVERAGE — measured

### The committed fixture is too small to settle anything

`scorecard-institutions-real-fixture.csv` has **4 data rows**
(`pandas .shape == (4, 3308)`):

| UNITID | name                   | ST | CONTROL | COSTT4_A | NPT4_PUB | TUITIONFEE_IN | TUITIONFEE_OUT | ROOMBOARD_ON | BOOKSUPPLY | OTHEREXPENSE_ON |
| ------ | ---------------------- | -- | ------- | -------- | -------- | ------------- | -------------- | ------------ | ---------- | --------------- |
| 100830 | Auburn U at Montgomery | AL | 1       | 21353    | 13224    | 9700          | 20668          | 7368         | 1500       | 4545            |
| 125028 | Ventura College        | CA | 1       | 9306     | −982     | 1426          | 9615           | —            | 1062       | —               |
| 136455 | Pensacola Christian    | FL | 2       | —        | —        | —             | —              | —            | —          | —               |
| 110680 | UC San Diego           | CA | 1       | 38701    | 12470    | 16758         | 50958          | 18970        | 1286       | 5888            |

(Row 110680 is the RFC 157 argument row, verbatim. `TUITIONFEE_OUT = 50958`
against `TUITIONFEE_IN = 16758` — a $34,200 differential the search index cannot
see.)

Three `CONTROL=1` rows. Two of them (100830, 110680) carry a complete on-campus
set; Ventura College has no `ROOMBOARD_ON` and no `OTHEREXPENSE_ON`, so it has
no on-campus total and would be **unrankable on an on-campus out-of-state
ladder** — a useful shape, and exactly one row of it. **SAY SO PLAINLY: n=3
publics is not a coverage measurement.** RFC 157's own Open Items already says
`n = 2` is not a corpus. To settle coverage you need a national snapshot — which
is what the next two subsections use.

### Coverage on a real national snapshot (6,429 rows, 05/19/2025)

Universe approximated as `CURROPER=1 AND ICLEVEL=1` (active, 4-year-or-more) —
the closest Scorecard-only stand-in for the default universe, whose real
definition reads IPEDS (`CollegesDao.kt:77-88`). Counts, computed with pandas:

```
universe (CURROPER=1, ICLEVEL=1)              2971
  public (CONTROL=1)                           867
  private nonprofit / for-profit              2104

universe with any NPT4                        2210   (74.4% — today's price ladder)
public with NPT4_PUB                           774   (89.3% of publics)
public with COSTT4_A                           774
public with TUITIONFEE_OUT                     806   (93.0% of publics)

public, out-of-state ON-CAMPUS total buildable    590   (68.1% of publics)
public, out-of-state OFF-CAMPUS total buildable   769
public, out-of-state WITH-FAMILY total buildable  771
public, ANY of the three buildable                774
public, ALL three buildable                       585

public with NPT4_PUB and at least one OOS total   771
public with NPT4_PUB but NO OOS total               3
public with an OOS total but NO NPT4_PUB            3
```

Two facts worth pulling out:

- **The out-of-state price is buildable for essentially every public that has a
  net price today** (771 of 774 overlap; 3 lost, 3 gained). So option (a) below
  does not shrink WHICH publics are priced — it shrinks which are priced
  _on-campus specifically_ (590 vs 774).
- **The on-campus arrangement is the weak one** (590), because `ROOMBOARD_ON` /
  `OTHEREXPENSE_ON` are the two most-often-missing components. Off-campus (769)
  and with-family (771) are near-complete.

### Coverage on the local dev database (this repo's pinned snapshot)

```
nix develop -c bin/db-query -x "WITH u AS (SELECT c.*, i.control AS icontrol
  FROM colleges c JOIN college_search_index i ON i.college_id = c.id
  WHERE i.is_active AND i.is_four_year IS NOT FALSE
    AND i.sector IS DISTINCT FROM 'administrative_unit') SELECT …"
```

Real output:

```
universe_rows       3264
public_rows          967
with_net_price      2224
public_net_price     775
public_tuit_out      885
public_oos_on_total  594     -- tuition_out + housing_on + books + other_on all present
any_oos_on_total    1684     -- same test over the whole universe (privates included)
```

The two independent measurements agree within a few dozen rows (the dev DB's
universe is larger because `is_four_year IS NOT FALSE` keeps unknown-level rows,
`CollegesDao.kt:78`). **Coverage is settled: an out-of-state arrangement total
is buildable for ~68% of public 4-years, and for ~89% of publics if any
arrangement counts.**

### How big is the defect the ladder is ranking wrong?

Public 4-years on the dev DB with both tuition figures: **885**. Of those, **98
have `tuition_in = tuition_out`** (no differential — for them the defect is
nil). Median out-minus-in gap **$9,159**; mean **$9,935**. On the national
snapshot the same gap has quartiles p25 $5,508 / p50 $9,492 / p75 $13,276 / p90
$19,973.

And for the published price specifically, on the 590 publics with a complete
on-campus set:
`(TUITIONFEE_OUT + ROOMBOARD_ON + BOOKSUPPLY + OTHEREXPENSE_ON) − COSTT4_A` has
median **$13,990** (p25 $9,850, p75 $19,602). That is the size of the number a
non-resident family is not being shown.

---

## 3. The rebuild path — what it is, and what one more ladder costs

### Shape

`CollegesDao.rebuildSearchIndex` (`CollegesDao.kt:2270-2285`) is **four
statements**: `DELETE` → `insertIndexRows` (`:2288-2385`) → `rankPercentiles`
(`:2398-2437`) → `ANALYZE`.

The index today is **26 columns** (`0064:88-201`), 6,273 rows, with **10
indexes** including the PK (`0064:255-275`).

### Measured size, from the dev DB

```
nix develop -c bin/db-query -r "select pg_size_pretty(pg_total_relation_size('college_search_index')),
  pg_size_pretty(pg_relation_size(…)), pg_size_pretty(pg_indexes_size(…)), count(*)"
→ 22 MB | 10 MB heap | 11 MB indexes | 6273 rows
```

Per-index, real output:

```
college_search_index_subject_slugs_idx        4720 kB
college_search_index_cip_codes_idx            4360 kB
college_search_index_completion_rate_idx       504 kB
college_search_index_admission_rate_idx        440 kB
college_search_index_pkey                      416 kB
college_search_index_net_price_idx             296 kB
college_search_index_athletic_associations_idx 296 kB
college_search_index_enrollment_idx            272 kB
college_search_index_universe_idx              192 kB
college_search_index_state_idx                 160 kB
```

**So the real cost of adding one INTEGER price column + one DOUBLE percentile
column + one btree:** heap grows by 12 bytes/row × 6,273 ≈ **75 kB** before
alignment padding (< 1% of the 10 MB heap); the new btree is the twin of
`college_search_index_net_price_idx`, i.e. **≈ 296 kB** — and less, because it
covers 1,684 rows rather than 2,224. Total ≈ **0.4 MB on a 22 MB table**. Two
GIN indexes over the program arrays account for 9 MB of the existing 11; a price
btree is noise beside them.

### Measured rebuild time — statement 3, verbatim

The percentile `UPDATE` (`CollegesDao.kt:2400-2437`) was run verbatim inside
`BEGIN … ROLLBACK` via `bin/db-write`, three times, `\timing on`:

```
4 ladders (today):  UPDATE 3264   229.4 ms | 92.1 ms | 205.2 ms
5 ladders (+ an out-of-state on-campus price ladder over the same universe CTE):
                    UPDATE 3264   188.1 ms |205.4 ms | 141.2 ms
```

**The fifth ladder is inside the run-to-run noise.** Both forms touch the same
3,264 rows; the extra work is one more sorted `percent_rank()` over ≤ 3,264
values and one more `LEFT JOIN` on `college_id`. Add the extra summed expression
to `insertIndexRows`' SELECT list and it is three more column reads on a row
already being read from `colleges` (the join is already there —
`CollegesDao.kt:2365` `FROM colleges c`). In real terms: **a second price ladder
costs single-digit milliseconds and ~0.4 MB.**

The cost of this slice is **not** performance. It is the DDL gate and the
correctness of the percentile semantics — question 4.

---

## 4. THE PINNED PERCENTILE CORPUS — the sharp question

### What exactly is pinned

Three things, and they are different pins:

**(i) The corpus definition itself is one expression, not two copies.**
`private object DefaultUniverse` (`CollegesDao.kt:52-93`) declares three axes —
`ACTIVE` (`:77`), `FOUR_YEAR` (`:80`), `NOT_ADMINISTRATIVE` (`:86-87`) — and
`sql(prefix)` (`:92`) renders all three. The percentile pass takes it **whole**:
`WHERE ${DefaultUniverse.sql("i.")}` (`CollegesDao.kt:2409`). The docstring at
`:2393-2396` states the rule: _"Rows OUTSIDE the default universe are never
touched and keep NULL."_ The
`UPDATE … FROM universe u … WHERE
t.college_id = u.college_id` (`:2428-2436`) is
what enforces it — a row outside the CTE is never in the update's row source.

**(ii) A test pins the two corpora to each other.**
`CollegeSearchIndexRebuildTest.kt:634` —
`` `the percentile corpus is exactly the default search universe` ``. It seeds
two ordinary four-years plus one out on **each** axis (a system central office
`sector=0`, a two-year `instLevel=2`, a closed school `cyActive=false`), runs
the rebuild, then runs `CollegesDao.search(session, CollegeQuery(limit = 25))`
and asserts, per unit id (`:670-678`):
`assertEquals(unitId in searched, ranked, "a percentile must describe exactly
the corpus the default search returns")`.
Its docstring (`:628-632`) records why: when the universe was written twice the
copies _had already diverged on `sector`_.

**(iii) Two further pins constrain any new column.**
`` `percentiles are computed over the default universe only and are NULL outside it` ``
(`:573`), and `` `rebuilding twice reproduces every column of every row` ``
(`:686`) — which snapshots **every column with no exclusions** (`:715-717`). A
new column joins that snapshot automatically. Also `0064:194-201`, the
`CHECK … BETWEEN 0 AND 1` over all four percentile columns; a fifth must be
added to that CHECK or it is unpoliced.

### What a second price axis must answer to

Pin (ii) is a pin on **membership**, not on the ladder's input: "ranked on
enrollment" ⇔ "in a default search". A second price column would satisfy it
trivially if it ranks over `DefaultUniverse` and NULLs elsewhere — the test
reads only `undergrad_enrollment_percentile_share`. **So the pin as written
would NOT catch the real hazard, and that is the finding.**

### Would a ladder over a SMALLER population break the pin, or make percentiles incomparable?

**It would not break the pin. It WOULD make the percentiles incomparable —
measurably.**

Two distinct things must not be confused:

_Restricting the CORPUS_ (ranking only schools that have out-of-state data, i.e.
dropping non-qualifying rows from the `universe` CTE) **would** break pin (ii):
a school in a default search would carry no percentile on any axis. Refuse that.

_Restricting the LADDER's population_ (keeping the corpus, ranking only the rows
whose input is non-NULL) is **exactly what the code already does** —
`WHERE net_price_per_year_usd IS NOT
NULL` (`:2427`), and pinned as correct by
`` `a row missing one percentile input still ranks on the others` `` (`:603`). A
new ladder follows that pattern and breaks nothing structural.

The damage is elsewhere: **`percent_rank()` is corpus-relative, and the
similarity distance subtracts two percentiles.** `createDistanceTerm` /
`AnchoredAxis.Price` (`CollegesDao.kt:1283-1291`) reads
`net_price_percentile_share` for both the candidate and, via the anchor's own
stored value, the anchor; `createPercentileTerm` yields
`abs(<candidate percentile> − ?)`. Mixing an anchor's percentile from ladder A
with a candidate's from ladder B is arithmetic on two different rulers.

Measured on the dev DB (one query, `percent_rank()` reproduced exactly as
Postgres computes it in the rebuild):

```
ladder_net_pop                        2224   -- today's net-price ladder
ladder_oos_pop                        1684   -- out-of-state on-campus published total
both_pop                              1650
mean |p_net − p_oos|                  0.154
max  |p_net − p_oos|                  0.899
mean |p_instate_oncampus − p_oos|     0.077   -- SAME measure, only residency changed
   … publics only                     0.122
   … privates only                    0.052
```

Independently on the national snapshot (pandas, `(rank−1)/(n−1)`, matching
Postgres): mean `|p_net − p_oos| = 0.149`, median 0.112, p90 0.324, max 0.772;
Kendall τ between the two orderings **0.561** → **~22% of all school pairs would
be ordered the opposite way**. On the same-measure in-state-vs-out-of-state
comparison τ = 0.816 (~9% of pairs; 18% among publics alone).

**The killer detail, and the reason this is the sharp question:** on the
in-state→out-of-state comparison the **privates move too — mean 0.052 — even
though not one private's price changed.** Their percentile moved because the
publics moved past them. That is the whole of "percentiles are corpus-relative"
in one number. It means:

- A second price ladder is **not** a drop-in that only affects publics.
- Percentiles from two ladders can never be mixed in one distance term, one
  filter, or one spoken sentence. A slice that adds a ladder must also make the
  tool CHOOSE a ladder for the whole query — anchor and candidates together —
  and say which one it chose.
- The existing pin (ii) does not test this. A new pin is needed: _no query reads
  two price percentile columns_, and/or _the anchor's percentile and the
  candidates' come from the same column_. State that as a required test in the
  spec, not as an afterthought.

One more constraint on any ladder built by addition: `0022:16` dropped the
non-negative check on net price because a net price is legitimately negative
(the fixture's Ventura College at −982). A _published_ out-of-state total is a
sum of gross components, all with nonneg CHECKs (`0062:46-58`), so it cannot be
negative — a real difference in the two ladders' domains, and another reason
they are not interchangeable.

---

## 5. Options, ranked

The honesty rule that binds all of them (RFC 149 D-F rule 2 / RFC 157 Design 8):
**never form an out-of-state figure by adding tuition to a blended average.**
`ForbiddenCostArithmeticTest` enforces the subtraction half of that rule
textually — but note its scope:
`private val sourceDirectory = File("src/main/kotlin/ed/unicoach/coaching/costs")`
(`ForbiddenCostArithmeticTest.kt:30`). **It scans the `:service` cost package
only. It does not scan `:db`, and a new expression in `CollegesDao`'s rebuild
SQL is outside it.** Any option that puts price arithmetic in the rebuild ships
that arithmetic unguarded unless the slice extends the scan. Say so in the spec.

### (a) One extra column `out_of_state_price_per_year_usd`, built as the cost surfaces build arrangement totals — **RECOMMENDED**

_What it is:_
`tuition_and_fees_out_of_state_per_year_usd + <that arrangement's components>`,
all-or-nothing (NULL if any part is missing), mirroring
`CostBreakdown.kt:170-172`. Plus one percentile ladder and one btree.

_Cost, measured:_ ≈ 0.4 MB; fifth ladder inside timing noise (§3). One DDL
migration → Ian's gate.

_Can it be honest?_ Yes — this is the ONLY option that is a published price for
a non-resident, and it is the same construction the Family Cost Report already
prints (RFC 157's UCSD table: on 77,102 · off 77,659 · family 59,923). No
forbidden arithmetic: it is a sum of published parts, never a blend plus a
tuition.

_What breaks / what it must answer:_

- **Which arrangement?** On-campus covers 590 publics / 1,684 universe rows;
  off-campus and with-family cover more. The column must name its arrangement
  (`…_on_campus_…`) or it is a fifth unlabelled blend — the exact class of
  defect RFC 157 exists to end.
- **It is a PUBLISHED price; today's axis is a NET price.** They are different
  measures (τ = 0.561, §4). Adding it does not "fix"
  `net_price_percentile_share`; it adds a second, incomparable axis. The spec
  must decide what `sortBy=net_price` and "but cheaper" (`CollegesDao.kt:1208`,
  `:1906`) select, and forbid mixing.
- Privates: they publish one price, so their cell is the same number under both
  ladders — but their **percentile still moves** (mean 0.052). Not a no-op for
  them.
- The `0064:194-201` percentile CHECK must gain the fifth column.
- `NON_NULL_COUNTABLE_COLUMNS` (`CollegesDao.kt:2526-2557`) is a `colleges`
  allowlist and is unaffected — the new column is on the index, not on
  `colleges`.

### (b) `tuition_in` / `tuition_out` columns only, caller combines — **SECOND, and a good fallback**

_Cost:_ two INTEGERs (~50 kB) and no new ladder at all if no percentile is
added. Coverage is the best of any option: 885 publics on the dev DB carry both
tuitions (93%).

_Can it be honest?_ **Only if the caller never adds them to a blended figure.**
Handing a tuition to a caller that also holds `net_price_per_year_usd` is
handing it both halves of the arithmetic RFC 149 forbids — and, per the scope
note above, the guard does not reach the SQL layer or the `:college` tool layer.
It is honest as a **filter/label** input ("this public charges you the
out-of-state rate, $50,958") and dishonest the moment it becomes a ranking
number pretending to be a price.

_What breaks:_ nothing structurally; no percentile column, so no ladder problem,
so pin (ii) is untouched. But it does not fix cheapest-first sort or
`cheaper_than_anchor`, which are the actual defect. It is a _label_ fix, the
search-side twin of RFC 157 Design 9/10 — good, and not sufficient.

### (c) No new column; filter/rank only within a residency-consistent subset — **THIRD; honest but small**

_What it is:_ when residency is known, restrict a price-ranked query to schools
whose in-state figure IS this family's — i.e. privates plus publics in the
family's own state — and withhold the price axis elsewhere, reusing
`UnknownAxis` / `excluded_unknown` (`CollegesDao.kt:686`, `:1208`).

_Cost:_ **zero DDL, zero gate, zero rebuild change.** Cheapest option by far.

_Can it be honest?_ Yes, and it is the search-side analogue of RFC 157 D-A
(withhold, do not caveat).

_What breaks:_ the result set collapses. Publics per state on the dev DB: **mean
16.7, min 1, max 67, over 58 states/territories** (query in §2 method). A WA
family's price-ranked universe becomes ~2,297 privates + ~20 in-state publics,
and "like Bowdoin but cheaper" silently stops considering out-of-state publics —
which for many families are the affordable ones. It trades a wrong answer for a
narrow one. Good as an **interim** while (a) waits for the gate; poor as the
destination.

### (d) Options found in the code that the brief did not list

**(d1) An `is_price_residency_sensitive` boolean on the index (1 byte/row).**
Derived: `control = 'public' AND tuition_out > tuition_in`. Measured: **98 of
885 publics have no differential**, so this flag is genuinely informative, not
just a restatement of `control`. Cheapest possible thing that lets the tool say
"this number is not yours" without a second ladder. Still DDL, but one boolean.
Pairs well with (c).

**(d2) Rank on ONE ladder, expose the other as a raw number only.** Keep exactly
one `price_percentile_share` (whatever the spec picks), and carry the second
price as an INTEGER with **no percentile at all**. Filters and cheapest-first
can use the raw column (`net_price_per_year_usd <= ?` is a raw comparison,
`:686`); only the similarity distance needs a percentile. This sidesteps §4's
incomparability entirely, because there is only ever one ruler. **This is the
cheapest way to get (a)'s honesty without (a)'s hardest problem, and it deserves
to be in the spec's option set.**

**(d3) Do nothing in the index; label at the boundary only.** Already shipped by
RFC 157 Design 9/10 (`FitLensService`, `CollegeMatchRow.NET_PRICE_BASIS_NOTE`).
Zero cost, and the honest floor. The reason it is not enough: a label cannot
change a `WHERE` clause, and RFC 157 D-A's own argument — a labelled number
beside a family's name is read as theirs — applies harder here, where the number
decides which schools the family ever SEES.

### Ranking

1. **(a) + (d2)** — one named, arrangement-specific out-of-state price column;
   one ladder, chosen per query, never mixed. Honest, ~0.4 MB, ~0 ms, one DDL
   gate.
2. **(b)** — tuition in/out as labels and filters. Cheap, high coverage, does
   not fix ranking.
3. **(c) / (d1)** — withhold or flag rather than re-rank. Zero-to-tiny cost,
   honest, narrow.
4. **(d3)** — the floor, already shipped.

Anything that adds a tuition to `cost_of_attendance_per_year_usd` or to
`net_price_per_year_usd` is out, at any coverage, at any cost.

---

## Evidence index (re-runnable)

- Fixture:
  `pandas.read_csv('college/src/test/resources/scorecard-institutions-real-fixture.csv')`
  → shape `(4, 3308)`.
- National snapshot: CKAN
  `https://data.ed.gov/api/3/action/package_search?q=College+Scorecard` →
  resource `Most Recent Institution Level Data` →
  `https://ed-public-download.scorecard.network/downloads/Most-Recent-Cohorts-Institution_05192025.zip`
  (24,026,521 bytes; CSV 103,136,944 bytes; 6,429 rows).
- Live API, the two calls that landed before the rate limit:
  `GET https://api.data.gov/ed/collegescorecard/v1/schools?school.ownership=1&fields=id&api_key=DEMO_KEY`
  → `"total":2047`; adding `latest.cost.tuition.out_of_state__range=0..` →
  `"total":1689` (all public institutions, all levels — consistent with the
  4-year figures above). Further calls returned `429 OVER_RATE_LIMIT`.
- Dev DB: `nix develop -c bin/db-query` / `bin/db-write` against
  `unicoach-dev-unicoach`, 6,273 index rows; index checksum identical before and
  after the timing runs.
