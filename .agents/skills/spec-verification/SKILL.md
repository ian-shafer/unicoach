---
name: spec-verification
description:
  Formalizes a feedback loop for testing if a specification document plus agent
  skills are robust and complete enough to be implemented by an LLM in a single
  autonomous shot, using test suites as the objective truth.
---

# Spec and Skill Verification

This skill formalizes an adversarial workflow to evaluate the completeness and
clarity of a design specification and agent skills. By forcing an LLM to
implement a spec entirely autonomously—and comparing the output against a
gold-standard test suite—we evaluate the _spec and agent skills_, not the LLM's
guessing ability.

If the generated code fails the tests, the spec and/or `.agents/skills` should
be updated to resolve the failures.

## The Verification Workflow

You must follow this strict loop when evaluating a specification. **Crucially:
Never fix failing implementation code manually during this process.** Any
failures must be solved by improving the upstream documentation.

In fact, much of this process should be automated by this skill and the LLM. The
human architect should kick off this process, review changes to the spec and
skills, and then kick off another iteration of the implementation and test loop.

### 1. Branching & Context Preparation

(Often initiated by the human architect prior to invocation, but ensure you
understand the state):

- Ensure the base commit contains the finalized `[spec file]` and any
  `.agents/skills` updates, but is strictly **before** any implementation code
  was committed.
- The working branch should be isolated for verification (e.g.,
  `spec:[name]:impl-verify`).
- The architect must explicitly provide the name of the branch containing the
  **"golden implementation"** when kicking off this skill.

### 2. Autonomous Implementation (The "Single-Shot" Run)

Execute the `Implementation Plan` section of the spec using the following
constraints:

- Ingest context from the spec document and relevant `.agents/skills`.
- **Single-Shot Implementation**: Follow the `spec-implementation` workflow
  using its **"single shot implementation"** mode. You must execute all steps
  autonomously without stopping for human review or approval between steps.
  Complete all steps until the codebase aligns completely with your
  interpretation of the specification. **Note**: All tests should pass (e.g., by
  running `bin/test`) before exiting this step.

### 3. Golden Comparison & Verification

A specification is only proven robust if its autonomous implementation matches
or exceeds the quality of the architect's reference code (the "golden
implementation").

1. **Compare Implementations**: Upon completing the single-shot implementation,
   compare your newly generated codebase against the provided golden
   implementation branch (e.g., by checking it out or running an exhaustive
   diff).
2. **Prepare a Verification Report**: Generate a detailed report to present to
   the architect. Format the report using the following rules:
   - Make each discovered discrepancy a clearly labeled section.
   - Describe the difference between the new and golden implementations using
     clear code examples for both.
   - For each section, provide at least **two separate options** to fix the
     discrepancy (either by updating the spec or tweaking global skills) and
     explicitly **recommend one**.
   - **Important Quality Clause**: The golden implementation is not infallible.
     If you find that your spec-driven implementation is actually safer,
     cleaner, or more standard than the golden implementation, explicitly call
     this out. In such cases, options or recommendations for "fixing" it are not
     required.
   - **Production Readiness Assessment**: Add a final conclusion to the report
     explicitly evaluating whether the new autonomous implementation exceeds the
     golden implementation with respect to overall production-readiness. Since a
     core goal of this autonomous verification process is to generate an
     objectively higher-quality implementation than the human-authored reference
     using just the spec and skills, specifically call out any areas where the
     autonomous run adopted safer, more robust, or more maintainable
     architectures.
3. **Prompt for Next Steps**: At the end of the report, present these specific,
   exact options to the architect and wait for their decision:
   - "Move forward making the recommended spec and skill changes"
   - "Go through each point, one-by-one"
   - "Allow the architect to drive the changes ad hoc"

- **If no changes are needed**: The specification is verified as
  context-complete.
- **If discrepancies must be fixed**: Proceed immediately to Step 4.

### 4. Architectural Corrective Loop (Failure Analysis)

If the comparison reveals that the implementation failed to meet the golden
standard, **never fix the implementation code manually**. The error lies in the
specification or the project's skills being ambiguous or insufficient.

- Based on the architect's decision from your Verification Report, apply the
  selected amendments to the `spec` and `.agents/skills` markdown files.
- Go through the standard review cycle with the architect and commit the spec
  and/or skills changes on the verification branch.
- Revert all implementation code changes and go back to Step 2 to begin a new
  verification cycle.
- The process repeats, going back to "Single-Shot Implementation", until the
  verification passes, at which point the specification is proven robust and
  ready for the standard `spec-implementation` workflow.
