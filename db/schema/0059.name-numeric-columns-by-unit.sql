-- Every numeric column says its UNIT, and the unit comes LAST.
--
-- The College Scorecard / IPEDS / CDS measures on `colleges`, `colleges_versions`,
-- `college_ipeds`, `college_merit_aid` and `college_programs_census` were named
-- for the concept only: `net_price`, `median_earnings`,
-- `graduation_rate`, `pct_pell`. Reading one of those you cannot tell dollars from
-- a 0-1 ratio from a percent, whether a price is annual or total, or -- for the two
-- federal definitions people most often misread -- what is actually being counted.
-- So each name is rebuilt as measure_qualifier_unit:
--
--   money      -> _usd        (whole dollars, nominal, INTEGER)
--   ratio 0..1 -> _share
--   counts     -> _headcount (people) / _count (things) / _beds
--   scores     -> _score
--
-- Pure rename: no column is added or dropped, no value changes, no behaviour
-- changes. ALTER ... RENAME throughout, so every index and CHECK keeps its
-- definition and is re-pointed by the catalog; only NAMES change.
--
-- Two deliberate deviations from the unit vocabulary above, both because the
-- alternative would be a lie:
--
--   * college_ipeds.disability_pct holds IC.DISABPCT, which IPEDS publishes on a
--     0-100 scale (its CHECK is `BETWEEN 0 AND 100`). It is NOT a 0-1 share, so it
--     becomes registered_disability_percent -- unit last, unit true. Calling it
--     `_share` would need a division by 100, which is a behaviour change.
--   * has_housing -> offers_housing: IC.ROOM means the institution OFFERS on-campus
--     housing, not that it guarantees or has room for a given student. The old name
--     invited the stronger reading.
--
-- Two consequences worth stating rather than discovering:
--
--   * KNOWN GAP surfaced by the rename, not caused by it: completion_rate_150pct_4yr
--     is C150_4, four-year institutions only, and the loader has no C150_L4
--     fallback -- so every less-than-four-year college is NULL, not zero. The
--     old name `graduation_rate` hid that; the new one and its COMMENT say it.
--   * The RFC 139 ingest provenance writes per-column keys into
--     college_index_build.change_summary (non_null.<column> before/after). That
--     log is append-only, so rows written after this migration key the NEW
--     column names while every historical row keeps the old ones. Nothing reads
--     across build rows today -- the summary is rendered per run -- so this is a
--     seam to know about, not a break.
--
-- The publisher's own names stay as published: the CSV headers (NPT4*, COSTT4_A,
-- MD_EARN_WNE_P10, C150_4, PCTPELL, SAT_AVG, UGDS, ADM_RATE, IC.ROOMCAP, ...) and
-- the fetched db/seed/cds/* artifacts whose sha256 digests PROVENANCE.json records.

-- ---------------------------------------------------------------------------
-- colleges / colleges_versions -- College Scorecard measures
-- ---------------------------------------------------------------------------

ALTER TABLE colleges RENAME COLUMN undergrad_enrollment TO undergrad_enrollment_headcount;
ALTER TABLE colleges RENAME COLUMN admission_rate       TO admission_rate_share;
ALTER TABLE colleges RENAME COLUMN sat_avg              TO sat_average_equivalent_score;
ALTER TABLE colleges RENAME COLUMN cost_attendance      TO cost_of_attendance_per_year_usd;
ALTER TABLE colleges RENAME COLUMN net_price            TO net_price_per_year_usd;
ALTER TABLE colleges RENAME COLUMN tuition_in_state     TO tuition_and_fees_in_state_per_year_usd;
ALTER TABLE colleges RENAME COLUMN tuition_out_state    TO tuition_and_fees_out_of_state_per_year_usd;
ALTER TABLE colleges RENAME COLUMN graduation_rate      TO completion_rate_150pct_4yr_share;
ALTER TABLE colleges RENAME COLUMN median_earnings      TO median_earnings_10y_after_entry_usd;
ALTER TABLE colleges RENAME COLUMN pct_pell             TO pell_share;
ALTER TABLE colleges RENAME COLUMN net_price_q1         TO net_price_per_year_income_q1_usd;
ALTER TABLE colleges RENAME COLUMN net_price_q2         TO net_price_per_year_income_q2_usd;
ALTER TABLE colleges RENAME COLUMN net_price_q3         TO net_price_per_year_income_q3_usd;
ALTER TABLE colleges RENAME COLUMN net_price_q4         TO net_price_per_year_income_q4_usd;
ALTER TABLE colleges RENAME COLUMN net_price_q5         TO net_price_per_year_income_q5_usd;
ALTER TABLE colleges RENAME COLUMN median_debt          TO median_debt_at_completion_usd;

ALTER TABLE colleges_versions RENAME COLUMN undergrad_enrollment TO undergrad_enrollment_headcount;
ALTER TABLE colleges_versions RENAME COLUMN admission_rate       TO admission_rate_share;
ALTER TABLE colleges_versions RENAME COLUMN sat_avg              TO sat_average_equivalent_score;
ALTER TABLE colleges_versions RENAME COLUMN cost_attendance      TO cost_of_attendance_per_year_usd;
ALTER TABLE colleges_versions RENAME COLUMN net_price            TO net_price_per_year_usd;
ALTER TABLE colleges_versions RENAME COLUMN tuition_in_state     TO tuition_and_fees_in_state_per_year_usd;
ALTER TABLE colleges_versions RENAME COLUMN tuition_out_state    TO tuition_and_fees_out_of_state_per_year_usd;
ALTER TABLE colleges_versions RENAME COLUMN graduation_rate      TO completion_rate_150pct_4yr_share;
ALTER TABLE colleges_versions RENAME COLUMN median_earnings      TO median_earnings_10y_after_entry_usd;
ALTER TABLE colleges_versions RENAME COLUMN pct_pell             TO pell_share;
ALTER TABLE colleges_versions RENAME COLUMN net_price_q1         TO net_price_per_year_income_q1_usd;
ALTER TABLE colleges_versions RENAME COLUMN net_price_q2         TO net_price_per_year_income_q2_usd;
ALTER TABLE colleges_versions RENAME COLUMN net_price_q3         TO net_price_per_year_income_q3_usd;
ALTER TABLE colleges_versions RENAME COLUMN net_price_q4         TO net_price_per_year_income_q4_usd;
ALTER TABLE colleges_versions RENAME COLUMN net_price_q5         TO net_price_per_year_income_q5_usd;
ALTER TABLE colleges_versions RENAME COLUMN median_debt          TO median_debt_at_completion_usd;

-- ---------------------------------------------------------------------------
-- college_ipeds -- IPEDS institutional attributes
-- ---------------------------------------------------------------------------

ALTER TABLE college_ipeds RENAME COLUMN disability_pct   TO registered_disability_percent;
ALTER TABLE college_ipeds RENAME COLUMN has_housing      TO offers_housing;
ALTER TABLE college_ipeds RENAME COLUMN housing_capacity TO housing_capacity_headcount;
ALTER TABLE college_ipeds RENAME COLUMN application_fee  TO application_fee_usd;

-- ---------------------------------------------------------------------------
-- college_merit_aid / college_programs_census -- CDS and IPEDS counts
-- ---------------------------------------------------------------------------
-- Counts split by WHAT is counted: _headcount is for people, _count for things.
-- Awards conferred are not people, so the census total is awards_count while the
-- two CDS freshman figures are headcounts.

ALTER TABLE college_merit_aid RENAME COLUMN freshmen_ft_total   TO first_time_full_time_freshmen_headcount;
ALTER TABLE college_merit_aid RENAME COLUMN no_need_merit_count TO no_need_merit_recipients_headcount;
ALTER TABLE college_merit_aid RENAME COLUMN no_need_merit_avg   TO no_need_merit_average_usd;

ALTER TABLE college_programs_census RENAME COLUMN awards_total TO awards_count;

-- ---------------------------------------------------------------------------
-- Dependent indexes and CHECK constraints -- renamed, never rebuilt
-- ---------------------------------------------------------------------------

ALTER INDEX colleges_undergrad_enrollment_idx RENAME TO colleges_undergrad_enrollment_headcount_idx;
ALTER INDEX colleges_admission_rate_idx       RENAME TO colleges_admission_rate_share_idx;
ALTER INDEX colleges_net_price_idx            RENAME TO colleges_net_price_per_year_usd_idx;
ALTER INDEX colleges_graduation_rate_idx      RENAME TO colleges_completion_rate_150pct_4yr_share_idx;

ALTER TABLE colleges
    RENAME CONSTRAINT colleges_admission_rate_range_check TO colleges_admission_rate_share_range_check;
ALTER TABLE colleges
    RENAME CONSTRAINT colleges_undergrad_enrollment_nonneg_check
        TO colleges_undergrad_enrollment_headcount_nonneg_check;
ALTER TABLE colleges
    RENAME CONSTRAINT colleges_sat_avg_nonneg_check TO colleges_sat_average_equivalent_score_nonneg_check;
ALTER TABLE colleges
    RENAME CONSTRAINT colleges_cost_attendance_nonneg_check
        TO colleges_cost_of_attendance_per_year_usd_nonneg_check;
ALTER TABLE colleges
    RENAME CONSTRAINT colleges_tuition_in_state_nonneg_check
        TO colleges_tuition_and_fees_in_state_nonneg_check;
-- The two tuition CHECKs drop `_per_year_usd` from their names, unlike every
-- other constraint here which mirrors its column exactly: the out-of-state form
-- would be 64 characters and Postgres silently TRUNCATES an identifier at 63,
-- leaving a name no one could grep for. Shortened symmetrically so the pair
-- still reads as a pair.
ALTER TABLE colleges
    RENAME CONSTRAINT colleges_tuition_out_state_nonneg_check
        TO colleges_tuition_and_fees_out_of_state_nonneg_check;
ALTER TABLE colleges
    RENAME CONSTRAINT colleges_median_earnings_nonneg_check
        TO colleges_median_earnings_10y_after_entry_usd_nonneg_check;
ALTER TABLE colleges
    RENAME CONSTRAINT colleges_graduation_rate_range_check
        TO colleges_completion_rate_150pct_4yr_share_range_check;
ALTER TABLE colleges
    RENAME CONSTRAINT colleges_pct_pell_range_check TO colleges_pell_share_range_check;
ALTER TABLE colleges
    RENAME CONSTRAINT colleges_median_debt_nonneg_check
        TO colleges_median_debt_at_completion_usd_nonneg_check;

ALTER TABLE college_ipeds
    RENAME CONSTRAINT college_ipeds_disability_pct_range_check
        TO college_ipeds_registered_disability_percent_range_check;
ALTER TABLE college_ipeds
    RENAME CONSTRAINT college_ipeds_housing_capacity_nonneg_check
        TO college_ipeds_housing_capacity_headcount_nonneg_check;
ALTER TABLE college_ipeds
    RENAME CONSTRAINT college_ipeds_application_fee_nonneg_check
        TO college_ipeds_application_fee_usd_nonneg_check;

ALTER TABLE college_merit_aid
    RENAME CONSTRAINT college_merit_aid_count_le_total_check
        TO college_merit_aid_recipients_le_freshmen_check;
ALTER TABLE college_programs_census
    RENAME CONSTRAINT college_programs_census_awards_total_nonneg_check
        TO college_programs_census_awards_count_nonneg_check;

-- college_merit_aid_nonneg_check spans all three renamed columns but names none
-- of them, so its name is still accurate and is left alone.

-- Deliberately NOT renamed: colleges_net_price_nonneg_check does not exist --
-- 0022 dropped it (Scorecard publishes legitimately negative net prices). The
-- Postgres-generated NOT NULL constraint names are left alone for the same
-- reason 0057 left them alone: catalog artifacts our code never names.

-- ---------------------------------------------------------------------------
-- The history writer
-- ---------------------------------------------------------------------------
-- A plpgsql body is stored as TEXT, so the renames above do NOT reach inside it:
-- the 0057 definition still names the old columns and would fail on the next
-- college write. Redefined verbatim except for those names, keeping the
-- 0023/0045/0051/0057 CREATE OR REPLACE pattern -- trigger_04_log_college_version
-- picks it up by name with no re-wiring.
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
        median_debt_at_completion_usd, aliases, created_at, updated_at
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
        NEW.aliases, NEW.created_at, NEW.updated_at
    );
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- ---------------------------------------------------------------------------
-- What each measure actually counts
-- ---------------------------------------------------------------------------
-- A name can only carry so much. Where the federal definition is narrower than
-- any name would suggest, the definition is written down here rather than
-- rediscovered from a data dictionary.

COMMENT ON COLUMN colleges.median_earnings_10y_after_entry_usd IS
    'MD_EARN_WNE_P10: median ANNUAL earnings, in whole USD, of former students '
    'measured 10 years after ENTRY -- not 10 years after graduation, and not a '
    'lifetime or cumulative figure. The cohort is students who received federal '
    'aid and are working, whether or not they completed a credential.';
COMMENT ON COLUMN colleges.completion_rate_150pct_4yr_share IS
    'C150_4: the share (0-1) of the FIRST-TIME, FULL-TIME degree-seeking cohort '
    'at a FOUR-YEAR institution that completed within 150% of normal time -- '
    '6 years. It is not an overall graduation rate: transfer-in and part-time '
    'students are outside the cohort entirely, so they can neither raise nor '
    'lower it. KNOWN GAP: the loader reads C150_4 only, with no C150_L4 '
    'fallback, so every less-than-four-year institution is NULL here -- absent, '
    'not zero, and not comparable across the two sectors.';

COMMENT ON COLUMN colleges.undergrad_enrollment_headcount IS
    'UGDS: the enrolled undergraduate headcount, degree-seeking AND '
    'certificate-seeking. It is NOT a degree-seeking-only figure, and it is a '
    'headcount rather than an FTE.';

COMMENT ON COLUMN colleges.sat_average_equivalent_score IS
    'SAT_AVG: the average SAT-EQUIVALENT score of ADMITTED students -- ACT '
    'scores are converted to the SAT scale and folded in, so this is not a pure '
    'SAT average, and the population is admits, not enrollees or applicants.';

COMMENT ON COLUMN colleges.net_price_per_year_usd IS
    'NPT4_PUB (control=1) else NPT4_PRIV: the AVERAGE annual net price in whole '
    'USD -- cost of attendance minus grant/scholarship aid -- over FULL-TIME, '
    'FIRST-TIME, degree/certificate-seeking undergraduates who received TITLE IV '
    'aid. It is not a price any individual family was quoted, and it says '
    'nothing about continuing or part-time students. `per_year` is the academic '
    'year the institution reports on: programs shorter than a year (LPROGRAM) '
    'are NOT filtered out, so a handful of rows are per-program rather than '
    'strictly annual.';

COMMENT ON COLUMN colleges.cost_of_attendance_per_year_usd IS
    'COSTT4_A: the AVERAGE annual published cost of attendance in whole USD -- '
    'tuition and fees plus books, supplies and living costs -- for FULL-TIME, '
    'FIRST-TIME, degree/certificate-seeking undergraduates. Sticker, before any '
    'aid. Same `per_year` caveat as net price: sub-one-year LPROGRAM programs '
    'are not filtered out.';

COMMENT ON COLUMN colleges.median_debt_at_completion_usd IS
    'GRAD_DEBT_MDN: the MEDIAN cumulative FEDERAL loan debt in whole USD, at '
    'completion, of students who COMPLETED. NSLDS federal borrowers only -- '
    'private loans, parent PLUS and any debt of non-completers are outside it, '
    'so it understates what a family may actually owe.';

COMMENT ON COLUMN college_ipeds.registered_disability_percent IS
    'IC.DISABPCT, published on a 0-100 PERCENT scale (not a 0-1 share): the '
    'percent of undergraduates formally registered with the disability services '
    'office. Reported only when disability_band = 2 (>3%).';
COMMENT ON COLUMN college_merit_aid.first_time_full_time_freshmen_headcount IS
    'CDS H.201 FRSH_FT_N: the count of DEGREE-SEEKING, FIRST-TIME, FULL-TIME '
    'freshmen enrolled -- all of them, needy or not. It is the denominator of '
    'the derived merit-aid share, which is therefore a share of ALL such '
    'freshmen, not of the no-need ones (CDS does not publish that denominator).';
COMMENT ON COLUMN college_merit_aid.no_need_merit_average_usd IS
    'CDS H.2A02 FRESH_FT_NN_NONEED_D: the school-reported AVERAGE institutional '
    'non-need ("merit") award, in whole USD, PER RECIPIENT -- the average over the '
    'no_need_merit_recipients_headcount freshmen who got one, not a total pot and '
    'not an average over all freshmen. Source data, not derivable: CDS publishes '
    'no total-dollars column.';

COMMENT ON COLUMN college_ipeds.offers_housing IS
    'IC.ROOM: the institution OFFERS on-campus housing. It is not a guarantee of '
    'a bed for any given student; housing_capacity_headcount is the reported capacity.';
