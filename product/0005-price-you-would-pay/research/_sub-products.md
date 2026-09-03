# How other college-search products treat in-state vs out-of-state price

Scope: external research only. Question: in SEARCH / FILTER / SORT / COMPARE
surfaces, do these products ask for the family's state of residence before they
show, filter or rank a price? And what exact words do they put on the number?

Short answer: **no mainstream search product asks for residency before it ranks
price.** Every one of them personalises by **income band** instead, and ranks on
an IPEDS/Scorecard net price that is in-state-only at publics. Only two sources
label that basis in words (College Scorecard glossary, Tuition Tracker) and only
one product (CollegeResults.org) keeps separate in-state and out-of-state cost
variables for matching. Residency-correct numbers, where they exist at all,
appear only on the **profile page** as two sticker tuition rows — never in the
filter or the sort.

---

## Method

Tooling note: the `websearch` skill has no Serper key, so all fetches were
direct HTTP (`httpx` + BeautifulSoup). DuckDuckGo HTML search returned a bot
challenge ("Unfortunately, bots use DuckDuckGo too... Select all squares
containing a duck"), so Bing HTML was used for the few link lookups needed.

Many of these products are single-page apps. Where the visible HTML was an empty
shell I went to the **shipped JS bundle** or the **server-rendered JSON state**
in the page and quote the label strings from there. That is stated per site
below.

Fetched successfully:

- `https://collegescorecard.ed.gov/search/` (200) — SSR filter labels
- `https://collegescorecard.ed.gov/data/glossary/` (200) — the definitive cost
  definition
- Scorecard Nuxt chunks `_nuxt/Bm_X-AxC.js`, `C_QDl7AK.js`, `euiFlSTl.js`,
  `bpsTAmVk.js` (200) — sort option list, income-band UI strings, glossary text
  as shipped
- `https://collegescorecard.ed.gov/assets/InstitutionDataDocumentation.pdf`
  (200, 569 KB) — **downloaded but NOT text-extracted** (no PDF parser available
  in this environment). No claim below rests on it.
- `https://bigfuture.collegeboard.org/college-search` (200) + bundles
  `672.e6d101c01bd40358392e.js`, `main.22ac0961babc67ae61bc.js` and 6 lazy
  chunks (200)
- `https://bigfuture.collegeboard.org/colleges/university-of-california-berkeley`
  (200) — server-rendered CMS strings
- `https://bigfuture.collegeboard.org/jsonapi/node/functional_content?page[limit]=50`
  (200)
- `https://www.niche.com/about/methodology/best-value-colleges/` (200)
- `https://www.niche.com/colleges/search/best-colleges/` (200) — filter config
  JSON
- `https://www.niche.com/colleges/university-of-california---berkeley/cost/`
  (200)
- `https://www.collegevine.com/schools/university-of-california-berkeley` (200)
- `https://www.tuitiontracker.org/` (200) — methodology text + full i18n string
  table
- `https://www.collegedata.com/college-search` (200) — filter + sort config JSON
- `https://www.appily.com/colleges` (200)
- `https://www.collegeresults.org/` (200) — full filter/compare field config
- `https://scholarships360.org/colleges/` (200)
- `https://myintuition.org/` (200)

Failed / could not verify:

- **Niche 403s** on a plain request. It returns 200 once browser-like `Accept`,
  `Accept-Language` and `Sec-Fetch-*` headers are sent. Do NOT send
  `Accept-Encoding: br` — the response then arrives as undecoded binary.
- **US News**
  (`https://www.usnews.com/best-colleges/rankings/national-universities` and
  `/best-colleges/search`): read timeout on every attempt, 15 s and 40 s. **Not
  verified. No claims made about US News below.**
- **CollegeAidPro / MeetCollegePro** (`https://www.meetcollegepro.com/`): DNS
  failure, `nodename nor servname provided`. **Not verified.**
- **TuitionFit** (`https://www.tuitionfit.org/`): 200 but the body is an
  895-byte Vite shell (`<div id="app"></div>`), no content without JS. **Not
  verified.**
- `https://www.tuitiontracker.org/methodology.html` → 404. The methodology text
  is on the homepage instead and is quoted from there.
- CollegeVine's search/explore surface (`/schools/hub/all`, `/schools/explore`):
  the HTML is "Looks like you don't have JavaScript enabled." **Its filter and
  sort controls could not be verified.** Its profile page IS server-rendered and
  is quoted.
- Edmit: not fetched (the consumer product is defunct). **Not verified.**

---

## 1. Federal College Scorecard — collegescorecard.ed.gov

**Does it ask for residency before showing price? NO.**

It personalises by **income only**. The profile/compare pages render an income
table and an income selector; there is no residency control anywhere.

From the shipped profile chunk `_nuxt/euiFlSTl.js` (fetched 200), the cost card:

> "By Family Income" "Depending on the federal, state, or institutional grant
> aid available, students in your income bracket may pay more or less than the
> overall average costs." table header: "Family Income" | "Average Annual Cost"
> rows: "$0-$30,000", "$30,001-$48,000", ...

and from the compare chunk `_nuxt/bpsTAmVk.js`:

> "By Family Income Category" "What's your Family Income?"

URL for both: assets under `https://collegescorecard.ed.gov/_nuxt/` linked from
`https://collegescorecard.ed.gov/search/`. There is **no equivalent "What's your
state?" string** in any chunk fetched.

**FILTER and SORT run on the blended figure.**

The search page's own server-rendered filter labels
(`https://collegescorecard.ed.gov/search/`) are:

> "Graduation Rate ≥ % | Average Annual Cost ≤ $ k | Test Scores ..."

and the only residency-shaped control on the page is the school-location one:

> "Location: Select one / Select a state..."

— i.e. _where the school is_, not _where the family lives_.

The sort list is hard-coded in `_nuxt/Bm_X-AxC.js`:

```js
sort:{type:"string",default:"median_earnings:desc"}
we=[{type:"Name",field:"name:asc"},
    {type:"Annual Cost",field:"avg_net_price:asc"},
    {type:"Graduation Rate",field:"completion_rate:desc"},
    {type:"Median Earnings",field:"median_earnings:desc"}]
```

and the comparator is a plain numeric sort on the single net-price field:

```js
case"avg_net_price:asc":c.sort((m,v)=>m[a.NET_PRICE]-v[a.NET_PRICE]);break;
```

So the price sort label a user sees is just **"Annual Cost"** (default sort
shown on the page is "Sort: Median Earnings"), and it is the blended NPT4
number.

**EXACT label + the disclaimer that does exist.**

`https://collegescorecard.ed.gov/data/glossary/`, entry **"Average Annual
Cost"**:

> "The average annual cost is the average net price for students who receive
> federal financial aid (e.g., Pell grants, federal loans) for one academic year
> of study. Net price is calculated by adding the advertised price for tuition,
> fees, books, supplies, and the average living costs at the school (on-campus,
> off-campus not with family, and off-campus with family) and subtracting the
> average grant and/or scholarship aid (e.g., Pell grants, school-based grants,
> merit scholarships). Net price is calculated as the average over all
> full-time, first-time students who receive federal financial aid and may not
> reflect a specific student's annual costs. **For public schools, this is only
> the average cost for in-state students.** Negative cost values indicate that
> the average grant/scholarship aid exceeded the cost of attendance." Relevant
> variables: NPT4_PUB, 2023-24 award year cohort / NPT4_PRIV, 2023-24 award year
> cohort

The same sentence appears again for "Average Annual Cost for Largest Program":

> "For public schools, this is only the average cost for in-state students."

This is the **best available borrowable sentence**: it is the federal source's
own words, it is short, and it is scoped exactly to publics. Note where it
lives: in the **glossary and the tooltip**, not next to the filter and not on
the sort control. The number itself is labelled only "Average Annual Cost".

---

## 2. Niche — niche.com

**Does it ask for residency before showing price? NO.** Nothing in the search
filter config mentions residency. (Verified by grepping the filter JSON on
`https://www.niche.com/colleges/search/best-colleges/`; the only "out-of-state"
hit in the whole page is inside the Diversity tooltip — "proportion of
international students and out-of-state students" — and the only "in-state" hits
are inside student review text.)

**FILTER runs on a blended figure.** The filter config on that page:

```json
{"type":"label","label":{"value":"Cost (net price)",
  "tooltip":"Average cost after financial aid for students receiving grant or scholarship aid, as reported by the college.",
  "id":"netPrice"}},
{"name":"netPrice","type":"control","control":{"format":["comma","dollar"],
  "labelIsRange":true,"rangeMin":2000,"rangeMax":36000,"step":2000,"type":"expediteSlider"}}
```

So: label **"Cost (net price)"**, tooltip **"Average cost after financial aid
for students receiving grant or scholarship aid, as reported by the college."**
— no residency qualifier at all.

**SORT**: Niche search is ranked-list based (Best Colleges, Best Value
Colleges...); no `Sort` control string appears in the fetched HTML, so there is
nothing to report beyond the ranking. Result cards expose the price as a bare
property: `{"@type":"PropertyValue","name":"Net Price","value":"20996"}`.

**Ranking methodology** —
`https://www.niche.com/about/methodology/best-value-colleges/`:

> "Average Net Price — Average cost after financial aid for students receiving
> grant or scholarship aid, as reported by the college. — Lower net price
> directly improves the value equation, particularly for students who aren't
> paying full sticker price. — U.S. Dept. of Education — 7.5%"

and

> "Average Return on Investment by 30 ... Compares estimated earnings at age 30
> against total cost of attendance."

Residency is not mentioned anywhere in the methodology. So the **Best Value
ranking itself is 20% driven by in-state-basis money figures with no
disclosure.**

**Profile page is where residency appears.** On
`https://www.niche.com/colleges/university-of-california---berkeley/cost/` the
server state contains both:

```json
{"key":"NetPrice","label":"Net Price","tooltip":"","value":16538, ...}
{"key":"OutOfStateTuition","label":"Out-of-State Tuition","value":50547,
 "config":{...,"tooltip":["Out-of-state college tuition is the higher tuition rate charged by public colleges and universities to students who are not residents of the state where the institution is located, as they don't pay state taxes."]}}
{"key":"InStateTuition","label":"In-State Tuition","value":16347,
 "config":{...,"tooltip":["In-state college tuition is the lower tuition rate charged by public colleges and universities to students who are legal residents of the state where the institution is located. It is subsidized by state tax revenue."]}}
```

and the trend card describes Net Price as:

> "Net Price ... Average cost per year after financial aid, grants, and
> scholarship aid" (dataSource: "IPEDS")

Note the shape of the failure: Berkeley shows **"Net Price $16,538"** with an
**empty tooltip**, right next to **"Out-of-State Tuition $50,547"** with a
careful, correct tooltip. The two clean residency definitions exist in the
product; they are simply not attached to the number that drives search and
ranking.

Niche also has a cost calculator that collects `householdIncome`, `roomBoard`,
`suppliesCost`, `otherExpenses` (state keys visible in the profile page JSON) —
again income and expense inputs, no residency input.

---

## 3. CollegeVine — collegevine.com

**Does it ask for residency before showing price? NO — but it shows both sticker
rates.**

**Search/filter/sort: could not be verified.** `/schools/hub/all` and
`/schools/explore` render only "Looks like you don't have JavaScript enabled.
Enable JavaScript to use our free tools." I will not guess at CollegeVine's
search controls.

**Profile page**
(`https://www.collegevine.com/schools/university-of-california-berkeley`,
server-rendered, 200) — the "Cost & scholarships" block reads exactly:

> Cost & scholarships Your estimated net cost $ ? / year Estimate my cost
> In-state $35,797 Out-of-state $65,869 Average net cost after aid Income |
> Average net cost $0–30,000 | $9,236 $30,001–48,000 | $10,294 $48,001–75,000 |
> $14,213 $75,001–110,000 | $22,151 Over $110,000 | $36,217 "Published costs and
> averages can be misleading: they don't fully account for your family's
> finances (for financial aid) or your academic profile (for scholarships)."

This is the clearest instance of the exact ambiguity we are deciding about. The
sticker row **is** residency-split. The "Average net cost after aid" table
directly under it is IPEDS in-state-only for a public, is **not** labelled as
such, and its disclaimer names _two_ reasons the number may mislead (family
finances, academic profile) and **omits residency**. A California family and a
New York family read the identical $9,236–$36,217 table.

---

## 4. BigFuture / College Board — bigfuture.collegeboard.org

**Does it ask for residency before showing price? NO.**

The premise that BigFuture asks in-state vs out-of-state in _search_ did not
hold up. What I found:

**There is no cost or price FILTER in the current college search at all.** The
full filter-key list is in the search bundle
(`https://bigfuture.collegeboard.org/college-search/672.e6d101c01bd40358392e.js`):

```js
r = "major",
  a = "majorCategory",
  i = "taxonomyMajor",
  o = "taxonomyAreaOfStudy",
  s = "schoolSize",
  u = "schoolTypeByDesignation",
  c = "schoolTypeByYears",
  l = "schoolTypeBySpecialty",
  d = "schoolTypeByStudyOptions",
  f = "state",
  h = "country",
  p = "stateByDistance",
  m = "zipCode",
  y = "schoolSetting",
  g = "religiousAffiliation",
  v = "acceptanceRate",
  _ = "extraDisabilityServices",
  b = "applicationFees",
  w = "acceptsApCredit",
  M = "commonApplication",
  k = "acceptsGedCredit",
  S = "acceptsTransferCredit",
  x = "acceptsClepCredit",
  L = "campusServices",
  E = "sports",
  T = "activities",
  C = "financialNeedMet",
  D = "openAdmissions",
  O = "financialAidForInternationalStudents",
  A = "applicationTypesAccepted",
  Y = "satScore",
  j = "gpaScore",
  P = "applicationDeadline",
  R = "degreesOffered";
```

No tuition, no net price, no cost-of-attendance key. `state`/`zipCode` are
school-location and distance filters.

**Price is shown and sorted, and the basis is a blended average.** The result
card in the same bundle renders:

> aria-label: "Average net annual cost" "$" +
> `Math.round(averageNetPrice/1000)` + "K" + " average per year after aid"
> fallback when missing: "Average annual cost after aid"

and the college-list sort options are:

```js
v = [{
  id: "acceptRate",
  text: "Acceptance Rate",
  sortQuery: { acceptanceRate: "desc" },
  default: !0,
}, {
  id: "netAsc",
  text: "Average Net Price",
  sortQuery: { averageNetPrice: "asc" },
  default: !1,
}, {
  id: "satDes",
  text: "SAT Range",
  sortQuery: { satCompositeScore25thPercentile: "desc" },
}];
```

So the sort label is **"Average Net Price"**, ascending, on one blended field.

**Where BigFuture DOES split residency: the profile page only.** From the
server-rendered CMS block on
`https://bigfuture.collegeboard.org/colleges/university-of-california-berkeley`:

```json
"stickerPrice":{"title":"Sticker Price"},
"inState":{"title":"In-State Tuition"},
"outState":{"title":"Out-of-State Tuition"},
"privateTuition":{"title":"Tuition"},
"householdIncome":{"title":"Average Net Price by Household Income"},
"lessThan30":{"title":"<$30k"},"lessThan48":{"title":"$30-48k"},
"lessThan75":{"title":"$48-75k"},"lessThan110":{"title":"$75-110k"},"moreThan110":{"title":"$110k+"}
```

with the net-price explainer:

> "Your net price is a college's cost of attendance minus the grants and
> scholarships you receive. The net price you pay for a particular college is
> specific to you because it's based on your personal circumstances and the
> college's financial aid policies. Use the college's Net Price calculator for
> the most accurate estimate of your net price."

and the summary sentence template, which is the unqualified one:

> "After scholarships and grants, $costSchoolName$ costs $costAmount$."

Same pattern as Niche and CollegeVine: residency splitting on sticker tuition,
income splitting on net price, and one flat sentence for the headline number.

---

## 5. Tuition Tracker — tuitiontracker.org (Hechinger Report)

**Does it ask for residency before showing price? NO — but it is the ONE product
that says so in plain words, in the product, and labels its charts by
residency.**

`https://www.tuitiontracker.org/` (the `/methodology.html` URL 404s; this text
is in the About/data block on the homepage):

> "Net prices are for first-time, full-time students (and, for public
> institutions, in-state students). The projected prices for each institution
> were calculated by taking the compound annual growth rate over the period of
> 2014-15 to 2024-25 using raw IPEDS data, then projecting that rate from the
> 2024-25 sticker price up to the 2026-27 academic year. ... Average net price
> projections are determined by applying the discount rate for each income level
> in the last historical year these data were available."

