-- The canonical money store (RFC 158, brief 0006 slice shape/01): five
-- unicoach-authored vocabulary tables, two fact tables shaped like what money
-- IS, and the empty aid-policy table modeled ahead (D9). Nothing reads these
-- yet; the `shape/04` slice opens that door.
--
-- Vocabulary tables (D6): unicoach-authored, seeded from
-- db/data/money-vocabulary.json by the money-vocabulary ingest phase.
-- Reference data: unversioned, no history trigger, house updated_at trigger,
-- reloaded by the ingest (the 0060/0064 reference-table shape).
--
-- Slug columns here are TEXT with an underscore-slug format CHECK, NOT the
-- shared `slug` DOMAIN from 0060 (which allows hyphens only): the vocabulary
-- values are gate-1-fixed with underscores -- `not_applicable`,
-- `tuition_and_fees`, `under_30k` -- because they must byte-agree with
-- LivingArrangement.kt, IncomeBand.kt and money_profiles' CHECK lists (P10),
-- all of which already speak underscores. One vocabulary, one spelling.
--
-- Fact tables (D2): one figure per row, keyed on the axes a figure really has
-- (concept, residency, arrangement, year; measure, population basis, vintage).
-- Wholesale-rebuilt by the `canonical-money` ingest phase, never hand-written,
-- never versioned; their history is `college_index_build`.
--
-- WHICH academic year each figure carries is DATA here (`academic_year`,
-- `vintage`), never a comment: the 0062 rule that a landed migration must not
-- restate a fact the snapshot moves.

CREATE TABLE residency_bases (
    slug        TEXT        NOT NULL PRIMARY KEY,
    description TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT residency_bases_slug_format_check
        CHECK (slug ~ '^[a-z0-9]+(_[a-z0-9]+)*$')
);
-- Rows: db/data/money-vocabulary.json (the authority; the loader proves it
-- against ResidencyBasis both ways, fatally). D4's four values.

CREATE TRIGGER trigger_03_enforce_residency_bases_updated_at
BEFORE UPDATE ON residency_bases
FOR EACH ROW
EXECUTE PROCEDURE update_colleges_timestamp();

COMMENT ON TABLE residency_bases IS
    'The residency axis a published price is on (RFC 158, D4). Authored '
    'vocabulary (db/data/money-vocabulary.json), pinned to the Kotlin '
    'ResidencyBasis enum by MoneyVocabularyLoader, fatally, both ways. '
    'not_applicable is an explicit row: a figure that does not vary by '
    'residency SAYS so rather than defaulting to NULL.';

CREATE TABLE arrangements (
    slug                  TEXT        NOT NULL PRIMARY KEY,
    description           TEXT        NOT NULL,
    is_living_arrangement BOOLEAN     NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT arrangements_slug_format_check
        CHECK (slug ~ '^[a-z0-9]+(_[a-z0-9]+)*$')
);
-- Rows: the seed file, proven against FigureArrangement. The living
-- arrangements match LivingArrangement.kt (pinned by test); the one row with
-- is_living_arrangement FALSE is the explicit key arrangement-invariant
-- concepts use (P3) -- inapplicability is chosen, never defaulted.

CREATE TRIGGER trigger_03_enforce_arrangements_updated_at
BEFORE UPDATE ON arrangements
FOR EACH ROW
EXECUTE PROCEDURE update_colleges_timestamp();

COMMENT ON TABLE arrangements IS
    'The way-of-living axis a published price is on (RFC 158, D4): the three '
    'living arrangements (LivingArrangement.kt, pinned by test) plus '
    'not_applicable, the explicit key an arrangement-invariant concept pairs '
    'with (P3). Authored vocabulary (db/data/money-vocabulary.json).';

COMMENT ON COLUMN arrangements.is_living_arrangement IS
    'TRUE for a way a student actually lives (on_campus/off_campus/'
    'with_family); FALSE only for not_applicable. The loader-enforced pairing '
    'rule (P3) reads this flag: a CHECK cannot span tables.';

