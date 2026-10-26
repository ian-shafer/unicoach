# Table anatomy for the Family Cost Report

Research report for brief 0016 (the report at a glance). Scope: how to lay out
per-school money figures that come from more than one publisher, how to keep two
adjacent tables from reading as one total, how those tables behave when stacked
into cards on a phone, the accessibility floor, and what a parent looks for
first. The brief's fixed constraints (one table per publisher, publisher and
cohort in the caption, no total row, hedges moved to captions or to one "What
kind of numbers these are" section, stacking below 640px via hidden `thead` and
`data-label`) are taken as given; the prior findings on concise/scannable copy,
"parallel figures go in a table", and "units live in the header once" are built
on, not re-derived.

## METHOD

All fetches on 2026-10-25. "fetch" = WebFetch; "browser" = the JS-rendered page
read in the built-in browser pane; "pdftotext" = the fetched PDF converted with
poppler. Quotes under 15 words unless the text is a label or a data definition.

Fetched and used:

- College Scorecard school page, UC Berkeley (browser; WebFetch returned only
  the site chrome):
  https://collegescorecard.ed.gov/school/?110635-University-of-California-Berkeley
- College Scorecard fields-of-study page for the same school (browser):
  https://collegescorecard.ed.gov/school/fields/?110635-University-of-California-Berkeley
- College Scorecard glossary (fetch):
  https://collegescorecard.ed.gov/data/glossary/
- College Scorecard institution-level data documentation, "Version: October
  2026" (fetch + pdftotext):
  https://collegescorecard.ed.gov/files/InstitutionDataDocumentation.pdf
- RTI College Scorecard Technical Review Panel 1 summary, Jan 2016 (fetch, read
  as PDF):
  https://edsurveys.rti.org/ipeds_trp_docs/prod/documents/cs1_summary.pdf
- White House blog on building the 2015 Scorecard (fetch):
  https://obamawhitehouse.archives.gov/blog/2015/09/12/under-hood-building-new-college-scorecard-students
- ED College Financing Plan page (browser; WebFetch 403):
  https://www.ed.gov/higher-education/paying-college/college-financing-plan
- FSA 2024-25 CFP electronic announcement (fetch):
  https://fsapartners.ed.gov/knowledge-center/library/electronic-announcements/2023-10-31/2024-2025-college-financing-plan
- CFP Technical Reference Guide, Responsive Version, Dec 2018 (fetch +
  pdftotext):
  https://fsapartners.ed.gov/sites/default/files/attachments/eannouncements/20192020CollegeFinancingPlanResponsivePreviewTechnicalGuide.pdf
- CFPB design system, Tables (fetch):
  https://cfpb.github.io/design-system/components/tables
- CFPB "Your financial path to graduation" (fetch):
  https://www.consumerfinance.gov/paying-for-college/your-financial-path-to-graduation/
- NCES College Navigator, UC Berkeley (fetch):
  https://nces.ed.gov/collegenavigator/?id=110635
- BigFuture, University of Michigan Ann Arbor, Costs tab (browser):
  https://bigfuture.collegeboard.org/colleges/university-of-michigan-ann-arbor/tuition-and-costs
- GOV.UK Design System, Table (fetch):
  https://design-system.service.gov.uk/components/table/
- GOV.UK Design System, Summary list (fetch):
  https://design-system.service.gov.uk/components/summary-list/
- W3C WAI Tables tutorial: index, Caption & Summary, One header, Tips (fetch):
  https://www.w3.org/WAI/tutorials/tables/ ,
  https://www.w3.org/WAI/tutorials/tables/caption-summary/ ,
  https://www.w3.org/WAI/tutorials/tables/one-header/ ,
  https://www.w3.org/WAI/tutorials/tables/tips/
- USWDS Table (fetch, two reads):
  https://designsystem.digital.gov/components/table/
- NHS design system Table (fetch, three reads):
  https://service-manual.nhs.uk/design-system/components/table
- CSS-Tricks "Responsive Data Tables" (fetch):
  https://css-tricks.com/responsive-data-tables/
- Adrian Roselli, "A Responsive Accessible Table" (fetch):
  https://adrianroselli.com/2017/11/a-responsive-accessible-table.html
- Adrian Roselli, "Tables, CSS Display Properties, and ARIA", updated Jan 2024
  (fetch):
  https://adrianroselli.com/2018/02/tables-css-display-properties-and-aria.html
- Adrian Roselli, "Under-Engineered Responsive Tables" (fetch):
  https://adrianroselli.com/2020/11/under-engineered-responsive-tables.html
- Filament Group Tablesaw README (fetch; project archived):
  https://github.com/filamentgroup/tablesaw
- WebAIM, Creating Accessible Tables: Data Tables (fetch):
  https://webaim.org/techniques/tables/data
- a11ysupport.io, CSS generated content (browser; WebFetch returned only a
  heading): https://a11ysupport.io/tech/css/generated_content
- 456 Berea Street, "CSS generated content and screen readers", May 2012
  (fetch):
  https://www.456bereastreet.com/archive/201205/css_generated_content_and_screen_readers
- NN/g, Data Tables: Four Major User Tasks (fetch):
  https://www.nngroup.com/articles/data-tables/
- NN/g, Comparison Tables (fetch):
  https://www.nngroup.com/articles/comparison-tables/
- NN/g video, How to Fit Big Tables on Small Screens (fetch):
  https://www.nngroup.com/videos/big-tables-small-screens/
- UK Government Analysis Function, Using symbols and shorthand (fetch):
  https://analysisfunction.civilservice.gov.uk/policy-store/symbols-in-tables-definitions-and-help/
- UK Government Analysis Function, Data visualisation: tables (fetch):
  https://analysisfunction.civilservice.gov.uk/policy-store/data-visualisation-tables/
- Datawrapper, what to consider when creating tables (fetch, after redirect):
  https://www.datawrapper.de/blog/guide-what-to-consider-when-creating-tables/
- GAO-23-104708, Financial Aid Offers, Nov 2022 (HTML summary fetch + full PDF
  via pdftotext): https://www.gao.gov/products/gao-23-104708 ,
  https://www.gao.gov/assets/gao-23-104708.pdf
