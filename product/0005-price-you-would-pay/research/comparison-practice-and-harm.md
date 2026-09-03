# What real products and real families do about residency in college search

Research for product brief **0005 — the price you would pay**. External
evidence. Companion to `search-surface-and-residency.md` (the repo-side surface
survey) and `data-and-index-feasibility.md`.

---

## Method

The `websearch` skill is **not configured** in this environment — it returns
"Web search is not set up yet: no Serper API key is configured." All research
below was done with direct HTTP (`httpx` + BeautifulSoup) against the products'
own pages, their shipped JS bundles and JSON config, published PDFs, and raw
NCES/IPEDS data files.

I ran three parallel researchers. Their full evidence files sit beside this one
and are cited throughout:

| file                 | scope                                                    |
| -------------------- | -------------------------------------------------------- |
| `_sub-products.md`   | product-by-product survey of search/filter/sort surfaces |
| `_sub-harm.md`       | documented harm, NPC research, dollar gaps               |
| `_sub-prevalence.md` | how common out-of-state attendance is (IPEDS microdata)  |

Honest limits, carried forward from those files rather than hidden:

- **Search engines were largely blocked.** DuckDuckGo HTML served a bot
  challenge or HTTP 202 with zero results for two of the three researchers; Bing
  returned bot junk for one and worked for another; Mojeek had no index for the
  enrolment queries. Where a claim could only have come from a search result, it
  is **absent**, not guessed.
- **Not verified at all:** US News (repeated read timeouts), TuitionFit (JS-only
  shell), CollegeAidPro/MeetCollegePro (DNS failure), Edmit (defunct),
  CollegeVine's search and sort controls (JS-only; its profile page is
  server-rendered and _is_ quoted).
- `gao.gov` returned HTTP 403; the GAO report was read from the ERIC mirror.
- The Scorecard `InstitutionDataDocumentation.pdf` downloaded (200, 569 KB) but
  no PDF text extractor was available, so **no claim here rests on it**.
- Several products are single-page apps. Where the visible HTML was an empty
  shell, labels are quoted from the **shipped JS bundle or server-rendered JSON
  state**, and that is stated at the point of quotation. A bundle string is
  strong evidence of the label the product ships; it is weaker evidence of
  exactly where on screen it appears.

---

## 0. The four findings, up front

1. **Nobody asks.** Of every mainstream college-search product I could verify —
   the federal College Scorecard included — **not one asks for the family's
   state of residence before it shows, filters or ranks a price.** They all
   personalise by **income band** instead, and they all rank on an
   IPEDS/Scorecard net price that is in-state-only at publics.
   _(`_sub-products.md` §1–6.)_ So our defect is the industry default, not an
   outlier. That is a reason to be careful about copying, not a reason to relax.
2. **The federal source states the basis in its own words** — just not where the
   ranking happens. Scorecard's glossary says: _"For public schools, this is
   only the average cost for in-state students."_ It lives in a glossary entry
   and a tooltip. The sort control is labelled **"Annual Cost"**. _(§1 below.)_
3. **The error is worth
   ~$20k/yr and it points the dangerous way.** Average published
   tuition and fees at public four-years, 2025-26: **$11,950 in-state vs $31,880
   out-of-state — a
   $19,930 gap**, which carries straight through to total budgets ($30,990 vs
   $50,920). At flagships it reaches **+$41,798** (Michigan). Ranking an
   out-of-state family on the in-state number makes schools look **cheaper than
   they are** — the same direction of error GAO found in aid offers and called
   out as _"makes a college appear less expensive than it is."_ _(§3.)_
4. **~19% of enrolees, but ~72% of applicants.** 18.6% of Fall-2022 first-time
   undergrads at public four-years came from another state, rising every cycle;
   but **71.9% of 2025-26 Common App applicants applied to at least one
   out-of-state institution.** A _search_ surface serves the applicant funnel,
   not the enrolment outcome. _(§4.)_

---

## 1. Product by product: how each search surface treats residency

Full quotes, URLs and fetch status per product are in `_sub-products.md`.
Summary table:

