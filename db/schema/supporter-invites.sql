-- RFC 232: Single-use supporter invite table for linking family members (brief 0014 D2/D7).
-- Neither a versioned aggregate nor an append-only log: its only mutations are compare-and-swap
-- setting consumed_at once on acceptance, or revoked_at once on revocation.
-- Modeled on verification_tokens (0014) and cost_report_shares (0073) -- only the SHA-256 hash
-- is stored; the raw token exists only in the invite link.

CREATE TABLE supporter_invites (
    id              UUID        NOT NULL PRIMARY KEY DEFAULT uuidv7(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    row_created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    -- Token hash: SHA-256 of raw secret token (hash at rest, raw only in link)
    token_hash      BYTEA       NOT NULL,

    -- Inviter identification: exactly one of student_id or supporter_id is populated
    inviter_user_id UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    inviter_role    TEXT        NOT NULL CHECK (inviter_role IN ('student', 'supporter')),
    -- Both endpoints CASCADE, stated on both sides: an invite is a pointer to
    -- the two parties, so a deleted party takes its pending invites with it.
    -- Left bare, supporter_id would default to NO ACTION and a stale invite
    -- would refuse the deletion of the supporter row it names, while the
    -- student side of the same table cleaned up silently.
    student_id      UUID        NULL REFERENCES students(id) ON DELETE CASCADE,
    supporter_id    UUID        NULL REFERENCES supporters(id) ON DELETE CASCADE,

    -- Relationship role offered (RFC 221 D22 vocabulary). The four values are
    -- owned by the Kotlin enum SupporterRelationship
    -- (db/src/main/kotlin/ed/unicoach/db/models): a member added there and not
    -- here is refused at run time by this CHECK, in this table and in the
    -- identical one on supporters-and-student-links.sql.
    relationship    TEXT        NOT NULL CHECK (relationship IN ('parent', 'guardian', 'grandparent', 'supporter')),

    expires_at      TIMESTAMPTZ NOT NULL,
    consumed_at     TIMESTAMPTZ NULL,
    revoked_at      TIMESTAMPTZ NULL,

    CONSTRAINT supporter_invites_inviter_endpoints_check CHECK (
        (inviter_role = 'student' AND student_id IS NOT NULL AND supporter_id IS NULL) OR
        (inviter_role = 'supporter' AND supporter_id IS NOT NULL AND student_id IS NULL)
    )
);

CREATE UNIQUE INDEX supporter_invites_token_hash_idx
    ON supporter_invites (token_hash);

CREATE INDEX supporter_invites_student_id_active_idx
    ON supporter_invites (student_id)
    WHERE consumed_at IS NULL AND revoked_at IS NULL;

CREATE INDEX supporter_invites_supporter_id_active_idx
    ON supporter_invites (supporter_id)
    WHERE consumed_at IS NULL AND revoked_at IS NULL;

CREATE INDEX supporter_invites_inviter_user_id_idx
    ON supporter_invites (inviter_user_id);
