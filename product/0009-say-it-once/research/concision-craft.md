# 0009 "Say it once" — EXTERNAL craft: saying sourced money facts concisely

Research report (outside-in). READ-ONLY; no repo code was read or changed.

## 1. Method

- **Web search was NOT available.** The `websearch` skill returned: "Web search
  is not set up yet: no Serper API key is configured." So I did not use a search
  engine. Instead I fetched **live pages directly over HTTP** (httpx +
  BeautifulSoup, 2026-09-10), and used the **OpenAlex works API**
  (`https://api.openalex.org/works?search=...`) as a literature search
  substitute. Every URL below was fetched by me on 2026-09-10 and the quoted
  strings are copied from what came back.
- Where a surface is a JavaScript app (College Scorecard), I fetched the Nuxt JS
  bundles listed in the page HTML and grepped the compiled templates for the
  real label / caveat strings. That is measured evidence of what the product
  actually renders, not a screenshot recollection.
- Where a surface is a PDF/HTML spec (College Financing Plan), I downloaded the
  official artifact and extracted text (`pypdf`, and the ED-published HTML
  specification zip), then **counted words**.
- Honest gaps, stated up front:
  - **Edmit** is effectively dead to me: `https://www.edmit.me/` returned HTTP
    **403** ("Edge IP Restricted | www.edmit.me | Cloudflare", Ray ID
    a38f2d9bab8cea78, 2026-09-10). I have no current measured evidence for it
    and make no claims about it.
  - **Common App** does not publish a per-college cost surface I could measure;
    I skipped it rather than speculate.
  - `plainlanguage.gov` now **redirects to digital.gov**. The old
    `/guidelines/concise/` URL and the `FederalPLGuidelines.pdf` URL both return
    the digital.gov "Plain language guide series" HTML page, not the old
    guidelines. I cite the current digital.gov pages.
  - Research on **grouped vs per-figure citation** is thin (section 3.4). I say
    where I am extrapolating.

---

## 2. How comparable products present dense, sourced college-cost data

### 2.1 College Scorecard (ED) — big number, glossary term, ~one caveat sentence

Fetched `https://collegescorecard.ed.gov/school/?166027`; the profile is a Nuxt
app, so I pulled its 34 JS assets and grepped the compiled templates.

**Pattern A — number + table, almost no prose.** In
`https://collegescorecard.ed.gov/_nuxt/euiFlSTl.js` the income-bracket cost
block is a `<table>` with a hidden caption and two columns:

> `t("caption",{class:"hidden"},"Average cost by family income",-1)` …
> `t("th",…," Family Income ")`, `t("th",…," Average Annual Cost ")` … rows
> `"$0-$30,000"`, `"$30,001-$48,000"`, `"$48,001-$75,000"`, …

Parallel figures go in a table. There is **no sentence per row**.

**Pattern B — the caveat is one sentence, and there are only a handful of
sentences on the whole profile.** I extracted every static prose node
(`t("p",…," …",-1)`) from all 34 bundles: **10 unique sentences total**, of
which the money-relevant ones are:

> "Depending on the federal, state, or institutional grant aid available,
> students in your income bracket may pay more or less than the overall average
> costs." "At some schools where few students borrow federal loans, the typical
> undergraduate may leave school with $0 in debt." "This is based on a standard
> 10-year payment plan, other…" (all from `_nuxt/euiFlSTl.js`)

**Pattern C — definitions and provenance are offloaded to a glossary, keyed by a
`term` id.** The profile bundle references a `<Term term="…">` component **23
times across 22 distinct terms** (`avg-debt`, `avg-loan-payment`,
`institution-median-earnings`, `parent-borrowing-rate`, `repayment-rate`,
`student-aid`, `threshold-earnings`, …). The definitions live at
`https://collegescorecard.ed.gov/data/glossary/` — a **6,337-word** page that is
NOT on the profile.

**Pattern D — provenance is a variable name + cohort year, one line, in the
glossary.** From the glossary page:

> "Average Annual Cost … Net price is calculated as the average over all
> full-time, first-time students who receive federal financial aid and may not
> reflect a specific student's annual costs. For public schools, this is only
> the average cost for in-state students. Negative cost values indicate that the
> average grant/scholarship aid exceeded the cost of attendance. **Relevant
> variables: NPT4_PUB, 2023-24 award year cohort NPT4_PRIV, 2023-24 award year
> cohort**"

