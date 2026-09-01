# RFC 153 — Similar colleges, decided per conversation

Status: proposed Product brief: `product/0004-college-search-index`, slice
**`search/04/similar-colleges`** (S4). Base: `main@1d94cb9a`. Lands on top of
RFC 150 (`search/03b/the-index`), whose percentile columns are the only numeric
substrate this slice ranks on.

## Why this exists

"Schools like Bowdoin" is the second of the two founding queries of brief 0004,
and today the product cannot answer it at all. `search_colleges` filters; it has
no notion of an anchor. A student who has one school they love has to translate
that love into filter words themselves — small, private, rural, selective —
which is precisely the work they came to a coach to avoid.

Gate 1 originally answered this with a precomputed `college_similarity` table:
peer strata, a weight row, a `method_version`, top-20 stored per college. Ian
overturned it (2026-08-27, verbatim): _"What does it mean to be similar? I'm not
sure it even makes sense to pre-define it"_ — and, on giving up reproducibility,
_"This is okay to give up"_. **D8 as amended is the design this RFC
implements**: no similarity tables, no method registry, no stored weights. Phase
2 precomputes facts only — the four percentile columns RFC 150 landed — and
"similar" is a query-time, per-call choice of axes, weights and constraints,
with the defaults written visibly in the tool definition.

The consequence worth stating plainly: two identical asks a week apart may
return different lists if the index was rebuilt between them, and the same ask
phrased differently may pick different axes. That is the trade Ian accepted. If
a future surface needs a stable peer list — a saved comparison, a shared report
— it pins a preset at that point and stores the pinned list, not a method.

## What the index gives us, and what it does not

RFC 150 wrote four percentile columns
(`0064.create-search-index-and-subjects.sql`), each a
`percent_rank() OVER (ORDER
BY col)` computed by `CollegesDao.rankPercentiles`
(`CollegesDao.kt:1695`) over a `universe` CTE gated by
`DefaultUniverse.sql("i.")`:

    undergrad_enrollment_percentile_share    -- size
    admission_rate_percentile_share          -- selectivity, admit side
    sat_average_percentile_share             -- selectivity, score side
    net_price_percentile_share               -- price

Two properties of that computation are load-bearing here and neither is
incidental:

1. **A percentile is NULL for every row outside the default universe.** The
   ranking CTE is gated. An inactive or non-four-year college has no percentile
   at all, not a low one.
2. **A percentile is NULL per-axis when the input is missing.** RFC 150's
   coverage is not uniform — admission rate and SAT are the thin columns — so a
   real candidate routinely has two of four.

Percentile ranks are also _relative_, which is exactly what makes them the right
distance substrate: raw enrollment is a long tail where the gap from 1,800 to
2,400 students is arithmetically trivial and, to a student, the whole question.
Ranks make every axis commensurable without a scaling constant anybody would
have to defend.

The index carries no percentile for outcomes, by ruling. Ian, 2026-08-27,
verbatim: _"drop both outcome percentiles"_. Earnings, debt and graduation stay
in the index as raw, cited values usable as filters and by the cost tools;
**they are never a similarity axis here.** Field-level earnings (Scorecard FoS)
is the honest path for earnings questions and is not this slice.

## Decisions

**D62 — one query, no new tables, no new migration except the prompt.** The tool
is a single `SELECT` over `college_search_index`: the default-universe
predicate, the caller's hard constraints, an `ORDER BY` on a computed distance
expression, `LIMIT ≤ 10`, then the existing payload read-back join. Nothing is
stored. The only migration this slice lands is the coach system prompt seed
(D71). There is no DDL to sign off.

The distance is layered OVER `SearchPlan` — the single home of a search
predicate and of its binds — by a `SimilarityPlan` that holds the two together,
so the ranked query and its count query cannot drift apart while an ordinary
search still carries no ranking state it must never use. It does not become a
second query path parallel to `search`.

**D63 — the anchor is resolved by the existing name path, and ambiguity refuses
rather than guesses.** `CollegeSearchService.searchByName`
(`CollegesDao.kt:1282`) is the fuzzy resolver the picker and
`update_college_list` already use — three arms: `one_keystroke_off` over
`college_name_words`, `search_text ILIKE`, and aliases. The tool reuses it
verbatim; no second notion of "which college did they mean" enters the codebase.

