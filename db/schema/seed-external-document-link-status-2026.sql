-- GENERATED FILE -- do not edit.
--
-- Written by bin/test-state-programme-seed from db/seed/test-state-programmes/link-status.csv.
-- That file is the reviewed record of a link sweep an operator ran with
-- bin/check-link (bin/test-state-programme-seed -k); this file is an artifact
-- of it. To change a row, re-run the sweep and review the CSV diff.
--
-- Reading session 2026: 43 document(s) we asked about, and what the server said
-- on the day named. A url that is NOT here was never asked about, and keeps the
-- truthful unknown-with-no-date the migration gave it.

INSERT INTO external_documents (url, url_status, url_checked_on)
VALUES
    ('http://web.archive.org/web/20250724085032/https://ksrevisor.gov/statutes/chapters/ch72/072_051_0079.html', 'found', DATE '2026-09-14'),
    ('http://web.archive.org/web/20251108115859/https://education.delaware.gov/educators/academic-support/standards-and-assessments/testing_calendar/', 'found', DATE '2026-09-14'),
    ('http://web.archive.org/web/20260314052200/https://www.azed.gov/assessment/act', 'unknown', DATE '2026-09-14'),
    ('http://web.archive.org/web/20260813081131/https://edu.wyoming.gov/college-career-ready/workkeys/', 'unknown', DATE '2026-09-14'),
    ('http://web.archive.org/web/20260813111427/https://oklahoma.gov/education/services/assessments/college-and-career-readiness-assessments.html', 'found', DATE '2026-09-14'),
    ('http://web.archive.org/web/20260814170517/https://hawaiipublicschools.org/academics/act-testing/', 'found', DATE '2026-09-14'),
    ('https://dese.ade.arkansas.gov/Offices/public-school-accountability/assessment/college--career-readiness-assessments', 'unknown', DATE '2026-09-14'),
    ('https://doe.louisiana.gov/docs/default-source/assessment/2026-2027-detailed-assessment-calendar.pdf?sfvrsn=e13942d5_6', 'found', DATE '2026-09-14'),
    ('https://doe.nv.gov/offices/office-of-assessment-data-and-accountability-management-adam/office-of-assessments/college-and-career-readiness-assessment-act', 'found', DATE '2026-09-14'),
    ('https://doe.sd.gov/assessment/', 'found', DATE '2026-09-14'),
    ('https://dpi.wi.gov/assessment/act', 'found', DATE '2026-09-14'),
    ('https://ed.cde.state.co.us/assessment/sat-psat', 'found', DATE '2026-09-14'),
    ('https://ed.sc.gov/tests/high/college-entrance-exams/', 'found', DATE '2026-09-14'),
    ('https://edu.wyoming.gov/college-career-ready/workkeys/', 'found', DATE '2026-09-14'),
    ('https://education.delaware.gov/educators/academic-support/standards-and-assessments/testing_calendar/', 'unknown', DATE '2026-09-14'),
    ('https://education.ohio.gov/Topics/Testing/State-Funded-ACT-Test', 'found', DATE '2026-09-14'),
    ('https://hawaiipublicschools.org/academics/act-testing/', 'found', DATE '2026-09-14'),
    ('https://ksrevisor.gov/statutes/chapters/ch72/072_051_0079.html', 'found', DATE '2026-09-14'),
    ('https://law.lis.virginia.gov/vacode/title22.1/chapter7/section22.1-79.10/', 'found', DATE '2026-09-14'),
    ('https://marylandpublicschools.org/programs/Documents/Testing/SAT-School-Day-Administration-2026-A.pdf', 'found', DATE '2026-09-14'),
    ('https://oklahoma.gov/education/services/assessments/college-and-career-readiness-assessments.html', 'found', DATE '2026-09-14'),
    ('https://opi.mt.gov/Leadership/Assessment-Accountability/MontCAS/MontCAS-Required-Assessments', 'found', DATE '2026-09-14'),
    ('https://osse.dc.gov/page/sat-school-testing', 'found', DATE '2026-09-14'),
    ('https://portal.ct.gov/sde/student-assessment/main-assessment/student-assessment/summative-assessment---end-of-year', 'found', DATE '2026-09-14'),
    ('https://ride.ri.gov/instruction-assessment/assessment/psat-and-sat', 'found', DATE '2026-09-14'),
    ('https://tea.texas.gov/student-readiness-and-high-school/college-career-and-military-prep/sat', 'found', DATE '2026-09-14'),
    ('https://web.archive.org/web/20230202182648/https://www.education.nh.gov/sites/g/files/ehbemt326/files/inline-documents/nh_sat_school_day_faqs.pdf', 'unknown', DATE '2026-09-14'),
    ('https://web.ped.nm.gov/wp-content/uploads/2026/07/SAT-Fact-Sheet-2026-27_2.pdf', 'unknown', DATE '2026-09-14'),
    ('https://wvde.us/academics/assessment/sat-school-day', 'found', DATE '2026-09-14'),
    ('https://www.alabamaachieves.org/assessment/', 'found', DATE '2026-09-14'),
    ('https://www.azed.gov/assessment/act', 'unknown', DATE '2026-09-14'),
    ('https://www.dpi.nc.gov/districts-schools/accountability-and-testing/school-accountability-and-reporting/act-reports', 'found', DATE '2026-09-14'),
    ('https://www.education.ky.gov/AA/Assessments/Pages/College_Admissions_Exam.aspx', 'found', DATE '2026-09-14'),
    ('https://www.education.ne.gov/assessment/act/', 'found', DATE '2026-09-14'),
    ('https://www.education.nh.gov/sites/g/files/ehbemt326/files/inline-documents/nh_sat_school_day_faqs.pdf', 'unknown', DATE '2026-09-14'),
    ('https://www.in.gov/doe/students/assessment/high-school-assessment', 'found', DATE '2026-09-14'),
    ('https://www.isbe.net/Documents/State-Assessment-Dates-2026-27.pdf', 'found', DATE '2026-09-14'),
    ('https://www.mdek12.org/studentassessment/mississippi-college-and-career-testing/', 'found', DATE '2026-09-14'),
    ('https://www.michigan.gov/mde/services/student-assessment/michigan-merit-examination-mme', 'found', DATE '2026-09-14'),
    ('https://www.revisor.mn.gov/statutes/cite/120B.30', 'found', DATE '2026-09-14'),
    ('https://www.schools.utah.gov/assessment/_assessment_/_parent_resources_/25WhatParentsNeedToKnowAboutACT.pdf', 'found', DATE '2026-09-14'),
    ('https://www.sde.idaho.gov/wp-content/uploads/2026/06/Statewide-Assessment-Window.pdf', 'found', DATE '2026-09-14'),
    ('https://www.tn.gov/education/districts/lea-operations/assessment/act-sat.html', 'unknown', DATE '2026-09-14')
ON CONFLICT (url) DO UPDATE SET
    url_status     = EXCLUDED.url_status,
    url_checked_on = EXCLUDED.url_checked_on;