Others:
`EARN_MDN_4YR, 2017-18 and 2018-19 pooled award year cohort measured in
calendar year 2022 and 2023`;
`DEBT_ALL_STGP_ANY_MDN, 2018-19 and 2019-20 pooled
award year cohort`;
`ADM_RATE_SUPP, fall 2024 cohort`.

**Pattern E — uncertainty/missing data is a component state, not prose.** The
bundles use a themed placeholder rather than an apology sentence:
`theme:"data-not-available"` (in `_nuxt/DldUlBb9.js` and `_nuxt/euiFlSTl.js`),
and empty table cells render as the literal string `" -- "`.

### 2.2 College Financing Plan (ED; formerly the Financial Aid Shopping Sheet)

Source page: `https://www2.ed.gov/policy/highered/guid/aid-offer/index.html` —
"The College Financing Plan (CFP) is a legacy consumer tool … It is intended
primarily for prospective students to receive information about costs and
financial aid so that they can easily compare institutions".

I downloaded the official 2027-28 undergraduate **HTML specification** zip
(`.../2027-28-college-financing-plan-undergraduate-html-specification-114544.zip`,
file `html-specifications/index.html`) and counted words.

- **Whole document: 1,344 words.**
- **Everything before the Glossary (i.e. the entire money surface): 476 words.**
- **Glossary: 868 words** — i.e. **65% of the text is definitions, and all of it
  sits at the bottom, once.**

The 476-word money surface is essentially all table rows with 3–10 word labels:

> "Tuition and Fees $ X,XXXX | Housing and Food | Books and Supplies |
> Transportation | Other Education Costs | Estimated Cost of Attendance $ X,XXXX
> / yr" "⚠ Important: Estimated College Costs You Will Be Required to Pay …
> **Net Price To You** — _Total cost of attendance minus total grants and total
> scholarships_ — $ X,XXXX / yr" "Federal Direct Subsidized Loan ( x.xx%
> interest rate) ( x.xx% origination fee)"

Three craft moves worth stealing:

1. **The derivation is the row's subtitle, not a paragraph**: "Total cost of
   attendance minus total grants and total scholarships" — 8 words, sits inside
   the table.
2. **One footnote marker for the one thing that needs it**: `Loan Options *` …
   "\* For federal student loans, origination fees are deducted from loan
   proceeds."
3. **Qualifiers ride in parentheses on the label**: "( x.xx% interest rate) (
   x.xx% origination fee)", "Work-Study Hours Per Week (estimated)".

### 2.3 Federal Net Price Calculator pattern