The input accepts a `college_id` **or** a `name`. Given a name:

- no match → refusal `{"error": ...}` naming the string that failed;
- exactly one match → proceed;
- more than one match, but exactly one of them whose name equals the string
  case-insensitively → proceed with that one;
- otherwise → refusal `kind: anchor_ambiguous` carrying the candidate
  `college_id`/`name`/`state` list, so the coach asks which one.

Guessing the top match here is the one failure the student cannot detect: a peer
list for the wrong Washington reads perfectly fluent and is entirely wrong. The
refusal costs one conversational turn.

The exact-match arm is not that guess, which is why it is stated rather than
avoided: the fuzzy resolver returns near-misses by design, so "Bowdoin College"
matches "Bowdoin College" and several schools whose names merely resemble it.
Taking the name the student typed IN FULL is reading their words, not picking
for them. The condition is that **the exact match is itself unique** — two
colleges both named "Columbia College" refuse exactly as before, because there
the student's own words genuinely do not say which one.

**D64 — an anchor with no percentiles is a named refusal, not an empty page.**
If the resolved anchor is outside the default universe (closed, not a
four-year), every percentile on its row is NULL and a distance query against it
would rank the whole database by nothing. That returns
`kind: anchor_not_rankable` with the reason in words — "Antioch College is not
an active four-year institution, so it has no comparable size, selectivity or
price position" — following the `INDEX_NOT_BUILT` precedent that a named refusal
beats a zero page.

A rankable anchor whose every requested axis was dropped (D67, or D68's
selectivity relaxation) is the same shape and a **different fact**, so it gets
its own kind: `no_rankable_axis`, whose `detail` joins the drop reasons. One
machine-readable kind meaning two things is not machine-readable.

**D65 — five axes, two kinds, and the bare ask uses three of them.** The axes
are named in the tool definition, not inferred:

| axis          | kind        | source                                                                                                             |
| ------------- | ----------- | ------------------------------------------------------------------------------------------------------------------ |
| `size`        | numeric     | `undergrad_enrollment_percentile_share`                                                                            |
| `selectivity` | numeric     | mean of `admission_rate_percentile_share` (inverted) and `sat_average_percentile_share`, over whichever is present |
| `price`       | numeric     | `net_price_percentile_share`                                                                                       |
| `setting`     | categorical | `locale` slug — 0 if equal, 1 if different                                                                         |
| `subjects`    | categorical | Jaccard distance over `subject_slugs`: `1 - \|A∩B\| / \|A∪B\|`                                                     |

Defaults for a bare "schools like X", per brief 0004 D20: axes **size,
selectivity, setting**, weights **1.0** each, constraint **same `control`**, and
the default universe (active, four-year). Price is deliberately not a default —
"like Bowdoin" is a question about character, and silently ranking on price
turns it into a budget question the student did not ask.

`selectivity` inverts the admission-rate percentile so that both of its inputs
point the same way (higher = harder to get into) before they are averaged.
Without the inversion a school with a high admit rate and a high SAT — which
exists — would average to a meaningless middle.

**D66 — weighted mean absolute difference, over the axes that have data.**

    distance = Σ wᵢ · dᵢ  /  Σ wᵢ        over axes i where BOTH anchor and
                                          candidate have a value

Each `dᵢ` is in `[0, 1]`, so `distance` is too, and 0 means "identical on every
axis we could measure". Mean absolute difference rather than Euclidean because
the coach has to be able to say what it did in one sentence, and "the average
gap across size, selectivity and setting" is that sentence. Squared distance
buys nothing on three-to-five bounded axes except an explanation nobody will
give.

