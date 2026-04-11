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

### 2. Autonomous Implementation (The "Zero-Shot" Run)

Execute the `Implementation Plan` section of the spec using the following
constraints:

- Ingest context from the spec document and relevant `.agents/skills`.
- **Rule Override**: Unlike the standard `spec-implementation` workflow that
  explicitly requires step-by-step human "LGTMs", you MUST implement the entire
  plan **autonomously**. Do not pause for human review between steps. Complete
  all steps until the codebase aligns completely with your interpretation of the
  specification.

### 3. Verification & Evaluation

- A specification is only proven robust if it passes independent, un-prompted
  verification.
- Upon completing the implementation, **you MUST immediately PAUSE and wait**.
- Inform the human architect that implementation is complete and ask how they
  would like to verify the execution.
- The architect will manually define the evaluation criteria. They may:
  - Provide a test script or command for you to run.
  - Patch in a "Gold Standard" test suite and ask you to execute it.
  - Instruct you to wait while they manually review the codebase correctness.
- Wait for the architect to explicitly state whether the implementation
  **PASSED** or **FAILED**.
- **If tests PASS**: The specification is verified as unambiguous and fully
  context-complete.
- **If tests FAIL**: The implementation run failed. Proceed immediately to
  Step 4.

### 4. Architectural Corrective Loop (Failure Analysis)

If the verification failed, do not write code to fix the failures directly. The
error lies in the specification or the project's skills being ambiguous or
insufficient.

- Analyze the failure feedback. Identify what context, constraint, API boundary,
  or architectural dependency was missing or confusing in the original `spec`
  document or global `skills`.
- Propose amendments to the spec and skills markdown files.
- Go through the standard review cycle with the architect and commit the spec
  and/or skills changes on the verification branch.
- Revert all implementation code changes and go back to Step 2 to begin a new
  verification cycle.
- The process repeats until the verification passes, at which point the
  specification is proven robust and ready for the standard
  `spec-implementation` workflow.
