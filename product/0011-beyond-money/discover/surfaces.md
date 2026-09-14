# 0011 DISCOVER — the three surfaces the field must reach

Scope: D12, D18, D19/D20, D21, D22 and the brief-0009 collision. Verified
2026-09-10 against the working tree, read-only. Every claim below cites a file
and lines that were opened. Four workers: the cost report + 0009 collision, the
share path, the search index + `similar_colleges`, the RFC 169 offer pattern.

## Corrections

Twelve. Ordered by how much damage they do to a slice written against the brief.

**C1 — D21's tri-state is on the WRONG COLUMN. `cip_codes` has no `{}` state.**
D21 says `{}` on `cip_codes` means "known, and none of them is that subject".
False. `db/schema/0064.create-search-index-and-subjects.sql:222-225` says of
`cip_codes` that "the empty array cannot occur today": the rebuild's `array_agg`
over zero census rows returns NULL
(`db/src/main/kotlin/ed/unicoach/db/dao/CollegesDao.kt:3012-3016`). The judged
**no** `{}` exists only on `subject_slugs` (`0064...sql:227-230`, written by the
guarded `CASE` at `CollegesDao.kt:2995`). Correct statement: `cip_codes` is
NULL-or-non-empty; `subject_slugs` carries all three states.

**C2 — D22 understates the code: a weighted subjects axis ALREADY EXISTS.**
`SimilarityAxis.SUBJECTS` (`db/.../models/SimilarityQuery.kt:36-37`), Jaccard
over `subject_slugs` (`CollegesDao.kt:1379-1394`), caller-weightable
(`SimilarityQuery.kt:49-54`), deliberately absent from `DEFAULTS` (`:47` =
SIZE/SELECTIVITY/SETTING). D22 must therefore read "never feed the STUDENT's
stored field into that existing axis, and keep SUBJECTS out of `DEFAULTS`" — not
"do not build a subject weight".

**C3 — "Nothing reads them today" is stale.** True only of "against a student".
Search already filters both columns: `subject_slugs @> ARRAY[?]::slug[]`
(`CollegesDao.kt:747-752`) and `cip_codes && $TEXT_ARRAY_PARAM` (`:753-761`);
`similar_colleges` already ranks on `subject_slugs`.

**C4 — D18 assumes a consent precedent that does not exist.** Grep for
`consent|acknowledg|disclosure` across `db/`, `service/`, `public-web/`,
`rest-server/` finds only `commitments.disclosure`
(`db/schema/0025.create-synthesis.sql:39,52,70`), an `explicit|internal` flag
for whether the COACH said a commitment aloud. D18 would be the FIRST consent
surface in the repo. Budget for it accordingly.

**C5 — `stop_cost_report_offers` is not the precedent D18 wants.** It is not a
preference column: it writes one append-only `share_events` row of kind
`opted_out` (`db/schema/0081.create-share-events.sql:20-24,36-42`) and
suppresses only the synthesis NUDGE (`SynthesisService.kt:252`). It revokes no
link and disables no tool (`StopCostReportOffersChatTool.kt:13-19,89-96`), and
it is permanent with no undo by design (`:17-19`). A fields-on-report permission
must be reversible, so it is not a second `opted_out` kind.

**C6 — there is NO per-share snapshot; the report is always LIVE.** One DB
session resolves the token and reads the costs
(`public-web/.../report/ServiceCostReportSource.kt:20-33,71-83`), and both
surfaces say so (`render/CostReportPage.kt:88`;
`ShareCostReportChatTool.kt:128-129`). A stored field appears on EVERY
already-minted link the instant it is stored. D18's "before the first share"
understates the risk: the dangerous case is a parent re-opening an OLD link.

**C7 — D15's "serve it through `GET /api/v1/vocabularies`, exactly as income
band and residency are served" contradicts RFC 165.**
`rfc/165-served-vocabularies.md:102-112` admits a vocabulary only when it is
closed, small (tens of entries), user-independent and display-facing, and `:111`
"excludes the subjects taxonomy and the college catalog, which are large or
open". `db/data/subjects.json` holds 181 entries; a 4-digit CIP title list is
larger. Serving the picker there needs a deliberate widening of RFC 165, or its
own route.

