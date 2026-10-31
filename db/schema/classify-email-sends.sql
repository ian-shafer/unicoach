-- email_sends: the mail we sent a person is typed pii.*, the log gains
-- deleted_at, and every send gains the account it was sent to (RFC 263, slice
-- deletion/01/the-classification, brief 0002 D3). The classification shape is
-- in classify-users.sql.
--
-- WHY THE KEY. email_sends was keyless: a row held a person's address and the
-- mail we wrote them, and nothing tied it to an account, so no erasure walk
-- could reach it (D3: no table holding a person's data is keyless).
--
-- user_id is NULLABLE. NULL means "no account is known to own this send", and
-- it arises three ways:
--   1. the mail went to an address with no account. No template does that
--      today, but the job payload is documented as serving recipients who are
--      not users;
--   2. the send was logged before this migration and the backfill below found
--      no account, or more than one, that ever held its address;
--   3. the job was queued before RFC 263 deployed, so its payload carries no
--      account id and the worker decodes it as null.
-- A NULL row does not say which. Account erasure never reaches a NULL-keyed
-- row, because no account owns it; the backfill reports how many case 2 left.
--
-- sender_email is not personal: it is our own address.

ALTER TABLE email_sends
    ADD COLUMN deleted_at TIMESTAMPTZ NULL,
    ADD COLUMN user_id UUID NULL REFERENCES users(id),
    ALTER COLUMN recipient_email TYPE pii.text,
    ALTER COLUMN subject         TYPE pii.text,
    ALTER COLUMN body            TYPE pii.text,
    ALTER COLUMN error_message   TYPE pii.text,
    ALTER COLUMN recipient_email DROP NOT NULL,
    ALTER COLUMN subject         DROP NOT NULL,
    ALTER COLUMN body            DROP NOT NULL,
    ADD CONSTRAINT email_sends_recipient_email_present_while_live_check
        CHECK (deleted_at IS NOT NULL OR recipient_email IS NOT NULL),
    ADD CONSTRAINT email_sends_subject_present_while_live_check
        CHECK (deleted_at IS NOT NULL OR subject IS NOT NULL),
    ADD CONSTRAINT email_sends_body_present_while_live_check
        CHECK (deleted_at IS NOT NULL OR body IS NOT NULL);

-- THE BACKFILL (RFC 263 D-B). A send already logged is keyed to the account
-- whose address it went to, when exactly ONE account has ever held that address
-- -- now (users) or before an email change (users_versions), so mail sent to an
-- old address is still found. An address no account held, or more than one
-- did, stays NULL: guessing would attach one person's mail to another.
--
-- Addresses are compared lower-cased: users.email is stored lower-case (its
-- own CHECK), and the domain half of an address is case-insensitive.
--
-- Each address's holders are counted once, into a temp table the UPDATE and
-- the report below both read, so the two cannot disagree on what "exactly one"
-- means. ON COMMIT DROP: it is gone with this migration's transaction.
CREATE TEMP TABLE email_sends_address_holders ON COMMIT DROP AS
SELECT email,
       count(DISTINCT user_id) AS holders,
       (array_agg(DISTINCT user_id))[1] AS user_id
FROM (
    SELECT email, id AS user_id FROM users
    UNION
    SELECT email, id AS user_id FROM users_versions
) address_holder
GROUP BY email;

-- The log's update guard is off for this one statement only, the 0035 and
-- create-partial-date-storage.sql precedent: the backfill is a migration
-- completing the row's shape, not an edit to a logged fact.
ALTER TABLE email_sends DISABLE TRIGGER trigger_00_prevent_email_sends_update;

UPDATE email_sends es
   SET user_id = h.user_id
  FROM email_sends_address_holders h
 WHERE lower(es.recipient_email) = h.email
   AND h.holders = 1;

ALTER TABLE email_sends ENABLE TRIGGER trigger_00_prevent_email_sends_update;

-- The backfill is best-effort by design, so it says what it did: the deploy log
-- carries how many logged sends it keyed and how many it left NULL, split by
-- why. Always printed, zeros included, so a silent log means the migration did
-- not run rather than that nothing was skipped.
DO $$
DECLARE
    keyed     bigint;
    no_holder bigint;
    ambiguous bigint;
BEGIN
    SELECT count(*) FILTER (WHERE es.user_id IS NOT NULL),
           count(*) FILTER (WHERE es.user_id IS NULL AND h.email IS NULL),
           count(*) FILTER (WHERE es.user_id IS NULL AND h.holders > 1)
      INTO keyed, no_holder, ambiguous
      FROM email_sends es
      LEFT JOIN email_sends_address_holders h ON h.email = lower(es.recipient_email);

    RAISE NOTICE
        'email_sends backfill: [%] logged send(s) keyed to an account; [%] left NULL because no account ever held the address; [%] left NULL because more than one account did.',
        keyed, no_holder, ambiguous;
END;
$$;
