-- Need and forms (RFC 170, brief 0006 slice shape/07a): the `academic_year`
-- domain the money layer's stored years move onto, the `source_documents`
-- table every CDS-backed fact cites, the authored `aid_forms` vocabulary and
-- the `aid_form_requirements` relation, and the drop of `aid_policy_facts`.
--
-- One migration, because it is one change: the aid-form rows key on an
-- academic year and cite a source document, and a half state (the relation
-- without the domain, or the CDS tables pointing at nothing) is a state no
-- ingest run can use.
--
-- WHICH year a row carries is DATA here, never a comment: the 0062 rule that a
-- landed migration must not restate a fact the snapshot moves.

-- ---------------------------------------------------------------------------
-- Part 1 -- the `academic_year` domain (D14).
-- ---------------------------------------------------------------------------
--
-- 2025 IS the 2025-26 academic year. '2025-26' is a LABEL, rendered at read
-- time by AcademicYear.kt, never stored -- the schema conventions' derived-
-- figures rule applied to a string: the second half of the label is a function
-- of the first, so storing it stores the same fact twice.
--
-- The 'YYYY-YY' format CHECKs these columns carried could not state the rule
-- that actually matters. `'2025-30'` matches `^[0-9]{4}-[0-9]{2}$` and is not
-- an academic year; a regex cannot say "the second half is the first plus
-- one". A SMALLINT start year cannot spell that mistake at all, and the range
-- rule is then written ONCE, here, instead of once per column.
--
-- The tree's own `cds_source_year` (0054) and `college_sfa.aid_year_start`
-- (0085) already do this. These four money columns were the outlier.
CREATE DOMAIN academic_year AS SMALLINT
    CONSTRAINT academic_year_check CHECK (VALUE BETWEEN 2000 AND 2100);

COMMENT ON DOMAIN academic_year IS
    'An academic year, named by its FIRST calendar year: 2025 is the 2025-26 '
    'academic year (RFC 170, D14). The ''2025-26'' label is rendered at read '
    'time by AcademicYear.kt and never stored. The range is the domain''s one '
    'declaration, so a column does not restate it.';

-- Every one of these tables is rebuilt wholesale by the ingest, so the USING
-- clauses convert whatever a live database happens to hold rather than
-- migrating data anyone depends on. left(...) is the same reading
-- AcademicYear puts on the label: the start year IS the year.
ALTER TABLE price_figures
    DROP CONSTRAINT price_figures_academic_year_format_check,
    ALTER COLUMN academic_year TYPE academic_year
        USING LEFT(academic_year, 4)::SMALLINT;

COMMENT ON COLUMN price_figures.academic_year IS
    'The academic year this price is published for, as its start year (RFC '
    '170, D14): the year is a property of the source snapshot and rides on '
    'the row, never in a schema comment (P5).';

ALTER TABLE college_ipeds_charges
    DROP CONSTRAINT college_ipeds_charges_academic_year_format_check,
    ALTER COLUMN academic_year TYPE academic_year
        USING LEFT(academic_year, 4)::SMALLINT;

COMMENT ON COLUMN college_ipeds_charges.academic_year IS
    'The academic year the published charge describes, as its start year (RFC '
    '170, D14): IC2023_AY''s CHG2AY3 cell is stored as charge_variable '
    'CHG2AY, academic_year 2023.';

-- 'undated' becomes NULL: an ABSENT year, rather than a magic string every
-- reader must know is not a year. The natural key is already NULLS NOT
-- DISTINCT, so two undated rows for the same cell still collide (P4) -- which
-- is the only thing the sentinel was buying.
ALTER TABLE cohort_money_stats
    DROP CONSTRAINT cohort_money_stats_vintage_format_check,
    ALTER COLUMN vintage DROP NOT NULL,
    ALTER COLUMN vintage TYPE academic_year
        USING CASE WHEN vintage = 'undated' THEN NULL
                   ELSE LEFT(vintage, 4)::SMALLINT END;

COMMENT ON COLUMN cohort_money_stats.vintage IS
    'The academic year the source dates the cohort with, as its start year, '
    'or NULL where the source pools or does not date it (RFC 170 D14, P5): an '
    'absent year, not the literal ''undated'' it replaced. Fabricating a year '
    'to satisfy a key would be the lie D15 exists to avoid.';

ALTER TABLE cohort_population_counts
    DROP CONSTRAINT cohort_population_counts_vintage_format_check,
    ALTER COLUMN vintage TYPE academic_year
        USING LEFT(vintage, 4)::SMALLINT;