and again in the school-page copy:

> "Net price is calculated by subtracting federal, state, local and
> institutional grants and scholarships from the total cost of attendance for
> first-time, full-time (and, at public universities, in-state) undergraduates.
> The data includes only families of students who received some form of federal
> student aid, including loans, since others are not tracked."

Even the download link is labelled by basis:

> "School net prices — Net, in-state prices for each school"

**Search FILTER is income-personalised, not residency-personalised.** From the
page's i18n string table:

```
SearchBar.advanced.cost.title            => "What it will cost"
SearchBar.advanced.cost.instructions     => "Optionally select your hosuehold income to get a better price estimate"   [sic, typo is theirs]
SearchBar.advanced.cost.histogramTitle   => "Net price for"
SearchBar.advanced.cost.anyIncome        => "Any"
GeneralPurpose.incomeSelection.0_30000   => "<$30K income"   (also 30-48K, 48-75K, 75-110K, >$110K, "any income")
```

**SORT is on that same in-state-basis net price:**

```
SearchResults.sortBy.title           => "Sort by"
SearchResults.sortBy.name            => "Name"
SearchResults.sortBy.priceAscending  => "Price $ - $$$"
SearchResults.sortBy.priceDescending => "Price $$$ - $"
```

**Chart labels are residency-explicit** — this is the borrowable bit:

```
SchoolPage.Prices.inStateNetPriceLabel   => "in-state net price"
SchoolPage.Prices.inStateStickerLabel    => "in-state sticker price"
SchoolPage.Prices.outOfStateStickerLabel => "out-of-state sticker price"
SchoolPage.Prices.netPriceLabel          => "net price"
SchoolPage.Prices.stickerLabel           => "sticker price"
SchoolPage.Prices.priceTrendTemplate     => "This year at {SCHOOL_NAME}, we project that on average {STUDENT_TYPE} will pay {NET_PRICE}, while the advertised price of attendance is {STICKER_PRICE}. That's a difference of {PRICE_DIFFERENCE}."
```

So Tuition Tracker **builds the residency qualifier into the metric name
itself** ("in-state net price") rather than into a footnote. But note: the
_sort_ is still "Price $ - $$$" on a number whose basis is in-state at publics,
and the _filter_ still asks income and never residency.

---

## 6. Other search products and aggregators

### CollegeResults.org (Education Trust) — the only residency-correct data model found

`https://www.collegeresults.org/` ships its whole field config in the page. The
comparison/profile section:

```json
{"type":"group_title","label":"Cost of Attendance (COA)"},
{"type":"simple_group_item","label":"In State","sr_label_suffix":"cost of attendance","variable":"costs_avg_coa_in_state","value_prefix":"$"},
{"type":"simple_group_item","label":"Out of State","sr_label_suffix":"cost of attendance","variable":"costs_avg_coa_out_state","value_prefix":"$"},
{"type":"group_title","label":"Out of pocket cost (net price)"},
{"type":"simple_group_item","label":"In State","sr_label_suffix":"net price","compare":true,"variable":"costs_avg_coa_in_state","value_prefix":"$"},
{"type":"simple_group_item","label":"Out of State","sr_label_suffix":"net price","compare":true,"variable":"costs_avg_coa_out_state","value_prefix":"$"}
```

