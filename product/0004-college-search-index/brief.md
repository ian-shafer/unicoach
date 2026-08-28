# 0004 — A college search index

Status: GATE 1 APPROVED (with amendments, recorded below). Phase: SPEC & SLICE —
**awaiting gate 2** (see `spec.md`). Research: 3 reports in `research/`
(repo-foundation, data-sources, search-tech), all cited. Next free RFC number at
framing time: **139**.

Ledger (updated as slices land):

    S1 LANDED as RFC 139 (main@cb1c598a + 5d1df831, 2026-08-28) — trigram fuzzy
       name search + curated aliases, unclamped total_matches + sort_by +
       credential_level, and ingest provenance (college_index_build, fatal
       header assertion, loud change summary). Migrations 0051/0052.

## The question

Ian: "I want to build a search index for colleges. We will need this for many
future features — 'show me similar colleges to X'; 'show me all small public
schools in Maine that have a literature program'. I want final say on the index
schema, design, and maintenance. Many /ship runs. Start with the index schema
definition, then how to populate it and keep it up to date (we'll use our
queue)."

This is a **foundation bet**, not a user-facing feature: it is the substrate the
next several product beats query. So the framing question is not "should we" but
**what exactly is the index, what is in it, where does it come from, and how
does it stay honest** — which is precisely the set of decisions Ian wants to
own.

## What exists today (measured, not assumed)

Full detail in `research/repo-foundation.md`; the load-bearing facts:

- **`colleges`** — 27 curated College Scorecard columns + `version`
  (`db/schema/0015`, `0023`, `0045`). **6,273 rows** in a dev ingest: 2,047
  public / 1,901 private-nonprofit / **2,325 for-profit**. Every row is still
  `version = 1` — no ingest has ever changed a row.
- **`college_programs`** — 215,059 rows, **4-digit CIP only** (Scorecard's
  field-of-study file is 4-digit and aid-cohort-gated), 68,634 bachelor's-level
  rows across 2,416 schools.
- **A structured search already ships** (RFC 67): `CollegeQuery` →
  `CollegesDao.search` → the `search_colleges` chat tool, also used by fit-lens.
  11 hard AND-filters (cip prefix, states, region, locale, control, enrollment,
  admit rate, net price, grad rate). So Ian's Maine query is _nearly_ answerable
  today.
