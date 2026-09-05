-- IPEDS SFA (RFC 162, brief 0006 slice shape/03): the SFA staging table, the
-- sibling headcount table population counts land in, and the widened
-- cohort_money_stats vocabularies the SFA canonical fill needs.
--
-- Three things, one migration, because they are one change: the SFA staging
-- rows are read by the `canonical-money` fill that writes both fact tables,
-- and a widened CHECK without the rows (or rows without the CHECK) is a half
-- state no run can use.
--
-- WHICH aid year each figure carries is DATA here (`aid_year`, `vintage`),
-- never a comment: the 0062 rule that a landed migration must not restate a
-- fact the snapshot moves.

-- ---------------------------------------------------------------------------
-- college_sfa -- the IPEDS Student Financial Aid staging rows.
-- ---------------------------------------------------------------------------
--
-- Reference/staging table, the 0055 college_ipeds composition: unversioned (no
-- history trigger, no soft delete), rebuilt by bin/ingest-colleges' `sfa`
-- phase, its history is college_index_build.
--
-- ONE ROW PER MEASURED CELL, not one row per institution with a column per
-- variable. The SFA file is 691 columns: 345 measured variables and their 345
-- mechanical `X`-prefixed imputation twins (plus UNITID). The ~60 variables
-- this ingest reads are the SAME THREE FACTS repeated -- a value, its
-- publisher flag, the aid year it describes -- so a wide table would restate
-- those three columns sixty times and force a migration for every variable
-- added later. The published variable NAME is kept raw and lowercase, exactly
-- as the source spells it (the source-defined-codes rule), so a reader can go
-- from a stored row back to the NCES dictionary entry with no decoder ring.
--
-- The value is NUMERIC because SFA publishes dollars (integers), integer
-- percents and headcounts through the same shape; the canonical fill is what
-- knows which is which, and it labels the unit there.
CREATE TABLE college_sfa (
    id             UUID        NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    ipeds_unit_id  INTEGER     NOT NULL,   -- SFA.UNITID; joins colleges.ipeds_unit_id
    aid_year_start SMALLINT    NOT NULL,   -- --sfa-aid-year: SFA2223 -> 2022. NOT college_ipeds.survey_year, which is 2023 for the same file set
    variable       TEXT        NOT NULL,   -- the published variable name, lowercase as the CSV spells it ('npist2', 'upgrnta')
    aid_year       TEXT        NOT NULL,   -- 'YYYY-YY' the CELL describes: the suffix 0/1/2 resolved against aid_year_start
    value          NUMERIC     NULL,       -- the published number, raw; NULL exactly when the flag bears no value
    publisher_flag TEXT        NOT NULL,   -- the raw X-twin imputation code (X<variable>), stored as published
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT college_sfa_ipeds_unit_id_positive_check CHECK (ipeds_unit_id > 0),
    CONSTRAINT college_sfa_aid_year_start_range_check CHECK (aid_year_start BETWEEN 1980 AND 2100),
    CONSTRAINT college_sfa_variable_format_check CHECK (variable ~ '^[a-z][a-z0-9_]*$'),
    CONSTRAINT college_sfa_aid_year_format_check CHECK (aid_year ~ '^[0-9]{4}-[0-9]{2}$'),
    -- The published code set (SFA2223_Dict.zip -> sfa2223.xlsx, sheet
    -- "imputation values"), not a range: an unknown letter means the publisher
    -- changed the vocabulary, which is a review, never a row.
    CONSTRAINT college_sfa_publisher_flag_domain_check
        CHECK (publisher_flag IN ('A', 'B', 'C', 'D', 'G', 'H', 'J', 'K', 'L', 'N', 'P', 'R', 'Z')),
    -- Measured over 1.95M (value, flag) pairs in sfa2223.csv with zero
    -- exceptions: flag A ("not applicable") always has an empty value cell.
    -- The converse is NOT asserted -- a future file may blank a cell under a
    -- flag that usually carries one -- so this is the one direction the data
    -- actually pins.
    CONSTRAINT college_sfa_not_applicable_has_no_value_check
        CHECK (publisher_flag <> 'A' OR value IS NULL)
);

-- The natural key: one institution's one variable in one loaded file. The cell's
-- own aid year is NOT in it -- it is a function of (aid_year_start, variable) --
-- so a second row for the same cell collides rather than shadowing.
CREATE UNIQUE INDEX college_sfa_ipeds_unit_id_aid_year_start_variable_unique_idx
    ON college_sfa (ipeds_unit_id, aid_year_start, variable);

-- No per-variable index: the natural key above is the only one this table
-- needs. Its ONE reader (CollegeSfaDao.allCells) scans every cell of one
-- loaded file's aid year, so a second B-tree over ~460k rows would be an index write per staged
-- cell per ingest for zero reads.

