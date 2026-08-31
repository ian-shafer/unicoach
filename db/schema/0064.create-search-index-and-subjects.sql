-- The search index and its subject taxonomy. RFC 150 (brief 0004 slice
-- `search/03b/the-index`), landing on RFC 147's published codebook tables.
--
-- Two tables arrive here and they are different kinds of thing:
--
--   `subjects` is REFERENCE data — an authored, committed taxonomy
--   (`db/data/subjects.json`) loaded by the `subjects` ingest phase, which runs
--   immediately after `codebooks`.
--   Unversioned, no history trigger; its history is the ingest (brief 0004
--   D15), exactly as `0055...sql:3-5` already says of the IPEDS reference
--   tables.
--
--   `college_search_index` is DERIVED state — one row per college, rebuilt
--   WHOLESALE by a new `search-index` phase, never hand-written and never
--   versioned. It is `college_name_words` (0056) at a wider grain: rows first,
--   derived state second, no per-row triggers.
--
-- Thirty columns survive the rule that puts a column here at all: something
-- FILTERS, SORTS or INDEXES on it (D60). Everything else is payload and is read
-- back from `colleges` / `college_ipeds` at result time, for at most the 25
-- rows a page returns — sixteen duplicated columns are sixteen chances for two
-- tables to disagree between a schema change and the next ingest.
--
-- Every coded column holds OUR vocabulary, not the publisher's number (D61).
-- `colleges` and `college_ipeds` keep storing the raw code, which is CLAUDE.md's
-- rule for a source-defined code; a DERIVED table is under no such obligation,
-- and storing the code here would only force a code-to-word mapping at the tool
-- boundary that the reference tables already do better. The consequence is that
-- "no bare source code reaches a tool result" stops being a boundary convention
-- and becomes a property of the schema: there is no code on this table to leak.

-- ---------------------------------------------------------------------------
-- subjects — the authored field-of-study taxonomy (D49/D50).
--
-- `slug` is the shared `slug` DOMAIN from 0060, so the taxonomy speaks the same
-- dialect as every codebook table. `cip_prefixes` are 2-, 4- or 6-digit CIP
-- prefixes in the canonical digits-only form `CipPrefix` produces; the LOADER
-- is what proves each one matches at least one real `cip_codes` row, fatally,
-- before the first write (D49) — a CHECK cannot contain a subquery, so the
-- schema can only constrain the SHAPE.
--
-- There is deliberately no size limit, no cap on subjects, and no cap on
-- prefixes: what bounds the file is the CIP vocabulary it partitions, not taste
-- (D50). A subject matching no COLLEGE is allowed and reads as zero matches; a
-- subject matching no CIP CODE is fatal.
-- ---------------------------------------------------------------------------

CREATE TABLE subjects (
    slug         slug        NOT NULL PRIMARY KEY,
    name         TEXT        NOT NULL,
    cip_prefixes TEXT[]      NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT subjects_cip_prefixes_nonempty_check
        CHECK (cardinality(cip_prefixes) > 0),
    -- A CHECK cannot contain a subquery, so the array is validated as ONE
    -- string: 2, 4 or 6 digits per element, comma-separated. This is the shape
    -- guard only — the "does it match a real code" half is the loader's, and is
    -- fatal there.
    CONSTRAINT subjects_cip_prefixes_format_check
        CHECK (array_to_string(cip_prefixes, ',')
               ~ '^([0-9]{2}){1,3}(,([0-9]{2}){1,3})*$')
);

-- The house updated_at trigger (0000/0015), the same one every 0060 reference
-- table takes: an unchanged re-load leaves the row byte-identical.
CREATE TRIGGER trigger_03_enforce_subjects_updated_at
BEFORE UPDATE ON subjects
FOR EACH ROW
EXECUTE PROCEDURE update_colleges_timestamp();

COMMENT ON TABLE subjects IS
    'The authored field-of-study taxonomy (db/data/subjects.json, RFC 150). '
    'Reference data: unversioned, no history trigger, reloaded by the ingest. '
    'Every cip_prefix is proven against cip_codes by SubjectLoader, fatally, '
    'before any write — the schema can only constrain the shape.';

-- ---------------------------------------------------------------------------
-- college_search_index — THE derived search table (D47).
-- ---------------------------------------------------------------------------

