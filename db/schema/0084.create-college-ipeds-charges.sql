-- IPEDS IC_AY published charges, staged source-shaped. RFC 161 (brief 0006,
-- slice shape/02/ipeds-ic-ay).
--
-- Reference/staging table, RFC 84 composition: unversioned (gate-2 D15 — no
-- history trigger, no soft delete), reloaded by bin/ingest-colleges. Its
-- history is college_index_build. No column is added to colleges, so
-- log_college_version() is untouched.
--
-- IC2023_AY.csv is a 235-column wide table whose value columns are a
-- year × tier × component cross-product (CHG2AY3 = in-state tuition and fees,
-- 2023-24). Mirroring that shape would encode the cross-product in column
-- identifiers and force a migration for every published year, so the staging
-- table is NARROW: one row per UNITID × charge variable stem × academic year,
-- carrying the published value and its imputation flag verbatim.
--
-- Values are stored exactly as IPEDS publishes them (the source-defined-codes
-- rule): imputation_flag is the raw X-code (R reported, A not applicable,
-- C analyst-corrected, Z implied zero, ...), never a unicoach word. The
-- unicoach reading of a flag is made once, in the canonical-money fill.
CREATE TABLE college_ipeds_charges (
    id              UUID        NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    college_id      UUID        NOT NULL REFERENCES colleges (id) ON DELETE CASCADE,
    charge_variable TEXT        NOT NULL,   -- IPEDS stem without the year suffix: 'CHG2AY'
    academic_year   TEXT        NOT NULL,   -- 'YYYY-YY', decoded from the 0-3 suffix
    amount_usd      INTEGER     NULL,       -- NULL when the flag bears no value
    imputation_flag TEXT        NOT NULL,   -- the raw published X-code
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- The shape the identifier actually has: upper-case alphanumerics. No
    -- length bound, because nothing derives one -- a `{1,16}` would be this
    -- migration's opinion about a published vocabulary, refusing a longer stem
    -- IPEDS is free to publish while defending nothing.
    CONSTRAINT college_ipeds_charges_variable_format_check
        CHECK (charge_variable ~ '^[A-Z0-9]+$'),
    CONSTRAINT college_ipeds_charges_academic_year_format_check
        CHECK (academic_year ~ '^[0-9]{4}-[0-9]{2}$'),
    -- The 13 published X imputation codes, in full: a known value set stated
    -- as one, never as a shape. A letter outside this list is not a code --
    -- `Y` (professional practice) is published but never occurs on a loaded
    -- variable, and `Q` is not published at all. Admitted by a `^[A-Z]$` shape
    -- either would store cleanly, read as valueless through the CHECK below,
    -- and then fatal in the canonical-money fill 180,000 rows later. Listed
    -- here (SQL cannot reference Kotlin) but NOT counted: the count belongs to
    -- ImputationFlag, which is their one declaration.
    CONSTRAINT college_ipeds_charges_flag_domain_check
        CHECK (imputation_flag IN ('R', 'C', 'G', 'J', 'K', 'L', 'N', 'P', 'Z', 'B', 'D', 'H', 'A')),
    CONSTRAINT college_ipeds_charges_amount_nonneg_check
        CHECK (amount_usd IS NULL OR amount_usd >= 0),
    -- The value-IFF-flag rule, enforced where the rows live. IPEDS publishes
    -- the imputation codes below as value-BEARING (the figure exists, reported
    -- or imputed); every other published code says why there is no figure. So
    -- an amount exists exactly when the flag bears one -- the same shape as
    -- price_figures' own value-IFF-status CHECK.
    --
    -- The loader already refuses both bad pairings at parse, so this adds no
    -- new failure mode; what it adds is that the pairing survives ANY writer,
    -- and that the loader's claim to be backstopped by the database is true.
    -- The codes are listed (SQL cannot reference Kotlin) but NOT counted: the
    -- count belongs to ImputationFlag, which is their one declaration.
    CONSTRAINT college_ipeds_charges_value_iff_flag_check
        CHECK ((amount_usd IS NOT NULL) = (imputation_flag IN ('R', 'C', 'G', 'J', 'K', 'L', 'N', 'P', 'Z')))
);

CREATE UNIQUE INDEX college_ipeds_charges_natural_key_idx
    ON college_ipeds_charges (college_id, charge_variable, academic_year);

CREATE TRIGGER trigger_03_enforce_college_ipeds_charges_updated_at
BEFORE UPDATE ON college_ipeds_charges
FOR EACH ROW
EXECUTE PROCEDURE update_colleges_timestamp();

COMMENT ON TABLE college_ipeds_charges IS
    'IPEDS IC_AY published charges, one row per college × charge variable stem '
    '× academic year (RFC 161). Source-shaped staging: the value and the raw '
    'X imputation flag as published, no unicoach reading applied. The '
    'canonical-money phase maps these into price_figures.';

COMMENT ON COLUMN college_ipeds_charges.charge_variable IS
    'The IPEDS variable stem with the 0-3 year suffix removed: CHG2AY3 is '
    'stored as charge_variable CHG2AY, academic_year 2023-24.';

COMMENT ON COLUMN college_ipeds_charges.imputation_flag IS
    'The raw published X-code partner of the value column (XCHG2AY3 for '
    'CHG2AY3), stored exactly as published. A flag that bears no value leaves '
    'amount_usd NULL and a value-bearing flag always carries one, which '
    'college_ipeds_charges_value_iff_flag_check enforces.';

-- ---------------------------------------------------------------------------
-- The money source becomes an owned enumeration (RFC 161, decision 6).
--
-- price_figures.source was decorative while one source existed. RFC 161 makes
-- it load-bearing: CanonicalMoneyLoader.ORDERED_SOURCES decides which of two
-- conflicting prices a family is shown, so a mistyped source string would
-- silently change a price. The repo's own rule for an owned enumeration
-- applies: TEXT + CHECK IN (...) in the schema plus exactly one Kotlin enum
-- class with a fromValue companion (MoneySource).
--
-- Deliberately NOT a sixth vocabulary table: the five RFC 158 seeded are
-- unicoach concepts; a source is external identity and provenance.
-- ---------------------------------------------------------------------------

ALTER TABLE price_figures
    ADD CONSTRAINT price_figures_source_domain_check
    CHECK (source IN ('ipeds_ic_ay', 'scorecard'));

ALTER TABLE cohort_money_stats
    ADD CONSTRAINT cohort_money_stats_source_domain_check
    CHECK (source IN ('ipeds_ic_ay', 'scorecard'));