`https://collegecost.ed.gov/net-price` (ED's Net Price Calculator Center) is
**180 words total** and defines the term once, in the second person:

> "What does Net Price mean? Net Price is the amount that a student pays to
> attend an institution in a single academic year AFTER subtracting scholarships
> and grants the student receives."

The pattern: the definition lives at the _entry point of the tool_, once; the
calculator output is the number.

### 2.4 CFPB — "Your financial path to graduation"

`https://www.consumerfinance.gov/paying-for-college/your-financial-path-to-graduation/`
(~2,040 words including the whole form). Its sourcing is a **single link**:

> "Learn how we got the numbers and guidelines for our calculations." →
> `/paying-for-college/your-financial-path-to-graduation/how-we-got-these-numbers/`

That methodology page is **6,586 words**. So CFPB keeps a ~3:1 ratio of
off-surface methodology to on-surface tool text, with **zero per-figure
citations in the tool itself**. The methodology page states its own job
explicitly:

> "These technical notes will: Explain why we ask for certain information /
> Provide additional information about terms and programs mentioned in the tool
> / Describe where we supply data from / List the assumptions and methods for
> our calculations"

Note also how it handles a data gap — one sentence, actionable, no hedging
paragraph:

> "Salary data is not available for any undergraduate programs at your school.
> Please ask the school to provide you with earnings figures for recent
> graduates."

### 2.5 Niche

`https://www.niche.com/colleges/university-of-michigan-ann-arbor/cost/` — the
whole rendered cost page is **753 words**, and the string "Data source" appears
**exactly twice** (once per data block), never per figure:

> "Net Price $17,574 per year … National Avg State Avg Metro Area Avg Trending
> down 1.6% lower than last year" "Net price is the average out-of-pocket cost
> for students receiving aid, after subtracting scholarships and grants from the
> total cost of attendance, including tuition, books, supplies, housing and
> food. Colleges significantly discount their prices (avg is over 50%) based on
> family circumstances — families with more need typically pay less due to
> larger discounts. **Data source : Integrated Postsecondary Education Data
> System (IPEDS) by U.S. Dept. of Education**"

Structure: figure → comparison benchmarks (national/state/metro) → trend → a
`Question` (?) icon whose tooltip holds the definition → **one source line at
the end of the block**.

### 2.6 College Board BigFuture

`https://bigfuture.collegeboard.org/colleges/university-of-michigan-ann-arbor` —
**396 words rendered for the whole college overview**. The money headline is a
label-value pair with a rounded number:

> "Average Per Year After Aid $19K … SAT Range 1360–1530 Graduation Rate 94%"

Rounding to `$19K` at the summary level, exact dollars deeper in the Costs tab,
is itself a concision device: the summary carries the decision-relevant
magnitude, not the precision.

### 2.7 NerdWallet / Bankrate money explainers

`https://www.bankrate.com/loans/student-loans/average-cost-of-college/` and
`https://www.nerdwallet.com/article/loans/student-loans/how-to-pay-for-college`
are long-form SEO articles, not dense product surfaces. Their transferable
device is the **byline/attribution block** — "Written by Dori Zinn … Expertise •
P…", plus an "Advertiser Disclosure". Trust is carried by a _standing_
attribution block, once at the top, rather than by repeating "according to X" in
every paragraph. Their body copy is the opposite of concise and should not be a
model for unicoach.

### 2.8 Summary of the external pattern

Every serious surface I measured converges on the same shape:

| Layer               | What it holds                     | Measured example                                                                |
| ------------------- | --------------------------------- | ------------------------------------------------------------------------------- |
| Headline            | figure + unit + short label       | BigFuture "Average Per Year After Aid $19K"                                     |
| Table               | parallel figures, one row each    | CFP cost-of-attendance table; Scorecard income-bracket table                    |
| Inline qualifier    | ≤10 words, in the label/parens    | CFP "( x.xx% origination fee)", "(estimated)"                                   |
| One caveat sentence | only where it changes the reading | Scorecard "…may pay more or less than the overall average costs."               |
| Source line         | once per block                    | Niche "Data source : IPEDS by U.S. Dept. of Education"                          |
| Off-surface         | definitions + provenance + method | Scorecard glossary 6,337 words; CFPB method 6,586 words; CFP glossary 868 words |

---

## 3. Evidence on citation placement and repetition

### 3.1 Concise + scannable is measurably better (the one hard number)

Morkes & Nielsen's study, reported at
`https://www.nngroup.com/articles/how-users-read-on-the-web/`:

> "measured usability was dramatically higher for the **concise version (58%
> better)** and for the **scannable version (47% better)**. And when we combined
> three ideas for improved writing style into a single site, the result was
> truly stellar: **124% better usability**."

The same page's recipe is literally this brief's ask:

> "highlighted keywords … meaningful sub-headings (not 'clever' ones) … bulleted
> lists … one idea per paragraph … the inverted pyramid style, starting with the
> conclusion … **half the word count (or less) than conventional writing**"

And on trust, from the same study:

> "Credibility can be increased by high-quality graphics, good writing, and use
> of outbound hypertext links. Links to other sites show that the authors have
> done their homework… Users detested 'marketese' … credibility suffers when
> users clearly see that the site exaggerates."

That is a direct argument that **a link is a credibility device**; it is not
evidence that repeating the source in prose adds anything.

### 3.2 Readers read a fraction, so front-load

`https://www.nngroup.com/articles/how-little-do-users-read/`:

> "users have time to read at most 28% of the words during an average visit; 20%
> is more likely."

`https://www.nngroup.com/articles/inverted-pyramid/`:

> "Start content with the most important piece of information so readers can get
> the main point, regardless of how much they read." … "Even those who have the
> time or inclination to read only a single paragraph, or even single sentence
> will still know what the story is about."