COMMENT ON COLUMN cohort_population_counts.vintage IS
    'The academic year the source dates the count with, as its start year '
    '(RFC 170, D14). NOT NULL, and deliberately without cohort_money_stats'' '
    'NULL escape: every headcount here comes from a cell whose aid year the '
    'staging loader has already resolved.';

-- college_sfa.aid_year is the same fact under a fourth spelling: the CELL's
-- own aid year, stored as a 'YYYY-YY' label the staging loader had to BUILD
-- (a third copy of the label rule) and the canonical fill would then have to
-- PARSE back into a year to write a vintage. The column joins the domain so
-- that neither half exists. `aid_year_start` is already a start year and is
-- left alone: it is the FILE's year, an argv fact, not a cell's.
ALTER TABLE college_sfa
    DROP CONSTRAINT college_sfa_aid_year_format_check,
    ALTER COLUMN aid_year TYPE academic_year
        USING LEFT(aid_year, 4)::SMALLINT;

COMMENT ON COLUMN college_sfa.aid_year IS
    'The aid year the CELL describes, as its START year (RFC 170, D14). SFA '
    'year suffixes are RELATIVE to the file: in SFAyyzz, 2 is the file''s own '
    'aid year, 1 one earlier, 0 two earlier. Resolving that at load is what '
    'stops a reader hardcoding "suffix 2 means 2022-23" and being silently '
    'wrong next file.';

-- ---------------------------------------------------------------------------
-- Part 2 -- the `money_source` domain (D8), and source_documents (D13).
-- ---------------------------------------------------------------------------
--
-- The publisher axis, as a DOMAIN for the reason `academic_year` is one: the
-- rule is declared ONCE and a column does not restate it. RFC 161 decision 6
-- made `source` an owned enumeration and pasted the CHECK into each table that
-- carried the column; this slice would have made that five copies and a
-- five-name pinning test. A fifth publisher is now one ALTER DOMAIN.
CREATE DOMAIN money_source AS TEXT
    CONSTRAINT money_source_check
        CHECK (VALUE IN ('ipeds_sfa', 'ipeds_ic_ay', 'scorecard',
                         'common_data_set'));

COMMENT ON DOMAIN money_source IS
    'The publisher of a stored money fact or document (RFC 170, D8), mirrored '
    'by the Kotlin MoneySource enum and pinned to it by test. A DOMAIN, not a '
    'per-table CHECK: the list is one list, wherever a source column appears.';
--
-- A url is a property of a DOCUMENT, not of a fact. One CDS filing backs every
-- fact read out of it, and the three landed CDS tables each stored the same
-- (source_url, archive_url) pair for the same filing -- so the copies could
-- disagree, and a corrected url had to be written in three places. Adding an
-- eleven-fact-per-school aid table would have created a fourth. The document
-- becomes an entity instead, and every fact table points at it.
--
-- The source axis is the SAME MoneySource enumeration the canonical fact
-- tables carry (RFC 161 decision 6, widened by D8 below), because a document
-- names its publisher exactly as a figure does.
CREATE TABLE source_documents (
    id            UUID          NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    college_id    UUID          NOT NULL REFERENCES colleges(id),
    source        money_source  NOT NULL,
    academic_year academic_year NOT NULL,
    source_url    TEXT          NOT NULL,   -- the school's own publication
    archive_url   TEXT          NULL,       -- our mirror, when one exists
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    -- One document per school per publisher per year: the CDS filing a school
    -- publishes for 2024-25 is ONE document, whatever number of facts are read
    -- out of it.
    CONSTRAINT source_documents_natural_key
        UNIQUE (college_id, source, academic_year),
    -- NOT NULL is not enough: an empty string is a url-shaped absence, and a
    -- citation rendered from one is a link to nowhere.
    CONSTRAINT source_documents_source_url_nonempty_check
        CHECK (source_url <> '')
);

CREATE TRIGGER trigger_03_enforce_source_documents_updated_at
BEFORE UPDATE ON source_documents
FOR EACH ROW
EXECUTE PROCEDURE update_colleges_timestamp();

COMMENT ON TABLE source_documents IS
    'One published document per row (RFC 170, D13): the school''s own '
    'publication for one publisher and one academic year, plus our mirror '
    'when one exists. Every fact table cites a document rather than carrying '
    'its own copy of the two urls -- duplicated data is a defect, and two '
    'copies of a url can disagree.';

