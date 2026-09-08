# RFC 169 — search on the price you would pay

Slice: `shape/05/search-on-your-price` (brief 0006, gate 2 approved 2026-09-02;
D14 decided there).\
Needs: BLOCKS `shape/01/canonical-store` — LANDED as RFC 158. PREFER
`shape/02/ipeds-ic-ay` — LANDED as RFC 161. CONFLICTS `shape/03/ipeds-sfa` —
`pipeline/rfc-162` is unlanded and holds migration `0085`; rebase risk only.

## Summary

The search index carries exactly one price column, `net_price_per_year_usd`
(`db/schema/0064.create-search-index-and-subjects.sql:138`), copied verbatim off
`colleges` (`CollegesDao.kt:2344`). That column is the Scorecard NPT4 blend: at
a public school it is the **in-state, after-federal-aid** figure. Every price
filter, every cheapest-first sort, `similar_colleges`' price axis and
`cheaper_than_anchor` therefore rank a family from another state on a price it
will never pay.

This RFC gives search a second ruler, built from the canonical money layer RFC
158/161 landed: the family's **residency-correct published on-campus total**,
materialised on the index from `price_figures`, ranked on one shared ladder, and
used for the WHOLE query when — and only when — the family's state is on file.
When it is not, search ranks on the net-price stat exactly as today, and the
basis moves into the metric NAME on the wire.

This is the first read path over `price_figures`:
`grep -rn "price_figures"
--include=*.kt service rest-server queue-worker`
returns nothing today.

## The defect, measured

Brief 0005's evidence is binding input
(`product/0006-money-in-unicoach-shape/spec.md:255-256`), and it is not close:

- **Kendall τ = 0.561** between today's net-price ladder and an out-of-state
  published-total ladder over the 2,971 operating four-year schools of the
  national Scorecard snapshot — **~22% of all school pairs order the opposite
  way**. Mean |p_net − p_oos| = 0.149, p90 0.324, max 0.772
  (`product/0005-price-you-would-pay/research/data-and-index-feasibility.md`
  §4).
- The same measurement on the dev DB: 2,224 rows on the net ladder, 1,684 on the
  out-of-state ladder, 1,650 on both, mean |Δ| 0.154, max 0.899.
- **The privates move too** — mean |Δ| 0.052 on an in-state→out-of-state swap
  where not one private's price changed. Their percentile moved because the
  publics moved past them. That is why two ladders may never be mixed in one
  distance term, one filter, or one sentence.
- **Exposure**: even for a family in Texas — the state with the most publics in
  the corpus — **32.4%** of every ranked search universe carries a price on a
  basis that is not theirs; no state gets below that and the ceiling is 34.8%
  (`research/search-surface-and-residency.md` §3b).
- **Coverage is settled**: an out-of-state on-campus total is buildable for
  68.1% of publics, and the out-of-state price exists for essentially every
  public that has a net price today (771 of 774 overlap; 3 lost, 3 gained) (§2).
- **Size**: median out-minus-in tuition
  $9,159 on the dev DB; the published-price
  gap on the complete on-campus publics has median **$13,990** — "the size of
  the number a non-resident family is not being shown".
- **Cost**: one INTEGER + one share column ≈ **0.4 MB** as measured (the btree
  that measurement included was dropped at review — nothing can use it, §4); a
  fifth ladder in `rankPercentiles` measured 188/205/141 ms against 229/92/205
  ms for the four ladders today — inside the run-to-run noise (§3).

So the argument against fixing this has to be a product argument, and brief 0006
D14 already made it the other way.

## Detailed Design

### 1. Two rulers, one per query (D1)

A search query is on exactly one **ruler**, resolved once, before any SQL is
built:

| family residency                       | ruler               | measure                                                                                                |
| -------------------------------------- | ------------------- | ------------------------------------------------------------------------------------------------------ |
| `unanswered` / `declined` / no profile | **net price**       | `cohort_money_stats`-shaped blended net price, i.e. today's `net_price_per_year_usd` column, unchanged |
| `answered` (a USPS code)               | **published price** | the residency-correct published on-campus total, per row                                               |

Under the published ruler each ROW contributes its own residency-correct value:
the out-of-state total for a public whose `state` differs from the family's, the
in-state total for a public in it, and for a private the two are the same number
because a private publishes one price. Only the **rate tier** varies per row;
the **measure** never does. That is what makes D8's "the rule is per ROW, not
per result set" implementable without the ruled-out "swap the column per school"
defect.

Aid is not in the published ruler, and there is no out-of-state net price and
never can be — subtracting an in-state average grant from an out-of-state total
is exactly the arithmetic RFC 149 forbids. So whenever the published ruler is
active the tool result SAYS so, in one sentence, never silently (§6).

One Kotlin value decides all of it. `PriceRuler` (new, `:db` models) carries the
value SQL, the ladder-share SQL, the wire word, the result key and the
`UnknownSubject`; `CollegesDao.createSearchPlan`, `orderBy`, `createFilters`,
`AnchoredAxis.Price` and `findSimilarityAnchorRow` all read it and nothing else.
A query that could read two price percentile columns cannot be constructed.

### 2. One ladder, two stored shares (D2)

The published ladder's corpus is **each school's out-of-state published
on-campus total over the default search universe** — one value per school, one
scale, `DefaultUniverse.sql("i.")` in its own words, exactly as the four
existing ladders (`CollegesDao.kt:2403-2440`).

BOTH stored published share columns are positions on **that one corpus**. The
in-state share is not a second `percent_rank()` over in-state values; it is the
in-state value's place on the out-of-state ladder. Postgres' `percent_rank()` is
`(rank − 1) / (n − 1)` where `rank − 1` is the count of strictly smaller rows,
so one expression serves both columns:

    share(v) = least(count(corpus.value < v)::numeric / (n - 1), 1.0)

evaluated at the row's own out-of-state value for one column and its in-state
value for the other. For a corpus member the two definitions are identical — a
test pins that against `percent_rank()`. Ranking by the raw residency-correct
value and by its share therefore give the same order, which is what "one ruler"
means at the distance term in `createDistanceTerm` (`CollegesDao.kt:1283-1291`),
where two ladders would have done their damage.

Coverage honesty follows brief 0005 §2.4 exactly: **do not restrict the corpus,
restrict only the ladder's population** (`WHERE <value> IS NOT NULL`), as all
four existing ladders already do. Rows outside the default universe are never
UPDATEd and keep NULL.

### 3. Value construction: all-or-nothing, never a subtraction (D3)

    published on-campus total(tier)
        = tuition_and_fees(tier, not_applicable)
        + housing_and_food(not_applicable, on_campus)
        + books_and_supplies(not_applicable, not_applicable)
        + other_expenses(not_applicable, on_campus)

mirroring `CostBreakdown.kt:170-172`. **NULL if any part is missing** — four
parts or no number. Residency lives only on `tuition_and_fees` (and
`fees_only`); every living cost is `residency_basis = 'not_applicable'`
(`0083.create-canonical-money-tables.sql`), so the tier enters through exactly
one addend.

