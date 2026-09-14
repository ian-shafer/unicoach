# DISCOVER — the state deadline dataset (merged), 2026-09-14

Three harvest agents covered all **51 jurisdictions** (50 states + DC). Merged
file: `state-deadlines-merged.csv` (148 rows). Per-block reports and their
Method/Gaps/Hazards sections: `state-deadlines-a|b|c.md`.

## Shape of what came back

| Measure                                     | Value              |
| ------------------------------------------- | ------------------ |
| Jurisdictions covered                       | 51 of 51           |
| Deadline rows                               | 148                |
| Rows verified against a fetched source page | 127                |
| Rows UNVERIFIED                             | 21                 |
| Jurisdictions with NO verified row          | 4 — AR, DE, NC, NH |
| Rows sourced from the state agency itself   | 137                |
| Rows from a Wayback copy of the agency page | 7                  |
| Rows from a secondary aggregator            | 4                  |
| 2026-27 rows / jurisdictions                | 119 / 51           |
| 2027-28 rows / jurisdictions                | 28 / 18            |

Deadline kinds: **89 a real date**, **48 a rule in words** (first-come, until
funds run out, set by your college), **8 no state deadline at all**, **3 we
could not establish**.

## Four findings that bind the spec

1. **A per-state cell cannot be one date.** Many states carry several rows:
   Pennsylvania's answer depends on the school type (1 May at four-year and
   private colleges, 1 August at community/trade/nursing schools) — showing one
   would either panic a community-college student or cost a university student
   the grant. New Jersey has three dates plus a separate state step, and judges
   the PROCESSED date, not the filed date. California needs a GPA verification
   form as well as the FAFSA.

2. **A state deadline is often not a FAFSA deadline.** Tennessee, West Virginia,
   Virginia, Oklahoma and Utah all require a SEPARATE state application in
   addition to the FAFSA; Mississippi's dates are its own programme; DC's is its
   own application; Florida's widely-copied April date belongs to a different
   form. A table that shows one date per state makes families miss the other
   half of the work.

3. **"No date" is not "no hurry", and a blank cell lies.** Oklahoma, Oregon,
   Vermont and Wisconsin award until funds run out — Oklahoma says plainly that
   filing early "may be the deciding factor". These must render as
   first-come-first-served in words. Separately, Virginia, Wisconsin, South
   Carolina, South Dakota and Wyoming have NO state date because the college
   sets it, and the spread is months (Virginia: 1 Feb to 15 Jul).

4. **The sources we would otherwise have trusted are wrong.** A widely-used
   third-party deadline table conflicts with the agencies themselves in FL, DC,
   IN, KS and LA. Live STATE pages are stale too: Oregon and Tennessee still
   tell students to file "as soon as possible after October 1", which has not
   been the FAFSA open date since 2023-24; North Dakota's own page misstates it.
   Massachusetts is 30 June, not the 1 May widely printed. Michigan's July 1
   belongs to two CLOSED programmes. Illinois' alternative-application route for
   undocumented students was struck down 24 July 2026 and closed 30 July 2026.
   Oregon Promise is closed right now and reopens October 2026.

## Consequences for the table's design (input to gate 2)

- The kind vocabulary must be **four** values, not three: `date`, `wording`,
  `none`, **`unknown`**. Three rows arrived as "we could not establish this",
  and an empty wording field silently became a blank cell — exactly the lie
  finding 3 describes. `unknown` must render as our own gap, in our own words.
- **Several rows per state is the normal case**, so the natural key needs the
  programme, not just the state and year.
- Each row needs the **programme name**, whether a **separate state
  application** is also required, and **who sets the date** (state or college).
- Provenance must record the **tier** (agency page / archived copy / secondary)
  and whether a human verified it. An unverified row may not be published as a
  fact; 21 rows and 4 whole jurisdictions are in that state today.
- Several agency sites cannot be fetched by machine at all (Virginia, New York,
  Minnesota, Pennsylvania, Delaware, Arkansas). A yearly refresh therefore has a
  **manual step by construction** — the seed's provenance must say which rows
  needed human eyes.
