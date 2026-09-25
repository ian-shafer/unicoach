# RFC 238 — The FAFSA page

**Slice:** `fafsa/04/the-fafsa-page` (brief 0012, FAFSA support) **Lane:** A
(rfc) — a new public surface, a fourth case in the explainer model, and a
widening of the shared deadline service. **Base:** `main@8c7d4b27`

## Summary

`GET /fafsa`: the public explainer a family reaches with no account, whose
centre is a table of FAFSA dates for every one of the 51 jurisdictions, grouped
by region, each row carrying the agency's own words, its cycle, its publisher
and the day we read it.

Ian's words are the design (brief 0012 D4): "we don't make ppl go read dry
federal and state documents; Unicoach consolidates and shares data. In fact, the
public html page should show a table of the dates for each state and region."

Nothing here computes a date. `FafsaDeadlineService` (RFC 230) already decides
the live set, the cycle per jurisdiction and the three refusal arms; this page
renders what it returns. Three gaps in that service stop the page rendering the
truth today, and closing them is most of the non-page work in this RFC.

## Detailed Design

### 1. The page, in order

One `ExplainerPage` at `/fafsa`, `ShellWidth.WIDE` (the 80rem token that already
exists in `Layout.kt`), header nav link, no account, no form, no ask.

1. **"The FAFSA is free"** — first, before any instruction about filing (D6).
   The statutory rule is already cited in the landed corpus (`FafsaProcessCopy`
   `THE_FORM_IS_FREE`, 20 U.S.C. §1090(d)), and beside it one sentence of ours:
   unicoach never fills the form in, never submits it, and never asks for the
   login. That sentence is about US, so it is `Prose`; the rule beside it is a
   `Claim` and carries the citation.
2. **"What the form asks now"** — the contributor model, the consent rule whose
   failure mode is total, and the Student Aid Index. Every sentence is an
   existing cited rule (`WHO_FILES`, 8 rules).
3. **"When it is due"** — the tables (§2). Federal first, then one table per
   region, then the four jurisdictions we could not read, then the sentences
   that bound the table: the two-cycle overlap (D23), the territories we do not
   cover (D13), and per-school priority deadlines being out of scope (D4).
4. **The edge cases no competitor page covers** — divorced or separated parents
   (the more-than-half-support rule, and the old "parent you lived with" rule
   named as wrong), a contributor with no Social Security number, dependency,
   and what happens after you submit. All existing cited rules.
5. The inherited "As of" line and the generated sources list.

**Every rule in the landed corpus goes on the page — all 30, all six topics.**
The rule for the choice is the one the brief already binds us to: the page and
the coach must not tell a family two different stories (D4). A subset would need
its own defensible boundary and would create exactly that gap.

**The page prints no figure of its own.** No coverage count (D20's reasoning: a
hardcoded number rots, a runtime number is a sentence a family cannot act on),
no Pell maximum, no "families left $N unclaimed". Figures quoted inside a cited
rule (the Student Aid Index floor is statutory) are the source's, not ours. **No
2024-rollout copy** (D21, which names coach copy; the same reasoning binds the
page, and this RFC says so explicitly so the next reader does not re-argue it).

### 2. The table, and the fourth block case

`ExplainerBlock` has three cases — `Prose`, `Claim`, `SelfCited` — and none is a
table. This RFC adds a fourth, `CitedTable`:

```kotlin
data class CitedRow(
  val cells: List<String>,     // rendered, in column order; COPIED at construction
  val source: ExplainerSource, // the row's own citation
  // The sweep's VERDICT, not a rendering of it -- tier 2. Whether a verdict
  // earns an anchor is SourceUrlStatus.rendersAsAnchor's rule, asked where the
  // anchor is drawn: carried as a Boolean, the reason a link is absent ("the
  // page answered 404" versus "we never asked") was spent at the boundary and
  // thrown away.
  val sourceUrlStatus: SourceUrlStatus,
  // Where the row was read THROUGH, when the agency's own page opens for no
  // client we have -- the STORE's own value, both halves together
  // (ArchivedCitation), for the reason §3 gives for holding it as one value.
  // Held as a url alone, three domain states collapsed into one nullable
  // string: never archived, archived and alive, archived and a grave.
  val archive: ArchivedCitation? = null,
)

// A COLUMN IS A VALUE, NOT THE TEXT OF ITS HEADER -- tier 2. The authored set
// was declared by matching header strings, which made column identity display
// copy: rewording a header either moved the sweep exemption to another column or
// faulted the table at render, a branded 503 earned by a copy edit.
data class TableColumn(
  val header: String,
  val authored: Boolean = false, // unicoach wrote these cells rather than reading them
)

data class CitedTable(
  val caption: String,
  val columns: List<TableColumn>, // the DATA columns; every row carries exactly this many cells
  // The column the row's own citation is printed under, held APART from the
  // data columns so the per-row arity guard (`row.cells.size == columns.size`)
  // holds without special-casing the last column. Every rendered header is
  // bounded as one set -- blank refused by TableColumn, and no two headers
  // alike, the citation column included, because a header is also a stacked
  // cell's `data-label`.
  val sourceColumn: TableColumn,
  val rows: List<CitedRow>,
) : ExplainerBlock
```