| Product                                   | Asks residency before price?  | Filter basis                          | Sort basis                                  | Residency labelled at the ranking?            |
| ----------------------------------------- | ----------------------------- | ------------------------------------- | ------------------------------------------- | --------------------------------------------- |
| College Scorecard                         | **No** (asks income)          | blended NPT4                          | `avg_net_price:asc`, labelled "Annual Cost" | No — glossary/tooltip only                    |
| Niche                                     | **No**                        | blended net price, "Cost (net price)" | Best Value ranked list                      | No                                            |
| CollegeVine                               | **No**                        | search surface unverifiable (JS-only) | unverifiable                                | No (profile net-cost table unlabelled)        |
| BigFuture                                 | **No**                        | **no cost filter exists**             | "Average Net Price" asc                     | No                                            |
| Tuition Tracker                           | **No** (asks income)          | in-state net price                    | "Price $ - $$$"                             | **Yes — in prose and in metric names**        |
| CollegeData                               | **No**                        | "Cost of Attendance"                  | "Tuition Cost (Low to High)"                | No — and _which_ tuition is unverifiable      |
| Appily                                    | **No**                        | "Net Price" / "Sticker Price" tabs    | unverifiable                                | No                                            |
| CollegeResults.org                        | **No** (filters `hide: true`) | **residency-split COA variables**     | similar-school match on both, separately    | **Yes — every row "In State"/"Out of State"** |
| Scholarships360                           | **No**                        | ranked lists on net price             | net price                                   | No (in-state discount noted in an FAQ)        |
| US News, TuitionFit, CollegeAidPro, Edmit | **not verified**              | —                                     | —                                           | —                                             |

### 1.1 College Scorecard — the source of our own defect, and of the best sentence

Fetched: `https://collegescorecard.ed.gov/search/` (200) and the shipped Nuxt
chunks.

There is **no residency control anywhere**. The only state control on the search
page is `"Location: Select one / Select a state..."` — _where the school is_,
not where the family lives. Personalisation is income only:
`"What's your Family Income?"`, `"By Family Income Category"`.

The sort list, hard-coded in `_nuxt/Bm_X-AxC.js`:

```js
we = [
  { type: "Name", field: "name:asc" },
  { type: "Annual Cost", field: "avg_net_price:asc" },
  { type: "Graduation Rate", field: "completion_rate:desc" },
  { type: "Median Earnings", field: "median_earnings:desc" },
];
```

and the comparator:

```js
case"avg_net_price:asc":c.sort((m,v)=>m[a.NET_PRICE]-v[a.NET_PRICE]);break;
```

That is exactly our `ORDER BY net_price_per_year_usd` — one blended field, every
family. The filter is `"Average Annual Cost ≤ $ k"`.

But `https://collegescorecard.ed.gov/data/glossary/`, entry **"Average Annual
Cost"**, says:

> "Net price is calculated as the average over all full-time, first-time
> students who receive federal financial aid and may not reflect a specific
> student's annual costs. **For public schools, this is only the average cost
> for in-state students.** Negative cost values indicate that the average
> grant/scholarship aid exceeded the cost of attendance."

The same sentence repeats for "Average Annual Cost for Largest Program". The
data documentation is equally explicit that `NPT4_PUB` is _"limited to those
undergraduates who pay in-state tuition"_ and that COA is reported _"for
students paying the in-state or in-district tuition rate"_ (`_sub-harm.md` §3).

**This matters for the brief:** the residency basis of our ranking key is not a
discovery and not contested. The publisher documents it. Our defect is that we
rank on it and never say so — and even ED only says so in a glossary a searcher
never opens.

### 1.2 BigFuture — the parent brief's premise, corrected

The task brief said "BigFuture historically DOES ask in-state vs out-of-state."
Verified against `https://bigfuture.collegeboard.org/college-search` and its
bundles: **the current college search has no cost filter at all.** The full
filter-key list is in `_sub-products.md`. The price sort is **"Average Net
Price"** ascending on a single blended field, and the profile summary line is
unqualified:

> "After scholarships and grants, $costSchoolName$ costs $costAmount$."

