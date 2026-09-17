-- The foreign keys test/05 and brief 0012 could not have. D27: unicoach owns
-- its published codebooks, so RFC 229 seeds them at MIGRATION time from
-- db/data/codebooks.json, and a migration-time row may now key into them.
--
-- Appended AFTER seed-published-codebooks-r1.sql (see ORDER), because a
-- freshly migrated database must hold the codebook rows before anything may be
-- checked against them. That order is the whole defect this migration closes:
-- test/05's seed applied against an EMPTY us_states and was refused with
-- `Key (state)=(AL) is not present in table "us_states"`.
--
-- ON DELETE RESTRICT on both: a codebook row that something cites may not be
-- deleted. That is the same answer CodebookLoader.ReferencedCodebookRowException
-- already gives one layer up, now held by the database.

ALTER TABLE admission_test_state_programmes
    DROP CONSTRAINT admission_test_state_programmes_state_format_check;

ALTER TABLE admission_test_state_programmes
    ADD CONSTRAINT admission_test_state_programmes_state_fkey
    FOREIGN KEY (state) REFERENCES us_states (usps_code) ON DELETE RESTRICT;

ALTER TABLE fafsa_filing_deadlines
    DROP CONSTRAINT fafsa_filing_deadlines_state_format_check;

ALTER TABLE fafsa_filing_deadlines
    ADD CONSTRAINT fafsa_filing_deadlines_state_fkey
    FOREIGN KEY (state) REFERENCES us_states (usps_code) ON DELETE RESTRICT;

COMMENT ON COLUMN admission_test_state_programmes.state IS
    'The USPS code of the state whose education agency published this '
    'programme, keyed into us_states (RFC 229). The interim format CHECK is '
    'dropped rather than left beside the key: a key is strictly stronger than '
    'a regex.';

COMMENT ON COLUMN fafsa_filing_deadlines.state IS
    'The USPS code of the state whose agency published this, or NULL for the '
    'FEDERAL row. Keyed into us_states (RFC 229); the interim format CHECK is '
    'dropped rather than left beside the key. Membership in the 59 published '
    'codes is UsStateCodes.parse''s job, in the generator and the seed test.';
