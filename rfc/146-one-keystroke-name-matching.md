# RFC 146: A typo is one keystroke, not a similarity score

**Status: Proposed**

Supersedes the fuzzy-matching half of RFC 139 (committed, therefore immutable —
this is the new-numbered RFC that carries the changed decision). RFC 139's
aliases, `total_matches`, `sort_by`, `credential_level`, and ingest provenance
are untouched and stay exactly as they are.

## The defect

Typing `Amhurst` in the iOS college picker returns **Elmhurst University**, and
Amherst College is absent from the results entirely.

RFC 139 matched typos with `word_similarity` (`<%`, threshold 0.6).
`word_similarity` scores the best-matching **contiguous extent** of the target,
so against the query `Amhurst`:

| candidate               | word_similarity | outcome         |
| ----------------------- | --------------- | --------------- |
| El**mhurst** University | **0.625**       | matches (wrong) |
| **Amherst** College     | 0.455           | misses (right)  |

A different real word sharing a long tail beats the intended word carrying a
mid-word typo, because one substitution in the middle destroys three consecutive
trigrams. This is a structural property of the metric, not a bad threshold:
lowering the floor to admit Amherst also admits everything between 0.455 and
0.625, and the inversion — Elmhurst still ranked above Amherst — remains.

Measured single-typo scores for real queries: Stanfrod→Stanford 0.385,
Amhurst→Amherst 0.455, Harvad→Harvard 0.500, Berkely→Berkeley 0.545,
Cornel→Cornell 0.667. Any threshold covering these is a curve fitted to six
examples, and says nothing about the seventh.

## The rule

A typo is **one keystroke wrong**: a substitution, an insertion, a deletion, or
an adjacent transposition. That is Damerau-Levenshtein (optimal string
alignment) distance ≤ 1 — a definition, not a fitted score.

**Match rule:** the query is split into words on the same `[^a-z0-9]+` boundary
the search text is — by the same SQL function, `college_search_words()`, so
there is one splitter rather than two implementations to drift apart. A college
matches when **every** query word is within one keystroke of **some** word of
its search text (name + curated aliases), OR the query is a literal substring of
that text.

Recall is guaranteed by construction: a query produced by making at most one
keystroke error in each word of a name is, by definition, within distance 1 of
each of those words, so it must match. There is no corpus statistic for this to
drift with — the property holds for every name in the table and for every name
added later.

Quantifying over query words is what keeps the two-word case working:
`Amhurst Colege` matches Amherst College (`amhurst`→`amherst`,
`colege`→`college`, one keystroke each) and does not match Elmhurst University
(`amhurst`→`elmhurst` is two). The quantifier is `for all` and not
`there exists` on purpose: any single query word matching would let `colege`
alone return every college in the corpus.

### Why per-word, and why not the whole string

Edit distance over the whole search text is meaningless at these lengths
(`amhurst` vs `Amherst College` is distance 9). The user types a word; the rule
is stated over words.

### The three mechanisms, one problem each

| user behaviour      | mechanism                 | exactness           |
| ------------------- | ------------------------- | ------------------- |
| typo ("Amhurst")    | one-keystroke rule        | exact by definition |
| fragment ("Amh")    | `ILIKE '%…%'` substring   | exact               |
| nickname ("Mizzou") | curated aliases (RFC 139) | exact               |

Every remaining mechanism is exact. **`pg_trgm` has no job left and is removed**
— along with its GIN index and its still-unverified-on-RDS status. It is
replaced by `fuzzystrmatch` (the same contrib set, also on AWS's supported
list), so the extension count is unchanged while every mechanism becomes
exactly-defined rather than threshold-tuned. `ILIKE` needs no extension; without
the trigram index it seq-scans, which is microseconds at ~6.3k rows.

## Detailed Design

### Migration: `fuzzystrmatch`, the two functions, and the word table