The in-state/out-of-state split exists **only on the profile page**, as two
sticker-tuition rows ("In-State Tuition" / "Out-of-State Tuition"). Same shape
as Niche and CollegeData: show both on the profile, rank on one, say which on
neither.

### 1.3 The two good actors

**Tuition Tracker** (Hechinger Report) is the only product that says it in
prose, in product:

> "Net prices are for first-time, full-time students (and, for public
> institutions, in-state students)."

> "Net price is calculated by subtracting federal, state, local and
> institutional grants and scholarships from the total cost of attendance for
> first-time, full-time (and, at public universities, in-state) undergraduates."

Even its download link is labelled by basis: _"School net prices — Net, in-state
prices for each school"_. And its chart strings bake the basis **into the metric
name**:

```
SchoolPage.Prices.inStateNetPriceLabel   => "in-state net price"
SchoolPage.Prices.inStateStickerLabel    => "in-state sticker price"
SchoolPage.Prices.outOfStateStickerLabel => "out-of-state sticker price"
```

Note the limit, though: its **sort is still "Price $ - $$$"** on that in-state
number, and its filter still asks income and never residency. Tuition Tracker
labels honestly and then ranks on the unlabelled thing anyway. Labelling is not
the whole fix.

**CollegeResults.org** (Education Trust) is the only **residency-correct data
model** found, and the finding most directly relevant to `SimilarCollegesTool`.
It keeps `costs_avg_coa_in_state` and `costs_avg_coa_out_state` as separate
variables, labels every money row `"In State"` / `"Out of State"` with a
screen-reader suffix, and — critically — drives similar-school matching off
**both, separately**:

```json
{"id":"costs_avg_coa_in_state","label":"Average COA In-State","similar_search_range":3000, ...}
{"id":"costs_avg_coa_out_state","label":"Average COA Out-of-State","similar_search_range":3000, ...}
```

i.e. _match on residency-split price bands; do not collapse to one number._ That
is a working precedent for a two-column index rather than one blended ranking
key.

Two cautions, quoted verbatim in `_sub-products.md` so we don't repeat them:
their rows under the heading `"Out of pocket cost (net price)"` point at the
**cost-of-attendance** variables, so as shipped their "net price" rows appear to
display COA; and both cost filters are marked `"hide": true`, so the user never
sees the residency choice either.

---

## 2. Does anyone rank on an ambiguous-basis price, and how do they word it?

**Yes — essentially everyone ranks on it, and almost nobody labels it at the
point of ranking.** The labels below are the candidate wordings, with my
recommendation on each.

**Worth borrowing — the federal source's own sentence (College Scorecard
glossary):**

> "For public schools, this is only the average cost for in-state students."

Short, scoped exactly to publics, and it is ED's own phrasing about ED's own
number, so it costs us nothing in authority. It is close to what RFC 157 already
landed at the tool boundary
(`college/src/main/kotlin/ed/unicoach/college/CollegeMatchRow.kt:32-34`).

**Worth borrowing — Tuition Tracker's technique, not just its sentence:** put
the basis in the **metric name** (`"in-state net price"`), not in a footnote. A
footnote is skipped; a column header is read. This is the cheapest structural
upgrade available to us.

**Worth deliberately rejecting — Appily's tooltip:**

> "Sticker Price is the yearly cost listed by the institution, including tuition
> and fees, room and board, and books and supplies."

"the yearly cost listed by the institution" is exactly the phrasing that **hides
that there are two published rates**. It sounds precise and settles nothing.

**Worth rejecting — Niche's filter tooltip:**

> "Average cost after financial aid for students receiving grant or scholarship
> aid, as reported by the college."

It carefully qualifies the _cohort_ (aid recipients) and the _source_ (as
reported), which makes the omission of residency read as deliberate
completeness. Niche's own Berkeley page shows **Net Price $16,538** with an
empty tooltip directly beside **Out-of-State Tuition $50,547** which _does_
carry a correct tooltip — the correct label next to the wrong number is worse
than no label, because it implies the pair share a basis. This is the same
amplifier `.scratch/blended-figure-surfaces.md` identified in our own cost
report at `CostReportPage.kt:421`.