CREATE TABLE college_search_index (
    -- Derived is not the same as unconstrained. The reference costs one index
    -- probe per row on a table written once per ingest, and it buys the one
    -- defect nobody would catch by reading a result: an index row pointing at a
    -- college that no longer exists, served as a match and joined for a payload
    -- that comes back empty. ON DELETE CASCADE matches `college_name_words`
    -- (0056) and `college_programs_census` (0055).
    college_id                            UUID     NOT NULL PRIMARY KEY
        REFERENCES colleges (id) ON DELETE CASCADE,
    ipeds_unit_id                         INTEGER  NOT NULL,  -- ORDER BY tiebreak
    name                                  TEXT     NOT NULL,  -- NAME_ASC sort key
    search_text                           TEXT     NOT NULL,  -- the ILIKE substring arm
    -- coded columns: OUR words, with real foreign keys where a codebook table
    -- exists (D61). `state` carries no FK: RFC 150 D57's matching constraint on
    -- `colleges.state` is NOT shipped (see the note at the foot of this file),
    -- so this column would be the only thing standing between an unseeded
    -- codebook table and a rebuild that cannot run at all.
    state                                 TEXT     NOT NULL,
    region                                slug     NULL
        REFERENCES ipeds_regions (slug),
    locale                                slug     NULL
        REFERENCES nces_locales (slug),
    -- No `control` codebook table exists: the vocabulary is the Kotlin
    -- InstitutionControl enum, whose labels are underscored and so cannot be
    -- slugs (the `slug` DOMAIN forbids `_`). Those three words are already the
    -- tool's public vocabulary and the value that goes on the wire, so
    -- hyphenating them would be a user-visible contract change (D61a).
    control                               TEXT     NOT NULL
        CONSTRAINT college_search_index_control_check
        CHECK (control IN ('public', 'private_nonprofit', 'private_for_profit')),
    -- the default universe (brief 0004 D2/D18): derived here, raw at source
    is_active                             BOOLEAN  NOT NULL,
    is_four_year                          BOOLEAN  NULL,
    -- `HD.UGOFFER` ("offers undergraduate awards"), stored under a broader
    -- name. The one D60 exception: nothing filters, sorts or indexes on it
    -- today. It is carried because brief 0004 D2 mandates the three universe
    -- flags together, so the axis is available to the next slice without a
    -- rebuild.
    is_degree_granting                    BOOLEAN  NULL,
    -- Our own enumeration of HD.SECTOR (D61b). NULL only when the college has
    -- NO college_ipeds row — an absence, nothing reported either way — while the
    -- publisher's own "sector unknown" (99) is the explicit word `unknown`. The
    -- eleven values are SECTOR_CODES (0..9 plus 99); 10..98 are values IPEDS
    -- does not publish, and accepting one would store junk indistinguishable
    -- from a real sector.
    sector                                TEXT     NULL
        CONSTRAINT college_search_index_sector_check
        CHECK (sector IN ('administrative_unit', 'public_four_year',
            'private_nonprofit_four_year', 'private_for_profit_four_year',
            'public_two_year', 'private_nonprofit_two_year',
            'private_for_profit_two_year', 'public_less_than_two_year',
            'private_nonprofit_less_than_two_year',
            'private_for_profit_less_than_two_year', 'unknown')),
    -- filter AND sort keys, all four, tri-state (brief 0004 D11)
    undergrad_enrollment_headcount        INTEGER  NULL,
    admission_rate_share                  DOUBLE PRECISION NULL,
    net_price_per_year_usd                INTEGER  NULL,
    completion_rate_150pct_4yr_share      DOUBLE PRECISION NULL,
    -- IPEDS attribute filters (D54): slugs and tri-state booleans
    test_policy                           slug     NULL
        REFERENCES admission_test_policies (slug),
    religious_affiliation                 slug     NULL
        REFERENCES religious_affiliations (slug),
    carnegie_class                        slug     NULL
        REFERENCES carnegie_2021_basic_classes (slug),
    carnegie_size                         slug     NULL
        REFERENCES carnegie_2021_size_settings (slug),
    has_rotc                              BOOLEAN  NULL,
    has_study_abroad                      BOOLEAN  NULL,
    offers_housing                        BOOLEAN  NULL,
    -- Postgres cannot foreign-key array ELEMENTS, so this column is
    -- unconstrained by the database. What replaces the constraint is the
    -- rebuild: the INSERT resolves each ASSOC ordinal through
    -- `athletic_associations` with an INNER join INSIDE its LATERAL, so an
    -- ordinal with no codebook row cannot enter the column — while the outer
    -- LEFT JOIN keeps the college. A test asserts the shape (D61).
    --
    -- NULLABLE, and the three array columns are nullable for ONE reason: an
    -- empty array is a FACT, not an absence. A `NOT NULL DEFAULT '{}'` sentinel
    -- makes "this college belongs to no athletic association" — the correct,
    -- KNOWN answer for most of the country — indistinguishable from "IPEDS
    -- reported nothing about its associations", and `excluded_unknown` would
    -- then say a number in the thousands out loud. NULL means we do not know;
    -- `{}` means we know: none.
    athletic_associations                 slug[]   NULL,
    -- Program rollup and its taxonomy expansion (D51), materialised rather than
    -- joined at query time: the taxonomy is small and static between builds,
    -- and a query-time prefix join over the census on every search is precisely
    -- what this table exists to avoid. The consequence, stated plainly: CHANGING
    -- THE TAXONOMY REQUIRES A REBUILD. `cip_codes` stays 6-digit codes because
    -- `cip_codes` is keyed by `code`, not by a slug, and these are a filter key
    -- that never reaches a result as a code.
    -- Both NULLABLE for the reason stated on `athletic_associations`. NULL is
    -- "this college reported no program census at all"; `{}` on `subject_slugs`
    -- beside a non-empty `cip_codes` is a judged NO — the programs are known
    -- and none of them is that subject.
    cip_codes                             TEXT[]   NULL,
    subject_slugs                         slug[]   NULL,
    -- Percentiles over the DEFAULT universe only (D52): a percentile taken
    -- against the 2-year and for-profit rows describes a corpus no student is
    -- searching. These four exist in NO source table, which is why D60 keeps
    -- them — they are a computed fact of the build, not a copy of anything.
    -- `sat_average_percentile_share` is the instructive case: its input,
    -- `colleges.sat_average_equivalent_score`, is not carried here, because
    -- nothing filters or sorts on it; the percentile pass reads it from
    -- `colleges`.
    undergrad_enrollment_percentile_share DOUBLE PRECISION NULL,
    admission_rate_percentile_share       DOUBLE PRECISION NULL,
    sat_average_percentile_share          DOUBLE PRECISION NULL,
    net_price_percentile_share            DOUBLE PRECISION NULL,
    CONSTRAINT college_search_index_percentile_range_check CHECK (
        (undergrad_enrollment_percentile_share IS NULL
            OR undergrad_enrollment_percentile_share BETWEEN 0 AND 1)
        AND (admission_rate_percentile_share IS NULL
            OR admission_rate_percentile_share BETWEEN 0 AND 1)
        AND (sat_average_percentile_share IS NULL
            OR sat_average_percentile_share BETWEEN 0 AND 1)
        AND (net_price_percentile_share IS NULL
            OR net_price_percentile_share BETWEEN 0 AND 1))
);

