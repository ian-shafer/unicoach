-- The guard arm: each guard that would refuse a write the erasure walk makes
-- admits it while the declared erasure names a closed account (RFC 266 §5,
-- slice deletion/03/the-walk, decisions D-B and D-C).
--
-- WHY. erase_account (erasure-functions.sql) moves keys these guards freeze and
-- NULLs columns on tables these guards keep append-only. Each guard gains ONE
-- line, as its first statement:
--
--     IF public.erasure_admits() THEN RETURN NEW; END IF;
--
-- erasure_admits() is true only while the walk's transaction-local marker,
-- unicoach.erasure_account, names a users row whose deleted_at is set. The walk
-- sets the marker before its first write and clears it before it returns, so
-- the rest of the caller's transaction is guarded again.
--
-- ONLY THE GUARDS THE WALK TRIPS gain the arm (D-B). prevent_immutable_updates
-- and prevent_physical_timestamp_update guard id, created_at and
-- row_created_at, which the walk never writes; prevent_physical_delete and
-- prevent_log_delete guard DELETEs the walk never makes (its physical deletes,
-- D10, are of tables with no delete guard, or with one that already admits a
-- closed account: user_auth_identities). Leaving them unchanged keeps every
-- write erasure does not need refused, even inside an erasure.
-- enforce_versioning is not a guard here: the walk bumps version itself.
--
-- Each function is restated from its latest definition by ORDER position, with
-- the arm the only change:
--
--   prevent_log_update                                 0006.create-coaching-conversations.sql
--   prevent_convos_party_updates                       convos-party.sql
--   prevent_supporter_immutable_updates                supporters-and-student-links.sql
--   prevent_supporter_student_link_immutable_updates   supporters-and-student-links.sql
--   prevent_supporter_link_request_column_updates      supporter-link-requests.sql
--   prevent_sharing_permission_column_updates          create-sharing-permissions.sql
--   prevent_intended_subject_immutable_updates         intended-subjects.sql
--   prevent_intended_subject_status_immutable_updates  intended-subjects.sql

-- prevent_log_update: refuses any UPDATE on the append-only tables; the walk
-- NULLs their pii.* columns, stamps deleted_at, and moves their account keys.
CREATE OR REPLACE FUNCTION public.prevent_log_update()
RETURNS TRIGGER AS $$
BEGIN
    IF public.erasure_admits() THEN RETURN NEW; END IF;
    RAISE EXCEPTION 'Log rows are append-only and cannot be updated.'
    USING ERRCODE = 'P0001';
END;
$$ LANGUAGE plpgsql;

-- prevent_convos_party_updates: refuses student_id and supporter_id moved onto their deleted siblings.
CREATE OR REPLACE FUNCTION public.prevent_convos_party_updates()
RETURNS TRIGGER AS $$
BEGIN
    IF public.erasure_admits() THEN RETURN NEW; END IF;
    IF NEW.party IS DISTINCT FROM OLD.party THEN
        RAISE EXCEPTION 'The party field is immutable.'
            USING ERRCODE = 'P0001',
                  COLUMN = 'party',
                  DETAIL = format('convos row [%s] party [%s] -> [%s]', OLD.id, OLD.party, NEW.party);
    END IF;
    IF NEW.supporter_id IS DISTINCT FROM OLD.supporter_id THEN
        RAISE EXCEPTION 'The supporter_id field is immutable.'
            USING ERRCODE = 'P0001',
                  COLUMN = 'supporter_id',
                  DETAIL = format('convos row [%s] supporter_id [%s] -> [%s]', OLD.id, OLD.supporter_id, NEW.supporter_id);
    END IF;
    IF NEW.student_id IS DISTINCT FROM OLD.student_id THEN
        RAISE EXCEPTION 'The student_id field is immutable.'
            USING ERRCODE = 'P0001',
                  COLUMN = 'student_id',
                  DETAIL = format('convos row [%s] student_id [%s] -> [%s]', OLD.id, OLD.student_id, NEW.student_id);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- prevent_supporter_immutable_updates: refuses supporters.user_id moved.
CREATE OR REPLACE FUNCTION public.prevent_supporter_immutable_updates()
RETURNS TRIGGER AS $$
BEGIN
    IF public.erasure_admits() THEN RETURN NEW; END IF;
    IF NEW.user_id IS DISTINCT FROM OLD.user_id THEN
        RAISE EXCEPTION 'The user_id field is immutable.' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- prevent_supporter_student_link_immutable_updates: refuses student_id and supporter_id moved.