**The clearest bad pattern — CollegeData:** result cards print both
`"In-State Tuition : $3,292"` and `"Out-of-State Tuition : $11,092"`, the filter
is titled `"Cost of Attendance"` with one `costCode` parameter for both
audiences, and the sort is `"Tuition Cost (Low to High)"`. Which of the two
columns that sort uses is **not stated and I could not verify it** (the sort is
applied client-side). Shows both, ranks on one, says which on neither.

---

## 3. Documented harm, and the size of the error in dollars

Full citations in `_sub-harm.md`.

**The gap.** College Board _Trends in College Pricing 2025_ (primary PDF),
public four-year, 2025-26:

|                  | in-state | out-of-state | gap         |
| ---------------- | -------- | ------------ | ----------- |
| tuition and fees | $11,950  | $31,880      | **$19,930** |
| total budget     | $30,990  | $50,920      | **$19,930** |

NCES Digest table 330.20 cross-check (2022-23): $9,750 vs $28,297. Flagship
examples (IPEDS 2023, via the Urban Institute Education Data API — every campus
`.edu` cost page 403'd and College Navigator was **down**, so these are API
figures, not campus pages):

| institution | in-state T&F | out-of-state T&F | gap          |
| ----------- | ------------ | ---------------- | ------------ |
| U. Michigan | $18,309      | $60,107          | **+$41,798** |
| UVA         | $22,323      | $59,633          | +$37,310     |
| UC Berkeley | $14,850      | $45,627          | +$30,777     |
| UCLA        | $13,747      | $44,524          | +$30,777     |
| Penn State  | $20,234      | $40,188          | +$19,954     |

**Residency is a named, measured defect in comparable tools.** TICAS (2020, ERIC
ED611240) found that only **32%** of non-federal-template net price calculators
ask about residency, and that some _"provide an estimate assuming in-state
tuition without asking students to indicate their expected residency status."_
That is our exact defect, already documented in the adjacent product category.

**Understating price is the harm regulators name.** GAO-23-104708 (Nov 2022;
read via ERIC ED628355) on aid offers: an estimated **91%** of colleges omit or
understate net price; **50%** understate, which _"makes a college appear less
expensive than it is"_; **41%** omit it entirely.

**A wrong-but-clear number changes behaviour.** NBER w26910 (Levine, Ma &
Russell, 2020) finds sticker shock is **causal**: students _"unaffected by
virtue of institutional aid policies still apply less often"_ when sticker
prices rise, and price increases at public flagships _"reduce enrollment of high
achieving students, regardless of financial aid status."_ Families are
demonstrably steered by displayed prices that are not their real price.

**Families cannot self-correct.** NCES 2003-030: only **25%** of
11th/12th-graders and **31%** of parents estimated tuition within 25% of the
true figure; 37% and 29% could not estimate at all. (1999 data — old, and I
found no rigorous federal replication this session.)

**Two honest gaps.**

1. I found **no published critique of Scorecard's residency basis
   specifically**. Treat that as absence of evidence, not evidence of absence —
   and note search engines were blocked.
2. All the behavioural evidence above measures harm from prices that are **too
   high**. Our defect makes prices look **too low**, and I found **no study
   measuring that reverse case**. The mechanism (a family invests months in a
   school, then the real bill arrives) is an inference, and should be labelled
   as one in the brief. GAO's language about understatement is the closest
   documented analogue.

---

## 4. How common is out-of-state? Sizing the harm