COMMENT ON COLUMN source_documents.archive_url IS
    'Our mirror of the document, when one exists; NULL when it does not. '
    'Never an empty string standing in for absence.';

-- The three CDS tables cut over. All three are rebuilt wholesale from the
-- committed seed by the `cds` ingest phase, so this is a loader change rather
-- than a data migration: the columns go, and the document reference arrives
-- NOT NULL because a CDS fact with no document behind it is exactly the
-- uncitable row this table exists to prevent.
DELETE FROM college_merit_aid;
DELETE FROM college_admission_factors;
DELETE FROM college_deadlines;

ALTER TABLE college_merit_aid
    DROP COLUMN source_url,
    DROP COLUMN archive_url,
    ADD COLUMN source_document_id UUID NOT NULL REFERENCES source_documents(id);

ALTER TABLE college_admission_factors
    DROP COLUMN source_url,
    DROP COLUMN archive_url,
    ADD COLUMN source_document_id UUID NOT NULL REFERENCES source_documents(id);

ALTER TABLE college_deadlines
    DROP COLUMN source_url,
    DROP COLUMN archive_url,
    ADD COLUMN source_document_id UUID NOT NULL REFERENCES source_documents(id);

-- ---------------------------------------------------------------------------
-- aid_forms (D4): the authored vocabulary of forms, as a family names them.
-- ---------------------------------------------------------------------------
--
-- The RFC 158 D6 vocabulary shape: unicoach-authored rows in
-- db/data/money-vocabulary.json, loaded and fatally pinned to the Kotlin
-- AidForm enum both ways. The CDS numbers these cells H.801-H.807; those ids
-- live in aid_form_requirements.source_variable, where source-defined codes
-- belong, and never in a slug.
CREATE TABLE aid_forms (
    slug        TEXT        NOT NULL PRIMARY KEY,
    description TEXT        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT aid_forms_slug_format_check
        CHECK (slug ~ '^[a-z0-9]+(_[a-z0-9]+)*$')
);

CREATE TRIGGER trigger_03_enforce_aid_forms_updated_at
BEFORE UPDATE ON aid_forms
FOR EACH ROW
EXECUTE PROCEDURE update_colleges_timestamp();

COMMENT ON TABLE aid_forms IS
    'The financial-aid forms a school can require (RFC 170, D4), named as a '
    'family knows them rather than as the CDS numbers them. Authored '
    'vocabulary (db/data/money-vocabulary.json), pinned to the Kotlin AidForm '
    'enum by MoneyVocabularyLoader, fatally, both ways.';

-- ---------------------------------------------------------------------------
-- aid_form_requirements (D4/D5): college x form x applicant group x year.
-- ---------------------------------------------------------------------------
--
-- The applicant group is a domain axis, not a source shape: a form required of
-- domestic first-years and a form required of international applicants are
-- DIFFERENT requirements, and a family in the wrong group must not be told to
-- file.
--
-- is_required is nullable and undefaulted for one reason: a row with status
-- not_collected_by_us (D7) states OUR gap and carries no value. It is never
-- FALSE today -- the corpus's forms block carries ticked boxes and no unticked
-- ones at all, so "not required" is a state no source asserts (D5). That is
-- enforced by the loader (which refuses a FALSE in the seed) and pinned by
-- test, rather than by a CHECK, so a future source that genuinely publishes a
-- negative can write one without a migration.
CREATE TABLE aid_form_requirements (
    id                 UUID          NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    college_id         UUID          NOT NULL REFERENCES colleges(id),
    aid_form           TEXT          NOT NULL REFERENCES aid_forms(slug),
    applicant_group    TEXT          NOT NULL,
    academic_year      academic_year NOT NULL,
    is_required        BOOLEAN       NULL,
    status             TEXT          NOT NULL REFERENCES figure_statuses(slug),
    source             money_source  NOT NULL,
    source_variable    TEXT          NOT NULL,   -- the CDS field id, raw
    source_document_id UUID          NOT NULL REFERENCES source_documents(id),
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    CONSTRAINT aid_form_requirements_natural_key
        UNIQUE (college_id, aid_form, applicant_group, academic_year),
    CONSTRAINT aid_form_requirements_applicant_group_check
        CHECK (applicant_group IN ('domestic_first_year_aid_applicants',
                                   'nonresident_first_year_aid_applicants')),
    -- D3 again, and for the same reason: a value exists exactly when the
    -- status bears one. The two value-bearing statuses are named literally (a
    -- CHECK cannot subquery figure_statuses.value_bearing).
    CONSTRAINT aid_form_requirements_value_iff_status_check
        CHECK ((is_required IS NOT NULL) =
               (status IN ('reported', 'imputed_by_publisher'))),
    CONSTRAINT aid_form_requirements_source_variable_nonempty_check
        CHECK (source_variable <> '')
);

