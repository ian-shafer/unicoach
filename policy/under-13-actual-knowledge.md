# Under-13 actual knowledge: the escalation runbook

unicoach is a general-audience service. COPPA reaches it only through **actual
knowledge** that it is collecting personal information from a child under 13.
The neutral date-of-birth question at student creation (RFC 222) keeps us out of
that regime at the front door. This file is the back door: what to do when we
learn, after the fact, that an account holder is under 13.

It is a **human runbook**, not code. No automation implements it today.

**Owner of every step below: Ian.**

## SIGNAL

A credible indication that an account holder is under 13. For example:

- a parent writing "my 12-year-old" in chat, email, or a support thread;
- a support email from anyone stating the student's age;
- a self-report in a coaching conversation.

"Actual knowledge" is a knowledge standard. **The day the operator learns is the
day the obligation attaches**, so the signal starts the clock — not the
confirmation, and not the investigation.

## 1. SUSPEND

Suspend the account **on the signal**, before any investigation. Suspension is
reversible; continued collection from a child is not.

**The mechanism that exists today** is the admin tool's soft delete on the
`users` row (`UsersResource`, `delete`). A soft-deleted user is refused at
authentication — `AuthService` resolves it as `Disabled` — and the same screen's
`undelete` puts it back. There is no separate "suspended" state, and this
runbook does not ask for one: soft delete already does the two things suspension
needs, which are to stop the account working and to be reversible.

## 2. CONFIRM

Ask the account holder to confirm the date of birth, and ask the parent too if a
parent link exists. State a deadline in the request.

## 3. RESOLVE

- **Confirmed 13 or older** — restore the account with `undelete` on the same
  admin screen, and record the confirmed date **in the support thread**. Note
  what the product cannot do: the stored `date_of_birth` is written once, at
  creation, and has no edit screen (RFC 222), so a wrong stored date is not
  corrected in place. If the stored date must change, the account is deleted and
  created again.
- **Confirmed under 13, or no answer by the deadline** — delete the account and
  its data through the existing account-deletion path, and tell the parent it
  was deleted.

## What this file does not cover

- The parent side. A parent account is an adult account; COPPA's concern is the
  child.
- Any re-ask, lockout, or memory of a refusal at the signup screen. A neutral
  screen is what the standard asks for; this runbook is what catches the rest.
