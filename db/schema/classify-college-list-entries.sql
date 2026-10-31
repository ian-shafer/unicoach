-- college_list_entries + _versions: which college is on the person's list,
-- how it went, why, and where they would live are typed pii.* (RFC 263, slice
-- deletion/01/the-classification). The shape is in classify-users.sql.
--
-- A structured answer the person gave is personal (D-A): on an erased row,
-- "this account had college X at status admitted" is still something they told
-- us. reasons and living_plan are already nullable, and their CHECKs admit
-- NULL; status's IN-list does too.

ALTER TABLE college_list_entries
    ALTER COLUMN college_id  TYPE pii.uuid,
    ALTER COLUMN status      TYPE pii.text,
    ALTER COLUMN reasons     TYPE pii.text,
    ALTER COLUMN living_plan TYPE pii.text,
    ALTER COLUMN college_id DROP NOT NULL,
    ALTER COLUMN status     DROP NOT NULL,
    ADD CONSTRAINT college_list_entries_college_id_present_while_live_check
        CHECK (deleted_at IS NOT NULL OR college_id IS NOT NULL),
    ADD CONSTRAINT college_list_entries_status_present_while_live_check
        CHECK (deleted_at IS NOT NULL OR status IS NOT NULL);

ALTER TABLE college_list_entries_versions
    ALTER COLUMN college_id  TYPE pii.uuid,
    ALTER COLUMN status      TYPE pii.text,
    ALTER COLUMN reasons     TYPE pii.text,
    ALTER COLUMN living_plan TYPE pii.text,
    ALTER COLUMN college_id DROP NOT NULL,
    ALTER COLUMN status     DROP NOT NULL,
    ADD CONSTRAINT college_list_entries_college_id_present_while_live_check
        CHECK (deleted_at IS NOT NULL OR college_id IS NOT NULL),
    ADD CONSTRAINT college_list_entries_status_present_while_live_check
        CHECK (deleted_at IS NOT NULL OR status IS NOT NULL);
