-- program_money_stats.amount_usd_year -- the calendar year of the dollars in
-- amount_usd (RFC 274).
--
-- The table is derived data, rebuilt wholesale by each ingest, and every row
-- that exists when this runs predates the column. It is emptied here and the
-- next ingest refills it, stamping the year from the fetched data dictionary.
DELETE FROM program_money_stats;

ALTER TABLE program_money_stats
    ADD COLUMN amount_usd_year SMALLINT NULL,
    ADD CONSTRAINT program_money_stats_amount_usd_year_earnings_check
        CHECK ((amount_usd_year IS NOT NULL) = (measure = 'median_earnings_4yr_after_entry'));

COMMENT ON COLUMN program_money_stats.amount_usd_year IS
    'The calendar year of the dollars in amount_usd, as the publisher states '
    'it. Empty where the publisher states none.';

COMMENT ON COLUMN program_money_stats.amount_usd IS
    'Whole US dollars, as the publisher states them; amount_usd_year names the '
    'calendar year of those dollars where the publisher states one. Cents are '
    'discarded at ingest, half up, in one place (ProgramMoneyLoader) -- only '
    'the ten-year monthly payment can arrive with a fraction, and it is never '
    'truncated silently. NULL exactly when the status does not bear a value. '
    'Unit-suffixed INTEGER on price_figures.amount_usd''s precedent, because '
    'all three measures are dollars; cohort_money_stats.value is unitless '
    'NUMERIC because it mixes shares with dollars, so do not "fix" this column '
    'to match it.';

COMMENT ON COLUMN program_money_stats.source_variable IS
    'The publisher''s own variable name -- never a column or table name of ours. '
    'It is the key into ScorecardFieldOfStudyColumns '
    '(college/src/main/kotlin/ed/unicoach/college/ScorecardFieldOfStudyColumns.kt), '
    'which holds, per variable, the measure, the publisher and the assurance '
    'tier.';
