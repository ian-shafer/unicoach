-- The blended Scorecard figures say WHOSE residency they are on. RFC 157 D-E.
--
-- COMMENTS ONLY: no column is added, dropped or retyped, no value changes, no
-- constraint or index moves. `COMMENT ON COLUMN` is a catalog write and nothing
-- reads these strings at runtime -- they are read by the next person to write a
-- docstring from them, which is exactly how the defect this fixes travelled.
--
-- 0059 wrote down what each measure COUNTS: the cohort (full-time, first-time,
-- degree/certificate-seeking), the aid basis (Title IV recipients, grant and
-- scholarship aid only) and the `per_year` LPROGRAM caveat. It said nothing
-- about RESIDENCY, while naming NPT4_PUB in the same sentence. The College
-- Scorecard Institution Data Documentation (June 2024) states the missing fact
-- twice: cost of attendance is "reported to IPEDS by institutions for students
-- paying the in-state or in-district tuition rate", and for public institutions
-- the net price "is limited to full-time, first-time, degree/certificate-seeking
-- undergraduates who pay in-state tuition and receive Title IV aid".
--
-- UC San Diego settles it arithmetically rather than by citation: COSTT4_A =
-- 38,701 sits inside the in-state arrangement span (25,723 .. 43,459) and BELOW
-- the smallest out-of-state arrangement total (59,923). A weighted average
-- cannot fall below its own smallest input, so no weighting of out-of-state
-- totals can produce it.
--
-- The consequence worth writing into the catalog rather than rediscovering: at a
-- public institution these seven figures do NOT describe a family from another
-- state, and there is no federal column that does -- an out-of-state published
-- price is only obtainable as tuition_and_fees_out_of_state_per_year_usd plus
-- the components. At a private institution one price applies to everyone, so the
-- distinction does not exist there.
--
-- Mirrors 0059's shape: `COMMENT ON COLUMN <col> IS` with the copy as adjacent
-- single-quoted string literals, and `colleges` only. 0059 commented no
-- `colleges_versions` column and this migration does not either -- the history
-- table takes its meaning from the live table it mirrors, and two copies of one
-- definition is one copy that goes stale.

COMMENT ON COLUMN colleges.cost_of_attendance_per_year_usd IS
    'COSTT4_A: the AVERAGE annual published cost of attendance in whole USD -- '
    'tuition and fees plus books, supplies and living costs -- for FULL-TIME, '
    'FIRST-TIME, degree/certificate-seeking undergraduates. Sticker, before any '
    'aid. IN-STATE BASIS: at a PUBLIC institution this is reported for students '
    'paying the in-state (strictly in-state or in-district) tuition rate, so it '
    'does NOT describe a family from another state -- their published price is '
    'tuition_and_fees_out_of_state_per_year_usd plus the components, and no '
    'federal column carries it. At a PRIVATE institution one price applies to '
    'everyone and the distinction does not arise. Same `per_year` caveat as net '
    'price: sub-one-year LPROGRAM programs are not filtered out.';

COMMENT ON COLUMN colleges.net_price_per_year_usd IS
    'NPT4_PUB (control=1) else NPT4_PRIV: the AVERAGE annual net price in whole '
    'USD -- cost of attendance minus grant/scholarship aid -- over FULL-TIME, '
    'FIRST-TIME, degree/certificate-seeking undergraduates who received TITLE IV '
    'aid. It is not a price any individual family was quoted, and it says '
    'nothing about continuing or part-time students. IN-STATE BASIS: NPT4_PUB is '
    'limited to those undergraduates who PAY IN-STATE TUITION, so at a public '
    'institution it does not describe a family from another state; NPT4_PRIV has '
    'no residency restriction because a private institution has one price. '
    '`per_year` is the academic year the institution reports on: programs '
    'shorter than a year (LPROGRAM) are NOT filtered out, so a handful of rows '
    'are per-program rather than strictly annual.';

-- The five band columns carry NPT41..45, which the Data Dictionary defines with
-- the SAME sentence as NPT4 per income band, so they inherit the same cohort,
-- the same aid basis and the same in-state restriction. 0045 added them and 0059
-- renamed them; neither commented them, so this is their first COMMENT.
COMMENT ON COLUMN colleges.net_price_per_year_income_q1_usd IS
    'NPT41_PUB (control=1) else NPT41_PRIV: net price as net_price_per_year_usd '
    'defines it, for the household income band $0-30,000 as the institution '
    'measured income for aid eligibility. Same cohort (FULL-TIME, FIRST-TIME, '
    'degree/certificate-seeking TITLE IV recipients), same aid basis (grants and '
    'scholarships only) and the same `per_year` LPROGRAM caveat. IN-STATE BASIS: '
    'the _PUB series is limited to undergraduates who PAY IN-STATE TUITION, so at '
    'a public institution it does not describe a family from another state. '
    'Legitimately NEGATIVE when aid exceeds cost, which is why 0045 gave these '
    'columns no nonneg CHECK.';
COMMENT ON COLUMN colleges.net_price_per_year_income_q2_usd IS
    'NPT42_PUB (control=1) else NPT42_PRIV: the $30,001-48,000 band. Cohort, aid '
    'basis, `per_year` caveat, the IN-STATE BASIS of the _PUB series and the '
    'legitimate negatives are all as net_price_per_year_income_q1_usd states '
    'them.';
COMMENT ON COLUMN colleges.net_price_per_year_income_q3_usd IS
    'NPT43_PUB (control=1) else NPT43_PRIV: the $48,001-75,000 band. Cohort, aid '
    'basis, `per_year` caveat, the IN-STATE BASIS of the _PUB series and the '
    'legitimate negatives are all as net_price_per_year_income_q1_usd states '
    'them.';
COMMENT ON COLUMN colleges.net_price_per_year_income_q4_usd IS
    'NPT44_PUB (control=1) else NPT44_PRIV: the $75,001-110,000 band. Cohort, aid '
    'basis, `per_year` caveat, the IN-STATE BASIS of the _PUB series and the '
    'legitimate negatives are all as net_price_per_year_income_q1_usd states '
    'them.';
COMMENT ON COLUMN colleges.net_price_per_year_income_q5_usd IS
    'NPT45_PUB (control=1) else NPT45_PRIV: the $110,001-and-over band. Cohort, '
    'aid basis, `per_year` caveat, the IN-STATE BASIS of the _PUB series and the '
    'legitimate negatives are all as net_price_per_year_income_q1_usd states '
    'them. This is the band a full-pay out-of-state family reads as theirs, and '
    'at a public institution it is the one that understates their price most.';