COMMENT ON TABLE college_search_index IS
    'THE derived search table (RFC 150). One row per college, rebuilt wholesale '
    'by the `search-index` ingest phase; never hand-written, never versioned. '
    'Its history is college_index_build.search_index_rows. A column lives here '
    'only if something filters, sorts or indexes on it (D60); payload is read '
    'from colleges/college_ipeds at result time.';

COMMENT ON COLUMN college_search_index.sector IS
    'Our word for HD.SECTOR (InstitutionSector). NULL means the college has no '
    'college_ipeds row at all; the publisher''s own "unknown (not active)" is '
    'the explicit word `unknown`. The two are different facts.';

COMMENT ON COLUMN college_search_index.athletic_associations IS
    'Slugs resolved through athletic_associations by the rebuild. Postgres '
    'cannot foreign-key array elements, so a TEST stands in for the constraint. '
    'NULL means the college reported nothing about its associations; the empty '
    'array means it reported belonging to none. The two are different facts, '
    'and only the first is counted in excluded_unknown.';

COMMENT ON COLUMN college_search_index.cip_codes IS
    'The college''s reported six-digit program codes. NULL means no program '
    'census was reported at all (unjudgeable); the empty array cannot occur '
    'today and would mean a census reporting no programs.';

COMMENT ON COLUMN college_search_index.subject_slugs IS
    'The subject taxonomy expansion of cip_codes. NULL exactly when cip_codes '
    'is NULL (nothing reported); the empty array is a judged NO — the programs '
    'are known and none of them is any taxonomy subject.';

