# The search surface, and whether residency is even reachable there

Research for product brief 0005 (the slice RFC 157 D-G reported to /chart).
READ-ONLY. Nothing in this repo was changed except this file. No worktree, no
branch.

## Method

1. Read `rfc/157-blended-figure-residency-basis.md`,
   `.scratch/blended-figure-surfaces.md`,
   `product/0004-college-search-index/spec.md` first, as instructed.
2. Traced the price axis by grep from the schema column outward, through `:db`,
   `:college`, `:service`, to the two chat tools and the fit lens. Every claim
   below carries a `file:line`.
3. Read the Gradle build files for the module boundary claim.
4. MEASURED the corpus. `bin/db-query` on this checkout's dev database, which
   holds a real ingested index (6,273 rows). Commands and their real output are
   quoted in full.
5. Where a number was not obtainable, I say so instead of estimating. I did NOT
   run the Kotlin test suite (read-only research; no code changed, so there was
   nothing to verify by running it). The test list in section 6 is read from
   source, not from an executed run — stated honestly.

Repo state: `git log --oneline -1` = `b8e31d17 Update dev users`; RFC 157 is
landed
(`29242880 Say whose price it is: the blended figures are in-state (RFC 157)`).
So the labels RFC 157 Design 9/10 added are present in the code cited below.

---

## 1. Every consumer of the index price axis

The axis itself, in the index
(`db/schema/0064.create-search-index-and-subjects.sql`):

- `:138` `net_price_per_year_usd INTEGER NULL`
- `:191` `net_price_percentile_share DOUBLE PRECISION NULL`, constrained 0..1 at
  `:199-200`
- `:267-268` the btree index on the price column
- `:99` `state TEXT NOT NULL` and `:109-111`
  `control TEXT NOT NULL CHECK IN ('public',
  'private_nonprofit', 'private_for_profit')`
  — **both residency-relevant facts are already in the index.** What is absent
  is any tuition or out-of-state column (grep for `tuition` in 0064 returns
  nothing).

