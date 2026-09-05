# Dual-entry patterns: chat + an explicit profile screen

Research report for product brief **0007 — explicit profile view** (unicoach).
Scope: external precedent only. Question: unicoach today lets a family set
household income band, state of residence, and college list **only** by talking
to the coach. Should there be a dedicated screen that shows and edits the same
facts?

---

## Method

- Date of research: 2026-09-05.
- The `websearch` skill was **not available** (no Serper API key configured), so
  search was done through the DuckDuckGo HTML endpoint, and every load-bearing
  claim was then verified by fetching the **primary page directly** and quoting
  it. Where I could only get a search-result snippet and not the page itself, I
  say so inline and in "What I could not verify".
- Primary sources fetched and read in full: Apple App Store Review Guidelines;
  five Apple Human Interface Guidelines pages (via Apple's own DocC JSON
  endpoint,
  `https://developer.apple.com/tutorials/data/design/human-interface-guidelines/<page>.json`,
  because the HTML pages are JavaScript-rendered); Anthropic product blog and
  Claude Help Center; Microsoft Support (Copilot memory); Google Gemini Apps
  Help; OpenAI `learn.chatgpt.com` docs; Google PAIR People + AI Guidebook;
  Amershi et al., CHI 2019 (PDF); Baymard Institute; GDPR Art. 16.
- Blocked hosts (403 / rate limit) that I could **not** read directly:
  `help.openai.com`, `openai.com/index/...`, `microsoft.com/en-us/haxtoolkit`,
  `dl.acm.org`, `help.replika.com`, `help.headspace.com`, `web.archive.org`
  (429).
- Quotations are verbatim from the fetched page. Emphasis in quotes is mine.

---

## 1. Apple platform expectations

### 1.1 Hard requirements (App Store Review Guidelines)

Source: <https://developer.apple.com/app-store/review/guidelines/> (fetched and
read).

**There is no rule requiring that user-provided data be viewable or editable in
a dedicated screen.** I read 5.1.1 and 5.1.2 in full. What Apple actually
requires that is adjacent:

- **5.1.1(i) Privacy Policies** — the policy must "Explain its data
  retention/deletion policies and describe how a user can revoke consent and/or
  request deletion of the user's data." This is a _disclosure_ duty, not a UI
  duty.
- **5.1.1(ii) Permission** — "Apps must also provide the customer with an
  **easily accessible and understandable way to withdraw consent**." This is the
  closest thing to a UI requirement, and it is about _consent to collection_,
  not about editing a stored value.
- **5.1.1(iv) Access** — "Where possible, provide alternative solutions for
  users who don't grant consent. For example, if a user declines to share
  Location, **offer the ability to manually enter an address**." Note the
  direction of this rule: the manual, explicit entry path is Apple's named
  _fallback_ for a user who refuses the automatic path. It is a convention that
  maps well onto "coach asks vs. user types it".
- **5.1.1(v) Account Sign-In** — "If your app supports account creation, you
  must also offer account deletion within the app. **Apps may not require users
  to enter personal information to function, except when directly relevant to
  the core functionality of the app or required by law.**" Account _deletion_
  in-app is a hard requirement. Forcing income band / state as a precondition to
  using the app would be the thing at risk here, not the absence of a profile
  screen. Household income for a net-price estimate is plainly "directly
  relevant to core functionality", so an _optional_ profile field is safe; a
  _mandatory gate_ is where the rule bites.
- **5.1.1(x)** — "Apps may request basic contact information ... so long as the
  request is **optional for the user, features and services are not
  conditional** on providing the information".
- **5.1.2(ii) Data Use and Sharing** — "Data collected for one purpose may not
  be repurposed without further consent unless otherwise explicitly permitted by
  law."

**Verdict for section 1 (hard):** Apple mandates in-app **account deletion** and
an easily accessible way to **withdraw consent**, and forbids making the app
conditional on personal information that is not core. Apple does **not** mandate
a viewer/editor for a stored profile field. A chat-only editing model is not a
review risk on its face.

