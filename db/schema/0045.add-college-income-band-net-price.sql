-- Income-band net price and median debt on colleges. RFC 133.
--
-- Six nullable INTEGER columns on `colleges` (and mirrored on
-- `colleges_versions`), sourced from the College Scorecard institution file:
--   net_price_q1  -- NPT41_PUB (control=1) else NPT41_PRIV, income $0-30k
--   net_price_q2  -- NPT42_*, $30,001-48k
--   net_price_q3  -- NPT43_*, $48,001-75k
--   net_price_q4  -- NPT44_*, $75,001-110k
--   net_price_q5  -- NPT45_*, $110k+
--   median_debt   -- GRAD_DEBT_MDN, median cumulative federal debt of completers
--
-- Constraints mirror the house pattern: median_debt (a loan amount) gets a
-- nonneg CHECK like the sibling cost/tuition/earnings columns; the five band
-- columns get NO nonneg CHECK, matching net_price, whose check was dropped in
-- 0022 because Scorecard publishes legitimate negative net prices when aid
-- exceeds cost (community colleges especially, and low-income bands most of
-- all -- e.g. Ventura College NPT41_PUB = -1913).
--
-- This migration runs before any backfill ingest, so every history row written
-- after it carries the six columns. Existing history rows show NULL for them --
-- correct: those ingests never saw the fields.

ALTER TABLE colleges
    ADD COLUMN net_price_q1 INTEGER NULL,
    ADD COLUMN net_price_q2 INTEGER NULL,
    ADD COLUMN net_price_q3 INTEGER NULL,
    ADD COLUMN net_price_q4 INTEGER NULL,
    ADD COLUMN net_price_q5 INTEGER NULL,
    ADD COLUMN median_debt  INTEGER NULL,
    ADD CONSTRAINT colleges_median_debt_nonneg_check
        CHECK (median_debt IS NULL OR median_debt >= 0);

ALTER TABLE colleges_versions
    ADD COLUMN net_price_q1 INTEGER NULL,
    ADD COLUMN net_price_q2 INTEGER NULL,
    ADD COLUMN net_price_q3 INTEGER NULL,
    ADD COLUMN net_price_q4 INTEGER NULL,
    ADD COLUMN net_price_q5 INTEGER NULL,
    ADD COLUMN median_debt  INTEGER NULL;

-- Redefine the history writer to carry the six new columns. CREATE OR REPLACE
-- is picked up by the existing trigger_04_log_college_version by name with no
-- re-wiring (the 0023 pattern).
CREATE OR REPLACE FUNCTION log_college_version()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO colleges_versions (
        id, version, unit_id, opeid, name, city, state, region, locale,
        latitude, longitude, control, undergrad_enrollment, admission_rate,
        sat_avg, cost_attendance, net_price, tuition_in_state, tuition_out_state,
        graduation_rate, median_earnings, pct_pell, website,
        net_price_q1, net_price_q2, net_price_q3, net_price_q4, net_price_q5,
        median_debt, created_at, updated_at
    ) VALUES (
        NEW.id, NEW.version, NEW.unit_id, NEW.opeid, NEW.name, NEW.city, NEW.state,
        NEW.region, NEW.locale, NEW.latitude, NEW.longitude, NEW.control,
        NEW.undergrad_enrollment, NEW.admission_rate, NEW.sat_avg,
        NEW.cost_attendance, NEW.net_price, NEW.tuition_in_state,
        NEW.tuition_out_state, NEW.graduation_rate, NEW.median_earnings,
        NEW.pct_pell, NEW.website,
        NEW.net_price_q1, NEW.net_price_q2, NEW.net_price_q3, NEW.net_price_q4,
        NEW.net_price_q5, NEW.median_debt, NEW.created_at, NEW.updated_at
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
