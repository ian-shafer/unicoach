# State FAFSA / state-aid deadlines — batch C (OK OR PA RI SC SD TN TX UT VT VA WA WV WI WY)

Checked on **2026-09-14**. 37 rows, 15 states, 35 rows verified and 2 marked
`unverified` (one Rhode Island program, one forward-looking Texas date). Every
one of the 15 states has at least one verified row from a fetched page.
Companion data: [`state-deadlines-c.csv`](state-deadlines-c.csv).

## Method

What I did, in order:

1. Guessed the canonical URL of each state's higher-education or grant agency
   and fetched it directly with `httpx` and a browser User-Agent. No search
   engine was used for discovery.
2. When the landing page did not carry the deadline, I extracted its anchor list
   and followed the program page (Wisconsin Grant, TSAA, VTAG, OTAG, Utah
   Opportunity Scholarship, WV Higher Education Grant), or read the site's
   `sitemap.xml` (Texas, PHEAA).
3. For PDFs (Texas), I extracted the text with `pypdf` and quoted the guidelines
   directly.

What blocked me, honestly:

- **`websearch` has no Serper API key.** It returns a setup message, not
  results. DuckDuckGo HTML timed out. So every URL here was guessed or followed
  from a page, never searched.
- **`schev.edu` (Virginia) returns 403** to every non-browser User-Agent I
  tried. I read it through the Wayback Machine instead; those two rows are
  marked `source_tier=wayback` and carry the snapshot date.
- **`pheaa.org` (Pennsylvania) is a JavaScript single-page app.** Its HTML shell
  is 908 bytes and its Wayback snapshots are the same shell. The page TEXT,
  however, ships inside the site's own JS bundle
  `https://www.pheaa.org/assets/index-Dt0s8tn3.js`. I read the PA State Grant
  deadline table out of that bundle. The content is PHEAA's own, so I kept
  `source_tier=state_agency`, but the citation URL
  (`/grants/state-grant-program/apply-renew`) will not render for a non-browser
  fetcher. A human opening it in a browser will see the same table.
- **`archive.org/wayback/available` was rate-limited (HTTP 429)** for the whole
  session. `https://web.archive.org/web/2026/<url>` still worked and is what I
  used.
- **`studentaid.gov` was never readable**, as warned. No row here depends on it.
- **`riopc.edu` has no RI State Grant page** and `riheaa.org` no longer resolves
  in DNS. That is the one gap.
- Several state sites (tn.gov, ccri.edu, ric.edu, collegeforalltexans.com) 404
  or 403 on their obvious paths; I reached the Tennessee content through
  `collegefortn.org`, which the tn.gov page links to.

Conventions used in the CSV:

- `award_year` is the START year: 2026 means the 2026-27 award year.
- Where a state states a **recurring rule** without a calendar year ("April 15
  each year", "May 1, prior to the academic year"), I emit one dated row per
  cycle and say so in `notes`.
- Where a state prints dates with **no year at all** (Oklahoma's Promise, TN
  HOPE term deadlines, PA Summer Grant), I use `deadline_kind=wording` and quote
  the text rather than invent a year.
- Many 2026-27 dates listed here **have already passed** on the check date
  (2026-09-14). They are kept because the rule repeats and because a family
  reading the page needs to see what it missed.

## Per-state table

| State | Div.               | AY   | Kind    | Date       | Program                                                      | Deadline / wording                                                                                                | Tier         | Verified   |
| ----- | ------------------ | ---- | ------- | ---------- | ------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------- | ------------ | ---------- |
| OK    | West South Central | 2026 | wording | —          | Oklahoma Tuition Aid Grant (OTAG)                            | "For best consideration, you should complete and submit the FAFSA to the federal student aid application system…" | state_agency | yes        |
| OK    | West South Central | 2026 | wording | —          | Oklahoma's Promise                                           | "Application Deadlines: June 30 - 8th-11th graders (recommended deadline); Dec. 31 - 12th graders (electronic s…" | state_agency | yes        |
| OR    | Pacific            | 2026 | wording | —          | Oregon Opportunity Grant                                     | "Submit a FAFSA or ORSAA as soon as possible after October 1 each year ... Awards are first-come, first-served …" | state_agency | yes        |
| OR    | Pacific            | 2026 | date    | 2026-06-01 | Oregon Promise Grant (renewal)                               | 2026-06-01                                                                                                        | state_agency | yes        |
| OR    | Pacific            | 2027 | wording | —          | Oregon Promise Grant                                         | "The Oregon Promise Grant has rolling deadlines based on when the student graduates ... Students who graduate b…" | state_agency | yes        |
| PA    | Middle Atlantic    | 2026 | date    | 2026-05-01 | PA State Grant - first-time applicants at a 2-year private c | 2026-05-01                                                                                                        | state_agency | yes        |
| PA    | Middle Atlantic    | 2026 | date    | 2026-08-01 | PA State Grant - first-time applicants at a community colleg | 2026-08-01                                                                                                        | state_agency | yes        |
| PA    | Middle Atlantic    | 2026 | wording | —          | PA Summer Grant                                              | "September 1, for the summer period you are attending - You must submit the online PA Summer Grant application."  | state_agency | yes        |
| PA    | Middle Atlantic    | 2027 | date    | 2027-05-01 | PA State Grant - first-time applicants at a 2-year private c | 2027-05-01                                                                                                        | state_agency | yes        |
| PA    | Middle Atlantic    | 2027 | date    | 2027-08-01 | PA State Grant - first-time applicants at a community colleg | 2027-08-01                                                                                                        | state_agency | yes        |
| RI    | New England        | 2026 | wording | —          | Rhode Island Promise Scholarship                             | "The student must complete the FAFSA and any required FAFSA verification or other financial aid forms by the de…" | state_agency | yes        |
| RI    | New England        | 2026 | wording | —          | Rhode Island State Grant (need-based)                        | —                                                                                                                 | state_agency | unverified |
| SC    | South Atlantic     | 2026 | none    | —          | SC Need-based Grant; SC Lottery Tuition Assistance           | —                                                                                                                 | state_agency | yes        |
| SD    | West North Central | 2026 | none    | —          | South Dakota state aid (no FAFSA-dated program)              | —                                                                                                                 | state_agency | yes        |
| SD    | West North Central | 2026 | wording | —          | Per-institution FAFSA priority deadlines                     | "Colleges often have priority deadlines for the FAFSA ... Below is a list of South Dakota colleges and universi…" | secondary    | yes        |
| TN    | East South Central | 2026 | date    | 2026-04-01 | Tennessee Student Assistance Award (TSAA)                    | 2026-04-01                                                                                                        | state_agency | yes        |
| TN    | East South Central | 2026 | wording | —          | Tennessee Student Assistance Award (TSAA)                    | "To receive priority consideration, students are strongly encouraged to submit a FAFSA as soon as possible afte…" | state_agency | yes        |
| TN    | East South Central | 2026 | wording | —          | Tennessee HOPE Scholarship (TELS)                            | "Application Deadlines: Fall Semester 09/01; Spring Semester 03/01; Summer Semester 05/01."                       | state_agency | yes        |
| TN    | East South Central | 2027 | date    | 2027-04-01 | Tennessee Student Assistance Award (TSAA)                    | 2027-04-01                                                                                                        | state_agency | yes        |
| TN    | East South Central | 2027 | date    | 2027-04-01 | Tennessee Promise                                            | 2027-04-01                                                                                                        | state_agency | yes        |
| TN    | East South Central | 2027 | date    | 2026-11-02 | Tennessee Promise (state application)                        | 2026-11-02                                                                                                        | state_agency | yes        |
| TX    | West South Central | 2026 | date    | 2026-01-15 | TEXAS Grant and other state financial aid (state priority de | 2026-01-15                                                                                                        | state_agency | yes        |
| TX    | West South Central | 2027 | date    | 2027-01-15 | TEXAS Grant and other state financial aid (state priority de | 2027-01-15                                                                                                        | state_agency | unverified |
| UT    | Mountain           | 2026 | wording | —          | Utah Promise Grant                                           | "Complete a FAFSA. Demonstrate financial need."                                                                   | state_agency | yes        |
| UT    | Mountain           | 2027 | date    | 2027-01-29 | Utah Opportunity Scholarship (priority deadline, class of 20 | 2027-01-29                                                                                                        | state_agency | yes        |
| UT    | Mountain           | 2027 | date    | 2027-03-18 | Utah Opportunity Scholarship (final deadline, class of 2027) | 2027-03-18                                                                                                        | state_agency | yes        |
| VT    | New England        | 2026 | wording | —          | Vermont Grant                                                | "applications are considered on a first-come, first-served basis as long as funding is available"                 | state_agency | yes        |
| VA    | South Atlantic     | 2026 | wording | —          | Virginia Commonwealth Award; Virginia Guaranteed Assistance  | "This page provides a list of financial aid deadlines for Virginia's public and private institutions participat…" | wayback      | yes        |
| VA    | South Atlantic     | 2026 | date    | 2026-09-15 | Virginia Tuition Assistance Grant (VTAG)                     | 2026-09-15                                                                                                        | wayback      | yes        |
| VA    | South Atlantic     | 2026 | date    | 2026-12-01 | Virginia Tuition Assistance Grant (VTAG) - late window       | 2026-12-01                                                                                                        | wayback      | yes        |
| VA    | South Atlantic     | 2027 | date    | 2027-09-15 | Virginia Tuition Assistance Grant (VTAG)                     | 2027-09-15                                                                                                        | wayback      | yes        |
| WA    | Pacific            | 2026 | wording | —          | Washington College Grant (WA Grant)                          | "It's never too early to plan ahead or too late to apply."                                                        | state_agency | yes        |
| WV    | South Atlantic     | 2026 | date    | 2026-04-15 | WV Higher Education Grant                                    | 2026-04-15                                                                                                        | state_agency | yes        |
| WV    | South Atlantic     | 2027 | date    | 2027-04-15 | WV Higher Education Grant                                    | 2027-04-15                                                                                                        | state_agency | yes        |
| WV    | South Atlantic     | 2027 | date    | 2027-03-01 | PROMISE Scholarship                                          | 2027-03-01                                                                                                        | state_agency | yes        |
| WI    | East North Central | 2026 | wording | —          | Wisconsin Grant (WG-UW, WG-TC, WG-TR, WG-PNP)                | "Application Priority Date by which students should apply for financial aid to receive maximum eligibility."      | state_agency | yes        |
| WY    | Mountain           | 2026 | none    | —          | Hathaway Scholarship (need-based component)                  | —                                                                                                                 | state_agency | yes        |

## Gaps

One state is incomplete and two rows elsewhere are soft. Nothing else in the CSV
is guessed.

**Rhode Island — the RI State Grant (need-based).** Row
`RI / 2026 / Rhode Island State Grant` is `verified=unverified` with an empty
deadline. I could not establish whether the program still exists in 2026-27, let
alone its date.

- What I tried: `riopc.edu` (the RI Office of the Postsecondary Commissioner)
  homepage and full link list; its own site search
  `https://riopc.edu/?s=state+grant` (returns Workforce Pell news and board
  policies, nothing about a state grant); `riheaa.org` (DNS does not resolve —
  RIHEAA is gone); `ccri.edu`, `ric.edu` and `uri.edu` financial-aid paths (all
  404 or bare).
- What a human must open: call RI OPC at 401-736-1100, or open
  `https://www.uri.edu/enrollment/` and `https://www.ccri.edu/` financial aid
  pages in a real browser and look for "Rhode Island State Grant". If the
  program is dead, the row becomes `deadline_kind=none`.
- Do **not** publish a Rhode Island state-grant date until this is answered. A
  stale "March 1" from an old blog post is exactly the failure mode this brief
  is trying to avoid.

**Texas 2027-28.** Row `TX / 2027 / 2027-01-15` is `unverified`. The January 15
rule is real and statutory (TAC 22.6, quoted in the FY2027 guidelines), but
THECB has moved it before and had published no 2027-28 notice on the check date.
Re-check `https://www.highered.texas.gov/student-financial-aid-programs/` under
"Notices" once the FY2028 guidelines appear.

**West Virginia PROMISE, class of 2026.** CFWV now shows only the class of 2027
(1 Mar 2027). The 2026-27 date is not on the page any more, so I did not record
one.

**Territories (PR, GU, VI).** Not researched. No time was left after the 15
states. Nothing about them is in the CSV, which is better than a guess.

## Hazards

Things that could mislead a family if the page copies a source uncritically.

1. **"October 1" is wrong on live state pages.** Oregon (Opportunity Grant) and
   Tennessee (TSAA) both still tell students to file "as soon as possible after
   October 1". The FAFSA has not opened on 1 October since 2023-24; the 2026-27
   form opened 24 Sept 2025. If Unicoach quotes those sentences verbatim it will
   repeat a stale date. Quote the intent ("as soon as the form opens"), not the
   number.

2. **A deadline can be an APPLICATION, not the FAFSA.** Tennessee Promise needs
   the THEC portal application by 2 Nov 2026 AND the FAFSA by 1 Apr 2027. West
   Virginia PROMISE needs its own application AND the FAFSA by 1 Mar 2027.
   Virginia VTAG needs a VTAG form handed to the college by 15 Sept — the FAFSA
   does not substitute. Oklahoma's Promise and Utah's Opportunity Scholarship
   are separate applications too. A page that shows one date per state will make
   families miss the other half.

3. **Pennsylvania has TWO dates and the one that applies depends on the school
   type.** 1 May for renewals and first-time students at 4-year/2-year private
   colleges; 1 August for community colleges, PA Open-Admission institutions,
   business/trade/technical schools, hospital schools of nursing, and
   non-transferable 2-year programs. Showing only "May 1" would push
   community-college students to think they are late when they are not — and
   showing only "August 1" would cost a university student their grant.

4. **"No date" does not mean "no hurry".** Oklahoma, Oregon, Vermont and
   Wisconsin all award until funds run out. Oklahoma says outright that how
   early you apply "may be the deciding factor". A page that renders these as a
   blank cell is actively misleading; render them as first-come, first-served.

5. **Texas moved its own deadline within living memory.** The 2025-26 priority
   deadline was extended from 15 Jan to 15 Feb 2025 by memo because the FAFSA
   opened late. Any Texas date must be shown as the standing rule plus "confirm
   with THECB", not as a fixed fact.

6. **Virginia, Wisconsin, South Carolina, South Dakota and Wyoming have NO state
   date — the college sets it, and the spread is months.** Virginia runs from 1
   Feb (VCU) to 15 Jul (Eastern Shore CC). Wyoming runs 1 Feb to 15 Apr. South
   Dakota runs 1 Jan to 20 Apr. A single per-state cell cannot express this.
   These states need a "set by your college" treatment plus a link to the
   state's own list.

7. **The Oregon Promise application is CLOSED right now** for anyone who
   graduated before 30 June 2026, and reopens in October 2026 for the next
   cohort. A static table would show a date that nobody can act on.

8. **Virginia's citation is a Wayback snapshot (31 Aug 2026), not the live
   page.** schev.edu blocks machine fetching. If Unicoach refreshes this data on
   a schedule, Virginia will need a browser-based fetch or a manual step,
   otherwise it will silently go stale.

9. **Pennsylvania's citation URL does not render without JavaScript.** Same
   refresh risk as Virginia, for the opposite technical reason.