-- No separate college_id index: the natural key above already LEADS with
-- college_id, so a by-college probe walks it (the 0085 rule). A second B-tree
-- would be an index write per rebuilt row for zero reads.

CREATE TRIGGER trigger_03_enforce_aid_form_requirements_updated_at
BEFORE UPDATE ON aid_form_requirements
FOR EACH ROW
EXECUTE PROCEDURE update_colleges_timestamp();

COMMENT ON TABLE aid_form_requirements IS
    'One aid-form requirement per row (RFC 170, D4): this college requires '
    'this form of this applicant group in this academic year. Rebuilt '
    'wholesale by the `cds` ingest phase. A form no source lists for a school '
    'gets NO row, and reads as "not listed in this school''s CDS" -- never as '
    '"not required" (D5).';

COMMENT ON COLUMN aid_form_requirements.is_required IS
    'TRUE where the source says the form is required. NULL exactly when the '
    'status bears no value -- not_collected_by_us for a cell the corpus '
    'failed to extract (D7). FALSE is writable but never written: no source '
    'publishes a negative (D5).';

-- ---------------------------------------------------------------------------
-- aid_policy_facts is dropped, not extended (D2).
-- ---------------------------------------------------------------------------
--
-- RFC 158 D9 modeled it ahead as one key/value table with a `policy` slug and
-- a boolean-or-number column -- an honest guess made before anyone had seen
-- the data. The data says it is two different shapes: cohort STATISTICS, which
-- the cohort tables already model, and application REQUIREMENTS, which are a
-- relation. It is empty, with zero readers and zero writers, so it goes rather
-- than growing a longer list of source cells one slice before shape/08 deletes
-- exactly that shape from `colleges`.
DROP TABLE aid_policy_facts;

-- ---------------------------------------------------------------------------
-- The cohort vocabularies the CDS need figures land in (D3).
-- ---------------------------------------------------------------------------
--
-- DROP + re-ADD, never "ALTER ... ADD another CHECK": the enum mirrors are
-- pinned to these lists by set equality BOTH ways, so the list must stay one
-- list (the 0085 rule).
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
                           'student_loan_share', 'student_loan_average_amount',
                           'avg_need_based_grant', 'avg_need_met_share')),
    -- avg_need_met_share joins the share family: CDS publishes "87.5% of need
    -- met" and the stored value is the 0-1 share, exactly as every IPEDS `_P`
    -- percent already is. One rule for shares, not one per publisher.
    DROP CONSTRAINT cohort_money_stats_share_range_check,
    ADD CONSTRAINT cohort_money_stats_share_range_check
        CHECK (value IS NULL
               OR measure NOT IN ('pell_share', 'federal_grant_share',
                                  'state_local_grant_share',
                                  'institutional_grant_share',
                                  'student_loan_share', 'avg_need_met_share')
               OR value BETWEEN 0 AND 1),
    DROP CONSTRAINT cohort_money_stats_population_check,
    ADD CONSTRAINT cohort_money_stats_population_check
        CHECK (population IN ('title_iv_aided_undergraduates',
                              'undergraduates',
                              'federal_loan_borrowing_completers',
                              'employed_not_enrolled_10y_after_entry',
                              'first_time_full_time_aid_cohort',
                              'pell_receiving_undergraduates',
                              'first_time_full_time_freshmen_awarded_any_aid',
                              'first_time_full_time_freshmen_awarded_need_based_grant',
                              'first_time_full_time_freshmen_need_fully_met')),
    -- The aid scope follows the DENOMINATOR (the 0085 rule): both CDS H2
    -- averages are reported over line e -- the freshmen awarded need-based
    -- scholarship or grant aid -- which is neither 'all' nor any federal scope
    -- already listed. The population column names that same line; the two
    -- columns agree because they describe one cohort, not two.
    DROP CONSTRAINT cohort_money_stats_aid_scope_check,
    ADD CONSTRAINT cohort_money_stats_aid_scope_check
        CHECK (aid_scope IN ('federal_aid_receiving',
                             'federal_loan_borrowing', 'all',
                             'grant_aided', 'pell_receiving',
                             'federal_grant_receiving',
                             'state_local_grant_receiving',
                             'institutional_grant_receiving',
                             'loan_receiving',
                             'need_based_aid_receiving'));

