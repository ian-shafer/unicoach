-- pg_trgm + curated college aliases for fuzzy name search. RFC 139.
--
-- The repo's first CREATE EXTENSION: pg_trgm is contrib, present in both the
-- nix dev Postgres 18 and RDS 18 (on AWS's supported-extension list).
--
-- `aliases` is curated repo data (db/data/college-aliases.json), not Scorecard
-- data: the ingest applies it after the Scorecard upsert phase with the same
-- change suppression (an unchanged alias set writes nothing and bumps nothing).
-- It is mirrored on colleges_versions and carried by log_college_version() (the
-- 0045 redefinition pattern) so alias changes are versioned like every other
-- college change.
--
-- The trigram index covers `name || ' ' || array_to_string(aliases, ' ')` — one
-- searchable text per college. RFC 139 writes that expression inline in the
-- index DDL, but array_to_string() is only STABLE, and an index expression must
-- be IMMUTABLE — so the expression lives in college_search_text(), an IMMUTABLE
-- SQL wrapper (honest for TEXT[]: string concatenation of text needs no
-- locale/catalog lookups). Queries must call college_search_text(name, aliases)
-- verbatim for the planner to match the index.

CREATE EXTENSION pg_trgm;

ALTER TABLE colleges
    ADD COLUMN aliases TEXT[] NOT NULL DEFAULT '{}';
ALTER TABLE colleges_versions
    ADD COLUMN aliases TEXT[] NOT NULL DEFAULT '{}';

-- The one searchable text per college: name plus every curated alias. IMMUTABLE
-- (see header) so it is usable in the index expression below; PARALLEL SAFE for
-- free. NULL-safe: aliases is NOT NULL DEFAULT '{}', and a NULL name row cannot
-- exist (NOT NULL), so no COALESCE is needed.
CREATE FUNCTION college_search_text(name TEXT, aliases TEXT[])
RETURNS TEXT
LANGUAGE sql IMMUTABLE PARALLEL SAFE
RETURN name || ' ' || array_to_string(aliases, ' ');

CREATE INDEX colleges_search_text_trgm_idx ON colleges
    USING gin (college_search_text(name, aliases) gin_trgm_ops);

-- Redefine the history writer to carry aliases. CREATE OR REPLACE is picked up
-- by the existing trigger_04_log_college_version by name with no re-wiring (the
-- 0023/0045 pattern).
CREATE OR REPLACE FUNCTION log_college_version()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO colleges_versions (
        id, version, unit_id, opeid, name, city, state, region, locale,
        latitude, longitude, control, undergrad_enrollment, admission_rate,
        sat_avg, cost_attendance, net_price, tuition_in_state, tuition_out_state,
        graduation_rate, median_earnings, pct_pell, website,
        net_price_q1, net_price_q2, net_price_q3, net_price_q4, net_price_q5,
        median_debt, aliases, created_at, updated_at
    ) VALUES (
        NEW.id, NEW.version, NEW.unit_id, NEW.opeid, NEW.name, NEW.city, NEW.state,
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
