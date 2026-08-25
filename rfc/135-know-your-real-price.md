# RFC 135: Know your real price

## Executive Summary

The pieces landed by RFC 133 (income-band net price + median debt on `colleges`)
and RFC 134 (the money profile, with `IncomeBand` owning the `netPriceQN`
selection) exist so a student can be told what their listed schools actually
cost their family. Nothing composes them yet: the coach has no way to read cost
facts for the student's list, and the system prompt never tells it to surface
price early.

This RFC lands the composition — the first-session "aha" of product brief 0001 —
as a student-scoped chat tool, `college_cost_profile`, plus a v3 coach system
prompt. The tool reads the student's active college list, their money profile,
and each college's cost columns, and returns one structured cost object per
school: sticker cost, applicable tuition, the family's band-specific net price
when the income band is answered — and a labeled overall average when it is not,
so the answer degrades gracefully instead of gating (the ethos, mechanically).
Every response carries its source attribution; the prompt tells the coach to
surface real price once schools are on the list, to frame the income-band ask by
its value ("I can be a lot more precise if…"), to respect a decline permanently,
and never to invent a number.

No new tables. No schema change. No iOS change (chat already renders the coach's
Markdown).

## Detailed Design

### The tool: college_cost_profile

A `StudentScopedChatTool` (RFC 134) in `:service`
(`coaching/costs/CollegeCostChatTool.kt` + a chat-free `CollegeCostService`).
Input:

    { college_ids?: [uuid] }   // optional subset; omitted = the whole active list

Behaviour:

- Reads `CollegeListEntriesDao.listActiveByStudent` (joined to `colleges`), the
  student's money profile (absent row = all-unanswered), and composes per
  college:

      name, city, state, control,
      list_status                     (considering | applying | ...),
      sticker_cost_attendance,
      tuition_in_state / tuition_out_state,
      tuition_applicable              ("in_state" | "out_of_state" | "unknown"
                                       from residency vs college state; public
                                       colleges only, private = single price),
      net_price: {
        amount,
        basis: "your_income_band" | "overall_average",   // the ethos label
        income_band                    (present when basis = your_income_band),
      },
      // The in-answer invitation (Ian, gate): when basis is overall_average
      // BECAUSE the band is unanswered, the result carries the offer to
      // upgrade the number right here — never when the band was declined.
      precision_offer                  (present only when income_band_status =
                                        unanswered: names update_money_profile
                                        and the value: "share the household
                                        income band and this becomes the
                                        family-specific price"),
      median_debt, median_earnings,
      data_availability               (explicit nulls list, so the coach says
                                       "X doesn't report this" instead of
                                       improvising)

- The band amount comes from `IncomeBand.netPriceFor(college)` — the mapping
  stays in its one home. `basis` makes the fallback self-describing: the coach
  cannot silently present an overall average as a personal number.
- A top-level `money_profile` block echoes both field statuses (answered /
  declined / unanswered) so the coach knows the history. Declined means the
  COACH never re-raises it — but the door stays open on the student's side (Ian,
  gate): `update_money_profile` accepts a new answer over declined at any time
  (RFC 134's versioned re-answer), and the prompt has the coach respond warmly
  if the student changes their mind, without the coach ever being the one to
  reopen the topic.
- Top-level `source`: "U.S. Department of Education College Scorecard" + the
  ingest recency (`colleges.updated_at` year); the prompt requires attribution
  when quoting numbers.
- Empty list is a structured result (`colleges: [], count: 0`) with the
  money-profile block intact — a valid outcome, not an error (RFC 67 precedent).
- Unknown/foreign `college_ids` entries: returned in an `unknown_college_ids`
  field; known ones still answer (best-effort read, all-or-nothing only for
  writes).

### The prompt: coach v3 (migration 0047 + service.conf pin)

RFC 124/129 catalog convention: v3 = v2 VERBATIM plus one appended paragraph;
rollback is `COACHING_SYSTEM_PROMPT_VERSION=v2`. The paragraph (approved copy,
final wording at implementation): once the student has schools on their list,
bring real cost into the conversation early — use `college_cost_profile`, lead
with the family-specific number when it exists; when the income band is
unanswered, give the overall average, say what it is, and — cued by the result's
`precision_offer` — offer to record the income band right there in the
conversation (`update_money_profile`) so the numbers become family-specific on
the next tool call; a declined band carries no `precision_offer` and the coach
never re-raises it — but if the STUDENT brings money back up or offers the band,
accept it warmly and record it (`update_money_profile` re-answers over declined
at any time; changing your mind is always allowed); always attribute numbers to
the College Scorecard and say plainly when a school doesn't report a figure.

### Wiring

`CollegeCostChatTool` registered beside `update_money_profile` in the
composition root (`rest-server`'s `Application.kt`, where the `ToolRegistry` is
built). `CollegeCostService` constructed in the same composition root with the
DAOs it reads. No REST surface — this is a coach capability; the student-facing
cost UI is S5 (Family Cost Report).

## Files Modified

- `service/src/main/kotlin/ed/unicoach/coaching/costs/CollegeCostService.kt` —
  new
- `service/src/main/kotlin/ed/unicoach/coaching/costs/CollegeCostChatTool.kt` —
  new
- `rest-server/.../Application.kt` (edit) — registry + construction
- `db/schema/0047.seed-coach-system-prompt-v3.sql` — new (catalog seed)
- `service/src/main/resources/service.conf` (edit) — pin v3
- `db/src/main/kotlin/ed/unicoach/db/dao/CollegeListEntriesDao.kt` — only if the
  college join needs a new read (prefer an existing one)
- Tests per below

## Implementation Plan

1. CollegeCostService: composition + per-college object + fallback basis.
2. CollegeCostChatTool: input parse, render, registration.
3. 0047 v3 seed + service.conf pin.
4. Tests.

## Tests

- Service: band-answered -> your_income_band amount for the right band;
  band-unanswered and band-declined -> overall_average basis; residency answered
  -> applicable tuition for public in-state/out-of-state, private unaffected;
  absent profile row = all-unanswered; nulls surface in data_availability; empty
  list; subset filter; unknown ids.
- Tool: definition schema, empty-list structured result, money-profile block
  statuses, structured error on malformed input, write-nothing guarantee.
- precision_offer: present when band unanswered, absent when answered, absent
  when DECLINED (the ethos assertion: the coach never initiates a reopen).
- Service/tool: a band re-answered after decline flows through on the next call
  (basis flips to your_income_band) — changing your mind works end-to-end.
- Prompt: 0047 seed row content pinned (house prompt-seed test convention),
  service.conf points at v3.
- Coaching integration: tool registered and dispatchable student-scoped (mirrors
  update_money_profile registration test).