**C8 — D16's "extend the `money_profiles` tri-state with a fourth value" does
not transfer as written.** The tri-state is a per-field PAIR of columns on one
active profile row, bound by a CHECK that the value is present iff the status is
`answered` (`db/schema/0046.create-money-profiles.sql:33-39,44-45,52-55`). A
capped SET has no single value column, so that CHECK has no counterpart.
`undecided` is a STATUS value on a parent row plus a child table — which is what
the brief's own DDL note expects, but D16's sentence implies a drop-in enum
member.

**C9 — nothing renders earnings on the cost report today, so D12 "weaves into
existing paragraphs" is only half true.**
`CollegeCost.medianEarnings10yAfterEntryUsd` exists
(`service/.../costs/CollegeCostService.kt:315`, filled `:1362`) and is emitted
in the CHAT payload (`CollegeCostChatTool.kt:447`), but `CostReportPage.kt`
never reads it (grep: no hit in `public-web/src/main`). There IS an existing
debt paragraph to weave a field debt figure into
(`CostReportPage.debtBlock:981-1000`); there is NO earnings paragraph at all.
The earnings half is new prose on the page, whatever D12 calls it.

**C10 — the page authors no money copy, so D12 cannot be a `public-web` slice.**
`CostReportPage.kt:74-79`: "The SENTENCES are the cost domain's own
(`ComparisonBasis`, `SingleSchoolBasis`, `MoneyBasis`) — this file authors no
money copy, so a parent can never be shown a caveat the coach would have worded
differently." The page renders exactly one input, `CollegeCostProfile`
(`:245-271`), delivered through the narrow `CostReportSource` port
(`public-web/.../report/CostReportSource.kt:22-24,48-59`) and assembled in
`service` (`CollegeCostService.kt:1192-1199`). A woven field figure and its
sentence must therefore land in `coaching/costs`, and it reaches chat and the
report together.

**C11 — brief 0009 has landed NOTHING, and its gate 2 is still open.**
`product/0009-say-it-once/brief.md:6-16`: "Phase: SPEC & SLICE — gate 2
presented 2026-09-09, awaiting Ian", "Ledger: (empty — nothing landed)". The
CONFLICTS edge is real but it is with UNAPPROVED slice text, not with landed
code. Either brief may reach `CostReportPage.kt` first.

**C12 — small cite drift in the brief's "Foundations confirmed" block.**
`0064...sql:169-177` is the comment block; the two column declarations are
`:178-179` and the `COMMENT ON COLUMN` text is `:222-230`.
`subjects.cip_prefixes` at `:48-63` is correct. (0009's own
`CostReportPage.kt:1081-1130` also overshoots a 1,127-line file.)

## Verified foundations

### 1. The Family Cost Report page — structure and the attach point

| Thing                        | Where                                                                                                                                                                                    |
| ---------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Page entry                   | `public-web/.../render/CostReportPage.kt:245-271` `respondCostReportPage(profile)`                                                                                                       |
| Page order                   | `section("report")` → `h1` → `LIVE_LINE` `:251` → `assumptions` `:252` → `summaryTable` `:260` → per-school `schoolSection` `:261` → `sourcesSection` `:267`                             |
| Per-college block            | `schoolSection:498-516`: `h2` name `:503`, city/state `:508`, residency sentence `:509`, `tuitionTierLine:510`, `priceTable:511`, blended hint `:512`, `meritBlock:513`, `debtBlock:514` |
| The money-outcome paragraphs | `debtBlock:981-1000` (federal debt, labelled blank at `:988-994`), `shownDebtSentence:1012-1026`, `borrowingParagraphs:1037-1058` (CDS, per-loan-type)                                   |
| Closing block                | `sourcesSection:1081-1112`, `moneyAttributionSentence:1126-1127`                                                                                                                         |
| Rules stated in code         | `:62-72` — no student identity (D-F), no upgrade cue (D-G), a missing part is a labelled blank                                                                                           |
| Copy ownership               | `:74-79` — the page authors no money copy                                                                                                                                                |
| Route + headers              | `public-web/.../Routing.kt:202-232`; `no-store`, `X-Robots-Tag`, `no-referrer`, byte-identical 404                                                                                       |
| Domain input                 | `CollegeCost` `service/.../costs/CollegeCostService.kt:244-330`; `CollegeCostProfile` `:858-900`; built `:1192-1199`                                                                     |