Rules the rebuild obeys:

- **Value-bearing statuses only.** `amount_usd IS NOT NULL` is equivalent to
  `status IN ('reported','imputed_by_publisher')` by
  `price_figures_value_iff_status_check`; the four other statuses are an absence
  to report, never a zero. `imputed_by_publisher` is kept — 7 of 13 IC_AY
  X-codes map to it and excluding it would gut coverage.
- **Latest academic year per college per figure** (0006 D15, store all, serve
  latest). IPEDS carries `2020-21..2023-24`, the Scorecard writes `2022-23`, so
  "the latest year" is not one year across the table and pinning a literal year
  would silently drop every Scorecard-only college. The ladder therefore mixes
  vintages across colleges by construction; `putSourceYears` already reports a
  span rather than a single year.
- **Never a subtraction, never a blend.** The total is a sum of published
  components. No tuition is ever added to `net_price_per_year_usd` or to a
  blended average (D9 extends the guard to enforce this in the rebuild SQL).
- **Upstream-wins is inherited, not re-implemented.** `source` is outside
  `price_figures`' natural key, so exactly one source already holds each cell.
- The column NAMES its arrangement: `..._on_campus_...`. Off-campus and
  with-family arrangements, and the `in_district` tier, are not ranked here
  (§Not in this RFC).

### 4. DDL — migration `0091`

`db/schema/0091.add-search-index-published-price.sql`. **The number is
re-claimed at every rebase, not once**: RFC 162 took `0085`, RFC 166 `0086` and
RFC 170 both `0087` and `0088`, and RFC 175 both `0089` and `0090` while this
slice was open, so `0091` is what is free now — and it is re-checked before
every commit, because it has moved four times.

```sql
ALTER TABLE college_search_index
    ADD COLUMN published_price_in_state_on_campus_per_year_usd     INTEGER      NULL,
    ADD COLUMN published_price_out_of_state_on_campus_per_year_usd INTEGER      NULL,
    ADD COLUMN published_price_in_state_on_campus_ladder_share     NUMERIC(5,4) NULL,
    ADD COLUMN published_price_out_of_state_on_campus_ladder_share NUMERIC(5,4) NULL;

-- The named 4-clause percentile CHECK (0064:192-200) becomes a 6-clause one.
-- A percentile column outside it is unpoliced, so the constraint is replaced,
-- not supplemented, and keeps its name.
ALTER TABLE college_search_index
    DROP CONSTRAINT college_search_index_percentile_range_check;

ALTER TABLE college_search_index
    ADD CONSTRAINT college_search_index_percentile_range_check CHECK (
        (undergrad_enrollment_percentile_share IS NULL
            OR undergrad_enrollment_percentile_share BETWEEN 0 AND 1)
        AND (admission_rate_percentile_share IS NULL
            OR admission_rate_percentile_share BETWEEN 0 AND 1)
        AND (sat_average_percentile_share IS NULL
            OR sat_average_percentile_share BETWEEN 0 AND 1)
        AND (net_price_percentile_share IS NULL
            OR net_price_percentile_share BETWEEN 0 AND 1)
        AND (published_price_in_state_on_campus_ladder_share IS NULL
            OR published_price_in_state_on_campus_ladder_share BETWEEN 0 AND 1)
        AND (published_price_out_of_state_on_campus_ladder_share IS NULL
            OR published_price_out_of_state_on_campus_ladder_share BETWEEN 0 AND 1));
```

No new table, no new column on `colleges`, no `log_college_version()` change,
and **no index**.

The btree this RFC first prescribed on the out-of-state value column is gone,
and its absence is the measured answer rather than a saving of effort: NOTHING
can use one. Every published-price bound, the price `ORDER BY` and
`cheaper_than_anchor` all read a `CASE` over the TWO tier columns (§6), which no
single-column btree serves, and the ladder is computed from a CTE over the whole
default universe rather than probed. It would have been write cost on every
rebuild of a ~3.3k-row table, paid for by no reader.

`net_price_per_year_usd` and `net_price_percentile_share` are **not renamed and
not dropped**. The wire rename of the net axis (§6) is done in Kotlin, because
D4's gated DDL is exactly these four columns and because `shape/08` owns the
column-shape end state.

### 5. The rebuild

Both statements stay inside `CollegesDao.rebuildSearchIndex` and its one
transaction; determinism (no `NOW()`, every `array_agg` ordered,
tie-deterministic ranking) is unchanged.

**Statement 2 — `insertIndexRows`.** Two more LEFT JOIN LATERALs, one per tier,
beside the existing ones. LEFT, so a college with no figure keeps its row and
the column goes NULL — the established rule, and exactly the shape
`excluded_unknown` counts.

```sql
LEFT JOIN LATERAL (
    SELECT CASE WHEN count(*) = 4 THEN sum(f.amount_usd)::INTEGER END AS total
    FROM (
        SELECT DISTINCT ON (pf.price_concept, pf.residency_basis, pf.arrangement)
               pf.amount_usd
        FROM price_figures pf
        WHERE pf.college_id = c.id
          AND pf.amount_usd IS NOT NULL          -- value-bearing statuses only
          AND (pf.price_concept, pf.residency_basis, pf.arrangement) IN (
                ('tuition_and_fees',   'out_of_state',   'not_applicable'),
                ('housing_and_food',   'not_applicable', 'on_campus'),
                ('books_and_supplies', 'not_applicable', 'not_applicable'),
                ('other_expenses',     'not_applicable', 'on_campus'))
        ORDER BY pf.price_concept, pf.residency_basis, pf.arrangement,
                 pf.academic_year DESC           -- D15: serve the latest year per cell
    ) f
) oos ON TRUE
LEFT JOIN LATERAL ( ... the same block with 'in_state' ... ) ins ON TRUE
```

`count(*) = 4` IS the all-or-nothing rule: three components produce NULL, not a
short total. `DISTINCT ON` over the natural key's first three fields with
`academic_year DESC` is the deterministic latest-year pick — RFC 170 (`0087`)
made that column a SMALLINT DOMAIN named by its FIRST calendar year, so the
ordering is numeric rather than lexicographic and picks the same year more
honestly; the natural key
`UNIQUE (college_id, price_concept, residency_basis, arrangement, academic_year)`
guarantees no tie to break.

**Statement 3 — `rankPercentiles`.** The `universe` CTE gains the two published
value columns; one ladder is added, read twice:

