-- The six published cost components on colleges. RFC 149.
--
-- `colleges` stored one blended sticker figure (cost_of_attendance_per_year_usd,
-- COSTT4_A) and no components at all, so the product could not say which part of
-- a price a family can actually influence. These six columns are the components
-- the pinned College Scorecard snapshot already carries, keyed by living
-- arrangement:
--   housing_and_food_on_campus_per_year_usd   -- ROOMBOARD_ON
--   housing_and_food_off_campus_per_year_usd  -- ROOMBOARD_OFF
--   books_and_supplies_per_year_usd           -- BOOKSUPPLY (one figure, all arrangements)
--   other_expenses_on_campus_per_year_usd     -- OTHEREXPENSE_ON
--   other_expenses_off_campus_per_year_usd    -- OTHEREXPENSE_OFF
--   other_expenses_with_family_per_year_usd   -- OTHEREXPENSE_FAM
--
-- Six, not seven: the Scorecard publishes no ROOMBOARD_FAM, which is why the
-- with-family arrangement renders no housing-and-food line rather than a $0 one.
--
-- The names spell the measure out (RFC 149 D-A): brief 0003 D18 fixed the
-- product vocabulary -- housing and food, never room and board -- and 0059 fixed
-- the column convention -- measure_qualifier_unit, dollars ending _per_year_usd.
-- Both are honoured by the long form; the Scorecard field name lives in a
-- COMMENT ON COLUMN below (the 0059 pattern), not in the column name. The
-- longest constraint identifier here is 62 characters, inside Postgres's
-- 63-character limit.
--
-- All six carry a nonneg CHECK: they are GROSS costs, so a negative is a loader
-- bug. This deliberately differs from 0045's net-price band columns, where the
-- Scorecard publishes legitimate negatives (aid exceeding cost, 0022).
--
-- No backfill: the next ingest loads them. Existing history rows show NULL --
-- correct, those ingests never saw the fields.

ALTER TABLE colleges
    ADD COLUMN housing_and_food_on_campus_per_year_usd  INTEGER NULL,
    ADD COLUMN housing_and_food_off_campus_per_year_usd INTEGER NULL,
    ADD COLUMN books_and_supplies_per_year_usd          INTEGER NULL,
    ADD COLUMN other_expenses_on_campus_per_year_usd    INTEGER NULL,
    ADD COLUMN other_expenses_off_campus_per_year_usd   INTEGER NULL,
    ADD COLUMN other_expenses_with_family_per_year_usd  INTEGER NULL,
    ADD CONSTRAINT colleges_housing_and_food_on_campus_per_year_usd_nonneg_check
        CHECK (housing_and_food_on_campus_per_year_usd IS NULL
               OR housing_and_food_on_campus_per_year_usd >= 0),
    ADD CONSTRAINT colleges_housing_and_food_off_campus_per_year_usd_nonneg_check
        CHECK (housing_and_food_off_campus_per_year_usd IS NULL
               OR housing_and_food_off_campus_per_year_usd >= 0),
    ADD CONSTRAINT colleges_books_and_supplies_per_year_usd_nonneg_check
        CHECK (books_and_supplies_per_year_usd IS NULL
               OR books_and_supplies_per_year_usd >= 0),
    ADD CONSTRAINT colleges_other_expenses_on_campus_per_year_usd_nonneg_check
        CHECK (other_expenses_on_campus_per_year_usd IS NULL
               OR other_expenses_on_campus_per_year_usd >= 0),
    ADD CONSTRAINT colleges_other_expenses_off_campus_per_year_usd_nonneg_check
        CHECK (other_expenses_off_campus_per_year_usd IS NULL
               OR other_expenses_off_campus_per_year_usd >= 0),
    ADD CONSTRAINT colleges_other_expenses_with_family_per_year_usd_nonneg_check
        CHECK (other_expenses_with_family_per_year_usd IS NULL
               OR other_expenses_with_family_per_year_usd >= 0);

-- No constraints on the history table (the 0045 pattern): it records what was
-- written, and a constraint there would reject history the live table accepted.
ALTER TABLE colleges_versions
    ADD COLUMN housing_and_food_on_campus_per_year_usd  INTEGER NULL,
    ADD COLUMN housing_and_food_off_campus_per_year_usd INTEGER NULL,
    ADD COLUMN books_and_supplies_per_year_usd          INTEGER NULL,
    ADD COLUMN other_expenses_on_campus_per_year_usd    INTEGER NULL,
    ADD COLUMN other_expenses_off_campus_per_year_usd   INTEGER NULL,
    ADD COLUMN other_expenses_with_family_per_year_usd  INTEGER NULL;