- **…but the search is thin where it matters.** No relevance ranking of any kind
  — every result set is `ORDER BY undergrad_enrollment DESC` (biggest school
  wins, always). `limit ≤ 25`, no pagination, **no total count** (the tool's
  `count` is just the returned slice, so the coach cannot truthfully say "12
  schools match"). Name lookup is a leading-wildcard `ILIKE` sequential scan —
  "UMass Amherst" or "Mizzou" or a typo returns nothing. No credential-level
  filter on programs. Earnings, debt, SAT, Pell, cost and the income bands are
  _returned_ but not _filterable_. No similarity, anywhere.
- **Coverage is the sharpest constraint.** state/control/region 100%, locale
  91%, enrollment 87%, net price 80% — but **graduation_rate 36%, admission_rate
  30%, sat_avg 16.5%**. Today's filters exclude NULLs silently, so a selectivity
  filter quietly discards ~70% of the catalog.
- **Ingest** — `bin/ingest-colleges` upserts per row on `unit_id` with a
  26-column `IS DISTINCT FROM` guard. Unchanged rows write nothing (good: an
  index can refresh off `updated_at`). Two traps: `college_programs` upserts do
  **not** bump the parent college's `updated_at`, and **stale rows are never
  deleted** — a closed school stays forever, with no "disappeared" signal.
  Critically: **no snapshot version, URL, or checksum is recorded anywhere in
  the repo.** "Version-pinned" is a convention, not an artifact.
- **Freshness machinery is ready**: `jobs`/`job_attempts` (RFC 3/97),
  `periodic_jobs` + the `:cron` process, `JobType` enum + `JobHandler` +
  `JobTypeConfig`; fit-lens and synthesis sweeps are the copy-paste pattern
  (seeded `enabled = FALSE`, `maxAttempts = 1`). `ASYNC_WORK.md` binds it.
- **Postgres 18** dev (flake) and prod (RDS). **Only `plpgsql` is installed** —
  the repo has zero `CREATE EXTENSION` statements. `pg_trgm`, `unaccent`,
  `btree_gin`, `cube`, `earthdistance` are available-but-not-enabled on both
  sides. **`pgvector` is not available in the nix Postgres** (RDS has it) — a
  real dev/prod parity gap.

## What the research changes about the obvious plan

Three findings should move the design:

1. **There is no scale argument for any new substrate.** 6k institution rows and
   215k program rows fit in RAM. A denormalised index table buys **shape,
   explainability, and observability** — not speed. Anyone proposing Meilisearch
   or OpenSearch here is proposing a second datastore and deploy target for no
   gain. (`research/search-tech.md`.)
2. **The attribute breadth Ian wants is already free — in IPEDS, not
   Scorecard.** Religious affiliation, ROTC, study abroad, disability services,
   on-campus housing, application fee, athletics membership/conference, Carnegie
   type, test-optional policy, and closure status all live in the IPEDS `HD`/
   `IC`/`ADM` files: ~1.5 MB of CSV, **plain GET, no login**, public domain.
   Greek life and application deadlines are the only named attributes that are
   _not_ in federal data (Common Data Set only, ~1,000 PDFs, unverified terms).
   (`research/data-sources.md`, all coverage figures computed from the 2023
   files by the researcher.)
3. **"A literature program" is a taxonomy problem, not a filter problem.**
   Literature spans CIP `23.01`, `23.13`, `23.14` **and `16.0104` Comparative
   Literature** — a naive `cip LIKE '23%'` silently misses the last. **No public
   CIP→plain-English-major crosswalk exists.** A ~60–100-subject curated
   taxonomy with synonyms is an artifact we must author and own. Also: today's
   4-digit programs table can answer "23.14 Literature" but not "23.1404 English
   Literature"; IPEDS Completions (`C_A`) is 6-digit and census-complete. Worked
   end-to-end from public data: Maine public 4-years with a bachelor's in CIP 23
   (2023) — UMaine Orono 27 awards, Farmington 20, USM 18, Presque Isle 7,
   Augusta 3 — plus a hygiene trap: a naive universe filter also admits
   "University of Maine-System Central Office", which is not a college.

## Candidates (the shape of the bet)

| # | Bet                                                                                                                                                           | What it buys                                                                                                                        | Cost                                                      | Foundation in repo                                 |
| - | ------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------- | -------------------------------------------------- |
| A | **Index what we have**: `pg_trgm` + GIN on name, true `totalMatches` + facets + `sortBy`, credential-level filter, ingest assertions                          | Honest counts, name resolution that survives typos and nicknames, an unbiased sort                                                  | 1 migration, 1 DAO, 1 tool schema — **one /ship run**     | `CollegesDao`, `CollegeSearchTool`                 |
| B | **A + a derived `college_search_index` table + offline `college_similarity`**, fed by IPEDS and a subject taxonomy, rebuilt by a queue job with a build stamp | "Similar to X"; "12 schools match, here's why"; every attribute Ian named except Greek life/deadlines; a diffable, observable build | 3–4 /ship runs                                            | `periodic_jobs`, `:cron`, `JobType`, RFC 67 loader |
| C | **B + pgvector embeddings** for semantic similarity                                                                                                           | Free-text "colleges with a strong outdoorsy engineering vibe"                                                                       | B + a flake/RDS parity fight + sourcing institution prose | none; RFC 67 explicitly rejected pgvector          |

Success criteria for this decision: the index must (a) answer both of Ian's
example queries end-to-end, (b) let the coach cite a count and a source-year for
every claim, (c) be rebuildable from scratch by one command and one queue job,
and (d) never present unknown data as a "no".

## Gate 1 — decisions

Defaults are pre-chosen; "approve" takes them all. Amend by number.

**D1 — the bet.** Default: **Option B, sliced** (B1 = Option A, independently
valuable and lands the P1 ingest-observability item; B2 = index table + build
stamp; B3 = similarity + tool surface). C is deferred, not rejected.

**D2 — index universe.** The catalog is 6,273 rows, but 2,325 are for-profit and
many are 2-year, cosmetology, and system offices. Default: **index everything,
but carry `is_four_year` / `is_active` / `is_degree_granting` flags and default
the search to 4-year + active + degree-granting, overridable.** (Rejected
alternative: filter at ingest — throws away data we can't get back and hides the
choice.)

**D3 — substrate.** Default: **a plain denormalised `college_search_index`
table, rebuilt by a job**, not a materialised view. Reason: an explicit build is
observable, incrementally refreshable off `updated_at`, and free of
`REFRESH …
CONCURRENTLY`'s unique-index constraint. It is a derived table —
droppable and rebuildable at any time, never the source of truth.

**D4 — second source: IPEDS.** Default: **yes — add an IPEDS `HD` + `IC` + `ADM`
loader.** This is the single highest-leverage move in the research: it is what
unlocks religion, ROTC, study abroad, disability services, housing, athletics,
Carnegie type, test-optional policy, and closure status. Public domain, no auth.

**D5 — program truth.** Default: **add IPEDS Completions (`C_A`) as the 6-digit
program census**, keeping Scorecard field-of-study for its
earnings/debt-by-field (which IPEDS lacks). Two sources, explicitly labelled,
never silently merged.

**D6 — subject taxonomy.** Default: **author one, in-repo, versioned** — ~60–100
subjects, each a curated CIP-prefix set plus synonyms ("lit", "English",
"creative writing"), reviewed by Ian at the spec gate. This is the layer that
turns "a literature program" into the right CIP set including `16.0104`.

**D7 — fuzzy name matching.** Default: **`CREATE EXTENSION pg_trgm` + a GIN
trigram index on `name`** (plus an alias/nickname column for "Mizzou", "UMass
Amherst"). This is the repo's **first extension** — a real precedent. Available
on both dev nix and RDS. (Alternative: built-in `tsvector`, zero extensions,
worse at typos.)

**D8 — similarity.** Default: **precomputed, stratum-gated top-K.** Filter to a
peer stratum first (same control × degree level × size band), _then_ rank by
weighted numeric distance; store top-20 per college in a `college_similarity`
table with a `method_version`. Reason: offline wins on **explainability** ("we
call these peers because…"), not speed. Weights live in a versioned row, not
compiled into code, so they can be tuned without a deploy.

**D9 — embeddings.** Default: **defer.** pgvector is a dev/prod parity gap and
the hard part is sourcing institution prose, not vector math. Revisit once the
peer feature is observed in use.

**D10 — non-federal attributes (deadlines, Greek life, NCAA division).**
Default: **decline the Common Data Set for now** (≈1,000 heterogeneous PDFs,
terms unverified, by far the largest effort item), and derive NCAA division from
a hand-authored ~100-row conference→division map. Note: **this overlaps brief
0001's S4** (Admissions Intelligence Layer, CDS-derived deadlines/merit aid) —
see D13.

**D11 — unknown is not "no".** Default: **every derived flag and band in the
index is tri-state** (yes / no / unreported), and every search filter declares
how it treats unknowns and **reports the count it excluded** ("142 matched; 61
more couldn't be judged — admission rate unreported"). This is the fix for the
36%/30%/16.5% coverage cliff, and it is the same "never evade, say 'not
reported'" rule the cost feature already follows.

**D12 — maintenance.** Default: **a `JobType` rebuild handler + a
`periodic_jobs` row on a quarterly cron, seeded `enabled = FALSE`**, plus a
manual `bin/` trigger; and **provenance becomes real**: a `college_index_build`
row recording source file URL/sha256, release date, rows written, and
`method_version`; a **header assertion** (fatal if a source CSV lacks a column
the build reads) and a **change summary** (non-null counts before/after, version
bumps). This absorbs the P1 "ingest observability" item in `product/STATUS.md`.
Closed schools (`HD.CYACTIVE`/`DEATHYR`) are **flagged and excluded from search,
never deleted**.

**D13 — sequencing against existing P1 work.** Default: **0004's B1 and B2 land
before brief 0001's S4** (Admissions Intelligence Layer), because S4 is a data
build that should sit _on_ this index rather than beside it, and B1 subsumes the
ingest-observability P1 row. Brief 0002 (account deletion, App-Store-blocking)
is unaffected and keeps its own priority.

## Gate 1 — OUTCOME (2026-08-27, recorded verbatim)

Ian, over the technical-implementation conversation:

- "I want to be able to do fuzzy matching via trigrams, and exact matches e.g.
  is_public" — **D7 approved** (pg_trgm).
- "`control` is better. Ignore my is_public. Or we could translate is_public to
  control == public." — **amendment: schema carries raw source codes; the
  human-readable enum ("public") exists only at the LLM-tool/API boundary, one
  mapping site.**
- "Since we're using postgres, I'm not sure we even need a queue. I was thinking
  we'd have a separate search engine, but I definitely do not want one. Postgres
  will do just fine" — **amendment: external search engine ruled out; Postgres
  is the search substrate. D12 amended: no queue in the core design — the index
  rebuild is a synchronous, transactional step at the end of
  `bin/ingest-colleges`. `periodic_jobs` automation demoted to an optional later
  slice.**
- "I'd like to update the database rows, and _then_ update any indexing features
  in the database. Instead of doing it on every row insertion or update." —
  **amendment confirming D3's shape: two-phase ingest. Phase 1 upserts the
  normalised tables row-by-row (unchanged); phase 2 rebuilds
  `college_search_index` once, whole, in one transaction. No triggers, no
  per-row index maintenance.**
- "I like the direction we're going so far. I want to spec out everything for
  this feature now" — **gate 1 approved with the above amendments; all other
  defaults (D1, D2, D4, D5, D6, D8, D9, D10, D11, D13) stand as written.**

Follow-up ruling (2026-08-27, similarity discussion, recorded verbatim): on
precomputed similarity — "What does it mean to be similar? I'm not sure it even
makes sense to pre-define it"; on giving up reproducibility — "This is okay to
give up"; on the query-time axes-with-defaults tool shape — "Yes". **D8 is
amended: no precomputed similarity, no `college_similarity` or
`similarity_methods` tables. Phase 2 precomputes facts only (percentile-rank
columns on the index); "similar" is a query-time, per-call choice of axes,
weights, and constraints made by the coach, defaults visible in the tool
definition.**

Second follow-up ruling (2026-08-27, percentile columns, verbatim): offered
"drop earnings_pct only (my default), or drop both outcome percentiles?" — Ian:
"'drop both outcome percentiles' This one". **The index carries percentile ranks
for school character only (enrollment, admission rate, SAT, net price); outcome
measures stay raw and cited, never ranked, never a similarity axis.**

Status update: PRIORITISE complete → SPEC & SLICE. See `spec.md`.
