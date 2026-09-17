-- policy_parameters adopts external_documents (RFC 228, slice
-- test/05.2/one-home-for-every-citation, brief 0010 D26).
--
-- 0077 seeded these rows with a bare `source_url TEXT NOT NULL` and no reading
-- date at all, so the evidence had to be ESTABLISHED rather than carried
-- across. The five verdicts below are one sweep, run by hand with
--
--     nix develop -c bin/check-link -f <the five urls>
--     nix develop -c bin/check-link -f <the five urls> -g
--
-- on 2026-09-17, read by RFC 200's vocabulary (2xx found, 404/410 not_found,
-- anything else unknown WITH the date we asked). Both passes answered 200 for
-- all five. They are literals rather than a generated seed because five
-- hand-typed rows in a migration are reviewable in exactly the diff a CSV would
-- be, and a generator for five rows is a mechanism for its own sake (D28).

ALTER TABLE policy_parameters
    ADD COLUMN source_document_id UUID NULL REFERENCES external_documents(id);

INSERT INTO external_documents (url, url_status, url_checked_on)
VALUES
    ('https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2025-2026/application-and-verification-guide/ch3-student-aid-index-sai-and-pell-grant-eligibility', 'found', DATE '2026-09-17'),
    ('https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2026-2027/application-and-verification-guide/ch3-student-aid-index-sai-and-pell-grant-eligibility', 'found', DATE '2026-09-17'),
    ('https://fsapartners.ed.gov/knowledge-center/library/dear-colleague-letters/2025-01-31/2025-2026-federal-pell-grant-maximum-and-minimum-award-amounts-updated-may-29-2025', 'found', DATE '2026-09-17'),
    ('https://fsapartners.ed.gov/knowledge-center/library/dear-colleague-letters/2026-01-30/2026-27-federal-pell-grant-maximum-and-minimum-award-amounts', 'found', DATE '2026-09-17'),
    ('https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2025-2026/vol8/ch4-annual-and-aggregate-loan-limits', 'found', DATE '2026-09-17')
ON CONFLICT (url) DO NOTHING;

UPDATE policy_parameters p
SET source_document_id = d.id
FROM external_documents d
WHERE d.url = p.source_url;

-- Any row this migration could not link cites a url the five literals above do
-- not cover -- a parameter seeded by a later migration, or by hand. It is
-- refused rather than linked to an unchecked document: a date is established by
-- a sweep, never by this migration.
DO $$
DECLARE
    unlinked BIGINT;
BEGIN
    SELECT COUNT(*) INTO unlinked FROM policy_parameters WHERE source_document_id IS NULL;

    IF unlinked > 0 THEN
        RAISE EXCEPTION
            'policy_parameters holds [%] row(s) citing a url this migration has no check for. Sweep them with bin/check-link, add them here, and re-run.',
            unlinked
            USING ERRCODE = 'P0001';
    END IF;
END;
$$;

ALTER TABLE policy_parameters
    ALTER COLUMN source_document_id SET NOT NULL,
    DROP COLUMN source_url;

COMMENT ON COLUMN policy_parameters.source_document_id IS
    'The Federal Student Aid page this parameter was read from (RFC 228). NOT '
    'NULL: a federal figure with no citation is not one this product will '
    'speak. The old source_url CHECK (non-empty) is now implied by the foreign '
    'key into external_documents, whose own checks are stronger.';