| consumer                             | file:line                                                                                                                                                                                                                                                    | what the family / model gets                                                                                                                                                                           | changes if the axis becomes residency-aware?                                                                                                                                                                                                  |
| ------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `maxNetPrice` filter                 | `CollegesDao.kt:685-687` — `net_price_per_year_usd <= ?`, paired with `UnknownAxis.ofColumn("net_price_per_year_usd")`                                                                                                                                       | "under $25k" silently drops every public school whose OUT-of-state price is over the cap but whose in-state price is under it, and keeps ones the family cannot afford                                 | YES — this is the filter that removes schools. A wrong keep is a school the family will price later and be shocked by; a wrong drop is invisible.                                                                                             |
| cheapest-first sort                  | `CollegesDao.kt:1905-1907` — `net_price_per_year_usd ASC NULLS LAST`, reached from `CollegeQuery.SortBy.NET_PRICE_PER_YEAR_USD_ASC` (`CollegeQuery.kt:87` "Cheapest first (lowest average annual net price)")                                                | an ordered list where out-of-state publics are ranked as if in-state — systematically too cheap, so publics float to the top of "cheapest first" for exactly the families they are most expensive for  | YES — the ORDER changes, which is the whole product of that sort.                                                                                                                                                                             |
| price percentile (rebuild)           | `CollegesDao.kt:2426-2427` — `percent_rank() OVER (ORDER BY net_price_per_year_usd) ... WHERE net_price_per_year_usd IS NOT NULL`, written to `net_price_percentile_share` at `:2432`                                                                        | one ladder for every family in the country                                                                                                                                                             | YES, and this is the hard one: the percentile is a PRE-COMPUTED, per-row, family-independent number. A residency-aware percentile is not one column; it is one column per residency, or a computed-at-read rank.                              |
| `SimilarColleges` price axis         | `CollegesDao.kt:1283-1291` pairs `AnchoredAxis.Price` to `net_price_percentile_share` with the unknown-axis column set `{net_price_percentile_share, net_price_per_year_usd}`; `SimilarityQuery.kt:30-31` `PRICE("price")`, `:100` `pricePercentile`, `:117` | "similar on price" = similar on the in-state ladder                                                                                                                                                    | YES — both the distance term and the `excluded_unknown` accounting for the `price` axis.                                                                                                                                                      |
| `cheaper_than_anchor`                | `CollegesDao.kt:1206-1208` `net_price_per_year_usd < ?`; the anchor's own figure read at `SimilarCollegesTool.kt:425-432` from `SimilarityAnchor.netPricePerYearUsd` (`SimilarityQuery.kt:88`); the strictness rule at `SimilarityQuery.kt:193`              | "like Bowdoin but cheaper" compares Bowdoin's private single price against a public's IN-STATE price — the exact defect RFC 157 D-G names                                                              | YES. And note the asymmetry: a private anchor's price IS every family's, so the comparison is half-right, which is worse than uniformly wrong.                                                                                                |
| the anchor's price, spoken back      | `SimilarCollegesTool.kt:775-776` — `"average annual net price below ${...} (${query.anchor.name}'s own net price)"`                                                                                                                                          | the coach reads a constraint sentence with a dollar figure in it and says it aloud                                                                                                                     | YES — the spoken echo would have to name the basis or the constraint would state a false one.                                                                                                                                                 |
| result row                           | `CollegeMatchRow.kt:73` `net_price_per_year_usd`, `:78-92` `net_price_by_income_band`                                                                                                                                                                        | the model sees the number per school                                                                                                                                                                   | Row already carries `state` (`:60`) and `control` (`:64`), so the model COULD reason. Today it is only told the basis in prose (see below).                                                                                                   |
| the basis label RFC 157 landed       | `CollegeMatchRow.kt:32-34` `NET_PRICE_BASIS_NOTE`, appended to `CollegeSearchTool.kt:221` and `SimilarCollegesTool.kt:1065`                                                                                                                                  | "At a public school net_price_per_year_usd and every net_price_by_income_band amount are figures for students paying in-state tuition: never offer one to a family from another state as their price." | This is the current mitigation, and it is DESCRIPTION-level only. It does not touch the filter, the sort or the ranking — the model is told not to SAY the number, while the tool has already used it to decide which schools it sees at all. |
| filter vocabulary                    | `CollegeQueryVocabulary.kt:139` "Maximum average annual net price, in whole US dollars."; `:337` spoken echo "average annual net price at or below $N"                                                                                                       | the model's own description of what it filtered on                                                                                                                                                     | YES — the description is the contract; a residency-aware filter that keeps this wording lies.                                                                                                                                                 |
| `CollegeSearchTool` sort description | `CollegeSearchTool.kt:57` `"net_price_per_year_usd": cheapest first`                                                                                                                                                                                         | model-facing                                                                                                                                                                                           | YES.                                                                                                                                                                                                                                          |
| `FitLensService`                     | `FitLensService.kt:709-719` prints `inStateNetPricePerYearUsd=[...]` per candidate, under the sentence at `:705-708`                                                                                                                                         | the fit-lens model picks a school partly on a number RFC 157 already labels as in-state                                                                                                                | YES, and here it is CHEAPEST to fix: see section 2 — `FitLensService` already has the student id AND a live session.                                                                                                                          |
| `CollegeMatch` model                 | `CollegeMatch.kt:44-49`                                                                                                                                                                                                                                      | carrier                                                                                                                                                                                                | Field names/doc only.                                                                                                                                                                                                                         |

Read path for the axis into the row: `CollegesDao.kt:1607` `netPricePerYearUsd`,
`:1611` `pricePercentile`.

**One structural observation.** The description-level label
(`NET_PRICE_BASIS_NOTE`) protects what the coach SAYS. It cannot protect what
the tool RETURNS, because the filter and the sort have already run. A family who
asks "show me schools under $30k" gets a result set, not a number, and there is
no sentence in the world that repairs a result set built on the wrong axis. That
is the honest statement of why brief 0005 exists at all.

---

## 2. IS RESIDENCY AVAILABLE AT SEARCH TIME?

**Short answer: NOT to the tool. But it IS already in the coach's own context on
every turn.** That second half is the finding that changes the option space.

### 2a. The tool has no student

`CollegeSearchTool` and `SimilarCollegesTool` are constructed ONCE at boot,
process-wide, with no student in scope:

- `rest-server/.../Application.kt:352-385` builds one `ToolRegistry`; `:355`
  `CollegeChatTool(CollegeSearchTool(collegeSearchService, codebook))`,
  `:364-368` the similar-colleges pair.
- `CollegeSearchTool.kt:32-36` — constructor takes a `CollegeSearchService` and
  a `Codebook`. `:79` `suspend fun execute(input: JsonObject)`. No student id,
  no session.
