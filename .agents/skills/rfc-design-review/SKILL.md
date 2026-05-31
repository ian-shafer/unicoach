---
name: rfc-design-review
description: >-
  Interactively reviews RFC designs to ensure they meet the standards defined
  in rfc-design/SKILL.md. Use when reviewing a new feature RFC,
  checking for required headers, analyzing design depth, or verifying
  implementation plans.
---

# RFC Design Review

Adversarial review of an RFC design against the standards in
`rfc-design/SKILL.md`.

## Behavioral Constraints

- **Adversarial Posture**: Do NOT agree with the author by default. Push back.
  Be radically honest. Surface blind spots, mistakes, and logical gaps.
- **Single-Pass Report**: Do NOT walk through findings one-by-one in an
  interview style. Produce the report (see §11 Report Output) as a single,
  complete output.
- **Categorized Findings**: Classify every finding as one of: `Critical`,
  `Major`, `Minor`, `Nit`.
- **Actionable Options**: For each finding, provide at least 2 distinct
  resolution options. Explicitly recommend one.
- **Tone**: Highly technical, dry, objective. Zero fluff. Zero subjective
  adjectives.
- **Acceptance of N/A**: While all required headers must be present, it is generally acceptable for the content of a section to be `N/A` if it is genuinely not applicable. **However, the `Detailed Design` section is a strict exception: it should almost never be N/A. Flag `Detailed Design` as `Critical` if it is marked `N/A` unless the objective is purely exploratory and no concrete design information exists.** For other sections, do not flag `N/A` as lacking detail, incomplete, or missing if it avoids unnecessary fluff.

## Post-Review Interaction

After presenting the full review, ask the user how they want to proceed.
Present the options below as a **numbered selection widget** so the user can
pick one:

