-- GENERATED FILE -- do not edit.
--
-- Written by bin/national-merit-seed from db/seed/national-merit/cycles.csv.
-- The CSV is the source a human edits and reviews in a diff; this file is an
-- artifact of it. To change a row, change the CSV and re-run the script.
--
-- Read on 2026-09-25: 2 National Merit competition cycle(s), each with the
-- publishers' own pages it was read from and the day a human read them. A landed seed
-- is replayed on every db-reset and so never changes: a CORRECTION is a re-read, which
-- carries a new checked_on and therefore lands as a NEW seed upserting on the
-- competition year.
--
-- ROUTE A (RFC 243): this corpus is keyed by a competition year and nothing else, so a
-- migration can seed it -- and must, because `bin/test` does db-reset and never ingests.
-- "What this test is and when it is" can never depend on an import having run.
--
-- NO CUTOFF SCORE IS STORED, by design. NMSC publishes none in advance and says the
-- qualifying score varies by state and by year; what is here is HOW each bar is set
-- plus NMSC's own sentence saying it is not published.
--
-- Each row cites FOUR documents by foreign key into external_documents (RFC 213/228):
-- the sponsor's page and the administrator's, and either one's optional mirror. The
-- urls are upserted FIRST, and each id is SUBSELECTED by url, because the id is a
-- uuidv7 the database mints and a generated file cannot know it.
--
-- Each url carries the sweep bin/check-link answered for it: url_status has no default
-- and url_checked_on is NOT NULL (RFC 220), so a citation states its evidence or it
-- cannot be stored. The answers are committed beside the corpus in link-status.csv.

INSERT INTO external_documents (url, url_status, url_checked_on)
VALUES
    ('https://satsuite.collegeboard.org/psat-nmsqt/scores/understanding-scores', 'found', DATE '2026-09-25'),
    ('https://satsuite.collegeboard.org/scores/score-release-dates', 'found', DATE '2026-09-25'),
    ('https://www.nationalmerit.org/s/1758/start.aspx?sid=1758&gid=2&pgid=1745', 'found', DATE '2026-09-25'),
    ('https://www.nationalmerit.org/s/1758/start.aspx?sid=1758&gid=2&pgid=1878', 'found', DATE '2026-09-25')
ON CONFLICT (url) DO NOTHING;

INSERT INTO national_merit_cycles
    (competition_year, qualifying_grade, window_opens_on, window_closes_on,
     window_as_published, alternate_entry_deadline, alternate_entry_contact,
     alternate_entry_as_published, selection_index_low,
     selection_index_high, semifinalist_cutoff_basis,
     commended_cutoff_basis, cutoff_as_published,
     score_visibility_as_published, test_takers, met_entry_requirements,
     high_scorers_screened, commended, semifinalists, finalists, winners,
     funnel_as_published, nmsc_award_usd, nmsc_award_as_published,
     sponsor_cap_as_published, publisher, source_document_id,
     archive_document_id, administrator_publisher,
     administrator_document_id, administrator_archive_document_id,
     checked_on, expires_on)