-- Nine indexes beside the primary key. `college_id` is the PK, so it is indexed
-- by that, and the foreign keys' parent sides need nothing new.
--
-- `search_text` gets NO trigram index, and that is a correction to RFC 150's
-- DDL rather than an omission: 0056 dropped `colleges_search_text_trgm_idx` AND
-- the `pg_trgm` extension with it, replacing trigram matching with the
-- `college_name_words` table plus `one_keystroke_off()`. `gin_trgm_ops` does
-- not exist in this database. 0056's own measurement stands: without the
-- trigram index the ILIKE arm seq-scans, which is microseconds at ~6.3k rows.
--
-- `name` gets no btree of its own either: NAME_ASC scans the whole result set
-- the filters already reduced, and the low-cardinality slug columns (region,
-- locale, test_policy, religious_affiliation, carnegie_class, carnegie_size and
-- the three tri-state booleans) are cheaper on a heap scan of 6,273 rows than
-- through an index the planner would decline to use.
--
-- `control` gets none either, and that is the SAME argument applied honestly:
-- three distinct values over 6,273 rows is the low-cardinality case the
-- paragraph above already refuses to index, and a `control = ANY (...)` over
-- two of the three selects most of the table. `state` DOES get one — ~59
-- distinct values, and a one-state filter is ~2% of the rows, which is the
-- selectivity a btree is for.
CREATE INDEX college_search_index_subject_slugs_idx
    ON college_search_index USING gin (subject_slugs);
CREATE INDEX college_search_index_cip_codes_idx
    ON college_search_index USING gin (cip_codes);
CREATE INDEX college_search_index_athletic_associations_idx
    ON college_search_index USING gin (athletic_associations);
CREATE INDEX college_search_index_universe_idx
    ON college_search_index (is_active, is_four_year, sector);
CREATE INDEX college_search_index_state_idx ON college_search_index (state);
CREATE INDEX college_search_index_enrollment_idx
    ON college_search_index (undergrad_enrollment_headcount);
CREATE INDEX college_search_index_admission_rate_idx
    ON college_search_index (admission_rate_share);
CREATE INDEX college_search_index_net_price_idx
    ON college_search_index (net_price_per_year_usd);
CREATE INDEX college_search_index_completion_rate_idx
    ON college_search_index (completion_rate_150pct_4yr_share);

-- ---------------------------------------------------------------------------
-- Provenance (D48). RFC 146 quietly repurposed `index_rows` for the
-- `college_name_words` rebuild count, which made 0052's comment ("NULL until
-- the S3 derived table arrives") false. Say what the column actually holds, and
-- give the S3 table a column of its own. One column, one meaning.
-- ---------------------------------------------------------------------------

ALTER TABLE college_index_build RENAME COLUMN index_rows TO name_words_rows;
ALTER TABLE college_index_build
    RENAME CONSTRAINT college_index_build_index_rows_nonneg_check
        TO college_index_build_name_words_rows_nonneg_check;
ALTER TABLE college_index_build
    ADD COLUMN search_index_rows INTEGER NULL,
    ADD CONSTRAINT college_index_build_search_index_rows_nonneg_check
        CHECK (search_index_rows IS NULL OR search_index_rows >= 0);

COMMENT ON COLUMN college_index_build.name_words_rows IS
    'Rows written by the college_name_words rebuild (RFC 146). NULL for every '
    'RFC 139-era row, written before that table existed.';
COMMENT ON COLUMN college_index_build.search_index_rows IS
    'Rows written by the college_search_index rebuild (RFC 150). NULL for '
    'every build row written before that table existed.';

-- ---------------------------------------------------------------------------
-- RFC 150 D57 is NOT shipped, and the reason is measurement, not preference.
--
-- D57 proposed dropping `colleges_locale_range_check` and
-- `colleges_state_length_check` and replacing them with real foreign keys onto
-- `nces_locales (code)` and `us_states (usps_code)`. The RFC bound that to a
-- measurement — "should the fixture corpus or the real snapshot show any locale
-- code with no nces_locales row, or any state code with no us_states row, that
-- FK is dropped from the migration rather than shipped".
--
-- The measurement is recorded in RFC 150's implementation report. Every STORED
-- state and locale VALUE resolves. What does not hold is the premise underneath
-- the FK: `us_states` and `nces_locales` are INGEST-LOADED reference tables,
-- empty in a migrated-but-never-ingested database and truncated before every
-- test in `CollegeScorecardTestBase`. A foreign key on `colleges.state` would
-- therefore make the `institutions` phase — and every direct `colleges` insert
-- — fail against an empty codebook table, turning "the codebooks phase should
-- run first" from an ordering into a hard precondition of writing ANY college
-- row. That is a much larger change than D57 argued for, and it is not this
-- slice's to make silently.
--
-- So both checks STAY exactly as 0015 wrote them, `college_search_index.state`
-- carries no FK either (its soundness argument was "satisfiable by
-- construction", which depended on D57 landing), and the constraint tightening
-- stays open with the measurement now on the record.
-- ---------------------------------------------------------------------------
