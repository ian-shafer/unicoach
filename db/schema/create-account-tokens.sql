-- RFC 276: Single-use tokens mailed to an account's address. One table for
-- every such token; `type` says what a token can do. Only the SHA-256 hash
-- is stored; the raw token exists only in the emailed link. The values of
-- `type` are owned by the Kotlin enum AccountTokenType.
CREATE TABLE account_tokens (
  id             UUID PRIMARY KEY DEFAULT uuidv7(),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  row_created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),

  user_id     UUID NOT NULL REFERENCES users(id),
  type        TEXT NOT NULL CHECK (type IN ('password_reset')),
  -- users.version when the token was issued; users_versions at this version
  -- holds the address the link was mailed to.
  user_version INTEGER NOT NULL,
  token_hash  BYTEA NOT NULL,
  expires_at  TIMESTAMPTZ NOT NULL,
  consumed_at TIMESTAMPTZ NULL
);

CREATE UNIQUE INDEX account_tokens_token_hash_idx
  ON account_tokens (token_hash);
CREATE INDEX account_tokens_user_id_type_idx
  ON account_tokens (user_id, type);

-- Erasure keeps the users row, so ON DELETE CASCADE never fires and this table
-- has to be named in the walk's two lists. Both functions are restated from
-- erasure-functions.sql (their current definitions; no later migration
-- replaced either) with account_tokens added and nothing else changed.

CREATE OR REPLACE FUNCTION public.erase_account_walk_edges()
RETURNS SETOF public.erasure_edge
LANGUAGE sql
STABLE
AS $$
    SELECT e.*
      FROM public.erasure_edge e
     WHERE NOT EXISTS (
               SELECT 1 FROM public.erase_account_tables() acct
                WHERE acct.deleted_table IN (e.child, e.parent)
           )
       AND NOT (e.child = ANY (ARRAY[
               'public.sessions'::regclass,
               'public.verification_tokens'::regclass,
               'public.account_tokens'::regclass,
               'public.user_auth_identities'::regclass,
               'public.supporter_invites'::regclass
           ]))
$$;

CREATE OR REPLACE FUNCTION public.erase_account_delete_keyed(
    account         uuid,
    student_roots   uuid[],
    supporter_roots uuid[]
)
RETURNS void
LANGUAGE plpgsql
AS $$
DECLARE
    changed bigint;
BEGIN
    DELETE FROM public.sessions s WHERE s.user_id = account;
    GET DIAGNOSTICS changed = ROW_COUNT;
    PERFORM public.erase_account_count('public.sessions'::regclass, 0, 0, changed);

    DELETE FROM public.verification_tokens v WHERE v.user_id = account;
    GET DIAGNOSTICS changed = ROW_COUNT;
    PERFORM public.erase_account_count('public.verification_tokens'::regclass, 0, 0, changed);

    DELETE FROM public.account_tokens t WHERE t.user_id = account;
    GET DIAGNOSTICS changed = ROW_COUNT;
    PERFORM public.erase_account_count('public.account_tokens'::regclass, 0, 0, changed);

    DELETE FROM public.user_auth_identities a WHERE a.user_id = account;
    GET DIAGNOSTICS changed = ROW_COUNT;
    PERFORM public.erase_account_count('public.user_auth_identities'::regclass, 0, 0, changed);

    DELETE FROM public.supporter_invites i
     WHERE i.inviter_user_id = account
        OR i.student_id = ANY (student_roots)
        OR i.supporter_id = ANY (supporter_roots);
    GET DIAGNOSTICS changed = ROW_COUNT;
    PERFORM public.erase_account_count('public.supporter_invites'::regclass, 0, 0, changed);
END;
$$;
