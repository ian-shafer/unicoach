-- GENERATED FILE -- do not edit.
--
-- Written by bin/test-calendar-seed from db/seed/test-calendar/administrations.csv.
-- The CSV is the source a human edits and reviews in a diff; this file is an
-- artifact of it. To change a date, change the CSV and re-run the script.
--
-- Cycle 2026-27: 15 SAT and ACT administrations, each with the publisher page it was
-- read from and the date a human read it. Upserts on the natural key, so a
-- corrected row lands as a new generated seed and never as an edit to this one.

INSERT INTO admission_test_administrations
    (test, cycle, test_date, registration_deadline, late_registration_deadline,
     score_release_date, dates_source_url, score_release_source_url,
     checked_on, expires_on)
VALUES
  ('sat', '2026-27', DATE '2026-08-22', DATE '2026-08-07', DATE '2026-08-11', DATE '2026-09-04', 'https://satsuite.collegeboard.org/sat/dates-deadlines', 'https://satsuite.collegeboard.org/scores/score-release-dates', DATE '2026-09-11', DATE '2027-07-31'),
  ('sat', '2026-27', DATE '2026-09-12', DATE '2026-08-28', DATE '2026-09-01', DATE '2026-09-25', 'https://satsuite.collegeboard.org/sat/dates-deadlines', 'https://satsuite.collegeboard.org/scores/score-release-dates', DATE '2026-09-11', DATE '2027-07-31'),
  ('sat', '2026-27', DATE '2026-10-03', DATE '2026-09-18', DATE '2026-09-22', DATE '2026-10-16', 'https://satsuite.collegeboard.org/sat/dates-deadlines', 'https://satsuite.collegeboard.org/scores/score-release-dates', DATE '2026-09-11', DATE '2027-07-31'),
  ('sat', '2026-27', DATE '2026-11-07', DATE '2026-10-23', DATE '2026-10-27', DATE '2026-11-20', 'https://satsuite.collegeboard.org/sat/dates-deadlines', 'https://satsuite.collegeboard.org/scores/score-release-dates', DATE '2026-09-11', DATE '2027-07-31'),
  ('sat', '2026-27', DATE '2026-12-05', DATE '2026-11-20', DATE '2026-11-24', DATE '2026-12-18', 'https://satsuite.collegeboard.org/sat/dates-deadlines', 'https://satsuite.collegeboard.org/scores/score-release-dates', DATE '2026-09-11', DATE '2027-07-31'),
  ('sat', '2026-27', DATE '2027-03-06', DATE '2027-02-19', DATE '2027-02-23', NULL, 'https://satsuite.collegeboard.org/sat/dates-deadlines', NULL, DATE '2026-09-11', DATE '2027-07-31'),
  ('sat', '2026-27', DATE '2027-05-01', DATE '2027-04-16', DATE '2027-04-20', NULL, 'https://satsuite.collegeboard.org/sat/dates-deadlines', NULL, DATE '2026-09-11', DATE '2027-07-31'),
  ('sat', '2026-27', DATE '2027-06-05', DATE '2027-05-21', DATE '2027-05-25', NULL, 'https://satsuite.collegeboard.org/sat/dates-deadlines', NULL, DATE '2026-09-11', DATE '2027-07-31'),
  ('act', '2026-27', DATE '2026-09-19', DATE '2026-08-14', DATE '2026-09-01', DATE '2026-10-06', 'https://www.act.org/content/act/en/products-and-services/the-act/registration/test-dates.html', 'https://www.act.org/content/act/en/products-and-services/the-act/registration/test-dates.html', DATE '2026-09-11', DATE '2027-07-31'),
  ('act', '2026-27', DATE '2026-10-17', DATE '2026-09-11', DATE '2026-09-29', DATE '2026-10-27', 'https://www.act.org/content/act/en/products-and-services/the-act/registration/test-dates.html', 'https://www.act.org/content/act/en/products-and-services/the-act/registration/test-dates.html', DATE '2026-09-11', DATE '2027-07-31'),
  ('act', '2026-27', DATE '2026-12-12', DATE '2026-11-06', DATE '2026-11-29', DATE '2026-12-22', 'https://www.act.org/content/act/en/products-and-services/the-act/registration/test-dates.html', 'https://www.act.org/content/act/en/products-and-services/the-act/registration/test-dates.html', DATE '2026-09-11', DATE '2027-07-31'),
  ('act', '2026-27', DATE '2027-02-27', DATE '2027-01-22', DATE '2027-02-09', DATE '2027-03-16', 'https://www.act.org/content/act/en/products-and-services/the-act/registration/test-dates.html', 'https://www.act.org/content/act/en/products-and-services/the-act/registration/test-dates.html', DATE '2026-09-11', DATE '2027-07-31'),
  ('act', '2026-27', DATE '2027-04-10', DATE '2027-03-05', DATE '2027-03-23', DATE '2027-04-20', 'https://www.act.org/content/act/en/products-and-services/the-act/registration/test-dates.html', 'https://www.act.org/content/act/en/products-and-services/the-act/registration/test-dates.html', DATE '2026-09-11', DATE '2027-07-31'),
  ('act', '2026-27', DATE '2027-06-12', DATE '2027-05-07', DATE '2027-05-25', DATE '2027-06-23', 'https://www.act.org/content/act/en/products-and-services/the-act/registration/test-dates.html', 'https://www.act.org/content/act/en/products-and-services/the-act/registration/test-dates.html', DATE '2026-09-11', DATE '2027-07-31'),
  ('act', '2026-27', DATE '2027-07-10', DATE '2027-06-04', DATE '2027-06-22', DATE '2027-07-20', 'https://www.act.org/content/act/en/products-and-services/the-act/registration/test-dates.html', 'https://www.act.org/content/act/en/products-and-services/the-act/registration/test-dates.html', DATE '2026-09-11', DATE '2027-07-31')
ON CONFLICT (test, test_date) DO UPDATE SET
    cycle                      = EXCLUDED.cycle,
    registration_deadline      = EXCLUDED.registration_deadline,
    late_registration_deadline = EXCLUDED.late_registration_deadline,
    score_release_date         = EXCLUDED.score_release_date,
    dates_source_url           = EXCLUDED.dates_source_url,
    score_release_source_url   = EXCLUDED.score_release_source_url,
    checked_on                 = EXCLUDED.checked_on,
    expires_on                 = EXCLUDED.expires_on;
