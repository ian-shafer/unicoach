-- A citation carries evidence, or it cannot be stored (RFC 220, slice
-- test/05.4/no-citation-without-a-check, brief 0010 D28).
--
-- external_documents landed (RFC 213) with `url_status link_status NOT NULL
-- DEFAULT 'unknown'` and `url_checked_on DATE NULL`, so
--
--     INSERT INTO external_documents (url) VALUES ('https://example.com/x');
--
-- is legal and stores a citation with NO evidence at all -- no status anybody
-- chose, and no day anybody looked. Two paths did exactly that: the adopt
-- migration's own fill, and ExternalDocumentsDao.idOf, which minted a row for
-- any url handed to it. The guarantee belongs in the SCHEMA because that is the
-- only layer that can hold it for every writer -- a seed, a migration, a DAO, a
-- human at psql.
--
-- IT REFUSES; IT DOES NOT BACKFILL. A backfill would invent the evidence this
-- migration exists to require, and a made-up date is the check-that-never-ran
-- looking like one that passed (RFC 182) written into the table by us. So the
-- guard below raises, names the count, and names the tool that produces the
-- missing answer. Today it finds none: the corpus cites 43 distinct urls and
-- the committed sweep covers all 43 (34 `found`, 9 `unknown` WITH a date).
--
-- WHAT IS STILL SAYABLE. The three-state vocabulary is untouched. `unknown`
-- WITH a date -- we asked and got no usable answer (a timeout, a DNS fault, a
-- TLS fault, a host refusing us) -- is exactly as storable as it was, and it is
-- what nine of our rows say. What becomes unrepresentable is the fourth state:
-- a url nobody ever checked.
--
-- WHAT IT COSTS EVERY FUTURE CALLER, and this is deliberate: a url must be
-- swept with bin/check-link -- or otherwise answered for by whoever cites it --
-- BEFORE it can be stored. Brief 0012 (FAFSA) inherited it the same day: RFC
-- 215's fafsa_filing_deadlines landed while this migration was being written,
-- and its citation path states the deadline row's own reading date.
--
-- url_status is ALREADY NOT NULL. The hole in it was the DEFAULT, which filled
-- the column in for a caller who said nothing; so only the DEFAULT is dropped
-- here, and there is no redundant SET NOT NULL beside it. With no default, an
-- INSERT that names only `url` now fails on url_status's existing NOT NULL, and
-- one that names a status but no date fails on url_checked_on's new one.
--
-- THE PAIRED CHECK GOES. `CHECK (url_status = 'unknown' OR url_checked_on IS
-- NOT NULL)` is trivially true once the date is NOT NULL, and a constraint that
-- cannot fail is a constraint a reader has to reason about for nothing.

DO $$
DECLARE
    dateless BIGINT;
BEGIN
    SELECT COUNT(*) INTO dateless
    FROM external_documents
    WHERE url_checked_on IS NULL;

    IF dateless > 0 THEN
        -- Deferred from any statement context, so this text is the whole
        -- payload the operator gets: it names the count, what will NOT happen,
        -- and the command that produces the missing answer.
        RAISE EXCEPTION
            'external_documents holds [%] row(s) with no url_checked_on. This migration will not invent a date: sweep those urls with bin/check-link, record the result, and re-run.',
            dateless
            USING ERRCODE = 'P0001';
    END IF;
END;
$$;

ALTER TABLE external_documents
    ALTER COLUMN url_status DROP DEFAULT,
    ALTER COLUMN url_checked_on SET NOT NULL,
    DROP CONSTRAINT external_documents_checked_when_known;

COMMENT ON COLUMN external_documents.url_status IS
    'Whether the document was still served when we last looked (RFC 200): '
    'found, not_found, or unknown. No default (RFC 220): a caller states what '
    'it knows, because a defaulted status is how a check that never ran comes '
    'to look like one that passed. Recorded by an operator sweep at seed time '
    'and never at render -- a family must never wait on somebody else''s web '
    'server. Only not_found justifies dropping an anchor.';

COMMENT ON COLUMN external_documents.url_checked_on IS
    'The day url_status was established. NOT NULL (RFC 220): a url nobody '
    'checked cannot be stored. An unknown WITH a date is a check that ran and '
    'got no usable answer, which must never look like one that passed '
    '(RFC 182) -- that row is still perfectly storable, and nine of ours say '
    'exactly it.';