VALUES
  (2028, 11, DATE '2026-10-01', DATE '2026-10-30', 'The fall in-school testing window is October 1–30, 2026.', DATE '2027-04-01', 'ae@nmerit.net', 'If the PSAT/NMSQT testing window has closed, write to NMSC as soon as possible but no later than April 1, 2027, to request another route of entry to the 2028 National Merit Scholarship Program. … Submit your request via email to ae@nmerit.net or by mail to: National Merit Scholarship Corporation, Attn: Scholarship Administration, 1560 Sherman Avenue, Suite 200, Evanston, IL 60201-4897. Your request must be received or postmarked on or before April 1, 2027, for your request to be considered.', 48, 228, 'state_representational', 'national', 'They will be named on the basis of a nationally applied Selection Index qualifying score that may vary from year to year. … As a result of this process, Semifinalist qualifying scores vary from state to state and from year to year, but the scores of all Semifinalists are extremely high.', 'If my child takes the PSAT/NMSQT, will colleges get their scores? No, colleges won''t receive your child''s actual test scores.', 3500000, 1200000, 50000, 34000, 16000, 15000, 6700, 'It is expected that about 3.5 million students will take the PSAT/NMSQT in 2026, and approximately 1.2 million of them will meet requirements to enter this program. … 1,200,000 Entrants. … 50,000 High Scorers. … 34,000 Commended Students. … 16,000 Semifinalists. … 15,000 Finalists. … 6,700 Merit Scholarship® winners.', 2500, 'National Merit® $2500 Scholarships provide a single payment of $2,500.', 'Officials of each sponsor institution choose award recipients from among Finalists who have been accepted for admission and have informed NMSC that the sponsor college or university is their first choice. Each college sponsor chooses the annual payment their institution offers to award recipients, within a range of $500 to $2,000 per year.', 'National Merit Scholarship Corporation', (SELECT id FROM external_documents WHERE url = 'https://www.nationalmerit.org/s/1758/start.aspx?sid=1758&gid=2&pgid=1745'), NULL, 'College Board', (SELECT id FROM external_documents WHERE url = 'https://satsuite.collegeboard.org/scores/score-release-dates'), NULL, DATE '2026-09-25', DATE '2027-07-31'),
  (2029, 11, NULL, NULL, 'They must take the PSAT/NMSQT again in 2027 (when they are juniors) to enter the competition that will end when scholarships are awarded in 2029, the year they will complete high school and enter college.', NULL, NULL, NULL, 48, 228, 'state_representational', 'national', 'They will be named on the basis of a nationally applied Selection Index qualifying score that may vary from year to year. … As a result of this process, Semifinalist qualifying scores vary from state to state and from year to year, but the scores of all Semifinalists are extremely high.', 'If my child takes the PSAT/NMSQT, will colleges get their scores? No, colleges won''t receive your child''s actual test scores.', NULL, NULL, NULL, NULL, NULL, NULL, NULL, 'United States high school students enter the National Merit Scholarship Program by taking the Preliminary SAT/National Merit Scholarship Qualifying Test (PSAT/NMSQT®), which serves as an initial screen of over 1.2 million entrants each year, and by meeting published program entry and participation requirements.', NULL, NULL, NULL, 'National Merit Scholarship Corporation', (SELECT id FROM external_documents WHERE url = 'https://www.nationalmerit.org/s/1758/start.aspx?sid=1758&gid=2&pgid=1878'), NULL, 'College Board', (SELECT id FROM external_documents WHERE url = 'https://satsuite.collegeboard.org/psat-nmsqt/scores/understanding-scores'), NULL, DATE '2026-09-25', DATE '2027-07-31')
ON CONFLICT (competition_year) DO UPDATE SET
    qualifying_grade                  = EXCLUDED.qualifying_grade,
    window_opens_on                   = EXCLUDED.window_opens_on,
    window_closes_on                  = EXCLUDED.window_closes_on,
    window_as_published               = EXCLUDED.window_as_published,
    alternate_entry_deadline          = EXCLUDED.alternate_entry_deadline,
    alternate_entry_contact           = EXCLUDED.alternate_entry_contact,
    alternate_entry_as_published      = EXCLUDED.alternate_entry_as_published,
    selection_index_low               = EXCLUDED.selection_index_low,
    selection_index_high              = EXCLUDED.selection_index_high,
    semifinalist_cutoff_basis         = EXCLUDED.semifinalist_cutoff_basis,
    commended_cutoff_basis            = EXCLUDED.commended_cutoff_basis,
    cutoff_as_published               = EXCLUDED.cutoff_as_published,
    score_visibility_as_published     = EXCLUDED.score_visibility_as_published,
    test_takers                       = EXCLUDED.test_takers,
    met_entry_requirements            = EXCLUDED.met_entry_requirements,
    high_scorers_screened             = EXCLUDED.high_scorers_screened,
    commended                         = EXCLUDED.commended,
    semifinalists                     = EXCLUDED.semifinalists,
    finalists                         = EXCLUDED.finalists,
    winners                           = EXCLUDED.winners,
    funnel_as_published               = EXCLUDED.funnel_as_published,
    nmsc_award_usd                    = EXCLUDED.nmsc_award_usd,
    nmsc_award_as_published           = EXCLUDED.nmsc_award_as_published,
    sponsor_cap_as_published          = EXCLUDED.sponsor_cap_as_published,
    publisher                         = EXCLUDED.publisher,
    source_document_id                = EXCLUDED.source_document_id,
    archive_document_id               = EXCLUDED.archive_document_id,
    administrator_publisher           = EXCLUDED.administrator_publisher,
    administrator_document_id         = EXCLUDED.administrator_document_id,
    administrator_archive_document_id = EXCLUDED.administrator_archive_document_id,
    checked_on                        = EXCLUDED.checked_on,
    expires_on                        = EXCLUDED.expires_on;