- `CollegeChatTool.kt:10-12` and `SimilarCollegesChatTool.kt:11-12` are
  `DelegatingChatTool`s — `DelegatingChatTool.kt:23-29`, "name, definition and
  execute are the wrapped tool's own, unreshaped".
- `chat/.../ChatTool.kt:13-23` — the interface's `execute` takes only the
  model's JSON.

So the search path today has **no access to the money profile.** Confirmed.

### 2b. The plumbing already exists, and the dispatcher already supports it

- `service/.../StudentScopedChatTool.kt:33-40` — an abstract `ChatTool` whose
  `execute(studentId, input)` receives the turn's student. The one-argument
  inherited `execute` is `final` and returns
  `"tool [$name] requires a student-scoped dispatch"` (`:40`).
- `CoachingService.kt:726-731` — dispatch is a `when` on TYPE:
  `is StudentScopedChatTool -> tool.execute(studentId, toolUse.input)` /
  `else ->
  tool.execute(toolUse.input)`. **Nothing in the dispatcher needs to
  change.** Making `CollegeChatTool` student-scoped is a superclass swap at
  `CollegeChatTool.kt:12`.
- The money-profile read that would follow is `CollegeCostService.kt:816-836`
  (`moneyProfileOf` → `MoneyProfilesDao.findActiveByStudent`), reading
  `residency_state` / `residency_status` at `MoneyProfilesDao.kt:43-44`. A
  `NotFoundException` maps to `ALL_UNANSWERED` (`:838-840`) — the unknown case
  already has a named value.

### 2c. The module boundary — checked, and it is NOT the obstacle

- `college/build.gradle.kts:16-17` — `:college` depends on `:common` and `:db`
  ONLY.
- `service/build.gradle.kts:11-17` — `:service` depends on `:db`, `:chat`,
  `:college`, ...

So `:college` **may not** depend on `:service`, confirmed. But it does not need
to:

- `MoneyProfilesDao` and `MoneyProfile` live in `:db`
  (`db/src/main/kotlin/ed/unicoach/db/dao/MoneyProfilesDao.kt`,
  `db/src/main/kotlin/ed/unicoach/db/models/MoneyProfile.kt`), which `:college`
  already depends on. So the residency VOCABULARY is reachable from `:college`
  with no new edge.
- The cleaner shape crosses nothing: `:service` (which has the student id) reads
  the profile and passes a plain residency VALUE down into the `:college` tool's
  `execute`. `:service` → `:college` is an existing edge.

**The cost is not the module graph. It is the `DelegatingChatTool` contract.**
That class exists to state one fact — "the definition and execute are the
wrapped tool's own, unreshaped" (`DelegatingChatTool.kt:19-24`) — and a
residency-carrying search tool is by definition a reshaped execute. Two of the
three delegating tools would leave the abstraction RFC 154 landed at n=3. That
is a real design cost and it is a DESIGN cost, not a plumbing one.

### 2d. FitLensService is the easy one

`FitLensService.discover(studentId)` (`:148`) → `readPhase(studentId)` (`:167`)
already opens a session and reads `StudentsDao`, `ClaimsDao`,
`CollegeListEntriesDao`, `FitSuggestionsDao`, `FitLensRunsDao` (`:169-213`) on
it. Adding one `MoneyProfilesDao.findActiveByStudent` read there is one line in
an existing read phase, in `:service`, crossing no boundary. The residency is
then available at `:709-719` where the candidate prompt is built.

### 2e. THE COACH ALREADY KNOWS — this is the load-bearing find

`CoachingService.composeSystem` writes the money profile into the SYSTEM PROMPT
of every turn:

- `CoachingService.kt:272` (`startConvo`) and `:337` (`postTurn`) —
  `val moneyProfile = activeMoneyProfile(session, studentId)`. The comment at
  `:332-336` is explicit: "The money-profile block (RFC 134) IS composed on
  every turn".
- `CoachingService.kt:886-889` —
  `"- state of residency: " + renderMoneyField(
  moneyProfile.residencyStatus, moneyProfile.residencyState, "residency_state", moneyProfile)`,
  rendering `answered (WA)` / `declined` / `unanswered` (`:914-935`).

So on the turn where the coach calls `search_colleges`, the coach's own context
ALREADY contains "state of residency: answered (WA)" — while the tool it is
calling does not. **The residency is in the room; it just is not in the call.**
That asymmetry is what makes option (iii) in section 4 cheap and option (i)
indefensible.