1. Walk through findings one-by-one.
2. Reviewer implements accepted recommendations using the `rfc-design`
   skill (`rfc-design/SKILL.md`). In this mode, read and follow the
   `rfc-design` skill, then apply accepted findings directly to the RFC
   file. Before starting, ask the user which findings to implement. Accept
   any of the following responses:
   - **All**: Implement every finding.
   - **Severity threshold**: e.g., "Minor and above" (= Critical + Major +
     Minor), "Major and above" (= Critical + Major), "Critical only".
   - **Individual selection**: User specifies finding IDs (e.g., "F1, F3,
     F5").
3. **PASS verdict only**: Ignore remaining findings and mark the RFC as ready
   for implementation. Only present this option when the verdict is `PASS`
   (zero Critical, zero Major).

## The Review Process

Evaluate the RFC against every criterion below. Do not skip steps.

### 1. RFC Identification

Identify the RFC file. If the user has already provided the file content or
path, use it immediately. Only ask for the path if it is absent from the current
context.

### 2. Header Verification

Verify the RFC contains these exact required headers:

- `## Executive Summary`
- `## Detailed Design`
- `## Tests`
- `## Implementation Plan`
- `## Files Modified`

Extra sections are permitted. Missing or misspelled required headers: flag as
`Critical` or `Major`. Do not stop to ask the user — proceed with the remaining
review. Note: If a required header is present but its content is simply `N/A`, this is perfectly acceptable provided the section is legitimately not applicable, **with the strict exception of `Detailed Design` which must almost never be N/A (unless purely exploratory). Flag an invalid `N/A` in Detailed Design as `Critical`.** Do not flag valid `N/A`s as missing or incomplete.

### 3. Executive Summary

Verify:

- Length ≤ 250 words.
- Answers: _Why_ are we building this? _How_ are we building it?

### 4. Detailed Design

Evaluate depth across these axes and flag the listed anti-patterns:

- **Structural Specifications Only**: Ensure that the RFC strictly excludes concrete implementation code (e.g., function bodies, control flow, markup templates, stylesheet rules). Verify that design specifications are restricted to declarative schemas and interfaces: API signatures, structural type definitions, database schemas, protobuf definitions, configuration schemas, etc. Flag any concrete implementation code as a `Major` finding.


- **Data Models**: Field types, nullability, and relationships (FKs) must be
  explicit. Flag missing indexes on queried fields. Flag overly broad types.
- **API Contracts**: Exact request/response schemas and methods must be
  specified. Flag `list` endpoints missing pagination. Flag state mutations
  using `GET`.
-   **Error Handling**: Behavior for invalid inputs, dependency failures, and
    timeouts must be specified. Flag vague "returns error" statements — demand
    specific error codes and failure modes.
- **Dependencies**: All services and libraries must be listed with their
  purpose and identified as new or existing. **You MUST perform a web search**
  to verify the latest stable release of every listed dependency. Flag outdated
  versions as `Minor` or `Major` based on criticality and version deviation.

### 5. Tests

Verify:

- Every individual test case is explicitly specified (no generic "unit tests
  will be added" statements).
- No missing tests — cross-reference `Detailed Design` for untested
  functionality.

### 6. Implementation Plan

Verify:

- Steps are atomic and sequential.
- Each step specifies a concrete verification action (e.g., run a test, check
  a log).
- Flag vague "refactor" steps with no clear deliverable.

### 7. Files Modified

Verify:

- All paths are exact, relative to the project root.
- Files listed as **new** do not already exist.
- Files listed as **modified** or **deleted** already exist.
- Use search tools to check for missing dependencies or configurations. Do not
  take the RFC's claims at face value.

### 8. Completeness Check (Semantic Cross-Referencing)

Go beyond bullet-point mapping. Evaluate sufficiency and logical consistency:

- **Design → Files**: Are the files in `Files Modified` actually sufficient to
  implement the design? Flag logically required files that are missing (e.g.,
  configuration, client libraries, routing modules).
- **Design → Tests**: Every edge case in `Detailed Design` (e.g., "retries on
  timeout") must have a corresponding test case in `Tests`.
- **Implied Dependencies**: If the design implies changes to systems or
  libraries not mentioned in the RFC, flag as `Critical`.

### 9. Tone Check

- Flag subjective fluff words (e.g., "elegant", "robust", "seamless").
- Flag lack of quantification: "low latency" → demand a target in
  milliseconds. "Handles high load" → demand QPS targets.
- Flag hand-wavy or non-committal language.

### 10. Advanced Considerations

Check whether the RFC should have addressed the following. Flag as
"Considerations for the Author" (not as critical failures):

- **Rollback Plan**: How to undo the changes if they fail in production.
- **Data Migration**: How to handle existing data if schemas change.
- **Performance/Scalability**: Explicitly **IGNORE** minor (and some major)
  performance concerns at this stage. Do not flag.
- **Security/Privacy**: Explicitly **IGNORE** PII and security concerns at
  this stage. Do not flag or evaluate.

### 11. Report Output

The report is the **sole output** of the review. Do not produce incremental
commentary — go straight to the report. Use this exact template:

```
# 🔍 RFC Review: <RFC Title>

**Verdict**: <✅ PASS | 🔧 NEEDS WORK>

| 🔴 Critical | 🟠 Major | 🟡 Minor | ⚪ Nit |
|-------------|----------|----------|--------|
| N           | N        | N        | N      |

## 📋 Findings

| #  | Sev | Finding | Options |
|----|-----|---------|---------|
| F1 | 🔴  | <one-line description> (§<N>) | ⭐ O1) <option><br>O2) <option> |
| F2 | 🟠  | ... | ... |
| F3 | 🟡  | ... | ... |
| F4 | ⚪  | ... | ... |

<Order rows by severity: 🔴 → 🟠 → 🟡 → ⚪>

<If PASS with zero findings: replace table with single line —
"No findings. RFC is complete and ready for implementation.">

## 📎 Detail

<Optional. Only include this section for findings that need more explanation
than fits in a table cell. Reference by finding ID.>

### F1: <Short title>

<Extended rationale, code snippets, or examples.>
```

Rules:

- **✅ PASS**: Zero `Critical` and zero `Major` findings.
- **🔧 NEEDS WORK**: One or more `Critical` or `Major` findings.
- After the report, present the post-review interaction options (see
  **Post-Review Interaction** above).
