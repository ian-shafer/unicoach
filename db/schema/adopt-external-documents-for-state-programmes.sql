-- The state-programme corpus adopts external_documents (RFC 213, slice
-- test/05.1/external-documents).
--
-- admission_test_state_programmes landed with the citation shape RFC 140 uses
-- for the Common Data Set: `source_url TEXT NOT NULL` plus
-- `archive_url TEXT NULL`. That is one more private copy of a general idea, and
-- brief 0010 D26 is the decision to stop having them. The columns become two
-- foreign keys into the one table that holds a url and its link-rot state.
--
-- THE SHAPE IS THE LANDED SHAPE, NORMALISED, AND NOTHING MORE. One row cites
-- one page, with an optional archived address. No join table, no claims
-- vocabulary, no "which document backs which claim" -- all of that was drafted
-- for this slice and cut, because the case it served (a row whose claims come
-- from two documents) is not a case we have: Utah shipped from a single parent
-- fact sheet that carries every claim its row makes.
--
-- TWO GUARANTEES, STATED HERE BECAUSE THEY ARE WHY THIS IS SAFE:
--
--   * An archived address may not BE the page. An archive of itself is not an
--     archive of anything, it is the same link twice, and a coach told to
--     "offer both links" would print one url as two. The CHECK refuses it.
--   * An archived address with no page is IMPOSSIBLE by construction, because
--     source_document_id is NOT NULL. That is exactly the guarantee the old
--     `source_url NOT NULL` gave, kept rather than downgraded to a check.
--
-- THE BACKFILL IS THIS MIGRATION'S OWN WORK, read out of the columns it is
-- about to drop. It depends on no CSV, no script and no network, so a database
-- migrated today and one migrated next year get identical rows -- which is the
-- property a data migration hidden in a seed generator would not have.
--
-- The rows arrive with url_status 'unknown' and NO date, which is the truthful
-- "we never asked". The sweep that fills them in is a separate, GENERATED seed
-- (seed-external-document-link-status-2026.sql), written from a committed file
-- an operator produced with bin/check-link. Nothing here asks the network.

ALTER TABLE admission_test_state_programmes
    ADD COLUMN source_document_id  UUID NULL REFERENCES external_documents(id),
    ADD COLUMN archive_document_id UUID NULL REFERENCES external_documents(id);

-- Every url the corpus already cites becomes a document, source and archive
-- alike. ON CONFLICT because the same page may legitimately be cited by two
-- rows (a state re-read in a later school year cites the same page), and
-- because an archived address and a page are the same KIND of thing here.
INSERT INTO external_documents (url)
SELECT DISTINCT source_url FROM admission_test_state_programmes
UNION
SELECT DISTINCT archive_url FROM admission_test_state_programmes
WHERE archive_url IS NOT NULL
ON CONFLICT (url) DO NOTHING;

UPDATE admission_test_state_programmes p
SET source_document_id = d.id
FROM external_documents d
WHERE d.url = p.source_url;

UPDATE admission_test_state_programmes p
SET archive_document_id = d.id
FROM external_documents d
WHERE p.archive_url IS NOT NULL
  AND d.url = p.archive_url;

ALTER TABLE admission_test_state_programmes
    ALTER COLUMN source_document_id SET NOT NULL,
    ADD CONSTRAINT admission_test_state_programmes_archive_is_not_the_source_check
        CHECK (archive_document_id IS NULL
               OR archive_document_id <> source_document_id),
    DROP COLUMN source_url,
    DROP COLUMN archive_url;

COMMENT ON COLUMN admission_test_state_programmes.source_document_id IS
    'The state''s own page, read on checked_on (RFC 213). NOT NULL: a row that '
    'cites nothing is the uncitable row this corpus exists to prevent.';

COMMENT ON COLUMN admission_test_state_programmes.archive_document_id IS
    'The archived address the row was read through, where the state''s own page '
    'would not open for us -- Arizona, Delaware and New Hampshire return 403 or '
    'a bot check to every client we have (RFC 208). NULL where no archive was '
    'needed. Never equal to source_document_id, and never present without it.';
