# 0008 — Source assurance landscape: is the softness axis real?

Research report for brief `0008-how-soft-is-this-number`. Written 2026-09-05.
Scope: question 1 of the brief's open assumptions — how each of our four sources
is actually produced and verified, and what the true tiers are.

## Method

- **Repo claims** are grepped from the working tree at
  `/Users/ian/Work/unicoach` (`main`), cited `file:line`. No code was run or
  changed; this report is the only file written.
- **College Scorecard** claims come from two files I downloaded on 2026-09-05:
  `https://collegescorecard.ed.gov/files/CollegeScorecardDataDictionary.xlsx`
  (727,278 bytes) and
  `https://collegescorecard.ed.gov/files/InstitutionDataDocumentation.pdf`
  (531,031 bytes, "Version: September 2025", 56 pages). Counts below were made
  by reading the dictionary's `Institution_Data_Dictionary` sheet with pandas
  and the PDF text with pypdf, not by eye.
- **IPEDS** and **CDS/legal** claims were gathered by two delegated research
  agents against NCES / commondataset.org / eCFR / FTC primary sources; every
  such claim carries its own URL below.
- **Not obtained:** Serper web search was unavailable to the CDS agent, so the
  misreporting list is what could be fetched directly, not an exhaustive sweep.
  No NCES "IC/SFA are not verified" blanket statement exists — see §1.1.5.
  Nothing here is legal advice.

---

## 1. How each source is actually produced

### 1.1 IPEDS (both `IPEDS_SFA` and `IPEDS_IC_AY`)

**Institution-reported, mandatory, edit-checked, self-attested, imputed for
non-response, and not independently audited.**