### 3.3 Progressive disclosure is the mechanism for "lose nothing"

`https://www.nngroup.com/articles/progressive-disclosure/` (Nielsen, 2006):

> "Initially, show users only a few of the most important options. Offer a
> larger set of specialized options upon request. Disclose these secondary
> features only if a user asks for them, meaning that **most users can proceed
> with their tasks without worrying about this added complexity.**"

CFPB's design system operationalises the same idea and adds an important limit:

- Tooltips (`https://cfpb.github.io/design-system/components/tooltips`):
  > "Be judicious in using tooltips … Only consider using a tooltip for **short,
  > non-critical** information in a space-constrained user interface. Because a
  > tooltip is hidden until activated, **ensure content within a tooltip is not
  > essential for completing a task on the page.**"
- Expandables (`https://cfpb.github.io/design-system/components/expandables`):
  > "…for conserving vertical space by collapsing secondary information".

So: definitions and provenance may be hidden; a number the parent must act on
may not.

### 3.4 Grouped/footnoted sourcing vs per-figure attribution — evidence is THIN

I searched OpenAlex for direct evidence and did not find a study that compares
_grouped_ vs _per-figure_ source attribution in a data UI. The nearest
literature is about source credibility in general, e.g.:

- "The effect of characteristics of source credibility on consumer behaviour: A
  meta-analysis", J. Retailing & Consumer Services (2019),
  `https://doi.org/10.1016/j.jretconser.2019.01.005` — credibility effects come
  from _who_ the source is (expertise/trustworthiness), not from repetition.
- "Credibility: A multidisciplinary framework" (2007),
  `https://doi.org/10.1002/aris.2007.1440410114`.
- "How do consumers search for and appraise health information on the world wide
  web?", BMJ (2002), `https://doi.org/10.1136/bmj.324.7337.573`.

**Honest conclusion:** I have no direct experimental evidence that moving from
per-figure attribution to one source line per block hurts trust. The defensible
claims are (a) named, authoritative sourcing matters, (b) it must be _present
and findable_, and (c) the strongest surfaces in this exact domain — federal
ones, under legal plain-writing duty — use **one source statement per block**
(Niche, Scorecard glossary) or **one methodology link per tool** (CFPB), not
per-figure prose. Treat "one source line per block" as best-practice
convergence, not as a proven trust-neutral result. If unicoach wants certainty,
A/B it.

### 3.5 Plain-writing guidance (current, post-redirect)

`https://digital.gov/guides/plain-language/` states the legal basis:

> "Not only is plain language more efficient and effective. It is also the law.
> The Plain Writing Act of 2010 established the requirement that content for the
> public is written for its specific audience."

`https://digital.gov/guides/plain-language/design`:

> "Create logically structured pages with key information at the top. Use
> heading levels consistently … Use lists for easy scanning. **Where
> appropriate, use tables to make complex relationships clear.**"

