# Non-cost tool payloads — measured size and repeated boilerplate

Scope: `search_colleges`, `similar_colleges`, `find_college`,
`college_admissions_profile`, `federal_aid_policy`.

**Measurement honesty.** No test asserts on a _serialized_ payload string, so no
byte count below is read off a golden file. All payload byte counts are
**RECONSTRUCTED**: I rebuilt each payload in Python from the exact serializer
code (key order, key names, literal sentences) plus real seed/test values, then
serialized compactly — what kotlinx `JsonObject.toString()` emits. Every quoted
_string literal_ is real and cited. Row counts and field values for
search/similar/admissions are plausible, not sampled from production.

## 1. Where the tools live

| tool | chat adapter | payload builder | |---|---|---|| `search_colleges` |
`coaching/CollegeChatTool.kt:28` | `college/CollegeSearchTool.kt:116-133` | |
`similar_colleges` | `coaching/SimilarCollegesChatTool.kt:26` |
`college/SimilarCollegesTool.kt:743-778` | | `find_college` |
`coaching/FindCollegeChatTool.kt:10` | `college/FindCollegeTool.kt:149-168` | |
`college_admissions_profile` | — |
`coaching/admissions/CollegeAdmissionsChatTool.kt:83-91` | |
`federal_aid_policy` | — | `coaching/aid/FederalAidPolicyChatTool.kt:62-69` |

Shared row renderer: `college/.../CollegeMatchRow.kt:132-191` (`matchObject`) —
`search_colleges` and `similar_colleges` return the _same_ row (RFC 153 D70).

## 2. JSON shapes

### `search_colleges` (CollegeSearchTool.kt:116-133)

```
{ colleges:[ <matchObject> ], count, total_matches,
  excluded_unknown:{<axis>:<int>}, price_ruler:{metric,max_filter_field,note},
  source_years:{<source>:<year>|{earliest,latest}} }
```

`matchObject` row keys, in order (CollegeMatchRow.kt:141-190):
`college_id, name,
city, state, control, region, locale_type, locale_detail,
undergrad_enrollment_headcount, admission_rate_share, in_state_net_price_per_year_usd,
[published_price_<tier>_on_campus_per_year_usd], [residency_tiers{basis,statement}],
net_price_by_income_band[{income_band, income_band_label,
in_state_net_price_per_year_usd}], completion_rate_150pct_4yr_share,
median_earnings_10y_after_entry_usd, median_debt_at_completion_usd, pell_share,
[programs[]]`.

### `similar_colleges` (SimilarCollegesTool.kt:743-778; row extras :918-924)

```
{ anchor{college_id,name,state,control}, axes_used[{axis,weight}],
  axes_dropped[{axis,reason}], constraints_used[<sentence>],
  colleges[ <matchObject> + distance + axes_scored[] ],
  total_candidates, excluded_unknown, price_ruler, source_years }
```

### `find_college` (FindCollegeTool.kt:149-168)

`{ colleges:[{college_id,name,city,state}], count }` — the lean one.

### `college_admissions_profile` (CollegeAdmissionsChatTool.kt:83-113)

```
{ colleges:[{ college_id,name,city,state,list_status,
   merit_aid{4 numbers, share_label, average_label, source{cited_as,url,archive_url}},
   admission_factors{factors[{factor,factor_label,importance}], source{...}},
   deadlines{rounds[{round,round_label,offered,offered_label,application_deadline,
                     decision_notification}], source{...}},
   data_availability[] }], count, [unknown_college_ids[]] }
```

### `federal_aid_policy` (FederalAidPolicyChatTool.kt:62-158)

```
{ current_award_year, pell{award_year,award_year_status,[award_year_statement],
   max_award_usd,min_award_usd,sai_floor,max_pell_agi_tests{2 pcts,statement},
   framing_statement,sources[{cited_as,url}]},
  undergrad_loans{...,dependent{annual[3],aggregate_*},independent{...},
   loan_framing_statement,sources[]},
  grad_loans{...,loan_framing_statement,sources[]},
  money_profile{dependency_status,[dependency]}, source_note }
```

## 3. Reconstructed payload sizes (compact JSON bytes)

| payload                                    | bytes                              | ~tokens (÷4) |
| ------------------------------------------ | ---------------------------------- | ------------ |
| `search_colleges`, 10 rows, net ruler      | **11,159**                         | ~2,790       |
| one search row                             | **1,057**                          | ~264         |
| `similar_colleges`, 5 rows (default limit) | **6,897**                          | ~1,720       |
| `find_college`, 5 rows                     | **694**                            | ~170         |
| `search_colleges`, 20 rows                 | **21,739**                         | ~5,430       |
| `college_admissions_profile`               | **4,151** /college, 6 = **24,936** | ~6,230       |
| `federal_aid_policy` (real seed values)    | **3,307**                          | ~830         |

