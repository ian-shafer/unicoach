# How common is out-of-state attendance at US public four-year institutions?

Sub-research for product brief 0005 ("the price you would pay"). External
evidence only. Every number below is either quoted from a fetched primary source
or computed by me from raw IPEDS data files, with the arithmetic shown.

## Headline answer

**Out-of-state is not a rounding error, and it is not the majority.**

- Fall 2022, **public 4-year institutions**: **18.6%** of first-time
  degree/certificate-seeking undergraduates whose residence was a US state came
  from a **different state** than the institution. (21.3% if you also count
  foreign, outlying-area and unknown-residence students.)
- That share has **risen every measured cycle**: 14.7% (2008) -> 18.6% (2022),
  +3.9 points.
- It is **highly concentrated**: 181 public 4-years (31% of public 4-year
  first-year students) are at or above 25% out-of-state; a handful are 60-84%
  out-of-state. But 359 of 753 (45% of students) are under 10%.
- On the **applicant** side the number is far larger: **71.9%** of 2025-26
  Common App first-year applicants applied to at least one out-of-state
  institution.

So: a price ranking keyed to the in-state figure is right for roughly 4 in 5
_enrolees_ at publics, but wrong for roughly 7 in 10 _applicants'_ consideration
sets, because the search funnel is much wider than the enrolment outcome.

---

## Method (what I fetched, what failed)

**Worked**

- Direct HTTPS fetches of `nces.ed.gov` Digest tables (HTML) — e.g.
  <https://nces.ed.gov/programs/digest/d23/tables/dt23_309.10.asp>,
  <https://nces.ed.gov/programs/digest/d24/tables/dt24_303.70.asp>.
- Direct download of **IPEDS complete data files** from the NCES Data Center:
  `EF{year}C.zip` (Fall Enrollment, residence and migration of first-time
  students) and `HD{year}.zip` (institutional directory, gives SECTOR and
  STABBR), for 2008, 2012, 2016, 2018, 2020, 2022. Example URLs:
  <https://nces.ed.gov/ipeds/datacenter/data/EF2022C.zip>,
  <https://nces.ed.gov/ipeds/datacenter/data/HD2022.zip>,
  <https://nces.ed.gov/ipeds/datacenter/data/EF2022C_Dict.zip>. All returned
  HTTP 200. I computed the public-4-year figures myself from these files; they
  are NOT quoted from a published NCES table, because NCES publishes residence
  and migration by **state**, not by **sector**. The inputs are primary; the
  aggregation is mine.
- Common App primary PDFs, linked from <https://www.commonapp.org/research>:
  - End-of-season report 2025-26:
    <https://commonapp.org/files/DAR/deadline-updates/2025-26/Common-App-End-of-Season-Report_25-26.pdf>
  - March 1 2026 deadline update:
    <https://www.commonapp.org/files/DAR/deadline-updates/2025-26/Common-App-Deadline-Update-2026-March.pdf>
    The in-state/out-of-state numbers live in a chart image (Figure 26), not in
    the PDF text layer; I extracted the embedded PNG and read the printed data
    labels off it.

**Failed — stated explicitly**

- The `websearch` skill has no Serper key (per task brief); not attempted.
- **DuckDuckGo** (`html.duckduckgo.com` and `lite.duckduckgo.com`) returned HTTP
  **202** with an anti-bot page and **zero results** on every query, all session
  long.
- **Bing** returned HTTP 200 but the result list was unrelated Microsoft sign-in
  pages (bot-detection junk). **Mojeek** returned results for generic queries
  but **zero** results for every out-of-state / nonresident-enrolment query I
  tried.
- Consequence: **I could not verify any secondary reporting** (Hechinger Report,
  Chronicle, Brookings, Jaquette & Curs on nonresident recruiting). I have not
  cited any of them. The trend evidence in section 3 is therefore computed by me
  from IPEDS microdata, which is stronger than reporting anyway, but the
  "flagships are recruiting out-of-state on purpose" _narrative_ is unverified
  here.
- `commonapp.org` PDF URLs for earlier seasons (2023-24, 2024-25) 404'd; the
  reports-and- insights index page is JS-rendered and exposes only the three
  current reports.
- Digest table 303.25 does not exist in the d23 issue (404); I used **303.70**
  instead.
- **NCES Digest tables 309.10 / 309.20 are all-sector, not public-4-year.** They
  are quoted below only as a cross-check on the national picture.

---

## 1. Out-of-state share at public four-year institutions (the number that matters)

