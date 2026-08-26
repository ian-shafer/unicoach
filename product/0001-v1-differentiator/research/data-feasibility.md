# Data Feasibility: A Rich Database of Every US Post-High-School Institution

**Product discovery pilot — Unicoach v1 differentiator research** **Question:**
Can we feasibly build (and maintain) a database over all ~6,000 US
post-secondary institutions whose depth is genuinely not available in any one
place on the internet — and is that a defensible v1 differentiator?

**Short answer:** Yes, and the path is narrower and cheaper than it sounds. The
federal layer (IPEDS + College Scorecard, which we already ingest) is a
commodity; everyone has it. The differentiating 20% is a **school-authored
layer** extracted from Common Data Set (CDS) PDFs — admissions factors, real
merit-aid practice, class-profile detail — plus a **logistics layer** (deadlines
by round, essay prompts) scraped from admissions pages with LLM extraction. Both
are legally low-risk, cost on the order of **$1–5K in LLM spend per admissions
cycle** for ~2,000 selective/relevant schools, and are already proven feasible
by a small open-source project
([collegedata.fyi](https://github.com/bolewood/collegedata-fyi)) that one team
shipped with ~4,000 archived CDS documents. Net-price-calculator (NPC)
automation is the one commonly-proposed idea we recommend **against** for v1:
high ToS/legal friction, high per-school engineering cost, and the federal data
already contains net price by income band.

---

## (a) Source inventory

| Source                                                         | Coverage                                                                                                                                                                                                         | Freshness                                                                                  | Format                                                                                   | Cost                                                                 | Licensing / access constraints                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                     |
| -------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------- | -------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **IPEDS** (NCES)                                               | All ~5,900–6,300 Title IV institutions, incl. non-degree trade schools (1,985 non-degree-granting in 2020-21)                                                                                                    | Annual, ~12–18 mo lag                                                                      | CSV "complete data files", Access DBs                                                    | Free                                                                 | Public domain; no restrictions. [nces.ed.gov/ipeds/use-the-data](https://nces.ed.gov/ipeds/use-the-data), [Fast Facts #1122](https://nces.ed.gov/fastfacts/display.asp?id=1122)                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| **College Scorecard** (already ingested)                       | Same universe; institution-level 1996–present + field-of-study files (earnings & debt **by major/program**)                                                                                                      | Refreshed ~2×/yr (site shows "last updated June 10, 2026"); earnings lag 2–4 yrs           | API + bulk CSV (470 MB all-years zip)                                                    | Free                                                                 | Public domain. [collegescorecard.ed.gov/data](https://collegescorecard.ed.gov/data/), [API docs](https://collegescorecard.ed.gov/data/api-documentation/)                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| **FVT/GE (Financial Value Transparency / Gainful Employment)** | Program-level debt-to-earnings for career/trade programs                                                                                                                                                         | New regime; regs in effect until 6/30/2027, then replaced by STATS/Earnings Accountability | ED published files                                                                       | Free                                                                 | Public. [fsapartners.ed.gov FVT/GE page](https://fsapartners.ed.gov/knowledge-center/topics/financial-value-transparency-and-gainful-employment-information) — trade-school outcomes are about to get much better as federal data; don't build this ourselves                                                                                                                                                                                                                                                                                                                                                                      |
| **Common Data Set PDFs**                                       | Voluntary; realistically ~1,500–2,500 schools publish, skewed toward 4-year selective (the ones our users ask about). collegedata.fyi archived **4,071 CDS docs** across a 6,322-school federal directory        | Annual, published fall/winter each cycle                                                   | PDF (some fillable AcroForm), XLSX, HTML — one URL per school, **no central index**      | Free to fetch; LLM extraction cost (see §c)                          | School-published public documents; CDS Initiative (College Board / Peterson's / US News) publishes a canonical template with **1,105 stable question-numbered fields** ([commondataset.org](https://commondataset.org/), [2025-26 template PDF](https://commondataset.org/wp-content/uploads/2025/11/CDS_2025-2026-PDF_Template.pdf)). Facts are not copyrightable; scraping risk low (§d). Existing free repositories: [College Transitions](https://www.collegetransitions.com/dataverse/common-data-set-repository) (7 yrs, hundreds of schools), [collegedata.fyi](https://www.collegedata.fyi/) (open-source, MIT-style, API) |
| **Net Price Calculators**                                      | Federally mandated on every Title IV school's website (HEOA §132)                                                                                                                                                | Prior-year cost data                                                                       | Interactive web forms — dozens of different vendors (College Board NPC, RaiseMe, custom) | Free to use manually; expensive to automate                          | ED's directory: [collegecost.ed.gov/net-price](https://collegecost.ed.gov/net-price). Automating form-runs at scale = per-vendor engineering + many are behind College Board ToS; **highest-friction source in this list**                                                                                                                                                                                                                                                                                                                                                                                                         |
| **Accreditation**                                              | DAPIP (ED's Database of Accredited Postsecondary Institutions & Programs); CHEA directory                                                                                                                        | Ongoing                                                                                    | Bulk download / web                                                                      | Free                                                                 | Public. Needed mostly as a trust/eligibility flag for trade schools                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                |
| **State workforce / trade registries (WIOA ETPLs)**            | Every state maintains an Eligible Training Provider List with completion/employment/earnings for funded programs; federal aggregation at [TrainingProviderResults.gov](https://www.trainingproviderresults.gov/) | Annual                                                                                     | 50 heterogeneous state formats; TPR.gov is a JS app over a downloadable dataset          | Free                                                                 | Public, but messy; coverage limited to WIOA-funded programs. Phase-3 material                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| **National Student Clearinghouse**                             | Enrollment/transfer/completion coverage of ~97% of US enrollment                                                                                                                                                 | Term-level                                                                                 | StudentTracker service                                                                   | **Paid, contract-based**; research access is institution-oriented    | Not licensable for a consumer app's per-school display in any practical way; use their free public research reports for aggregate transfer stats. [studentclearinghouse.org](https://www.studentclearinghouse.org/colleges/studenttracker/)                                                                                                                                                                                                                                                                                                                                                                                        |
| **Peterson's Data (commercial)**                               | ~4,000 institutions; sells exactly the "missing" layer — deadlines, program specifics, "true costs"                                                                                                              | Continuous                                                                                 | Licensed datasets                                                                        | Quote-based (typically **$10K–$100K+/yr** for app-display licensing) | [petersonsdata.com](https://www.petersonsdata.com/) markets on the same pain we identified ("key fields like deadlines, true costs, and program specifics are missing or scattered"). A fallback/accelerator, not a differentiator — anyone can license it                                                                                                                                                                                                                                                                                                                                                                         |
| **School admissions pages / Common App**                       | Deadlines by round (ED/ED2/EA/REA/RD), essay prompts, test policy                                                                                                                                                | Each cycle, Aug–Sep                                                                        | HTML; Common App has no public data API                                                  | Free to fetch                                                        | Prompts/deadlines are facts; short prompt texts quoted with attribution are low-risk. Aggregators exist (College Essay Guy publishes prompt roundups — [collegeessayguy.com](https://www.collegeessayguy.com/blog/college-essay-prompts)) but none exposes a clean API                                                                                                                                                                                                                                                                                                                                                             |

## (b) What is genuinely scarce and valuable

Ranked by (user value at decision time) × (absence from commodity sources):

1. **CDS Section C7 admissions-factor grid** — how each school weighs rigor,
   GPA, essays, interviews, demonstrated interest, legacy ("Very Important" →
   "Not Considered"). Not in IPEDS/Scorecard at all. This is _the_ input to an
   AI coach's "what should YOU emphasize for THIS school" advice. Scarce,
   structured, annual, and school-authored.
2. **Real merit-aid practice — CDS Section H2A**: number of students _without
   financial need_ who received merit awards, and the average award. Combined
   with H1/H2 need-based detail, this answers "will this school actually
   discount for a strong student?" — the single most asked, least answerable
   family question. No federal source has it; consultants charge thousands to
   interpret it.
3. **Deadlines by round + application-plan rules** (ED/ED2/EA/REA restrictions,
   scholarship-priority deadlines). Zero federal coverage; Peterson's sells it;
   students currently maintain spreadsheets by hand. High maintenance value for
   a chat coach that can proactively warn ("your REA to X forbids your EA to
   Y").
4. **Supplemental essay prompts per school per cycle.** Only blogs and paid
   tools track these. Directly feeds Unicoach's essay-coaching loop.
5. **Net price by income band** — valuable but **not scarce**: Scorecard/IPEDS
   `NPT4` fields already give average net price by 5 income bands per school.
   Present it well; don't scrape NPCs to get it.
6. **Outcomes by major** — valuable, and already ours: Scorecard field-of-study
   earnings/debt files. Differentiation is presentation (in-chat comparisons),
   not acquisition.
7. **Transfer pathways / articulation** — extremely valuable (community-college
   → 4-year is half the market) but fragmented across state systems (ASSIST in
   CA, etc.) with no national dataset; NSC transfer data is not licensable for
   consumer display. Genuine moat potential but a Phase-3 project,
   state-by-state.
8. **Trade-school outcomes** — FVT/GE program-level debt-to-earnings is arriving
   as free federal data (and STATS after 2027); ETPLs add state coverage.
   Ingest, don't build.

## (c) Build approach and cost

**Precedent:** [collegedata-fyi](https://github.com/bolewood/collegedata-fyi)
(open source) proves the whole pipeline: federal directory of 6,322 schools →
discover each school's CDS URL → tiered extraction (filled XLSX; _fillable
AcroForm PDFs extract deterministically via `pypdf.get_fields()`_; flattened
PDFs and scans via LLM/OCR) → canonical 1,105-field schema keyed by CDS question
numbers → 262K+ extracted field rows. One small team did this; we can too, or
even seed from their MIT-licensed archive and API.

**Pipeline sketch (per cycle):**

1. _Discovery:_ LLM-assisted search over
   `site:<school domain> "common data set"`
   - institutional-research page crawl; cache URL patterns year-over-year.
2. _Extraction:_ tier by format. Deterministic where possible; LLM (vision for
   scans) elsewhere, always emitting into the canonical CDS question-number
   schema with per-field source-page provenance.
3. _Validation:_ cross-check overlapping fields against IPEDS (admit rate,
   enrollment, test ranges) — automatic anomaly gate; human review only on
   deltas.
4. _Versioning:_ lands in the existing versioned `colleges` table pattern as a
   new `cds_facts` source layer, exposed through the same LLM tool.

**Cost estimate (LLM spend):**

- CDS doc ≈ 15–35 pages ≈ 20–50K tokens in; structured out ≈ 5–10K tokens.
- Frontier-model extraction ≈ **$0.30–$1.00/doc**; mid-tier model with a
  verifier pass ≈ $0.05–0.20/doc.
- 2,000 schools × 1 doc = **$600–$2,000/cycle**; add discovery-search and
  validation passes → **$1–5K/cycle all-in LLM cost.** Trivial relative to
  payroll.
- Deadlines/prompts layer: ~2,000 schools × 2–5 admissions pages ≈ 10K pages ≈
  similar low-thousands cost, refreshed 2–3× between August and January.
- **Real cost is people, not tokens:** expect ~0.3–0.5 FTE steady-state for URL
  churn, format oddities, and the fall re-ingestion crunch. NPC automation, by
  contrast, is per-vendor browser automation across dozens of NPC platforms —
  months of engineering for data we largely already have.

**Maintenance rhythm:** one heavy re-ingest each fall (CDS + prompts + deadlines
publish Aug–Dec), one Scorecard/IPEDS refresh on federal release, spot re-crawls
for deadline corrections. Year-over-year field diffs (as collegedata.fyi's
"change intelligence" does) become a content/notification feature for free.

## (d) Legal / ToS risk

- **Facts are not copyrightable** (_Feist_); CDS numbers, deadlines, and
  admission factors are facts. Reproducing whole PDFs verbatim is riskier than
  extracting fields with source citation — extract, don't mirror (though
  archiving for provenance, as collegedata.fyi does openly, has drawn no known
  challenge).
- **Scraping public pages:** _hiQ v. LinkedIn_ (9th Cir. 2019, affirmed on
  remand 2022) established that scraping publicly available data does not
  violate the CFAA; hiQ still lost on **breach of contract** for violating ToS
  after a cease-and-desist and settled in Nov 2022
  ([Wikipedia summary](https://en.wikipedia.org/wiki/HiQ_Labs_v._LinkedIn)).
  Practical rule: no login walls, respect robots.txt, throttle, stop on C&D.
  University IR pages and admissions pages are the lowest-risk category on the
  web — public institutions are subject to public-records norms and _want_ this
  data distributed (the CDS exists precisely to feed publishers).
- **NPCs are the exception:** many run on College Board's platform under its
  ToS, require form interaction (arguably "use of the service," not passive
  reading), and some assert per-student-use terms. Automating them at scale is
  the one clearly elevated-risk item. Skip.
- **Commercial data (Peterson's, US News, Niche, College Board BigFuture):** do
  not scrape competitors' compiled databases — that's where _compilation_
  copyright and ToS claims actually bite. License or ignore.
- **NSC:** contract-only; not viable for consumer display. Use their public
  aggregate research reports only.

## (e) Recommended phased build — the 20% that gives 80%

**Phase 1 (pre-launch, ~6–8 weeks): the "Admissions Intelligence Layer."**
Target the ~1,200–1,500 schools that cover >95% of what our users will ask about
(all CDS-publishing 4-years + top community colleges). Ship three datasets the
chat coach can cite with per-field provenance:

1. CDS C-section extract (C7 factor grid, C9 test ranges, C1/C2 class profile,
   waitlist C2) — seeded from the open collegedata.fyi archive/API where quality
   passes, filled by our own pipeline elsewhere.
2. Merit-aid reality (CDS H2A + H1): "X% of no-need freshmen got merit, avg $Y."
3. Deadlines-by-round + essay prompts for the current cycle. This is the
   differentiator: _"Unicoach knows what each school actually weighs, what it
   actually pays, and exactly when everything is due — with receipts."_ No
   consumer AI chat app has this as structured, cited tool data.

**Phase 2 (first cycle post-launch):** presentation-layer wins on data we
already own — net price by income band (Scorecard NPT4), earnings/debt by major
(field-of-study files), FVT/GE trade-program debt-to-earnings ingest. Extend CDS
coverage toward all ~2,500 publishers; add year-over-year delta notifications
("Northeastern just dropped ED2").

**Phase 3 (only if Phases 1–2 win):** transfer-pathway data state-by-state
(start CA/TX/FL via ASSIST-style systems), ETPL trade outcomes, and evaluate a
Peterson's license purely as a coverage backstop for long-tail schools.

**Explicit no-gos for v1:** NPC automation (risk + cost, redundant with NPT4);
NSC licensing (contractually impossible for our use); building our own
trade-outcomes measurement (feds are shipping it).

---

_Prepared by research subagent, product-discovery pilot. All URLs verified by
direct fetch on the date of writing; Serper websearch was unavailable, so
evidence was gathered via direct HTTP retrieval of primary sources._