```sql
CREATE EXTENSION fuzzystrmatch;
DROP INDEX colleges_search_text_trgm_idx;
DROP EXTENSION pg_trgm;   -- college_search_text() is retained; only the index used trigrams

-- "One keystroke off": Levenshtein <= 1 covers substitution/insertion/deletion;
-- the second arm covers adjacent transposition, which plain Levenshtein scores
-- as 2 (Stanfrod -> Stanford). Together they are exactly Damerau-Levenshtein
-- (optimal string alignment) <= 1.
--
-- The SQL-standard unquoted body (the college_search_text precedent in 0051),
-- NOT a quoted AS $$…$$ body: a standard body is parsed and its references
-- resolved at CREATION time, so levenshtein_less_equal is bound here and
-- recorded as a dependency on the extension, rather than re-resolved at call
-- time through whatever search_path a session inherited. (`SET search_path` on
-- the function would pin it too, but it defeats inlining and adds a GUC
-- save/restore to the hottest call in the query.)
CREATE FUNCTION one_keystroke_off(a TEXT, b TEXT) RETURNS BOOLEAN
LANGUAGE sql IMMUTABLE PARALLEL SAFE
RETURN levenshtein_less_equal(a, b, 1) <= 1
    OR (length(a) = length(b) AND EXISTS (
          SELECT 1 FROM generate_series(1, length(a) - 1) i
          WHERE overlay(a placing reverse(substring(a from i for 2)) from i for 2) = b));

-- The ONE definition of a search word: lowercase, cut on [^a-z0-9]+, drop the
-- empty strings a leading, trailing or doubled separator produces. BOTH sides
-- of the match rule call it — the derived table is built from it and the user's
-- query is split by it — so there is no second implementation in a second
-- engine to drift from (a Kotlin regex and Locale.ROOT folding vs POSIX regex
-- and collation-dependent lower() is exactly the divergence this removes).
-- Duplicates are not collapsed here: the rebuild de-duplicates per college
-- (its primary key requires it), and the query keeps a repeated word repeated,
-- which is what the rank sum counts.
CREATE FUNCTION college_search_words(t TEXT) RETURNS TEXT[]
LANGUAGE sql IMMUTABLE PARALLEL SAFE
RETURN coalesce(
    (SELECT array_agg(w) FROM regexp_split_to_table(lower(t), '[^a-z0-9]+') AS w WHERE w <> ''),
    '{}'::TEXT[]);

-- Derived: one row per (college, word). Rebuilt wholesale, never hand-written —
-- the same derived-table discipline brief 0004 fixed for the search index, and
-- the shape S3's rebuild already produces. `len` is GENERATED, not supplied:
-- the length prefilter is lossless only while len IS length(word), so a row
-- whose len lied would silently delete matches the rule calls a theorem.
CREATE TABLE college_name_words (
    college_id UUID NOT NULL REFERENCES colleges(id) ON DELETE CASCADE,
    word       TEXT NOT NULL,
    len        SMALLINT NOT NULL GENERATED ALWAYS AS (length(word)::SMALLINT) STORED,
    PRIMARY KEY (college_id, word)
);
CREATE INDEX college_name_words_len_word_idx ON college_name_words (len, word);

-- Seed: unlike the GIN index it replaces, this table does not populate itself,
-- and the ingest is a manual operator task rather than part of deploy. Without
-- this every existing database — dev, and production — would lose typo matching
-- from the moment the migration commits until somebody hand-ran an ingest. The
-- migration seeds it once; the ingest's name-words phase owns it thereafter.
INSERT INTO college_name_words (college_id, word)
SELECT DISTINCT c.id, w
FROM colleges c,
  LATERAL unnest(college_search_words(college_search_text(c.name, c.aliases))) AS w;
ANALYZE college_name_words;
```

The word table is populated from `SELECT DISTINCT` over
`unnest(college_search_words(college_search_text(name, aliases)))`. The DISTINCT
is forced by the primary key: a word appearing in both a name and one of its
aliases would otherwise collide. The rebuild is `DELETE` + `INSERT … SELECT`
(not `TRUNCATE`, which takes `ACCESS EXCLUSIVE` against live search readers)
followed by an `ANALYZE` of the table in the same transaction: after a wholesale
replacement the planner's statistics describe the previous build, and on a first
ingest into a fresh database they describe an empty table — the one case where
the length prefilter's index looks pointless to the planner. It runs in one
transaction as its own ingest phase — phase 2 of the two-phase ingest RFC 139
established (rows first, derived state second, never per-row triggers).

### The query

