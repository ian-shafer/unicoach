-- Seed the v3 fit-lens query system prompt into the immutable system_prompts
-- catalog (RFC 147). v2 carried a HAND-WRITTEN codebook in its prose — the
-- IPEDS region numbers, the urbanization locale numbers, and the control
-- codes, transcribed by a person and verified by nothing. It was the only
-- transcription of any federal codebook in this repo, and it was already a
-- duplicate of the copy 0032's v1 carried.
--
-- RFC 147 makes the boundary speak words instead. `record_college_query` and
-- `search_colleges` now share ONE filter vocabulary
-- (`CollegeQueryVocabulary`), whose schema offers `region` as a published
-- region name read from the LOADED `ipeds_regions` table, `locale_type` /
-- `locale_detail` as the two published halves of the NCES locale label, and
-- `control` as the same word a result renders ("public",
-- "private_nonprofit", "private_for_profit"). Each field carries its own
-- description — what a CIP prefix is, that states are two-letter USPS codes,
-- that a rate is a share 0..1, that a net price is in whole dollars — so
-- every fact the deleted sentence stated is now stated where the model reads
-- the field, from the code that parses it.
--
-- So v3 is v2 with EXACTLY ONE SPAN DELETED and nothing added: the sentence
-- beginning "The coded fields use these codebooks:" and ending "net price is
-- in dollars. ". The prefix before it and the suffix after it are
-- byte-identical, which is what `SystemPromptCatalogTest` asserts — a
-- deletion is the whole change, so no new copy needs approving.
--
-- Mirrors 0026/0032/0037/0044/0047-0050/0053/0058: architect-approved copy
-- stored as a single concatenated string (|| is layout only; the body is
-- verbatim and untrimmed). This seed is never edited; a later change is a new
-- version row. service.conf pins v3.
--
-- The v2 row remains in the catalog, as every superseded prompt does — but it
-- is NOT a rollback path on its own, and this comment says so rather than
-- promising the usual one-env-var revert. v2's prose asks the model for an
-- integer `region`, integer `control` codes, and a `locales` array; the shared
-- word vocabulary refuses all three (`locales` is not a field name any more,
-- and FitLensService now REFUSES unknown keys rather than ignoring them, so it
-- fails loudly instead of quietly searching with no locale filter). Reverting
-- this pin requires reverting RFC 147's boundary change with it.
--
-- `fit_lens_reason` is deliberately NOT re-versioned: its v2 body names no
-- codes at all (it reasons over rendered matches), so there is nothing in it
-- to delete. Nor is the `coach` prompt, whose v8 body has never carried a
-- codebook — the prose lived only here and in this prompt's own v1.

INSERT INTO system_prompts (name, version, body)
VALUES (
  'fit_lens_query',
  'v3',
  'You are a college coach preparing to search a real college dataset '
  || 'for a single student. You are given the student''s active claims '
  || '(the coach''s distilled beliefs about them) and an exclusion set of '
  || 'colleges the student already knows (their list plus schools already '
  || 'suggested). Translate what the claims imply about fit into a '
  || 'structured query over the dataset by calling the '
  || 'record_college_query tool with only the filter fields you are '
  || 'confident about; omit any axis you are unsure of, and never name a '
  || 'specific school. Do not set a result limit; the coach sets it. Base '
  || 'the query only on the supplied claims; never invent facts about the '
  || 'student.'
);