### 1a. What I computed, and how

Source files: `EF2022C` (variable `EFRES01` = "All first-time degree/certificate
seeking undergraduates, total"; variable `EFCSTATE` = state of residence) joined
to `HD2022` on `UNITID` to get `SECTOR` and the institution's own state
`STABBR`.

Key `EFCSTATE` code values, quoted verbatim from the IPEDS dictionary
(`EF2022C_Dict.zip`, sheet "Frequencies"):

| code | label                                                           |
| ---- | --------------------------------------------------------------- |
| 99   | All first-time degree/certificate seeking undergraduates, total |
| 58   | US total                                                        |
| 57   | State unknown                                                   |
| 89   | Outlying areas total                                            |
| 90   | Foreign countries                                               |
| 98   | Residence not reported                                          |

Filter: `SECTOR == 1` ("Public, 4-year or above"). 768 institutions matched;
**15 reported only a total and no residence detail**, so I dropped them and
computed on the remaining **753** (which carry 1,372,263 of the 1,378,437
first-year students, i.e. 99.6%).

### 1b. The arithmetic

```
Public 4-year, Fall 2022, first-time degree/certificate-seeking undergraduates
  Total (EFCSTATE=99)                        1,372,263
  US state residents (EFCSTATE=58)           1,326,578
  Residents of the institution's own state   1,080,043
  Foreign countries (EFCSTATE=90)               37,272
  Outlying areas (EFCSTATE=89)                   5,329
  State unknown / not reported (57 + 98)        10,375

Out-of-state (US domestic) = 1,326,578 - 1,080,043 = 246,535
Out-of-state share of US residents = 246,535 / 1,326,578 = 0.1858  ->  18.6%
In-state share of ALL first-years  = 1,080,043 / 1,372,263 = 0.7871 ->  78.7%
Non-in-state share of ALL          = 1 - 0.7871              = 0.2129 ->  21.3%
```

**Answer: 18.6% out-of-state among US-resident first-year students at public
4-years; 21.3% of all first-year students at public 4-years are not in-state
residents.**

Two of the 753 units are large fully-online operations whose "state" is nominal
(Arizona State University Digital Immersion, 90.2% out-of-state; Purdue
University Global, 88.2%). Excluding both, the 2022 figure is **18.4%** — the
conclusion does not depend on them.

### 1c. Cross-check against a published NCES table (all sectors)

NCES _Digest of Education Statistics_ 2023, **Table 309.10**, "Residence and
migration of all first-time degree/certificate-seeking undergraduates in
degree-granting postsecondary institutions, by state or jurisdiction: Fall
2022". <https://nces.ed.gov/programs/digest/d23/tables/dt23_309.10.asp>

Quoted United States row (columns: total first-time enrollment in institutions
located in the state; state residents enrolled in institutions in any state; ...
in their home state; ratio of in-state students to first-time enrollment in
their home state; ... to residents enrolled in any state; migration out of
state; into state; net):

> United States 2,749,449 2,657,456 2,082,875 0.76 0.78 574,581 666,574 91,993

Arithmetic: 2,082,875 / 2,749,449 = 0.7576 -> **75.8% of all US first-year
students, all sectors, attend in their home state**; 574,581 / 2,657,456 =
0.2162 -> **21.6% of US-resident first-year students cross a state line**.
Higher than the public-4-year 18.6% because private non-profits are ~50%
out-of-state (see 4b). This table is _not_ sector-broken-out, which is why I
computed 1b from the raw files.

---

## 2. All undergraduates paying non-resident rates — NOT AVAILABLE

**I could not find this number, and I believe it does not exist in IPEDS.** The
IPEDS Fall Enrollment residence-and-migration component (`EF{year}C`) collects
state of residence for **first-time degree/certificate-seeking undergraduates
only** — the dictionary label is literally "All first-time degree/certificate
seeking undergraduates, total". The Digest table title is likewise "Residence
and migration of all **first-time** degree/certificate-seeking undergraduates".
There is no all-undergraduate residence variable.

Nor does IPEDS report a headcount of students _billed_ the non-resident rate.
Residency for tuition purposes is a legal determination made by each
state/institution (a student can establish residency after a year, or hold
non-resident status while living in-state), and it is not the same thing as
state of origin at matriculation. **Do not treat 18.6% as the share of all
public-4-year undergraduates paying non-resident tuition.** It is a first-year
migration figure; the standing-stock figure is unpublished, and plausibly
somewhat lower (some non-residents reclassify) — but I have no source for that
direction and am not asserting it as fact.

Practical read for the product: 18.6% is the best available proxy, it is
measured at exactly the moment a search product is used (choosing where to
enrol), and it is an _understatement_ of the population the search surface
serves (see section 5).

---

## 3. Trend: yes, out-of-state enrolment at publics is rising

Same computation as 1b, repeated on the IPEDS `EF{year}C` + `HD{year}` files for
each collection year (the residence/migration component is collected in even
years only).

| Year      | US-resident first-years at public 4-yrs | Of whom in-state | Out-of-state share | Institutions |
| --------- | --------------------------------------- | ---------------- | ------------------ | ------------ |
| Fall 2008 | 1,032,655                               | 881,110          | 14.68%             | 618          |
| Fall 2012 | 1,092,692                               | 927,610          | 15.11%             | 652          |
| Fall 2016 | 1,213,514                               | 1,020,784        | 15.88%             | 703          |
| Fall 2018 | 1,269,300                               | 1,063,509        | 16.21%             | 727          |
| Fall 2020 | 1,240,562                               | 1,040,471        | 16.13%             | 724          |
| Fall 2022 | 1,326,578                               | 1,080,043        | 18.58%             | 753          |

Arithmetic for the endpoints:

```
2008: 1 - (881,110 / 1,032,655) = 0.1468 -> 14.7%
2022: 1 - (1,080,043 / 1,326,578) = 0.1858 -> 18.6%
Change: 18.58 - 14.68 = +3.90 percentage points over 14 years
Relative: 0.1858 / 0.1468 = 1.27 -> a 27% relative increase
```

The direction is monotone across every point except a flat 2018->2020 (the COVID
cycle). The count of out-of-state first-years at publics rose from 151,545
(2008) to 246,535 (2022), i.e. +63%, while total first-years at publics rose
only +28% — out-of-state grew more than twice as fast as the sector.

**Caveat:** institution counts differ by year (618 -> 753 reporting units),
partly from new online-heavy units. That inflates the last step somewhat; the
2008->2018 movement (14.7% -> 16.2%) is on a more stable base and is already
clearly upward.

**I could not verify the secondary reporting** on flagship out-of-state
recruiting (Hechinger / Chronicle / Brookings / Jaquette) because all general
web search was blocked this session. I am not citing any such source rather than
risk inventing one.

---

## 4. Distribution: concentrated, not uniform

### 4a. It is a minority of campuses carrying most of the exposure

Computed on the same 753 public 4-year institutions (share = out-of-state /
US-resident first-years at that institution):

- **>= 25% out-of-state:** 181 institutions, 412,234 first-year students =
  **31.1%** of all public-4-year first-years.
- **>= 40% out-of-state:** 88 institutions, 222,071 students = **16.7%**.
- **>= 50% out-of-state:** 48 institutions, 98,522 students = **7.4%**.
- **< 10% out-of-state:** 359 institutions = **45.4%** of students.
- **Median institution:** 10.8% out-of-state.

Read: the median public 4-year is overwhelmingly local, but roughly a third of
public-4-year first-year seats sit at campuses where at least a quarter of the
incoming class is paying non-resident price. Those campuses are
disproportionately the well-known flagships that a search product surfaces
first.

### 4b. Named institutions (Fall 2022, US-resident first-year students, >= 1,000 students)

| Institution                                | State | US-resident first-years | In-state | Out-of-state share |
| ------------------------------------------ | ----- | ----------------------- | -------- | ------------------ |
| United States Air Force Academy            | CO    | 1,024                   | 54       | 94.7%              |
| United States Military Academy             | NY    | 1,152                   | 79       | 93.1%              |
| United States Naval Academy                | MD    | 1,151                   | 85       | 92.6%              |
| Arizona State University Digital Immersion | AZ    | 2,932                   | 288      | 90.2%              |
| Purdue University Global                   | IN    | 1,270                   | 150      | 88.2%              |
| University of Vermont                      | VT    | 2,966                   | 486      | 83.6%              |
| Jackson State University                   | MS    | 1,121                   | 309      | 72.4%              |
| Coastal Carolina University                | SC    | 2,639                   | 824      | 68.8%              |
| University of Delaware                     | DE    | 4,846                   | 1,635    | 66.3%              |
| Montana State University                   | MT    | 3,734                   | 1,305    | 65.1%              |
| North Dakota State University-Main Campus  | ND    | 2,504                   | 877      | 65.0%              |
| The University of Alabama                  | AL    | 7,985                   | 2,799    | 64.9%              |
| University of Mississippi                  | MS    | 4,416                   | 1,581    | 64.2%              |
| University of New Hampshire-Main Campus    | NH    | 2,912                   | 1,093    | 62.5%              |
| University of North Dakota                 | ND    | 1,730                   | 670      | 61.3%              |
| Tennessee State University                 | TN    | 3,538                   | 1,371    | 61.2%              |
| University of Arkansas                     | AR    | 7,065                   | 2,800    | 60.4%              |
| University of Rhode Island                 | RI    | 3,374                   | 1,358    | 59.8%              |
| West Virginia University                   | WV    | 4,534                   | 1,916    | 57.7%              |
| University of Oregon                       | OR    | 5,250                   | 2,269    | 56.8%              |

Selected flagships asked about in the brief:

| Institution                               | Out-of-state share |
| ----------------------------------------- | ------------------ |
| University of Vermont                     | 83.6%              |
| University of Delaware                    | 66.3%              |
| The University of Alabama                 | 64.9%              |
| University of Mississippi                 | 64.2%              |
| West Virginia University                  | 57.7%              |
| University of Oregon                      | 56.8%              |
| University of South Carolina-Columbia     | 47.4%              |
| University of Michigan-Ann Arbor          | 46.4%              |
| Indiana University-Bloomington            | 45.2%              |
| Arizona State University Campus Immersion | 38.7%              |

(Also note the service academies at 93-95% "out-of-state" — they are a data
artefact for price purposes, since they charge no tuition at all.)

### 4c. By state of the institution (public 4-years aggregated)

| Institution's state | US-resident first-years | In-state | Out-of-state share |
| ------------------- | ----------------------- | -------- | ------------------ |
| VT                  | 3,750                   | 909      | 75.8%              |
| NH                  | 4,582                   | 1,926    | 58.0%              |
| RI                  | 4,048                   | 1,897    | 53.1%              |
| ND                  | 5,964                   | 2,885    | 51.6%              |
| MS                  | 11,491                  | 5,706    | 50.3%              |
| MT                  | 6,823                   | 3,391    | 50.3%              |
| DE                  | 8,519                   | 4,501    | 47.2%              |
| SD                  | 5,367                   | 2,974    | 44.6%              |
| AZ                  | 31,337                  | 17,378   | 44.5%              |
| OR                  | 13,231                  | 7,504    | 43.3%              |
| IA                  | 12,083                  | 7,078    | 41.4%              |
| AL                  | 26,281                  | 15,420   | 41.3%              |

At the other end: Texas 3.4%, California 5.8%, New Jersey 6.1% — big-population
states whose publics are almost entirely local.

### 4d. The counterweight: most undergraduates are nowhere near this problem

Sector breakdown of Fall 2022 first-time undergraduates, computed from
`EF2022C` + `HD2022` (same method):

| Sector                  | US-resident first-years | In-state  | Out-of-state share | Share of all first-years |
| ----------------------- | ----------------------- | --------- | ------------------ | ------------------------ |
| Public 4yr+             | 1,326,578               | 1,080,043 | 18.6%              | 47.8%                    |
| Private nonprofit 4yr+  | 491,274                 | 245,243   | 50.1%              | 17.7%                    |
| Private for-profit 4yr+ | 49,644                  | 25,859    | 47.9%              | 1.8%                     |
| Public 2yr              | 759,308                 | 727,684   | 4.2%               | 27.4%                    |
| Private nonprofit 2yr   | 7,632                   | 4,434     | 41.9%              | 0.3%                     |
| Private for-profit 2yr  | 49,910                  | 39,795    | 20.3%              | 1.8%                     |
| Public <2yr             | 20,597                  | 20,146    | 2.2%               | 0.7%                     |
| PNP <2yr                | 2,450                   | 2,091     | 14.6%              | 0.1%                     |
| PFP <2yr                | 68,496                  | 63,483    | 7.3%               | 2.5%                     |

And on total (not first-time) undergraduate enrolment, NCES _Digest_ 2024
**Table 303.70**, "Total undergraduate fall enrollment in degree-granting
postsecondary institutions, by attendance status, sex of student, and control
and level of institution: Selected years, 1970 through 2023"
<https://nces.ed.gov/programs/digest/d24/tables/dt24_303.70.asp>, Fall 2023:

```
All levels, total undergraduates      15,825,762   (of which public 12,227,529)
4-year institutions,   total          11,109,174   (of which public  7,713,622)
2-year institutions,   total           4,716,588   (of which public  4,513,907)

Public 2-year share of all undergrads = 4,513,907 / 15,825,762 = 0.2852 -> 28.5%
Public 4-year share of all undergrads = 7,713,622 / 15,825,762 = 0.4874 -> 48.7%
```

So ~28.5% of all US undergraduates are at public two-year colleges, where
out-of-state is 4.2% of first-years and residency is effectively a non-issue.
Combined with the 45% of public-4-year students at campuses under 10%
out-of-state, the "in-state figure is fine" case covers a genuine majority of
_enrolments_. The exposure is concentrated in exactly the selective,
nationally-recruiting publics that a college-search product ranks highest.

---

## 5. The applicant funnel is much wider than the enrolment outcome

Common Application, **End-of-season report, 2025-2026: First-year application
trends** (published August 20, 2026), Figure 26, "Growth in applicants by in-
and out-of-state application behavior since 2016-17". PDF:
<https://commonapp.org/files/DAR/deadline-updates/2025-26/Common-App-End-of-Season-Report_25-26.pdf>

The figure's own printed data labels for the 2025-26 season (read off the
embedded chart image; these numbers are not in the PDF text layer):

