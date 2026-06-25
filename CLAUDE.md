# Project: Android MDM Headwind Fork

This project uses a private local project brain in `brain/` and private Claude project automation in `.claude/`.
Both are local-only and should stay gitignored unless the user explicitly changes that policy.

`.claude/` may contain project-local Claude Code commands, skills, settings, agents, hooks, MCP config, plugin state, logs, and worktrees. Treat it as Claude's local project control plane, not just a commands directory.

Default operating loop: brainstorm → spec → plan → implement → verify → checkpoint.

Before planning or implementation:
1. Brainstorm the idea/scope when the request is fuzzy, using focused questions and clear tradeoffs.
2. Turn the accepted direction into `brain/SPEC.md`.
3. Turn the accepted spec into a concrete implementation plan in `brain/TASKS.md`.
4. Implement only after the user accepts the spec/plan.
5. Read `brain/STATE.md`, `brain/SPEC.md`, and `brain/TASKS.md` before resuming work.

Git checkpoint policy:
- After every meaningful stage, update the brain files, run appropriate verification, then create a git commit without asking the user again.
- Meaningful stages include accepted spec, accepted plan, completed implementation chunk, passing verification, migration/setup milestone, and end-of-session checkpoint.
- Do not commit `brain/` or `.claude/`; they are private/local unless the user explicitly changes that policy.
- Do not push to GitHub or any remote unless the user explicitly asks.
- Use concise commit messages like `stage: accepted spec`, `stage: implement auth flow`, or `fix: repair build`.

Use installed Superpowers skills when relevant instead of duplicating their content:
- brainstorming for unclear ideas or scope discovery
- writing-plans after a design/spec is approved
- executing-plans after plan approval
- test-driven-development for behavior changes and bug fixes
- systematic-debugging for bugs
- verification-before-completion before saying done

Do not treat chat history as the source of truth. Update `brain/STATE.md`, `brain/TASKS.md`, and `brain/CHECKPOINTS.md` after meaningful work.
