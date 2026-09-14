-- GENERATED FILE -- do not edit.
--
-- Written by bin/test-state-programme-seed from db/seed/test-state-programmes/programmes.csv.
-- The CSV is the source a human edits and reviews in a diff; this file is an
-- artifact of it. To change a row, change the CSV and re-run the script.
--
-- Reading session 2026: 36 state school-day programme row(s), each with the state
-- page it was read from and the day a human read it. The corpus is append-only,
-- so a later reading lands as a NEW seed and never as an edit to this one.

INSERT INTO admission_test_state_programmes
    (state, school_year, school_year_basis, participation, school_must_offer,
     test_chooser, test, lowest_grade, highest_grade, state_funded,
     source_url, archive_url, checked_on, expires_on)
VALUES
  ('AL', 2026, 'reading_year', 'required', NULL, 'state', 'act', 11, 11, NULL, 'https://www.alabamaachieves.org/assessment/', NULL, DATE '2026-09-14', DATE '2027-07-31'),
  ('AR', 2025, 'stated_by_source', 'offered', NULL, 'student', NULL, 11, 11, TRUE, 'https://dese.ade.arkansas.gov/Offices/public-school-accountability/assessment/college--career-readiness-assessments', NULL, DATE '2026-09-14', DATE '2027-07-31'),
  ('AZ', 2025, 'stated_by_source', 'required', NULL, 'state', 'act', 11, 11, NULL, 'https://www.azed.gov/assessment/act', 'http://web.archive.org/web/20260314052200/https://www.azed.gov/assessment/act', DATE '2026-09-14', DATE '2027-07-31'),
  ('CO', 2026, 'stated_by_source', 'required', NULL, 'state', 'sat', 11, 11, TRUE, 'https://ed.cde.state.co.us/assessment/sat-psat', NULL, DATE '2026-09-14', DATE '2027-07-31'),
  ('CT', 2026, 'stated_by_source', 'required', NULL, 'state', 'sat', 11, 11, TRUE, 'https://portal.ct.gov/sde/student-assessment/main-assessment/student-assessment/summative-assessment---end-of-year', NULL, DATE '2026-09-14', DATE '2027-07-31'),
  ('DC', 2025, 'stated_by_source', 'offered', NULL, 'state', 'sat', 11, 12, TRUE, 'https://osse.dc.gov/page/sat-school-testing', NULL, DATE '2026-09-14', DATE '2027-07-31'),
  ('DE', 2026, 'reading_year', 'required', NULL, 'state', 'sat', 11, 11, NULL, 'https://education.delaware.gov/educators/academic-support/standards-and-assessments/testing_calendar/', 'http://web.archive.org/web/20251108115859/https://education.delaware.gov/educators/academic-support/standards-and-assessments/testing_calendar/', DATE '2026-09-14', DATE '2027-07-31'),
  ('HI', 2025, 'stated_by_source', 'required', NULL, 'state', 'act', 11, 11, TRUE, 'https://hawaiipublicschools.org/academics/act-testing/', 'http://web.archive.org/web/20260814170517/https://hawaiipublicschools.org/academics/act-testing/', DATE '2026-09-14', DATE '2027-07-31'),
  ('ID', 2026, 'stated_by_source', 'offered', NULL, 'state', 'sat', 11, 11, NULL, 'https://www.sde.idaho.gov/wp-content/uploads/2026/06/Statewide-Assessment-Window.pdf', NULL, DATE '2026-09-14', DATE '2027-07-31'),
  ('IL', 2026, 'stated_by_source', 'required', NULL, 'state', 'act', 11, 11, TRUE, 'https://www.isbe.net/Documents/State-Assessment-Dates-2026-27.pdf', NULL, DATE '2026-09-14', DATE '2027-07-31'),
  ('IN', 2026, 'stated_by_source', 'required', NULL, 'state', 'sat', 11, 11, NULL, 'https://www.in.gov/doe/students/assessment/high-school-assessment', NULL, DATE '2026-09-14', DATE '2027-07-31'),
  ('KS', 2026, 'stated_by_source', 'offered', NULL, 'state', 'act', 11, 12, TRUE, 'https://ksrevisor.gov/statutes/chapters/ch72/072_051_0079.html', 'http://web.archive.org/web/20250724085032/https://ksrevisor.gov/statutes/chapters/ch72/072_051_0079.html', DATE '2026-09-14', DATE '2027-07-31'),
  ('KY', 2026, 'stated_by_source', 'required', NULL, 'state', 'sat', 11, 11, TRUE, 'https://www.education.ky.gov/AA/Assessments/Pages/College_Admissions_Exam.aspx', NULL, DATE '2026-09-14', DATE '2027-07-31'),
  ('LA', 2026, 'stated_by_source', 'required', NULL, 'state', 'act', 11, 11, TRUE, 'https://doe.louisiana.gov/docs/default-source/assessment/2026-2027-detailed-assessment-calendar.pdf?sfvrsn=e13942d5_6', NULL, DATE '2026-09-14', DATE '2027-07-31'),
  ('MD', 2025, 'stated_by_source', 'offered', NULL, 'district', NULL, NULL, NULL, NULL, 'https://marylandpublicschools.org/programs/Documents/Testing/SAT-School-Day-Administration-2026-A.pdf', NULL, DATE '2026-09-14', DATE '2027-07-31'),
  ('MI', 2025, 'stated_by_source', 'required', NULL, 'state', 'sat', 11, 11, NULL, 'https://www.michigan.gov/mde/services/student-assessment/michigan-merit-examination-mme', NULL, DATE '2026-09-14', DATE '2027-07-31'),
  ('MN', 2026, 'reading_year', 'offered', NULL, 'district', NULL, 11, 12, FALSE, 'https://www.revisor.mn.gov/statutes/cite/120B.30', NULL, DATE '2026-09-14', DATE '2027-07-31'),
  ('MS', 2026, 'reading_year', 'required', NULL, 'state', 'act', 11, 11, TRUE, 'https://www.mdek12.org/studentassessment/mississippi-college-and-career-testing/', NULL, DATE '2026-09-14', DATE '2027-07-31'),
  ('MT', 2026, 'stated_by_source', 'required', NULL, 'state', 'act', 11, 11, NULL, 'https://opi.mt.gov/Leadership/Assessment-Accountability/MontCAS/MontCAS-Required-Assessments', NULL, DATE '2026-09-14', DATE '2027-07-31'),
  ('NC', 2026, 'reading_year', 'required', NULL, 'state', 'act', 11, 11, NULL, 'https://www.dpi.nc.gov/districts-schools/accountability-and-testing/school-accountability-and-reporting/act-reports', NULL, DATE '2026-09-14', DATE '2027-07-31'),
  ('NE', 2026, 'stated_by_source', 'required', NULL, 'state', 'act', 11, 11, TRUE, 'https://www.education.ne.gov/assessment/act/', NULL, DATE '2026-09-14', DATE '2027-07-31'),
  ('NH', 2026, 'reading_year', 'required', NULL, 'state', 'sat', 11, 11, TRUE, 'https://www.education.nh.gov/sites/g/files/ehbemt326/files/inline-documents/nh_sat_school_day_faqs.pdf', 'https://web.archive.org/web/20230202182648/https://www.education.nh.gov/sites/g/files/ehbemt326/files/inline-documents/nh_sat_school_day_faqs.pdf', DATE '2026-09-14', DATE '2027-07-31'),
  ('NM', 2026, 'stated_by_source', 'required', NULL, 'state', 'sat', 11, 11, TRUE, 'https://web.ped.nm.gov/wp-content/uploads/2026/07/SAT-Fact-Sheet-2026-27_2.pdf', NULL, DATE '2026-09-14', DATE '2027-07-31'),
  ('NV', 2026, 'reading_year', 'required', NULL, 'state', 'act', 11, 11, TRUE, 'https://doe.nv.gov/offices/office-of-assessment-data-and-accountability-management-adam/office-of-assessments/college-and-career-readiness-assessment-act', NULL, DATE '2026-09-14', DATE '2027-07-31'),
  ('OH', 2026, 'stated_by_source', 'required', NULL, 'district', NULL, 11, 11, TRUE, 'https://education.ohio.gov/Topics/Testing/State-Funded-ACT-Test', NULL, DATE '2026-09-14', DATE '2027-07-31'),
  ('OK', 2026, 'reading_year', 'required', NULL, 'state', 'act', 11, 11, NULL, 'https://oklahoma.gov/education/services/assessments/college-and-career-readiness-assessments.html', 'http://web.archive.org/web/20260813111427/https://oklahoma.gov/education/services/assessments/college-and-career-readiness-assessments.html', DATE '2026-09-14', DATE '2027-07-31'),
  ('RI', 2026, 'reading_year', 'required', NULL, 'state', 'sat', 11, 11, NULL, 'https://ride.ri.gov/instruction-assessment/assessment/psat-and-sat', NULL, DATE '2026-09-14', DATE '2027-07-31'),
  ('SC', 2026, 'stated_by_source', 'offered', NULL, 'student', NULL, 11, 11, TRUE, 'https://ed.sc.gov/tests/high/college-entrance-exams/', NULL, DATE '2026-09-14', DATE '2027-07-31'),
  ('SD', 2026, 'stated_by_source', 'required', NULL, 'state', 'act', 11, 11, TRUE, 'https://doe.sd.gov/assessment/', NULL, DATE '2026-09-14', DATE '2027-07-31'),
  ('TN', 2026, 'reading_year', 'required', NULL, 'district', NULL, 11, 11, TRUE, 'https://www.tn.gov/education/districts/lea-operations/assessment/act-sat.html', NULL, DATE '2026-09-14', DATE '2027-07-31'),
  ('TX', 2025, 'stated_by_source', 'offered', NULL, 'student', NULL, 11, 12, TRUE, 'https://tea.texas.gov/student-readiness-and-high-school/college-career-and-military-prep/sat', NULL, DATE '2026-09-14', DATE '2027-07-31'),
  ('UT', 2026, 'reading_year', 'required', NULL, 'state', 'act', 11, 11, TRUE, 'https://www.schools.utah.gov/assessment/_assessment_/_parent_resources_/25WhatParentsNeedToKnowAboutACT.pdf', NULL, DATE '2026-09-14', DATE '2027-07-31'),
  ('VA', 2026, 'reading_year', 'offered', TRUE, 'state', 'sat', 11, 12, NULL, 'https://law.lis.virginia.gov/vacode/title22.1/chapter7/section22.1-79.10/', NULL, DATE '2026-09-14', DATE '2027-07-31'),
  ('WI', 2026, 'reading_year', 'required', NULL, 'state', 'act', 11, 11, NULL, 'https://dpi.wi.gov/assessment/act', NULL, DATE '2026-09-14', DATE '2027-07-31'),
  ('WV', 2026, 'reading_year', 'required', NULL, 'state', 'sat', 11, 11, NULL, 'https://wvde.us/academics/assessment/sat-school-day', NULL, DATE '2026-09-14', DATE '2027-07-31'),
  ('WY', 2026, 'reading_year', 'required', NULL, 'state', 'act', 11, 12, TRUE, 'https://edu.wyoming.gov/college-career-ready/workkeys/', 'http://web.archive.org/web/20260813081131/https://edu.wyoming.gov/college-career-ready/workkeys/', DATE '2026-09-14', DATE '2027-07-31')
ON CONFLICT (state, school_year) DO UPDATE SET
    school_year_basis = EXCLUDED.school_year_basis,
    participation     = EXCLUDED.participation,
    school_must_offer = EXCLUDED.school_must_offer,
    test_chooser      = EXCLUDED.test_chooser,
    test              = EXCLUDED.test,
    lowest_grade      = EXCLUDED.lowest_grade,
    highest_grade     = EXCLUDED.highest_grade,
    state_funded      = EXCLUDED.state_funded,
    source_url        = EXCLUDED.source_url,
    archive_url       = EXCLUDED.archive_url,
    checked_on        = EXCLUDED.checked_on,
    expires_on        = EXCLUDED.expires_on;