Per-axis weights are accepted, clamped to `[0.1, 10.0]`, and then normalised by
the denominator above — so weights are ratios, and scaling all of them changes
nothing **while every one of them stays inside the range**. The clamp runs first
and is not scale-preserving: `{size: 10, price: 1}` scaled by ten becomes
`{10, 10}`, an equal weighting the caller never wrote and one that is then
reported back in `axes_used` as fact. The range is wide enough that a sane
weighting never reaches it, and both the KDoc and the `weights` schema
description the model reads say exactly this rather than claiming unconditional
scale-invariance. The clamp applies **within the positive domain**: a NEGATIVE
weight is refused by name, not clamped up to `0.1`. A negative number is not a
small ratio, it is the opposite instruction, so repairing it would invent an
input the model never wrote and then report it back in `axes_used` as fact. `0`
is the caller saying "count this as little as possible" and clamps, as before. A
weight naming a word that is not an axis is refused by name, and so is a weight
naming an axis **this call does not rank on**, and so is `{}` — an object that
weights nothing is the same caller mistake `axes: []` is refused for, with no
member to name. All of them are the caller saying something the call cannot
honour, and the refuse-unknown-word policy the shared vocabulary already applies
says so rather than dropping it silently.

**D67 — an axis with no data is dropped, counted, and reported; it never scores
zero.** This is brief 0004 D11 ("unknown is not no") applied to ranking, where
the temptation to substitute is strongest. Three cases, three behaviours:

- **The anchor lacks the axis.** The axis is dropped for the whole query and
  named in the response:
  `axes_dropped: [{axis: "selectivity", reason: "Bowdoin's
  admission rate and SAT average are both unreported"}]`.
  Every candidate is then judged on the same footing.
- **A candidate lacks the axis.** That candidate is scored on the remaining axes
  and reports `axes_scored`, so a college matched on one axis out of three is
  visibly not the same claim as one matched on three.
- **A candidate has no axis in common with the anchor.** It is excluded, and the
  count appears in `excluded_unknown` under the axis names — the same per-filter
  count shape `search_colleges` already emits.

Both counts this tool reports are **constraint-relative**: `excluded_unknown` is
measured over the colleges the caller's own constraints admit, not over the bare
default universe, so it is commensurable with `total_candidates` beside it. Over
the universe an ask narrowed to one state could report more unjudgeable colleges
than it considered candidates, and the prompt tells the coach to read both
numbers aloud. The constraints an arm drops are the filters over the **columns
that arm itself reads** — a filter over a nullable column is exactly what
removes the rows that cannot be judged on it, so counting them inside its own
clause would always answer zero and D68's "excluded and counted, never kept as
maybe-cheaper" would have nothing to report. A filter arm reads its own column;
a ranked-AXIS arm names the index columns its judgeability predicate reads
(`price` reads `net_price_percentile_share` and `net_price_per_year_usd`), so
`cheaper_than_anchor` beside the `price` axis really is dropped for that arm
rather than matched by a string equality an axis word can never satisfy.

Each key of `excluded_unknown` is ONE subject: a supplied filter's column, or a
ranked axis, never both. When a ranked axis reads the column a supplied filter
also names, the count is reported **once, under the axis word** the caller asked
in — `cheaper_than_anchor` with the `price` axis reports `price`, not a second
`net_price_per_year_usd` key describing the same silence in a schema
identifier's vocabulary.

Substituting the median for a missing percentile would be the obvious
alternative and is refused: it manufactures a fact and then ranks on it, which
is the exact defect the tri-state rule exists to prevent.

**D68 — "but cheaper" and "where I'd likely get in" are anchor-relative
constraints, because the coach cannot know the anchor's numbers before it
calls.** Two boolean inputs, each expanded server-side against the resolved
anchor and echoed back in words:

- `cheaper_than_anchor: true` → `net_price_per_year_usd < <anchor's>`, reported
  as "net price below $28,400 (Bowdoin's)". Candidates with an unreported net
  price are excluded and counted, never kept as "maybe cheaper".
- `easier_to_admit_than_anchor: true` → `admission_rate_share > <anchor's>`, and
  **`selectivity` is dropped as an axis for that call**, named in `axes_dropped`
  with the reason. Keeping it would leave the ranking pulling candidates back
  toward the anchor's selectivity while the constraint pushes them away — the
  two would fight, and the student would get the least-relaxed schools that
  technically qualify. Relaxing selectivity means relaxing it, deliberately,
  which is what the acceptance criterion asks for.

