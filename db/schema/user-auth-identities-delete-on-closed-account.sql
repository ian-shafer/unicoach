-- user_auth_identities: a closed account's federated identities may be deleted.
-- RFC 252 (family/09/the-deletion-partition), section 1b.
--
-- WHY. `user_auth_identities_provider_subject_idx` is a TOTAL unique index on
-- (provider, subject) (0018), and the table was append-only, so the identity row
-- of a CLOSED account kept its Apple or Google subject forever. A returning
-- sign-in resolved that row to the closed user and answered `account_disabled`:
-- a person who closed their account could never come back on the same sign-in.
--
-- The close (`AccountClosureService`) now deletes the closed account's
-- identities in the same transaction that soft-deletes the `users` row, so a
-- returning sign-in finds no identity and takes the first-time path. Deleting
-- the row also removes the provider email it carried.
--
-- THE GUARD keeps the table append-only for everybody else: a DELETE is
-- admitted only when the owning `users` row is soft-deleted, and refused for a
-- live account exactly as `prevent_log_delete()` refused every delete before.
-- An UPDATE is still refused by trigger_00 (0018), untouched here.
--
-- EXISTS, not `SELECT deleted_at INTO x`: an absent users row would leave x NULL
-- and `IF x IS NOT NULL` would then refuse for the wrong reason. The foreign key
-- makes an absent owner impossible anyway, and EXISTS says what is asked.

CREATE OR REPLACE FUNCTION prevent_live_user_auth_identities_delete()
RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM users u
         WHERE u.id = OLD.user_id AND u.deleted_at IS NOT NULL
    ) THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION
        'An auth identity may be deleted only for a closed account: identity [%], user [%].',
        OLD.id, OLD.user_id
        USING ERRCODE = 'P0001',
              CONSTRAINT = 'user_auth_identities_owner_closed';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER trigger_01_prevent_user_auth_identities_delete ON user_auth_identities;

CREATE TRIGGER trigger_01_prevent_user_auth_identities_delete
BEFORE DELETE ON user_auth_identities
FOR EACH ROW EXECUTE PROCEDURE prevent_live_user_auth_identities_delete();
