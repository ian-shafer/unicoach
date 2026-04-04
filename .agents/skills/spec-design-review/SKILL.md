---
name: spec-design-review
description: >-
  Interactively reviews spec designs to ensure they meet the standards defined
  in spec-design/SKILL.md. Use when reviewing a new feature specification,
  checking for required headers, analyzing design depth, or verifying
  implementation plans.
---

# Spec Design Review

Skill to help review a spec design interactively.

## Critical Behaviors

•⁠  ⁠*IMPORTANT*: Do NOT just agree with the author. Push back. Be radically honest. Point out blind spots, mistakes, and errors.
•⁠  ⁠You must read the spec design file provided by the user.
•⁠  ⁠Do NOT go through the review process step-by-step asking one question at a time. Generate a comprehensive, structured review of the entire spec design at once.
•⁠  ⁠Categorize findings clearly (e.g., Critical, Major, Minor, Nit).
•⁠  ⁠After presenting the full review, ask the user how they want to proceed. Offer these specific options:
    1. Go through the review points one-by-one.
    2. Let the user edit the spec markdown file manually.
    3. Let the user drive the conversation ad-hoc style.
•⁠  ⁠Be clear, concise, and to the point.
•⁠  ⁠The tone must be highly technical, dry, and objective.

## The Review Process

You MUST evaluate the spec against the following criteria to build your review.

 1.⁠ ⁠*Read the Spec*: Identify the spec file. If the user has already provided the file content or path in the conversation, use it immediately. Only ask for the path if it is missing from the current context.
 2.⁠ ⁠*Verify Headers*: Check if the spec has the exact required headers (note that extra sections are allowed):
   - ⁠ ## Executive Summary ⁠
   - ⁠ ## Detailed Design ⁠
   - ⁠ ## Tests ⁠
   - ⁠ ## Implementation Plan ⁠
   - ⁠ ## Files Modified ⁠
   If any are missing or incorrect, note this in your review findings (categorized as Critical or Major) and proceed with the rest of the review. Do not stop to ask the user.
 3.⁠ ⁠*Review Executive Summary*: Check if it's short (250 words max) and answers: Why and how are we building this?
 4.⁠ ⁠*Review Detailed Design*: Check if it covers the following and flag these specific anti-patterns:
   - *Data Models*: Are field types, nullability, and relationships (FKs) explicitly defined? Flag missing indexes for queried fields or use of overly broad types.
   - *API Contracts*: Are exact request/response schemas and methods/RPCs specified? Flag ⁠ List ⁠ endpoints missing pagination, or state mutations using ⁠ GET ⁠.
   - *Error Handling*: Does it specify behavior for invalid inputs, dependency failures, and timeouts? Flag vague "returns error" statements; demand specific error codes or failure modes.
   - *Dependencies*: Are all services/libraries listed with their purpose and identified as new or existing?
 5.⁠ ⁠*Review Tests*: Check if it specs out every individual test that will be implemented. Also check that there are no missing tests (i.e., important functionality that is not tested).
 6.⁠ ⁠*Review Implementation Plan: Check if steps are atomic and sequential. Verify that each step is **locally verifiable* by requiring a specific verification action (e.g., running a test, checking a log). Flag vague "refactor" steps.
 7.⁠ ⁠*Review Files Modified*: Check if all files listed use exact paths relative to project root. You MUST use ⁠ code_search ⁠ or other file-viewing tools to verify that files listed as new do not exist, and files listed as modified or deleted already exist. Use search tools to check if any dependencies or configs are missing. Do not trust the spec's claims blindly.
 8.⁠ ⁠*Completeness Check (Semantic Cross-Referencing)*: Do not just map bullet points; evaluate sufficiency and logical consistency:
   - *Design vs. Files: Are the files listed in ⁠ Files Modified ⁠ *actually sufficient to implement the design? If they describe a new feature, did they forget to list the configuration or client library files that need updates? Flag missing files that are logically required.
   - *Design vs. Tests*: Verify that every edge case mentioned in ⁠ Detailed Design ⁠ (e.g., "retries on timeout") has a specific test case in ⁠ Tests ⁠. Do not accept generic "unit tests will be added" statements.
   - *Implied Dependencies*: If the design implies changes to other systems or libraries not mentioned in the spec, flag this as a critical missing detail.
 9.⁠ ⁠*Tone Check: Scan for subjective "fluff" words (e.g., "elegant", "robust"). More importantly, flag **lack of quantification*. If the author says "low latency", demand a target in milliseconds. If they say "handles high load", demand queries-per-second (QPS) targets. Flag any hand-wavy or non-committal language.
10.⁠ ⁠*Advanced Considerations*: Check if the spec should have addressed the following, and if missing, suggest them as "Considerations for the Author" (not as critical failures):
    - *Rollback Plan*: How to undo the changes if they fail in production.
    - *Data Migration*: How to handle existing data if schemas are changing.
    - *Performance/Scalability*: Impacts on latency, throughput, or resource usage.
    - *Security/Privacy*: Handling of PII or access control.
11.⁠ ⁠*Provide Summary*: Summarize all findings and ask the user for final feedback.