- **Both in- and out-of-state: 787,646 (+6%)**
- **Only in-state: 429,796 (+2%)**
- **Only out-of-state: 309,886 (-6%)**

Arithmetic:

```
Total distinct applicants        = 429,796 + 309,886 + 787,646 = 1,527,328
Applied to >= 1 out-of-state     = 309,886 + 787,646           = 1,097,532
Share applying out-of-state      = 1,097,532 / 1,527,328 = 0.7186 -> 71.9%
Share applying in-state only     =   429,796 / 1,527,328 = 0.2814 -> 28.1%
```

The report's own narrative text on the same trend (quoted verbatim, page 23):

> "Across Figures 25 and 26, the largest increases in applicants were among
> applicants applying to a mix of public and private institutions (4%) and
> applicants applying to a mix of in-state and out-of-state institutions (6%)
> via the Common App."

And from the March 1 2026 deadline update (page 22),
<https://www.commonapp.org/files/DAR/deadline-updates/2025-26/Common-App-Deadline-Update-2026-March.pdf>:

> "Figure 24 similarly looks at the applicant level, but now examines applicants
> who apply only to members in-state, only to members out-of-state, or both. The
> number of applicants applying only to out-of-state institutions declined
> compared to this point in 2024-25, with a greater number of applicants
> applying to in-state institutions only or both in- and out-of-state
> institutions."

