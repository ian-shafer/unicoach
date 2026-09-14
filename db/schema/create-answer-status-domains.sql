-- One declinable-answer vocabulary per QUESTION SHAPE, declared once each
-- (RFC 209, brief 0010 D24 and brief 0011 D43/D44).
--
-- `('unanswered','answered','declined')` is written four times in this schema
-- today -- 0046 twice, 0070, 0078 -- and the pairing rule
-- `(value IS NOT NULL) = (status = 'answered')` four more. 0054 already states
-- the argument against that, about its own vocabulary:
--
--   "The vocabulary is one value shared by every factor column, so it is
--    declared ONCE as a DOMAIN over TEXT ... Adding or renaming a rating is one
--    ALTER DOMAIN plus the Kotlin enum, not eighteen edits."
--
-- TWO spellings, not one. A money question has three possible answers; a
-- question about what you intend to study has a fourth, "I don't know yet",
-- which is a real and durable answer at 16 and is not the same sentence as
-- "I'd rather not say". One four-value domain everywhere would make `undecided`
-- legal on an income band unless every money column carried a narrowing CHECK
-- of its own -- a shared superset with four private corrections, which is the
-- duplication a domain exists to remove, wearing the domain's clothes.
--
-- Two spellings and not three-values-everywhere, because of the direction of
-- regret: a four-state vocabulary can be COLLAPSED later if we decide the
-- fourth state was noise, and a three-state one cannot be expanded to recover a
-- distinction it never stored. Cheap-to-reverse is only true when the
-- information still exists.
--
-- The narrow spelling is DERIVED from the wide one rather than declared beside
-- it, so the three shared words exist exactly ONCE and "the money spelling is
-- the subject spelling minus `undecided`" is a fact the database enforces
-- instead of a comment two migrations apart. The inversion is deliberate and
-- worth naming: the ordinary case (money) is the derived one, over a base that
-- includes a value it can never hold. We accept that to buy the containment.
--
-- Measured before it was adopted: a column retyped to the nested domain
-- advertises `domain_name = answer_status` (the narrow one, not the base); an
-- `undecided` on such a column is refused as
-- `violates check constraint "answer_status_excludes_undecided"`, and a word
-- outside the vocabulary entirely as `"answer_status_or_undecided_check"` --
-- both name the column's own domain, so the diagnostic stays actionable.

CREATE DOMAIN answer_status_or_undecided AS TEXT
    CONSTRAINT answer_status_or_undecided_check
    CHECK (VALUE IN ('unanswered', 'answered', 'declined', 'undecided'));

-- The money spelling: the same words, minus the one a money question cannot
-- have. Written as a subtraction so the two can never drift apart.
CREATE DOMAIN answer_status AS answer_status_or_undecided
    CONSTRAINT answer_status_excludes_undecided
    CHECK (VALUE <> 'undecided');

-- The pairing rule: a value is present exactly when the question was answered,
-- so a declined or unanswered field can never smuggle a stale value to a
-- consumer. Two columns, so it cannot be a domain -- but it can be one function
-- instead of four copies of the same expression.
--
-- Declared over TEXT, not over either domain. A domain-typed parameter is
-- re-validated on call, so a function typed `answer_status` could not serve the
-- wider spelling: passing `undecided` would fail with "value for domain
-- answer_status violates check constraint". Over the base type it serves both
-- (measured), and the vocabulary is still enforced by each COLUMN's own domain.
CREATE FUNCTION value_iff_answered(status TEXT, has_value BOOLEAN)
RETURNS BOOLEAN LANGUAGE sql IMMUTABLE AS
$$ SELECT has_value = (status = 'answered') $$;

-- ---------------------------------------------------------------------------
-- money_profiles + money_profiles_versions -- the three-value vocabulary
-- ---------------------------------------------------------------------------
-- Eight columns across two tables, not four across one: the history table
-- carries the same four status columns, and retyping only the live table would
-- leave the vocabulary written twice over.
--
-- Retyping a TEXT column to a domain over TEXT REWRITES the table (measured:
-- a 20,000-row probe moved its filenode). Both tables are one row per student
-- and per edit, and the rewrite is inside this migration's transaction.

ALTER TABLE money_profiles
    DROP CONSTRAINT money_profiles_income_band_status_check,
    DROP CONSTRAINT money_profiles_residency_status_check,
    DROP CONSTRAINT money_profiles_living_plan_status_check,
    DROP CONSTRAINT money_profiles_dependency_status_check,
    DROP CONSTRAINT money_profiles_income_band_value_iff_answered_check,
    DROP CONSTRAINT money_profiles_residency_value_iff_answered_check,
    DROP CONSTRAINT money_profiles_living_plan_value_iff_answered_check,
    DROP CONSTRAINT money_profiles_dependency_value_iff_answered_check;

ALTER TABLE money_profiles
    ALTER COLUMN income_band_status TYPE answer_status,
    ALTER COLUMN residency_status   TYPE answer_status,
    ALTER COLUMN living_plan_status TYPE answer_status,
    ALTER COLUMN dependency_status  TYPE answer_status;

ALTER TABLE money_profiles
    ADD CONSTRAINT money_profiles_income_band_value_iff_answered_check
        CHECK (value_iff_answered(income_band_status, income_band IS NOT NULL)),
    ADD CONSTRAINT money_profiles_residency_value_iff_answered_check
        CHECK (value_iff_answered(residency_status, residency_state IS NOT NULL)),
    ADD CONSTRAINT money_profiles_living_plan_value_iff_answered_check
        CHECK (value_iff_answered(living_plan_status, living_plan IS NOT NULL)),
    ADD CONSTRAINT money_profiles_dependency_value_iff_answered_check
        CHECK (value_iff_answered(dependency_status, dependency IS NOT NULL));

-- The DEFAULT 'unanswered' on each column is untouched and stays legal: the
-- literal is a member of the domain.
ALTER TABLE money_profiles_versions
    ALTER COLUMN income_band_status TYPE answer_status,
    ALTER COLUMN residency_status   TYPE answer_status,
    ALTER COLUMN living_plan_status TYPE answer_status,
    ALTER COLUMN dependency_status  TYPE answer_status;

-- ---------------------------------------------------------------------------
-- intended_subject_status -- the four-value spelling, NOT retyped here
-- ---------------------------------------------------------------------------
-- RFC 205 creates that table with its own four-value CHECK, deliberately and as
-- a single NAMED constraint, because this domain did not exist when it was
-- written. It had not landed when this migration did (checked at this run's
-- final rebase: no intended_subject table in `main`), and a migration must
-- never name a table that is not there.
--
-- So the handover is a follow-on, and it is two statements:
--
--   ALTER TABLE intended_subject_status DROP CONSTRAINT intended_subject_status_status_check;
--   ALTER TABLE intended_subject_status ALTER COLUMN status TYPE answer_status_or_undecided;
--   ALTER TABLE intended_subject_status_versions ALTER COLUMN status TYPE answer_status_or_undecided;
--
-- No data changes. Until it runs, `answer_status_or_undecided` has no column of
-- its own -- it is the base the money spelling is derived FROM, which is the
-- whole reason the three shared words are written once.