**A cited address is an absolute `http(s)` address, and that rule has ONE home**
(`requireCitedPageUrl` in `:common`, beside the landed `isWebUrl`) — tier 2, and
a defect this RFC shipped. `ExplainerSource` demanded `https://`, and four
Tennessee rows of the landed corpus cite `http://collegefortn.org/...`: those
rows were DROPPED, so Tennessee reached no table and — being served — no gap
list either, on the very state this RFC names for the extra-application case.
`external_documents` accepts http by an explicit decision, so the page's bar was
wrong and not the data. A reader's citation guard may not be stricter than the
corpus it renders: the row does not fail loudly, it disappears.
`ArchivedCitation` carried the same https-only bar on a read path with no
failure branch at all, where it faults the WHOLE `:db` read rather than one row.

A row that cannot name where it came from does not construct — the same guard
`Claim` has, applied per row, which is the whole reason a table needs a case of
its own rather than being flattened into prose.

Four edit sites in `ExplainerPage.kt`, two of which the compiler does NOT catch
and which are therefore named here:

- `unsourcedPassages()` — a new `when` arm. A table's PUBLISHED cells are
  sourced data, exactly as a `Claim`'s text is, and are exempt. This is what
  keeps the prose sweep (no digits, no percentages, no quotation marks in
  unicoach's own copy) writable on a page carrying 94 published dates. The
  exemption stops at the publisher's words, and the arm therefore returns the
  caption, the column headers and the cells of the columns that declare
  themselves authored — reason: the exemption was argued for data we read, and a
  table would otherwise be the one place on the page a copy edit could put a
  figure with nothing to catch it (the "What this date is" column is ours). What
  the page can say about the jurisdictions it could not read is still ONE prose
  block above that table, not a cell repeated in every row of it.
- `renderCitation()` — a new arm.
- `sourcesInReferenceOrder()` — **filters `filterIsInstance<Claim>()`, so a
  table's sources silently never reach the marker map and `getValue` throws at
  render.** The fix is deliberate and is a design decision, below.
- `renderBlock()` — wraps every block in `<p>`. A `<table>` inside a `<p>` is
  invalid HTML that the parser auto-closes. It becomes a `when`.

**A table row cites itself, in the row; it does not join the page's footnotes.**
The page-level sources list stays what it is — the numbered list of documents
the page's PROSE claims cite. Three reasons: 84 distinct agency documents would
drown a 14-entry reference list; the inherited list requires one reading per url
and would fault on two rows of one agency read on different days; and a family
scanning for its own state wants the agency's name beside its own row, not a
footnote number. The row renders `publisher` as a link to `sourceUrl`, plus "as
we read it on <date>", and the archived copy where the agency blocks every
client we have.

**A dead link is never rendered as a link** (RFC 200). `external_documents`
already holds `url_status`; one of the corpus's 84 urls is `not_found`
(Wyoming's Hathaway page) and five are `unknown`. `not_found` renders the
publisher's name as text with no anchor; `unknown` — we asked and got no usable
answer, the page may well be live for a person — stays a link. This needs the
status to reach the page (§3).

**Shape.** One table per region (the 51 jurisdictions fall into 8 of the 10
IPEDS regions), plus one for the federal rows. Columns: jurisdiction, programme,
what kind of date it is, what the agency says, the cycle it belongs to, and the
source. The kind of date is an exhaustive `when` over `AidDeadlineEvent`, so a
fifth event is a compile error rather than a wire code printed at a parent: 36
of the landed rows are priority deadlines and 87 are final ones, the agencies'
own words do not reliably say which, and a priority date read as the last day is
the confusion on this page that costs a family money. A jurisdiction with
several programmes contributes several rows; the live set is 94 state rows
today. Mobile: the stacking pattern `.report-table` already uses (`data-label`
attributes, the existing 640px media query) — no new media query, no CSS
nesting, no inline style, all three of which the served-stylesheet tests forbid.

**Dates render through `UsDate.of`.** `DateRenderingHasOneSeamTest` sweeps every
module's `src/main/kotlin` for a second date formatter and for month-name
literals, and `:public-web` is on nobody's permitted list.

### 3. What the deadline service must gain

Three changes, all in code this run owns, none of them a schema change.

**a. `missingJurisdictions` carries a jurisdiction a page can print.** It is
`List<DeadlineJurisdiction.State>` today — a USPS code and nothing else — but
the page must print all 51 grouped by region, and Arkansas, Delaware, North
Carolina and New Hampshire have no row to carry a name or a region. The element
type becomes:

```kotlin
data class MissingJurisdiction(
  val jurisdiction: DeadlineJurisdiction.State,
  val name: String,
  val region: String,
  // NULLABLE: the corpus in (b) states TODAY's seed, so a later re-seed can
  // gap a jurisdiction we never tried. The page then says less -- it names the
  // jurisdiction and says we hold nothing for it -- rather than naming an
  // agency we did not try, or refusing the table for all 51 over one gap.
  val agency: UnreadableAgency?,
)
```

The name and the region come from the same `CodebooksDao` read the served
jurisdictions already use, so the page still derives nothing and no second
region vocabulary appears (gate-2 D10).

All four of today's gaps carry an agency and a test says so; the nullability is
about the seed changing under the corpus, which is the failure this run cannot
test away.

**b. The four we could not read get a cited agency** (D9: the page "names the
agency to call, and links what we tried"). A small typed corpus in `:service`
beside `FafsaProcessCopy`, same guard — an entry with no https page and no read
date does not construct:

```kotlin
data class UnreadableAgency(
  // The page itself, in the ONE type §4 gives a cited document -- reason: the
  // guard on "a page we cite" is one guard, and a second copy of it is one
  // nobody keeps in step.
  val document: FafsaProcessCopy.FafsaDocument,
  // What bin/check-link answered, run in this run. NO DEFAULT -- reason: the
  // unmeasured answer must not be the anchor-rendering one, and whether a
  // verdict earns an anchor is SourceUrlStatus's rule and not a boolean here.
  val urlStatus: SourceUrlStatus,
  val checkedOn: LocalDate = CHECKED_ON,
)
```

Four entries, taken from the harvest's own notes
(`product/0012-fafsa-support/discover/state-deadlines-*.md`), each url swept
with `bin/check-link` during implementation and recorded with what the sweep
said: Arkansas 200, Delaware 403, North Carolina 200, New Hampshire 403 — the
403s record as `unknown`, because an agency refusing our client is not a grave.
This is a citation for "here is the page we tried", not for a deadline: no date
is claimed for these four, which is exactly D9's point.

The corpus is PASSED to `FafsaDeadlineService` as a constructor parameter
defaulting to the production one — reason: what a gap says about itself is
context the service is given, so a test states its own scenario instead of
borrowing whichever entries production ships.

A jurisdiction that HAS rows and also appears in this corpus is an entry the
seed has OVERTAKEN, and the service WARNS the operator and serves the rows —
reason: a served jurisdiction never reaches the gap list, so no door can render
the contradiction, and refusing would answer `fafsa/02` FIXING a gap with a 503
for all 51 and for the coach.

**c. The row carries its url status.** `FafsaFilingDeadlineDao` projects
`s.url`/`a.url` only. It gains `url_status` for both documents, and
`FafsaDeadline` carries them, so the render rule above is a fact from the store
rather than a literal in the page.

The archive half is ONE nullable value and not two —
`ArchivedCitation(url,
urlStatus)` on both `FafsaFilingDeadline` and
`FafsaDeadline` — reason: the LEFT join produces both or neither, so a url with
no verdict must not be representable, and the render then asks one question
instead of defending against a state the store cannot return.

**d. `:public-web` reads the service through a narrow port.** `Application.kt`
constructs `ServiceFafsaDeadlineSource(FafsaDeadlineService(database))` — the
adapter TAKES its service rather than building one, as the landed
`ServiceCostReportSource` boot site does, reason: the boot site owns every
construction and a test of the adapter can then stand a service in, the clock
that service takes included. It is a `FafsaDeadlineSource` with one method,
`suspend fun get(): Result<FafsaDeadlines>` — and the route layer never sees a
`Database`. This is the landed `CostReportSource` precedent, and it is what
makes the page's four arms testable: the resolved answer, the three refusals of
§5 and the faulting read are hand-built values in a fake rather than a seeded
corpus plus a driven clock. Those four decisions belong to
`FafsaDeadlineService` and are proven against real Postgres in its own suite;
re-driving them through a page test would assert the same rules twice, in the
worse place. The adapter takes no zone: a browser sends none, so the service
falls back to its one home for that decision.

**Sibling run.** `pipeline/rfc-237` is building `fafsa/05` (the coach speaking
these dates) against the same service on the same base. (a) is a breaking change
to a type its tool may print. It is one line at their end, it is the right shape
for both doors, and the report states which run rebased onto which.

### 4. One home for a cited document

`ExplainerSource` needs a publisher and a title separately; `FafsaRule.citedAs`
is one string ("Federal Student Aid, 2025-26 FSA Handbook, AVG, Ch. 1"), and one
of the eight documents has commas inside its title, so no split rule is safe.
Rather than retype an eight-entry mapping in `:public-web` — a second home for a
fact, which is the defect `concise/03` exists to prevent — the landed corpus
gains the type it already implies:

```kotlin
data class FafsaDocument(val publisher: String, val title: String, val url: String)
```

The eight document constants become `FafsaDocument` values and `FafsaRule` cites
a document. ONE URL IS ONE DOCUMENT, so the two statute rules share `STATUTE`,
which is what makes the page's reference list legal (one reading per url) — and
the subsection each of those two is in therefore moves to the RULE, as a
`pinpoint` string that is empty for a document cited whole. `citedAs` becomes
the derived join (`"$publisher, $title$pinpoint"`), so the coach tool's wire
payload does not change by a byte.

The pinpoint is not decoration: without it, the two demands above cannot both
hold. One document for the statute collapses `§ 1090(d)(3)` and `§ 1090(d)(2)`
into one citation and changes what the coach emits for both; two documents put
two readings on one url, which `sourcesInReferenceOrder()` refuses at render.
The pinpoint keeps the url single and every `cited_as` byte-identical, and the
test pins those eight strings as literals rather than recomputing the join.

### 5. When the service refuses

The page renders on all three refusal arms and never shows a date it does not
believe: it says which of the three it is in a family's words, repeats the free
and we-never-touch-it sentence, keeps the process sections (they are statutory,
not dated), and points at the agency and the college's financial aid office. The
table is absent, not empty, and the "as of" line is absent because a refusal
carries no date. That last one is a type change and not a render branch:
`ExplainerPage.asOf` becomes `LocalDate?`, because printing today's date over a
table that is not there is exactly the sentence a refusal exists to withhold.
The who-pays guide always has a date and now asserts so (`assertNotNull`), which
is the whole cost of the widening to a landed page. The operator line the
service already logs is the fix signal; the page says nothing about seeds or
cycles.

### 6. Route, chrome, and the tests that fail by name

- `Routing.kt`: one `get(FAFSA_PATH)`. `WebAuthGate.mapPathToAccess` defaults to
  `OPEN`, so no allowlist entry.
- `CspConformanceTest` walks Ktor's routing tree and fails by name unless
  `/fafsa` is in `PUBLIC_ROUTES`. It is added there.
- `Layout.siteHeader` hardcodes its links; `/fafsa` joins them, reading its path
  and title from the page value exactly as the who-pays guide does. **That link
  is the door**: a page only a URL-typer can reach is not one.

## Files Modified

| File                                                                      | Change                                                                                     |
| ------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------ |
| `public-web/src/main/kotlin/ed/unicoach/web/render/FafsaPage.kt`          | NEW — the page value, its sources, its sections, the responder                             |
| `public-web/src/main/kotlin/ed/unicoach/web/render/ExplainerPage.kt`      | `CitedTable` + `CitedRow` + `TableColumn`, four render/sweep sites, width, `asOf` nullable |
| `public-web/src/main/kotlin/ed/unicoach/web/render/DataTable.kt`          | NEW — the shared table class, and the `data-label` attribute beside it                     |
| `common/src/main/kotlin/ed/unicoach/common/util/WebUrl.kt`                | `requireCitedPageUrl` — the ONE home for "an address we are willing to cite"               |
| `public-web/src/main/kotlin/ed/unicoach/web/render/Layout.kt`             | the header link                                                                            |
| `public-web/src/main/kotlin/ed/unicoach/web/Routing.kt`                   | `get(FAFSA_PATH)`                                                                          |
| `public-web/src/main/kotlin/ed/unicoach/web/Application.kt`               | construct `ServiceFafsaDeadlineSource(FafsaDeadlineService(database))`, thread it          |
| `public-web/src/main/kotlin/ed/unicoach/web/fafsa/FafsaDeadlineSource.kt` | NEW — the narrow read port (`CostReportSource`'s shape) and its in-process adapter         |
| `public-web/src/main/resources/static/site.css`                           | the cited-table rules, inside the existing media queries                                   |
| `service/.../aid/FafsaDeadlineService.kt`                                 | `MissingJurisdiction`, url status on `FafsaDeadline`, the refusal                          |
| `service/.../aid/FafsaUnreadableAgencies.kt`                              | NEW — the four agencies we could not read                                                  |
| `service/.../aid/FafsaProcessCopy.kt`                                     | `FafsaDocument`; `pinpoint`; `citedAs` derived; two statute rules share one                |
| `db/.../dao/FafsaFilingDeadlineDao.kt`, `models/FafsaFilingDeadline.kt`   | project and carry `url_status` for source and archive                                      |
| `public-web/src/test/...`                                                 | `FafsaPageTest`, the leak sweep, the fake source, `WhoPaysPageTest` (`asOf` non-null)      |
| `service/src/test/...`, `db/src/test/...`                                 | the widened types and the corpus guard                                                     |

No migration. No new table. No coach prompt edit — the page does not touch
`prompts/coach-system-prompt.md`, so no version is cut.

## Implementation Plan

1. `:db` — project `url_status` for both documents; extend the model and its DAO
   test.
2. `:service` — `FafsaDocument` in the process corpus (payload byte-identical);
   `FafsaUnreadableAgencies` with the four entries, urls swept with
   `bin/check-link`; `MissingJurisdiction`; url status on `FafsaDeadline`; the
   corpus-versus-seed refusal.
3. `:public-web` — `CitedTable` and the four render sites; `FafsaPage.kt`
   content; the route, the nav link, `PUBLIC_ROUTES`; the stylesheet rules.
4. Tests, then `nix develop -c bin/test` through `ship-run`.

## Tests

Behavioural only; no static-analysis test is added (repo rule).

- **The page**: 200 with no session; the jurisdictions the page TABLED — the
  rendered cells of the jurisdiction column, never a name matched anywhere in
  the body, since the page's own scope sentence names the District of Columbia —
  are exactly the codebook's 51, so a half-rendered table fails; the free
  sentence appears before any sentence about filing; a jurisdiction whose rule
  is a date shows that date; one whose rule is words shows the words verbatim;
  one whose absence is verified says the agency publishes none; the four we
  could not read say we tried, name the agency and link what we tried; every row
  shows its cycle; a state that also demands its own application says so; the
  territories sentence is present; the bundle is the page's only script.
- **Citations**: the Wyoming source renders as text and not as an anchor; an
  `unknown` source renders as a link; the page-level reference list holds no url
  twice; the third-party leak sweep passes for this page.
- **Refusal**: each of the three arms renders 200, shows no table, shows no "as
  of" date, and still shows the free sentence.
- **Model**: a `CitedRow` with no source does not COMPILE — `source` is a
  non-null positional parameter, so that guard is the type's and no test can
  express it. The tests assert the guards that DO run: a row refuses an empty
  cell list and a blank cell, and keeps its cells when the caller empties the
  list it passed; a column refuses a blank header; a table refuses a blank
  caption, two headers of one name (the citation column included), a row that
  does not fit its columns, and no rows at all; `ArchivedCitation` accepts a
  plain-http address — six seeded ones are — and refuses one with no scheme at
  all. Plus: a jurisdiction cited over plain http reaches a table row; a
  jurisdiction whose rows cannot be printed is NAMED to the family and takes no
  other row with it; a resolved answer no row of which can be printed renders
  the refusal page with no date. Plus: a table's PUBLISHED cells are not swept
  as unicoach prose while its caption, its headers and its authored cells are; a
  table renders outside a `<p>`.
- **Service**: `missingJurisdictions` carries name, region and the agency for
  all four; a jurisdiction holding rows and an unreadable entry is SERVED and no
  longer named as a gap; a gap carries the corpus the service was GIVEN; the
  coach tool's payload is unchanged by the `FafsaDocument` refactor.

## Risks and open items

- **The sibling run** (§3). Expect one rebase; the report names it.
- **The four agency urls** are read-blocked by design — that is why they have no
  deadline. `bin/check-link` may answer `unknown` for them; the page then names
  the agency without a link, which is the honest rendering of "we tried".
- **Page length.** 30 rules plus ~100 table rows is a long page. Regional tables
  and a short contents list keep it scannable; if review disagrees, the fix is
  presentation, not content.
