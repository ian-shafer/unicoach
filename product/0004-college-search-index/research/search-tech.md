# Search / index technique options for a ~6,000-institution corpus

Research note for product brief 0004. Scope: what substrate the college search
index should live on, how "similar to X" can be computed, what shape the
LLM-facing API should take, and how a rebuild stays observable. Decidable
options at the end; no decision is taken here.

## Method, and what I could not verify

Repo claims below were read directly from this checkout (`path:line`). Postgres
version claims were executed, not assumed: `nix develop -c psql --version`
prints **PostgreSQL 18.3**, and the contrib extensions actually shipped by that
nix store path were listed from its `share/postgresql/extension` directory.
External claims cite primary docs (postgresql.org/docs/18, AWS RDS release
notes, Anthropic tool-use docs). **Not verified:** the real production row
counts (I did not connect to prod; ~6k institutions / ~200k program rows is the
brief's figure, and the Scorecard institution file is ~6.5k rows in the era this
schema was built for), any latency number below (no benchmark was run against a
loaded DB), and the RDS `db_engine_version` actually applied (it is a variable,
`infra/rds.tf:21`, defaulted in tfvars — the example shows `18`,
`infra/terraform.tfvars.example:13`).

## 0. What exists today

| Thing                         | Where                                                            | Note                                                                                                                        |
| ----------------------------- | ---------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------- |
| `colleges` (institution rows) | `db/schema/0015.create-colleges.sql:34`                          | 6 plain btree filter indexes, `:87`–`:92`; unique on `unit_id` `:84`                                                        |
| `college_programs` (CIP rows) | `db/schema/0015.create-colleges.sql:105`                         | btree on `cip_code` `:126` and `college_id` `:127`                                                                          |
| Versioning + history          | `db/schema/0023.version-colleges.sql`                            | `colleges_versions`, `log_college_version()`; a no-op re-ingest writes no history row (comment at `0023...sql`, trigger_04) |
| Income-band net price         | `db/schema/0045.add-college-income-band-net-price.sql:24`        | NPT41–45 + `median_debt`                                                                                                    |
| Structured search SQL         | `db/src/main/kotlin/ed/unicoach/db/dao/CollegesDao.kt:427`–`521` | dynamic `WHERE` builder, optional program JOIN, `GROUP BY c.id`, hardcoded `ORDER BY undergrad_enrollment DESC`             |
| Name search                   | `CollegesDao.kt:534`–`557`                                       | `name ILIKE '%'                                                                                                             |
| Service caps                  | `college/.../CollegeSearchService.kt:54`–`59`                    | limit 1..25, `q` ≤ 100 chars                                                                                                |
| LLM tool                      | `college/.../CollegeSearchTool.kt:37`–`82`                       | 12 optional params, all filters, no facets/counts beyond `count` of returned rows                                           |
| Loader                        | `college/.../CollegeScorecardLoader.kt:337`                      | one `Load summary [loaded=..] [skips=..] [coercions=..]` line                                                               |

Two facts worth pinning: **the repo contains zero `CREATE EXTENSION`
statements** (grep over `db/`), so adopting _any_ extension is a first — a
migration plus a role-privilege question. And the local nix Postgres 18.3 ships
contrib but **not pgvector**: available there are `btree_gin`, `btree_gist`,
`citext`, `cube`, `fuzzystrmatch`, `pg_trgm`, `unaccent`, `earthdistance`,
`intarray`, `ltree`, `tablefunc`, `seg` (etc.). RDS for PostgreSQL 18 _does_
offer `pgvector` 0.8.1–0.8.2 and `pg_trgm` 1.6
(https://docs.aws.amazon.com/AmazonRDS/latest/PostgreSQLReleaseNotes/postgresql-extensions.html).
So **pgvector is a dev/prod parity problem, not a prod problem**: prod has it,
`flake.nix:27`'s `pkgs.postgresql_18` does not, and `bin/test` runs against the
local server. Adding it means `postgresql18Packages.pgvector` in the flake.
`pg_trgm` has no such gap (contrib both sides).

## 1. Index substrate options

| Option                                                | Fit at 6k/200k rows                                                                                                                                                                                                                                                                                                                                              | Real cost                                                                                                                                                                                                                                                                                                 | Honest verdict                                                                                                                    |
| ----------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------- |
| (a) Normalised tables + btree/GIN                     | Excellent. 6k rows ≈ a few MB; even a seq scan of `colleges` is sub-millisecond. Program filter is the only join.                                                                                                                                                                                                                                                | Zero new infra. Needs `btree_gin` only if you want one multi-column GIN over the filter set.                                                                                                                                                                                                              | The scale argument for anything else is **absent**. Any "we need an index for speed" claim here is not true at 6k rows.           |
| (b) Denormalised `college_search_index` table/matview | Buys _shape_, not speed: one row per college carrying pre-aggregated program CIP arrays, a text blob, a similarity vector, derived buckets (size band, selectivity band). Kills the `GROUP BY c.id` + `array_agg` in `CollegesDao.kt:487`–`510`.                                                                                                                 | A refresh job and a staleness story. `REFRESH MATERIALIZED VIEW CONCURRENTLY` needs ≥1 unique index on the matview and non-empty contents (https://www.postgresql.org/docs/18/sql-refreshmaterializedview.html). A plain table + upsert avoids the matview restrictions and can be written incrementally. | The strongest option, and for shape/observability reasons, not performance.                                                       |
| (c) FTS `tsvector` + GIN, plus `pg_trgm`              | FTS is **core Postgres, no extension** — a GIN index on `to_tsvector(...)` (https://www.postgresql.org/docs/18/textsearch-indexes.html). `pg_trgm` gives `similarity()`/`%` and GIN/GiST support for `LIKE`/`ILIKE` (https://www.postgresql.org/docs/18/pgtrgm.html) — exactly what today's unindexed `ILIKE` scan wants for typo tolerance ("Umass", "Wash U"). | One extension migration; trigram thresholds are tunable knobs (`pg_trgm.similarity_threshold`, default 0.3).                                                                                                                                                                                              | Highest value per line of code, because name-resolution failure is a _user-visible_ failure and 6k names is where trigrams shine. |
| (d) pgvector embeddings                               | Semantic "small liberal-arts vibe" / "similar to X by description". At 6k rows you do **exact** brute-force distance and skip HNSW/IVF entirely — 6k × 768 floats ≈ 18 MB.                                                                                                                                                                                       | Parity gap above; an embedding pipeline; a model dependency; embeddings need re-generating on text change; and you must source descriptive text the corpus doesn't currently have (Scorecard has no prose).                                                                                               | Real, but it is a _content_ project disguised as an index project. Not first.                                                     |
| (e) Meili / Typesense / OpenSearch                    | Nothing at this scale that Postgres cannot do.                                                                                                                                                                                                                                                                                                                   | A second datastore, a second deploy target, a second consistency story, a second thing to be silently stale. `infra/` is one RDS instance (`infra/rds.tf:17`) on `db.t4g.micro`-class hardware; adding a search node is a real ops step.                                                                  | Not justified. Revisit only if the corpus grows ~100× or you need typo-tolerant ranked search over long text.                     |

## 2. "Colleges similar to X"

Four practical families:

1. **Attribute-vector distance over normalised numerics.** Z-score (or
   rank-normalise) size, selectivity, net price, graduation rate, earnings,
   %Pell, plus categorical matches on control/locale/region; weighted Euclidean
   or cosine. All inputs already exist on `colleges`. Fully computable offline.
   `cube` (contrib, both sides) gives a first-class distance operator if you
   want it in SQL; plain arithmetic works fine too.
2. **IPEDS-style peer/comparison groups.** IPEDS does not publish a similarity
   score; it lets an institution _choose_ a comparison group and then reports
   itself against those peers (Data Feedback Report "compares the data with peer
   institutions", https://nces.ed.gov/ipeds/use-the-data/). The industry pattern
   underneath is **stratify first, rank second**: filter to the same
   sector/control, degree level, and size band, then rank within stratum. This
   matters because it prevents the "similar to Amherst → a for-profit with the
   same net price" failure that pure numeric distance produces.
3. **Embedding similarity over descriptive text.** What consumer sites lean on
   for "colleges like X" (Niche's methodology is proprietary and I could not
   retrieve a citable page — treat any claim about their exact algorithm as
   unverified). Requires text the corpus lacks.
4. **Hybrid.** Stratum gate (2) → numeric distance (1) → optional embedding
   re-rank (3). This is the defensible default.

**Offline vs query time.** 6,000² = 36M pairs; storing top-K (K=20) per college
is 120k rows — trivial. A
`college_similarity (college_id, similar_college_id,
rank, score, method_version)`
table computed in the ingest/build job is the right split: the _scoring policy
is versioned data_, so the coach can cite "peers by size/selectivity/cost,
method v1" instead of an opaque number. Query-time computation is also
affordable at this scale, but then the weights live in code, change silently,
and cannot be diffed between builds. Offline wins on **explainability**, not
speed.

## 3. LLM-facing shape

The consumer is a tool call, not a search box. Three shapes:

- **Fixed parameter tool** (today, `CollegeSearchTool.kt:37`–`82`): 12 flat
  optional params. Anthropic's guidance is that the description is "by far the
  most important factor in tool performance", ≥3–4 sentences per tool, and to
  **consolidate related operations into fewer, more capable tools** because
  "fewer, more capable tools reduce selection ambiguity"
  (https://docs.anthropic.com/en/docs/agents-and-tools/tool-use/implement-tool-use).
  Note that guidance is about tool _count_, not parameter count; I found no
  primary source quantifying degradation vs. number of parameters, so treat
  "many params hurt" as folklore, not fact.
- **Structured filter DSL** (nested JSON predicate trees). More expressive,
  strictly harder for the model to emit correctly, and every new operator is new
  validation surface. The current tool is already total-on-malformed-input
  (`CollegeSearchTool.kt:89`–`107`) — that property is worth more than
  expressiveness.
- **Natural-language-to-SQL.** Rejected on the merits for a student-facing
  coach: unbounded query surface, injection/cost/timeout risk, and no stable
  contract to cite. If you want it later, do it as an _offline authoring_ tool,
  not a live path.

**Facets are the actual gap.** "12 schools match" requires the tool to return
`totalMatches` (unclamped count) separately from the ≤25 returned rows — today
`count` is just `matches.size` (`CollegeSearchTool.kt:105`), so the coach cannot
truthfully say how many schools matched. Add: `totalMatches`, a small `facets`
object (counts by state / control / size band) so the coach can say "most of
those are private", and `source` + `sourceYear` per numeric field so citation is
mechanical rather than improvised. Also add `sortBy` — the current hardcoded
`ORDER BY undergrad_enrollment DESC` (`CollegesDao.kt:511`) silently biases
every answer toward big schools.

## 4. Freshness and maintenance

The scar is real and named in `product/STATUS.md`: a stale jar and a real load
are indistinguishable because the loader prints one summary line
(`CollegeScorecardLoader.kt:337`) while new columns stay NULL.

- **Full rebuild** is the right default: 6k rows rebuild in seconds, and a
  from-scratch build is the only thing that catches "the derived column stopped
  being derived". Incremental per-college is an optimisation with no payoff at
  this size.
- **Detect staleness structurally, not by eyeballing logs.** Give the index a
  build row:
  `college_index_build (id, started_at, finished_at, source_file,
  source_sha256, rows_written, method_version)`,
  and have the search path expose the newest build's timestamp. Anything
  downstream can then answer "how old is this?" without reading logs.
- **Make a rebuild loud.** Three assertions the ingest lacks: (i) header
  assertion — fatal if the CSV lacks a column this build reads; (ii) per-field
  non-null counts _before vs after_, so an all-NULL column is a failure, not a
  silence; (iii) a `colleges.version` bump count — zero bumps on a fresh
  snapshot means nothing changed, which is either correct or a stale jar, and
  should be printed either way. `0023`'s no-op suppression means version bumps
  are already an honest change signal.
- **Scheduling:** `pg_cron` is available on RDS 18 (1.6.7, same AWS page) but
  **not** in the local nix contrib set — so schedule from the existing `cron/`
  module, not from the database, to keep dev/prod parity.

## 5. Decidable options

**Option A — "Index the tables we have" (smallest).** Add `pg_trgm` + a GIN
trigram index on `colleges.name`; switch `searchByName` to trigram-backed
matching with a similarity floor; add `totalMatches`, `facets`, and `sortBy` to
the tool; add the header assertion + change summary to the ingest. No new
tables, no new concepts. _Cost:_ one migration, one DAO change, one tool-schema
change. _Ships in one `/ship` run._ _Limit:_ no "similar to X".

**Option B — "A" plus a materialised `college_search_index` +
`college_similarity` (default pick).** Everything in A, plus a plain (not
matview) denormalised table per college carrying program CIP arrays, derived
bands, and a build-stamp, plus an offline top-20 peer table using stratum-gated
numeric distance with a stored `method_version`. Rebuilt whole by the ingest
job; `college_index_build` makes freshness queryable. _Why default:_ it delivers
"colleges similar to X" and "12 schools match, here's why" — the two things the
brief actually asks for — using only data already in the schema, with no
extension parity risk beyond `pg_trgm`, and it turns the silent-ingest scar into
a diffable artifact. _Risk:_ it is bigger than one `/ship` run; slice it as B1 =
A, B2 = index table + build stamp, B3 = similarity table + tool surface.

**Option C — "B plus embeddings."** Add pgvector (flake change for dev parity),
source descriptive text per institution, embed, store, and hybrid-rank. _Why not
first:_ the hard part is sourcing and maintaining institution prose, not the
vector math; and exact brute-force distance over 6k rows would work fine without
pgvector at all if you ever wanted to test the idea cheaply. _Revisit after B
proves the peer feature is used._

**Recommended default: B, sliced, with B1 (= Option A) as the first `/ship`
run.** B1 is independently valuable (name resolution and honest match counts are
user-visible today), it lands the ingest observability item `product/STATUS.md`
already ranks as P1, and it commits Ian to no schema he has not seen.

**Open decisions for Ian:** (D-a) plain table vs materialised view for the index
— matview is declarative but `CONCURRENTLY` constrains it; (D-b) whether
similarity weights are code or a versioned row; (D-c) whether the peer stratum
is control+size+degree-level or something narrower; (D-d) whether to accept the
pgvector dev/prod parity gap at all, now or ever.