---

## 3. What fraction of calls would have a KNOWN residency?

### 3a. How residency gets answered — the prompt sequence

The current seed is `db/schema/0076.seed-coach-system-prompt-v16.sql` (RFC 157
D-F). The ONLY instruction that makes the coach ask for residency is attached to
a COST result:

> `0076:103-110` — "When a college's result carries a **precision_offer**, it
> lists the upgrades that result can take, in the order to raise them. A
> residency_state offer comes first: ask what state the family lives in before
> you raise household income, and say what it unlocks — whether they would pay
> the in-state or the out-of-state published price at that public school. **Only
> ask when the result offers it**; a private school has one price and the
> question buys nothing there."

That `precision_offer` is produced by `CollegeCostService`
(`PrecisionOffer.RESIDENCY` at `CollegeCostService.kt:615-631`), which is
reached only through `college_cost_profile` — a `StudentScopedChatTool`
(`CollegeCostChatTool.kt:35-37`) that reads **the student's college list**
(`:51-59`).

The search paragraphs say nothing about state or residency at all:

- `0076:239-246` — the `search_colleges` paragraph ("Reach for search_colleges
  instead when the student describes the kind of school they want -- by subject,
  place, size, selectivity or price").
- `0076:247-265` — the whole `similar_colleges` paragraph.

Grep evidence, run on the seed:

```
$ grep -n "state" db/schema/0076.seed-coach-system-prompt-v16.sql | grep -v 'in-state\|out-of-state'
105:  || 'take, in the order to raise them. A residency_state offer '
106:  || 'comes first: ask what state the family lives in before you '
236:  || 'which one they mean, telling them apart by the city and state the '   <- find_college disambiguation
268:  || 'comes after residency_state and income_band, and it is '
319, 327: the RFC 157 blended-figure paragraph
```

**Conclusion, and it is a strong one: the ordering runs the WRONG WAY for
search.** The product flow is discover → shortlist → list → cost. Residency is
asked at the COST step, which is downstream of search by construction. A
student's FIRST search — the one that decides which schools they ever see — is
the call least likely to have a residency on file. The residency question is
sequenced AFTER the student searches, not before.

I could not measure the real answered-rate: this is a dev checkout, and I did
not query `money_profiles` for production-shaped data (there is none here).
**The fraction is not measurable from this repo.** What IS measurable is the
structural ordering above, and the corpus exposure below.

### 3b. What is at stake when residency IS unknown — measured

```
$ nix develop -c bin/db-query "select control, count(*) as rows, count(net_price_per_year_usd)
  as with_net_price, count(net_price_percentile_share) as with_percentile
  from college_search_index group by control order by rows desc;"

      control       | rows | with_net_price | with_percentile
--------------------+------+----------------+-----------------
 private_for_profit | 2325 |           1884 |             229
 public             | 2047 |           1796 |             775
 private_nonprofit  | 1901 |           1354 |            1220
```

The ranked universe (rows carrying a percentile — the default search universe,
pinned by `CollegeSearchIndexRebuildTest.kt:634` "the percentile corpus is
exactly the default search universe") is 2,224 rows, of which **775 (34.8%) are
public**:

```
$ nix develop -c bin/db-query "... select state, c as own_state_public,
  (total public) - c as other_state_public, pct of universe ... limit 6"

 state | own_state_public | other_state_public | pct_of_universe_wrong_basis
-------+------------------+--------------------+-----------------------------
 TX    |               55 |                720 |                        32.4
 CA    |               48 |                727 |                        32.7
 OH    |               45 |                730 |                        32.8
 FL    |               41 |                734 |                        33.0
 NY    |               39 |                736 |                        33.1
 WA    |               39 |                736 |                        33.1
```

Read this as the exposure floor. **Even for a family in Texas — the state with
the most public institutions in the corpus — 32.4% of every ranked search
universe carries a price on a basis that is not theirs.** No state gets below
that; the ceiling is 34.8% for a family from a state with no publics in the
corpus. The largest single state holds only 7.1% of the public rows (same query
family, `biggest_state_pct_of_public = 7.1`), so "most publics are somebody
else's" is true for every family in the country.

### 3c. There IS an out-of-state tuition figure in `colleges` (context, not a recommendation)

```
$ nix develop -c bin/db-query "select control, count(*) rows,
  count(tuition_and_fees_in_state_per_year_usd) in_state,
  count(tuition_and_fees_out_of_state_per_year_usd) out_of_state from colleges group by control;"

 control | rows | in_state | out_of_state
---------+------+----------+--------------
       1 | 2047 |     1689 |         1689
       2 | 1901 |     1445 |         1445
       3 | 2325 |      478 |          478
```

1,689 of 2,047 publics carry an out-of-state tuition figure today, in `colleges`
(`CollegesDao.kt:288` names both columns). The index carries neither. This is
stated as fact, not as a proposal: it is TUITION, not a total, and RFC 149
forbids manufacturing an out-of-state total by adding it to a blended average.
Any use of it in the index is a new index column and hits the DDL gate.

---

## 4. The three candidate answers for an UNKNOWN residency

Setting, restated: unlike `CostReportPage` (RFC 157 D-A: "this page has no coach
in the loop"), **there IS a coach in the loop here**, and section 2e shows the
coach already holds the residency. That changes the arithmetic of all three.

### (i) Rank on in-state anyway (today's behaviour)

- **Implementation cost: zero.** It is the status quo.
- **Contract cost: zero.** No test changes.
- **What it costs in truth:** it is the one option RFC 157 D-A already rejected
  in its own domain — "a number printed beside a family's own name is read as
  theirs whatever the footnote says". A ranking is worse than a number, because
  a ranking is a number the family never sees and cannot check. The
  `NET_PRICE_BASIS_NOTE` label is not a defence here: it constrains what the
  coach may SAY, and by the time the coach reads it the filter has already run.
- **Verdict: not defensible as the answer for residency-unknown, but see the
  note below** — it IS defensible as the answer for residency-DECLINED, since
  D-B's "an unanswered residency is not licence to hide the only price we hold"
  applies with full force to a search too.

### (ii) Refuse to rank or filter on price

- **Implementation cost: moderate, and mostly in the vocabulary.** The mechanics
  exist: `maxNetPricePerYearUsd` is optional (`CollegeQuery.kt:51`), the sort
  has four other modes (`CollegesDao.kt:1896-1915`), and the similarity axis set
  already survives a dropped axis (`excluded_unknown`, `CollegesDao.kt:948-973`;
  the tool refuses a `cheaper_than_anchor` it cannot compute at
  `SimilarCollegesTool.kt:429-431`). So "refuse" has a landing place in every
  one of the four consumers.
- **Contract cost: high.** The tool descriptions promise the filter
  unconditionally (`CollegeQueryVocabulary.kt:139`, `CollegeSearchTool.kt:57`,
  `SimilarCollegesTool.kt:88-92`), and the definitions are STATIC —
  `ChatTool.definition` is a `val` built once at boot
  (`CollegeSearchTool.kt:38`), so the schema cannot advertise a conditional
  field. The model would ask for a filter it is told exists and be refused at
  runtime, which is exactly the shape RFC 150 D55 spent effort removing.
- **What it costs in product terms:** it gates. "Show me schools under $30k" is
  answered with a question. That is a direct violation of guided-not-gated
  (brief 0001 D11, restated in RFC 157 D-B: "Never hide a price because that
  question is unanswered, and never make an answer wait on it" —
  `0076:330-331`).
- **Verdict: contradicts a standing decision.** Would need that decision
  overturned, not worked around.

### (iii) Rank, and state the basis to the coach

- **Implementation cost: LOW for the labelling half, which is already done**
  (`CollegeMatchRow.kt:32-34` reaching both tools). The new work is per-ROW
  rather than per-description: the row already carries `control`
  (`CollegeMatchRow.kt:64`) and `state` (`:60`), so a per-row applicability fact
  — the search analogue of RFC 157's `BlendedFigureApplicability` — is derivable
  **with no new index column and no DDL gate**. `control = 'public'` and `state`
  are both in the index today (`0064:99`, `0064:109-111`).
- **Contract cost: additive.** A new key on the result row, and one sentence in
  each description. No filter is removed, no sort disappears, no existing key
  changes type.
- **What it does NOT fix:** the ordering and the filter still run on the
  in-state number. This is honest labelling over a wrong ranking. It is strictly
  better than (i) and strictly weaker than a residency-aware rank.
- **Verdict: the cheapest real improvement, and the natural continuation of RFC
  157 D-A's vocabulary into the search domain.** It is also the only one of the
  three that exploits section 2e: the coach holds the residency, so a per-row
  "this figure is/is not on your family's basis" is a fact the coach can ACT on
  in the same turn.

### A fourth option the question does not list, stated because the evidence points at it

Because `state` and `control` are already in the index, a residency-aware
**partition** is available without any new column: at a known residency, a
public outside the family's state can be marked, ordered last within ties, or
excluded from the price FILTER (kept in the result, counted in
`excluded_unknown`'s existing machinery at `CollegesDao.kt:948-973`) rather than
silently kept. That is (iii) plus a ranking consequence, and it is the only
shape I found that touches the ORDER without inventing a price. It still needs a
decision on the pinned percentile corpus (RFC 157 D-G's open question), which no
evidence in this repo resolves.

---

## 5. Private-only result sets

**Control is already known everywhere on this path**, so yes, a private-only
result set can skip all of this:

- In the index: `0064:109-111`,
  `control TEXT NOT NULL CHECK IN ('public', 'private_nonprofit',
  'private_for_profit')`
  — the WORD, not a code. `0064:248-251` records the deliberate decision that
  `control` gets no btree index (a two-value filter selects most of the table),
  which does not affect readability, only plan shape.
- As a filter the model may pass: `CollegeQueryVocabulary.kt:126` (`"control"`
  property), parsed at `:491-493`.
- On the wire, per row: `CollegeMatchRow.kt:64` `put("control", match.control)`,
  with the comment at `:61-63` — "the search index stores our vocabulary, so
  there is no code on this path to leak".
- In the domain rule this would reuse: RFC 135 / RFC 157 D-A, "At a private
  college the distinction does not exist, so both figures print unchanged"; the
  same rule is expressed in code at `CollegeCostService.kt:185-190` ("Code 2 —
  one price, no residency distinction", "Code 3 — one price, no residency
  distinction").

So the applicability test for a search row is a pure function of two columns the
index already has: `control != 'public'` → applies to everyone;
`control == 'public' && state == family state` → applies;
`control == 'public' && state != family state` → not this family's; residency
unknown → basis stated. That is RFC 157's three-outcome vocabulary
(`BlendedFigureApplicability`: `APPLIES` / `WITHHELD` / `BASIS_STATED`) with the
same shape.

Measured relevance of the skip: 1,449 of the 2,224 ranked rows (65.2%) are
private and would short-circuit unchanged — from the control-count query in 3b
(1,220 private_nonprofit + 229 private_for_profit with a percentile).

**One caution.** A private-only result set is safe only if the RESULT SET is
private-only, not if the QUERY asked for private. The percentile axis
(`net_price_percentile_share`, `CollegesDao.kt:2426`) is computed over the WHOLE
universe including publics, so a private-only result still carries percentile
positions calibrated against in-state public prices. A private-only
`search_colleges` result is clean; a private-only `similar_colleges` result
still ranks on a mixed-basis ladder.

---

## 6. Tests that would fail or need new assertions

Read from source. NOT from an executed run — I ran no Kotlin tests (nothing
changed).

**Will break outright if the search tools become student-scoped:**

1. `service/src/test/kotlin/ed/unicoach/coaching/SimilarCollegesChatToolTest.kt:61-68`
   — `execute delegates and returns the wrapped tool's object` calls the
   ONE-ARGUMENT `adapter.execute(badInput)`. Under `StudentScopedChatTool` that
   path returns `"tool [...] requires a student-scoped dispatch"`
   (`StudentScopedChatTool.kt:40`), so the equality assertion fails. Same file
   `:54-57` asserts `adapter.definition == wrapped.definition`, which survives
   only if the definition is unchanged.
2. `service/src/test/kotlin/ed/unicoach/coaching/CollegeChatToolTest.kt:46, :52`
   — the identical two tests for `search_colleges`. Same failure.
3. `service/src/test/kotlin/ed/unicoach/coaching/FindCollegeChatToolTest.kt:44, :50`
   — same `DelegatingChatTool` shape; breaks only if `find_college` is scoped
   too (it need not be).

**Will need new assertions if the row or descriptions gain a residency fact:**

4. `college/src/test/kotlin/ed/unicoach/college/CollegeSearchToolTest.kt` —
   `:182` pins the input-schema key set including `maxNetPricePerYearUsd`;
   `:452-479` pins the exact result-row keys and the `net_price_by_income_band`
   shape; `:530-535` asserts the description carries no quintile code and no
   `"NPT4"`, which constrains the wording of any new basis sentence.
5. `college/src/test/kotlin/ed/unicoach/college/SimilarCollegesToolTest.kt` —
   `:334` pins the tool's own field set; `:393` and `:420-438` pin
   `cheaper_than_anchor` behaviour and the
   `excluded_unknown.net_price_per_year_usd` count; `:1082-1105` pins that an
   unreported price is excluded and never "maybe cheaper"; `:1296` names
   `net_price_per_year_usd` in a key list.
6. `college/src/test/kotlin/ed/unicoach/college/CollegeSearchServiceTest.kt:60, :82-87`
   — the `CollegeMatch` fixture builder defaults `netPricePerYearUsd = 20000`; a
   new residency-derived field lands here first.
7. `db/src/test/kotlin/ed/unicoach/db/dao/CollegeSearchIndexRebuildTest.kt:573-599`
   — percentiles are computed over the default universe only; `:634-676` is the
   pin "the percentile corpus is exactly the default search universe". **Any
   residency-aware percentile breaks this pin, and it is the pin that most
   directly encodes the current design.**
8. `db/src/test/kotlin/ed/unicoach/db/dao/CollegesDaoTest.kt` and
   `db/src/test/kotlin/ed/unicoach/db/dao/CollegeFixtures.kt` — column
   round-trip and every fixture that sets `netPricePerYearUsd`; a new index
   column changes both.
9. `service/src/test/kotlin/ed/unicoach/coaching/fitlens/FitLensServiceTest.kt`
   — the candidate prompt currently promises `inStateNetPricePerYearUsd`
   (`FitLensService.kt:706`); a residency read in `readPhase` changes both the
   prompt text and the read-phase fixture setup.
10. `service/src/test/kotlin/ed/unicoach/coaching/CoachingServiceTest.kt:392-396`
    — builds `ToolRegistry` from bare `ChatTool`s; a scoped search tool
    exercises the `is StudentScopedChatTool` dispatch arm
    (`CoachingService.kt:729`) and wants a case here.
11. `service/src/test/kotlin/ed/unicoach/coaching/SystemPromptCatalogTest.kt:964`
    — asserts the v16 blended-figure paragraph verbatim. A v17 seed that extends
    the rule to search needs a new case, and the existing
    `assertFalse(appended.contains("sticker"))` guards constrain wording.
12. `service/src/test/kotlin/ed/unicoach/coaching/costs/ForbiddenCostArithmeticTest.kt:41, :144`
    — the RFC 149 D-F source scan. Any new derived price expression in
    `:college` or `:db` must not trip it; note its scan currently covers the
    cost surfaces' vocabulary, so extending the guard's SPAN to the search
    modules is itself a decision, not an accident.
13. `rest-server/src/test/kotlin/ed/unicoach/rest/ConvoToolLoopRoutingTest.kt:59`
    — wires the real `ToolRegistry` with `search_colleges` over a live DB; a
    scoped tool changes the wiring under it.
14. `college/src/test/kotlin/ed/unicoach/college/CollegeScorecardRealDataTest.kt:260`
    — the RFC 157 data invariant (`COSTT4_A` inside the in-state span).
    Untouched by a search change, listed because it is the falsifier for the
    premise this whole brief rests on and must keep passing.

**No test asserts `NET_PRICE_BASIS_NOTE` reaches either tool description.**
`grep -rn "another
state" --include=*.kt .` returns four hits, and the only test
among them is `SystemPromptCatalogTest.kt:964`, which pins the PROMPT paragraph,
not the tool description (`CollegeMatchRow.kt:34`). RFC 157's Design 10 label is
therefore unpinned on the search side — worth adding whatever this brief
decides.

---

## Honest limits of this report

- I did not measure the real-world rate at which residency is answered before a
  search. No production data is reachable from this checkout. Section 3a's
  conclusion is STRUCTURAL (the prompt only asks at the cost step, which is
  downstream of search) and I state it as that.
- I ran no Kotlin tests. The section 6 list is read from test source; each entry
  says why from the assertion it names, but no entry is confirmed by an observed
  failure.
- The corpus numbers in 3b/3c come from THIS checkout's dev database (6,273
  index rows). If that is not the shipped snapshot, the percentages move; the
  qualitative claim (a family is out-of-state for the overwhelming majority of
  publics) does not.
- I did not evaluate whether a residency-aware percentile is computable at read
  time within the query's existing shape. That is a performance question and it
  needs a plan, not a grep.
