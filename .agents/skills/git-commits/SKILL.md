---
name: git-commits
description:
  Global baseline philosophies for git commit management and atomic commits.
---

# 🤖 Skill: Git Commit Hygiene

This skill establishes universal constraints regarding how git commits should be
structured and managed by agents working in this repository.

## 📜 Core Philosophy

1. **Atomic, Isolated Commits**
   - NEVER bundle unrelated file changes into a single git commit.
   - Each commit should represent the smallest, logically cohesive unit of work
     possible.
   - _Example_: If you format an agent skill file while also finalizing a
     codebase specification, you MUST split those changes into two distinct
     `git add` and `git commit` executions.

2. **Strict File Scoping**
   - When executing `git add`, explicitly declare the targeted files by path
     rather than using wildcard tracking (avoid `git commit -a` or `git add .`
     unless strictly necessary for a unified feature).
   - Check `git status` prior to committing to ensure unrelated untracked or
     modified files do not unintentionally slip into the staging area.

3. **Component Separation**
   - Separate infrastructure changes (e.g., Dockerfiles, Makefile),
     documentation/specs, and codebase implementation into separate commits. Do
     not merge meta-repository changes (like updating an AI agent skill) with
     feature delivery commits.

4. **Pre-Commit Verification and Approval**
   - BEFORE finalizing and executing any `git commit` command, you MUST
     explicitly present the proposed commit message to the user.
   - You should propose the commit message by writing it to a temporary file
     (e.g., in `/tmp/`) and allow the user to review and edit it if desired.
   - You MUST also display the exact list of files that will be included in the
     commit.
   - Await the user's explicit permission to execute the commit. Do not auto-run
     the commit execution without this explicit verification.