`https://digital.gov/guides/plain-language/writing`: shorter words, short
sections, **active voice**, **present tense** — "The more you use conditional or
future tense, the harder your audience has to work to understand your meaning."
That is a direct hit on hedged coach prose ("you would likely be expected to
pay…").

CFPB's table guidance (`https://cfpb.github.io/design-system/components/tables`)
gives the unit rule that kills repetition:

> "**if the units are the same across columns, indicate the unit of measurement
> in the title or someplace noticeable above the table.**" … "if the table uses
> at most two units of measurement, consider using asterisks following the
> column label, and define the unit of measurement below the table."

i.e. "/yr" and "$" belong in the header, not in every cell — exactly the CFP's
"$ X,XXXX / yr" only on total rows.

---

## 4. LLM-assistant concision, specifically

Evidence here is weaker than for web content; I flag what is measured vs what is
craft convergence.

**Measured:** Liu, Zhang & Liang, "Evaluating Verifiability in Generative Search
Engines" (Findings of EMNLP 2023), `https://arxiv.org/abs/2304.09848`:

> "on average, a mere **51.5% of generated sentences are fully supported by
> citations** and only **74.5% of citations support their associated
> sentence**." … "responses from existing generative search engines are fluent
> and appear informative, but frequently contain unsupported statements and
> inaccurate citations… especially given their **facade of trustworthiness**."

Two implications for 0009:

1. **More sentences = more unsupported sentences.** Verbosity is not neutral; it
   manufactures claims the data does not back. Cutting prose to the figures you
   actually hold is a _correctness_ intervention, not only a style one.
2. **Citation density is not trust.** Sprinkling "(source: IPEDS 2023-24)" after
   every figure creates the same "facade of trustworthiness" the paper warns
   about, while the real requirement is that every stated number is traceable.

**Craft convergence (weaker evidence):** the OpenAI Model Spec
(`https://model-spec.openai.com/2025-04-11.html`, raw at
`https://raw.githubusercontent.com/openai/model_spec/main/model_spec.md`) frames
per-surface response style as explicit, overridable guidelines rather than
derived on the fly — "we improve predictability and reliability" by specifying
them. I did **not** find a quotable "be concise" clause in the version I
fetched, so I will not claim one. The practical rule stands on the web-writing
evidence in §3 instead: front-load the number (inverted pyramid), tabulate
parallel figures, state the source once, and hedge only where the hedge changes
the decision.

**Applied shape for a coach turn** (synthesis of §2.8 + §3.1–3.3, not a cited
finding):

> **You'd likely pay about $19,000 a year at Michigan.**
>
> |                                                                           | Per year    |
> | ------------------------------------------------------------------------- | ----------- |
> | Sticker (cost of attendance)                                              | $34,000     |
> | Grants and scholarships (typical, your income band)                       | −$15,000    |
> | **Net price**                                                             | **$19,000** |
> | Your income band matters here: families under $30k typically pay $12,000. |             |
> | Figures: IPEDS 2023-24 net price by income band.                          |             |

One headline, one table, one caveat that changes the number, one source line.

---

## 5. Recommended rules (each a testable acceptance criterion)

Each rule is written so a test can pass or fail on rendered output.

**R1 — Front-load the number.** The first sentence of any money answer contains
a figure and the entity it belongs to. _Test:_ the first sentence of the coach
reply and the first line of each report section match a currency/percentage
pattern. (Basis: inverted pyramid, NN/g; §3.2.)

**R2 — Parallel figures go in a table, never in prose.** Three or more figures
of the same kind (per-year costs, aid components, income bands) render as a
table row each. _Test:_ no rendered paragraph contains ≥3 currency values.
(Basis: CFP HTML spec; Scorecard income table; digital.gov "use tables to make
complex relationships clear".)

**R3 — Units live in the header, once.** "$" and "/yr" appear in the column
header or table title, not in every cell of a same-unit column. _Test:_ for any
single-unit column, the unit string appears at most twice (header + total row).
(Basis: CFPB tables guidance, §3.5.)

**R4 — One source line per block, not per figure.** Each data block carries
exactly one provenance line naming the dataset and its year. _Test:_ count of
source/attribution strings per block == 1; the same dataset name never appears
twice in one section. (Basis: Niche "Data source : IPEDS…"; CFPB single
methodology link; convergence, not proven trust-neutral — see §3.4.)

**R5 — Provenance must exist somewhere for every figure.** Every rendered figure
maps to a glossary/method entry naming the source variable and cohort year.
_Test:_ every figure key has a non-empty method entry, in the style of
Scorecard's "Relevant variables: NPT4_PUB, 2023-24 award year cohort". (Basis:
§2.1 Pattern D. This is what makes R4 safe: fewer citations, not less
traceability.)

**R6 — Define each term once, off-surface.** A term's definition appears at most
once in the whole document/turn, in a glossary or tooltip; the body uses the
term bare. _Test:_ no definitional pattern ("X is the …", "X means …") occurs
twice for the same term in one rendering. (Basis: CFP — 868 of 1,344 words are
the glossary, all at the end; Scorecard 22 glossary terms; §2.1, §2.2.)

**R7 — Hidden content must be non-essential.** Anything the parent must act on
stays visible; only definitions, provenance and method may be collapsed. _Test:_
the set of tooltip/expandable payloads contains no figure the decision depends
on. (Basis: CFPB tooltip guidance — "ensure content within a tooltip is not
essential for completing a task on the page"; §3.3.)

**R8 — At most one caveat sentence per figure, and it must change the reading.**
A caveat is allowed only if it names a condition under which the number would
differ. _Test:_ ≤1 caveat sentence per figure; each caveat contains a
comparative or conditional that names an alternative value or population (e.g.
"may pay more or less than the overall average costs"). (Basis: Scorecard ships
~10 static prose sentences on the entire profile; §2.1 Pattern B.)

**R9 — Inline qualifiers are ≤10 words and ride on the label.** "(estimated)",
"(in-state)", "(typical, your income band)" — not a following sentence. _Test:_
every parenthetical qualifier attached to a figure label is ≤10 words. (Basis:
CFP "( x.xx% origination fee)", "Work-Study Hours Per Week (estimated)"; §2.2.)

**R10 — Missing data is a state, not an apology.** Absent figures render a fixed
short marker plus, at most, one actionable sentence. _Test:_ the missing-data
rendering is a single fixed string (e.g. "Not available") plus ≤1 sentence that
contains an imperative. (Basis: Scorecard `theme:"data-not-available"` and
`" -- "`; CFPB "Please ask the school to provide you with earnings figures for
recent graduates."; §2.1 Pattern E, §2.4.)

**R11 — Present tense, active voice, second person.** _Test:_ no "would be
expected to", "it should be noted", "may be considered" patterns in money copy.
(Basis: digital.gov writing guide — conditional/future tense "makes the audience
work harder"; §3.5.)

**R12 — Word budget per surface, enforced.** Set a hard cap and measure it, as
these products effectively do: BigFuture overview **396 words**, Niche cost page
**753 words**, CFP money surface **476 words** (glossary excluded). _Test:_
rendered word count of the parent Family Cost Report money section ≤ ~500 words
excluding glossary/method; regression test asserts the count. (Basis: measured
counts in §2; Morkes & Nielsen's "half the word count or less" → 58% usability
gain, §3.1.)

**R13 — No sentence without a supported number or a decision.** Delete sentences
that restate a figure already shown. _Test:_ no figure value appears in both a
table cell and a body sentence within the same section (the headline sentence in
R1 is the single allowed exception). (Basis: Liu et al. 2023 — unsupported
generated sentences are the dominant failure mode; §4.)

---

## Sources (all fetched 2026-09-10)

- https://collegescorecard.ed.gov/school/?166027 and its bundles
  `/_nuxt/euiFlSTl.js`, `/_nuxt/DldUlBb9.js`
- https://collegescorecard.ed.gov/data/glossary/
- https://www2.ed.gov/policy/highered/guid/aid-offer/index.html
- https://www.ed.gov/media/document/2027-28-college-financing-plan-undergraduate-html-specification-114544.zip
- https://www.ed.gov/media/document/2027-28-annotated-undergraduate-college-financing-plan-114401.pdf
- https://collegecost.ed.gov/net-price
- https://www.consumerfinance.gov/paying-for-college/your-financial-path-to-graduation/
- https://www.consumerfinance.gov/paying-for-college/your-financial-path-to-graduation/how-we-got-these-numbers/
- https://www.niche.com/colleges/university-of-michigan-ann-arbor/cost/
- https://bigfuture.collegeboard.org/colleges/university-of-michigan-ann-arbor
- https://www.bankrate.com/loans/student-loans/average-cost-of-college/
- https://www.nerdwallet.com/article/loans/student-loans/how-to-pay-for-college
- https://www.nngroup.com/articles/how-users-read-on-the-web/
- https://www.nngroup.com/articles/how-little-do-users-read/
- https://www.nngroup.com/articles/inverted-pyramid/
- https://www.nngroup.com/articles/progressive-disclosure/
- https://cfpb.github.io/design-system/components/tooltips
- https://cfpb.github.io/design-system/components/expandables
- https://cfpb.github.io/design-system/components/tables
- https://digital.gov/guides/plain-language/ , /writing , /design
- https://arxiv.org/abs/2304.09848 (Liu, Zhang, Liang, Findings of EMNLP 2023)
- https://raw.githubusercontent.com/openai/model_spec/main/model_spec.md
- OpenAlex API: https://doi.org/10.1016/j.jretconser.2019.01.005 ,
  https://doi.org/10.1002/aris.2007.1440410114 ,
  https://doi.org/10.1136/bmj.324.7337.573
- NOT reachable: https://www.edmit.me/ (HTTP 403, Cloudflare "Edge IP
  Restricted")
