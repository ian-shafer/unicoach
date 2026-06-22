-- College knowledge: reference dataset for grounded school search. RFC 67.
--
-- Two reference tables holding a curated subset of College Scorecard data:
--   colleges          — institution-level identity/location/size/selectivity/cost/outcomes.
--   college_programs  — CIP program offerings per institution (full 6-digit granularity).
--
-- Both are externally-sourced federal data, mutated only by re-ingestion (upsert
-- on the natural key), never by application request flow. They are therefore
-- reference tables, not student entities: no OCC versioning, no version history,
-- no soft-delete, and no append-only log guards. The pinned snapshot file is the
-- archive; rows are re-derivable from it. They carry only logical created_at/
-- updated_at with a plain update-timestamp trigger (no physical/logical split).

-- ---------------------------------------------------------------------------
-- update_colleges_timestamp — plain updated_at advance (no logical/physical
-- split), mirroring update_jobs_timestamp() in 0003.create-queue.sql. Defined
-- here because this migration introduces the first non-entity, non-log mutable
-- reference tables; reused by both colleges and college_programs (touches only
-- updated_at).
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION update_colleges_timestamp()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------------
-- colleges — institution-level reference data
-- ---------------------------------------------------------------------------

CREATE TABLE colleges (
    id                   UUID              NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    unit_id              INTEGER           NOT NULL,                       -- UNITID, federal natural key (upsert target)
    opeid                TEXT              NULL,                           -- OPEID8
    name                 TEXT              NOT NULL,                       -- INSTNM
    city                 TEXT              NOT NULL,                       -- CITY
    state                TEXT              NOT NULL,                       -- STABBR, 2-letter postal code
    region               SMALLINT          NULL,                           -- REGION, IPEDS region 0-9
    locale               SMALLINT          NULL,                           -- LOCALE, urbanization 11-43
    latitude             DOUBLE PRECISION  NULL,                           -- LATITUDE
    longitude            DOUBLE PRECISION  NULL,                           -- LONGITUDE
    control              SMALLINT          NOT NULL,                       -- CONTROL, 1 public / 2 private nonprofit / 3 for-profit
    undergrad_enrollment INTEGER           NULL,                           -- UGDS, degree-seeking undergrad headcount
    admission_rate       DOUBLE PRECISION  NULL,                           -- ADM_RATE, 0-1
    sat_avg              INTEGER           NULL,                           -- SAT_AVG
    cost_attendance      INTEGER           NULL,                           -- COSTT4_A, annual cost of attendance USD
    net_price            INTEGER           NULL,                           -- NPT4_PUB (control=1) else NPT4_PRIV, coalesced at load
    tuition_in_state     INTEGER           NULL,                           -- TUITIONFEE_IN
    tuition_out_state    INTEGER           NULL,                           -- TUITIONFEE_OUT
    graduation_rate      DOUBLE PRECISION  NULL,                           -- C150_4, 6-yr completion 0-1
    median_earnings      INTEGER           NULL,                           -- MD_EARN_WNE_P10, median earnings ~10yr post-entry USD
    pct_pell             DOUBLE PRECISION  NULL,                           -- PCTPELL, Pell share of undergrads 0-1
    website              TEXT              NULL,                           -- INSTURL
    created_at           TIMESTAMPTZ       NOT NULL DEFAULT NOW(),
    updated_at           TIMESTAMPTZ       NOT NULL DEFAULT NOW(),

    CONSTRAINT colleges_unit_id_positive_check CHECK (unit_id > 0),
    CONSTRAINT colleges_name_length_check CHECK (length(name) <= 255),
    CONSTRAINT colleges_name_not_empty_check CHECK (length(trim(name)) > 0),
    CONSTRAINT colleges_city_length_check CHECK (length(city) <= 255),
    CONSTRAINT colleges_city_not_empty_check CHECK (length(trim(city)) > 0),
    CONSTRAINT colleges_state_length_check CHECK (length(state) = 2),
    CONSTRAINT colleges_control_valid_check CHECK (control IN (1, 2, 3)),
    CONSTRAINT colleges_region_range_check CHECK (region IS NULL OR region BETWEEN 0 AND 9),
    CONSTRAINT colleges_locale_range_check CHECK (locale IS NULL OR locale BETWEEN 11 AND 43),
    CONSTRAINT colleges_admission_rate_range_check CHECK (admission_rate IS NULL OR admission_rate BETWEEN 0 AND 1),
    CONSTRAINT colleges_undergrad_enrollment_nonneg_check CHECK (undergrad_enrollment IS NULL OR undergrad_enrollment >= 0),
    CONSTRAINT colleges_sat_avg_nonneg_check CHECK (sat_avg IS NULL OR sat_avg >= 0),
    CONSTRAINT colleges_cost_attendance_nonneg_check CHECK (cost_attendance IS NULL OR cost_attendance >= 0),
    CONSTRAINT colleges_net_price_nonneg_check CHECK (net_price IS NULL OR net_price >= 0),
    CONSTRAINT colleges_tuition_in_state_nonneg_check CHECK (tuition_in_state IS NULL OR tuition_in_state >= 0),
    CONSTRAINT colleges_tuition_out_state_nonneg_check CHECK (tuition_out_state IS NULL OR tuition_out_state >= 0),
    CONSTRAINT colleges_median_earnings_nonneg_check CHECK (median_earnings IS NULL OR median_earnings >= 0),
    CONSTRAINT colleges_graduation_rate_range_check CHECK (graduation_rate IS NULL OR graduation_rate BETWEEN 0 AND 1),
    CONSTRAINT colleges_pct_pell_range_check CHECK (pct_pell IS NULL OR pct_pell BETWEEN 0 AND 1),
    CONSTRAINT colleges_website_length_check CHECK (website IS NULL OR length(website) <= 255),
    CONSTRAINT colleges_opeid_length_check CHECK (opeid IS NULL OR length(opeid) <= 255)
);