```sql
ladder AS (
    SELECT published_price_out_of_state_on_campus_per_year_usd AS v
    FROM universe
    WHERE published_price_out_of_state_on_campus_per_year_usd IS NOT NULL),
ladder_size AS (SELECT count(*)::numeric AS n FROM ladder),
-- Both readings are GENERATED by one `createPublishedLadderShareCte(cte, column)`
-- helper, never hand-copied: the guarantee is that both stored shares are
-- positions on ONE ladder, and two blocks of SQL make that a fact about two
-- pieces of text staying in step. Four things have to agree — the `n > 1`
-- guard, the strict `<`, the `least(…, 1.0)` cap and the `numeric(5,4)` cast —
-- and a divergence in any one would move a school on one tier and not the other.
published_out AS (
    SELECT u.college_id,
           (CASE WHEN s.n > 1 THEN
               least((SELECT count(*) FROM ladder l
                      WHERE l.v < u.published_price_out_of_state_on_campus_per_year_usd)::numeric
                     / (s.n - 1), 1.0)
            END)::numeric(5,4) AS v
    FROM universe u CROSS JOIN ladder_size s
    WHERE u.published_price_out_of_state_on_campus_per_year_usd IS NOT NULL),
published_in AS (
    SELECT u.college_id,
           (CASE WHEN s.n > 1 THEN
               least((SELECT count(*) FROM ladder l
                      WHERE l.v < u.published_price_in_state_on_campus_per_year_usd)::numeric
                     / (s.n - 1), 1.0)
            END)::numeric(5,4) AS v
    FROM universe u CROSS JOIN ladder_size s
    WHERE u.published_price_in_state_on_campus_per_year_usd IS NOT NULL)
```

joined into the existing `UPDATE` by two more `LEFT JOIN`s and two more `SET`
clauses. Each ladder is still computed independently, so a row missing one input
still ranks on the others.

**The one-school corpus is guarded by `CASE WHEN s.n > 1`, and NOT by a
`nullif(s.n - 1, 0)` inside the division.** This RFC first prescribed the
`nullif` form and claimed it yielded NULL for a corpus of one. It does not:
Postgres' `least()` IGNORES a NULL argument, so `least(NULL, 1.0)` is `1.0` —
the only school in the corpus would have been pinned to the TOP of the ladder,
silently, and every distance measured against it would have been wrong. The
shipped form returns NULL, and a test
(`a one-school ladder is NULL, not a
division error`) fails if the `nullif`
shape is ever copied back in.

### 6. The read path and the wire (D7, D11)

Only the ACTIVE ruler's filter word and sort word are honoured. A caller that
names the inactive one is **refused by name**, with a sentence saying which
ruler this query is on — the expand-and-refuse precedent at
`SimilarCollegesTool.kt:420-442`. Never silently ignored.

|                         | net-price ruler                                  | published-price ruler                                                                                                                              |
| ----------------------- | ------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------- |
| filter field            | `maxInStateNetPricePerYearUsd`                   | `maxPublishedPriceOnCampusPerYearUsd`                                                                                                              |
| `sort_by` word          | `in_state_net_price`                             | `published_price_on_campus`                                                                                                                        |
| result key              | `in_state_net_price_per_year_usd`                | `published_price_in_state_on_campus_per_year_usd` or `published_price_out_of_state_on_campus_per_year_usd` — the tier that was applied to THAT row |
| `excluded_unknown` key  | `in_state_net_price_per_year_usd`                | `published_price_on_campus_per_year_usd`                                                                                                           |
| `similar_colleges` axis | `price` (unchanged word, ruler-dependent column) | same                                                                                                                                               |

Two mechanical consequences:

1. **`UnknownSubject.FilterColumn` gains an explicit key.** Today the
   `excluded_unknown` key IS the schema column name (`CollegesDao.kt:998-1006`),
   and the net axis' wire name now differs from its column while the published
   axis has no single column at all (its filter is a `CASE` over two).
   `FilterColumn(column, key = column)` keeps every existing key byte-identical
   and lets these two axes name themselves. Each ruler declares its `columns`
   set, or the similarity arm counts zero by construction — the exact defect
   that doc records.
2. **The residency-correct expression is built from index columns only**, so the
   filter and count statements still name no table but `college_search_index`
   (pinned at `CollegesDaoTest.kt:1758`):

```sql
CASE WHEN control = 'public' AND state IS DISTINCT FROM 'WA'
     THEN published_price_out_of_state_on_campus_per_year_usd
     ELSE published_price_in_state_on_campus_per_year_usd END
```

The family's state is **written into the expression, not bound** — and that is a
decision, not an oversight. `PriceRuler.Published` checks it against
`^[A-Z]{2}$` in its `init`, so the value is a closed shape by construction
rather than caller text, and it is the [DefaultUniverse] / `CONTROL_CASE`
treatment of a closed vocabulary rather than an unbound filter value.

Binding it was tried first and does not work, because the SAME expression is
needed in five places and three of them cannot take a parameter: the `ORDER BY`
key (whose binds would have to interleave with the predicate's), the
`excluded_unknown` arm's condition (`countPage` binds the predicate and nothing
else), and `SimilarityPlan.sharedAxisClause`, whose own doc says it "carries no
parameter on purpose: the count query restates it without rebinding anything".
One expression used in five places beats five expressions that must be kept in
step. It never enters a universe fragment either way (`SearchPlan.init` forbids
a bound universe; `CollegesDao.kt:912`).

The same `CASE` shape, over the two share columns, is what `AnchoredAxis.Price`
ranks and what `findSimilarityAnchorRow` reads for the anchor, so anchor and
candidates are on one ladder by construction.

Sort stays `... ASC NULLS LAST` plus the `ipeds_unit_id` tiebreak: a row with no
figure on the active ruler sinks, it never vanishes (brief 0004 D11). **Two
price fields are ADVERTISED, and one surface advertises only one.** The tool
schema is built once at boot, before any family is known, so both
`maxInStateNetPricePerYearUsd` and `maxPublishedPriceOnCampusPerYearUsd` appear
on `search_colleges` and `similar_colleges`, each description saying when it is
honoured — that is what makes the inactive one REFUSABLE by name rather than
unreachable.

The fit lens is the exception, and it is the same rule rather than a break from
it. `FitLensService` carries no student residency into its query, so it is
always on the net-price ruler and the published bound could only ever be refused
— `CollegeQueryVocabulary`'s own doc calls an advertised filter that cannot be
used worse than one the model is never told about. So `schemaProperties` takes
`offerPublishedPrice: Boolean = true` and the fit lens passes `false`. The field
stays in `FIELD_NAMES`, so a model that writes it there is REFUSED BY NAME
rather than rejected as an unknown key.

**The sentence.** EVERY page carries a `price_ruler` object — not only a
published one. One ruler per query is a fact of every query, and a key that
appears exactly when the answer is unusual is a key a reader learns to ignore;
the net ruler has a basis worth naming too. It carries three keys: `metric` (the
active ruler's `sort_by` word), `max_filter_field` (the bound this query would
accept, which is what makes the inactive-ruler refusal legible rather than a
bare "no"), and `note` — one sentence saying, under the published ruler, that
this ranking is a published price with no financial aid in it and that no
out-of-state after-aid price exists, and under the net ruler that the figure is
the after-aid blend and in-state at a public school. `NET_PRICE_BASIS_NOTE`
(`CollegeMatchRow.kt:32`) is rewritten to state both rulers and their bases —
ED's own sentence is the model for the copy: "For public schools, this is only
the average cost for in-state students." It is one constant, used by both tool
descriptions, and it acquires its first test.

