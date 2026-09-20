---
name: code-review-bracket-serialization
description: Reviews code to ensure values whose boundaries are not otherwise visible — user input and free text — are wrapped in brackets in logs, CLIs and internal exceptions.
implementation_summary: >
  **Dynamic Variable Formatting (Bracket Serialization)**: In non-end-user output (logs, CLIs, internal exceptions), wrap a value in square brackets ([]) when its boundaries are not otherwise visible — user input, free text, anything that can be empty or carry leading or trailing spaces. Example: log-info "Rejected sign-in for [$EMAIL]." Do NOT bracket a self-delimiting value: a number, a timestamp, a UUID, an enum constant, a boolean. Never use single quotes as the delimiter.
---

# 🔍 Code Review: Dynamic Variable Formatting (Bracket Serialization)

**Rewritten 2026-09-20.** The old rule said "ALWAYS wrap dynamic variables" with
no test for which ones — its own example bracketed a timestamp. Brackets exist
to show a boundary the reader cannot otherwise see, so they belong on values
that can be empty or carry leading or trailing spaces: in practice, user input.

You are a code reviewer focusing strictly on clear violations of the following
principle. Do not review for other concerns outside this scope, and do not
report a borderline case.

## 📜 Review Criteria

- Delimit a value whose boundaries the reader cannot otherwise see: anything
  that can be empty, carry leading or trailing whitespace, or contain spaces. In
  practice this is **user input** — an email, a display name, a search query, a
  pasted token, a file path. `Rejected sign-in for []` says something that
  `Rejected sign-in for` does not.
- Do NOT delimit a value whose shape shows its own boundaries: a number, a
  timestamp, a UUID, an enum constant, a boolean. `at epoch 1758240000` is
  correct; `at epoch [1758240000]` is clutter.
- The delimiter is square brackets, never single quotes. Quotes are ordinary
  punctuation in a log sentence, so `user 'bob'` reads as emphasis; `user [bob]`
  cannot.
- Non-end-user output only: logs, CLI messages, internal exceptions. Never in
  copy a family reads.

## 🎯 Review Guidelines

- **Clear-cut cases only:** Flag a user-supplied or free-text value printed with
  no delimiter, or delimited with single quotes, in a log line, a CLI message or
  an internal exception. A self-delimiting value is not a finding either way,
  and end-user copy is out of scope.
- **Code Examples:** When pointing out a flaw, include short code snippets
  demonstrating the violation.

## 🎯 Code Examples

### Example 1: System Logging (e.g., SLF4J / Log4j)

#### ❌ Negative Example (user input undelimited; a self-delimiting value bracketed)

```kotlin
// VIOLATION: `email` is user input — it can be empty or arrive as "  bob@x.com  "
// and the log line will not show it. `timestamp` is a number: it delimits itself,
// so the brackets are clutter.
logger.info("Rejected sign-in for $email at epoch [$timestamp]")
```

#### ✅ Positive Example (brackets where the boundary is invisible, nowhere else)

```kotlin
// ADHERES TO RULE: the trailing space in "  bob@x.com  " is now visible as
// [  bob@x.com  ], and the epoch reads cleanly without a wrapper.
logger.info("Rejected sign-in for [$email] at epoch $timestamp")
```

### Example 2: Internal Exceptions and CLI Errors

#### ❌ Negative Example (single quotes as the delimiter; free text left bare)

```kotlin
// VIOLATION: `query` is a free-text search term and carries no delimiter at all.
// `studentId` is a UUID that needs none, and single quotes are never the
// delimiter here in any case.
throw IllegalStateException("No college matched query $query for student '$studentId'")
```

#### ✅ Positive Example (the free-text value bracketed, the identifier bare)

```kotlin
// ADHERES TO RULE: an empty or space-padded query is now visible, and the UUID
// is left alone.
throw IllegalStateException("No college matched query [$query] for student $studentId")
```

## 📋 Output

Follow the output instructions in your prompt. If it gives none, use
[`findings-output.template.md`](../findings-output.template.md).
