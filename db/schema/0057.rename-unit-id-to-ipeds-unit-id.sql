-- Rename the federal institution identifier column to say what it is: an IPEDS
-- UNITID. `unit_id` on its own read as a generic surrogate key; every place it
-- appears it is the IPEDS UNITID, so the column, its indexes, its CHECK
-- constraints and the history writer all take the fuller name.
--
-- Pure rename: no column is added or dropped, no value changes, no behaviour
-- changes. ALTER ... RENAME throughout, so indexes and CHECK bodies keep their
-- definitions and are re-pointed by the catalog; only their NAMES are updated.

ALTER TABLE colleges          RENAME COLUMN unit_id     TO ipeds_unit_id;
ALTER TABLE colleges_versions RENAME COLUMN unit_id     TO ipeds_unit_id;
ALTER TABLE college_ipeds     RENAME COLUMN unit_id     TO ipeds_unit_id;
ALTER TABLE college_ipeds     RENAME COLUMN new_unit_id TO new_ipeds_unit_id;

ALTER INDEX colleges_unit_id_unique_idx      RENAME TO colleges_ipeds_unit_id_unique_idx;
ALTER INDEX college_ipeds_unit_id_unique_idx RENAME TO college_ipeds_ipeds_unit_id_unique_idx;

ALTER TABLE colleges
    RENAME CONSTRAINT colleges_unit_id_positive_check TO colleges_ipeds_unit_id_positive_check;
ALTER TABLE college_ipeds
    RENAME CONSTRAINT college_ipeds_unit_id_positive_check TO college_ipeds_ipeds_unit_id_positive_check;
ALTER TABLE college_ipeds
    RENAME CONSTRAINT college_ipeds_new_unit_id_positive_check TO college_ipeds_new_ipeds_unit_id_positive_check;

-- A plpgsql body is stored as TEXT, so the rename above does NOT reach it: the
-- 0051 definition still names unit_id and would fail on the next college write.
-- Redefined verbatim except for that one column name, keeping the
-- 0023/0045/0051 CREATE OR REPLACE pattern -- trigger_04_log_college_version
-- picks it up by name with no re-wiring.
CREATE OR REPLACE FUNCTION log_college_version()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO colleges_versions (
        id, version, ipeds_unit_id, opeid, name, city, state, region, locale,
        latitude, longitude, control, undergrad_enrollment, admission_rate,
        sat_avg, cost_attendance, net_price, tuition_in_state, tuition_out_state,
        graduation_rate, median_earnings, pct_pell, website,
        net_price_q1, net_price_q2, net_price_q3, net_price_q4, net_price_q5,
        median_debt, aliases, created_at, updated_at
    ) VALUES (
        NEW.id, NEW.version, NEW.ipeds_unit_id, NEW.opeid, NEW.name, NEW.city, NEW.state,
        NEW.region, NEW.locale, NEW.latitude, NEW.longitude, NEW.control,
        NEW.undergrad_enrollment, NEW.admission_rate, NEW.sat_avg,
        NEW.cost_attendance, NEW.net_price, NEW.tuition_in_state,
        NEW.tuition_out_state, NEW.graduation_rate, NEW.median_earnings,
        NEW.pct_pell, NEW.website,
        NEW.net_price_q1, NEW.net_price_q2, NEW.net_price_q3, NEW.net_price_q4,
        NEW.net_price_q5, NEW.median_debt, NEW.aliases, NEW.created_at, NEW.updated_at
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Deliberately NOT renamed: the Postgres-generated NOT NULL constraint names
-- (colleges_unit_id_not_null, colleges_versions_unit_id_not_null,
-- college_ipeds_unit_id_not_null). They are catalog artifacts of PG 17+, never
-- named by our code, and absent entirely on a cluster whose rows predate that
-- representation -- so renaming them buys nothing and could fail the migration.

COMMENT ON COLUMN colleges.ipeds_unit_id IS
    'IPEDS UNITID, the federal natural key for the institution (upsert target).';
COMMENT ON COLUMN college_ipeds.ipeds_unit_id IS
    'IPEDS UNITID; joins colleges.ipeds_unit_id.';
COMMENT ON COLUMN college_ipeds.new_ipeds_unit_id IS
    'HD.NEWID -- the IPEDS UNITID of the merger successor institution.';
