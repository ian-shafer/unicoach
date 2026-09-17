-- program_money_stats -- what a PROGRAM returns, per (college, CIP field,
-- credential level) (RFC 218, slice beyond/03/what-this-major-returns, brief
-- 0011 D26/D27/D37).
--
-- The College Scorecard field-of-study file publishes earnings and student-debt
-- figures per program. This table holds three of them: the Treasury median
-- earnings four years after entry, the NSLDS median federal loan debt of
-- completers, and the publisher's own monthly payment on a standard ten-year
-- plan.
--
-- A SIBLING OF cohort_money_stats, NEVER A WIDENING OF IT (brief 0011 D26,
-- D37). cohort_money_stats is keyed institution-level -- (college_id, measure,
-- population, residency_scope, aid_scope, income_band, vintage) -- under a
-- unique index with NULLS NOT DISTINCT. A nullable cip_code bolted onto it
-- would silently widen every existing reader's WHERE: today "the median debt at
-- this college" is one row, and after such a change it is one row plus one per
-- field. Model each thing as what it is; never deform an existing model to
-- absorb something new.
--
-- What the sibling REUSES is the canonical store's furniture rather than its
-- key: the figure_statuses vocabulary, the money_source domain with
-- source_variable beside it, and the value-iff-status CHECK that makes a value
-- without a value-bearing status impossible to insert.
--
-- NO publisher_flag (Ian, 2026-09-16: "Remove publisher_flag because it's not
-- needed"). cohort_money_stats has one because ITS publisher files one -- the
-- raw IPEDS imputation code -- and this publisher files none beside these three
-- columns. A column with no writer is a question every future reader has to ask
-- and nobody can answer.
--
-- NO DEBT-TO-EARNINGS RATIO, HERE OR ANYWHERE (brief 0011 D27). The earnings
-- column and the debt columns are different cohorts, different publishers and
-- different dollar bases; a ratio over them is a figure nobody published. The
-- ten-year monthly payment is stored because the PUBLISHER computed it -- a
-- payment we derived from a debt figure would be a stored derived figure, which
-- CLAUDE.md forbids.
--
-- THREE SILENCES, KEPT APART (RFC 206). A numeric cell is `reported`; the
-- literal token `PS` is `suppressed_by_publisher`; the literal token `NA` is
-- `not_reported_by_institution`. The absence rows are stored on purpose -- "the
-- publisher withheld this" is a fact a family is owed, and RFC 206 exists
-- because we once served it as silence.
--
-- Reference-table shape (0060/0077/0087/create-external-documents precedent):
-- unversioned, no soft delete, no optimistic concurrency, created_at/updated_at
-- with the plain update_colleges_timestamp() trigger. It is a wholesale-rebuilt
-- projection of one snapshot file, deleted and re-inserted by each ingest.
CREATE TABLE program_money_stats (
    id                 UUID         NOT NULL PRIMARY KEY DEFAULT uuidv7(),

    -- The figure is a fact about a PROGRAM, so it points at the program row
    -- rather than restating its three-column grain. college_programs already
    -- holds (college_id, cip_code, credential_level) under a unique index, and
    -- a second copy here would be a second place for them to disagree.
    college_program_id UUID         NOT NULL REFERENCES college_programs(id) ON DELETE CASCADE,

    measure            TEXT         NOT NULL,

    -- The FIRST and LAST academic year the publisher's cohort pools, each as
    -- its START year: earnings pool AY2017-18 and AY2018-19, so 2017 and 2018.
    -- EQUAL when a figure pools nothing. The academic_year DOMAIN (0087) makes
    -- the range rule one rule and makes "2017-19" unspellable -- its own
    -- comment is the argument: "a regex cannot say 'the second half is the
    -- first plus one'."
    cohort_first_year academic_year NOT NULL,
    cohort_last_year  academic_year NOT NULL,

    -- Whole US dollars, nominal. INTEGER and unit-suffixed, on
    -- price_figures.amount_usd's precedent (0083:191) and the naming rule 0059
    -- states: every numeric column says its UNIT, and the unit comes last.
    amount_usd         INTEGER      NULL,
    status             TEXT         NOT NULL REFERENCES figure_statuses(slug),

    source             money_source NOT NULL,
    source_variable    TEXT         NOT NULL,

    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT program_money_stats_measure_check
        CHECK (measure IN (
            'median_earnings_4yr_after_entry',
            'median_debt_at_completion',
            'median_debt_10yr_monthly_payment'
        )),

    -- An amount exists exactly when the status is one that bears a value. The
    -- canonical store's own rule, copied without deviation: a suppressed or
    -- unreported figure with a number in it is not representable.
    CONSTRAINT program_money_stats_amount_iff_status_check
        CHECK ((amount_usd IS NOT NULL) = (status IN ('reported', 'imputed_by_publisher'))),

    -- Dollars, never negative. The publisher states none of these can be.
    CONSTRAINT program_money_stats_amount_nonnegative_check
        CHECK (amount_usd IS NULL OR amount_usd >= 0),

    -- A cohort cannot end before it starts.
    CONSTRAINT program_money_stats_cohort_years_ordered_check
        CHECK (cohort_last_year >= cohort_first_year),

    CONSTRAINT program_money_stats_source_variable_nonempty_check
        CHECK (source_variable <> '')
);

-- The natural key. One figure per program, per measure, per cohort: a snapshot
-- that re-dates a column adds rows beside the old ones rather than overwriting
-- a cohort nobody re-published.
CREATE UNIQUE INDEX program_money_stats_unique_idx
    ON program_money_stats (
        college_program_id, measure,
        cohort_first_year, cohort_last_year
    );

COMMENT ON TABLE program_money_stats IS
    'What a PROGRAM returns (RFC 218): the College Scorecard field-of-study '
    'file''s median earnings, median federal loan debt and the publisher''s own '
    'ten-year monthly payment, one row per (program, measure, cohort). A '
    'sibling of cohort_money_stats and never a widening of it -- that table is '
    'keyed institution-level, and a nullable cip_code on it would silently turn '
    'every existing reader''s one row into one row per field. Wholesale-rebuilt '
    'from the snapshot by each ingest. No debt-to-earnings ratio is ever '
    'computed over these rows: the earnings column and the debt columns are '
    'different cohorts, different publishers and different dollar bases.';

COMMENT ON COLUMN program_money_stats.college_program_id IS
    'The program the figure is about. college_programs already holds (college_id, '
    'cip_code, credential_level) under a unique index, so the grain is stated '
    'once. ON DELETE CASCADE because a figure about a program that no longer '
    'exists is not a figure.';

COMMENT ON COLUMN program_money_stats.measure IS
    'Which of the three published figures this row carries. TEXT + CHECK, '
    'mirrored by exactly one Kotlin enum (ProgramMoneyMeasure) with a fromValue '
    'companion, pinned to this list by set equality in both directions.';

COMMENT ON COLUMN program_money_stats.cohort_first_year IS
    'The FIRST academic year the publisher''s cohort pools, as its START year: '
    'the earnings cohort pools AY2017-18 and AY2018-19, so 2017. EQUAL to '
    'cohort_last_year when a figure pools nothing.';

COMMENT ON COLUMN program_money_stats.cohort_last_year IS
    'The LAST academic year the publisher''s cohort pools, as its START year: '
    'the earnings cohort pools AY2017-18 and AY2018-19, so 2018. Never earlier '
    'than cohort_first_year.';

COMMENT ON COLUMN program_money_stats.amount_usd IS
    'Whole US dollars, nominal. Cents are discarded at ingest, half up, in one '
    'place (ProgramMoneyLoader) -- only the ten-year monthly payment can arrive '
    'with a fraction, and it is never truncated silently. NULL exactly when the '
    'status does not bear a value. Unit-suffixed INTEGER on '
    'price_figures.amount_usd''s precedent, because all three measures are '
    'dollars; cohort_money_stats.value is unitless NUMERIC because it mixes '
    'shares with dollars, so do not "fix" this column to match it.';

COMMENT ON COLUMN program_money_stats.status IS
    'Why there is, or is not, a figure (RFC 206''s three arms): a numeric cell '
    'is reported, the publisher''s PS token is suppressed_by_publisher, and its '
    'NA token is not_reported_by_institution. An absence always has a reason, '
    'and it is stored rather than served as silence.';

COMMENT ON COLUMN program_money_stats.source_variable IS
    'The publisher''s own variable name -- never a column or table name of ours. '
    'It is the key into ScorecardFieldOfStudyColumns '
    '(college/src/main/kotlin/ed/unicoach/college/ScorecardFieldOfStudyColumns.kt), '
    'which holds, per variable, the publisher, the cohort years, the '
    'publisher''s cohort sentence verbatim, and the inflation base year where '
    'one is published. An earnings figure is in 2024 dollars and nothing on this '
    'row says so: that lookup is where a reader of the figure finds the dollar '
    'base before speaking it out loud.';

CREATE TRIGGER trigger_03_enforce_program_money_stats_updated_at
BEFORE UPDATE ON program_money_stats
FOR EACH ROW
EXECUTE PROCEDURE update_colleges_timestamp();

-- NO INDEX beyond the primary key and the unique natural key. The only read
-- this slice creates is the rebuild's own delete, and the reader beyond/05 will
-- query by program -- the leading column of the unique index. An index
-- describing a query nobody has written yet is worse than none.
