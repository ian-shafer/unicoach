-- The testing calendar adopts external_documents (RFC 228, slice
-- test/05.2/one-home-for-every-citation, brief 0010 D26).
--
-- admission_test_administrations landed (RFC 197) with the citation shape the
-- Common Data Set once used: `dates_source_url TEXT NOT NULL` and
-- `score_release_source_url TEXT NULL`. The columns become two foreign keys
-- into the one table that holds a url and its link-rot state.
--
-- THE EVIDENCE IS THE ROW'S OWN READING DATE, on the rule RFC 220 settled for
-- every cited corpus: a person opened that page on checked_on and typed this
-- row from what it served, so the page was FOUND on that day. That is the same
-- statement ExternalDocumentsDao.citationIds makes for
-- admission_test_state_programmes (RFC 213) and fafsa_filing_deadlines (RFC
-- 215), and a second rule here would be a second answer to a settled question.
-- No network, no sweep, no invention: the document's date is a date the row
-- already carries.
--
-- The two https-only checks are NOT replaced. external_documents already
-- refuses a url with no scheme and accepts http:// as well as https://, on
-- Ian's own call at the RFC 208 gate ("prefer https, but not block insecure").
-- So the calendar stops being the one corpus that refuses an insecure
-- publisher page; the general rule arrives, no guarantee is lost.

ALTER TABLE admission_test_administrations
    ADD COLUMN dates_document_id         UUID NULL REFERENCES external_documents(id),
    ADD COLUMN score_release_document_id UUID NULL REFERENCES external_documents(id);

INSERT INTO external_documents (url, url_status, url_checked_on)
SELECT DISTINCT ON (url) url, 'found', checked_on
FROM (
    SELECT dates_source_url AS url, checked_on FROM admission_test_administrations
    UNION ALL
    SELECT score_release_source_url, checked_on FROM admission_test_administrations
    WHERE score_release_source_url IS NOT NULL
) cited
ORDER BY url, checked_on DESC
ON CONFLICT (url) DO NOTHING;

UPDATE admission_test_administrations a
SET dates_document_id = d.id
FROM external_documents d
WHERE d.url = a.dates_source_url;

UPDATE admission_test_administrations a
SET score_release_document_id = d.id
FROM external_documents d
WHERE a.score_release_source_url IS NOT NULL
  AND d.url = a.score_release_source_url;

ALTER TABLE admission_test_administrations
    ALTER COLUMN dates_document_id SET NOT NULL,
    -- The paired rule RFC 197 wrote, carried over unchanged: a score-release
    -- date and the page it was read from arrive together or not at all.
    ADD CONSTRAINT admission_test_administrations_score_release_document_check
        CHECK ((score_release_date IS NULL) = (score_release_document_id IS NULL)),
    DROP CONSTRAINT admission_test_administrations_score_release_citation_check,
    DROP CONSTRAINT admission_test_administrations_dates_source_url_check,
    DROP CONSTRAINT admission_test_administrations_score_release_url_check,
    DROP COLUMN dates_source_url,
    DROP COLUMN score_release_source_url;

COMMENT ON COLUMN admission_test_administrations.dates_document_id IS
    'The publisher page this sitting''s dates were read from (RFC 228). NOT '
    'NULL: a test date with no citation is not one this product will speak. '
    'The document''s own url_checked_on is this row''s checked_on.';

COMMENT ON COLUMN admission_test_administrations.score_release_document_id IS
    'The page the score-release date was read from (RFC 228). NULL exactly '
    'where score_release_date is NULL, and never present without a date: the '
    'pair travels together, as RFC 197 required of the two url columns this '
    'replaces.';