**Caveats, stated plainly:**

- Common App members are ~1,100 mostly four-year institutions and skew selective
  and private; community colleges are barely represented. This population is not
  all US applicants. It is, however, a good proxy for _the population that uses
  a college-search product with a coach_.
- "Out-of-state" here means the member institution is outside the applicant's
  state — it includes private colleges, where in-state/out-of-state pricing does
  not exist. So 71.9% is the share applying across a state line, **not** the
  share exposed to non-resident tuition. The out-of-state-_public_ subset is
  smaller and I could not isolate it from published data.
- I could not retrieve NACAC's _State of College Admission_ report. The landing
  page <https://www.nacacnet.org/state-of-college-admission-report/> resolves
  (HTTP 200) but I did not obtain a downloadable report with an out-of-state
  application statistic, and I am not quoting a number from it.

---

## What this means for the "cheaper" ranking decision (sizing only, not a recommendation)

- **Not 5%, not 25% — call it ~19% of public-4-year enrolees and ~30% of
  public-4-year first-year seats sitting at heavily non-resident campuses.** The
  harm is real and material, well past a rounding error.
- The harm is **not uniform**: it is near-zero at community colleges and
  regional publics, and extreme (50-84% of the incoming class) at the
  flagship-tier publics that a "best value" or "cheapest first" ranking
  naturally floats to the top. A ranking bug therefore lands hardest exactly
  where the ranking is most consulted.
- The **search funnel is wider than enrolment**: 72% of Common App applicants
  apply across a state line. A search/consideration product serves the
  applicant, not the enrolee, so the enrolment-based 19% understates the
  fraction of _sessions_ where the in-state number is the wrong number to sort
  on.
- No published figure exists for "all undergraduates paying non-resident rates";
  do not let a spec cite one.
