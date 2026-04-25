---
name: spec-design-review
description: >-
  Interactively reviews spec designs to ensure they meet the standards defined
  in spec-design/SKILL.md. Use when reviewing a new feature specification,
  checking for required headers, analyzing design depth, or verifying
  implementation plans.
---

# Spec Design Review

Adversarial review of a spec design against the standards in
`spec-design/SKILL.md`.

## Behavioral Constraints

- **Adversarial Posture**: Do NOT agree with the author by default. Push back.
  Be radically honest. Surface blind spots, mistakes, and logical gaps.
- **Single-Pass Review**: Do NOT walk through findings one-by-one in an
  interview style. Generate a comprehensive, structured review of the entire
  spec in a single output.
- **Categorized Findings**: Classify every finding as one of: `Critical`,
  `Major`, `Minor`, `Nit`.
- **Actionable Options**: For each finding, provide at least 2 distinct
  resolution options. Explicitly recommend one.
- **Tone**: Highly technical, dry, objective. Zero fluff. Zero subjective
  adjectives.

## Post-Review Interaction

After presenting the full review, ask the user how they want to proceed.
Present the options below as a **numbered selection widget** so the user can
pick one:

1. Walk through findings one-by-one.
2. Reviewer implements accepted recommendations using the `spec-design`
   skill (`spec-design/SKILL.md`). In this mode, read and follow the
   `spec-design` skill, then apply accepted findings directly to the spec
   file. Before starting, ask the user which findings to implement. Accept
   any of the following responses:
   - **All**: Implement every finding.
   - **Severity threshold**: e.g., "Minor and above" (= Critical + Major +
     Minor), "Major and above" (= Critical + Major), "Critical only".
   - **Individual selection**: User specifies finding IDs (e.g., "F1, F3,
     F5").
3. **PASS verdict only**: Ignore remaining findings and mark the spec as ready
   for implementation. Only present this option when the verdict is `PASS`
   (zero Critical, zero Major).

## The Review Process

Evaluate the spec against every criterion below. Do not skip steps.

### 1. Spec Identification

Identify the spec file. If the user has already provided the file content or
path, use it immediately. Only ask for the path if it is absent from the current
context.

### 2. Header Verification

Verify the spec contains these exact required headers:

- `## Executive Summary`
- `## Detailed Design`
- `## Tests`
- `## Implementation Plan`
- `## Files Modified`

Extra sections are permitted. Missing or misspelled required headers: flag as
`Critical` or `Major`. Do not stop to ask the user — proceed with the remaining
review.

### 3. Executive Summary

Verify:

- Length ≤ 250 words.
- Answers: _Why_ are we building this? _How_ are we building it?

### 4. Detailed Design

Evaluate depth across these axes and flag the listed anti-patterns:

- **Data Models**: Field types, nullability, and relationships (FKs) must be
  explicit. Flag missing indexes on queried fields. Flag overly broad types.
- **API Contracts**: Exact request/response schemas and methods must be
  specified. Flag `list` endpoints missing pagination. Flag state mutations
  using `GET`.
- **Error Handling**: Behavior for invalid inputs, dependency failures, and
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
  take the spec's claims at face value.

### 8. Completeness Check (Semantic Cross-Referencing)

Go beyond bullet-point mapping. Evaluate sufficiency and logical consistency:

- **Design → Files**: Are the files in `Files Modified` actually sufficient to
  implement the design? Flag logically required files that are missing (e.g.,
  configuration, client libraries, routing modules).
- **Design → Tests**: Every edge case in `Detailed Design` (e.g., "retries on
  timeout") must have a corresponding test case in `Tests`.
- **Implied Dependencies**: If the design implies changes to systems or
  libraries not mentioned in the spec, flag as `Critical`.

### 9. Tone Check

- Flag subjective fluff words (e.g., "elegant", "robust", "seamless").
- Flag lack of quantification: "low latency" → demand a target in
  milliseconds. "Handles high load" → demand QPS targets.
- Flag hand-wavy or non-committal language.

### 10. Advanced Considerations

Check whether the spec should have addressed the following. Flag as
"Considerations for the Author" (not as critical failures):

- **Rollback Plan**: How to undo the changes if they fail in production.
- **Data Migration**: How to handle existing data if schemas change.
- **Performance/Scalability**: Explicitly **IGNORE** minor (and some major)
  performance concerns at this stage. Do not flag.
- **Security/Privacy**: Explicitly **IGNORE** PII and security concerns at
  this stage. Do not flag or evaluate.

### 11. Summary

Conclude the review with a structured summary using this template:

```
## Review Summary

**Verdict**: <✅ PASS | 🔧 NEEDS WORK>

| Severity | Count |
|----------|-------|
| 🔴 Critical | N     |
| 🟠 Major    | N     |
| 🟡 Minor    | N     |
| ⚪ Nit      | N     |

### 📋 Key Findings

<If NEEDS WORK: bulleted list of all Critical and Major findings, one line
each. Reference the section number (e.g., "§4 Detailed Design") where each
finding was raised.>

<If PASS: single line — "No critical or major findings. Spec is complete
and ready for implementation.">

### 📝 Detailed Findings

#### <Fn>. <Short title> [<Severity>] (§<Section number>)

**Finding**: <One-line description of the issue.>

**Options**:
1. <Resolution option A.>
2. <Resolution option B.>

**Recommendation**: Option <1|2> — <brief rationale>.

<Repeat for every finding, ordered by severity: Critical → Major → Minor →
Nit.>
```

Rules:

- **PASS**: Zero `Critical` and zero `Major` findings.
- **NEEDS WORK**: One or more `Critical` or `Major` findings.
- After the summary, present the post-review interaction options (see
  **Post-Review Interaction** above).