Two things to note, and the second is a caution not an endorsement:

1. Every money row is **labelled "In State" / "Out of State"** and carries a
   screen-reader suffix ("cost of attendance", "net price"). This is the most
   disciplined labelling of anything I fetched.
2. The rows under **"Out of pocket cost (net price)"** point at
   `costs_avg_coa_in_state` / `costs_avg_coa_out_state` — the
   **cost-of-attendance** variables, i.e. the same variables as the block above
   it. As shipped, their "net price" rows appear to display COA. Quoted verbatim
   so we do not repeat it.

Its filter definitions include a residency-split pair, marked hidden in the UI:

```json
{"id":"costs_avg_coa_in_state","label":"Average COA In-State","similar_search_range":3000,"hide":true,
 "section_id":"admissions_price","type":"range","range":[0,90000],"variable":"costs_avg_coa_in_state","tooltip_prefix":"$"},
{"id":"costs_avg_coa_out_state","label":"Average COA Out-of-State","similar_search_range":3000,"hide":true,
 "section_id":"admissions_price","type":"range","range":[0,90000],"variable":"costs_avg_coa_out_state","tooltip_prefix":"$"}
```

**Directly relevant to our similar-colleges tool:** their "find similar
colleges" match uses `"similar_search_range": 3000` on **both** in-state and
out-of-state COA separately (and `"similar_search_range":"same"` on Sector).
That is: match on residency-split price bands, do not collapse to one number.