### 1.2 Legal requirements outside Apple (stronger than Apple's, still not UI-specific)

- **GDPR Art. 16** — "The data subject shall have the right to obtain from the
  controller without undue delay the **rectification** of inaccurate personal
  data concerning him or her." <https://gdpr-info.eu/art-16-gdpr/>
- **California Civil Code §1798.106 (CPRA right to correct)** — "A consumer
  shall have the right to request a business that maintains inaccurate personal
  information about the consumer to correct that inaccurate personal
  information". <https://codes.findlaw.com/ca/civil-code/civ-sect-1798-106/> (I
  read the search snippet plus the FindLaw codification; I did **not** fetch the
  OAG page.)

Both create a right to **correction**, satisfied by any working mechanism —
including a support request. Neither prescribes a screen. But note the risk
asymmetry: if the only correction path is "argue with a language model that may
not comply", the mechanism is harder to defend as reliable than a form that
writes a column.

### 1.3 Conventions (Human Interface Guidelines)

All quotes below fetched from Apple's own DocC JSON for the HIG.

**Settings**
(<https://developer.apple.com/design/human-interface-guidelines/settings>):

- "When necessary, you can provide a **custom settings area within your app**
  ... to offer general settings that affect your overall experience ... If you
  need to offer settings that affect **only a specific task, you can provide
  these options within the task itself**, so people don't have to leave the
  experience to customize it."
- "**Minimize the number of settings you offer.** Although people appreciate
  having control over an app or game, too many settings can make the experience
  feel less approachable".
- "**Avoid using settings to ask for setup information you can get in other
  ways.**"
- "Put general, **infrequently changed** settings in your custom settings area.
  People must suspend what they're doing to open an app's or game's settings
  area".

This cuts both ways for unicoach and is the most important HIG passage for the
decision. State of residence and income band are _general and infrequently
changed_ → settings-area material. A college list is _task-specific and
frequently changed_ → HIG says put it "within the task itself", i.e. keep it in
or beside the conversation, not buried in a settings screen.

**Onboarding**
(<https://developer.apple.com/design/human-interface-guidelines/onboarding>):

- "if onboarding is necessary, design a flow that's fast, fun, and
  **optional**."
- "**Postpone nonessential setup flows or customization steps.** Provide
  reasonable default settings so most people can immediately start interacting
  with your app".

This is Apple stating unicoach's own "value before ask" ethos. It argues against
turning a new profile screen into a first-run wizard.

**Entering data**
(<https://developer.apple.com/design/human-interface-guidelines/entering-data>):

- "**Get information from the system whenever possible.** Don't ask people to
  enter information that you can gather automatically".
- "**When possible, offer choices instead of requiring text entry.** It's
  usually easier and more efficient to choose from lists of options than to type
  information ... consider using a picker, menu, or other selection component."
- "**Dynamically validate field values.** People can get frustrated when they
  have to go back and correct mistakes after filling out a lengthy form."

Income _band_ and _state_ are both closed sets → picker, not free text. This is
a real argument for a form over chat for these two fields specifically: a picker
cannot produce "Washington DC" when the user meant Washington state.

**Privacy**
(<https://developer.apple.com/design/human-interface-guidelines/privacy>):

- "Request access only to data that you actually need. **Asking for more data
  than a feature needs — or asking for data before a person shows interest in
  the feature — can make it hard for people to trust your app.**"

**Machine learning**
(<https://developer.apple.com/design/human-interface-guidelines/machine-learning>)
— Apple's "corrections" pattern is the closest HIG analogue to the
reconciliation question:

- "**Make it easy for people to correct frequent or predictable mistakes.** If
  you don't give people an easy way to correct mistakes, they can lose trust in
  your app."
- "**Provide immediate value when people make a correction.** Reward people's
  effort by instantly displaying corrected content ... Also, be sure to
  **persist the updates so people don't have to make the same corrections
  again**."
- "**When possible, use guided corrections instead of freeform corrections.**
  Guided corrections suggest specific alternatives, so they require less user
  effort; freeform corrections don't suggest specific alternatives, so they
  require more input from people."
- Attributions "give people insight into where suggestions come from, which can
  help them understand mistakes" — Apple's word for provenance; the worked
  example is "Because you listen to pop music", i.e. a short, human-readable
  _why_.

Chat is by definition a **freeform** correction surface; a picker is a
**guided** one. Apple prefers guided.

**Generative AI**
(<https://developer.apple.com/design/human-interface-guidelines/generative-ai>):

- "**Keep people in control.** While AI can manipulate and create content,
  respect people's agency and ensure they remain in charge of decision making
  and the overall experience ... Give them the ability to dismiss new content
  they don't want, and **revert or retry** content transformations or other
  actions they don't agree with."
- "**Ensure a great experience even when generative features aren't available or
  people opt not to use them** ... When possible, consider offering a **non-AI
  fallback**."
- "Be transparent by ... **showing them what's shared**, and helping them
  understand what data may be stored off-device".

"Offer a non-AI fallback" and "show them what's shared" is, in Apple's own
words, the argument for the profile screen. It is a **strong convention**, not a
rule.

**Summary of section 1.** Hard: in-app account deletion; easily accessible
consent withdrawal; no personal-information gate on core function. Convention
(well-evidenced, from Apple): a non-AI fallback, showing people what you store,
guided rather than freeform correction, closed-set pickers, minimal settings,
and postponed setup. Nothing forces a profile screen; everything Apple writes
about AI features points toward one for the two stable fields.

---

## 2. Precedent: chat-first products that also expose an explicit editor

### 2.1 Claude (Anthropic) — the closest precedent, and it is explicitly dual-entry

Anthropic ships **both** surfaces and states that both write the same store.

- "Everything Claude remembers is in a list of files under **Topics in Memory
  settings**, where you can **read, edit, or delete** each one. The files are
  short, and a fix pays off everywhere: correct your company's old name in one
  file and every conversation from then on gets it right."
  <https://claude.com/blog/claudes-memory-works-everywhere-and-you-decide-whats-in-it>
- Help Center, on the older memory-summary experience: "See exactly what Claude
  remembers about you by navigating to **Settings > Capabilities** and clicking
  'View and edit memory.' ... In addition to asking Claude to edit the existing
  summary, you can also tell Claude what you want it to remember."
- **How the two are reconciled — stated explicitly:** "You can also update your
  memory summary directly from your chats. Simply tell Claude what you'd like it
  to remember, and it will update your memory summary without needing to leave
  the conversation. **Any edits made in this way will immediately apply to your
  next conversation, so you don't need to wait for the daily synthesis to
  run.**"
  <https://support.claude.com/en/articles/11817273-use-claude-s-chat-search-and-memory-to-build-on-previous-context>
- **Provenance is shown:** "When Claude references previous conversations,
  you'll see **citations linking back to the original chats**, along with the
  option to delete specific conversations." (same page)

Reading: one store, two write paths, chat writes take effect at the _next turn_
boundary, and the reader can trace a fact back to the chat it came from.

### 2.2 Microsoft Copilot — form + chat, with an in-chat acknowledgement

- "If Copilot comes across information that seems important to remember, **it
  asks you if you would like to save that information** for future chats."
- "You can also tell Copilot to remember specific information in Copilot Chat
  ... Copilot saves your preference to Copilot Memory in **Saved memories**.
  Copilot Chat **shows a notice that says `Memory updated`** to let you know
  that the information is now saved."
- "Copilot manages your saved memories intelligently, **merging related ones,
  updating outdated details**, or removing ones when you ask."
- Form path: "Chat settings → Personalization → Saved memories → Manage saved
  memories", with per-item Delete and Delete all.
  <https://support.microsoft.com/en-us/microsoft-365-copilot/manage-copilot-memory-in-microsoft-365-copilot>

Reading: consent **before** storing an inferred fact; a visible,
machine-generated acknowledgement (`Memory updated`) rather than a chatty
sentence; an explicit list view for review and deletion; vendor-side merge as
the conflict policy.

### 2.3 ChatGPT (OpenAI)

First-party docs I _could_ fetch (`learn.chatgpt.com`):

- "Open **Settings** to update your **personality, custom instructions,
  memories**, and other available personalization controls."
  <https://learn.chatgpt.com/docs/personalize>
- The single most transferable sentence for unicoach: "**Keep required team
  guidance in `AGENTS.md` or checked-in documentation. Treat memories as a
  helpful recall layer, not as the only source for rules that must always
  apply.**" <https://learn.chatgpt.com/docs/customization/memories>
- And on chat-derived memory files: "Treat these files as **generated state**.
  You can inspect them when troubleshooting ... but **don't rely on editing them
  by hand as your primary control surface**." (same page)
- Memory generation is asynchronous and lossy: "Memories may not update right
  away when a chat ends." (same page)

`help.openai.com` and `openai.com` both returned HTTP 403 to my client. From the
DuckDuckGo result snippet of OpenAI's own announcement page: "You can also view
and delete specific memories or clear all memories in settings (**Settings >
Personalization > Manage Memory**)" and "Deleting a chat doesn't erase its
memories; you must delete the memory itself."
(<https://openai.com/index/memory-and-new-controls-for-chatgpt/> — snippet only,
page not fetched). Custom instructions: "Your custom instructions are **applied
immediately to all chats**"
(<https://help.openai.com/en/articles/8096356-chatgpt-custom-instructions> —
snippet only).

Reading, and it is directly on point for brief 0007: OpenAI's own guidance
separates **binding, user-authored configuration** (custom instructions /
`AGENTS.md` — an explicit editable artifact) from **AI-inferred recall** (memory
— generated state). unicoach's income band, state, and college list are binding
inputs to a computation, not recall flavour. By OpenAI's own framing they belong
in the explicit artifact.

### 2.4 Google Gemini — the counterexample, and it is a cautionary one

Gemini's chat-derived memory has **no editable list**. Google's own
instructions:

- "**To delete something that Gemini remembered about you, delete all chats with
  this info** from Gemini Apps Activity."
- "To correct what Gemini knows about you and uses in a response: ... **Correct
  Gemini directly in your chat. While Gemini continues to improve, it might not
  always get it right.**"
- "If you delete a chat, **there might be a short delay** before Gemini stops
  using it to personalize your responses."
- Two-step deletion for connected apps: "If you only disconnect the app, Gemini
  might still use the info if it's in a past chat. If you only delete the chats,
  Gemini might still find the info in the connected app."
- "To check if Gemini used your past chats, just ask Gemini, 'Did you use any
  info from past chats?'"
  <https://support.google.com/gemini/answer/16598469?hl=en>

By contrast Gemini's _explicit_ layer — "Instructions for Gemini" — is a
first-class CRUD list: "Find the instructions you want to view, edit or delete.
Tap More → Edit or Delete."
<https://support.google.com/gemini/answer/16598625?hl=en>

Reading: the same vendor gives a **list UI to what the user typed** and a
**"tell it again and hope" UI to what the model inferred** — and has to warn
users that the hope-based path "might not always get it right". That is exactly
the failure unicoach would ship if it left income and state chat-only.

### 2.5 Other named apps

- **Replika** exposes a literal profile: the Help Center has a "Your Profile
  Settings" section with articles "How do I change my relationship status?",
  "How do I change my pronouns?", "How do I update my birthday or age?", "How do
  I change my name?" — i.e. the identity facts the companion converses about are
  also form fields.
  <https://help.replika.com/hc/en-us/sections/360000007551-Your-Profile-Settings>
  (403 to my client; article titles read from search results, see "could not
  verify").
- **Headspace** (not chat-first, included because the brief asked): reminders
  are set on a screen — "Tap the Profile icon on the bottom right corner of the
  app → Tap on the Settings gear".
  <https://help.headspace.com/hc/en-us/articles/115008276788-How-can-I-set-up-Reminders>
  (403 to my client; snippet only).
- **Duolingo**: the daily goal is a settings control, not something you
  negotiate with the AI characters. I could not find a first-party support
  article for it — every result was third-party how-to content. Treat as
  unverified.

---

## 3. The reconciliation problem, as reported

Honest framing: **I found little practitioner _essay_ writing on "the AI and the
form disagree".** What I did find is stronger than blog opinion — the shipping
vendors document the exact failure modes in their own help centres. Four named
problems:

1. **Staleness / propagation lag.** Gemini: "If you delete a chat, there might
   be a short delay before Gemini stops using it"
   (<https://support.google.com/gemini/answer/16598469?hl=en>). OpenAI:
   "Memories may not update right away when a chat ends"
   (<https://learn.chatgpt.com/docs/customization/memories>). Anthropic had to
   build an explicit bypass so chat edits do not wait for the batch job: edits
   "immediately apply to your next conversation, so you don't need to wait for
   the daily synthesis to run"
   (<https://support.claude.com/en/articles/11817273-use-claude-s-chat-search-and-memory-to-build-on-previous-context>).
   **Lesson for unicoach: whatever the profile screen writes must be in the
   coach's context on the very next turn, not on the next session.**
2. **Two stores, so deletion needs two actions.** Gemini's connected-apps case
   is documented above: deleting in one place leaves the fact live in the other.
   Whenever a fact exists in both a profile row _and_ a conversation transcript,
   correcting the row does not correct the transcript, and a model that re-reads
   history will resurrect the old value.
3. **Last-write-wins is the de facto policy, and vendors paper over it with
   merge.** Copilot: "Copilot manages your saved memories intelligently, merging
   related ones, **updating outdated details**"
   (<https://support.microsoft.com/en-us/microsoft-365-copilot/manage-copilot-memory-in-microsoft-365-copilot>).
   No vendor I read exposes a conflict UI or asks the user which value wins.
4. **Provenance is shown, but weakly and only in one direction.** Claude shows
   "citations linking back to the original chats" for facts drawn from
   conversation (same URL as above). Gemini's provenance affordance is to _ask
   the model_: "just ask Gemini, 'Did you use any info from past chats?'"
   (<https://support.google.com/gemini/answer/16598469?hl=en>). **I found no
   consumer product that labels a stored field with "you set this in Settings"
   vs "I inferred this from chat".** That is a gap unicoach could fill cheaply —
   a one-line source label per field — and Apple's HIG already has a name and a
   rationale for it: attributions "give people insight into where suggestions
   come from, which can help them understand mistakes"
   (<https://developer.apple.com/design/human-interface-guidelines/machine-learning>).

Design-research backing for keeping the two surfaces conceptually distinct: in
building the Microsoft HAX guidelines, the authors found evaluators
systematically confused the two: "our initial phrasing of Guideline 9 ('allow
efficient correction') and Guideline 17 ('allow coarse controls') caused several
evaluators to **confuse instance-level corrections with global-level settings**
... We subsequently rephrased Guideline 17 to include the term 'global'."
(Amershi et al., _Guidelines for Human-AI Interaction_, CHI 2019, §4, PDF:
<https://www.microsoft.com/en-us/research/wp-content/uploads/2019/01/Guidelines-for-Human-AI-Interaction-camera-ready.pdf>;
copy I actually read:
<https://haoyuma20492350.github.io/data/papers_pdf/AmershiSaleema2019Gfhi.pdf>).
If trained evaluators confuse "fix this one thing" with "change my standing
setting", families will too. The profile screen should read as **standing
state**, and the coach's in-chat fixes should read as **this-conversation
corrections that also persist**.

---

## 4. Should the assistant acknowledge a change made in the settings screen?

There is **guidance**, and it points to "acknowledge, briefly, at the moment of
use" — but I found **no A/B evidence** either way.

- **Microsoft HAX Guideline 16 — "Convey the consequences of user actions":
  "Immediately update or convey how user actions will impact future behaviors of
  the AI system."** Worked example given: "[The product] communicates that
  hiding an Ad will adjust the relevance of future ads." (Amershi et al., CHI
  2019, Table 1.) The paired **Guideline 17 — "Provide global controls": "Allow
  the user to globally customize what the AI system monitors and how it
  behaves."** These two guidelines together are the strongest external
  endorsement of "ship the profile screen _and_ have the coach reflect the
  change."
- **Guideline 18 — "Notify users about changes: Inform the user when the AI
  system adds or updates its capabilities."** (same table) — about capability
  changes, not data changes; do not over-read it.
- **Google PAIR:** "People learn faster when they can see a response to their
  actions right away, because then it's easier to identify cause and effect.
  This means **the perfect time to show explanations is in response to a user's
  action.** If the user takes an action and the AI system doesn't respond, or
  responds in an unexpected way, an explanation can go a long way in building or
  recovering a user's trust."
  <https://pair.withgoogle.com/chapter/explainability-trust/>
- **Apple HIG (corrections):** "**Provide immediate value when people make a
  correction.** Reward people's effort by instantly displaying corrected
  content, especially when the feature is critical or you're responding to
  direct user input."
  <https://developer.apple.com/design/human-interface-guidelines/machine-learning>
- **Shipped precedent is a terse system notice, not a chatty line.** Copilot
  writes "**Memory updated**" into the chat
  (<https://support.microsoft.com/en-us/microsoft-365-copilot/manage-copilot-memory-in-microsoft-365-copilot>).
  Claude shows a notice each time it stores a sensitive-topic fact: "With the
  setting turned on, **each time Claude saves something on one of these topics
  to memory, you'll see a notice**."
  (<https://claude.com/blog/claudes-memory-works-everywhere-and-you-decide-whats-in-it>)
- **Counter-pressure from Apple:** "Be especially careful to avoid mistakes in
  proactive features ... because people don't request a proactive feature, they
  often have less patience with its mistakes."
  (<https://developer.apple.com/design/human-interface-guidelines/machine-learning>)
  An unprompted "I see you set your state to Washington" is a proactive
  utterance; if the coach mis-states what changed, the cost is higher than
  saying nothing.

**Synthesis (my reading, flagged as such):** the evidenced pattern is a **short,
non-conversational receipt at the point of change** (Copilot's "Memory updated")
plus **use-time acknowledgement** ("Using Washington for in-state pricing")
rather than a proactive greeting-style comment on the next open. Nobody I found
publishes data on which converts better.

---

## 5. Does an explicit editable view measurably improve trust or retention?

**The direct evidence is thin. I found no study measuring trust or retention for
"we added a screen showing what the AI stored about you."** State that plainly
in the brief.

What exists is adjacent and weaker:

- **Google PAIR (industry, non-quantified):** "When users have the right level
  of control over the system, **they're more likely to trust it**." And: "Let
  the user know where they can see their data and where they can change
  data-collection settings. Ideally, do this in-context ... **Don't implicitly
  collect data without telling people. Always provide a way to see, and ideally
  edit, data collected.**"
  <https://pair.withgoogle.com/chapter/feedback-controls/> PAIR states this
  draws on "dozens of Google user research studies ... The details of these are
  proprietary, so they are not included in this list" — i.e. the underlying
  evidence is not inspectable.
- **PAIR, on onboarding specifically:** new users "may want to understand
  **which settings they can edit**, especially those controlling privacy and
  security ... Engage users and give them some control as they get started ...
  Setting the expectation that the system will learn as they teach it can help
  build trust." <https://pair.withgoogle.com/chapter/explainability-trust/>
- **Academic, recommender systems (nearest measured result):** Bostandjiev,
  O'Donovan & Höllerer, _TasteWeights_ (RecSys 2012): "Results of the study
  indicate that **explanation and interaction with a visual representation of
  the hybrid system increase user satisfaction and relevance of predicted
  content**." <http://sites.cs.ucsb.edu/~holl/pubs/Bostandjiev-2012-RecSys.pdf>
  Note carefully: the measured outcomes are **satisfaction and perceived
  relevance**, _not_ trust and _not_ retention, and the domain is music
  recommendation, not household finance.
- **Academic, control preferences are heterogeneous:** Knijnenburg et al., _Each
  to his own: how different users call for different interaction methods in
  recommender systems_ (RecSys 2011): "This paper compares five different ways
  of interacting with an attribute-based recommender system and shows that
  **different types of users prefer different interaction methods.**"
  <https://dl.acm.org/doi/abs/10.1145/2043932.2043960> (abstract via search
  result; ACM DL returned 403 to my client.) Read as: _offering_ both surfaces
  beats _forcing_ either.
- **Regulatory pressure, not user preference:** PAIR notes "regulations in some
  countries may require such specific, contextual explanations and data
  controls" (<https://pair.withgoogle.com/chapter/explainability-trust/>),
  consistent with GDPR Art. 16 above.

**Honest conclusion for the brief:** build the profile screen on correctness,
control, and Apple-convention grounds, and on the "binding config vs. generated
recall" separation OpenAI itself documents. **Do not put a trust or retention
lift in the success criteria as if it were externally evidenced** — it is not.

---

## 6. Anti-patterns, and the risk to "value before ask"

Ranked by how much they actually threaten unicoach's ethos.

1. **A profile screen that becomes a first-run wizard.** This is the main risk,
   and Apple names it twice: "**Postpone nonessential setup flows or
   customization steps.**"
   (<https://developer.apple.com/design/human-interface-guidelines/onboarding>)
   and "**Asking for more data than a feature needs — or asking for data before
   a person shows interest in the feature — can make it hard for people to trust
   your app.**"
   (<https://developer.apple.com/design/human-interface-guidelines/privacy>).
   Mitigation: the screen is reachable, never interposed; it opens with fields
   already filled from conversation and empty fields shown as optional with the
   benefit stated.
2. **Field count is the abandonment driver, not screens.** Baymard Institute:
   "The average checkout flow in 2024 is 5.1 steps long and contains 11.3 form
   fields — and **17% of users have abandoned due to checkout complexity**" and
   "what really matters to the overall UX of checkout is **the number of form
   fields users must consider**", with "many sites ... including too-many form
   fields ... degrading the overall user experience."
   <https://baymard.com/blog/checkout-flow-average-form-fields> Caveat: this is
   e-commerce checkout, a different task with a purchase at the end; do not
   over-transfer. The transferable part is that a three-field profile is a very
   different object from a "complete your profile" questionnaire, and the
   temptation is always to grow it.
3. **Too many settings.** "**Minimize the number of settings you offer** ... too
   many settings can make the experience feel less approachable, while also
   making it hard to find a particular setting."
   (<https://developer.apple.com/design/human-interface-guidelines/settings>)
   Same discipline, restated by Apple.
4. **Putting a frequently changing, task-bound object into a settings screen.**
   Apple: "If you need to offer settings that affect only a specific task, you
   can provide these options within the task itself, so people don't have to
   leave the experience" and "Put general, **infrequently changed** settings in
   your custom settings area." (same URL) The **college list** is the field at
   risk here — it is edited constantly and always in the middle of a coaching
   task. Consider income band + state on the profile screen, and college-list
   editing wherever the list is being discussed.
5. **Duplicated truth.** The Gemini connected-apps warning is the crisp
   statement of it: fixing one copy leaves the other live
   (<https://support.google.com/gemini/answer/16598469?hl=en>). Mitigation: one
   row is the truth; the coach reads it every turn; conversation is a _write
   path into that row_, never a second store.
6. **Freeform correction where a guided one would do.** Apple: "When possible,
   use guided corrections instead of freeform corrections."
   (<https://developer.apple.com/design/human-interface-guidelines/machine-learning>)
   A user who types "actually we're in WA now" into chat is doing a freeform
   correction of a closed-set field. A picker is strictly more reliable — but
   per Apple's own 5.1.1(iv) logic, offer it as the alternative, not the
   mandate.
7. **Degradation on decline must stay real.** Apple's generative-AI guidance:
   "**Ensure a great experience even when generative features aren't available
   or people opt not to use them** ... When possible, consider offering a non-AI
   fallback."
   (<https://developer.apple.com/design/human-interface-guidelines/generative-ai>)
   Applied in reverse for unicoach: a family that never opens the profile screen
   and never states an income band must still get a useful coach. If any answer
   becomes "fill in your profile first", the ethos is broken and 5.1.1(v)/(x)
   get closer.

**Net: the profile screen does not threaten "value before ask" — a profile
_gate_ would.** The evidence supports: invite, prefill from conversation, mark
every field optional, state the benefit of each field at the field, and never
block.

---

## What I could not verify

- **`websearch` was unavailable** (no Serper key). All discovery ran through the
  DuckDuckGo HTML endpoint, which rate-limited repeatedly; several planned
  queries returned nothing and were abandoned (notably: NN/g on conversational
  vs. form input; "progressive profiling" research; practitioner writing on
  dual-entry last-write-wins). **Absence of results here is a search limitation,
  not evidence of absence.**
- **OpenAI**: `help.openai.com` and `openai.com/index/...` both returned **HTTP
  403** to my client, and `web.archive.org` returned **429**. The ChatGPT memory
  settings path ("Settings > Personalization > Manage Memory"), the "Deleting a
  chat doesn't erase its memories" line, and the custom-instructions "applied
  immediately to all chats" line are quoted **from search-result snippets of
  those OpenAI pages**, not from pages I fetched. The `learn.chatgpt.com` quotes
  _are_ first-party pages I fetched.
- **Microsoft HAX toolkit site**
  (`microsoft.com/en-us/haxtoolkit/ai-guidelines/`) returned 403; the guideline
  texts (G16, G17, G18) are quoted from the CHI 2019 camera-ready PDF, from a
  university mirror
  (<https://haoyuma20492350.github.io/data/papers_pdf/AmershiSaleema2019Gfhi.pdf>).
  I checked the mirror's content is the CHI 2019 paper, but it is a mirror.
- **Replika and Headspace help centres** returned 403; those two claims rest on
  search-result titles/snippets only.
- **Duolingo**: I could **not** find a first-party support article for changing
  the daily goal or course. Everything returned was third-party. **Do not cite
  Duolingo as precedent in the brief.**
- **ACM DL** returned 403, so Knijnenburg et al. 2011 is cited from its DL
  abstract as shown in search results, not from the paper.
- **CPRA §1798.106** was read from FindLaw's codification, not from the
  California OAG or the official code site.
- **No study found** measuring trust or retention change from adding a "what the
  AI knows about you" editor. I searched for it and did not find it; I am not
  confident such a study does not exist, only that I did not reach it.
- **No product found** that labels a stored profile fact with its provenance
  ("set by you" vs. "learned from chat"). Again: not found, not proven absent.
- Apple HIG pages were read via Apple's DocC JSON endpoint. The text is Apple's,
  but the JSON strips some inline links and image captions, so a few sentences
  in my extracts end with a dangling reference (rendered above without the link
  target).
