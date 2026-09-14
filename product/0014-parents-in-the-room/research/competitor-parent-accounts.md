# Competitor research: parent / guardian / supporter accounts

**Brief:** 0014-parents-in-the-room · **Question:** how do existing
college-planning and adjacent products handle parent accounts — linking,
permissions, unlinking, and what users complain about.

## Method

- **Tools used:** direct HTTP fetching (httpx + BeautifulSoup) of vendor help
  centers, marketing sites, and sitemaps; the Wayback Machine CDX API + archived
  captures for pages that block bots or no longer exist; the public iTunes
  Search / customer-reviews RSS API for app-store review mining.
- **Tools unavailable (honestly):** the `websearch` skill has no Serper API key
  configured, and every general search engine tried (DuckDuckGo HTML/lite, Bing,
  Mojeek, SearXNG instances, Marginalia) either bot-blocked or returned degraded
  canned results. Reddit's JSON search API returned 403. So forum-complaint
  coverage is thin; app-store complaints come from the iTunes reviews RSS only.
- **Coverage gaps flagged inline:** Naviance and MaiaLearning keep their real
  product docs behind logins (PowerSchool Community requires JS/auth;
  MaiaLearning's Freshdesk KB requires login), so those sections rely on sign-in
  surfaces, marketing pages, and clearly-flagged unverified knowledge.

## Product-by-product

### Scoir — the richest model, and the closest comparable

- **Own account:** yes; a first-class Parent/Guardian role with its own
  dashboard, inbox, profile, and mobile app
  ([dashboard guide](https://help.scoir.com/article/lgenr2ys89-parents-guardians-dashboard-guide)).
- **Linking:** three entry paths — student email-invites the parent from their
  profile, the school invites/bulk-uploads parents, or the parent self-registers
  at signup and invites the student
  ([parent account & linking](https://help.scoir.com/article/vvzwsgqgvr-for-parents-guardians-managing-your-account-linking-to-students),
  [student invites parent](https://help.scoir.com/article/gbggus55fk-inviting-parents)).
  Every link is consent-gated: the invited side must click **Accept**.
- **Cardinality:** many-to-many — a parent links to "your student(s)", a student
  can invite multiple parents.
- **Parent can SEE/DO:** view the student's college list and suggested matches,
  **suggest colleges** to the student, use financial/cost calculators, see
  college visits and college messages, complete parent-targeted assignments.
  Notably view-plus-suggest, never edit.
- **Unlink:** the student can unlink a parent at any time ("Unlink Parent" →
  "Confirm Unlink"); unlinking removes the connection but **does not delete the
  parent's account**
  ([same article](https://help.scoir.com/article/gbggus55fk-inviting-parents)).
- **Failure mode (documented by Scoir itself):** a **two-edge permission model**
  — parent↔student AND parent↔school — where a student-initiated invite creates
  only the first edge. Parents then "mysteriously" lack Scattergrams, surveys,
  and school features until a counselor separately approves the school link;
  Scoir needs a whole troubleshooting article and a counselor "Pending parents"
  roster filter to mop this up
  ([troubleshooting parent linking](https://help.scoir.com/article/cnvlou86i7-for-counselors-troubleshooting-parent-linking-issues)).
  Parents also lose access entirely when their student graduates.

### Naviance (PowerSchool) — school-controlled, parents as spectators

- **Own account:** yes, but school-provisioned: the sign-in surface is "For
  Students and Families" and troubleshooting routes to "contact your school or
  district site manager" ([id.naviance.com](https://id.naviance.com/)).
  Historically the parent product was literally called "Family Connection"
  ([archived login](https://web.archive.org/web/20240508080625/https://succeed.naviance.com/family-connection/auth/login/)).
- **Linking:** the school creates/enables parent accounts (registration codes or
  school-sent invites, incl. SAML SSO for parents — an archived
  [SAML for Parent quick guide](https://web.archive.org/web/20231227102023/http://help.naviance.com/Content/Documents/Quick_Guides/SAML_for_Parent_Quick_Guide.pdf)
  existed on help.naviance.com). Neither student nor parent can self-serve a
  link; PowerSchool markets this as "engagement with all students and families"
  ([CCLR product page](https://www.powerschool.com/products/college-career-life-readiness/cclr/)).
- **Parent can SEE/DO:** view-mostly (college lists, tasks, surveys where
  enabled). _Caveat: capability detail is behind the login-gated
  [PowerSchool Community Naviance hub](https://help.powerschool.com/t5/Naviance/ct-p/Naviance);
  treat specifics as unverified here._
- **Unlink:** not user-controlled; the school administers it.
- **Lesson:** when the institution owns the link, parents get access late, lose
  it on graduation, and nobody in the family can fix a broken link themselves.

### CollegeVine — the cautionary tale: the consumer product left the room

- Historically a free consumer platform (chancing, lists, community) with
  student accounts
  ([archived account page, 2023](https://web.archive.org/web/20230622225952/https://www.collegevine.com/account));
  parents participated mainly as forum members and as inputs to financial
  chancing.
- Today collegevine.com sells "The AI operating system for universities"
  ([homepage](https://www.collegevine.com/)) and its help center documents an
  institutional "Agent Platform"
  ([help.collegevine.com](https://help.collegevine.com/en/)). The consumer
  student/parent experience is effectively gone.
- **Lesson:** none of the money/coaching relationships families built there
  survived the pivot; a durable family workspace is a differentiator precisely
  because incumbents abandon it.

### Common App — no parent account; a deliberately weak "Advisor" role

- Parents never get an application account. A student may invite up to **3
  Advisors** — explicitly including **family members** — who get **read-only**
  review of the in-progress application and progress tracking; "No forms are
  submitted while using the advisor role. Colleges do not receive any
  information about advisors."
  ([commonapp.org/advisors](https://www.commonapp.org/advisors)).
- **Linking:** student-initiated email invite only; the student chooses and can
  remove advisors. One-to-few (cap of 3).
- **Lesson:** the highest-stakes system in the domain chose _student-initiated,
  read-only, invisible-to-colleges_ as the safe design point for parents.

### BigFuture (College Board) — content community, zero account linking

- No parent account linked to a student. The "BigFuture Parent Community" is
  emails and live virtual events for parents
  ([parents.collegeboard.org](https://parents.collegeboard.org)); the help
  center's parent articles are about FAFSA and events, not accounts (e.g.
  [can parents attend events](https://bigfuture.collegeboard.org/help-center/can-parents-and-guardians-attend-event)).
- **Lesson:** the biggest player serves parents _content_ but gives them no
  shared state with the student — an obvious gap unicoach can exploit.

### Going Merry (scholarships) — parent accounts that are deliberately NOT linked

- Parents get a free account with scholarship search, financial-aid resources,
  appeal-letter generator, budgeting, aid-package comparison
  ([archived parents page](https://web.archive.org/web/20250114230757/https://www.goingmerry.com/parents),
  [help article](https://web.archive.org/web/20241202102818/https://support.goingmerry.com/en/articles/7116987-parents-can-i-sign-up-for-an-account)).
- But: "**Student and parent accounts are not linked for security purposes.**"
  One child profile per parent account; a parent with two kids is told to create
  **two parent accounts** (same source).
- **Lesson:** the anti-pattern. No shared state means the parent re-enters
  everything, sees nothing real, and multi-child parents get account sprawl.
  (Live site is Cloudflare-blocked to bots; archives used.)

### Appily (formerly Cappex) — parent content hub, self-serve account, no documented link

- "Appily for Parents" markets college search, virtual tours, scholarships, and
  a fit quiz to parents and invites them to "Create an Account"
  ([appily.com/guidance/parents](https://www.appily.com/guidance/parents));
  public docs show no student↔parent linking mechanism.
- **Lesson:** parents-as-second-audience without linking produces a parallel,
  disconnected experience.

### MaiaLearning — flagged, not verified

- Public marketing addresses "Students & Parents" together
  ([maialearning.com/students-parents](https://www.maialearning.com/students-parents))
  but documents no guardian mechanics; the KB is login-gated
  ([Freshdesk portal](https://maialearning.freshdesk.com/support/home)). Schools
  using it describe school-provisioned guardian accounts with view +
  college-recommendation rights, i.e. Naviance/Scoir-like — **unverified**,
  treat as a hint only.

### Adjacent-domain comparisons

- **Khan Academy:** parents have a real Parent role + dashboard. Linking is
  bidirectional and age-aware: parent creates the child account (<13), or
  email-invites a 13+ child who must **accept from their Learner Home**; a child
  can equally invite the parent. Parents see activity/progress reports and can
  create assignments
  ([parent quick-start guide](https://support.khanacademy.org/hc/en-us/articles/360040168512-Khan-Academy-for-Parents-Quick-Start-Guide)).
  Many-to-many in practice (add multiple children).
- **Duolingo:** the "Family Plan" is a _billing_ group — a manager shares a
  Super subscription with up to 5 others via a share link (SMS/WhatsApp/email);
  members can be anyone anywhere, and the manager gets **no visibility or
  control** over members' learning
  ([invite flow](https://web.archive.org/web/20240304080317/https://support.duolingo.com/hc/en-us/articles/4546572968589-How-do-I-invite-members-to-my-Family-Plan),
  [general FAQ](https://web.archive.org/web/20230927161929/https://support.duolingo.com/hc/en-us/articles/4546736159373-General-questions-about-Family-Plan)).
  Lesson: "family" can mean billing only; naming matters.
- **Greenlight (teen banking):** opposite pole — parent-owned. Signup forks
  "Parent or guardian / Teen" on the homepage; parents set controls and approve
  investments ("$75M+ invested with parent approval",
  [greenlight.com](https://www.greenlight.com/)). App-store reviews (iTunes RSS,
  Nov 2025 window) show the failure modes of parent-controlled linking:
  kids/teens locked out and unable to self-serve ("Won't let me sign in… I need
  it so I can use the card"), and **two-parent coordination bugs** — "spouse and
  I both approving same things"
  ([Greenlight reviews feed](https://itunes.apple.com/us/rss/customerreviews/page=1/id=1049340702/sortby=mostrecent/json)).

## Patterns that work

1. **Explicit, symmetric, consent-gated linking** (Scoir, Khan Academy): either
   side can initiate; the other side must accept; either side can sever; unlink
   ≠ account deletion.
2. **View-plus-suggest permission ceiling** (Scoir's "suggest colleges", Common
   App's read-only advisor): parents get real visibility and a structured way to
   contribute, but no write access to the student's plan.
3. **Parent-specific value, not a mirrored student UI** (Scoir cost calculator +
   parent assignments; Going Merry's aid tools): the parent account earns its
   keep with money tools — exactly unicoach's strength.
4. **Age/consent-aware flows** (Khan Academy): under-13 vs 13+ handled
   differently and the teen always accepts on their own surface.

## Failure modes to avoid

1. **Two-edge link graphs with a hidden third party** (Scoir's parent↔school
   edge): every extra approval edge generates "why can't I see X" support load.
   Keep unicoach's link a single parent↔student edge.
2. **No linking at all** (Going Merry, Appily, BigFuture): parent re-enters
   data, sees stale or no truth, multi-child = multiple accounts.
3. **Institution-owned links** (Naviance): nobody in the family can create or
   repair the relationship; access dies on graduation.
4. **Parent-owned accounts that infantilize the student** (Greenlight): lockouts
   and parent-as-gatekeeper resentment — the exact inversion unicoach rejects.
5. **Multi-parent coordination left undesigned** (Greenlight's double-approval
   reviews): with many-to-many, define what each parent sees of the _other_
   parent's actions.
6. **"Family" as billing bundle** (Duolingo): if the link carries no shared
   context, don't call it a family feature.

## Implications for unicoach (ranked, opinionated)

1. **Copy Scoir's link lifecycle, minus the school edge:** invite by email from
   either side, explicit accept, either side unlinks anytime, unlink never
   deletes the other account. This is the industry's proven consent shape and
   matches the RFC's many-to-many + cross-invite design exactly. Nobody in this
   market does it _without_ the institutional third wheel — that's the opening.
2. **Set the permission ceiling at view + suggest + own-workspace, and say so at
   invite time.** Common App's read-only advisor and Scoir's suggest-only parent
   are the two strongest precedents that "parents never direct" is a viable,
   even standard, design. Make the invite email itself state what the parent
   will and won't be able to do — it markets the product's point.
3. **Give the parent account money tools on day one** (cost truth, aid
   comparison, budget timeline — coached). Going Merry proves parents will
   create accounts just for aid tooling; Scoir leads its parent pitch with
   financial calculators; BigFuture serves parents only content. A parent
   account whose _first screen_ is money value, not a surveillance feed of the
   student, is both differentiated and on-message.
4. **Design multi-parent visibility explicitly:** each linked parent should see
   that other parents are linked and what they've suggested, or you get
   Greenlight's duplicate-action mess (divorced-parent privacy needs a
   per-parent workspace, shared student-facing surface).
5. **Make the student the sovereign of the link:** show them exactly what each
   parent can see, let them unlink in two clicks (Scoir's bar), and coach them
   through it rather than burying it in settings.
6. **Expect the "pending link" support burden and build the empty state for
   it:** Scoir's dashboard tells unlinked parents precisely what they're missing
   and how to fix it. An unlinked parent account should still be useful (money
   content) _and_ relentlessly clear about what linking adds.