ALTER TABLE cohort_population_counts
    DROP CONSTRAINT cohort_population_counts_population_check,
    ADD CONSTRAINT cohort_population_counts_population_check
        CHECK (population IN ('title_iv_aided_undergraduates',
                              'undergraduates',
                              'federal_loan_borrowing_completers',
                              'employed_not_enrolled_10y_after_entry',
                              'first_time_full_time_aid_cohort',
                              'pell_receiving_undergraduates',
                              'first_time_full_time_freshmen_awarded_any_aid',
                              'first_time_full_time_freshmen_awarded_need_based_grant',
                              'first_time_full_time_freshmen_need_fully_met'));

-- ---------------------------------------------------------------------------
-- The three landed fact tables move onto the `money_source` domain (D8).
-- ---------------------------------------------------------------------------
--
-- One more publisher on the same source axis every canonical row already
-- carries -- and the last time adding one edits three tables. Each column
-- carries the rule by TYPE from here; a per-table CHECK stating the same list
-- is what this slice's own thesis refuses. The CDS field id lives in
-- `source_variable`, never in a table name, a column name or a vocabulary slug.
ALTER TABLE price_figures
    DROP CONSTRAINT price_figures_source_domain_check,
    ALTER COLUMN source TYPE money_source;

ALTER TABLE cohort_money_stats
    DROP CONSTRAINT cohort_money_stats_source_domain_check,
    ALTER COLUMN source TYPE money_source;

ALTER TABLE cohort_population_counts
    DROP CONSTRAINT cohort_population_counts_source_domain_check,
    ALTER COLUMN source TYPE money_source;

-- ---------------------------------------------------------------------------
-- The document a canonical CDS fact came from (D13), as a REFERENCE.
-- ---------------------------------------------------------------------------
--
-- The three CDS tables and aid_form_requirements carry this column already.
-- The two cohort tables did not, so the read had to re-derive the same edge
-- from (college_id, academic_year, source) -- the duplicated truth this table
-- was created to end, with a worse failure mode: an inner join on a triple
-- that misses DROPS the fact from the answer, where a reference cannot.
--
-- NULLABLE, and then made mandatory PER SOURCE: a publisher with no per-school
-- document may leave it null, and the Common Data Set may not. Without that
-- second half the column is optional for everyone, the citation join is inner,
-- and a CDS fact written without a document VANISHES from the read -- so the
-- school is told "we hold no filing" about a filing we hold. That invariant is
-- the schema's to keep, not the loader's to remember.
ALTER TABLE cohort_money_stats
    ADD COLUMN source_document_id UUID NULL REFERENCES source_documents(id),
    ADD CONSTRAINT cohort_money_stats_cds_cites_document_check
        CHECK (source <> 'common_data_set' OR source_document_id IS NOT NULL);

ALTER TABLE cohort_population_counts
    ADD COLUMN source_document_id UUID NULL REFERENCES source_documents(id),
    ADD CONSTRAINT cohort_population_counts_cds_cites_document_check
        CHECK (source <> 'common_data_set' OR source_document_id IS NOT NULL);

COMMENT ON COLUMN cohort_money_stats.source_document_id IS
    'The published document this figure was read out of (RFC 170, D13), or '
    'NULL for a publisher that has no per-school document (the Scorecard and '
    'IPEDS national releases). The urls live on that row, once.';

COMMENT ON COLUMN cohort_population_counts.source_document_id IS
    'The published document this count was read out of (RFC 170, D13), or '
    'NULL for a publisher that has no per-school document.';


-- ---------------------------------------------------------------------------
-- Provenance (P11): the 0064/0083/0085 one-column-one-meaning precedent.
-- ---------------------------------------------------------------------------

ALTER TABLE college_index_build
    ADD COLUMN aid_form_requirement_rows INTEGER NULL,
    ADD CONSTRAINT college_index_build_aid_form_requirement_rows_nonneg_chk
        CHECK (aid_form_requirement_rows IS NULL OR aid_form_requirement_rows >= 0);

COMMENT ON COLUMN college_index_build.aid_form_requirement_rows IS
    'Rows written by the aid_form_requirements rebuild (RFC 170). NULL for '
    'every build row written before that table existed.';