### 7. Residency reaches the query (D5)

`CollegeChatTool` and `SimilarCollegesChatTool` are plain `DelegatingChatTool`s
today, so no `studentId` reaches them, while the coach's own system context
already says "state of residency: answered (WA)" (`CoachingService.kt:886-889`).
The residency is in the room; it is not in the call.

- Both become student-scoped in `:service` (the `MoneyProfileChatTool`
  precedent), dispatched with the owning student's id by `CoachingService`. They
  share ONE base, `ResidencyScopedChatTool`: the read, the call and the offer
  merge were byte-identical in both, and the two doors must be on the SAME ruler
  for the same family or a search and a peer list about one school disagree
  about which price they ranked.
- They read `MoneyProfileService.getForStudent`. No profile row is `NotAsked`,
  never an error — the `FederalAidPolicyService.dependencyOf` shape. The read is
  a function-typed SEAM with the real service as its default, so the documented
  "the profile read failed → net-price ruler" branch is testable without a fault
  injected into Postgres. Its three outcomes are a sealed `Residency` (`OnFile`
  / `NotAsked` / `Unavailable`), so "a state is on file" and "the offer is still
  open" cannot both be true of one value.
- They pass a plain residency code down, which the tool resolves ONCE into a
  `PriceRuler` and `CollegeQuery` then CARRIES (`priceRuler`, not a residency
  code a getter re-resolves at six read sites). `:college` stays free of `:chat`
  (RFC 67) and free of the money profile; it receives a two-letter code.
- **The ruler is never defaultable.** `findSimilarityAnchor` (both layers), both
  tools' `execute`, `CollegeQueryVocabulary.parse`, `CollegeSearchPage` and
  `CollegeSimilarityPage` all take it as a required argument. A caller that
  forgets it would otherwise get the mixed anchor-versus-candidate comparison
  this whole slice exists to make impossible, and on the two page types — whose
  only job is to say which price was used — "nobody set it" must not equal "net
  price". `FitLensService` passes null explicitly, exactly as it passes
  `offerPublishedPrice = false`.

- **The mixed query is unconstructable, not merely unlikely.**
  `SimilarityAnchor` is stamped with the ruler it was READ on, and
  `SimilarityQuery`'s `init` requires it to equal the filters' ruler. Two
  ruler-dependent figures ride that row and neither says so alone, so this is
  the one seam no other guard can see: both halves are individually valid and
  only the query that joins them can refuse the pairing.
- **The residency is checked for MEMBERSHIP**, not shape. `^[A-Z]{2}$` admits
  676 strings of which 59 are real, and this value is written into SQL, so
  `PriceRuler.Published`'s `init` refuses anything outside `UsStateCodes.ALL` —
  the one list the served vocabulary (RFC 165) and the profile write boundary
  also use, moved down to `:db` so the ruler can reach it.
- **A corrupt profile degrades; it does not fail the search.** A row that says
  ANSWERED while holding an unusable state violates `money_profiles`' own CHECK.
  It used to reach `PriceRuler.Published`'s `init` and throw, turning every
  search by that family into a tool failure; it is now caught at the read,
  logged as corruption, and answered on the net-price ruler like its sibling
  impossible case. `Residency.Cause` carries WHY — `DECLINED`, `READ_FAILED`,
  `CORRUPT_PROFILE` — because those are three facts, and the page says the true
  one: after a fault we do NOT tell a family their state is "not on file".
- The field is **not** `states`, which already means where the COLLEGE is
  (`CollegeQueryVocabulary.kt:94-100`). No new _input_ field is added to either
  tool's JSON schema: the state comes from the profile, never from the model, so
  it cannot contradict `money_profiles`.
- `FindCollegeChatTool` stays unscoped: it is a name lookup with no ranking. Its
  rows follow the net-ruler rename like everyone else's.

### 8. The offer — ask earlier, never gate (D6)

Search is UPSTREAM of the cost `precision_offer`, which needs a college list, so
a family's FIRST search is the least likely to have residency on file. The
unknown-residency case is the load-bearing one.

The search result therefore carries an offer of RFC 145's exact shape
(`CollegeCostService.kt:571-601`, `CollegeCostChatTool.kt:423-425, 742-758`):
keyed on `AnswerStatus.UNANSWERED` (not on a missing value, so a decline never
re-raises a closed topic), `{ "field": "residency_state", "offer": <copy> }`,
the key omitted entirely when empty, and copy no wider than what the answer buys
— that a state on file makes the ranking a price this family would actually pay.
It never withholds a row, never blocks a result, and never re-asks after a
decline.

### 9. Ingest order (D8)

`CollegeScorecardLoader.load` runs `search-index` (`:762`) BEFORE
`canonical-money` (`:771`). The index can only read canonical money if that
order is swapped, so it is: `canonical-money` moves ahead of `search-index`,
both stay before `provenance` (their row counts ARE provenance),
`METHOD_VERSION` goes **7 → 8** (`:1606`), and
`CanonicalMoneyLoader.kt:291-306`, whose doc states the old order as an
assumption, is rewritten. `bin/ingest-colleges`' help text narrates the phase
order and is re-checked with `bin/shell-tests`.

### 10. Guard scope (D9)

`ForbiddenCostArithmeticTest` scans only
`service/src/main/kotlin/ed/unicoach/coaching/costs` and `.../aid`. This slice
is the first to build a price OUTSIDE `:service`, so the scan gains the `:db`
rebuild SQL (`db/src/main/kotlin/ed/unicoach/db/dao/CollegesDao.kt`, reached as
`File("../db/src/main/kotlin/ed/unicoach/db/dao")` from the `:service` module
directory, beside the existing relative paths). Its positive control — the scan
reacts to the arithmetic it forbids — is extended to the new directory so the
addition cannot silently scan nothing.

### 11. Prompt and descriptions (D10)

Standard 4-file bump: a new `db/schema/0092.seed-coach-system-prompt-v22.sql`
carrying the v21 body byte-identically as a prefix plus exactly one appended
paragraph; the `service.conf` changelog stanza plus the version line flipped to
`"v22"`; `CoachingConfigTest`; `SystemPromptCatalogTest`. The v16 residency
paragraph (`0076` tail) is the wording precedent, down to its closing "never
hide a price … never make an answer wait on it". The new paragraph says: which
ruler a search result is on and why, that a published ranking has no aid in it,
and that the state question is offered and never gates. It names tools through
their `TOOL_NAME` constants and must never say "subtract" without "never "
before it.

Two sentences exist to agree with copy that landed while this slice was open
rather than merely to follow it. RFC 166's v19 gave the COST answers a third
tuition tier, so the paragraph says the in-district tier is a cost answer and
never a search ranking — otherwise a coach could read a search price back as an
in-district one. And D19 (§12) makes a search ROW able to carry the same
`residency_tiers` sentence a cost answer carries, so the paragraph says to give
that sentence as written and never to turn it into a claim about what the school
charges. Both are asserted, each with a premise assertion against the served v21
body so the guard cannot go vacuous.