Computed from raw IPEDS `EF{year}C` + `HD{year}` files (NCES publishes
residence-and- migration by **state**, not by **sector**, so the aggregation is
the researcher's; the inputs are primary). Full arithmetic in
`_sub-prevalence.md`.

**Fall 2022, public four-year (SECTOR 1, 753 institutions):** 1,326,578
US-resident first-time undergrads, of whom 1,080,043 were in-state → **246,535
out-of-state = 18.6%**. Counting foreign/outlying/unknown residence, **21.3%**
are not in-state. Digest 309.10 all-sector cross-check: 21.6% crossed a state
line.

**Rising every cycle:** 14.7% (2008) → 15.1% (2012) → 15.9% (2016) → 16.2%
(2018) → 16.1% (2020) → **18.6% (2022)**; +3.9 points, with out-of-state
headcount up 63% against sector growth of 28%.

**Highly concentrated, and concentrated on exactly the schools a "cheapest" sort
surfaces:** median public four-year is only 10.8% out-of-state, but **181
campuses ≥25% out-of-state hold 31% of public four-year first-year seats**, and
88 campuses ≥40% hold 17%. Vermont 83.6%, Delaware 66.3%, Alabama 64.9%, Ole
Miss 64.2%, WVU 57.7%, Oregon 56.8%, South Carolina 47.4%, Michigan 46.4%,
Indiana-Bloomington 45.2%, ASU 38.7%.

**Counterweight:** public two-years are 4.2% out-of-state and hold 28.5% of all
undergrads (Digest 303.70, Fall 2023) — for a large slice of students residency
is a non-issue.

**The applicant funnel is much wider than the enrolment outcome.** Common App
2025-26 end-of-season, Figure 26: only-in-state 429,796 / only-out-of-state
309,886 / both 787,646 = 1,527,328 → **71.9% applied to at least one
out-of-state institution.** Caveat stated by the researcher: Common App's
"out-of-state" includes privates, so this is not the
non-resident-tuition-exposed share; it bounds the _consideration_ set, not the
_bill_.

**Does not exist:** a figure for "all undergrads paying non-resident rates".
IPEDS residence/migration is first-time-only, and residency-for-tuition is a
legal status IPEDS never collects. **The spec must not cite such a number.**

**So the answer to the brief's sizing question is neither 5% nor 25%.** It is
~19% of enrolees at publics, ~31% of public four-year seats sitting at heavily
non-resident campuses, and ~72% of applicants having at least one out-of-state
school in the set the search surface is ranking.

---

## 5. Recommendation

**When residency is unknown, "cheaper" should rank on the in-state figure — but
the index must carry both bases, the metric must be named for its basis, and the
coach must be required to state it.** The evidence points three ways at once and
the recommendation has to hold all three. First, ranking on in-state is the
_modal-correct_ choice — 81% of enrolees at publics pay it and every private is
unaffected — and it is what every product we can verify already does, so it is
not a competitive liability. Second, the modal-correct choice is not defensible
_silently_: the error is ~$19,930/yr on average and up to ~$41,798 at Michigan,
it points toward **understatement** (the direction GAO names as _"makes a
college appear less expensive than it is"_), it concentrates precisely on the
heavily-non-resident flagships that a cheapest-first sort floats to the top, and
~72% of applicants have at least one out-of-state school in the set being
ranked. Third, TICAS shows "assume in-state and don't ask" is a _documented
defect_ in the neighbouring product category, not a neutral convention — so
"everyone does it" is a reason to label, not to relax. Concretely: **keep one
ranking key when residency is unknown, make it explicitly the in-state key, and
rename it so the basis is unavoidable** — Tuition Tracker's technique of putting
`in-state net price` in the metric name rather than a footnote, carried into the
index column, the tool field name, and the sort control label, not just a tool
description. **Once residency is known, rank on the residency-correct key**,
which requires the index to carry both bases; CollegeResults.org is the working
precedent, matching similar colleges on `costs_avg_coa_in_state` and
`costs_avg_coa_out_state` **separately** rather than collapsing to one number,
and that is the shape `SimilarCollegesTool` needs so "like Bowdoin but cheaper"
stops comparing two in-state figures for an out-of-state family. And because our
surface has something no shared PDF has — **a coach in the loop** — the honest
answer to "what does cheaper rank on when residency is unknown" is _in-state,
said out loud, with the question asked_: the right move for an unknown-residency
search is not to withhold the ranking but to rank on the labelled in-state key
**and prompt the coach to ask the family's state**, converting an unstated
assumption into one question whose answer makes every subsequent ranking
correct. That is the one thing Scorecard, Niche, BigFuture and Tuition Tracker
structurally cannot do, and it is where our product should differ from all of
them.