-- Redefine the history writer to carry the six new columns. A plpgsql body is
-- stored as TEXT, so it names its columns literally and must be restated in
-- full; this is the 0059 body verbatim plus the six. CREATE OR REPLACE is picked
-- up by the existing trigger_04_log_college_version by name with no re-wiring
-- (the 0023/0045/0051/0057/0059 pattern).
CREATE OR REPLACE FUNCTION log_college_version()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO colleges_versions (
        id, version, ipeds_unit_id, opeid, name, city, state, region, locale,
        latitude, longitude, control, undergrad_enrollment_headcount,
        admission_rate_share, sat_average_equivalent_score, cost_of_attendance_per_year_usd,
        net_price_per_year_usd, tuition_and_fees_in_state_per_year_usd,
        tuition_and_fees_out_of_state_per_year_usd, completion_rate_150pct_4yr_share,
        median_earnings_10y_after_entry_usd, pell_share, website,
        net_price_per_year_income_q1_usd, net_price_per_year_income_q2_usd,
        net_price_per_year_income_q3_usd, net_price_per_year_income_q4_usd,
        net_price_per_year_income_q5_usd,
        median_debt_at_completion_usd,
        housing_and_food_on_campus_per_year_usd, housing_and_food_off_campus_per_year_usd,
        books_and_supplies_per_year_usd, other_expenses_on_campus_per_year_usd,
        other_expenses_off_campus_per_year_usd, other_expenses_with_family_per_year_usd,
        aliases, created_at, updated_at
    ) VALUES (
        NEW.id, NEW.version, NEW.ipeds_unit_id, NEW.opeid, NEW.name, NEW.city, NEW.state,
        NEW.region, NEW.locale, NEW.latitude, NEW.longitude, NEW.control,
        NEW.undergrad_enrollment_headcount, NEW.admission_rate_share,
        NEW.sat_average_equivalent_score, NEW.cost_of_attendance_per_year_usd,
        NEW.net_price_per_year_usd, NEW.tuition_and_fees_in_state_per_year_usd,
        NEW.tuition_and_fees_out_of_state_per_year_usd, NEW.completion_rate_150pct_4yr_share,
        NEW.median_earnings_10y_after_entry_usd, NEW.pell_share, NEW.website,
        NEW.net_price_per_year_income_q1_usd, NEW.net_price_per_year_income_q2_usd,
        NEW.net_price_per_year_income_q3_usd, NEW.net_price_per_year_income_q4_usd,
        NEW.net_price_per_year_income_q5_usd, NEW.median_debt_at_completion_usd,
        NEW.housing_and_food_on_campus_per_year_usd, NEW.housing_and_food_off_campus_per_year_usd,
        NEW.books_and_supplies_per_year_usd, NEW.other_expenses_on_campus_per_year_usd,
        NEW.other_expenses_off_campus_per_year_usd, NEW.other_expenses_with_family_per_year_usd,
        NEW.aliases, NEW.created_at, NEW.updated_at
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------------
-- What each component actually counts
-- ---------------------------------------------------------------------------
-- The 0059 convention: the federal field name and the definition live here, not
-- in the column name. Every one of the six is the school's own PUBLISHED
-- ALLOWANCE for a full-time undergraduate for ONE academic year -- an estimate
-- the school publishes for financial-aid budgeting, not a bill and not a
-- measured expenditure. NULL is "not reported", never zero.
--
-- WHICH academic year is deliberately not stated in these comments. It is a
-- property of the pinned Scorecard snapshot, and the snapshot moves; a landed
-- migration never does. The one home for the year is
-- ScorecardVintage.PUBLISHED_PRICE in the service cost domain (RFC 149 D-E),
-- which is what the payload quotes and what the next snapshot bump edits. A year
-- restated here would be permanently wrong the day after that bump, and nothing
-- would fail for it.

COMMENT ON COLUMN colleges.housing_and_food_on_campus_per_year_usd IS
    'ROOMBOARD_ON: the school''s published allowance for housing and food for '
    'a student living ON CAMPUS, whole USD per academic year (the year is '
    'named by ScorecardVintage.PUBLISHED_PRICE, never restated here). NULL is '
    '"not reported", never zero; a school with no residence halls reports no '
    'on-campus figure at all (college_ipeds.offers_housing is the authority '
    'on which of the two it is -- do not infer it from this NULL).';

COMMENT ON COLUMN colleges.housing_and_food_off_campus_per_year_usd IS
    'ROOMBOARD_OFF: the school''s published allowance for housing and food for '
    'a student living OFF CAMPUS and not with family, whole USD per academic '
    'year (the year is named by ScorecardVintage.PUBLISHED_PRICE, never '
    'restated here). NULL is "not reported", never zero.';

COMMENT ON COLUMN colleges.books_and_supplies_per_year_usd IS
    'BOOKSUPPLY: the school''s published allowance for books and supplies, '
    'whole USD per academic year (the year is named by '
    'ScorecardVintage.PUBLISHED_PRICE, never restated here). ONE figure for '
    'every living arrangement -- the Scorecard publishes no per-arrangement '
    'split -- so the same value enters the on-campus, off-campus and '
    'with-family totals. NULL is "not reported", never zero.';

COMMENT ON COLUMN colleges.other_expenses_on_campus_per_year_usd IS
    'OTHEREXPENSE_ON: the school''s published allowance for travel and '
    'personal expenses for a student living ON CAMPUS, whole USD per academic '
    'year (the year is named by ScorecardVintage.PUBLISHED_PRICE, never '
    'restated here). It excludes tuition and fees, housing and food, and '
    'books and supplies, each of which has its own column. NULL is "not '
    'reported", never zero.';

COMMENT ON COLUMN colleges.other_expenses_off_campus_per_year_usd IS
    'OTHEREXPENSE_OFF: the same travel-and-personal allowance for a student '
    'living OFF CAMPUS and not with family, whole USD per academic year (the '
    'year is named by ScorecardVintage.PUBLISHED_PRICE, never restated here). '
    'NULL is "not reported", never zero.';

COMMENT ON COLUMN colleges.other_expenses_with_family_per_year_usd IS
    'OTHEREXPENSE_FAM: the same travel-and-personal allowance for a student '
    'living WITH FAMILY, whole USD per academic year (the year is named by '
    'ScorecardVintage.PUBLISHED_PRICE, never restated here). There is no '
    'ROOMBOARD_FAM to go with it: the Scorecard publishes no housing-and-food '
    'allowance for a student living at home, so that arrangement carries this '
    'plus books and supplies and no housing line -- absent, never a zero. '
    'NULL is "not reported", never zero.';