### 12. The third tuition tier — labelled, never ranked (D19)

A tuition figure whose tier the publisher does not separate must be RANKED and
LABELLED, never silently treated as in-state.

**D19 costs no DDL.** The index keeps exactly the four columns §4 gates and
nothing else. The fact the label needs is a per-figure STATUS, not a value, and
it is read LIVE for the returned page: a `LEFT JOIN LATERAL` over
`price_figures` on the PAYLOAD half of both page statements, beside the join
back to `colleges`,
`DISTINCT ON (residency_basis) … ORDER BY academic_year DESC` for the three
tuition tiers of the at-most-25 rows being returned. One lateral over
`price_figures_college_idx`, on statements that already join `colleges`,
`college_ipeds` and a census lateral.

That is legal by the scope pin's own design, not in spite of it:
`CollegesDaoTest`'s "names no table but `college_search_index`" takes its
absence assertions over the `FROM (` … `) i` subquery and separately asserts the
payload half DOES reach `colleges`. The filter, the count and the ranking are
untouched and still name one table.

**The label.** A returned row whose published price is reported on the IN-STATE
tier, at a school whose district status is an UNANSWERED absence, carries
`ResidencyTierBasis.PUBLISHER_DOES_NOT_SEPARATE_IN_DISTRICT`'s own statement,
under the `residency_tiers` key the cost payload already uses and through the
same single emitter. The row is ranked exactly as every other: never dropped,
never excluded, never substituted, and its in-state figure is shown as it was
published. A school whose publisher ANSWERED "no district tier here" carries
nothing at all.

**One derivation, two readers.** The basis is decided by `residencyTierBasisOf`
in `:db`, which the cost path's `residencyTiersOf` now calls as an adapter over
`ServedFigures`. `:college` computes no basis of its own — it asks whether the
answer is the one member D19 is about. `publishedTuitionTiersOf`'s doc already
required this ("so no surface re-derives the tier shape from one amount"); a
second copy in `:college` would have been a second dialect of "which prices does
this school publish". The enum itself moved to
`db/src/main/kotlin/ed/unicoach/db/models/` on the `LivingArrangement`
precedent, so the sentence keeps one home.

**Why an amount could not have carried this.** An INTEGER in-district column is
NULL in two different situations. IPEDS IC_AY publishes flag `A` on `CHG1AY` at
the ordinary public four-year and at every private, which the canonical fill
stores as an `in_district` row with status `not_applicable` — the publisher
ANSWERING — while a Scorecard-only school has no `in_district` row at all, which
is a district price nobody can speak about. Only the second is D19's case, and
RFC 166's own rule turns on exactly that distinction.

Measured over the default search universe of the dev corpus (3,264 rows, 967 of
them public), from `IC2023_AY.zip` (`Last-Modified` 2024-08-20; zip and member
sha256 both matching `db/seed/ipeds/PROVENANCE.json`):

| the school's district status                     | rows  | public |
| ------------------------------------------------ | ----- | ------ |
| a real in-district amount                        | 2,269 | 779    |
| an UNANSWERED absence — labelled                 | 641   | 146    |
| the publisher ANSWERED `not_applicable` — silent | 354   | 42     |

All 641 unanswered rows are colleges absent from IC_AY entirely; none carries a
`B`/`D`/`H` flag, and no school flagged `A` at 2023-24 has a value-bearing
earlier year, so nothing depends on the latest-year choice. A rule that read the
amount alone would have labelled 995 rows and been wrong on 354 of them — a
third of what it labelled, and wrong in the direction that tells a family we
cannot say whether a lower district price exists at a school whose publisher
said there is none.

**The ruler does not change, and no ranking is added.** Nothing sorts, filters
or ranks on an in-district figure: we do not know a family's district, and
`money_profiles.residency_state` cannot answer one — RFC 166's v19 rule and this
RFC's own v20 sentence. A future slice that RANKS on the in-district tier buys
its own migration then, when it has a reader for it.

## Files Modified

