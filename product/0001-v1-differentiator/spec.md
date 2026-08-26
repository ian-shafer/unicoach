# Brief 0001 — SPEC & SLICE (gate 2 draft)

Gate-1 decision (Ian, recorded): report-as-wedge. Beat 1 = student-side cost
truth ending in a shareable Family Cost Report, with an explicit
invite-your-parent mechanic. Beat 2 = parent partner accounts landing on the
report (specced after Beat 1 ships).

Grounding checked against the repo: `colleges` carries only overall net_price
(NPT4 coalesced at load, 0015/0022); NO income-band prices, NO debt fields, NO
CDS data. RFC 91 college list, RFC 93 commitments, RFC 94 tool loop all landed.

## Beat 1 slices — each one /ship instruction, in order

S1. Income-band net price + debt in the Scorecard ingest (lane A, small) Add
NPT41..NPT45 (pub/priv coalesced per control, mirroring the NPT4 pattern) and
median-debt fields to `colleges` + loader + CollegeSearchTool output. AC:
re-ingest populates bands for schools that report them; tool responses include
them.

S2. Student money profile (lane A) A durable, student-correctable place for
family income band (+ residency state) so the right NPT4 band can be chosen.
Mechanism (coaching-memory claim vs. dedicated entity) is an RFC design
decision, not pre-decided here.

    NEVER FORCED (Ian, gate 2): the profile is guided, not gated. The coach
    invites; the student can start, stop mid-way, continue, skip and restart
    later — and the UI/UX must make that state legible (what's answered,
    what's skippable, how to resume). Every cost surface degrades gracefully:
    no profile -> overall net price, labeled as such; partial profile -> best
    answer from what exists. No cost feature is locked behind completion.
    AC: profile can be started/abandoned/resumed via chat across sessions;
    partial and empty states produce labeled answers in S3.

S3. "Know your real price" in chat (lane A) A cost tool over the student's
college list + money profile: per school, sticker vs. likely net price for their
band, debt/earnings context, every number cited to its source-year. Coach prompt
updated to make this a first-session moment. AC: a new student with 3 listed
schools gets a cited cost answer in session one.

S4. Admissions Intelligence Layer v0 (lane A, largest — may split in design) New
reference tables for CDS-derived facts: H2A merit-aid practice (% of no-need
freshmen receiving merit, avg award), C7 admissions factors, deadlines by round.
Seed ingest from the collegedata.fyi corpus for the launch set (~300-500 schools
by student-list popularity). Exposed as a cited LLM tool; merit-aid feeds S3's
answers ("X% of freshmen without need got merit here, avg $Y"). NO
net-price-calculator automation. AC: launch-set coverage report; cited merit
answers in chat.

S5. Family Cost Report (lane A) Student-initiated shareable artifact: tokenized
public-web page rendering the per-school cost table (sticker, likely net, merit
practice, debt context) for the student's list. Revocable token; no parent auth
yet. AC: student triggers share from chat/iOS, link renders without login,
student can revoke.

S6. Invite-your-parent mechanic (lane B/A, small) The wedge: share CTA on the
report surface + a synthesis commitment trigger (RFC 93) so the coach nudges
sharing at the right moment; share events tracked. The token is designed to
later become the parent-account claim path (Beat 2). AC: nudge fires for
eligible students; share event recorded.

## Beat 2 (not sliced yet)

Parent partner accounts: claim-the-report onboarding, linked family, parent-
side coaching seeded by the report. Specced in a follow-on brief section after
Beat 1 lands and share-rate is observed.

## Gate 2 decisions (defaults)

- D5. Slice order S1-S6 as above; S1 can start immediately. DEFAULT: yes
- D6. Money-profile mechanism decided inside S2's RFC, not here. DEFAULT: yes
- D7. Report = revocable tokenized public-web page (no PDF in v1). DEFAULT: yes
- D8. Launch data set = ~300-500 schools ranked by college-list popularity +
  national popularity. DEFAULT: yes
- D9. Beat 2 specced only after Beat 1 ships. DEFAULT: yes
- D10. (Ian, standing rule) Any NEW database table — schema, columns,
  constraints, versioning choice — is approved by Ian personally. The /ship
  approval gate for a slice that adds tables must present the proposed DDL
  explicitly, not bury it in the RFC. Applies to S2, S4, S5/S6 (share
  tokens/events) here, and to all future work. DECIDED.
- D11. (Ian, gate 2) Money-profile UX is guided/resumable, never forced — see
  S2. DECIDED.
- D12. (Ian, standing ethos — applies to EVERY slice and all future work) Value
  before ask: never force a user through a step whose value they don't yet
  understand. Invite, allow start/stop/resume/later, degrade gracefully on
  decline, never gate on completion. The money profile is the pattern, not the
  exception (cf. chat-before-subscription). ENFORCEMENT LIVES IN THE PRODUCT
  LAYER, NOT /ship: the ethos is applied at SPEC & SLICE, where each slice's
  instruction and acceptance criteria are written so the ethos is already
  embodied in what /ship is asked to build. /ship's job is production-quality
  implementation of the instruction; it is not asked to reason about product
  value. DECIDED.
