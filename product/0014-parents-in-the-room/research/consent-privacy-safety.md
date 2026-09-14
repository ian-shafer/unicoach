# Consent, privacy, and safety floor for parent↔student account linking

**Brief 0014 — parents in the room. Research question:** what law and what good
practice require when a US consumer edtech product links a (often 13–18,
sometimes minor) student's account to an adult parent's account.

## Method

- The `websearch` skill (Serper) was **unavailable in this session** (no API key
  configured). I state this honestly per the task requirements.
- Instead I fetched primary sources directly over HTTPS (`httpx` +
  BeautifulSoup): FTC COPPA FAQ and press releases, the Federal Register API
  (for the amended COPPA Rule's effective date), the US Dept. of Education's
  studentprivacy.ed.gov (FERPA regulations text and FAQ), the FSA Handbook on
  fsapartners.ed.gov (FAFSA contributor model), the California AG's CCPA page,
  Apple's App Store Review Guidelines, and NetChoice's litigation-tracker pages
  for the state-law court status.
- Caveat: NetChoice is the **plaintiff** in the state-law cases and an advocacy
  group; I use its pages only for procedural facts (what was enjoined, when,
  what is on appeal), which are the kind of facts a litigant reports accurately,
  not for characterization of the laws.
- One Bing query was used as a search fallback; it produced nothing
  load-bearing.

## 1. COPPA — why 13 is the line, and the under-13 edge

**What the law says.** COPPA and the FTC's COPPA Rule apply to operators of
sites/services _directed to children under 13_, and to _general-audience_
services with **actual knowledge** that they are collecting personal information
from a child under 13
([FTC COPPA FAQ](https://www.ftc.gov/business-guidance/resources/complying-coppa-frequently-asked-questions)).
Covered operators must post a children's privacy policy, obtain **verifiable
parental consent** before collecting/using/disclosing a child's personal
information, and honor a parent's right to review and to require **deletion** of
the child's data (same source). COPPA imposes **no obligations for teens 13+** —
that is why nearly every US consumer service sets 13 as its minimum age: staying
13+ keeps you out of COPPA's verifiable-parental-consent machinery entirely.

**Recent change.** The FTC finalized amendments in January 2025
([FTC press release](https://www.ftc.gov/news-events/news/press-releases/2025/01/ftc-finalizes-changes-childrens-privacy-rule-limiting-companies-ability-monetize-kids-data)),
published April 22, 2025, **effective June 23, 2025**
([Federal Register 2025-05904](https://www.federalregister.gov/documents/2025/04/22/2025-05904/childrens-online-privacy-protection-rule)).
The amendments notably require separate parental **opt-in for disclosure to
third parties / targeted advertising** and tighten data-retention rules. None of
this changes the age-13 line.

**The under-13 edge for unicoach.** unicoach is a general-audience service
(students + parents), not "directed to children." So COPPA bites only via
**actual knowledge**. Two practical consequences:

1. Ask date of birth (a neutral age gate) at student signup. If the answer is
   under 13, **refuse the account** rather than build a COPPA consent flow — the
   compliance cost (verifiable parental consent methods, parental
   review/deletion rights, retention limits) is not worth it for a
   college-planning product whose youngest sensible user is ~13–14. Even the
   FAFSA has a special manual process for the rare under-13 filer
   ([FSA Handbook AVG Ch. 1](https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2025-2026/application-and-verification-guide/ch1-application-process-fafsa-isir)).
2. The parent-linking feature creates an actual-knowledge hazard: a parent
   free-texting "my daughter is 11" gives the operator actual knowledge. Have a
   policy: on credible signal that a linked student is under 13, suspend the
   student account pending age confirmation, and delete the child's data if
   unresolved. Apple's rules echo this: apps may ask for birthdate/parental
   contact **only** to comply with these statutes, and must comply with all
   applicable children's-privacy law when handling minors' data
   ([App Store Review Guidelines 5.1.4](https://developer.apple.com/app-store/review/guidelines/)).

## 2. FERPA — why it (almost certainly) does not bind unicoach directly

FERPA's regulations apply, by their own scope section, "to an educational agency
or institution **to which funds have been made available under any program
administered by the Secretary** [of Education]" — i.e., schools and districts
receiving federal education funds
([34 CFR §99.1, studentprivacy.ed.gov](https://studentprivacy.ed.gov/ferpa)).
unicoach is a direct-to-consumer product; it receives no ED program funds and is
not an educational agency or institution. FERPA reaches vendors only
**derivatively**, when a school shares education records with a vendor acting as
a "school official" under contract. unicoach has no school contracts and holds
no school-created education records, so FERPA does not bind it directly.

Two FERPA points still matter as **design signal**, not obligation:

- **The rights flip at 18.** Under FERPA, when a student turns 18 (or enters
  postsecondary at any age), the rights transfer **from parent to student**
  ([ED FERPA FAQ](https://studentprivacy.ed.gov/faq/what-ferpa)). Federal
  policy's settled intuition is that an 18-year-old — exactly unicoach's
  college-applicant — controls their own record, and the parent's access is by
  the student's grace. A linking model should match that intuition even for
  16–17-year-olds.
- If unicoach ever sells to schools/districts, FERPA (and state student-privacy
  laws like California's SOPIPA) would apply through the contract. Keep the
  consumer architecture clean enough that this door stays open.

## 3. State teen-privacy laws — current status (as of late 2026)

The wave of state laws aimed specifically at minors' online experience is
**mostly enjoined** on First Amendment grounds, but the direction of travel is
real:

- **California AADC (AB 2273).** Preliminarily enjoined since Sept 2023; after
  remand and a third NetChoice preliminary-injunction win (March 2025), the
  **Ninth Circuit in March 2026 held a majority of the Act unconstitutional — 5
  of the 6 challenged substantive provisions remain enjoined**, with the last
  remanded
  ([NetChoice v. Bonta tracker](https://netchoice.org/netchoice-v-bonta/)). Do
  not build to the AADC as binding law; do treat its themes (high-default
  privacy for minors, no dark patterns, data-minimization, DPIA-style thinking)
  as the likely shape of future obligations.
- **Texas SCOPE Act (HB 18).** The central "monitoring-and-filtering"
  requirement is enjoined, and the **Fifth Circuit affirmed the injunction**
  ([CCIA & NetChoice v. Paxton (2024) tracker](https://netchoice.org/ccia-netchoice-v-paxton-texas-2024/)).
  Note SCOPE targets "digital service providers" with social-media-like
  features; a 1:1 coach + family-linking product is plausibly out of scope
  anyway, but the parental-tools provisions (parents can view/adjust a minor's
  privacy settings) are the norm Texas wants.
- **Utah Minor Protection in Social Media Act.** Preliminarily enjoined Sept 10,
  2024; on appeal in the Tenth Circuit
  ([NetChoice v. Reyes tracker](https://netchoice.org/netchoice-v-reyes/)).
  Aimed at social-media companies; unicoach is unlikely to qualify.
- **Arkansas Act 689 (Social Media Safety Act).** **Permanently enjoined** as
  unconstitutional on March 31, 2025; on appeal in the Eighth Circuit
  ([NetChoice v. Griffin tracker](https://netchoice.org/netchoice-v-griffin/)).
- **What is NOT enjoined and does apply: general state privacy laws.**
  California's CCPA requires **opt-in** before _selling/sharing_ the personal
  information of a consumer known to be under 16 — the **13–15-year-old can opt
  in themselves**; under 13 it must be the parent
  ([California AG CCPA page](https://oag.ca.gov/privacy/ccpa)). Several other
  state comprehensive laws similarly restrict targeted advertising / sale for
  known minors. Simplest compliance posture for unicoach: **never sell or share
  student data for advertising at all** — then the teen-specific opt-in
  provisions never trigger.

## 4. Apple App Store rules relevant to linking, invitations, deletion

- **Account deletion — Guideline 5.1.1(v):** "If your app supports account
  creation, you must also offer account **deletion within the app**"
  ([App Store Review Guidelines](https://developer.apple.com/app-store/review/guidelines/)).
  This binds both the student and the parent account. Deletion of a linked
  account must therefore work in-app without the other party's permission —
  design the link model so either side's deletion is always possible (it is; see
  §5).
- **5.1.1(v) also:** apps "may not require users to enter personal information
  to function, except when directly relevant to core functionality." Parent
  linking is core functionality for the parent experience, so requesting the
  student's invite email/code is fine — but don't make linking a precondition of
  using the app at all.
- **5.1.4 (Kids):** if any minor data is collected, a privacy policy is
  mandatory and all applicable children's privacy statutes apply;
  birthdate/parental-contact collection is allowed only for compliance purposes
  (same URL). unicoach should _not_ enter the Kids Category (it is 13+), which
  avoids the stricter 1.3 parental-gate regime.
- Invitations: cross-invites by email are ordinary; note 5.1.1(v)'s
  social-network language — "inviting friends to use the app" is explicitly
  _not_ core functionality, so the app must be usable without completing an
  invite.

## 5. Consent norms — what good practice (not law) recommends

Law is nearly silent on parent↔teen linking in a 13+ consumer product. The
strongest norm-setters are the FAFSA itself and FERPA's age-18 flip:

- **The FAFSA "contributor" model is the best precedent, and it is unicoach's
  own domain.** Each contributor (student, parent, parent spouse) has their
  **own account**, completes their **own section**, and provides their **own
  consent** to federal-tax-information use; no one can consent on another's
  behalf, and the form is partitioned by contributor
  ([FSA Handbook AVG Ch. 2](https://fsapartners.ed.gov/knowledge-center/fsa-handbook/2025-2026/application-and-verification-guide/ch2-filling-out-fafsa-form)).
  Families applying to college already know this shape: _parent supplies parent
  data, sees parent data; student's side is the student's._
- **Mutual consent to link.** A link should require acceptance by the receiving
  side in both directions (student accepts parent's invite; parent accepts
  student's). No US law mandates this for 13+, but every mainstream supervision
  product (Apple, Google) requires the teen's participation to establish 13+
  supervision, and FERPA's flip-at-18 says the student is the presumptive owner.
  Silent linking would also be the single fastest way to violate the product's
  own "parents never direct" thesis.
- **Default visibility — recommended split:**
  - **Coach conversations: private to each party, absolutely.** The parent never
    sees the student's chats and vice versa. This is the product's trust core; a
    teen who believes a parent can read the transcript will not say the true
    thing ("I don't want to apply to Dad's alma mater"). No law requires sharing
    them; nothing should.
  - **Money profile: shared by construction, attributed by author.** The parent
    typically _supplies_ household finances, so the FAFSA rule applies: each
    party sees and edits its own inputs; derived outputs (net-price estimates,
    affordability verdicts) are visible to both, because they are the shared
    work product. Do not show the parent's raw income figures to the student by
    default (mirror FAFSA, where the student never sees parental FTI) — show the
    student the _conclusions_.
  - **College list: student-owned, visible to parent by student's choice,
    defaulting to visible at link time with a one-tap per-entry veil.**
    Visibility invites conversation; a hidden-by-default list makes the parent's
    coach useless. But the student must be able to keep an exploratory entry
    private.
- **Unlink and deletion.** Either side can sever the link at any time (the
  task's stated design — correct). On **account deletion** (Apple-mandated
  in-app): delete the account, sever all links, and **partition the data by
  author** — the student's deletion removes the student's conversations, list,
  and profile but leaves the parent's own account, the parent's own
  conversations, and the parent-supplied financial data intact (it is the
  parent's data); and symmetrically. Notify the other side that the link ended,
  without saying why. CCPA/state deletion rights point the same way: each
  consumer can demand deletion of _their_ data
  ([CCPA](https://oag.ca.gov/privacy/ccpa)).

## Implications for unicoach (ranked)

1. **Set the floor at 13+, enforce it with a neutral DOB gate, and refuse
   under-13 signups.** This keeps unicoach wholly outside COPPA's
   verifiable-parental-consent regime. Write down the actual-knowledge
   escalation path (suspend → confirm → delete) now, because the parent channel
   _will_ generate "my 12-year-old" signals.
2. **Require the student's explicit acceptance for every link, both directions,
   at every age.** Law doesn't require it at 13+; the product thesis does, and
   FERPA's age-18 flip plus the FAFSA contributor model make it the norm
   families already expect.
3. **Make coach conversations non-shareable by design, not by setting.** Don't
   build a "share transcript with parent" toggle you'll be pressured to default
   on later. State it in the privacy policy as a commitment.
4. **Copy the FAFSA partition for money data:** author-owned inputs, mutually
   visible derived outputs, parent raw figures hidden from the student by
   default. This is both the compliant shape and the shape families recognize.
5. **Build in-app deletion (Apple 5.1.1(v)) with author-partitioned data
   outcomes and link-severance notifications from day one** — retrofitting
   deletion across a many-to-many link graph is far more expensive than
   designing it in.
6. **Do not sell or share student data for advertising, ever, and say so.** It
   moots the CCPA under-16 opt-in, the 2025 COPPA amendments'
   third-party-disclosure opt-in, and most of what the (enjoined) state teen
   laws would demand — and it is the only tenable posture for a product holding
   teens' private college anxieties and parents' household finances.
7. **Track, don't build to, the AADC/SCOPE wave.** Mostly enjoined today (Ninth
   Cir. March 2026; Fifth Cir. affirmance; Utah and Arkansas enjoined), but
   data-minimization, high-privacy defaults for minors, and parental-tools
   expectations are where US law is heading; unicoach's design above already
   satisfies their spirit.