| file                                                                                                      | why                                                                                                                                                                                                                                                |
| --------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `db/schema/0091.add-search-index-published-price.sql`                                                     | new: four columns and the replaced 6-clause percentile CHECK — no index (§4). D19 adds none (§12)                                                                                                                                                  |
| `db/schema/0092.seed-coach-system-prompt-v22.sql`                                                         | new: v21 byte-identical prefix + one appended ruler paragraph                                                                                                                                                                                      |
| `db/src/main/kotlin/ed/unicoach/db/dao/CollegesDao.kt`                                                    | two LATERALs in `insertIndexRows`, the ladder CTEs in `rankPercentiles`, `PriceRuler` selection in `createSearchPlan` / `orderBy` / `createFilters` / `AnchoredAxis.Price` / `findSimilarityAnchorRow`, `UnknownSubject.FilterColumn` explicit key |
| `db/src/main/kotlin/ed/unicoach/db/models/PriceRuler.kt`                                                  | new: the one ruler object — value SQL, share SQL, wire word, result key, unknown subject                                                                                                                                                           |
| `db/src/main/kotlin/ed/unicoach/db/models/ResidencyTierBasis.kt`                                          | MOVED from `:service`, and gains `residencyTierBasisOf` + the one `residency_tiers` emitter (§12)                                                                                                                                                  |
| `service/src/main/kotlin/ed/unicoach/coaching/costs/canonical/ResidencyTiers.kt`                          | `residencyTiersOf` becomes the cost path's adapter onto the shared derivation (§12)                                                                                                                                                                |
| `db/src/main/kotlin/ed/unicoach/db/models/CollegeQuery.kt`                                                | `priceRuler` (the RESOLVED ruler, not a residency code), the two ruler `SortBy` members replacing `NET_PRICE_PER_YEAR_USD_ASC`                                                                                                                     |
| `db/src/main/kotlin/ed/unicoach/db/models/SimilarityQuery.kt`                                             | `SimilarityAnchor` carries the ruler's value, share AND the ruler it was read on; the init refuses a mixed pairing                                                                                                                                 |
| `db/src/main/kotlin/ed/unicoach/db/dao/PriceRulerSql.kt`                                                  | new: the ruler's `internal` SQL — column names, value/share expressions, the tier `CASE`                                                                                                                                                           |
| `db/src/main/kotlin/ed/unicoach/db/models/UsStateCodes.kt`                                                | MOVED from `MoneyProfileService`: `PriceRuler` writes a state into SQL, so membership must be checkable in `:db`                                                                                                                                   |
| `db/src/main/kotlin/ed/unicoach/db/models/CollegeSearchPage.kt`, `CollegeSimilarityOutcome.kt`            | the active ruler rides the page so the tool can name it                                                                                                                                                                                            |
| `college/src/main/kotlin/ed/unicoach/college/CollegeQueryVocabulary.kt`                                   | `maxInStateNetPricePerYearUsd` / `maxPublishedPriceOnCampusPerYearUsd`, `FIELD_NAMES`, `schemaProperties(offerPublishedPrice)`, the `constraints_used` sentences, the inactive-ruler refusal                                                       |
| `college/src/main/kotlin/ed/unicoach/college/CollegeMatchRow.kt`                                          | the row price keys, the `price_ruler` sentence, the rewritten `NET_PRICE_BASIS_NOTE`                                                                                                                                                               |
| `college/src/main/kotlin/ed/unicoach/college/CollegeSearchTool.kt`                                        | `SORT_BY_WORDS`, the `sort_by` description, `DESCRIPTION`, the residency argument, the offer key                                                                                                                                                   |
| `college/src/main/kotlin/ed/unicoach/college/SimilarCollegesTool.kt`                                      | `cheaper_than_anchor` copy and refusal, axis copy, `DESCRIPTION`, the residency argument                                                                                                                                                           |
| `college/src/main/kotlin/ed/unicoach/college/FindCollegeTool.kt`                                          | renders `CollegeMatchRow`; follows the net-ruler key rename only                                                                                                                                                                                   |
| `college/src/main/kotlin/ed/unicoach/college/CollegeSearchService.kt`                                     | passes the residency through; no student, no profile                                                                                                                                                                                               |
| `college/src/main/kotlin/ed/unicoach/college/CollegeScorecardLoader.kt`                                   | `canonical-money` before `search-index`, phase comments, `METHOD_VERSION` 8                                                                                                                                                                        |
| `college/src/main/kotlin/ed/unicoach/college/CanonicalMoneyLoader.kt`                                     | the `:291-306` doc states the OLD phase order as an assumption                                                                                                                                                                                     |
| `service/src/main/kotlin/ed/unicoach/coaching/CollegeChatTool.kt`                                         | becomes a `StudentScopedChatTool`, resolves residency                                                                                                                                                                                              |
| `service/src/main/kotlin/ed/unicoach/coaching/SimilarCollegesChatTool.kt`                                 | same                                                                                                                                                                                                                                               |
| `service/src/main/kotlin/ed/unicoach/coaching/fitlens/FitLensService.kt`                                  | the forced tool's schema drops the published bound it can never honour (§6). Its DIGEST is untouched — see below                                                                                                                                   |
| `service/src/main/resources/service.conf`                                                                 | changelog stanza + `systemPromptVersion = "v22"`                                                                                                                                                                                                   |
| `rest-server/src/main/kotlin/ed/unicoach/rest/Application.kt`                                             | registers the two student-scoped tools (`:353-372`)                                                                                                                                                                                                |
| `bin/ingest-colleges`                                                                                     | help text narrates the phase order                                                                                                                                                                                                                 |
| `db/src/test/kotlin/ed/unicoach/db/dao/SearchIndexFixture.kt`                                             | `TABLES` gains `price_figures` and `cohort_money_stats`; `seedCodebooks` calls `MoneyVocabularyFixture.seed` (five vocabulary tables are FK preconditions)                                                                                         |
| `db/src/test/kotlin/ed/unicoach/db/dao/CollegeSearchIndexRebuildTest.kt`                                  | money fixtures + the new rebuild assertions                                                                                                                                                                                                        |
| `db/src/test/kotlin/ed/unicoach/db/dao/CollegesDaoTest.kt`                                                | the one-ruler pin, filter/sort/percentile tests, three `NewCollegeIndexBuild` literals (`:1886`, `:2076`, `:2120`)                                                                                                                                 |
| `college/src/test/kotlin/ed/unicoach/college/CollegeSearchToolTest.kt`                                    | input-schema key set (`:170-197`), `sort_by` word list (`:738-742`), `NUMBERS_BY_CONTRACT` (`:1206-1231`), band keys, description pins, build literal (`:1100`)                                                                                    |
| `college/src/test/kotlin/ed/unicoach/college/SimilarCollegesToolTest.kt`                                  | own-field set (`:334`), price fixture, both `excluded_unknown` key styles (`:438`, `:1077-1106`), `NUMBERS_BY_CONTRACT` (`:1284-1305`), two hand-built `SimilarityAnchor` literals (`:827-868`, `:1235-1289`)                                      |
| `college/src/test/kotlin/ed/unicoach/college/CollegeSearchServiceTest.kt`, `FindCollegeToolTest.kt`       | compile-follow on the renamed keys/fixtures                                                                                                                                                                                                        |
| `college/src/test/kotlin/ed/unicoach/college/CollegeScorecardIngestTest.kt`                               | phase order and `METHOD_VERSION`                                                                                                                                                                                                                   |
| `service/src/test/kotlin/ed/unicoach/coaching/fitlens/FitLensServiceTest.kt`                              | the forced tool's advertised field set; the wire rename in its query fixtures                                                                                                                                                                      |
| `service/src/test/kotlin/ed/unicoach/coaching/costs/ForbiddenCostArithmeticTest.kt`                       | scan scope + positive control over `:db`                                                                                                                                                                                                           |
| `service/src/test/kotlin/ed/unicoach/coaching/CoachingConfigTest.kt`                                      | `"v22"` (`:17`)                                                                                                                                                                                                                                    |
| `service/src/test/kotlin/ed/unicoach/coaching/SystemPromptCatalogTest.kt`                                 | the v22 trio + the ruler paragraph accessor                                                                                                                                                                                                        |
| `rest-server/src/test/kotlin/ed/unicoach/rest/CollegeSearchRoutingTest.kt`, `ConvoToolLoopRoutingTest.kt` | rebuild the index; fixture follow-through                                                                                                                                                                                                          |
| `service/src/test/kotlin/ed/unicoach/coaching/costs/CostsTestDb.kt`                                       | shared seeder touched by the money-table truncation                                                                                                                                                                                                |

## Implementation Plan

Each step is independently checkpointable; run `nix develop -c bin/test` at
each.

1. **Migration `0091`** (number re-claimed at commit) + `SearchIndexFixture`
   growth (`TABLES` + `MoneyVocabularyFixture.seed`). Nothing reads the columns
   yet; the suite must stay green.
2. **Rebuild statement 2**: the two LATERALs, all-or-nothing and latest-year.
   New `CollegeSearchIndexRebuildTest` cases for value, absence and the
   three-of-four NULL.
3. **Rebuild statement 3**: the shared ladder and the two share columns, plus
   the `percent_rank()` identity and determinism tests.
4. **Ingest order**: `canonical-money` before `search-index`, `METHOD_VERSION`
   8, the two doc comments, `bin/ingest-colleges` help; `bin/shell-tests`.
5. **`PriceRuler`** in `:db` plus the resolved `priceRuler` on `CollegeQuery` /
   `SimilarityQuery`, and the `UnknownSubject.FilterColumn` key. Pure refactor
   first: with no residency the ruler resolves to net price and every existing
   test still passes on the old wire words.
6. **Read path**: filter, sort, `cheaper_than_anchor`, `AnchoredAxis.Price`,
   anchor read, `excluded_unknown` — all through the ruler. Add the one-ruler
   pin in `CollegesDaoTest`.
