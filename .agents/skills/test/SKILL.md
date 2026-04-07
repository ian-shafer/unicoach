---
name: test
description:
  Test-driven development guidelines for resolving bugs.
---

# 🤖 Skill: Test-Driven Bug Fixing

This skill establishes the universal workflow for resolving bugs in the codebase. You MUST adhere to this test-driven approach to ensure strict validations against regressions.

## 📜 Core Philosophy

1. **Write a Failing Test First**
   - BEFORE altering any application code to fix a reported bug, you MUST write an automated test that explicitly exercises the broken behavior.
   - The test MUST fail.
   - You MUST run the test and verify its failure to confirm that the test accurately models the error.

2. **Fix the Bug**
   - After (and only after) proving the test fails, you may modify the application logic to address the flaw.

3. **Verify Success**
   - Re-run the test suite to ensure the previously failing test now passes successfully, and that no other adjacent tests have experienced a regression.