**Where a woven field-of-study figure attaches without a new section (D12):**
one new nullable field on `CollegeCost` (beside `medianDebtAtCompletionUsd`
`:314` and `medianEarnings10yAfterEntryUsd` `:315`), its sentence composed in
`coaching/costs` next to `AssuranceTierCopy`/`BorrowingWire`, and rendered as an
extra `p` inside `debtBlock` (`:981-1000`) plus one new paragraph in the same
`section("report-school")` for the earnings half. Nothing else in the page
changes; `sourcesSection` already derives publishers from the profile
(`:1081-1090`), so a new publisher must arrive as a `MoneySource`, not as typed
prose.

**RFC 155 rules that constrain it**, quoted: D-F "No name, no email, no user id
— the link is not proof of who sent it, so the page must not assert it"
(`rfc/155-family-cost-report.md:165-170`); D-G "a page that asks them to would
be selling, not informing … the page therefore suppresses the offer copy and
keeps only its honest half" (`:181-187`, enforced `CostReportPage.kt:66-69`);
labelled blanks never zeros or a neighbour's number (`:449-450,457-458`);
byte-identical 404 (`:387-392`). D20's citation of the upgrade-cue ban is
accurate.

### 2. The brief-0009 collision — the EXACT paragraphs both briefs rewrite

| 0009 slice                                                  | Paragraphs it rewrites                                                                                                                                                                                       | Collides with 0011                                                                                                                                            |
| ----------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `concise/01/the-page-says-it-once` (`spec.md:109-172`)      | hoists tuition + books rows, hoists `SCHOOL_BLENDED_HINT` (`:228-231`) to page level, DELETES the per-school residency sentence `CostReportPage.kt:509` on one-school reports, moves the `BandSpecific` note | Same `section("report-school")` body D12 weaves into; adds a **rendered-word-count test** (≤1,480) that any new woven sentence will fail                      |
| `concise/04/cite-once-per-block` (`spec.md:283-348`)        | **`borrowingParagraphs` (`:1037`)**, **`meritBlock` (`:948`)**, `sourcesSection` (`:1081`), `BorrowingWire.kt:280-425` — one attribution header per block                                                    | Directly: a field-of-study debt/earnings figure with a NEW publisher lands inside a block that is being given a single attribution header; word budget ≤1,230 |
| `concise/05/tiers-grouped-not-repeated` (`spec.md:350-396`) | groups `AssuranceTierCopy` sentences by tier on both surfaces                                                                                                                                                | A woven figure needs a tier sentence and must join a GROUP, not print its own; budget ≤1,170                                                                  |
| `concise/07/quiet-provenance` (`spec.md:456-495`)           | wraps `assumptions` (`:311`) and `sourcesSection` (`:1081`) in new `<details>` chrome                                                                                                                        | D19's once-stated coverage limit may end up inside a collapsed block                                                                                          |

The load-bearing conflict is **`concise/04` + D12**: both rewrite
`borrowingParagraphs`/`meritBlock` attribution. Declare CONFLICTS on `01`, `04`,
`05`, `07`, and note the word-count tests explicitly — they are the mechanism
that will fail an 0011 slice that rebases blind.

### 3. The share path and the D18 seam

