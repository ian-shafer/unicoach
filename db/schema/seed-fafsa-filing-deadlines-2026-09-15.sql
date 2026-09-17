-- GENERATED FILE -- do not edit.
--
-- Written by bin/fafsa-deadline-seed from db/seed/fafsa-deadlines/deadlines.csv.
-- The CSV is the source a human edits and reviews in a diff; this file is an
-- artifact of it. To change a row, change the CSV and re-run the script.
--
-- Read on 2026-09-15: 4 FAFSA filing deadline row(s), each with the publisher's
-- own page it was read from and the day a human read it. The corpus is append-only,
-- so a CORRECTION is a re-read -- which carries a new checked_on and therefore lands
-- as a NEW seed upserting on the natural key, never as an edit to this one.
--
-- Each row cites its documents by foreign key into external_documents (RFC 213). The
-- urls are upserted FIRST, and each id is SUBSELECTED by url, because the id is a
-- uuidv7 the database mints and a generated file cannot know it.
--
-- Each url carries the sweep bin/check-link answered for it: url_status has no default
-- and url_checked_on is NOT NULL (RFC 220), so a citation states its evidence or it
-- cannot be stored. The answers are committed beside the corpus in link-status.csv.

INSERT INTO external_documents (url, url_status, url_checked_on)
VALUES
    ('https://fsapartners.ed.gov/knowledge-center/library/electronic-announcements/2026-07-21/2027-28-fafsa-beta-testing-plan-updated-aug-25-2026', 'found', DATE '2026-09-16'),
    ('https://www.ed.gov/about/news/press-release/us-department-of-education-announces-earliest-fafsa-form-launch-program-history', 'found', DATE '2026-09-16'),
    ('https://www.federalregister.gov/documents/2026/06/22/2026-12462/2026-2027-award-year-deadline-dates-for-reports-and-other-records-associated-with-the-free', 'found', DATE '2026-09-16')
ON CONFLICT (url) DO NOTHING;

INSERT INTO fafsa_filing_deadlines
    (award_year, state, program, event, deadline_kind, deadline_date,
     deadline_as_published, publisher, source_document_id,
     archive_document_id, checked_on, expires_on)
VALUES
  (2026, NULL, 'Federal student aid', 'corrections_deadline', 'date', DATE '2027-09-11', '"The deadline date for the receipt of corrections, notices of change of address or institution, or requests for a duplicate FAFSA Submission Summary is September 11, 2027." Footnote 1: "The deadline for electronic transactions is 11:59 p.m. (Central Time) on the deadline date. Transmissions must be completed and accepted before 12:00 midnight to meet the deadline."', 'U.S. Department of Education', (SELECT id FROM external_documents WHERE url = 'https://www.federalregister.gov/documents/2026/06/22/2026-12462/2026-2027-award-year-deadline-dates-for-reports-and-other-records-associated-with-the-free'), NULL, DATE '2026-09-15', DATE '2027-09-15'),
  (2026, NULL, 'Federal student aid', 'final_deadline', 'date', DATE '2027-06-30', '"The deadline date for the receipt of a FAFSA form by the Department''s FAFSA Processing System (FPS) is June 30, 2027, regardless of the method that the applicant uses to submit the FAFSA form."', 'U.S. Department of Education', (SELECT id FROM external_documents WHERE url = 'https://www.federalregister.gov/documents/2026/06/22/2026-12462/2026-2027-award-year-deadline-dates-for-reports-and-other-records-associated-with-the-free'), NULL, DATE '2026-09-15', DATE '2027-09-15'),
  (2026, NULL, 'Federal student aid', 'form_opens', 'date', DATE '2025-09-24', 'September 24, 2025: "Today, the U.S. Department of Education (the Department) announced that the 2026-27 Free Application for Federal Student Aid (FAFSA) form is online and available, marking the earliest launch in the program''s history."', 'U.S. Department of Education', (SELECT id FROM external_documents WHERE url = 'https://www.ed.gov/about/news/press-release/us-department-of-education-announces-earliest-fafsa-form-launch-program-history'), NULL, DATE '2026-09-15', DATE '2027-09-15'),
  (2027, NULL, 'Federal student aid', 'form_opens', 'wording', NULL, '"The U.S. Department of Education (Department) is excited to preview its plans for beta testing the 2027-28 Free Application for Federal Student Aid (FAFSA) ahead of the public release by Oct. 1, 2026."', 'Federal Student Aid, U.S. Department of Education', (SELECT id FROM external_documents WHERE url = 'https://fsapartners.ed.gov/knowledge-center/library/electronic-announcements/2026-07-21/2027-28-fafsa-beta-testing-plan-updated-aug-25-2026'), NULL, DATE '2026-09-15', DATE '2027-09-15')
ON CONFLICT (award_year, state, program, event) DO UPDATE SET
    deadline_kind         = EXCLUDED.deadline_kind,
    deadline_date         = EXCLUDED.deadline_date,
    deadline_as_published = EXCLUDED.deadline_as_published,
    publisher             = EXCLUDED.publisher,
    source_document_id    = EXCLUDED.source_document_id,
    archive_document_id   = EXCLUDED.archive_document_id,
    checked_on            = EXCLUDED.checked_on,
    expires_on            = EXCLUDED.expires_on;