1. **Mandatory.** NCES, _About IPEDS_ (https://nces.ed.gov/ipeds/about-ipeds):
   "The completion of all IPEDS surveys is mandatory for institutions that
   participate in or are applicants for participation in any federal student
   financial aid program" — authority cited as 20 U.S.C. 1094 §487(a)(17) and 34
   CFR 668.14(b)(19). The Scorecard's own documentation says the same: "Under
   the Higher Education Act, all institutions that participate in Title IV
   federal student aid programs must complete the IPEDS questionnaires"
   (`InstitutionDataDocumentation.pdf` p.38).
2. **Enforced.** NCES _Statutory Requirements_
   (https://surveys.nces.ed.gov/ipeds/public/statutory-requirement): 34 CFR
   668.84–.86 allow a fine or loss of Title IV eligibility, and "Each year, the
   Office of Federal Student Aid issues fine notices to institutions for not
   completing their IPEDS surveys". Consequence: "Mandatory participation
   consequently results in a response rate of nearly 100% for each IPEDS survey
   component"
   (https://nces.ed.gov/ipeds/survey-components/ipeds-survey-methodology).
3. **Attested, not audited.** Same methodology page: the CEO appoints a
   keyholder "responsible for ensuring that the institution's submitted survey
   data are correct and complete", and "locking indicates to NCES that the data
   submitted are accurate, true, and complete." That is an attestation by the
   school, not a third-party check.
4. **Edit-checked.** "The web-based survey instrument contains edit checks to
   detect major reporting errors ... and compares current responses to data
   reported the previous year"; "All edit checks have to be resolved (confirmed
   or explained) before each survey is permitted to be locked." Some errors are
   fatal and force a Help Desk call (SFA 2024-25 survey package,
   https://nces.ed.gov/ipeds/use-the-data/download-survey-material/2024/student%20financial%20aid/package_7_16.pdf).
   Named SFA edits include "The number of full-time, first-time undergraduate
   students receiving federal grants cannot exceed the number ... who received
   any financial aid" — i.e. NCES runs the same _class_ of internal-coherence
   check the brief's candidate C4 proposes, on the SFA file, before publication.
5. **Not audited.** Only the **Finance** component is tied to audited statements
   ("should be provided from your institution's audited General Purpose
   Financial Statements", Finance 2024-25 package). For **IC and SFA** the
   delegated search found no NCES statement of any independent audit or
   verification. The closest explicit NCES disclaimer ("have not been subjected
   to independent verification by the U.S. Department of Education") is scoped
   to Clery **crime** data on College Navigator and must not be quoted about
   SFA/IC.
6. **Imputed, and flagged per cell.** "All components of the annual IPEDS
   collection are subject to imputation for nonresponse", by Carry Forward,
   Nearest Neighbor or Group Median. Every measured variable carries an
   `X`-prefixed flag with a published 13-code list. **We already ingest this**:
   `db/src/main/kotlin/ed/unicoach/db/models/IpedsImputationFlag.kt:38` (`R`
   reported), `:50` (`P` carry forward), `:53` (`N` nearest neighbor), `:65`
   (`L` group median). Measured scale, from the released files: in `SFA2223`
   (5,653 rows) `XSCUGRAD` is `R` 5,649 / `P` 4 — imputation is ~0.07% of
   institutions; in `IC2023_AY` (3,825 rows) `XFEE2` is `R` 3,272 / `A` 288 /
   `Z` 263 / `C` 2, with no `L`/`N`/`P` at all in the price fields checked.
7. **Revisable for about a year.** Provisional release ~9 months after
   collection, final ~12 months later; institutions may correct through the
   Prior Year Revision system for one further collection cycle
   (https://nces.ed.gov/ipeds/report-your-data). A published IPEDS number is not
   frozen.

**Verdict:** IPEDS is institution-reported. It is _not_ a self-report in the CDS
sense: reporting is compelled, near-universal, machine-edit-checked against
prior-year and cross-component values, followed up by a help desk, formally
attested, and per-cell flagged when the publisher (not the school) supplied the
number. Nobody audits it.

### 1.2 College Scorecard — mostly not its own source

This is the decisive finding for the brief's shape question.

The Scorecard data dictionary carries a `SOURCE` column per variable. Across the
`Institution_Data_Dictionary` sheet the distribution is: **NSLDS 2,208 rows,
IPEDS 869, (blank) 278, Treasury 204, FSA 11, OPE 6, FSA/IPEDS 5, ACS 2,
IPEDS/NSLDS 1, DOL/IPEDS 1, PEPS 1.**

Now the 18 cells **we actually load** from the Scorecard
(`college/src/main/kotlin/ed/unicoach/college/CanonicalMoneyLoader.kt`):

| Our cell                                | Scorecard variable                 | Loader         | Dictionary `SOURCE` |
| --------------------------------------- | ---------------------------------- | -------------- | ------------------- |
| tuition & fees, in-state / out-of-state | `TUITIONFEE_IN` / `TUITIONFEE_OUT` | `:922`, `:923` | IPEDS               |
| housing & food, on / off campus         | `ROOMBOARD_ON` / `ROOMBOARD_OFF`   | `:924`, `:925` | IPEDS               |
| books & supplies                        | `BOOKSUPPLY`                       | `:926`         | IPEDS               |
| other expenses, on / off / with family  | `OTHEREXPENSE_*`                   | `:927`–`:929`  | IPEDS               |
| published cost blend                    | `COSTT4_A`                         | `:988`         | IPEDS               |
| average net price + 5 income bands      | `NPT4[1-5]_{PUB,PRIV}`             | `:994`         | IPEDS               |
| Pell share                              | `PCTPELL`                          | `:1021`        | IPEDS               |
| median debt at completion               | `GRAD_DEBT_MDN`                    | `:1029`        | **NSLDS**           |
| median earnings 10y                     | `MD_EARN_WNE_P10`                  | `:1037`        | **Treasury**        |

**16 of our 18 Scorecard cells are re-published IPEDS. Exactly 2 are federal
administrative records.** The technical documentation says it in prose too: "The
elements in this category are elements from IPEDS" and "All cost elements are
derived from data reported to the IPEDS Institutional Characteristics and
Student Financial Aid (SFA) components" (p.12–13). And the Scorecard passes
through **no** per-cell imputation flag — our loader records that at
`CanonicalMoneyLoader.kt:911-914`: "The Scorecard publishes no per-cell
imputation code".

So the Scorecard copy of a tuition figure is _strictly less_ informative than
the IPEDS original: same number, same institutional origin, minus the `X*` flag.
Our existing precedence (`CanonicalMoneyLoader.kt:1117`, IPEDS SFA → IPEDS IC_AY
→ Scorecard) already encodes this correctly by accident of freshness; the
assurance axis explains _why_ it is right.

The two administrative cells are genuinely different in kind. NSLDS is "the
Department of Education's central database for monitoring federal student aid
... used primarily for operational purposes" (p.38); earnings are "linked to
earnings data from administrative tax records maintained by the Department of
the Treasury" (p.38–39), defined as W-2 box 1 + box 12 plus Schedule SE
self-employment. They carry their own defects, but _no school types them in_.

Countervailing detail a family-facing sentence must not overclaim: the earnings
figures are **deliberately noised**. "median incomes ... had noise added to them
using independent differentially private algorithms" and privacy-suppressed
cells appear as `PS` (p.3). Cohort entry dates are "estimated ... based on ...
the student's self-reported grade level on the FAFSA" (p.41). Administrative ≠
exact.

Also worth stating plainly: the string "audit" appears **zero times** in the
56-page Scorecard technical documentation (regex count over extracted text).

### 1.3 Common Data Set

**Voluntary, ungoverned, unverified, self-published.**

- Governance is three publishers: "a collaborative effort among data providers
  in the higher education community and publishers as represented by the College
  Board, Peterson's, and U.S. News & World Report" (https://commondataset.org/).
  The whole governing body is a three-person advisory board. No staff, no
  compliance function.
- It is not even a collection: "The CDS is a set of standards and definitions of
  data items rather than a survey instrument or set of data represented in a
  database." Compliance language is hortatory — reporters are "**urged** to
  abide by the definitions".
- In the official 2025-26 template PDF
  (https://commondataset.org/wp-content/uploads/2025/11/CDS-PDF-2025-2026_PDF_Template.pdf):
  **"audit" 0 hits, "verif\*" 0 hits, "accura\*" 1 hit** (in the aspirational
  goal sentence). No submission deadline, no edit checks, no sanction.
- No central repository. Each school posts its own file; some post none.
  Columbia published no CDS at all — "This is highly unusual for a university of
  its stature" (Michael Thaddeus,
  https://www.math.columbia.edu/~thaddeus/ranking/investigation.html).
- **Known misreporting** (all self-reports to U.S. News surveys or CDS-style
  self-disclosure): Claremont McKenna, Jan 2012, SAT scores inflated 10–20
  points over six years, dean resigned (LA Times, 2012-01-30). Emory, 2012,
  deliberate falsification of test scores and class rank 2000–2012 (89% reported
  top-decile vs 75% actual). George Washington, 2012, decade of overstated
  top-decile share, unranked for 2013. Temple Fox, dean Moshe Porat convicted of
  conspiracy and wire fraud 2021-11-29, 14 months prison and a
  $250,000 fine (DOJ EDPA); Temple paid
  ED **$700,000** in Dec 2020 over misrepresented GMAT scores, GPAs and debt.
  Columbia, 2022: Thaddeus showed submitted figures "inaccurate, dubious, or
  highly misleading"; U.S. News unranked it Jul 2022; Columbia admitted
  incorrect data Sep 2022, quit the undergraduate ranking Jun 2023, and filed a
  **$9M** preliminary settlement of the student class action in **Jul 2025**.
  USC Rossier, Dec 2022, dean's own statement: "for several years, USC Rossier
  had been inaccurately reporting data on research and student enrollment to
  U.S. News." (Bucknell 2013 is thinly sourced; Tulane and Rutgers could not be
  verified — do not assert them.)
- **No citable prevalence estimate exists.** The often-repeated "most admissions
  directors believe rivals misreport" figure could not be verified; do not use
  it. The defensible structural point is that **every** case above was caught by
  an outsider — a professor, a whistle-blower, a prosecutor — never by a CDS
  control.

Our corpus: `db/seed/cds/aid_policy.csv` is 1,807 data rows over CDS field ids
`H.204`, `H.208`, `H.209`, `H.211`, `H.801`–`H.807`, with `source_url` pointing
at each school's own PDF and `archive_url` at collegedata.fyi. The CDS is
written by a separate phase and is deliberately unranked in
`SOURCES_FILLED_ELSEWHERE` (`CanonicalMoneyLoader.kt:1127`).

---

## 2. The true tiers

**A per-SOURCE tier is wrong.** Three independent facts kill it:

1. `MoneySource.SCORECARD` (`MoneySource.kt:33`) mixes IPEDS re-publication (16
   of our 18 cells) with federal administrative records (2 of 18). One tier for
   that member is a lie in one direction or the other.
2. `MoneySource.IPEDS_SFA` / `IPEDS_IC_AY` mix reported cells with
   publisher-imputed ones, and IPEDS tells us **per cell** which is which — a
   fact we already store on `publisher_flag` and already partition in
   `IpedsImputationFlag.kt`. A source-level constant would throw that away.
3. `MoneySource.COMMON_DATA_SET` softness is really a property of one
   **filing**: a school that filed an internally incoherent 2023-24 CDS and a
   clean 2024-25 one is not one level of soft.

Proposed axis: **three named tiers, assigned per measure family (and, for CDS,
per document), not per publisher.**

| Tier                        | One-line definition                                                                                                                                                                        | What it maps to                                                                                             |
| --------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------- |
| **`ADMINISTRATIVE_RECORD`** | Computed by the federal government from its own operational records; no school typed the number.                                                                                           | Scorecard `GRAD_DEBT_MDN` (NSLDS) and `MD_EARN_WNE_P10` (Treasury) only.                                    |
| **`MANDATORY_SURVEY`**      | The school reported it, but reporting is compelled by law, machine edit-checked against prior years, formally attested by a keyholder, and flagged per cell when the publisher imputed it. | All `IPEDS_SFA` and `IPEDS_IC_AY` cells, **and the 16 Scorecard cells whose dictionary `SOURCE` is IPEDS**. |
| **`VOLUNTARY_SELF_REPORT`** | The school chose to publish it about itself, to a standard nobody enforces; no collector, no edit check, no audit, no sanction.                                                            | Every `COMMON_DATA_SET` cell.                                                                               |

Two refinements the shape decision should absorb:

- **`FigureStatus.IMPUTED_BY_PUBLISHER` (`FigureStatus.kt:21`) is already the
  fourth tier, per cell, and it is orthogonal.** An imputed IPEDS cell is a
  _publisher's estimate for this school_, softer than a reported IPEDS cell and
  soft in a different way than a CDS cell. Do not fold it into the new enum; the
  renderer must read `(assurance, status)` as a pair.
- **A per-document coherence flag (candidate C4) is the only mechanism that can
  distinguish two CDS filings.** The tier above is static per measure family; it
  says a CDS number is unaudited, never that _this_ filing contradicted itself.
  The precedent that this is the right shape is IPEDS' own: NCES ships exactly
  such cross-item edits in the SFA instrument (grant recipients ≤ any-aid
  recipients), which is why an IPEDS cell earns a higher tier than a CDS cell in
  the first place. We are supplying for the CDS the control its publisher never
  built.

Concretely, this argues for **C6 (hybrid) with the tier attached to the measure
mapping rather than to `MoneySource`** — the loader already knows, at the point
it writes `sourceVariable`, whether a Scorecard column is an IPEDS copy or an
NSLDS record.

**Falsifiable check on the tier assignment:** re-download
`CollegeScorecardDataDictionary.xlsx`, filter `VARIABLE NAME` to our 18 columns,
and assert `SOURCE == "IPEDS"` for 16 and `{"NSLDS","Treasury"}` for the other
two. If NCES ever re-sources a column, that assertion fails and the tier is
wrong.

---

## 3. How the publishers themselves hedge — quotable phrasings

Publisher wording, verbatim:

- Scorecard, on cost: living expenses are "**estimated by the institution**"
  (`InstitutionDataDocumentation.pdf` p.13). This is the single most useful
  phrase we can borrow: it is the government's own admission that a cost-of-
  attendance line is a school's estimate.
- Scorecard, on origin: "All cost elements are derived from data **reported to
  the IPEDS** Institutional Characteristics and Student Financial Aid (SFA)
  components" (p.13).
- Scorecard, on earnings: median incomes "had **noise added** to them using
  independent differentially private algorithms" (p.3).
- NCES, on IPEDS: reporting is "**mandatory**"; locking "indicates to NCES that
  the data submitted are **accurate, true, and complete**"; "**Data are imputed
  for nonresponding institutions**" (SFA/IC data file documentation note).
- CDS: reporters are "**urged** to abide by the definitions"; the CDS "is a set
  of standards and definitions of data items **rather than a survey
  instrument**".
- The cleanest external formulation, quoted by Thaddeus from ex-Reed president
  Colin Diver: rankings depend on "**unaudited, self-reported data**".

At an 8th-grade reading level, three sentences a renderer can derive:

- `ADMINISTRATIVE_RECORD` — "This comes from federal loan and tax records, not
  from the college. It covers only students who got federal aid, and small
  numbers are blurred a little to protect privacy."
- `MANDATORY_SURVEY` — "The college had to report this to the U.S. Department of
  Education, and the form checks it against last year's answer. Nobody audits
  it."
- `VOLUNTARY_SELF_REPORT` — "The college published this about itself. No one
  checks it. We link to the college's own file so you can see it."
- (`IMPUTED_BY_PUBLISHER`, on top of any tier) — "The college did not answer
  this one, so the government filled it in from similar colleges."

Note what all four avoid: any number, score or percentage of confidence, per the
brief's success criterion 4.

---

## 4. Legal / consumer-protection angle (brief; not legal advice)

- **FTC Act §5.** The FTC Policy Statement on Deception (1983, appended to
  _Cliffdale Associates_, 103 F.T.C. 110, 174;
  https://www.ftc.gov/legal-library/browse/ftc-policy-statement-deception) turns
  on whether there is "a representation, omission or practice that is **likely
  to mislead the consumer**", material, judged from a reasonable consumer's
  view. There is **no innocent-repetition exemption**: we own the net impression
  our own page conveys, even about a number we merely repeat.
- **Republisher analogy.** 16 CFR 255.1(a): an endorsement "may not convey any
  express or implied representation that **would be deceptive if made directly
  by the advertiser**"; 255.1(d) makes advertisers liable for statements made
  through endorsements "even when the endorser is not liable"
  (https://www.ecfr.gov/current/title-16/chapter-I/subchapter-B/part-255/section-255.1).
  The Guides govern advertising endorsements, so a neutral attributed data table
  is not squarely within them; the transferable principle is ownership of the
  net impression.
- **ED's misrepresentation rules bind schools, not us.** 34 CFR 668.71(a)
  reaches an "eligible institution"; 668.71(b) extends only to a party "with
  whom the eligible institution **has an agreement**" for educational programs,
  marketing, advertising, recruiting or admissions services
  (https://www.ecfr.gov/current/title-34/subtitle-B/chapter-VI/part-668/subpart-F/section-668.71).
  An independent consumer site with no such agreement is outside it — but a
  future marketing or lead-gen deal with a school would pull us in. Temple's
  $700,000 payment to ED (Dec 2020) is the precedent for how hard this bites the
  school.
- **State UDAP** is the realistic exposure: NY GBL §349, CA B&P §17200/§17500
  and their equivalents let AGs and usually private plaintiffs sue over
  deceptive commercial statements. The Columbia student class action ($9M
  preliminary settlement, Jul 2025) is exactly this species.
- **Partial safe harbours.** 47 U.S.C. §230(c)(1) protects hosting another
  party's content verbatim, but weakens as we select, aggregate, recompute or
  characterise — and our derived figures are our own speech, and §230 does not
  bar FTC action on our own first-party claims. Attribution is not a formal safe
  harbour but attacks the deception elements directly.

**Practical posture this argues for, and it is the same thing the brief wants
for product reasons:** attribute and date every school-sourced figure, name the
document, label derived figures as ours, never imply verification. A derived
hedge sentence carried by the store is how that becomes structurally impossible
to forget, rather than a paragraph of prompt copy each surface must remember.

---

## 5. Answers to the brief's open assumptions

- _"Is IPEDS meaningfully more assured than the CDS, or is that a story we tell
  ourselves?"_ — **It is real, and it is large.** Mandatory under 20 U.S.C.
  1094(a)(17) with fines for non-response and ~100% response, versus voluntary
  with no collector at all; automated edit checks including cross-item coherence
  versus zero; a keyholder attestation versus nobody's signature; per-cell
  imputation flags we already ingest versus nothing. Neither is audited — a tier
  named "audited" would be false for all four sources.
- _"Which Scorecard figures are administrative and which are re-published
  IPEDS?"_ — **16 of our 18 are re-published IPEDS; 2 are administrative**
  (`GRAD_DEBT_MDN`/NSLDS, `MD_EARN_WNE_P10`/Treasury). A source-level tier on
  `MoneySource.SCORECARD` is therefore incoherent, exactly as the brief feared.
- _"14 of 249 filings report more borrowers than graduates"_ — not measured
  here; that is the corpus report's question. The relevant finding for it is
  that NCES ships this class of check inside the SFA instrument, so building it
  for the CDS is following the assured source's precedent rather than inventing
  a standard.

## Top recommendation

Adopt a **three-tier `SourceAssurance` axis — `ADMINISTRATIVE_RECORD` /
`MANDATORY_SURVEY` / `VOLUNTARY_SELF_REPORT` — assigned per measure mapping, not
per `MoneySource`**, read by the renderer together with the existing
`FigureStatus` (so `IMPUTED_BY_PUBLISHER` stays orthogonal), plus per-document
coherence findings for the CDS. That is candidate C6, with one correction: the
tier must not hang off `MoneySource`, because our own Scorecard loader writes
both an IPEDS copy and an NSLDS record under that one member.