-- Upsert target + dedup guard on the federal natural key.
CREATE UNIQUE INDEX colleges_unit_id_unique_idx ON colleges (unit_id);

-- Filter-supporting indexes.
CREATE INDEX colleges_state_idx ON colleges (state);
CREATE INDEX colleges_control_idx ON colleges (control);
CREATE INDEX colleges_undergrad_enrollment_idx ON colleges (undergrad_enrollment);
CREATE INDEX colleges_admission_rate_idx ON colleges (admission_rate);
CREATE INDEX colleges_net_price_idx ON colleges (net_price);
CREATE INDEX colleges_graduation_rate_idx ON colleges (graduation_rate);

-- Reserved enforce-updated_at slot (_03), per the trigger_NN convention. The
-- table is neither append-only nor OCC-versioned, so it reuses no entity guard.
CREATE TRIGGER trigger_03_enforce_colleges_updated_at
BEFORE UPDATE ON colleges
FOR EACH ROW
EXECUTE PROCEDURE update_colleges_timestamp();

-- ---------------------------------------------------------------------------
-- college_programs — CIP offerings per institution
-- ---------------------------------------------------------------------------

CREATE TABLE college_programs (
    id               UUID         NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    college_id       UUID         NOT NULL REFERENCES colleges(id) ON DELETE CASCADE,
    cip_code         TEXT         NOT NULL,                  -- CIPCODE, 6-digit CIP as a 6-char digit string
    cip_title        TEXT         NOT NULL,                  -- CIPDESC
    credential_level SMALLINT     NOT NULL,                  -- CREDLEV, 3 = bachelor's, etc. (always present in source)
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),

    CONSTRAINT college_programs_cip_code_format_check CHECK (cip_code ~ '^[0-9]{6}$'),
    CONSTRAINT college_programs_cip_title_length_check CHECK (length(cip_title) <= 255),
    CONSTRAINT college_programs_cip_title_not_empty_check CHECK (length(trim(cip_title)) > 0),
    CONSTRAINT college_programs_credential_level_range_check CHECK (credential_level BETWEEN 1 AND 8)
);

-- Upsert key. credential_level is NOT NULL (always present in the field-of-study
-- file), so the unique index is plain (no COALESCE sentinel needed).
CREATE UNIQUE INDEX college_programs_unique_idx
    ON college_programs (college_id, cip_code, credential_level);

-- Program-prefix lookups and the join from a matched college.
CREATE INDEX college_programs_cip_code_idx ON college_programs (cip_code);
CREATE INDEX college_programs_college_id_idx ON college_programs (college_id);

-- Reserved enforce-updated_at slot (_03); reuses update_colleges_timestamp()
-- (touches only updated_at). Carried for uniformity with colleges.
CREATE TRIGGER trigger_03_enforce_college_programs_updated_at
BEFORE UPDATE ON college_programs
FOR EACH ROW
EXECUTE PROCEDURE update_colleges_timestamp();