| Thing                    | Where                                                                                                                                                                                                            |
| ------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Credential               | `db/schema/0073.create-cost-report-shares.sql:15-31` — `(id uuidv7, student_id FK, token_hash BYTEA, revoked_at NULL)`; token derived `HMAC-SHA256(secret,id)`, row keeps `SHA-256(token)`; no expiry, multi-use |
| One live share           | partial unique `cost_report_shares_one_live_per_student_idx ... WHERE revoked_at IS NULL` (`:28-31`)                                                                                                             |
| Event log                | `db/schema/0081.create-share-events.sql:30-51` — `kind IN ('minted','repeat','reissued','revoked','opted_out')`, paired CHECK, append-only triggers `:48-51`; enum `db/.../models/ShareEventKind.kt:16-34`       |
| Service seam             | `service/.../report/CostReportShareService.kt` — `share():176-195`, `mintOrReuse():283-293`, `mintLink():351-388`, `reuseLink():301-308`, `revoke():205-218`, `stopOffers():229-265`                             |
| DAOs                     | `CostReportSharesDao.findLiveByTokenHash:93`, `revokeLive:112-130`; `ShareEventsDao.has:104-117`, `hasOptOut:123-129`                                                                                            |
| Doors                    | three no-argument chat tools only: `ShareCostReportChatTool`, `RevokeCostReportShareChatTool`, `StopCostReportOffersChatTool:36-65`. No REST or iOS mint path.                                                   |
| "First share ever" probe | `ShareEventsDao.has(session, studentId, MINTED)` before the mint, in the same txn — needed because `MINTED` fires again after a student-driven revoke (`ShareEventKind.kt:22`)                                   |

### 4. The index: `cip_codes` / `subject_slugs`

| Fact                                                                                              | Where                                                                                                                                                                                                                                                               |
| ------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `cip_codes TEXT[] NULL`, `subject_slugs slug[] NULL`, GIN on both                                 | `0064...sql:178-179`, `:254-257`                                                                                                                                                                                                                                    |
| Stored codes are **6-digit everywhere**; no 4-digit form is stored at all                         | `0064...sql:171-173,222-225`; census CHECK `0055...sql:95,101-102`; codebook CHECK `0060...sql:322-329`                                                                                                                                                             |
| Rebuild                                                                                           | `CollegesDao.rebuildSearchIndex:2892` → `insertIndexRows:2924-3027` → `rankPercentiles:3106-3155`                                                                                                                                                                   |
| `cip_codes` = LATERAL `array_agg(DISTINCT pc.cip_code)` over `college_programs_census`            | `CollegesDao.kt:3012-3016`                                                                                                                                                                                                                                          |
| `subject_slugs` = LATERAL prefix match `pc.cip_code LIKE p \|\| '%'` over `subjects.cip_prefixes` | `:3017-3026`; NULL/`{}` discipline `:2985-2995`                                                                                                                                                                                                                     |
| Prefix matching happens at BOTH times                                                             | rebuild `:3017-3026`; query-time `expandCipPrefix:1806-1830`, `expandSubject:1836-1866`, both `LIKE ? \|\| '%'` over `cip_codes` — so the 4→6 digit widening mechanism already exists on the query side                                                             |
| NULL vs `{}` SURVIVES the read path                                                               | anchor read `getStringListOrNull` (`:1179,:1675,:161-169`); candidate predicate rejects `{}` (`:1383`); two different refusal sentences (`SimilarityQuery.kt:140-146`, `SimilarCollegesTool.kt:709-718`); the only `coalesce` is rebuild-side and guarded (`:2995`) |
| Trap                                                                                              | `getStringList` (`:153`) DOES collapse NULL → empty list. A new reader must use `getStringListOrNull`.                                                                                                                                                              |
| Neither column reaches a wire model                                                               | `db/.../models/CollegeMatch.kt:8-12`; only `programTitles` (`college/.../CollegeMatchRow.kt:183-190`)                                                                                                                                                               |
| A taxonomy change requires a rebuild                                                              | `0064...sql:167-171`                                                                                                                                                                                                                                                |

### 5. `similar_colleges` (RFC 153) and the label seam

