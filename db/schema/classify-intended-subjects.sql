-- intended_subjects + _versions: what the person means to study is typed
-- pii.slug (RFC 263, slice deletion/01/the-classification). The shape is in
-- classify-users.sql.
--
-- pii.slug is a domain over public.slug, not TEXT, so the column keeps the slug
-- format its foreign key to subjects relies on.

ALTER TABLE intended_subjects
    ALTER COLUMN subject_slug TYPE pii.slug,
    ALTER COLUMN subject_slug DROP NOT NULL,
    ADD CONSTRAINT intended_subjects_subject_slug_present_while_live_check
        CHECK (deleted_at IS NOT NULL OR subject_slug IS NOT NULL);

ALTER TABLE intended_subjects_versions
    ALTER COLUMN subject_slug TYPE pii.slug,
    ALTER COLUMN subject_slug DROP NOT NULL,
    ADD CONSTRAINT intended_subjects_subject_slug_present_while_live_check
        CHECK (deleted_at IS NOT NULL OR subject_slug IS NOT NULL);
