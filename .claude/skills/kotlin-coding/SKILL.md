---
name: kotlin-coding
description:
  Kotlin-specific coding constraints and best practices.
---

# 🤖 Skill: Kotlin Coding

This skill establishes Kotlin-specific constraints and best practices building upon the general principles found in `coding/SKILL.md`.

## 📜 Core Philosophy

1. **Never Use Empty Strings as Sentinels**
   - Because Kotlin is strictly null-safe, you must NEVER use the empty string
     (`""`) to represent an uninitialized or missing state.
   - You must explicitly define parameters and variables as nullable (`String?`)
     and use `null` as the default value when data may legitimately be absent.
   - *Example Formulation*: `fun example(name: String? = null)` instead of
     `fun example(name: String = "")`.