Either flag against an anchor that does not report the figure is a refusal by
name — "[cheaper_than_anchor] cannot be answered: Bowdoin College does not
report an average annual net price, so there is no figure to be cheaper than".
The constraint is a sentence about the anchor's own number; without it there is
nothing to expand, and an unfiltered page would silently answer a different
question.

No margin, no fudge factor on either: strictly cheaper, strictly higher admit
rate. A margin is a product judgement nobody has made, and an invented one would
be invisible in the answer.

**D69 — every other constraint is the vocabulary that already exists.**
`CollegeQueryVocabulary` (23 fields) is reused wholesale, unchanged: states,
region, locale, control, enrollment bounds, admission-rate bounds, max net
price, test policy, religion, Carnegie, athletics, ROTC, study abroad, housing.
Same words, same refusals for unknown words, same "no bare source code"
property. The model does not learn a second dialect for the same concepts, and
this slice adds no new filter vocabulary.

The anchor itself is always excluded from its own results.

**D70 — the response is the query, in words.** Success payload, `snake_case` to
match `search_colleges`:

    anchor              {college_id, name, state, control}   — what it resolved to
    axes_used           [{axis, weight}]                      — after drops
    axes_dropped        [{axis, reason}]                      — never silent
    constraints_used    [sentence, ...]                       — words: EVERY
                                                                constraint the SQL
                                                                applied, incl. the
                                                                expanded
                                                                anchor-relative ones
    colleges            [{college_id, name, ..., distance, axes_scored}]
    total_candidates    int
    excluded_unknown    {axis_or_filter: count}
    source_years        {source: year | {earliest, latest}}  — as search_colleges

`constraints_used` is the whole applied predicate, not a summary of it: the
default universe in `DefaultUniverse`'s own words, then the same-control
sentence, then every `CollegeQueryVocabulary` filter the call carried — rendered
by the vocabulary itself, so a filter added there is reported here with no
second list to forget — then the two anchor-relative constraints. The control
sentence says WHOSE control it is: the anchor's own by default, and the
caller's, named as the caller's, when a `control` filter was written; an anchor
whose stored control this vocabulary does not define states that no same-control
constraint could be applied, rather than claiming one the SQL never ran.

`distance` is emitted rounded to three decimals and described in the tool
definition as a rank aid, not a percentage and not a quality score — the model
is told not to render it to the student as "87% similar", which is a number this
design cannot honestly support.

The result rows carry `college_id` first and words never codes, exactly as
`matchObject` (`CollegeSearchTool.kt:170`) already does; the payload is reused
rather than reinvented. Every payload, success and error, passes the RFC 143
`BareSourceCodeGuard` with a per-tool allowlist.

`limit` defaults to 5 and clamps to `1..10` (spec: `LIMIT ≤ 10`). A peer list is
read, not scrolled.

