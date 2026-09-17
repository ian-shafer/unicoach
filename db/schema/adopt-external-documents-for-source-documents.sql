-- The Common Data Set corpus adopts external_documents (RFC 228, slice
-- test/05.2/one-home-for-every-citation, brief 0010 D26).
--
-- source_documents was itself the first attempt to de-duplicate a url (RFC 170
-- D13): three CDS fact tables carried their own (source_url, archive_url) pair
-- and 0087 moved them onto one college-keyed row. This finishes the move. The
-- urls become foreign keys, and the link-rot columns RFC 200 added here go with
-- them -- there is one home for "is this url still served", and it is not here.
--
-- IT REFUSES; IT DOES NOT INVENT (RFC 220, and this is the first of the three
-- adoptions in this slice to inherit it). Every document it creates carries the
-- evidence the row it came from already holds. Where a row holds none, this
-- migration says so and stops, or leaves a NULLABLE mirror link unlinked, and
-- never writes a date nobody established.

ALTER TABLE source_documents
    ADD COLUMN external_document_id         UUID NULL REFERENCES external_documents(id),
    ADD COLUMN archive_external_document_id UUID NULL REFERENCES external_documents(id);

-- A page with no recorded check cannot become a document: url_checked_on is NOT
-- NULL since RFC 220, so there is no honest row to write. Our own committed
-- corpus has none -- the committed sweep covers all 441 pages -- and a database
-- that has one is told which tool produces the missing answer.
DO $$
DECLARE
    unchecked BIGINT;
BEGIN
    SELECT COUNT(*) INTO unchecked
    FROM source_documents
    WHERE source_url_checked_on IS NULL;

    IF unchecked > 0 THEN
        RAISE EXCEPTION
            'source_documents holds [%] row(s) whose source_url was never checked. This migration will not invent a date: run bin/cds-link-sweep, re-run the CDS ingest, and migrate again.',
            unchecked
            USING ERRCODE = 'P0001';
    END IF;
END;
$$;

-- The page, with the sweep's own verdict. DISTINCT ON because one filing backs
-- several colleges (one University of Washington filing backs three campuses),
-- and the newest check of a url is the one that is true about it.
INSERT INTO external_documents (url, url_status, url_checked_on)
SELECT DISTINCT ON (source_url) source_url, source_url_status, source_url_checked_on
FROM source_documents
ORDER BY source_url, source_url_checked_on DESC
ON CONFLICT (url) DO NOTHING;

UPDATE source_documents s
SET external_document_id = d.id
FROM external_documents d
WHERE d.url = s.source_url;

-- THE MIRROR IS RE-LINKED ONLY WHERE SOMEBODY HAS ALREADY ASKED ABOUT IT. The
-- committed corpus cites 446 mirror addresses and, until RFC 228, none of them
-- was ever swept, because bin/cds-link-check harvested one column. This
-- migration links a mirror to a document that EXISTS -- the committed sweep
-- covers them at the next ingest, and an operator may seed them first; for the
-- rest it leaves the nullable column NULL and says how many, because the
-- alternative is writing a status nobody established.
UPDATE source_documents s
SET archive_external_document_id = d.id
FROM external_documents d
WHERE s.archive_url IS NOT NULL
  AND d.url = s.archive_url;

DO $$
DECLARE
    dropped BIGINT;
BEGIN
    SELECT COUNT(*) INTO dropped
    FROM source_documents
    WHERE archive_url IS NOT NULL
      AND archive_external_document_id IS NULL;

    IF dropped > 0 THEN
        RAISE NOTICE
            '[%] mirror address(es) had no checked document and were left unlinked. They return with the next CDS ingest, which writes them from db/seed/cds/link-status.csv.',
            dropped;
    END IF;
END;
$$;

ALTER TABLE source_documents
    ALTER COLUMN external_document_id SET NOT NULL,
    ADD CONSTRAINT source_documents_archive_is_not_the_source_check
        CHECK (archive_external_document_id IS NULL
               OR archive_external_document_id <> external_document_id),
    DROP CONSTRAINT source_documents_checked_when_known,
    DROP COLUMN source_url,
    DROP COLUMN archive_url,
    DROP COLUMN source_url_status,
    DROP COLUMN source_url_checked_on;

COMMENT ON COLUMN source_documents.external_document_id IS
    'The college''s own Common Data Set filing (RFC 228). NOT NULL: this row IS '
    'a citation, and a citation that cites nothing is the thing the table '
    'exists to prevent. Named external_document_id and not source_document_id '
    'because every CDS fact table already carries a source_document_id that '
    'points HERE -- two different tables, two different meanings, one name '
    'would be a trap.';

COMMENT ON COLUMN source_documents.archive_external_document_id IS
    'The tidy third-party mirror of the same filing (www.collegedata.fyi), '
    'where one exists. NULL where none does, or where none has been checked '
    'yet. Never equal to external_document_id.';
