-- One keystroke, not a similarity score: fuzzystrmatch + the derived word table. RFC 146.
--
-- RFC 139 matched typos with pg_trgm word_similarity at a 0.6 threshold. That
-- metric scores the best-matching contiguous EXTENT of the target, so a
-- different real word sharing a long tail beats the intended word carrying a
-- mid-word typo: "Amhurst" returned Elmhurst University (0.625) and missed
-- Amherst College (0.455) entirely. No threshold repairs that — lowering the
-- floor to admit Amherst keeps the inversion. The defect is the metric, so the
-- metric goes.
--
-- What replaces it is a definition rather than a score: a typo is ONE keystroke
-- wrong — a substitution, an insertion, a deletion, or an adjacent
-- transposition, i.e. optimal-string-alignment (Damerau-Levenshtein) distance
-- <= 1. Recall is then a theorem, not a corpus statistic: a query formed by
-- mistyping one key in each word of a name is by definition within one
-- keystroke of each of those words, for every name in the table and every name
-- added later.
--
-- With the trigram arms gone every remaining mechanism is exact (one-keystroke,
-- ILIKE substring, curated aliases), so pg_trgm has no job left and its GIN
-- index is dropped with it; fuzzystrmatch (the same contrib set, likewise on
-- AWS's RDS-supported list) takes its place, leaving the extension count
-- unchanged. college_search_text() is RETAINED — it is no longer an index
-- expression but it is still the one definition of a college's searchable text,
-- and the word table below is derived from it. Without the trigram index the
-- ILIKE arm seq-scans, which is microseconds at ~6.3k rows.

CREATE EXTENSION fuzzystrmatch;

DROP INDEX colleges_search_text_trgm_idx;
DROP EXTENSION pg_trgm;

-- "One keystroke off": levenshtein_less_equal(a, b, 1) covers substitution,
-- insertion and deletion; the second arm covers adjacent transposition, which
-- plain Levenshtein scores as 2 (Stanfrod -> Stanford). Together they are
-- exactly optimal string alignment distance <= 1. The bounded levenshtein
-- variant is the cheap one: it abandons a row once the distance exceeds 1
-- rather than filling the whole matrix. That bound and the `<= 1` beside it are
-- ONE decision, not two: past max_d the function is only documented to return
-- SOME value greater than max_d, so the comparison is only meaningful because
-- it is the same 1 — changing either alone would silently change the rule.
--
-- The SQL-standard unquoted body (`RETURN …`, the college_search_text
-- precedent in 0051) rather than a quoted AS $$…$$ body, and deliberately: a
-- standard body is parsed and its function references resolved AT CREATION
-- TIME, so `levenshtein_less_equal` is bound here, recorded as a real
-- dependency of this function on the extension, and cannot be re-pointed by
-- whatever search_path a calling session happened to inherit. (`SET
-- search_path` on the function would also pin it, but it defeats inlining and
-- adds a GUC save/restore to the single hottest call in the search query —
-- this predicate runs once per candidate word row.)
--
-- fuzzystrmatch documents 255 characters as levenshtein's maximum argument
-- length, and raises above it rather than returning a distance. No runtime
-- guard is added here, because the bound is unreachable by argument rather
-- than merely unlikely. The only caller is CollegesDao.searchByName, and it
-- compares a query word against stored words only inside the len +/- 1 band
-- around that query word: `a` is a word of a query clamped to
-- CollegeSearchService.MAX_QUERY_LENGTH = 100 before it reaches the DAO, so
-- `a` is at most 100 characters, and `b` is drawn from the band, so it is at
-- most 101. Both sit an order of magnitude under 255. A guard here would be a
-- branch for a case the caller's own bound already excludes, so the bound is
-- stated as the argument it is instead.
CREATE FUNCTION one_keystroke_off(a TEXT, b TEXT)
RETURNS BOOLEAN
LANGUAGE sql IMMUTABLE PARALLEL SAFE
RETURN levenshtein_less_equal(a, b, 1) <= 1
    OR (length(a) = length(b) AND EXISTS (
          SELECT 1 FROM generate_series(1, length(a) - 1) i
          WHERE overlay(a placing reverse(substring(a from i for 2)) from i for 2) = b));

-- The ONE definition of a search word: lowercase, cut on [^a-z0-9]+, drop the
-- empty strings a leading, trailing or doubled separator produces. Both sides
-- of the match rule call it — the derived table below is built from it, and
-- searchByName splits the user's query with it — so the stored words and the
-- query words cannot be cut by two different engines (a Kotlin regex and this
-- one) and quietly disagree. Duplicates are NOT collapsed here: the rebuild
-- de-duplicates per college (the primary key requires it), while the query
-- keeps a repeated word repeated, which is what the rank sum has always seen.
-- Standard body, same reason as above.
CREATE FUNCTION college_search_words(search_text TEXT)
RETURNS TEXT[]
RETURNS NULL ON NULL INPUT
LANGUAGE sql IMMUTABLE PARALLEL SAFE
RETURN coalesce(
    (SELECT array_agg(word)
     FROM regexp_split_to_table(lower(search_text), '[^a-z0-9]+') AS word
     WHERE word <> ''),
    '{}'::TEXT[]);

-- Derived: one row per (college, word) over college_search_words(
-- college_search_text(...)). Rebuilt WHOLESALE by the ingest's own phase, never
-- hand-written and never trigger-maintained — the same derived-state discipline
-- the ingest already applies to the rest of the college snapshot (RFC 139:
-- rows first, derived state second).
--
-- `len` is GENERATED ALWAYS … STORED, not a column a writer supplies: the
-- length prefilter below is lossless only while len IS length(word), so a row
-- whose len lied would silently delete matches the rule calls a theorem. The
-- database derives it, so that row is not representable at all.
CREATE TABLE college_name_words (
    college_id UUID     NOT NULL REFERENCES colleges(id) ON DELETE CASCADE,
    word       TEXT     NOT NULL,
    len        SMALLINT NOT NULL GENERATED ALWAYS AS (length(word)::SMALLINT) STORED,
    PRIMARY KEY (college_id, word)
);

-- (len, word) leads with the length so the one-keystroke search can prune on
-- len BETWEEN length(qw) - 1 AND length(qw) + 1 — lossless by argument (one
-- edit changes a string's length by at most 1), and measured 91ms -> 16ms per
-- query on the real 6,273-college corpus.
CREATE INDEX college_name_words_len_word_idx ON college_name_words (len, word);

-- Seed the derived table from the colleges already in this database, with the
-- same expression CollegesDao.rebuildNameWords uses. Unlike the GIN index it
-- replaces, this table does not populate itself: without the seed every
-- existing database — dev, and production — would lose typo matching from the
-- moment this migration commits until somebody hand-ran the ingest, which is a
-- manual, source-file-bearing operator task and not part of deploy. The
-- migration seeds it once; the ingest's `name-words` phase owns it thereafter.
INSERT INTO college_name_words (college_id, word)
SELECT DISTINCT c.id, w
FROM colleges c,
  LATERAL unnest(college_search_words(college_search_text(c.name, c.aliases))) AS w;

-- The seed is a wholesale build, so the planner's statistics for this table
-- describe an empty relation until it is analysed — the one case where the
-- length prefilter's btree looks pointless to the planner (the rebuild does the
-- same for the same reason).
ANALYZE college_name_words;