- uAspire / New America, Decoding the Cost of College, June 2018 (summary page
  fetch + PDF via pdftotext):
  https://www.newamerica.org/education-policy/policy-papers/decoding-cost-college/
  ,
  https://www.uaspire.org/getattachment/fdc6e2a5-e4fc-4390-ae17-de4b9b9df9da/Decoding-the-Cost-of-College.pdf
- Center for American Progress, Improving the College Scorecard, Nov 2012 (PDF
  via pdftotext):
  https://www.americanprogress.org/wp-content/uploads/sites/2/2012/11/CollegeScorecard-4.pdf
- Education Week on the AEI "Nothing but Net" survey, Dec 2011 (fetch):
  https://www.edweek.org/teaching-learning/confusion-over-net-price-college-costs-may-deter-students/2011/12
- Gallup / Lumina, Cost of College, 2024 (PDF via pdftotext):
  https://gallup.com/file/analytics/643526/gallup-lumina%20foundation-cost%20of%20college%20report.aspx

Failed or not used as a source:

- Niche cost page: WebFetch 403; browser returned "Access to this page has been
  denied". Not read; nothing below is attributed to Niche.
- https://fsapartners.ed.gov/knowledge-center/topics/college-financing-plan 404.
- ED's 2027-28 and 2026-27 CFP annotated PDFs and technical guides: 403 to
  WebFetch; the HTML specification is distributed only as a .zip, which was not
  downloaded. The CFP layout below is from the Dec 2018 responsive-version guide
  plus the ED page text; the current forms' row set was not verified.
- BigFuture `/paying` path 404; the live path is `/tuition-and-costs`.
- TPGi "accessibility support for CSS generated content" 404; replaced by
  a11ysupport.io and 456 Berea Street.
- NN/g "Mobile Tables" article 404; replaced by the NN/g video page and
  Comparison Tables article.
- ONS style guide redirected; the tables guidance now lives at the Analysis
  Function URLs above.
- 18F launch post (DNS failure), RNL family-engagement blog (redirect to a
  generic index), Axios summary of Gallup (403). The Gallup figures were taken
  from the report PDF instead.
- The GOV.UK, USWDS and NHS pages report no breakpoint-specific user research on
  stacked tables; where this report says "no evidence" it means none was found
  in these sources, not that none exists.

## 1. Table anatomy for mixed-publisher financial data

### What each reference site actually does

**College Scorecard school page** (browser read). The page opens with three
headline figures (Graduation Rate 94%, Average Annual Cost $14,538, Median
Earnings $96,018), each with a "Midpoint for 4-yr Colleges" comparison, then
accordion sections: Fields of Study, Costs, Graduation & Retention, Financial
Aid & Debt, Typical Earnings, Campus Diversity, Test Scores. Only two `<table>`
elements exist on the whole page (DOM inspection): one with
`<caption>Average cost by family income</caption>` and headers "Family Income" /
"Average Annual Cost" (six rows, no `scope` attributes), preceded by a heading
"By Family Income"; and one captioned "Repayment Rates" with headers "Repayment
Status" / "Percent". Every other money figure is a labelled stat block, not a
table: "Students Receiving Federal Loans 17%", "Median Total Debt After
Graduation $12,416" with the one-sentence definition "The typical total debt for
undergraduate borrowers who complete college.", "Typical Monthly Loan Payment
$141". The net-price table and the debt figures are in different accordion
sections and never share a table. Source and cohort are not on the page at all
beyond "Powered by College Scorecard Data" in the footer and the "Glossary"
link; they live in the glossary
(https://collegescorecard.ed.gov/data/glossary/): average annual cost is the
"2023-24 award year cohort", median total debt after graduation is the "2020 and
2021 pooled fiscal year cohort", earnings are the "2017-18 and 2018-19 pooled
award year cohort" measured in 2022 and 2023 and "adjusted to 2024 dollars".

The fields-of-study view is a stacked label/value list with zero `<table>`
elements (DOM inspection): "Number of Graduates 841 / Median Earnings (At this
College) Data Not Available / Median Earnings (National) $92,374 / Median Total
Debt $12,538 / Monthly Earnings Data Not Available / Monthly Loan Payment $142",
with a single note under the section: "These data were collected from
undergraduate students who received federal financial aid." So Scorecard does
put a debt figure and an earnings figure in one visual block, but they are
labelled values, not columns, there is no total, and a missing value is spelled
out as "Data Not Available".