**D71 — one prompt paragraph, version v13.**
`db/schema/0069.seed-coach-system-prompt-v13.sql` appends exactly one paragraph
to the v12 body (RFC 154's), byte-identical otherwise, naming
`SimilarCollegesTool.TOOL_NAME` through the constant per
`SystemPromptCatalogTest`'s drift rule: when a student names one school and asks
for others like it, call the tool rather than inventing peers from memory; state
the axes the response reports; do not present `distance` as a percentage.

The paragraph defines a THIRD tool role and claims no name resolution of its
own: the anchor is a `college_id`, and turning a name into one is
`find_college`'s job, which v12 already prompts. Two name-to-id doors in the
prompt would teach the coach that either will do. The tool's own `name`
parameter (D63) is untouched and still works; it is simply not what the coach is
told to reach for.

`service.conf` pins `v13`; rollback is `COACHING_SYSTEM_PROMPT_VERSION=v12`,
which leaves the tool registered but un-prompted.

**D72 — the door.** The coach in chat, reachable today: a student in any
conversation says "what schools are like Bowdoin?" and the coach calls the tool.
Registration is one entry in the single composition root
(`rest-server/.../Application.kt:317-334`); `queue-worker` needs nothing, having
no `ToolRegistry`. No new surface, no new endpoint, no client change.

## Detailed Design

### `college/src/main/kotlin/ed/unicoach/college/SimilarCollegesTool.kt` (new)

A `ChatTool` in the shape of `CollegeSearchTool`: definition built once at
construction from `vocabulary.schemaProperties()` plus this tool's own
properties (`college_id`, `name`, `axes`, `weights`, `cheaper_than_anchor`,
`easier_to_admit_than_anchor`, `limit`); `required` empty; `KNOWN_FIELDS`
unknown-key refusal owned by the tool; a total `execute` returning
`{"error": sentence}` for validation and domain failures and the
`searchFailureObject` shape for infrastructure failures.

`execute` is four steps and no more: resolve the anchor (D63/D64), plan the
query, call the service, serialize. Every per-call decision of D65-D68 lives in
`planQuery`; all ranking is SQL.

### `SimilarityQuery` and the distance expression

A data class beside `CollegeQuery` carrying the resolved anchor, the axes with
their weights, and the constraint `CollegeQuery` — which owns the one `limit`.
Each axis carries the ANCHOR's own value on it (`AnchoredAxis`), so a dropped
axis cannot reach the SQL at all, and D68's two anchor-relative constraints are
carried as the anchor's own FIGURES (`cheaperThanUsd`, `easierToAdmitThanShare`)
rather than as flags beside them: a constraint with nothing to compare against
is not a state the type can be in. They are properties of the CALL, not of the
college, so they live here. The distance emitter returns clause text and binds
together, so the anchor's four percentile values and the weights are
**parameters**, never interpolated. Shape, per axis, as one `CASE` contributing
to a numerator and a denominator:

    SUM over axes:  CASE WHEN <scored> THEN ? * (<difference>) ELSE 0 END
    / NULLIF(SUM:   CASE WHEN <scored> THEN ? ELSE 0 END, 0)

`<scored>` is the per-axis predicate that says the CANDIDATE can be measured on
it, stated positively rather than as an `IS NULL` test: `selectivity` reads two
columns and is scored when either is present, which one negated null test cannot
say. The columns carry no alias prefix because the expression is emitted inside
the index subquery.

`NULLIF(..., 0)` makes a candidate with no shared axis sort as NULL, which the
outer query counts into `excluded_unknown` and drops. The categorical axes emit
the same shape with an equality test (`setting`) or a Jaccard expression over
the `slug[]` columns (`subjects`), whose GIN index RFC 150 landed already
serves.

The read-back is `listMatches`'s existing pattern (`CollegesDao.kt:769`): rank,
sort and `LIMIT` inside a subquery over `college_search_index`, then join
`colleges` / `college_ipeds` for payload on at most ten rows.

### Cost

One sequential scan over the default universe with a bounded arithmetic
expression per row, then a top-N sort. There is no index on the percentile
columns and none is added: a distance over four parameters is not indexable by
b-tree, and the universe is small enough that adding one would cost ingest time
to buy nothing.

## Files Modified

- `college/.../SimilarCollegesTool.kt` — **new**, the tool.
- `college/.../CollegeSearchService.kt` — a `findSimilar` entry point beside
  `search`/`searchByName`; same `withConnection` + `mapDatabaseError` boundary.
- `db/.../CollegeQuery.kt` (or a sibling) — `SimilarityQuery`, `SimilarityAxis`,
  `SimilarityAnchor`, and `CollegeSimilarityOutcome` beside it for
  `findSimilar`'s return shape.
- `db/.../CollegesDao.kt` — `findSimilar`, the `SimilarityPlan` distance emitter
  over `SearchPlan`, the anchor axis-value read.
- `college/.../CollegeMatchRow.kt` — **new**, `matchObject` lifted out of
  `CollegeSearchTool` verbatim so both college tools return the same result row
  (D70: the payload is reused rather than reinvented).
- `college/.../CollegeSearchTool.kt` — call site only; the `search_colleges`
  payload is unchanged, key for key.
- `service/.../coaching/DelegatingChatTool.kt` — **new**, the ONE verbatim
  `ChatTool` delegate. `:college` carries no `:chat` dependency, so every
  college tool needs a bridge on this side; hand-written three times over
  (`search_colleges`, `find_college`, `similar_colleges`) it was the same six
  lines each, so the rule of three applies and the abstraction RFC 154 parked at
  n=2 lands here.
- `service/.../coaching/SimilarCollegesChatTool.kt` — **new**, and
  `CollegeChatTool.kt` / `FindCollegeChatTool.kt` — each now a one-line
  `DelegatingChatTool` subclass.
- `college/.../ToolErrors.kt` — RFC 154's one home for the model-facing error
  envelope, widened to the whole refusal vocabulary: `INDEX_NOT_BUILT` moves
  here from `CollegeSearchTool`'s companion, and the two `refusalSentence`
  overloads (an unresolvable program filter, an over-long `name`) join it, so
  three tools refuse in one set of words instead of three.
- `db/schema/0069.seed-coach-system-prompt-v13.sql` — **new**, prompt only.
- `service/src/main/resources/service.conf` — pin `v13`.
- `rest-server/.../Application.kt` — register the tool.
- Tests, below.

Explicitly unchanged: `college_search_index` DDL, the ingest, the
`search_colleges` payload and vocabulary, `CollegeQueryVocabulary`'s field set,
the iOS client.

## Implementation Plan

1. `SimilarityQuery` / `SimilarityAxis` + the `SearchPlan` distance emitter,
   with DAO-level tests over seeded index rows.
2. `CollegesDao.findSimilar` — anchor read, ranked query, `excluded_unknown`
   counts, payload read-back.
3. `CollegeSearchService.findSimilar` — boundary, clamping, error mapping.
4. `SimilarCollegesTool` — schema, parsing, refusals, payload, bare-code guard.
5. Registration in `Application.kt`; prompt seed `0069`; `service.conf` pin.
6. Full `nix develop -c bin/test`.

## Tests

Real-Postgres, in `CollegeSearchToolTest`'s established shape (`newCollege` +
`insert()` which rebuilds the index; `CodebookFixture.load` for real codebooks):