CREATE TABLE figure_statuses (
    slug          TEXT        NOT NULL PRIMARY KEY,
    description   TEXT        NOT NULL,
    value_bearing BOOLEAN     NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT figure_statuses_slug_format_check
        CHECK (slug ~ '^[a-z0-9]+(_[a-z0-9]+)*$')
);
-- Rows: the seed file, proven against FigureStatus. D3's six statuses, two
-- of them value-bearing -- and the *_value_iff_status_check CHECKs below name
-- those two literally (a CHECK cannot subquery), pinned to this column by test.

CREATE TRIGGER trigger_03_enforce_figure_statuses_updated_at
BEFORE UPDATE ON figure_statuses
FOR EACH ROW
EXECUTE PROCEDURE update_colleges_timestamp();

COMMENT ON TABLE figure_statuses IS
    'Why a figure has or lacks a value (RFC 158, D3): an absence always has a '
    'reason. Authored vocabulary (db/data/money-vocabulary.json). The two '
    'value-bearing rows are restated literally inside the fact tables'' '
    '*_value_iff_status_check CHECKs (a CHECK cannot subquery this table); a '
    'test pins the two lists together.';

COMMENT ON COLUMN figure_statuses.value_bearing IS
    'TRUE exactly when a figure with this status carries a value (reported, '
    'imputed_by_publisher). Pinned by test to the literal status lists inside '
    'price_figures_value_iff_status_check and '
    'cohort_money_stats_value_iff_status_check.';

CREATE TABLE price_concepts (
    slug               TEXT        NOT NULL PRIMARY KEY,
    description        TEXT        NOT NULL,
    arrangement_varies BOOLEAN     NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT price_concepts_slug_format_check
        CHECK (slug ~ '^[a-z0-9]+(_[a-z0-9]+)*$')
);
-- Rows: the seed file, proven against PriceConcept. arrangement_varies is
-- the RFC 149 rule as data.
-- No blend concept exists here: the FK below IS the no-blends rule (P6).
-- published_price is reserved for a source-published all-in price; a SUM of
-- components is derived and is never stored (CLAUDE.md schema conventions).

CREATE TRIGGER trigger_03_enforce_price_concepts_updated_at
BEFORE UPDATE ON price_concepts
FOR EACH ROW
EXECUTE PROCEDURE update_colleges_timestamp();

COMMENT ON TABLE price_concepts IS
    'What a published price is FOR (RFC 158, D2). Authored vocabulary '
    '(db/data/money-vocabulary.json). Deliberately carries NO blend concept: '
    'price_figures'' FK onto this table is the "no blend may enter the price '
    'table" rule itself (P6) -- COSTT4_A and the NPT4 family cannot be named '
    'here and land in cohort_money_stats instead.';

COMMENT ON COLUMN price_concepts.arrangement_varies IS
    'The RFC 149 rule as data (P3): TRUE when the concept''s figure differs by '
    'where the student lives (housing_and_food, other_expenses, '
    'published_price), FALSE when it does not (tuition_and_fees, fees_only, '
    'books_and_supplies). The loader fatals on a row whose arrangement '
    'disagrees with this flag.';

CREATE TABLE income_bands (
    slug          TEXT        NOT NULL PRIMARY KEY,
    min_usd       INTEGER     NOT NULL,
    max_usd       INTEGER     NULL,              -- NULL = open-ended top band
    bracket_label TEXT        NOT NULL,          -- e.g. "$30,001 to $48,000"
    sort_order    SMALLINT    NOT NULL UNIQUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT income_bands_min_nonneg_check CHECK (min_usd >= 0),
    CONSTRAINT income_bands_range_check
        CHECK (max_usd IS NULL OR max_usd > min_usd),
    CONSTRAINT income_bands_slug_format_check
        CHECK (slug ~ '^[a-z0-9]+(_[a-z0-9]+)*$')
);
-- Rows: the seed file, proven against IncomeBand. D7's five cut-points as
-- data, with slugs identical to money_profiles.income_band's CHECK list and
-- IncomeBand.kt's values -- set equality both ways, pinned by test (P10).