CREATE TRIGGER trigger_03_enforce_college_sfa_updated_at
BEFORE UPDATE ON college_sfa
FOR EACH ROW
EXECUTE PROCEDURE update_colleges_timestamp();

COMMENT ON TABLE college_sfa IS
    'IPEDS Student Financial Aid staging (RFC 162): one row per measured cell '
    '-- institution, published variable name, the aid year the cell describes, '
    'the value and its raw X imputation flag. Wholesale-rebuilt by the `sfa` '
    'ingest phase; its history is college_index_build. The canonical-money '
    'fill reads it; nothing else does.';

COMMENT ON COLUMN college_sfa.aid_year_start IS
    'The START year of the FILE''s own aid year: SFA2223 is aid year 2022-23, '
    'so 2022. Deliberately NOT called a survey year -- college_ipeds.survey_year '
    'is 2023 for the same published file set, so a join on "the survey year" '
    'across the two tables would silently match nothing.';

COMMENT ON COLUMN college_sfa.aid_year IS
    'The aid year the CELL describes, ''YYYY-YY''. SFA year suffixes are '
    'RELATIVE to the file: in SFAyyzz, 2 is the file''s own aid year, 1 one '
    'earlier, 0 two earlier. Resolving that at load is what stops a reader '
    'hardcoding "suffix 2 means 2022-23" and being silently wrong next file.';

COMMENT ON COLUMN college_sfa.publisher_flag IS
    'The raw IPEDS X-twin code beside the value (R reported, C analyst '
    'corrected, Z implied zero, A not applicable, P/N imputed, ...), stored as '
    'the source publishes it. IpedsImputationFlag maps it to a figure_statuses '
    'slug at fill time; the raw letter rides on the canonical row too.';

-- ---------------------------------------------------------------------------
-- cohort_population_counts -- headcounts, which are not money.
-- ---------------------------------------------------------------------------
--
-- A sibling of cohort_money_stats, not more rows inside it: that table's own
-- comment says it holds "a number about a population, never a price", and a
-- headcount has no MeasureUnit, needs a residency value its two-value
-- residency_scope cannot express (in_district, unknown), and needs an
-- arrangement dimension it does not have at all.
--
-- The two axes are the RFC 158 authored vocabularies by FK (residency_bases,
-- arrangements), so "how many of this cohort pay the in-district rate" and
-- "how many live with family" are the SAME vocabulary the price side speaks.
CREATE TABLE cohort_population_counts (
    id              UUID        NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    college_id      UUID        NOT NULL REFERENCES colleges(id),
    population      TEXT        NOT NULL,
    residency_basis TEXT        NOT NULL REFERENCES residency_bases(slug),
    arrangement     TEXT        NOT NULL REFERENCES arrangements(slug),
    vintage         TEXT        NOT NULL,
    headcount       INTEGER     NULL,
    status          TEXT        NOT NULL REFERENCES figure_statuses(slug),
    source          TEXT        NOT NULL,
    source_variable TEXT        NOT NULL,
    publisher_flag  TEXT        NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- The same population vocabulary cohort_money_stats speaks, mirrored by
    -- CohortPopulation and pinned to it by test, both ways.
    CONSTRAINT cohort_population_counts_population_check
        CHECK (population IN ('title_iv_aided_undergraduates',
                              'undergraduates',
                              'federal_loan_borrowing_completers',
                              'employed_not_enrolled_10y_after_entry',
                              'first_time_full_time_aid_cohort',
                              'pell_receiving_undergraduates')),
    -- 'YYYY-YY' only, WITHOUT cohort_money_stats' 'undated' escape: every
    -- headcount this table admits comes from an SFA cell whose aid year the
    -- staging loader has already resolved, so there is no pooled or undated
    -- count to admit. A table that admits a state no writer produces is a
    -- state some future reader must still handle.
    CONSTRAINT cohort_population_counts_vintage_format_check
        CHECK (vintage ~ '^[0-9]{4}-[0-9]{2}$'),
    -- D3 again, and for the same reason: a count exists exactly when the
    -- status bears one. The two value-bearing statuses are named literally
    -- (a CHECK cannot subquery figure_statuses.value_bearing).
    CONSTRAINT cohort_population_counts_value_iff_status_check
        CHECK ((headcount IS NOT NULL) =
               (status IN ('reported', 'imputed_by_publisher'))),
    CONSTRAINT cohort_population_counts_headcount_nonneg_check
        CHECK (headcount IS NULL OR headcount >= 0),
    -- The SAME MoneySource domain 0084 pinned on the two money tables (RFC
    -- 161 decision 6), stated once more here because a headcount names its
    -- publisher exactly as a price does. Listed, not counted: the count
    -- belongs to MoneySource, which is their one declaration.
    CONSTRAINT cohort_population_counts_source_domain_check
        CHECK (source IN ('ipeds_sfa', 'ipeds_ic_ay', 'scorecard')),
    CONSTRAINT cohort_population_counts_source_variable_nonempty_check
        CHECK (source_variable <> '')
);