- Asks residency before showing price? **No** (the two cost filters are
  `hide: true`).
- Filter/sort basis? **Residency-split variables exist and drive similar-school
  matching**; the visible filters are non-price.

### CollegeData — collegedata.com

`https://www.collegedata.com/college-search` (200, config JSON in page).

Filter, under "Financials, Debt & Aid":

```json
{"type":"SliderSelector","returnParamName":"costCode","data":{"title":"Cost of Attendance",
 "valueOptions":[{"value":0,"label":"No Preference"},{"value":1,"label":"$10,000 or less"},
 {"value":2,"label":"$15,000 or less"},{"value":3,"label":"$20,000 or less"},
 {"value":4,"label":"$25,000 or less"},{"value":5,"label":"$30,000 or less"},
 {"value":6,"label":"$35,000 or less"}, ...]}}
```

Sort options:

```json
"sortOptions":[{"value":"name","label":"Alphabetical"},
 {"value":"costLow","label":"Tuition Cost (Low to High)"},
 {"value":"costHigh","label":"Tuition Cost (High to Low)"},
 {"value":"admissionLow","label":"Admission Rate (Low to High)"},
 {"value":"admissionHigh","label":"Admission Rate (High to Low)"}]
```

Result cards **do** carry both rates, e.g.:

> "In-State Tuition : $3,292" / "Out-of-State Tuition : $11,092"
>
>> (and for privates the same value twice, e.g. "$47,350" / "$47,350")