CREATE TRIGGER trigger_03_enforce_income_bands_updated_at
BEFORE UPDATE ON income_bands
FOR EACH ROW
EXECUTE PROCEDURE update_colleges_timestamp();

COMMENT ON TABLE income_bands IS
    'The five household income bands as DATA (RFC 158, D7): dollar cut-points '
    'and the spoken bracket label, authored in db/data/money-vocabulary.json. '
    'Slugs and labels are pinned by test to IncomeBand.kt and to '
    'money_profiles_income_band_check (P10); the Kotlin bracket text is '
    'deleted at shape/04, not here.';

COMMENT ON COLUMN income_bands.max_usd IS
    'Exclusive upper dollar bound of the band; NULL for the open-ended top '
    'band (over_110k). Derived shares and labels are computed at read time, '
    'never stored.';

-- ---------------------------------------------------------------------------
-- The price facts table (D2): one published figure per row.
-- ---------------------------------------------------------------------------

CREATE TABLE price_figures (
    id              UUID        NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    college_id      UUID        NOT NULL REFERENCES colleges(id),
    price_concept   TEXT        NOT NULL REFERENCES price_concepts(slug),
    residency_basis TEXT        NOT NULL REFERENCES residency_bases(slug),
    arrangement     TEXT        NOT NULL REFERENCES arrangements(slug),
    academic_year   TEXT        NOT NULL,
    amount_usd      INTEGER     NULL,
    status          TEXT        NOT NULL REFERENCES figure_statuses(slug),
    source          TEXT        NOT NULL,
    source_variable TEXT        NOT NULL,   -- e.g. 'TUITIONFEE_IN'
    publisher_flag  TEXT        NULL,       -- raw IPEDS X-code, when one exists
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT price_figures_natural_key
        UNIQUE (college_id, price_concept, residency_basis, arrangement,
                academic_year),
    CONSTRAINT price_figures_academic_year_format_check
        CHECK (academic_year ~ '^[0-9]{4}-[0-9]{2}$'),
    -- D3: a value exists exactly when the status bears one. The two
    -- value-bearing statuses are named literally (a CHECK cannot subquery
    -- figure_statuses.value_bearing); a test pins the two lists together.
    CONSTRAINT price_figures_value_iff_status_check
        CHECK ((amount_usd IS NOT NULL) =
               (status IN ('reported', 'imputed_by_publisher'))),
    -- Published gross prices are nonnegative (0062's nonneg family). Net
    -- prices can go negative but net prices are cohort statistics and cannot
    -- enter this table (P6).
    CONSTRAINT price_figures_amount_nonneg_check
        CHECK (amount_usd IS NULL OR amount_usd >= 0),
    CONSTRAINT price_figures_source_nonempty_check CHECK (source <> ''),
    CONSTRAINT price_figures_source_variable_nonempty_check
        CHECK (source_variable <> '')
);

CREATE INDEX price_figures_college_idx ON price_figures (college_id);

CREATE TRIGGER trigger_03_enforce_price_figures_updated_at
BEFORE UPDATE ON price_figures
FOR EACH ROW
EXECUTE PROCEDURE update_colleges_timestamp();

COMMENT ON TABLE price_figures IS
    'One published price figure per row (RFC 158, D2): a college, a concept, '
    'a residency, an arrangement and an academic year -- with a value exactly '
    'when the status bears one (D3). Rebuilt wholesale by the canonical-money '
    'ingest phase (P12); its history is college_index_build. A cell no '
    'ingested source carries gets NO row (P7).';

COMMENT ON COLUMN price_figures.academic_year IS
    'Always a real ''YYYY-YY'' academic year (P5): the year is a property of '
    'the source snapshot and rides on the row, never in a schema comment.';

COMMENT ON COLUMN price_figures.publisher_flag IS
    'The raw publisher flag beside the figure (an IPEDS X-code), when the '
    'source publishes one; stored raw per the source-defined-codes rule. NULL '
    'for the Scorecard fill, which publishes none.';

-- ---------------------------------------------------------------------------
-- The cohort statistics table (D2): a number about a population, never a
-- price anyone is quoted.
-- ---------------------------------------------------------------------------

CREATE TABLE cohort_money_stats (
    id              UUID        NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    college_id      UUID        NOT NULL REFERENCES colleges(id),
    measure         TEXT        NOT NULL,
    population      TEXT        NOT NULL,
    residency_scope TEXT        NOT NULL,
    aid_scope       TEXT        NOT NULL,
    income_band     TEXT        NULL REFERENCES income_bands(slug),
    vintage         TEXT        NOT NULL,
    value           NUMERIC     NULL,
    status          TEXT        NOT NULL REFERENCES figure_statuses(slug),
    source          TEXT        NOT NULL,
    source_variable TEXT        NOT NULL,
    publisher_flag  TEXT        NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT cohort_money_stats_measure_check
        CHECK (measure IN ('avg_net_price', 'published_cost_blend',
                           'pell_share', 'median_debt_at_completion',
                           'median_earnings_10y')),
    CONSTRAINT cohort_money_stats_population_check
        CHECK (population IN ('title_iv_aided_undergraduates',
                              'undergraduates',
                              'federal_loan_borrowing_completers',
                              'employed_not_enrolled_10y_after_entry')),
    CONSTRAINT cohort_money_stats_residency_scope_check
        CHECK (residency_scope IN ('in_state_rate_paying', 'all')),
    CONSTRAINT cohort_money_stats_aid_scope_check
        CHECK (aid_scope IN ('federal_aid_receiving',
                             'federal_loan_borrowing', 'all')),
    CONSTRAINT cohort_money_stats_vintage_format_check
        CHECK (vintage ~ '^[0-9]{4}-[0-9]{2}$' OR vintage = 'undated'),
    CONSTRAINT cohort_money_stats_value_iff_status_check
        CHECK ((value IS NOT NULL) =
               (status IN ('reported', 'imputed_by_publisher'))),
    CONSTRAINT cohort_money_stats_source_nonempty_check CHECK (source <> ''),
    CONSTRAINT cohort_money_stats_source_variable_nonempty_check
        CHECK (source_variable <> '')
);
-- Natural key: income_band NULL means "the overall figure", and two overall
-- rows for the same cell must collide -- hence NULLS NOT DISTINCT.
CREATE UNIQUE INDEX cohort_money_stats_natural_key
    ON cohort_money_stats (college_id, measure, population, residency_scope,
                           aid_scope, income_band, vintage)
    NULLS NOT DISTINCT;

CREATE INDEX cohort_money_stats_college_idx ON cohort_money_stats (college_id);

CREATE TRIGGER trigger_03_enforce_cohort_money_stats_updated_at
BEFORE UPDATE ON cohort_money_stats
FOR EACH ROW
EXECUTE PROCEDURE update_colleges_timestamp();

COMMENT ON TABLE cohort_money_stats IS
    'One cohort money statistic per row (RFC 158, D2): a number about a '
    'POPULATION -- blends, net prices, shares, medians -- never a price '
    'anyone is quoted. The population basis is three structured columns '
    '(population, residency_scope, aid_scope; measure attributes, house enum '
    'pattern) plus an optional income_band. Rebuilt wholesale by the '
    'canonical-money ingest phase (P12).';

COMMENT ON COLUMN cohort_money_stats.income_band IS
    'NULL means "the overall figure" -- not unanswered, not unknown. The '
    'natural-key index is NULLS NOT DISTINCT so two overall rows for the same '
    'cell collide (P4); a sentinel ''overall'' row would pollute D7''s five '
    'owned bands.';

COMMENT ON COLUMN cohort_money_stats.vintage IS
    'The academic year the source dates the cohort with (''YYYY-YY''), or the '
    'literal ''undated'' where the source pools or does not date it (P5): '
    'fabricating a year to satisfy a key would be the lie D15 exists to '
    'avoid.';

-- ---------------------------------------------------------------------------
-- Modeled ahead (D9): empty until shape/07 fills it from the CDS corpus.
-- ---------------------------------------------------------------------------

CREATE TABLE aid_policy_facts (
    id            UUID        NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    college_id    UUID        NOT NULL REFERENCES colleges(id),
    policy        TEXT        NOT NULL,
    academic_year TEXT        NOT NULL,
    value_boolean BOOLEAN     NULL,
    value_number  NUMERIC     NULL,
    status        TEXT        NOT NULL REFERENCES figure_statuses(slug),
    source        TEXT        NOT NULL,
    source_url    TEXT        NULL,
    archive_url   TEXT        NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT aid_policy_facts_natural_key
        UNIQUE (college_id, policy, academic_year),
    CONSTRAINT aid_policy_facts_policy_check
        CHECK (policy IN ('css_profile_required', 'noncustodial_css_required',
                          'fafsa_required', 'meets_full_need',
                          'need_fully_met_share', 'avg_need_based_grant_usd')),
    CONSTRAINT aid_policy_facts_academic_year_format_check
        CHECK (academic_year ~ '^[0-9]{4}-[0-9]{2}$'),
    CONSTRAINT aid_policy_facts_one_value_shape_check
        CHECK (NOT (value_boolean IS NOT NULL AND value_number IS NOT NULL)),
    CONSTRAINT aid_policy_facts_value_iff_status_check
        CHECK (((value_boolean IS NOT NULL) OR (value_number IS NOT NULL)) =
               (status IN ('reported', 'imputed_by_publisher')))
);

CREATE TRIGGER trigger_03_enforce_aid_policy_facts_updated_at
BEFORE UPDATE ON aid_policy_facts
FOR EACH ROW
EXECUTE PROCEDURE update_colleges_timestamp();

COMMENT ON TABLE aid_policy_facts IS
    'Aid-policy facts modeled ahead (RFC 158, D9): created empty, untouched by '
    'the canonical-money phase, filled by shape/07 from the CDS corpus. One '
    'value shape per row (boolean or number, never both), value iff a '
    'value-bearing status -- the D3 discipline applied to policies.';

-- ---------------------------------------------------------------------------
-- Provenance (P11): the 0064 one-column-one-meaning precedent.
-- ---------------------------------------------------------------------------

ALTER TABLE college_index_build
    ADD COLUMN price_figure_rows INTEGER NULL,
    ADD COLUMN cohort_money_stat_rows INTEGER NULL,
    ADD COLUMN canonical_money_summary JSONB NULL,
    ADD CONSTRAINT college_index_build_price_figure_rows_nonneg_check
        CHECK (price_figure_rows IS NULL OR price_figure_rows >= 0),
    ADD CONSTRAINT college_index_build_cohort_money_stat_rows_nonneg_chk
        CHECK (cohort_money_stat_rows IS NULL OR cohort_money_stat_rows >= 0);

COMMENT ON COLUMN college_index_build.price_figure_rows IS
    'Rows written by the price_figures rebuild (RFC 158). NULL for every '
    'build row written before that table existed.';
COMMENT ON COLUMN college_index_build.cohort_money_stat_rows IS
    'Rows written by the cohort_money_stats rebuild (RFC 158). NULL for every '
    'build row written before that table existed.';
COMMENT ON COLUMN college_index_build.canonical_money_summary IS
    'Per-status row counts per canonical table (RFC 158, P11), keyed by OUR '
    'vocabulary slugs -- the operator-visible fact that suppression survived '
    'the fill. NULL for every build row written before the canonical-money '
    'phase existed.';