-- NULLS NOT DISTINCT for the same reason cohort_money_stats' key has it: every
-- axis here is NOT NULL today, and the day one becomes nullable ("the count
-- across all arrangements") two such rows must collide rather than both land.
CREATE UNIQUE INDEX cohort_population_counts_natural_key
    ON cohort_population_counts (college_id, population, residency_basis,
                                 arrangement, vintage)
    NULLS NOT DISTINCT;

-- No separate college_id index: the natural key above already LEADS with
-- college_id, so a by-college probe walks it. Nothing reads this table by
-- college yet (CanonicalMoneyDao only deletes all, batch-inserts and counts by
-- status), so a second B-tree would be an index write per rebuilt row for zero
-- reads -- the same trade refused for college_sfa above.

CREATE TRIGGER trigger_03_enforce_cohort_population_counts_updated_at
BEFORE UPDATE ON cohort_population_counts
FOR EACH ROW
EXECUTE PROCEDURE update_colleges_timestamp();

COMMENT ON TABLE cohort_population_counts IS
    'One published headcount per row (RFC 162): how many students of a cohort '
    'sit on a residency basis and a living arrangement, in a given vintage. '
    'The sibling of cohort_money_stats for numbers that are not money -- a '
    'headcount has no unit, and its two axes are the authored residency_bases '
    'and arrangements vocabularies. Rebuilt wholesale by the canonical-money '
    'ingest phase; its history is college_index_build.';

COMMENT ON COLUMN cohort_population_counts.residency_basis IS
    'Which tuition rate this part of the cohort pays (IPEDS SCFA11N-SCFA14N: '
    'in_district, in_state, out_of_state, unknown), or not_applicable for a '
    'count that does not split by residency.';

COMMENT ON COLUMN cohort_population_counts.arrangement IS
    'Where this part of the cohort lives (IPEDS GIS4ON/WF/OF/UN and the GRN4 '
    'private twin), or not_applicable for a count that does not split by '
    'living arrangement. These are the enrolment weights the published net '
    'price averages away.';

-- The 0083 comment on arrangements.is_living_arrangement said not_applicable
-- was the only FALSE row. The `unknown` arrangement the SFA counts need is a
-- second one -- a published category, not a way of living -- so the comment is
-- restated here rather than left as a fact the snapshot has moved past.
COMMENT ON COLUMN arrangements.is_living_arrangement IS
    'TRUE for a way a student actually lives (on_campus/off_campus/'
    'with_family); FALSE for not_applicable (the concept does not vary by it) '
    'and for unknown (the publisher counts students whose arrangement it did '
    'not determine). The loader-enforced pairing rule (P3) reads this flag: a '
    'CHECK cannot span tables.';

-- ---------------------------------------------------------------------------
-- The widened cohort_money_stats vocabularies (RFC 162's measure table).
-- ---------------------------------------------------------------------------
--
-- DROP + re-ADD, never "ALTER ... ADD another CHECK": the enum mirrors are
-- pinned to these lists by set equality BOTH ways, so the list must stay one
-- list.
ALTER TABLE cohort_money_stats
    DROP CONSTRAINT cohort_money_stats_measure_check,
    ADD CONSTRAINT cohort_money_stats_measure_check
        CHECK (measure IN ('avg_net_price', 'published_cost_blend',
                           'pell_share', 'median_debt_at_completion',
                           'median_earnings_10y',
                           'pell_average_award',
                           'federal_grant_share', 'federal_grant_average_award',
                           'state_local_grant_share',
                           'state_local_grant_average_award',
                           'institutional_grant_share',
                           'institutional_grant_average_award',
                           'student_loan_share', 'student_loan_average_amount')),
    -- A SHARE row holds a 0-1 share, never the source's published integer
    -- percent. Every `_share` measure is fed from an IPEDS `_P` column that
    -- publishes 18 for eighteen percent, and the conversion to 0.18 is a
    -- reader-side unit conversion (MeasureUnit.SHARE). The Kotlin side derives
    -- the factor from the measure's own unit so it cannot be forgotten at a
    -- call site; this is where a forgotten one is REFUSED rather than stored.
    ADD CONSTRAINT cohort_money_stats_share_range_check
        CHECK (value IS NULL
               OR measure NOT IN ('pell_share', 'federal_grant_share',
                                  'state_local_grant_share',
                                  'institutional_grant_share',
                                  'student_loan_share')
               OR value BETWEEN 0 AND 1),
    DROP CONSTRAINT cohort_money_stats_population_check,
    ADD CONSTRAINT cohort_money_stats_population_check
        CHECK (population IN ('title_iv_aided_undergraduates',
                              'undergraduates',
                              'federal_loan_borrowing_completers',
                              'employed_not_enrolled_10y_after_entry',
                              'first_time_full_time_aid_cohort',
                              'pell_receiving_undergraduates')),
    -- The aid scope follows the DENOMINATOR, symmetrically: a *_share row is a
    -- share OF THE COHORT and carries 'all'; a *_average_award / _average_amount
    -- row is an average OVER RECIPIENTS and carries its own receiving scope, as
    -- 'pell_receiving' already bounds the average Pell award. The four new
    -- receiving scopes are the aid mix's four averages. 'loan_receiving' is
    -- deliberately NOT 'federal_loan_borrowing': IPEDS LOAN_A/LOAN_P count any
    -- loan -- federal, institutional or private.
    DROP CONSTRAINT cohort_money_stats_aid_scope_check,
    ADD CONSTRAINT cohort_money_stats_aid_scope_check
        CHECK (aid_scope IN ('federal_aid_receiving',
                             'federal_loan_borrowing', 'all',
                             'grant_aided', 'pell_receiving',
                             'federal_grant_receiving',
                             'state_local_grant_receiving',
                             'institutional_grant_receiving',
                             'loan_receiving'));

-- ---------------------------------------------------------------------------
-- The MoneySource domain gains its third member.
-- ---------------------------------------------------------------------------
--
-- 0084 made `source` an owned enumeration (RFC 161 decision 6) and pinned both
-- money tables to the two publishers that existed then. SFA is the third, and
-- the enum and these CHECKs are pinned to each other by test, so the list must
-- stay ONE list: DROP + re-ADD, never a second CHECK beside the first.
ALTER TABLE price_figures
    DROP CONSTRAINT price_figures_source_domain_check,
    ADD CONSTRAINT price_figures_source_domain_check
        CHECK (source IN ('ipeds_sfa', 'ipeds_ic_ay', 'scorecard'));

ALTER TABLE cohort_money_stats
    DROP CONSTRAINT cohort_money_stats_source_domain_check,
    ADD CONSTRAINT cohort_money_stats_source_domain_check
        CHECK (source IN ('ipeds_sfa', 'ipeds_ic_ay', 'scorecard'));

-- ---------------------------------------------------------------------------
-- Provenance (P11): the 0064/0083 one-column-one-meaning precedent.
-- ---------------------------------------------------------------------------

ALTER TABLE college_index_build
    ADD COLUMN cohort_population_count_rows INTEGER NULL,
    ADD CONSTRAINT college_index_build_cohort_population_count_rows_nonneg_chk
        CHECK (cohort_population_count_rows IS NULL OR cohort_population_count_rows >= 0);

-- ---------------------------------------------------------------------------
-- `unknown` is a COUNT category, never a price key.
-- ---------------------------------------------------------------------------
--
-- The SFA headcounts need a published `unknown` residency basis and living
-- arrangement (SCFA14N, GIS4UN/GRN4UN): the publisher counted students it could
-- not classify. price_figures FKs onto the same two vocabularies, so widening
-- them made `unknown` an insertable PRICE key -- and a price about students
-- nobody classified is a price about nobody. FigureArrangement.UNKNOWN says so
-- in a doc comment; said here too, where an INSERT can actually be refused.
--
-- ALLOWLISTS, not `<> 'unknown'`. This very migration is the proof that these
-- two vocabularies gain COUNT-ONLY members: `unknown` was added for SCFA14N
-- and GIS4UN/GRN4UN, which no price will ever key on. A denylist admits the
-- NEXT such member by default, and the mistake is a stored price about a
-- category nobody is quoted; an allowlist refuses it until a migration says,
-- deliberately, that the new slug is price-keyable.
ALTER TABLE price_figures
    ADD CONSTRAINT price_figures_residency_basis_price_keyable_check
        CHECK (residency_basis IN ('in_district', 'in_state', 'out_of_state', 'not_applicable')),
    ADD CONSTRAINT price_figures_arrangement_price_keyable_check
        CHECK (arrangement IN ('on_campus', 'off_campus', 'with_family', 'not_applicable'));

COMMENT ON COLUMN college_index_build.cohort_population_count_rows IS
    'Rows written by the cohort_population_counts rebuild (RFC 162). NULL for '
    'every build row written before that table existed.';