- Asks residency? **No.** No residency control in the filter config.
- Filter basis: the filter is titled **"Cost of Attendance"** with no residency
  qualifier, and there is exactly one `costCode` parameter — one number, both
  audiences.
- Sort basis: label is **"Tuition Cost (Low to High)"**. Which of the two
  tuition columns it sorts on is **not stated in the label and I could not
  verify it** — passing `?sortBy=costLow` returned the same alphabetical server
  payload, so the sort is applied client-side. Flagging it as an _unlabelled
  ambiguous sort_, which is the pattern we care about.

This is the clearest "shows both, ranks on one, says which on neither" case.

### Appily (formerly Cappex) — appily.com

`https://www.appily.com/colleges` (200). The cost filter is a two-tab slider:

> tabs: "Net Price" | "Sticker Price" tooltip: "Net Price is the total cost
> after financial aid for students receiving grants or scholarship. Sticker
> Price is the yearly cost listed by the institution, including tuition and
> fees, room and board, and books and supplies." control:
> `input value="Any Price"` + range slider, one per tab

- Asks residency? **No.**
- Filter basis: **blended.** The tooltip explains net vs sticker and says
  nothing about in-state vs out-of-state. "the yearly cost listed by the
  institution" is precisely the sentence that hides which of the two published
  tuition rates is meant.
- Sort: not verifiable from the server HTML.

### Scholarships360 — scholarships360.org/colleges/

Ranked lists rather than a filterable search:

> "Top Colleges: Overall — Based on net price, graduate salaries, student debt,
> financial aid packages, graduation rate, & more" "Top Colleges: Affordability
> — Based on net price, student debt, and graduation rates"

Their own FAQ on the same page:

> "What is Net Price? Net price is the amount that a student ends up actually
> paying to attend a college. While each college posts costs for tuition, room
> and board, and other expenses, these vary by student. Different students
> receive different financial aid packages, and their expenses can be more or
> less depending on the cost of books and other factors."

and, separately:

> "Public colleges are run by the state, and most offer significant in-state
> tuition discounts."

- Asks residency? **No.**
- Ranking basis: net price, unqualified. They know and state that in-state
  discounts are significant — in an FAQ paragraph, not against the ranked
  number.

### MyinTuition — myintuition.org

Not a search/sort surface. It is a per-college quick net-price estimator:

> "This innovative tool provides a reliable estimate of what a family will have
> to pay based on just six simple questions."