**Scorecard's own documentation on why the same-named figure differs by
publisher.** The data documentation defines net price as cost of attendance
"minus federal, state, and institutional grant/scholarship aid, for full-time,
first-time undergraduate Title IV-receiving students", derived from the IPEDS
Institutional Characteristics and Student Financial Aid components, and
footnotes that the Scorecard derivation "is different than the methodology used
in other presentations of net price (e.g. College Navigator)"
(https://collegescorecard.ed.gov/files/InstitutionDataDocumentation.pdf, p. 14).
Cumulative median debt is "the median loan debt accumulated at the institution
by all student borrowers of federal loans who separate", PLUS loans excluded,
"rolling two-year pooled cohorts" by fiscal year, and the consumer site uses the
completers-only variant (`GRAD_DEBT_MDN_SUPP`) (same PDF, p. 20-21). The 2016
Technical Review Panel recorded that Scorecard and College Navigator "are
updated at different times, which can result in different presentations of the
same data element (i.e. different cohort year)" and that some are "calculated
using varying definitions", citing exactly the net-price case
(https://edsurveys.rti.org/ipeds_trp_docs/prod/documents/cs1_summary.pdf, p. 4).
The same panel asked that earnings be labelled as "for students who received
financial aid" and "not earnings of graduates" (p. 7). This is the federal
publisher's own statement that figures with the same name from two sources are
not the same number, which is the premise of the brief's no-total rule.

**College Financing Plan** (ED page, browser; 2018 technical guide, pdftotext).
ED describes the CFP as "a legacy consumer tool" and publishes, for each of
2027-28 and 2026-27, an annotated form, a template, a technical guide and an
HTML specification (zip)
(https://www.ed.gov/higher-education/paying-college/college-financing-plan). The
2018 guide says the plan "consists of two sections: Student Information and
Institutional Performance Metrics". Student Information is institution-supplied:
"Costs in the 2019-20 year" (Estimated Cost of Attendance; Tuition and fees;
Housing and meals; Books and supplies; Transportation; Other educational costs),
"Grants and scholarships to pay for college" (Total; from your school; Federal
Pell Grant; from your state; other), "What will you pay for college: Net Costs",
Work options, Loan options (Direct Subsidized, Direct Unsubsidized), Other
options (Family Contribution). Institutional Performance Metrics are federal and
come from "a combination of the IPEDS ... data collections as well as the
College Scorecard ... release": graduation rate, repayment rate, "Median
Borrowing" and an estimated monthly payment. The two sections are separate
panels with separate theme colours (`.primary-color` for the student panels,
`.secondary-color` for "the right side bar", `.accent-color` for "the 'What will
you pay for college' panel"), and the cohort statements are on a second page
headed Glossary: median borrowing "is from the U.S. Department of Education's
National Student Loan Data System (NSLDS)" for "all undergraduate borrowers who
graduated or withdrew from the institution between October 1, 2015, and
September 30, 2017", "Private loans and Parent PLUS loans are not included",
monthly payment "based on an interest rate of 5.05%"
(https://fsapartners.ed.gov/sites/default/files/attachments/eannouncements/20192020CollegeFinancingPlanResponsivePreviewTechnicalGuide.pdf,
sections 2, 3, 4, 5). Missing metrics are sentences, not blanks: "This
information is not available for this institution." The responsive version "will
... reconfigure the layout for the best user experience given the screen size"
but the guide does not say how (section 4).

So the federal form keeps institution-supplied money and federal-cohort money in
different panels, different colours and different columns, and the net-cost
figure and the median-borrowing figure are never in one table.

**CFPB design system tables**
(https://cfpb.github.io/design-system/components/tables). The guidance is
structural, not about sources: "Always use `<th>` elements for data tables";
tables "are **not** responsive unless you add one of the small screen classes";
`.o-table--stack-on-small` stacks and `data-label` "must be used to label each
cell in a `table` for small screen responsive views"; if all columns share a
unit "state it in the title or above the table", if units differ "put the unit
in parentheses after the column label"; right-align quantities and ordinals; a
page with several tables should label them "Table 1" before the title. There is
no guidance on source notes, footnotes or totals. CFPB's own consumer tool "Your
financial path to graduation" uses labelled fields and subtotals rather than
tables, describes its sources generically ("the most recent data from the U.S.
Department of Education"), and marks missing school data as "Ask the school"
rather than mixing in a federal substitute
(https://www.consumerfinance.gov/paying-for-college/your-financial-path-to-graduation/).

**College Navigator** (https://nces.ed.gov/collegenavigator/?id=110635). One
IPEDS survey component per table, year in the caption or column headers:
"Estimated expenses for academic year" with columns 2021-2022 to 2024-2025;
"Undergraduate Student Financial Aid, 2023-2024" with columns Type of Aid /
Number awarded / Percent awarded / Total amount / Average amount; "Average Net
Price for Full-Time Beginning Students" with year columns through 2023-2024.
Federal grants and federal student loans are rows of the same aid table, but
they are the same publisher and cohort (IPEDS SFA), each is its own row group,
and there is no row summing them. No "Source" line appears anywhere on the page
because the whole page is one publisher. Note the year misalignment the fetch
flagged: expenses run to 2024-25 while net price stops at 2023-24, so even a
single-publisher page cannot share one year column across tables.

**BigFuture Costs tab** (browser). Headline tiles ("Average Per Year After Aid
$19,005", "Students Receiving Financial Aid 37.19%", "Average Aid Package
$35,086"), then label/value stacks: Average Net Price with the sentence "Your
net price is a college's cost of attendance minus the grants and scholarships
you receive"; net price by household income as five label/value pairs; Sticker
Price (in-state, out-of-state); Other Costs; Financial Aid "Distribution
Details" and "Aid Package Details" (need-based grant "Scholarships and grants
don't need to be paid back. $26,860"; need-based loan "Loans must be paid back.
$4,432"; non-need-based aid $6,270); "Debt at Graduation ... $27,923". Missing
dates read "Not available". The only provenance statement is one page-level
note: profiles "are based primarily on information supplied by the colleges
themselves ... with some data provided via federal and state agencies". No
per-figure source, no year, no table element in the money blocks
(https://bigfuture.collegeboard.org/colleges/university-of-michigan-ann-arbor/tuition-and-costs).

### Where the publisher and cohort sit, by site

| Site                     | Table or list                            | Publisher placement           | Cohort/year placement                        | Two publishers in one table           |
| ------------------------ | ---------------------------------------- | ----------------------------- | -------------------------------------------- | ------------------------------------- |
| Scorecard school page    | 2 tables, rest stat blocks               | Footer + glossary only        | Glossary only                                | No; sections never share a table      |
| Scorecard field of study | Label/value list                         | One note under section        | Not on page                                  | Debt + earnings in one list, no total |
| College Financing Plan   | Panels (institution) + sidebar (federal) | Panel colour + glossary page  | "Costs in the 2019-20 year" header; glossary | No; separate panels                   |
| College Navigator        | One table per IPEDS component            | Not stated (single publisher) | Caption or year columns                      | No                                    |
| BigFuture                | Label/value stacks                       | One page-level note           | Not on page                                  | Mixed, unlabelled                     |
| CFPB design system       | n/a (guidance)                           | Not covered                   | Not covered                                  | Not covered                           |

### Recommendations for the unicoach page

1. One `<table>` per publisher, with the publisher named in the `<caption>`
   text, is consistent with every federal surface read: Scorecard keeps the
   IPEDS net-price table and the NSLDS debt figures in different sections, the
   CFP keeps institution-supplied and federal figures in different panels, and
   College Navigator keeps one IPEDS component per table. (evidenced)
2. Put the cohort in the caption, not a footnote: College Navigator's
   "Undergraduate Student Financial Aid, 2023-2024" and the CFP's "Costs in the
   2019-20 year" header do this; Scorecard's choice to hide cohorts in a
   glossary is what the 2016 panel criticised. (evidenced)
3. Keep Scorecard's pattern of one plain-language definition sentence directly
   under or beside the figure ("The typical total debt for undergraduate
   borrowers who complete college."), and move the per-figure hedges into the
   caption and the once-per-page section as the brief specifies; BigFuture's
   single page-level "based primarily on" disclaimer is the pattern to avoid
   because it does not say which figure came from whom. (inferred: no source
   measures the harm of the page-level disclaimer; the preference follows from
   the panel's labelling recommendation)
4. Render a missing figure as words inside the cell ("Not reported by this
   school"), as Scorecard ("Data Not Available"), BigFuture ("Not available")
   and the CFP ("This information is not available for this institution.") all
   do, never a blank or a dash alone. (evidenced)
5. Treat Scorecard's field-of-study block as the ceiling for mixing debt and
   earnings: label/value rows under one caption, no total, with the "received
   federal financial aid" cohort stated once for the block. (evidenced)

## 2. Preventing the "summable" misreading

### What the guidance says

- **Split by topic, never by row group.** WAI: "Complex tables are more work for
  content creators as well as being harder to interpret for users"; break them
  "into simple individual tables, each holding one sub-topic", and start a new
  table when the topic changes rather than adding a second `<th>` row, because
  "screen readers may read all `<th>` cells in a column"
  (https://www.w3.org/WAI/tutorials/tables/tips/). WebAIM: some screen readers
  "do not fully support complex tables", so "flatten" and avoid spanned cells
  (https://webaim.org/techniques/tables/data). Both say two tables, not one
  table with two header rows.
- **Caption as the heading of the thing being read.** GOV.UK: use the caption
  "to describe a table in the same way you would use a heading"
  (https://design-system.service.gov.uk/components/table/). WAI: "A caption
  functions like a heading for a table" and in Tables Mode "captions are the
  primary mechanism to identify tables"
  (https://www.w3.org/WAI/tutorials/tables/caption-summary/). USWDS: put titles,
  attribution and last-updated dates in the `<caption>`
  (https://designsystem.digital.gov/components/table/). A screen-reader user who
  jumps table to table therefore hears the publisher change if and only if the
  caption carries it.
- **Units and formats as the separator.** Analysis Function: units "belong in
  column headings, or in the title if every value uses the same unit"; "Use the
  same precision within each column"; sources "go underneath the table" in the
  form "[publication, survey or other source] from the [organisation]"
  (https://analysisfunction.civilservice.gov.uk/policy-store/data-visualisation-tables/).
  USWDS: "one unit or format per column". CFPB: units in parentheses after the
  column label when they differ. Datawrapper, on sparklines: they "are not
  comparable with each other", and different heatmap measures need "different
  color gradients to separate them clearly"
  (https://www.datawrapper.de/blog/guide-what-to-consider-when-creating-tables/).
  The transferable rule: when two tables are not comparable, give them different
  units in the header and different column vocabularies so nothing lines up.
- **Totals are a convention readers expect, so their absence has to be
  deliberate and consistent.** Analysis Function: totals "usually go at the
  bottom or right" and summary rows use bold. Right-aligned numbers "in columns,
  close together" are what makes a column look summable. GOV.UK and NHS both say
  right-align numeric columns for comparison. So a borrowing table with
  right-aligned dollars and no bold last row reads as "a list", and one with a
  bold last row reads as "a sum"; the no-total rule must apply to every
  non-price table, not just the two debt tables. (The sources establish the
  convention; the inference that a bold last row is read as a sum is marked in
  the recommendations.)
- **Separate the kinds of money, and name the source of each.** GAO's best
  practices 4, 6 and 7: "Separate gift aid, loans, and work-study", "Label type
  of aid", "Label source of aid ... because the protections and stipulations
  differ"; an estimated 15%, 24% and 58% of colleges respectively did not meet
  them in 2021-22 offers (https://www.gao.gov/assets/gao-23-104708.pdf, Tables 2
  and 4). uAspire: "Seventy percent of letters grouped all aid together"; gift
  aid and loans "should be listed separately under its own heading and
  subtotaled"; Parent PLUS and work-study "should not be included as a line item
  ... nor should they be used in calculations of total aid offered"
  (https://www.uaspire.org/getattachment/fdc6e2a5-e4fc-4390-ae17-de4b9b9df9da/Decoding-the-Cost-of-College.pdf,
  pp. 25-26). The CFP implements this as separate panels with their own headers.
  These are the same move the brief makes for publishers: things that must not
  be added get their own heading and their own box.
- **Missing-value tokens, not "NA".** Analysis Function: avoid "NA" because
  "some readers take it to mean 'Not Applicable' and others 'Not Available'";
  use a defined marker, introduce the key "above the table, so users read the
  key before the data", keep markers out of spreadsheet data cells because a
  marker next to a number "can stop it being read or summed"
  (https://analysisfunction.civilservice.gov.uk/policy-store/symbols-in-tables-definitions-and-help/).
  The last point is the only explicit statement found that a non-numeric cell
  blocks summation; it is about spreadsheets, but it argues for spelling out
  "Not reported by this school" in a cell rather than leaving it blank.
- **Scorecard's own practice.** Net price and median debt are in different
  accordion sections; the field-of-study list stacks debt and earnings as
  label/value pairs with different label vocabularies ("Median Total Debt",
  "Monthly Loan Payment", "Median Earnings (National)") and no total; cohorts
  differ per figure in the glossary. The federal publisher never gives two
  cohorts one column.

### Concrete, testable patterns

- Two `<table>` elements, each with its own `<caption>`; never one table with
  two `<tbody>` groups or a second header row (WAI, WebAIM).
- The caption names the publisher and the cohort ("U.S. Department of Education
  College Scorecard, federal loan borrowers who graduated, 2020 and 2021";
  "School's Common Data Set, 2024-25 freshmen"), so adjacent captions differ in
  both nouns (GOV.UK, USWDS, Analysis Function).
- Different units in the header: "$ per year", "$ owed at graduation", "$ per
  month", "% of freshmen" (Analysis Function, USWDS, CFPB). Two columns in two
  tables must not share a header string.
- Different first-column vocabulary between the federal debt table and the
  school borrowing table (e.g. "Median federal debt at graduation" vs "Federal
  loans" / "Private loans"), so the row labels cannot be read as a continuation
  (inferred from the Datawrapper "separate them clearly" principle).
- No row header containing "Total", "All", "Combined" or "Sum" outside the price
  table; no `<tfoot>`; no bold last row (Analysis Function convention,
  inverted).
- A heading or one sentence of prose between consecutive tables, so the DOM
  never has `</table><table>` adjacency (inferred; GOV.UK treats caption as the
  heading, but a visually adjacent second caption still reads as a subhead of
  the first unless something intervenes).
- The "different measure" statement lives once in "What kind of numbers these
  are" and names both publishers by the same strings used in captions, so a
  reader can match them (USWDS caption attribution; TRP labelling request).
- Missing values are a fixed phrase in the cell, never "NA", "N/A", "-" or an
  empty cell (Analysis Function; Scorecard, BigFuture, CFP practice).

### Recommendations for the unicoach page

1. Enforce "one publisher, one `<table>`, one `<caption>`" in the template, not
   in content review; a caption that names two publishers is a defect.
   (evidenced: WAI split-by-topic, USWDS/GOV.UK caption-as-heading)
2. Make the federal debt table and the school borrowing table unalignable: no
   shared column header text, different units in the header, different row-label
   vocabulary. (evidenced for units and headers; inferred for row-label
   vocabulary)
3. Ban total rows outside the price table at the template level and assert that
   no non-price table's last row is bolded or styled as a summary. (evidenced
   that the bold last row is the convention for sums; inferred that readers
   apply it in reverse)
4. Keep every "not comparable" hedge in the once-per-page section, and have it
   cite the caption strings verbatim, so the hedge and the tables share a
   vocabulary. (inferred)
5. Spell out missing values in the cell with one fixed phrase; never "NA".
   (evidenced)

## 3. Stacked cards on phones

### Patterns in the design systems and articles

- **CFPB**: `.o-table--stack-on-small` stacks "to make the information legible";
  every cell needs `data-label`; striping "is not visible on small screens";
  "Responsive layouts are not applied when pages are printed"; a
  `.o-table--scrolling` wrapper is the alternative "for tables with many
  columns" (https://cfpb.github.io/design-system/components/tables).
- **USWDS**: `usa-table--stacked` "collapses at narrow widths for better
  readability on small screens"; "you must ensure there is a `data-label`
  attribute on each cell of the table that matches the column header";
  `usa-table--stacked-header` promotes the first cell of each row to a card
  heading "for directories where the first cell in each row is a name"; "Tables
  with more than two columns need" either scrolling or stacking; use "a
  horizontally scrolling table for dense numbers, or a stacked layout for
  directories"; stacking is incompatible with sorting and sticky headers; no
  numeric breakpoint is published
  (https://designsystem.digital.gov/components/table/).
- **GOV.UK** does not stack. Its advice is to reduce the data, split into
  several tables or pages, and otherwise apply
  `govuk-table--small-text-until-tablet` (added v5.2, Feb 2024); it warns not to
  use the small-text class on tables with little data
  (https://design-system.service.gov.uk/components/table/).
- **NHS**: `nhsuk-table--responsive` "on small screens it stacks vertically";
  recommended when a table is "squashed and hard to read on small screens, 768px
  and smaller"; "do not use" it "if content is easier to read in a non-stacked
  layout", and use a scrolling table "if there are too many columns"; the
  caption element is kept; the responsive variant "was tested at HM Revenue &
  Customs" (no findings published)
  (https://service-manual.nhs.uk/design-system/components/table).
- **CSS-Tricks** (the origin of the technique): breakpoint `max-width: 760px`;
  `thead tr { position: absolute; top: -9999px; left: -9999px; }` with the
  comment "Hide table headers (but not display: none;, for accessibility)";
  labels via `td:before`; the author: "There may be some accessibility concerns
  (or maybe not, I'm really not sure)"; a commenter: the result is "totally
  unreadable as a table now" (https://css-tricks.com/responsive-data-tables/).
- **Tablesaw** (archived): Stack mode "activates when the viewport is below 40em
  (640px)", the same breakpoint as the brief; per-table/row/cell
  `data-tablesaw-no-labels` opt-out; `data-tablesaw-hide-empty` hides the label
  of an empty cell when stacked (https://github.com/filamentgroup/tablesaw).
- **Roselli 2017**: at narrow widths the `<th>` row is `display: none`, "the
  caption is styled to visually stand in for the header", each cell gets a
  `::before` label; the author concedes "Strictly interpreted, it fails WCAG
  1.3.1" (https://adrianroselli.com/2017/11/a-responsive-accessible-table.html).
  **Roselli 2020**: he tested a scrolling container against a reflowing version
  and says "this one performs better for all users"; CSS display changes can
  "break the semantics" and "ARIA cannot replicate all the semantics"
  (https://adrianroselli.com/2020/11/under-engineered-responsive-tables.html).
  This is one practitioner's unpublished test, not a study.
- **NN/g**: for comparison tables on mobile "it's unlikely that you'll be able
  to show more than 2 items at a time"; converting to tabs or lists works but
  "these formats do not support compensatory decision making as well"; sticky
  column headers because "users will easily forget which column is for which
  product" (https://www.nngroup.com/articles/comparison-tables/). Big tables on
  small screens: lock headers, let users pick a subset of columns
  (https://www.nngroup.com/videos/big-tables-small-screens/). Data tables: order
  columns by importance, use "a human-readable identifier as the first column"
  (https://www.nngroup.com/articles/data-tables/).
- **WAI tips**: responsive tables "often change format on small screens"; "Make
  sure the structural relationship is preserved in every format"
  (https://www.w3.org/WAI/tutorials/tables/tips/).

### When a 2-3 column table stays a grid

USWDS draws the line at two columns: a table with more than two columns needs
scrolling or stacking; a two-column table does not. NN/g's "not more than 2
items" for comparison tables is the same line from the usability side. NHS says
not to stack when the content is easier to read unstacked. No source gives
evidence that a 2-column label/value table reads better as cards than as a grid;
the merit table (share, average), the National Merit lines (attribute, value)
and the automatic-award grid (award, band, amount) are at or below that line,
while the price table (row header plus two or three "ways of living" columns) is
above it. (The brief's constraint stacks every table; the evidence supports a
narrower rule, below.)

### What happens to the caption and to word-valued cells

- Caption: NHS keeps `<caption>` in the responsive variant; USWDS keeps it;
  Roselli promotes it to stand in for the hidden header row. No system removes
  or moves it when stacking. (evidenced) Since every stacked card loses its
  column headers to `data-label` prefixes, the caption is the only remaining
  place the publisher and cohort are stated once; this is why the brief's
  caption rule and stacking rule depend on each other. (inferred)
- Row header: USWDS `stacked-header` and Tablesaw both promote the first cell to
  the card title; NHS prefixes it with its own aria-hidden label instead. For a
  price table whose first cell is "Tuition and fees", promotion to the card
  title is the directory pattern USWDS describes. (evidenced)
- Word-valued cells ("Not reported by this school"): Tablesaw hides the label of
  an empty cell; nothing hides a non-empty one, so the card line reads "Average
  amount: Not reported by this school", which is correct. Analysis Function's
  rule that markers belong in headings or row labels, not data cells, applies to
  spreadsheets; for a stacked card the data cell is the only place the reader
  sees the row, so the phrase must stay in the cell. (evidenced for the
  spreadsheet rule; inferred for the card)
- A card repeats the column header on every line, so headers that carry units
  ("$ per year") are repeated per line; keep them short enough to fit a 375px
  line beside a currency value. (inferred)

### Recommendations for the unicoach page

1. Stack only tables with more than two data columns (the price table); let
   two-column tables stay as grids at 375px, as USWDS and NN/g both place the
   threshold at two. (evidenced) If the brief keeps "stack every table" as a
   fixed constraint, treat this as a recorded disagreement, not a blocker.
2. When stacking, promote the row header to the card title (USWDS stacked-header
   pattern) and prefix the other cells with their column header via `data-label`
   whose text is identical to the `<th>` text. (evidenced)
3. Keep the `<caption>` visible and first in reading order in the stacked
   layout; do not hide it with the header row. (evidenced)
4. Keep column header text under roughly 20 characters so the repeated prefix
   fits beside a value on one line at 375px. (inferred)
5. Do not stack when printing; CFPB does not apply responsive layouts to print.
   (evidenced)

## 4. Accessibility floor

### Caption vs heading

- WAI: "A caption functions like a heading for a table"; "Most screen readers
  announce the content of captions"; in Tables Mode "captions are the primary
  mechanism to identify tables"; a summary can go inside the caption text so it
  is "available to visual users as well"
  (https://www.w3.org/WAI/tutorials/tables/caption-summary/).
- WebAIM: "The `<caption>` element must be the first thing after the opening
  `<table>` tag" (https://webaim.org/techniques/tables/data).
- GOV.UK and NHS: use the caption "in the same way you would use a heading" with
  size modifier classes; NHS requires `caption.text` and `caption.id` for
  scrolling tables and labels the scroll region with `aria-labelledby`.
- USWDS: title, attribution and date go in the caption.

Floor: every data table has a non-empty `<caption>` as its first child; a page
heading above a table does not substitute, because Tables Mode navigation skips
headings.

### scope, headers/id

- WAI one-header: a small table with one header row is acceptable without
  `scope` "only ... because it is such a small table"; `scope` "is also needed
  for larger tables with one header row or column", and whenever content is
  ambiguous or the table has both row and column headers "the `scope` attribute
  should be used to avoid confusion"
  (https://www.w3.org/WAI/tutorials/tables/one-header/).
- WebAIM: "All `<th>` elements should generally always have a scope attribute";
  `headers`/`id` is "rarely necessary (use scope instead)".
- USWDS: up to two header rows, `scope="col"`/`scope="row"` on every header;
  beyond that, `id` + `headers`. GOV.UK: `scope` "helps assistive technology
  tell row and column headers apart".
- WAI tips: give each datum its own cell; "Headers in one column with all the
  data in another column make relationships hard for screen readers to
  determine"; use `<th>` for header cells and do not style `<td>` to look like
  one; no merged cells.

Floor: every `<th>` has `scope`; the price table has both `scope="col"` on the
living-arrangement headers and `scope="row"` on the cost-line headers; no
`colspan`/`rowspan`; no `headers`/`id`.

### When a definition list instead

- GOV.UK summary list: for "information as a list of key facts", built on
  `<dl>`/`<dt>`/`<dd>`, and "Do not use the summary list component for tabular
  data" (https://design-system.service.gov.uk/components/summary-list/).
- USWDS: consider "definition or hierarchical lists" for non-tabular content and
  keep table cells "brief and scannable".
- WAI tips' warning above (one column of headers, one of data) is the
  screen-reader cost of forcing key/value pairs into a `<table>`.

Applied to the report: the National Merit block (standing, entering class,
amount, guaranteed/competitive, first-choice rule, deadline) is a list of key
facts with no shared column and is a `<dl>`, not a table; the merit block (share
receiving, average amount) and the borrowing block (per loan type: share who
borrowed, average owed) have a repeated column structure and are tables. (The
classification is inferred from the GOV.UK/USWDS definitions; no source tests
these particular blocks.)

### What screen readers do with the `data-label` pseudo-element trick

Two separate questions: is the generated text read, and does the cell still have
a header.

- Is it read: a11ysupport.io's "CSS Generated Content with HTML span element"
  test is "supported" for all eleven screen reader/browser pairs listed (JAWS
  with Chrome/Edge/Firefox, Narrator/Edge, NVDA with Chrome/Edge/Firefox,
  Orca/Firefox, TalkBack/Chrome, VoiceOver iOS and macOS with Safari); the
  feature overall is "partial (36/44)" because of the label and button-name
  cases; tests last updated 2021-07-29; the site's own advice is "Avoid using
  CSS generated content for meaningful textual additions"
  (https://a11ysupport.io/tech/css/generated_content). Older history: in 2012
  VoiceOver read it and NVDA read it with Firefox but not Internet Explorer
  (https://www.456bereastreet.com/archive/201205/css_generated_content_and_screen_readers).
  Roselli 2017 reports "screen readers have supported it for some time" while
  granting it "fails WCAG 1.3.1" on a strict reading, because the relationship
  is conveyed by presentation only.
- Does the cell keep its header: WebAIM: with proper markup users "navigate
  through data tables one cell at a time" and "will hear the column and row
  headers spoken". Roselli's tracked history: through 2018-2020
  `display:
  block|flex|grid|inline-block|contents` on table elements made
  screen readers "no longer consider `<table>`s to be tables" in most browsers;
  by 2023 "Table display properties were functional across Chromium, Gecko, and
  WebKit" with `display: contents` the remaining risk; his standing advice is
  still to avoid display properties on tables and to add ARIA roles to a
  responsive table "only ... if you can test them with screen readers"; the
  roles are `table`, `rowgroup`, `row`, `cell`, `columnheader`, `rowheader`
  (https://adrianroselli.com/2018/02/tables-css-display-properties-and-aria.html).
  NHS ships exactly those roles on the responsive table and adds a visible
  `<span class="nhsuk-table__heading" aria-hidden="true">How much? </span>`
  before each cell's content, so sighted users see the label and screen-reader
  users rely on the (role-preserved) header association rather than hearing the
  label twice (https://service-manual.nhs.uk/design-system/components/table).
  USWDS and CFPB rely on `data-label` alone.

Floor and recommended alternative: keep the `<thead>` in the DOM (CSS-Tricks'
offscreen positioning or a visually-hidden class, not `display: none`, which
Roselli uses but which removes the headers from the accessibility tree), put the
ARIA table roles on the elements so the header association survives
`display: block`, and render the sighted label with `data-label`. The NHS
aria-hidden span is the alternative if the team prefers real text over generated
content; it costs a duplicated string per cell.

### Recommendations for the unicoach page

1. `<caption>` first child of every table, never a heading instead. (evidenced)
2. `scope` on every `<th>`; `scope="row"` on the first cell of every price-table
   row; no spans. (evidenced)
3. National Merit facts as `<dl>`; merit and borrowing figures as tables.
   (inferred from GOV.UK/USWDS definitions)
4. Stacked tables carry `role="table|rowgroup|row|columnheader|rowheader|cell"`
   and keep the `<thead>` visually hidden rather than `display: none`; test with
   VoiceOver on iOS Safari at minimum, since that is the phone case the stacking
   rule exists for. (evidenced for the roles and the display risk; the specific
   AT to test is inferred)
5. `data-label` text must equal the `<th>` text character for character, so the
   sighted prefix and the announced header never diverge. (evidenced: USWDS
   "matches the column header")

## 5. What a parent scans for first

What the sources actually measure, in order of directness:

- **Stated priorities (students, not parents).** The 2016 Scorecard panel
  summary: "prospective and recently enrolled students cited cost, availability
  of financial aid, and the majors or programs offered as the most important
  factors", and students "tend to prioritize more immediate factors like cost",
  citing the New America College Decisions Survey 2014
  (https://edsurveys.rti.org/ipeds_trp_docs/prod/documents/cs1_summary.pdf, p.
  2). The survey itself was not fetched.
- **Observed reading of a cost disclosure (students, not parents).** CAP ran
  "four different groups of college-bound high school juniors and seniors" on
  two one-page scorecards and states plainly that a definitive study "would need
  to conduct ... tests involving parents". Findings: students "read the
  government-designed scorecard in a nonlinear way", were "drawn to certain
  elements ... such as the tuition and fees numbers, graduation rate, and
  earnings potential", and "missed" the percent change in cost; asked what "net
  price" and "out-of-pocket cost" meant they "most often responded with blank
  looks"; "most students could not decide whether the net price or out-of-pocket
  figure would include the amount a student would take out in loans"; one said
  "I don't really trust averages" and wanted income-specific figures; they asked
  for the same words as the FAFSA and award letters ("Can't they just use the
  same words?"); CAP recommended the "average monthly payment figure should be
  prominently displayed"
  (https://www.americanprogress.org/wp-content/uploads/sites/2/2012/11/CollegeScorecard-4.pdf,
  pp. 5-13).
- **Parents and sticker vs net price.** AEI's 2011 survey as reported by
  Education Week: "about six in 10 families rule out some colleges because of
  the published price" without realising net price is lower; "only 12 percent of
  surveyed parents felt 'very well informed' about college costs"; parents
  "ranked cost second in importance, after quality"
  (https://www.edweek.org/teaching-learning/confusion-over-net-price-college-costs-may-deter-students/2011/12).
  Sample size not given in the article; the AEI paper was not fetched.
- **Adults' estimate of net cost.** Gallup/Lumina 2024: "fewer than one in four
  U.S. non-degree holders (23%) estimate the net cost within $5,000 of the
  actual price"; 31% say at least $20,000 a year and 47% under $10,000; the
  question asked what "the average in-state student at a public college actually
  pays per year, after grants and scholarships"; web survey Oct 9-Nov 16 2023,
  13,970 adults aged 18-59 without a degree, non-probability panel, weighted
  (https://gallup.com/file/analytics/643526/gallup-lumina%20foundation-cost%20of%20college%20report.aspx,
  section "Most Americans incorrectly estimate the actual cost of a college
  degree", chart 9, and the Methodology section). Not a parent sample.
- **What aid letters fail to show (document analysis).** uAspire: of 515 letters
  "more than one-third did not include any cost information", "only 40 percent
  calculated what students would need to pay" using 23 methods, "Seventy percent
  of letters grouped all aid together", Parent PLUS listed as an "award" in
  nearly 15%; the recommended order is cost of attendance with a direct/indirect
  breakdown, gift aid under its own heading, loans under their own heading, then
  net cost and estimated bill. GAO 2022: 41% of colleges omit the net price and
  50% understate it; the first three best practices are itemise costs, give a
  total cost of attendance, estimate net price by subtracting only gift aid
  (https://www.gao.gov/assets/gao-23-104708.pdf, Tables 2-4). Neither is a
  reading-order study; both are expert judgements about what must be findable.
- **Design decisions by publishers (not research).** Scorecard's headline trio
  is Graduation Rate, Average Annual Cost, Median Earnings; BigFuture's first
  tile is "Average Per Year After Aid"; the CFP's accent colour is reserved for
  the "What will you pay for college" panel. The White House post describes
  informal interviews and one Maryland mother "who chose schools based on the
  lowest annual actual cost"
  (https://obamawhitehouse.archives.gov/blog/2015/09/12/under-hood-building-new-college-scorecard-students);
  the panel summary says ED "will continue testing the College Scorecard with
  students and counselors" but reports no results.

What is not evidenced anywhere in these sources: a measured order in which a
parent reads a college cost page, or a parent-specific eye-tracking or
click-order study. Every ordering below is an inference from the stated
priorities, the sticker-shock finding and the publishers' convergent choices.

### Recommendations for the unicoach page

1. Lead each school with the single net-price figure for this family's income
   band, labelled as "after grants and scholarships", before the sticker price
   table: the stated top priority is cost, the dominant error is judging by
   sticker price, and three publishers put the after-aid figure first. (inferred
   from evidence; not a measured reading order)
2. Keep the words parents meet elsewhere: "cost of attendance", "grants and
   scholarships", "net price" with the one-line definition beside it, since
   students asked for the same terms as the FAFSA and letters and did not know
   whether net price included loans. (evidenced for the confusion; the term
   choice is inferred)
3. State next to every debt figure whether loans are inside it, because the CAP
   groups could not tell; a caption like "after grants, before any loans" does
   this. (evidenced for the confusion)
4. Show the monthly payment with the debt total, as CAP recommended and as
   Scorecard and the CFP both do. (evidenced as a recommendation and a practice;
   not tested on parents)
5. Do not rely on a page-level disclaimer to carry the "different measures"
   warning; the CAP groups missed the percent-change line on a one-page sheet,
   which is the closest evidence that peripheral notes are skipped. (inferred)

## Recommended rules

Each is written as an acceptance criterion on the rendered HTML of the Family
Cost Report, checkable without a screen reader unless stated.

- **T1 Caption present and first.** Every `<table>` has a `<caption>` as its
  first element child with non-empty text. (WAI, WebAIM, GOV.UK, USWDS)
- **T2 Caption names one publisher and one cohort.** Each caption contains
  exactly one publisher string from the page's fixed set (e.g. "College
  Scorecard", "Common Data Set", "scholarship office page", "admissions page")
  and at least one cohort token (a four-digit year, an academic-year range, or
  "entering class"). A caption containing two publisher strings fails.
  (Scorecard documentation, TRP, College Navigator, USWDS)
- **T3 One publisher per table.** Every `<td>` in a table carries, via the
  table's `data-publisher` attribute or equivalent, the same publisher the
  caption names; no table mixes rows from two publishers. (brief constraint;
  Scorecard and CFP practice)
- **T4 No totals outside the price table.** For every table other than the
  per-school price table, no `<th scope="row">` or first-column `<td>` text
  matches `/\b(total|all|combined|sum)\b/i`, there is no `<tfoot>`, and the last
  `<tr>` has no bold or summary styling. The price table has at most one such
  row. (brief rule; Analysis Function totals convention)
- **T5 Scope on every header.** Every `<th>` has `scope="col"` or `scope="row"`;
  no `colspan`, `rowspan`, `headers` or `id`-based association is used. (WAI,
  WebAIM, GOV.UK, USWDS)
- **T6 Units in the header, once.** Every column whose cells are numeric has a
  header containing a unit ("$", "%", "per year", "per month", "at graduation");
  no numeric `<td>` repeats the unit word. (Analysis Function, CFPB, USWDS;
  prior research)
- **T7 No two tables share a numeric column header.** The set of column header
  strings of the federal debt table and of the school borrowing table is
  disjoint. (inferred from Datawrapper and Analysis Function)
- **T8 Missing values are a phrase.** Every `<td>` whose value is unavailable
  contains one of the page's fixed phrases ("Not reported by this school", "Not
  available for this program"); no `<td>` is empty or equals "NA", "N/A", "n/a"
  or a lone dash. (Analysis Function; Scorecard, BigFuture, CFP)
- **T9 Tables are separated.** No `<table>` is the immediate next element
  sibling of another `<table>`; a heading or `<p>` intervenes. (inferred from
  caption-as-heading guidance)
- **T10 Hedges live once.** The page has exactly one section whose heading is
  "What kind of numbers these are", and every publisher string used in a caption
  appears verbatim in that section. (brief constraint; USWDS attribution; TRP)
- **T11 Stacked layout keeps semantics.** At a 375px viewport, every table has
  `role="table"`, each `<tr>` `role="row"`, each `<th>` `role="columnheader"` or
  `role="rowheader"`, each `<td>` `role="cell"`, and the `<thead>` computed
  `display` is not `none` (it is visually hidden). (Roselli 2018/2024, NHS)
- **T12 data-label equals header text.** At 375px every `<td>` in a stacked
  table has a `data-label` whose value equals, character for character, the text
  of the `<th scope="col">` in its column. (USWDS, CFPB)
- **T13 Caption survives stacking.** At 375px every `<caption>`'s computed
  `display` is not `none` and it precedes the first card in DOM order. (NHS,
  USWDS, Roselli)
- **T14 Two-column tables stay grids.** A table with one row-header column and
  at most two data columns renders its `<td>` with computed
  `display: table-cell` at 375px. (USWDS "more than two columns"; NN/g; recorded
  as a disagreement with the brief's blanket stacking rule if that rule is kept)
- **T15 Numeric columns right-aligned.** At every viewport width where the table
  is a grid, cells matching `/^\$?[\d,]+(\.\d+)?%?$/` have computed
  `text-align: right`, and their header matches. (GOV.UK, NHS, CFPB, WAI tips)
- **T16 Key facts are a definition list.** The National Merit block renders as
  `<dl>` with one `<dt>`/`<dd>` pair per fact and no `<table>`. (GOV.UK summary
  list, USWDS)
- **T17 Debt captions say what is inside.** Every caption on a debt or borrowing
  table contains either "federal" or "private" (or both) and one of "who
  graduated", "who borrowed", "freshmen", so the population is in the caption,
  not a footnote. (TRP labelling request; CAP confusion finding; Scorecard
  definitions)
- **T18 Print does not stack.** In the print stylesheet no stacking rules apply;
  `<thead>` is displayed. (CFPB)
