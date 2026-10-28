---
name: deploy
description: >-
  Ships unicoach to production in one phrase: the backend (bin/deploy prod,
  waited to completion and health-checked) and the iOS app (bin/release-ios
  prod, uploaded to TestFlight), backend first, stopping at the first failure,
  and reports the verified result. Use when Ian says "deploy", "deploy the
  backend", "deploy iOS", "deploy the backend and iOS", "release to
  TestFlight", "ship to prod", "push a build", or invokes /deploy. The phrase
  is the approval: never ask before starting.
---

# deploy

One phrase in, a verified release out. The reliability lives in the two scripts;
this skill only sequences them, keeps their logs, and relays what they say.
Every message to Ian follows
[`.prime/agent/skills/shared/operator-comms.md`](../../../.prime/agent/skills/shared/operator-comms.md):
a triage label first, the outcome first, exact names unchanged.

## Targets

`backend`, `ios`, or both. Both is the default; "deploy the backend" is backend
only; "deploy iOS" / "release to TestFlight" / "push a build" is iOS only.
Backend first, then iOS, so the app never ships against an API it is ahead of.
Stop at the first failure; never start iOS after a failed backend.

## Non-negotiables

1. **Never ask before starting.** The phrase is the approval. Open with a
   `WORKING` line and go.
2. **Both scripts refuse unless the checkout is on `main`, clean, and equal to
   `origin/main`.** That check is theirs, not yours: relay the refusal verbatim
   with its fixing command and stop. Never pass `-f` on your own; `-f` is Ian's
   word, spoken in the same message, and is reported in the result.
3. **`bin/release-ios` runs under system Xcode, directly — never via
   `nix develop -c`.** `bin/deploy` always runs via `nix develop -c`.
4. **Never re-run an upload with the same build number without reading the
   error.** App Store Connect rejects a duplicate `CFBundleVersion`; the build
   number is the commit count on `main`, so a retry needs a new commit on `main`
   or `UNICOACH_BUILD_NUMBER` — Ian's call, asked as a decision block.

## Preflight (the skill's own, before any script)

The session must be in the root checkout of the unicoach repository, not a
linked worktree. In the session's working directory, the two paths
`git rev-parse --git-dir` and `git rev-parse --git-common-dir` print must
resolve to the same directory (a worktree's git-dir is `.git/worktrees/<name>`
under the root checkout; the root checkout's is its own `.git`), and the
basename of `git rev-parse --show-toplevel` must be `unicoach`; that top level
is the directory every command below runs in. Anything else: stop with `BLOCKED`
and say to start the session in the root checkout — a release from a worktree
would ship whatever branch it holds. Everything else — branch, cleanliness,
origin, AWS account, signing, credentials — is the scripts' job.

Create the run's log directory once: `RUN=.scratch/deploy/<UTC timestamp>`
(`date -u +%Y%m%dT%H%M%SZ`), `mkdir -p "$RUN"`. `.scratch/` is gitignored.

## Backend

    nix develop -c bin/deploy prod > "$RUN/backend.log" 2>&1

Run it in the background and wait for it; it builds, bundles, uploads, sends the
SSM Run Command, polls it to a terminal status, prints the on-instance output,
exits with the on-instance exit code if the status is not `Success`, then polls
`/healthz` on the three public URLs the env's infra outputs publish (the API,
public-web and admin-web hosts) until each answers 200. Its last `log-info` line
names the bundle id, the SHA and the three URLs; quote it. While it runs, send
one `WORKING` update when it reaches the SSM wait (the "dispatched; polling"
line), none otherwise.

## iOS

    bin/release-ios prod > "$RUN/ios.log" 2>&1

Run it in the background and wait for it. It archives, signs, and uploads to App
Store Connect; the build number is in its "Archiving ... [build=N]" line. A
successful upload means App Store Connect has the build; its processing is
asynchronous and this skill does not verify it.

## Report

`DONE` on success, with: the SHA shipped, the bundle id, the SSM status, the
three health results, the TestFlight build number, and the note that App Store
Connect processing is asynchronous and unverified. Then the two log paths.

On failure the label is `DONE` (abandoned) or `BLOCKED` (something outside the
run must change), then: which step failed, the exact failing lines from the log
verbatim, the log path, and what Ian must do — in a `YOUR DECISION REQUIRED`
block when there is a choice (a `-f` ship, a new build number), as a plain
instruction when there is not (`git pull --ff-only`).

## Out of scope

App Store review submission, TestFlight tester distribution, and verifying App
Store Connect processing through its API. Say so in the report only when Ian's
phrase asked for one of them.