7. **Wire rename**: `in_state_net_price*` and the published words, the
   inactive-ruler refusal, both `NUMBERS_BY_CONTRACT` allowlists, the schema
   key-set and `sort_by` order assertions.
8. **Residency plumbing**: the two `StudentScopedChatTool`s,
   `MoneyProfileService` read, `Application.kt` registration, the never-gates
   test.
9. **The offer**: `residency_state` offer object, key omitted when empty, copy,
   and the field/`update_money_profile` binding test.
10. **Copy**: `NET_PRICE_BASIS_NOTE` rewrite + its first tests, both tool
    `DESCRIPTION`s. The `FitLensService` digest needs NO change — see §6.
11. **`ForbiddenCostArithmeticTest`** scope extension + control.
12. **Prompt v22**: seed migration, `service.conf` stanza + the version line,
    `CoachingConfigTest`, `SystemPromptCatalogTest` trio.
13. **D19** (§12): the shared derivation down into `:db`, then the payload
    lateral on BOTH page paths and the label. No column, no ladder, no ruler
    change.
14. Full `nix develop -c bin/test` and `nix develop -c bin/shell-tests`;
    re-claim both migration numbers immediately before the commit.

## Tests

**`db/src/test/kotlin/ed/unicoach/db/dao/CollegesDaoTest.kt`**

- `one query reads one price ruler` — the pin D14(c) asks for. Seeds two
  colleges whose order FLIPS between the rulers (a same-state public and an
  out-of-state public), runs the same query with and without a published
  `priceRuler`, and asserts the page order, the `ORDER BY`, the price bound and
  the `excluded_unknown` key all name ONE measure. Fails if a query reads two
  price percentile columns.
- `a published-price ruler ranks a non-resident on the out-of-state total` and
  its in-state twin — the residency-correct value is the one that filters and
  sorts, per row.
- `sortBy on the published ruler never filters` — a row with no figure on that
  ruler is RETURNED, sunk, and counted in `totalMatches` (the `:1418-1426`
  shape, duplicated for the published ruler).
- `naming the inactive ruler is refused before a query can be built` — the
  type-level backstop under the vocabulary's own named refusal
  (`naming the inactive ruler is refused by name, never silently ignored`, in
  `CollegeSearchToolTest`): an inconsistent query is not a state `CollegeQuery`
  can be in, so nothing downstream can quietly fall back to the other measure.
- `a numeric filter reports one excluded-unknown count per axis` and the
  `:1642`/`:1656` accounting tests, re-pointed at the new keys.
- **Trap**:
  `the filter and count statements name no table but college_search_index`
  (`:1758`) must stay green — it asserts `FROM college_search_index` and the
  ABSENCE of `colleges`, `college_ipeds`, `college_programs`, `cip_codes`,
  `college_programs_census` in both statements. This is why the ruler is
  materialised on the index and the `CASE` reads index columns only.
- **Trap**: three `NewCollegeIndexBuild` literals (`:1886`, `:2076`, `:2120`)
  carry `priceFigureRows = null`.

**`db/src/test/kotlin/ed/unicoach/db/dao/CollegeSearchIndexRebuildTest.kt`**

- `the published on-campus total is the sum of four canonical components` —
  seeds `price_figures` rows directly (the `CanonicalMoneyDaoTest.kt:145-155`
  raw-SQL precedent) and asserts both tier columns.
- `a missing component makes the total NULL, not a short sum` — three of four
  parts, both tiers.
- `a figure with no value is an absence, not a zero` — a
  `suppressed_by_publisher` row leaves the total NULL.
- `the latest academic year wins per college per figure` — two years of the same
  cell; the newer amount is the one summed; a Scorecard-only college at
  `2022-23` is not dropped by an IPEDS-shaped year literal.
- `both published shares are positions on one ladder` — the in-state share of a
  school equals the share the same value would take on the out-of-state corpus,
  and for a corpus member the stored share equals `percent_rank()` over that
  corpus.
- `a row missing the published input still ranks on the others` and
  `published shares are NULL outside the default universe` — the `:602`/`:572`
  shapes for the new axis.
- `the percentile corpus is exactly the default search universe` (`:627-675`) —
  extended to the published ladder; the corpus is NOT restricted, only the
  ladder's population.
- `rebuilding twice reproduces every column of every row` (`:684-720`) — now
  with money rows seeded, so the new columns are actually covered by the
  whole-row snapshot; no clock, no vintage timestamp may enter a column.
- The `CHECK` obligation: a share outside `0..1` is rejected by
  `college_search_index_percentile_range_check`, asserted for both new columns.

- **D19 (§12)**:
  `the index carries exactly six share columns, and D19 added no column at all`
  — the share columns and the named CHECK are read back from the catalog, so a
  ladder or an in-district column added later fails here rather than arriving as
  speculative DDL.
  **`college/src/test/kotlin/ed/unicoach/college/CollegeSearchToolTest.kt`**

- **Trap**: `:170-197` is an EXACT `assertEquals` on the input-schema key set.
  The set changes by RENAME AND ADDITION: §6's expand-and-refuse mandate needs
  BOTH price bounds advertised, because the schema is built at boot with no
  family in scope and a field that is never offered can never be refused by
  name. No residency input field is added (§7) — the state comes from the money
  profile, so the model cannot contradict it — and that is the part of this trap
  that is load-bearing.
- **Trap**: `:738-742` pins the `sort_by` enum WORD LIST **in order**; the new
  list is `enrollment`, `admission_rate_share`, `in_state_net_price`,
  `published_price_on_campus`, `completion_rate_150pct_4yr_share`, `name`.
- **Trap**: `NUMBERS_BY_CONTRACT` (`:1206-1231`) is EXHAUSTIVE in both
  directions (`:551-552`): every allowlisted key must appear in the payload, and
  every payload number must be allowlisted. The renamed net key and the two
  published keys go in, and the fixture must render each one it allowlists.
- `the result names the ruler it ranked on` — the `price_ruler` sentence is
  present under the published ruler and says aid is not in it.
- The income-band entries and the `excluded_unknown` tests re-pointed at the new
  key;
  `a filter that cannot exclude an unknown reports an empty excluded_unknown`
  unchanged.
- **Trap**: the `NewCollegeIndexBuild` literal at `:1100`.

- **D19 (§12)**:
  `a school whose publisher does not separate a district price says so, and is
  ranked anyway`
  (labelled, and still ranked, counted and priced),
  `a publisher that ANSWERED not_applicable carries no district sentence` — THE
  354-row regression an amount-only rule would have shipped, pinned by name —
  `a school that publishes a real district price carries no district sentence
  either`,
  and
  `the district sentence rides the tier it is about, and no
  net-ruler row`.
  The statement is asserted against
  `ResidencyTierBasis.PUBLISHER_DOES_NOT_SEPARATE_IN_DISTRICT.statement`, never
  a literal.

**`college/src/test/kotlin/ed/unicoach/college/SimilarCollegesToolTest.kt`**

- **Trap**: `:334` own-field set for `similar_colleges` — no residency input
  field is added.
