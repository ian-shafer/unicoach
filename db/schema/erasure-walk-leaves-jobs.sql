-- The walk leaves jobs alone, and returns at once for an erased account
-- (RFC 267 §2, slice deletion/04/the-sweep, brief 0002 D13).
--
-- WHY. Erasure now runs as a queue job (ERASE_ACCOUNT) that, in the same
-- transaction as the walk, enqueues the confirmation email. A walk that
-- deleted the jobs naming the account would delete its own erase job and, on
-- a re-run, a confirmation not yet sent. A jobs row now outlives the erasure
-- until the queue's reaper deletes it, 30 days after it finished, like every
-- other finished job (D13).
--
-- And a walk erases every row keyed on a root by its live key OR its sibling,
-- so a second call would erase the deletion receipt -- the email_sends row
-- keyed on deleted_user_id that the erasure itself wrote. A walk is one
-- transaction, so an account with a deleted_users row was erased whole: the
-- second call returns at once, before the declaration, and writes nothing.
--
-- erase_account is restated from erasure-functions.sql with those two changes
-- only: step 6 deletes D10's four keyed kinds and no jobs, so step 1's two
-- reads (which only fed the jobs predicate) go; and the early RETURN follows
-- the lock, the two refusals and the root resolution. The three functions only the jobs deletion
-- used are dropped.
--
-- ORDER OF WORK, all inside one call. Steps keep their numbers from
-- erasure-functions.sql; step 1 is gone. Before step 2: lock the users row,
-- refuse an unknown id (erase_account_exists) or a live account
-- (erase_account_closed), resolve the roots (erase_account_one_root), and
-- return no rows for an account already erased.
--   2. declare the erasure; 3. insert the deleted rows; 4. move the keys;
--   5. collect what the account owns, then erase it;
--   6. delete D10's rows: sessions, verification_tokens, user_auth_identities
--      keyed on the account, and supporter_invites keyed on any root;
--   7. clear the declaration; 8. return one row per table with a non-zero
--      count.

CREATE OR REPLACE FUNCTION public.erase_account(account uuid)  -- a users.id
RETURNS TABLE (
    table_name text,   -- a table the walk touched
    moved      bigint, -- rows whose live account key moved to its deleted sibling
    erased     bigint, -- rows soft_delete_entity changed
    deleted    bigint  -- rows physically deleted (D10)
)
LANGUAGE plpgsql
AS $$
DECLARE
    closed_at       timestamptz;
    student_root    uuid;
    supporter_root  uuid;
    student_roots   uuid[];
    supporter_roots uuid[];
BEGIN
    SELECT u.deleted_at INTO closed_at FROM public.users u WHERE u.id = account FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'erase_account: there is no account [%]', account
            USING ERRCODE = 'P0001', CONSTRAINT = 'erase_account_exists';
    END IF;
    IF closed_at IS NULL THEN
        RAISE EXCEPTION 'erase_account: account [%] is live; close it before erasing it', account
            USING ERRCODE = 'P0001', CONSTRAINT = 'erase_account_closed';
    END IF;

    student_root := public.erase_account_one_root(account, 'public.students'::regclass, ARRAY(
        SELECT s.id FROM public.students s
         WHERE s.user_id = account OR s.deleted_user_id = account));
    supporter_root := public.erase_account_one_root(account, 'public.supporters'::regclass, ARRAY(
        SELECT s.id FROM public.supporters s
         WHERE s.user_id = account OR s.deleted_user_id = account));
    student_roots := pg_catalog.array_remove(ARRAY[student_root], NULL);
    supporter_roots := pg_catalog.array_remove(ARRAY[supporter_root], NULL);

    -- Erased whole by an earlier call: nothing is left to do, and what has
    -- been written against the erased account since (its receipt) is not this
    -- call's to touch. After the roots are resolved, so a surplus students or
    -- supporters row still raises erase_account_one_root on a re-run rather
    -- than hiding behind the return.
    IF EXISTS (SELECT 1 FROM public.deleted_users du WHERE du.id = account) THEN
        RETURN;
    END IF;

    -- 2. Declare the erasure.
    PERFORM pg_catalog.set_config('unicoach.erasure_account', account::text, true);
    PERFORM public.erase_account_prepare_walk();
    PERFORM public.erase_account_record_roots(account, student_root, supporter_root);

    -- 3. The deleted rows (D1, D9).
    PERFORM public.erase_account_insert_deleted_rows(account, student_root, supporter_root);

    -- 4. Move the keys.
    PERFORM public.erase_account_move_keys();

    -- 5. What the account owns, then erase it.
    PERFORM public.erase_account_collect_owned();
    PERFORM public.erase_account_erase_owned();

    -- 6. D10's rows.
    PERFORM public.erase_account_delete_keyed(account, student_roots, supporter_roots);

    -- 7. Close the declaration.
    PERFORM pg_catalog.set_config('unicoach.erasure_account', '', true);

    -- 8. One row per table with a non-zero count.
    RETURN QUERY SELECT * FROM public.erase_account_tally();
END;
$$;

DROP FUNCTION public.erase_account_delete_jobs(uuid[], text[], text[]);
DROP FUNCTION public.erase_account_addresses(uuid);
DROP FUNCTION public.erase_account_token_hashes(uuid);