- **The acceptance criteria, literally.** A seeded universe with a
  Bowdoin-shaped anchor, small selective privates, and an ASU-shaped large
  public: the bare ask returns the small selective privates and not the large
  public; `cheaper_than_anchor` shifts the set and the reported constraint
  sentence; `easier_to_admit_than_anchor` returns _less_ selective schools and
  reports `selectivity` in `axes_dropped`.
- **Every response names its axes and constraints** — asserted on all three.
- Anchor by id, by exact name, by fuzzy name; `anchor_ambiguous` with
  candidates; no-match refusal; `anchor_not_rankable` for an inactive anchor.
- Unknown handling: anchor missing an axis drops it globally with a reason; a
  candidate missing an axis is scored on the rest with a lower `axes_scored`; a
  candidate sharing no axis is excluded and counted; a candidate with no net
  price is excluded from a `cheaper_than_anchor` ask and counted under
  `net_price_per_year_usd`, never kept as "maybe cheaper".
- Weights change the order; weights outside `[0.1, 10.0]` clamp; unknown axis
  word refuses by name.
- The anchor never appears in its own results; `limit` clamps to 10.
- `BareSourceCodeGuard` over success and every error payload.
- `SystemPromptCatalogTest` v13: the v12 body verbatim plus one paragraph
  containing `SimilarCollegesTool.TOOL_NAME`.
- `CollegeChatToolTest`-style registration test: unique name, definition
  identity.

## Risks

- **Fluency outruns the data.** Three axes with two of them NULL still produces
  a ranked list. `axes_scored` and `excluded_unknown` are the mitigation, and
  the prompt paragraph requires the coach to state them.
- **Non-reproducibility is now user-visible**, not just an internal property. It
  was accepted at gate 2 with eyes open; if it bites, the fix is a pinned preset
  at the surface that needs it, not a method table here.
- **Jaccard over `subject_slugs` is coarse** — a college offering 40 subjects is
  distant from one offering 6 regardless of overlap. It is not a default axis,
  and a better subject-mix measure is a later slice if the axis proves popular.

## Deferred

Embeddings (brief 0004 D9, still deferred). Stored presets and reproducible peer
lists. Field-of-study earnings as an axis. A subject-mix measure better than
Jaccard. Any index on the percentile columns.