```sql
-- The RAW query is bound as ONE text parameter and split by Postgres with
-- college_search_words() — the same function the stored words are built from,
-- so there is exactly one word boundary in the system and no client-side
-- splitter to diverge from it. Bound, never interpolated; % / _ / \ are not
-- word characters, so they are inert here, while the ILIKE arms take the
-- ESCAPED query (that raw/escaped split stays load-bearing and positional).
--
-- The match is computed ONCE, in word_match: the minimum distance from each
-- query word to that college's name words (0 exact, 1 one keystroke, no row at
-- all when nothing is within one keystroke). Membership and ranking then read
-- the SAME numbers instead of each re-expanding the join — they cannot drift,
-- and the work is done once.
--
-- The cardinality guard is load-bearing: a query with no word characters ("%%%")
-- yields an empty array, and "every word matches" is VACUOUSLY TRUE for every
-- row, so without it such a query returns the whole corpus. Guarded, it falls to
-- the substring arm alone, which is the RFC 137 behaviour.
WITH q(words) AS (SELECT college_search_words(:query)),
word_match AS (
  SELECT nw.college_id, qw.ord, min(CASE WHEN nw.word = qw.word THEN 0 ELSE 1 END) AS distance
  FROM q, unnest(q.words) WITH ORDINALITY AS qw(word, ord)   -- ORDINALITY keeps duplicates
  JOIN college_name_words nw
    ON nw.len BETWEEN length(qw.word) - 1 AND length(qw.word) + 1   -- lossless prefilter
   AND one_keystroke_off(qw.word, nw.word)
  GROUP BY nw.college_id, qw.ord
),
scored AS (
  SELECT college_id, count(*) AS matched_words, sum(distance) AS distance
  FROM word_match GROUP BY college_id
)
SELECT c.id, c.name, c.city, c.state
FROM colleges c
  CROSS JOIN q
  LEFT JOIN scored s ON s.college_id = c.id
WHERE (cardinality(q.words) > 0 AND s.matched_words = cardinality(q.words))
   OR college_search_text(c.name, c.aliases) ILIKE '%' || :escaped || '%'
ORDER BY (c.name ILIKE :escaped || '%') DESC,          -- exact prefix first
         (coalesce(s.matched_words, 0) = cardinality(q.words)) DESC,  -- rule match, or substring-only
         s.distance ASC NULLS LAST,                    -- 0 exact + 1 per one-keystroke word
         c.undergrad_enrollment DESC NULLS LAST, c.name, c.unit_id
LIMIT :limit
```

`matched_words = cardinality(q.words)` **is** the all-words rule: a college is
matched when every query word had a name word within one keystroke. The ranking
reads the same numbers, as **two explicit keys** rather than one number with a
penalty folded into it.

The first key is the **class**: did the row match the one-keystroke rule at all,
or is it here through the substring arm alone? That is a boolean, so it cannot
collide with a distance — a substring-only row can never tie or outrank a rule
match, whatever the query's word count. (An in-band per-word penalty could: at
two query words a substring-only row with one exact word would tie a genuine
two-typo rule match, and at three it would win.)

The second key, within a class, is the summed per-word distance: an exact word
contributes 0 and a one-keystroke word 1. It is NULL for a substring-only row
that matched no query word at all, which therefore sorts last — the
least-explained match. No weights, no magic literal, nothing fitted.

The length prefilter is **lossless by argument**, not by measurement: one edit
changes a string's length by at most 1, so a word outside `len ± 1` cannot be
within one keystroke. It exists to let the btree prune: on the real
6,273-college corpus a probe without it measured ~91ms/query against ~16ms with
it, and the landed query measures a median 22ms (see Evidence).

`SIMILARITY_THRESHOLD` / `WORD_SIMILARITY_THRESHOLD` and the `SET LOCAL` that
pinned them are deleted: there is no threshold left to pin.

## Evidence

Gathered against the real dev corpus (6,273 colleges) before writing this RFC:

- **Every failing case resolves**, with small candidate sets: Amhurst→Amherst
  schools (3 candidates), Stanfrod→Stanford (1), Harvad→Harvard (1),
  Berkely→Berkeley (7), Cornel→Cornell (3), Mizzou→Missouri-Columbia (1, via
  alias), and Elmhurst→Elmhurst (1) — no cross-contamination.
- **The SQL predicate provably implements the definition**: differential-tested
  against an independent reference implementation of optimal string alignment
  over **2,490 pairs** (1,690 one-keystroke positives generated by exhaustive
  mutation of four real name words, 800 random negatives) — **zero mismatches**.
  The design probe that produced this section ran 2,160 pairs; the committed
  test is the larger corpus, and this number is that test's.
- **Cost**: a design probe measured ~16ms/query. The landed query — which
  quantifies over every query word rather than matching one string — measures a
  **median 22ms (range 18–41ms)** warm on the real 6,273-college corpus (25,526
  word rows), across eight probe queries, 3 warm repetitions each. That is the
  number to hold this against; the 16ms figure described a query that is not the
  one shipped. The spread is structural and worth stating: each query word costs
  one index scan of the `len ± 1` band (~11.6k of the 25,526 word rows) with
  `one_keystroke_off` evaluated per row, so a one-word query measures 18–23ms
  and the two-word `amhurst colege` measures 41ms. Cost is therefore **linear in
  the number of query words**, at roughly 20ms each: a deliberately hostile
  ten-word query (the `MAX_QUERY_LENGTH = 100` boundary admits about a dozen
  real words) measures 153ms. That is the honest worst case at this corpus size,
  and it is recorded rather than capped — at this product's traffic it is a
  latency note, not a defence problem, and a word cap would be a fitted knob. An
  earlier shape that expanded the match twice — once in the predicate, once in
  the rank key — measured a median 29ms (range 26–35ms); computing it once is
  faster on every single-word probe and slower on the two-word one, because the
  predicate no longer short-circuits per college.