Distance is a weighted mean built as text in `createDistance`
(`CollegesDao.kt:1291-1306`):
`(Σ CASE WHEN <scored> THEN w*<diff> ELSE 0 END) / nullif(Σ CASE WHEN <scored> THEN w ELSE 0 END, 0)`.
Axis inputs in `createDistanceTerm` (`:1325-1396`): SIZE
`undergrad_enrollment_percentile_share`; PRICE the active ruler's share column
(`PriceRulerSql.kt:29`); SELECTIVITY the mean of inverted
`admission_rate_percentile_share` and `sat_average_percentile_share`
(`:1689,:1692`); SETTING `locale` equality; SUBJECTS Jaccard over
`subject_slugs`. An unmeasurable axis adds 0 to both sums. The ranked page is
`listSimilarMatches:1429-1516`; row type
`SimilarityMatch(match, distance,
axesScored)`
(`db/.../models/CollegeSimilarityOutcome.kt:61-65`), serialized by
`SimilarCollegesTool.similarObject:914-924`.

**The "teaches your field: yes/no/unknown" label attaches with ZERO ranking
change**: add `i.cip_codes` (or a boolean over it) to the INNER select list of
`listSimilarMatches` (`:1455-1470`), or a LATERAL beside the existing
program-titles one (`:1475-1482`); map a third field onto `SimilarityMatch`
(`CollegeSimilarityOutcome.kt:61-65`) at `:1498-1512`; emit it in
`similarObject` (`:914-924`). `createDistance`, `createDistanceTerm`,
`sharedAxisClause` and both `ORDER BY`s stay untouched. Label rule: **yes** =
overlap; **no** = `cip_codes IS NOT NULL` with no overlap; **unknown** =
`cip_codes IS NULL`. Confirmed: `similar_colleges` is the only production reader
of the percentile columns, with the one nuance that `PriceRulerSql.kt:29` names
`net_price_percentile_share` for the price axis.

### 6. The RFC 169 offer pattern D21 reuses

| Piece                  | Where                                                                                                                                                                                                                                                                                                                                                                                                    |
| ---------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Offer carrier          | `service/.../coaching/ResidencyOffer.kt:200-261` — `KEY="residency_offer"` `:202`, copy `:223-227`, `UNAVAILABLE_KEY` `:239`, `noteFor(cause)` `:242-260`                                                                                                                                                                                                                                                |
| Wire shape             | `:271-282` `putOffer` writes `{"field":…,"offer":…}`; the key is OMITTED when not offerable                                                                                                                                                                                                                                                                                                              |
| Merge                  | `:293-306` `addResidencyOffer`; returns the result untouched on a tool refusal `:301`                                                                                                                                                                                                                                                                                                                    |
| State model            | `:36-185` `sealed interface Residency` = `OnFile/NotAsked/Unavailable(cause)`; `offerable` true only for `NotAsked` `:48-49,62-66`; `Cause` = `DECLINED/READ_FAILED/CORRUPT_PROFILE` `:82-100`                                                                                                                                                                                                           |
| Doors                  | `ResidencyScopedChatTool.kt:31-51`; `CollegeChatTool.kt:14-15`, `SimilarCollegesChatTool.kt:18` both extend it; no residency INPUT field on either schema                                                                                                                                                                                                                                                |
| Never gates            | the search runs first, unconditionally (`ResidencyScopedChatTool.kt:44-50`); `addResidencyOffer` only ADDS a key (`:302-305`); residency's only other effect is which RULER is used (`CollegeSearchTool.kt:95`, `SimilarCollegesTool.kt:141`, `PriceRuler.kt:168`) — a ruler picks a column, it is not a predicate. Pinned by `CollegeChatToolTest.kt:119-150` and `SimilarCollegesChatToolTest.kt:149`. |
| Decline remembered     | `AnswerStatus` (`db/.../models/AnswerStatus.kt:11-21`) on `money_profiles`; offer keyed on `UNANSWERED` (`ResidencyOffer.kt:132-138`, `CollegeCostService.kt:971`); `noteFor(DECLINED)` returns null on purpose (`:242-249`)                                                                                                                                                                             |
| Write path             | `MoneyProfileChatTool.kt:51-89,203-262` — a `<field>` value key plus a literal-true `<field>_declined` key; both in one call is refused `:230-232`                                                                                                                                                                                                                                                       |
| Status columns         | `0046.create-money-profiles.sql:33-39,44-45,52-55`; added the same way by `0070.add-money-profile-living-plan.sql:21-31` and `0078.add-money-profile-dependency-status.sql:30-34`; row type `MoneyProfile.kt:21-31`                                                                                                                                                                                      |
| `/api/v1/vocabularies` | route `rest-server/.../routing/VocabularyRoutes.kt:35-68` (session required, no money gate `:25-29`); domain `service/.../vocabulary/VocabularyService.kt:20-51,66-80`; registry `:107-111` holds exactly two suppliers today (`incomeBands():183-187`, `residencyStates():190-193`); adding one is ONE line, and any supplier fault fails the whole document `:113-139`                                 |

