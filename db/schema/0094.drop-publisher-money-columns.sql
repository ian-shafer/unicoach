-- Drop the publisher shape (RFC 176, brief 0006 slice shape/08). The eighteen
-- publisher-shaped money columns leave `colleges` AND `colleges_versions`, and
-- the history writer is restated without them.
--
-- Reader count is zero. RFC 158 built the canonical store beside `colleges`,
-- 161/162 filled it, 166 moved every cost answer onto it, 169 put search's
-- published-price ruler on it, and this RFC's own first step re-pointed the
-- last nine live readers -- the search / similar-colleges payload and the
-- search index's net-price ruler -- at `cohort_money_stats`. Nothing selects
-- these columns any more; two tests in `:db` and `:college` say so by name.
--
-- WHERE THE FIGURES LIVE NOW. Every one of the eighteen has a canonical home,
-- filled by the same ingest run from the same pinned Scorecard CSV (plus the
-- IPEDS publishers, which are newer and win):
--
--   cost_of_attendance_per_year_usd            -> cohort_money_stats  published_cost_blend
--   net_price_per_year_usd                     -> cohort_money_stats  avg_net_price, income_band NULL
--   net_price_per_year_income_q1..q5_usd       -> cohort_money_stats  avg_net_price, income_band under_30k..over_110k
--   tuition_and_fees_in_state_per_year_usd     -> price_figures       tuition_and_fees x in_state
--   tuition_and_fees_out_of_state_per_year_usd -> price_figures       tuition_and_fees x out_of_state
--   median_earnings_10y_after_entry_usd        -> cohort_money_stats  median_earnings_10y
--   median_debt_at_completion_usd              -> cohort_money_stats  median_debt_at_completion
--   housing_and_food_on_campus_per_year_usd    -> price_figures       housing_and_food x on_campus
--   housing_and_food_off_campus_per_year_usd   -> price_figures       housing_and_food x off_campus
--   books_and_supplies_per_year_usd            -> price_figures       books_and_supplies
--   other_expenses_on_campus_per_year_usd      -> price_figures       other_expenses x on_campus
--   other_expenses_off_campus_per_year_usd     -> price_figures       other_expenses x off_campus
--   other_expenses_with_family_per_year_usd    -> price_figures       other_expenses x with_family
--   pell_share                                 -> cohort_money_stats  pell_share
--
-- HISTORY (RFC 176 D5, Ian's call at the /ship gate). Both sides go, not just
-- the live one. Measured over every version row in the dev database before
-- this migration was written: 6,338 rows over 6,273 colleges, max version 2,
-- and a v1-vs-v2 self-join over all sixteen price columns with IS DISTINCT
-- FROM returns ZERO rows -- not one second version changes any money value.
-- There is no money HISTORY in the history table, only a snapshot stored
-- twice, and the live half of it is superseded by the canonical layer. A
-- frozen money tail on `colleges_versions` would break the `colleges` /
-- `colleges_versions` mirror that every migration since 0023 has maintained,
-- and would leave eighteen permanently-NULL columns nobody could explain. The
-- 0017 precedent (`users` + `users_versions` + a restated `log_user_version()`)
-- is what this follows. A `bin/db-dump` of the pre-migration database was
-- taken and its path is named in the run's report, so the raw values exist
-- outside the schema at a known point; the cutoff itself is recorded in the
-- COMMENT ON TABLE at the foot of this file.
--
-- NOT TOUCHED, deliberately: `college_index_build.change_summary`. That log is
-- append-only (the rule 0059 wrote for itself when it RENAMED these keys), so
-- rows written before this migration keep their 28 `non_null` keys and stay
-- readable exactly because nothing here backfills, strips or CHECKs them. New
-- rows carry 10 keys at ingest method version 9, which is what tells the two
-- shapes apart.
--
-- ORDER MATTERS INSIDE THIS TRANSACTION. `log_college_version()` is restated
-- FIRST. A plpgsql body is stored as TEXT and is not dependency-tracked, so
-- DROP COLUMN neither fails nor rewrites it: a stale body passes this
-- migration and then kills the very next write to `colleges` with
-- `42703 record "new" has no field ...`, from inside the trigger, in the
-- middle of an ingest.

-- ---------------------------------------------------------------------------
-- Part 1 -- restate the history writer, money removed from both lists.
-- ---------------------------------------------------------------------------
--
-- The 0062 body verbatim minus the eighteen names on both sides of the VALUES:
-- 37 columns become 19. CREATE OR REPLACE, never DROP/CREATE -- the existing
-- `trigger_04_log_college_version` binds by name and must not be re-wired (the
-- 0023/0045/0051/0057/0059/0062 pattern).
CREATE OR REPLACE FUNCTION log_college_version()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO colleges_versions (
        id, version, ipeds_unit_id, opeid, name, city, state, region, locale,
        latitude, longitude, control, undergrad_enrollment_headcount,
        admission_rate_share, sat_average_equivalent_score,
        completion_rate_150pct_4yr_share, website,
        aliases, created_at, updated_at
    ) VALUES (
        NEW.id, NEW.version, NEW.ipeds_unit_id, NEW.opeid, NEW.name, NEW.city, NEW.state,
        NEW.region, NEW.locale, NEW.latitude, NEW.longitude, NEW.control,
        NEW.undergrad_enrollment_headcount, NEW.admission_rate_share,
        NEW.sat_average_equivalent_score, NEW.completion_rate_150pct_4yr_share,
        NEW.website, NEW.aliases, NEW.created_at, NEW.updated_at
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------------
-- Part 2 -- the index, dropped explicitly.
-- ---------------------------------------------------------------------------
--
-- `colleges_net_price_per_year_usd_idx` (born 0015 as `colleges_net_price_idx`,
-- renamed at 0059:114) would go with its column anyway; it is named here so the
-- drop of a btree nothing has filtered on since search moved to
-- `college_search_index` is stated rather than inferred.
DROP INDEX IF EXISTS colleges_net_price_per_year_usd_idx;

-- ---------------------------------------------------------------------------
-- Part 3 -- drop the eighteen from the live table.
-- ---------------------------------------------------------------------------
--
-- The twelve CHECK constraints on these columns -- eleven `_nonneg_check`s
-- (0015, 0045, 0062) plus `colleges_pell_share_range_check` (0015, renamed by
-- 0059) -- and the sixteen COMMENT ON COLUMN texts (0059, 0062, 0075) are
-- dropped WITH the columns by Postgres, the 0007:102 precedent; they are not
-- dropped by hand. There is no CHECK on `net_price_per_year_usd` (0022 dropped
-- it) and none on the five band columns (0045 never added one) -- a net price
-- is legitimately negative when aid exceeds cost. No foreign key, no view and
-- no other trigger names a money column.
ALTER TABLE colleges
    DROP COLUMN cost_of_attendance_per_year_usd,
    DROP COLUMN net_price_per_year_usd,
    DROP COLUMN net_price_per_year_income_q1_usd,
    DROP COLUMN net_price_per_year_income_q2_usd,
    DROP COLUMN net_price_per_year_income_q3_usd,
    DROP COLUMN net_price_per_year_income_q4_usd,
    DROP COLUMN net_price_per_year_income_q5_usd,
    DROP COLUMN tuition_and_fees_in_state_per_year_usd,
    DROP COLUMN tuition_and_fees_out_of_state_per_year_usd,
    DROP COLUMN median_earnings_10y_after_entry_usd,
    DROP COLUMN median_debt_at_completion_usd,
    DROP COLUMN housing_and_food_on_campus_per_year_usd,
    DROP COLUMN housing_and_food_off_campus_per_year_usd,
    DROP COLUMN books_and_supplies_per_year_usd,
    DROP COLUMN other_expenses_on_campus_per_year_usd,
    DROP COLUMN other_expenses_off_campus_per_year_usd,
    DROP COLUMN other_expenses_with_family_per_year_usd,
    DROP COLUMN pell_share;

-- ---------------------------------------------------------------------------
-- Part 4 -- drop the same eighteen from the history table (D5).
-- ---------------------------------------------------------------------------
--
-- The columns alone: the history table carries no CHECK and no index on any of
-- them by design (0062:59-60 -- "it records what was written, and a constraint
-- there would reject history the live table accepted").
ALTER TABLE colleges_versions
    DROP COLUMN cost_of_attendance_per_year_usd,
    DROP COLUMN net_price_per_year_usd,
    DROP COLUMN net_price_per_year_income_q1_usd,
    DROP COLUMN net_price_per_year_income_q2_usd,
    DROP COLUMN net_price_per_year_income_q3_usd,
    DROP COLUMN net_price_per_year_income_q4_usd,
    DROP COLUMN net_price_per_year_income_q5_usd,
    DROP COLUMN tuition_and_fees_in_state_per_year_usd,
    DROP COLUMN tuition_and_fees_out_of_state_per_year_usd,
    DROP COLUMN median_earnings_10y_after_entry_usd,
    DROP COLUMN median_debt_at_completion_usd,
    DROP COLUMN housing_and_food_on_campus_per_year_usd,
    DROP COLUMN housing_and_food_off_campus_per_year_usd,
    DROP COLUMN books_and_supplies_per_year_usd,
    DROP COLUMN other_expenses_on_campus_per_year_usd,
    DROP COLUMN other_expenses_off_campus_per_year_usd,
    DROP COLUMN other_expenses_with_family_per_year_usd,
    DROP COLUMN pell_share;

-- ---------------------------------------------------------------------------
-- Part 5 -- the cutoff record (D5).
-- ---------------------------------------------------------------------------
--
-- Written on the table because that is where someone asking "what did this
-- history hold before?" is standing.
COMMENT ON TABLE colleges_versions IS
    'Append-only version history of `colleges`, written by '
    '`log_college_version()` on every INSERT or UPDATE (RFC 82). It mirrors '
    '`colleges` column for column and carries no constraints of its own: it '
    'records what was written. '
    'MONEY CUTOFF: until migration 0094 (2026-09-05, RFC 176) this table also '
    'carried eighteen publisher-shaped money columns -- '
    'cost_of_attendance_per_year_usd, net_price_per_year_usd, '
    'net_price_per_year_income_q1_usd..q5_usd, '
    'tuition_and_fees_in_state_per_year_usd, '
    'tuition_and_fees_out_of_state_per_year_usd, '
    'median_earnings_10y_after_entry_usd, median_debt_at_completion_usd, '
    'housing_and_food_on_campus_per_year_usd, '
    'housing_and_food_off_campus_per_year_usd, books_and_supplies_per_year_usd, '
    'other_expenses_on_campus_per_year_usd, '
    'other_expenses_off_campus_per_year_usd, '
    'other_expenses_with_family_per_year_usd and pell_share. They held the '
    'College Scorecard figures as of the ingest that wrote that version, copied '
    'onto the row; measured before the drop, no second version changed any of '
    'them, so they were a snapshot stored twice rather than a history. The live '
    'figures are in `price_figures` and `cohort_money_stats`, each with its own '
    'academic year or vintage, publisher and status. Pre-drop raw values exist '
    'only in the pre-migration database dump named in the RFC 176 run report.';
