---
name: rfc-pipeline
description: >-
  A master macro skill that orchestrates the entire lifecycle of an RFC from
  design to spec synchronization. It guides the Architect and other agents
  through the phases as a state-machine.
---

# RFC Pipeline Master Skill

This skill acts as the master orchestrator for the entire Spec-Driven Development lifecycle of an RFC. It is a **state-machine guide** that walks the human Architect and various LLM personas through the process in strict sequential order.

## Critical Behaviours

- **Context Window Protection**: Do NOT attempt to execute all of these steps autonomously inside a single, continuous context window. To maintain high code quality and prevent context bloat, you must explicitly pause and notify the Architect at the end of each phase, prompting them to initiate the next phase (which helps enforce context resets).
- **Strict Ordering**: You must enforce the lifecycle phases sequentially. Do not jump to implementation before design is approved.

## The Pipeline Lifecycle

Guide the Architect through the following phases one by one:

### Phase 1: Design
1. **Interactive Design**: Inform the Architect they should run the `rfc-design` skill to collaboratively draft the initial RFC. Pause and wait for this to complete.
2. **Autonomous Review Loop**: Instruct the Architect to run the `rfc-review-loop` on the drafted RFC. Pause and wait for this to complete.
3. **Architect Decision**: After the loop finishes, explicitly ask the Architect: *"Are you satisfied with the RFC design, or should we run another review loop?"* Proceed to Implementation only when they explicitly approve.

### Phase 2: Implementation
4. **Autonomous Implementation**: Instruct the Architect to run the `rfc-impl` skill to execute the RFC's Implementation Plan in a single shot. Pause and wait for this to complete.
5. **Autonomous Implementation Review**: Instruct the Architect to run the `rfc-impl-review-loop` to adversarially review and automatically fix the implementation. Pause and wait for this to complete.

### Phase 3: Finalization & Specs
6. **Code Approval & Commit**: Explicitly instruct the Architect to review the uncommitted changes in their working tree. Ask them to verify everything locally and manually `git commit` the code once they approve. Wait for their confirmation that the code is committed.
7. **Specification Synchronization**: For every directory that was modified during the implementation phase, instruct the Architect to run the `spec-sync-loop` skill. This will ensure that all `SPEC.md` files are strictly verified and updated via interactive `spec-editor` sessions.