Note the reuse cost: `ResidencyOffer` is `internal` and residency-specific
(`:200-306`) and `ResidencyScopedChatTool` hardcodes one read and one merge
(`:31-51`). "Reuse the pattern exactly" means clone the shape or generalise the
base — the RFC must say which.

## Open questions for the spec

| #   | Question the code does not settle                           | Recommendation                                                                                                                                              | Why                                                                                                               |
| --- | ----------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------- |
| Q1  | Which side widens 4-digit → 6-digit?                        | Widen at QUERY time via `expandCipPrefix` (`CollegesDao.kt:1806-1830`); do not add a 4-digit column to the index.                                           | The mechanism already exists and a new column forces a full rebuild.                                              |
| Q2  | Where does the D18 permission live?                         | A reversible `*_status` column on the profile row (the `money_profiles` shape), never a new `share_events` kind.                                            | `share_events` is trigger-enforced append-only (`0081:48-51`); a log cannot express a CURRENT permission.         |
| Q3  | What triggers the D18 telling?                              | Gate on `ShareEventsDao.has(…, MINTED)` **plus** a live-share probe, not on `mintLink()`.                                                                   | The report is live (C6): a student who shared months ago never re-enters `mintLink()`.                            |
| Q4  | What does the report do while the permission is unanswered? | WITHHOLD, reusing the existing `not asked` render path.                                                                                                     | "Silence is not consent" (D18) and "renders exactly as today" (D20) are then one code path, not two branches.     |
| Q5  | Does `AnswerStatus` gain `UNDECIDED`?                       | Declare a separate `FieldAnswerStatus`, or add the member and let the compiler force each `when` to state its meaning — never an `else`.                    | Four sites switch exhaustively (`ResidencyOffer.kt:134-138`, `CollegeCostService.kt:971,:997`, the cost payload). |
| Q6  | Where does the CIP picker get served?                       | Its own searchable endpoint; state in the RFC that `/vocabularies` stays the small-bootstrap document.                                                      | RFC 165 `:102-112` excludes large/open catalogs by rule (C7).                                                     |
| Q7  | Which surface composes the woven field sentence?            | `coaching/costs`, beside `AssuranceTierCopy`/`BorrowingWire`; `CostReportPage` only renders it.                                                             | `CostReportPage.kt:74-79` forbids the page authoring money copy; this also gives chat the same sentence for free. |
| Q8  | How does D12 coexist with 0009's word-count tests?          | Declare CONFLICTS on `concise/01,04,05,07` and require whichever lands second to re-baseline the counts in its own RFC.                                     | The counts (≤1,480 / ≤1,230 / ≤1,170) are asserted tests, so they fail rather than warn.                          |
| Q9  | Does the `similar_colleges` label ship as data or prose?    | One new `SimilarityMatch` field plus one inner-select column (§5 seam); the grouping copy is the tool's.                                                    | A second query would re-read the index and could disagree with the ranked page.                                   |
| Q10 | How is the SUBJECTS axis fenced (C2)?                       | An explicit guard: `SimilarCollegesTool` may never populate `axes` from the stored profile, and SUBJECTS stays out of `DEFAULTS` (`SimilarityQuery.kt:47`). | Without it, D22's ban is a convention rather than a rule the code holds.                                          |