- **Trap**: the two `excluded_unknown` key styles (`:438` column key,
  `:1077-1106` axis word `price`) and the rule that only one appears at a time —
  extended to the published ruler: an unknown on the active ruler is dropped,
  counted and named, never substituted and never "maybe cheaper".
- `cheaper_than_anchor` on the published ruler compares the anchor's own
  residency-correct total, says the figure back with grouping ("28,400"), and
  REFUSES BY NAME when the anchor has no figure on the active ruler (the
  `:869-899` shape).
- **Trap**: `NUMBERS_BY_CONTRACT` (`:1284-1305`) and the two hand-built
  `SimilarityAnchor` literals.

- **D19 (§12)**: `both tools describe one college's tuition tiers identically` —
  the D70 pin over the shared derivation and the single emitter.

**`college/src/main/.../CollegeMatchRow.kt` — `NET_PRICE_BASIS_NOTE`**

- The constant has **zero test coverage today**. This RFC adds one, asserted on
  BOTH tool descriptions (RFC 153 D70: two tools that return "a college"
  describe it the same way): the note names both rulers, states that the
  published ruler contains no financial aid, states that no out-of-state
  after-aid price exists, and never says "subtract" without "never " before it.

**Residency and the offer**

- `CollegeChatToolTest` / `SimilarCollegesChatToolTest`: an answered residency
  reaches `CollegeQuery.priceRuler` as a `PriceRuler.Published`; an unanswered,
  a declined and a missing profile all resolve to the net ruler without error.
- `a residency offer never gates a result` — with residency unanswered the page
  is returned in full, the offer key is present; with residency declined the key
  is ABSENT (keyed on `UNANSWERED`, not on a missing value); with it answered
  the key is absent and the published ruler is active.
- `every offer field is a parameter update_money_profile accepts` — the
  `CollegeCostChatToolTest.kt:368` twin.

**Ingest, guard, prompt**

- `CollegeScorecardIngestTest`: `canonical-money` commits before `search-index`,
  the build row carries both counts, `METHOD_VERSION` is 8.
- `ForbiddenCostArithmeticTest`: the `:db` rebuild source is actually being read
  (the positive control extended), and no rebuild line subtracts a price from a
  price.
- `SystemPromptCatalogTest`:
  `coach v22 is v21 plus one appended search-ruler
  paragraph` (the paragraph's
  markers, tool names from `TOOL_NAME` constants, the standing money guards over
  the appended span, and the two sentences that must agree with v19's
  in-district copy rather than merely follow it),
  `coach v22 keeps the whole v21 body, byte for byte, as its prefix` (stated as
  an EQUALITY, on RFC 170's own precedent: v19 edited interior blocks, so a list
  of `contains` checks over older spans would assert the wrong contract), and
  `coach v21 stays selectable as v22's rollback target`. `CoachingConfigTest`
  pins `"v22"`.
- `FitLensServiceTest`: the forced tool's advertised field set no longer offers
  the published bound (§6), and its query fixtures follow the wire rename. The
  **digest is deliberately unchanged**, and this RFC records why rather than
  commissioning work: RFC 166 cut that digest onto `cohort_money_stats`
  (`netPricePerYearUsd` / `netPriceBasis` / `netPriceVintage`, with the six
  figure statuses), so it no longer reads `CollegeMatch.netPricePerYearUsd` at
  all and is independent of the search ruler by construction. The fit lens is
  unscoped, so it is always on the net ruler, and the canonical net-price stat
  IS that ruler's measure. There is nothing to align.

## Rollback

Three independent switches, none requiring a migration revert:

1. **The ruler**: `COACHING_SYSTEM_PROMPT_VERSION=v21` restores the prior coach
   copy (every catalog row is immutable), and the tools' residency read is
   configuration-free — a query built with no residency resolves to the
   net-price ruler, which is today's behaviour column-for-column.
2. **The data**: the four new columns are additive and nullable. An index
   rebuilt with an empty `price_figures` leaves them NULL, and the published
   ruler then drops, counts and names every row rather than ranking wrongly.
3. **The wire words** are the one irreversible-in-practice change; they ship in
   the same commit as the prompt and tool descriptions the model reads, so the
   model never sees a half-renamed dialect.

## Decisions taken and not taken at review

Two shapes a reviewer proposed are deliberately NOT built, and the reasons
belong here rather than in a review thread nobody reads again.

**Two price sort words stay two words.** Collapsing `in_state_net_price` and
`published_price_on_campus` into a single `PRICE_ASC` would be simpler code and
the wrong product. Brief 0006 D14(b) requires the BASIS to be in the metric name
on the wire, and the refusal machinery needs the inactive word to exist so it
can be refused BY NAME — a word that cannot be written cannot be corrected. Two
members of `CollegeQuery.SortBy`, and the `init` requires that pair them to the
active ruler, are the product decision, not an oversight.

**The two refusal channels stay as they are.** `CollegeSearchTool`'s parse
returns `Result<CollegeQuery>` with the reason as an exception message, rather
than an outcome ADT distinguishing "unknown field" from "inactive ruler". It
follows the convention every other parse in that file already uses, it produces
no wrong answer, and rewriting the parse channel is a larger change than this
slice warrants. Recorded as an open item, not a defect.

**One stable result key plus a `residency_basis` field.** REJECTED for the same
reason the two sort words stay two: brief 0006 D14(b) puts the basis in the
metric NAME on the wire, and the tier-named result key IS that mechanism. Its
sub-point is taken, though — `price_ruler.metric`, the `excluded_unknown` key
and the per-row key all derive from `PriceRuler`, and its KDoc now says WHY
those four names differ in shape (asked-for word, stated field, per-axis key,
per-tier key) rather than leaving four spellings to look accidental.

**A `UspsStateCode` value class.** REJECTED in favour of checking MEMBERSHIP at
the boundary: `PriceRuler.Published`'s `init` refuses anything outside
`UsStateCodes.ALL`, the one list the served vocabulary (RFC 165) and the profile
write boundary also use. That is what makes the SQL literal airtight; a wrapper
type around a value already checked at construction would add a type without
adding a guarantee.

## Not in this RFC

- **`shape/08`'s column drop.** `colleges.net_price_per_year_usd`,
  `college_search_index.net_price_per_year_usd` and `net_price_percentile_share`
  stay exactly as they are. The wire rename is Kotlin-side; the schema end state
  belongs to the slice that proves reader-count zero.
- **`shape/04`'s cost surfaces.** `CollegeCostService`, `CollegeCostChatTool`
  and the family cost report keep reading the publisher-shaped columns. This RFC
  does not move them, and it does not add an in-district, off-campus or
  with-family ranking axis — the `in_district` tier ranks on `in_state` here,
  and the arrangement-tier answers are `shape/04`'s.
- **`search/06`** and any further search axes.
- Any stored derived figure: the totals live on the index because the index IS
  the materialisation, and nothing is written back to `price_figures`.
- Trend/history surfaces over the stored academic years (0006 D15 defers them).
