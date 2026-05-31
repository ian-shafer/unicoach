---
name: agent-learning
description:
  Updates or creates agent skills based on conversation learnings. Use when a
  user asks to remember a lesson, codify rules, or document architectural
  decisions.
---

# Skill: Agent Learning

**DESCRIPTION** Triggers when the user requests to "remember what you've
learned" or "codify this conversation". Extracts architectural decisions and
updates skill files to ensure permanent retention.

**EXECUTION FLOW**

1. **ANALYZE:**
   - Scan the current conversation for friction points and architectural
     alignments.
   - Distill findings into actionable rules. These rules should not be overly
     specific to the current conversation, but should be general enough to be
     applicable to future conversations.

2. **PLAN:**
   - Output an `implementation_plan` artifact detailing the extracted rules.
   - Propose exactly which existing `SKILL.md` files to update, or if new skill
     files are required.
   - STOP and wait for user approval.

3. **EXECUTE:**
   - Upon approval, update or create the targeted `SKILL.md` files.
   - Commits the skill updates in an isolated commit.
