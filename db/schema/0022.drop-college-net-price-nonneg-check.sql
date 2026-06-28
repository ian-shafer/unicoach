-- Drop colleges_net_price_nonneg_check: net price is legitimately negative.
--
-- 0015 assumed net price (NPT4_PUB/NPT4_PRIV) could not go below zero, but the
-- Scorecard value is cost of attendance minus average grant/scholarship aid --
-- and at heavily-subsidized institutions (community colleges especially) aid
-- exceeds cost, so the published figure is negative (e.g. Ventura College,
-- UNITID 125028, NPT4_PUB = -982). The nonneg CHECK rejected those whole
-- institutions, which then cascaded into "no college" skips for every one of
-- their programs.
--
-- Net price has no semantic lower bound, so the constraint is dropped outright
-- rather than relaxed to an arbitrary floor. The sibling cost/tuition/earnings
-- nonneg checks are kept: those quantities genuinely cannot be negative.

ALTER TABLE colleges
  DROP CONSTRAINT colleges_net_price_nonneg_check;