CREATE OR REPLACE FUNCTION public.prevent_supporter_student_link_immutable_updates()
RETURNS TRIGGER AS $$
BEGIN
    IF public.erasure_admits() THEN RETURN NEW; END IF;
    IF NEW.supporter_id IS DISTINCT FROM OLD.supporter_id THEN
        RAISE EXCEPTION 'The supporter_id field is immutable.' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.student_id IS DISTINCT FROM OLD.student_id THEN
        RAISE EXCEPTION 'The student_id field is immutable.' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- prevent_supporter_link_request_column_updates: refuses student_id and supporter_id moved.
CREATE OR REPLACE FUNCTION public.prevent_supporter_link_request_column_updates()
RETURNS TRIGGER AS $$
BEGIN
    IF public.erasure_admits() THEN RETURN NEW; END IF;
    IF NEW.supporter_id IS DISTINCT FROM OLD.supporter_id THEN
        RAISE EXCEPTION 'Supporter link request [%]: supporter_id is immutable ([%] -> [%]).',
            OLD.id, OLD.supporter_id, NEW.supporter_id USING ERRCODE = 'P0001';
    END IF;
    IF NEW.student_id IS DISTINCT FROM OLD.student_id THEN
        RAISE EXCEPTION 'Supporter link request [%]: student_id is immutable ([%] -> [%]).',
            OLD.id, OLD.student_id, NEW.student_id USING ERRCODE = 'P0001';
    END IF;
    IF NEW.cost_report_share_id IS DISTINCT FROM OLD.cost_report_share_id THEN
        RAISE EXCEPTION 'Supporter link request [%]: cost_report_share_id is immutable ([%] -> [%]).',
            OLD.id, OLD.cost_report_share_id, NEW.cost_report_share_id USING ERRCODE = 'P0001';
    END IF;
    IF OLD.resolution IS NOT NULL AND (
        NEW.resolution IS DISTINCT FROM OLD.resolution OR
        NEW.resolved_at IS DISTINCT FROM OLD.resolved_at OR
        NEW.link_id IS DISTINCT FROM OLD.link_id
    ) THEN
        RAISE EXCEPTION
            'Supporter link request [%] is already resolved as [%] at [%] (link [%]); refused write: resolution [%], resolved_at [%], link [%].',
            OLD.id, OLD.resolution, OLD.resolved_at, OLD.link_id, NEW.resolution, NEW.resolved_at, NEW.link_id
            USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- prevent_sharing_permission_column_updates: refuses student_id moved.
CREATE OR REPLACE FUNCTION public.prevent_sharing_permission_column_updates()
RETURNS TRIGGER AS $$
BEGIN
    IF public.erasure_admits() THEN RETURN NEW; END IF;
    IF NEW.student_id IS DISTINCT FROM OLD.student_id THEN
        RAISE EXCEPTION 'The student_id field is immutable.'
            USING ERRCODE = 'P0001',
                  COLUMN = 'student_id',
                  DETAIL = format('sharing_permissions row [%s] student_id [%s] -> [%s]', OLD.id, OLD.student_id, NEW.student_id);
    END IF;
    IF NEW.permission IS DISTINCT FROM OLD.permission THEN
        RAISE EXCEPTION 'The permission field is immutable.'
            USING ERRCODE = 'P0001',
                  COLUMN = 'permission',
                  DETAIL = format('sharing_permissions row [%s] permission [%s] -> [%s]', OLD.id, OLD.permission, NEW.permission);
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- prevent_intended_subject_immutable_updates: refuses student_id moved, and subject_slug (pii.slug) set NULL.
CREATE OR REPLACE FUNCTION public.prevent_intended_subject_immutable_updates()
RETURNS TRIGGER AS $$
BEGIN
    IF public.erasure_admits() THEN RETURN NEW; END IF;
    IF NEW.student_id IS DISTINCT FROM OLD.student_id THEN
        RAISE EXCEPTION 'The student_id field is immutable.' USING ERRCODE = 'P0001';
    END IF;
    IF NEW.subject_slug IS DISTINCT FROM OLD.subject_slug THEN
        RAISE EXCEPTION 'The subject_slug field is immutable.' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- prevent_intended_subject_status_immutable_updates: refuses student_id moved.
CREATE OR REPLACE FUNCTION public.prevent_intended_subject_status_immutable_updates()
RETURNS TRIGGER AS $$
BEGIN
    IF public.erasure_admits() THEN RETURN NEW; END IF;
    IF NEW.student_id IS DISTINCT FROM OLD.student_id THEN
        RAISE EXCEPTION 'The student_id field is immutable.' USING ERRCODE = 'P0001';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