(also on the page: "An Exciting Next Step for MyinTuition: We're joining College
Board to bring clearer, earlier college cost information to more students and
families.") Nothing to report on filter/sort. Whether its six questions include
residency was **not verified** — the calculator itself is behind the tool flow
and was not exercised.

### Not verified

US News (timeouts), TuitionFit (JS-only shell), CollegeAidPro/MeetCollegePro
(DNS failure), Edmit (defunct, not fetched), CollegeVine search/explore
(JS-only).

---

## Does anyone rank or sort on a price whose residency basis is ambiguous — and do they

## label it?

**Yes — essentially everyone ranks on it. Almost nobody labels it at the point
of ranking.**

| Product            | Asks residency first | Filter/sort basis                                              | Labelled at the ranking?                        |
| ------------------ | -------------------- | -------------------------------------------------------------- | ----------------------------------------------- |
| College Scorecard  | No (asks income)     | blended NPT4; sort "Annual Cost"                               | No — only in glossary/tooltip                   |
| Niche              | No                   | blended net price; "Cost (net price)" filter                   | No                                              |
| CollegeVine        | No                   | search not verifiable                                          | No (profile net table unlabelled)               |
| BigFuture          | No                   | no cost filter; sort "Average Net Price"                       | No                                              |
| Tuition Tracker    | No (asks income)     | in-state net price; sort "Price $ - $$$"                       | **Yes, in prose and chart labels**              |
| CollegeData        | No                   | "Cost of Attendance" filter; "Tuition Cost (Low to High)" sort | No                                              |
| Appily             | No                   | "Net Price"/"Sticker Price" tabs                               | No                                              |
| CollegeResults.org | No                   | residency-split COA variables drive similar-school match       | **Yes, "In State"/"Out of State" on every row** |

**Wording worth borrowing**

1. The federal sentence, because it is the source's own and is scoped to
   publics:
   > "For public schools, this is only the average cost for in-state students."
   > (collegescorecard.ed.gov/data/glossary/)

2. Tuition Tracker's approach of putting the basis in the **metric name**, so it
   travels with the number instead of living in a footnote:
   > "in-state net price" / "out-of-state sticker price" (tuitiontracker.org
   > i18n keys `SchoolPage.Prices.inStateNetPriceLabel`,
   > `outOfStateStickerLabel`)

3. Tuition Tracker's parenthetical, which is the most compact full statement:
   > "Net prices are for first-time, full-time students (and, for public
   > institutions, in-state students)."

4. CollegeResults' structural answer for a similar-colleges tool: keep
   `costs_avg_coa_in_state` and `costs_avg_coa_out_state` as **separate** match
   dimensions with a `similar_search_range` band on each, rather than one
   blended price band.

**Wording worth rejecting**

1. BigFuture's summary sentence, which states a residency-specific number as a
   fact about the college:
   > "After scholarships and grants, $costSchoolName$ costs $costAmount$."

2. CollegeVine's disclaimer, because it is the near-miss: it enumerates the
   reasons the number may mislead and leaves residency out, next to a sticker
   row that IS split.
   > "Published costs and averages can be misleading: they don't fully account
   > for your family's finances (for financial aid) or your academic profile
   > (for scholarships)."

3. Appily's "the yearly cost listed by the institution" — at a public there are
   two such costs and the sentence conceals the choice.

4. Niche's empty string. Berkeley's profile ships
   `{"key":"NetPrice","label":"Net Price","tooltip":"","value":16538}`
   immediately beside `"Out-of-State Tuition"` = `50547` with a correct,
   carefully written tooltip. Having the right definition in the product and not
   attaching it to the ranked number is the exact failure mode we are trying to
   avoid.

**One structural observation for our decision.** Every product in this set
solved the _income_ ambiguity by asking one question and re-rendering the
number, and left the _residency_ ambiguity unasked. The residency-correct
numbers are not missing from their data — BigFuture, Niche, CollegeData and
CollegeVine all display in-state and out-of-state sticker tuition on the
profile. What is missing is any product that carries residency into the filter,
the sort, or the similar-college match. CollegeResults.org is the sole exception
and it keeps those fields hidden from the UI, using them only for matching.
Nobody in this set has a human coach in the loop, which is the one asset we have
that they do not.