`federal_aid_policy` is closest to measured: every number comes from
`db/schema/0077.create-and-seed-policy-parameters.sql:97-185`, pinned by
`FederalAidPolicyChatToolTest.kt:60-104`.

## 4. Repeated boilerplate — the concrete number

**Search / similar rows.** Per row, byte-identical row to row: JSON key names
(16 top-level keys) **319 B**; `net_price_by_income_band` inner key names (5
bands × 3 keys) **340 B**; the band code + label literals themselves
(`"under_30k"`, `"$0 to $30,000"` … `IncomeBand.kt:24-35`, emitted by
`putIncomeBand`, `IncomeBand.kt:94-97`) **153 B**.

**Total identical per row: 812 B = 77 % of a 1,057 B row.**

- 10-row search: **8,120 B repeated** of 11,159 B (73 %) → ~2,030 wasted tokens.
- 20-row search: **16,240 B repeated** of 21,739 B (75 %).

`similar_colleges` adds `"axes_scored":["size","selectivity","setting"],` = **47
B/row identical** (`SimilarCollegesTool.kt:921-923`), and a fixed header of
**~1,246 B** of prose (`axes_used`, `axes_dropped` reason sentences,
`constraints_used`, `price_ruler.note`).

**Page-level hedge prose, once per call (~565 B of the search page):**
`price_ruler.note` is **262 B** on the net ruler (`CollegeMatchRow.kt:74-76`) or
**452 B** on the published ruler (`CollegeMatchRow.kt:80-85`) — "This search is
ranked on the average annual NET price -- what students actually paid after
federal aid…".

**Admissions.** Per college: 3 identical citation objects (`putCitation`,
`CdsCitation.kt:51-55`) **840 B**; the 18-row C7 factor grid (`factor` +
`factor_label` + `importance`) **1,720 B**; `share_label` + `average_label`
(`MeritAidWire.kt:75,84-86`) ~215 B. A 6-college read repeats the ~4.1 KB shape
six times = **24.9 KB**.

**Federal aid.** Fixed coach prose that is the same on every call:
`PELL_FRAMING_STATEMENT` + 2 × `LOAN_FRAMING_STATEMENT` + `SOURCE_NOTE` + 2 ×
prior-year statement + the AGI statement = **1,018 B**, plus **866 B** of
citations — i.e. **~57 % of the 3.3 KB payload is boilerplate**
(`FederalAidPolicyChatTool.kt:208-245`).

## 5. Tool definitions (in context on _every_ turn, not just after a call)

Description bytes, approximate (literal chunks summed; interpolations ±5 %):
`search_colleges` **~3,800** (2,982 prose + the 727 B `NET_PRICE_BASIS_NOTE`,
`CollegeMatchRow.kt:45-54`); `similar_colleges` **~2,640** (1,853 + the same 727
B note); `federal_aid_policy` ~1,444; `college_admissions_profile` ~1,149;
`find_college` ~784.

The `search_colleges`/`similar_colleges` `input_schema` also carries the shared
filter vocabulary prose: **~3,763 B** of literal description text in
`CollegeQueryVocabulary.kt:75-210`, counted twice (both tools embed it).

## 6. Prompt size and how much payload can pile up in one answer

- `prompts/coach-system-prompt.md`: **25,201 bytes, 4,523 words, 158 lines** →
  **~6,300 tokens** at 4 B/token.
- `coaching.maxToolRounds = 8` (`service/src/main/resources/service.conf:193`).
- The loop (`CoachingService.kt:533,575`) allows 8 rounds, then one forced
  no-tools call. `dispatchTools` (`:573`) runs **every** `tool_use` block in a
  round — so 8 is a floor on payload count, not a cap.
- Worst plausible answer: 8 rounds × one 10-row `search_colleges` = **~89 KB ≈
  22,000 tokens** of tool JSON, **~65 KB of it identical repeated key/label
  bytes**, on top of ~6,300 prompt tokens. Mixed rounds (2 searches + 1 similar
  - 1 six-college admissions + 1 federal aid) already reach **~57 KB ≈ 14,000
    tokens**.

## 7. Reading

The redundancy is structural, not accidental: RFC 142 ("leave no vacuum") and
RFC 151 D-D require every code to travel with its sentence, so a label rides
every band of every row. Cheap at 1 row; 812 B × N at N rows. Candidate levers:
(a) hoist `income_band_label` to a page-level legend, (b) drop `axes_scored`
when it equals `axes_used`, (c) one citation per document per _payload_ rather
than per section per college, (d) lower the default `limit` of 10
(`CollegeSearchTool.kt:191`).