## What this deliberately does NOT solve

1. **Two or more keystroke errors.** "Massachusettes" (two edits) will not
   match. The guarantee is exactly one keystroke, and is stated as such rather
   than widened into a metric that sometimes catches two and sometimes inverts.
2. **Ranking among many legitimate matches.** Measured: over 57 randomly typo'd
   real names, the intended school ranked first 49% of the time (median 12
   candidates). The misses are queries like `minnqesota` and `technixal`, where
   20+ schools genuinely contain that word — one word does not identify one
   school, and no matching rule can repair that. Both consumers (the iOS picker,
   and the model choosing a `college_id` from `search_colleges` output) see a
   LIST, so this is cosmetic below the result limit. It becomes real only when
   candidates exceed the limit (`technixal` → 363), where the intended school
   can fall off the list. Recorded in the product backlog with exactly that
   promotion trigger; not addressed here.

## Files Modified

- `db/schema/0056.one-keystroke-name-matching.sql` — new (extension swap,
  `one_keystroke_off`, `college_search_words`, word table + seed, index drop)
- `db/src/main/kotlin/ed/unicoach/db/dao/CollegesDao.kt` — `searchByName`
  rewrite; thresholds deleted; word-table rebuild query
- `db/src/main/kotlin/ed/unicoach/db/dao/CollegeNameWordsDao.kt` — new, or the
  rebuild lives beside the other derived writes in `CollegesDao`
- `college/src/main/kotlin/ed/unicoach/college/CollegeScorecardLoader.kt` —
  phase-2 rebuild of `college_name_words`; `index_rows`-style count in the
  provenance row
- `db/src/test/kotlin/ed/unicoach/db/dao/CollegesDaoTest.kt` — typo corpus + the
  property test below
- `college/src/test/kotlin/ed/unicoach/college/CollegeScorecardIngestTest.kt` —
  word-table rebuild coverage
- `product/STATUS.md` — the "Finding a college by name" entry described trigram
  matching; corrected to this mechanism

## Implementation Plan

1. Migration: `CREATE EXTENSION fuzzystrmatch`, `one_keystroke_off`,
   `college_search_words`, `college_name_words` + index, the seed of that table
   from the colleges already present, and the drop of the trigram index and
   `pg_trgm`.
2. Rebuild the word table wholesale in its own ingest phase (`name-words`, after
   `aliases`, before `provenance`, registered in `committedPhases`), and carry
   its row count into `college_index_build.index_rows` — NULL since 0052,
   populated from here — and into the printed summary. `method_version` 1 → 2:
   the derivation logic changed.
3. Rewrite `searchByName` to the query above; delete the threshold constants and
   the `SET LOCAL` pinning.
4. Tests, including the property test.
5. Full `nix develop -c bin/test` + `bin/shell-tests`; re-measure query latency
   on the real corpus and report it.

## Tests

- **Property test (the one that would have caught this bug):** for a sample of
  real colleges, generate **every** single-keystroke variant of a distinctive
  name word — all substitutions, insertions, deletions, and adjacent
  transpositions — and assert the college is found for each. Recall is a theorem
  here, so any failure is an implementation defect, and the test is the proof
  obligation. RFC 139's tests asserted only the two-word `"Amhurst Colege"`
  case, which passed for the wrong reason; a single-word case was never tested.
- **Differential test:** `one_keystroke_off` against a reference OSA
  implementation over generated positives and random negatives (the 2,490-pair
  check above, as a committed test), asserting both argument orders and that the
  corpus really does contain negatives.
- **Regression:** `Amhurst` → Amherst College present and Elmhurst NOT ranked
  above it; `Stanfrod` → Stanford; `Harvad` → Harvard; `Mizzou` → Missouri
  (alias path unchanged); `Amh` → Amherst (substring path unchanged); the
  two-word `Amhurst Colege` → Amherst College (RFC 139's case, now matching for
  the right reason and not through a similarity score).
- **Ingest:** the word table is rebuilt wholesale, an unchanged re-ingest leaves
  it identical, its row count appears in the ingest summary and in
  `college_index_build.index_rows`, and a failure in the one phase that runs
  after it names `name-words` in the partial-state report — the rebuild commits
  rows, so a failed run must say so.
- **Unchanged:** every RFC 139 test for `total_matches`, `sort_by`,
  `credential_level`, aliases, and provenance still passes.
