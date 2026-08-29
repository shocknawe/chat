# AGENTS.md

## Agent configuration

`.agents/` is the single source of truth for all AI agent configuration in this
repository. Tool-specific directories hold relative symlinks back into it — with
one exception, no file is duplicated.

```
.agents/
├── agents/     # Agent definitions (one .md per agent)
├── skills/     # Skills (one directory per skill, each with SKILL.md)
├── commands/   # Slash commands / reusable command definitions
├── rules/      # Coding standards, review rules, guardrails
└── prompts/    # Standalone prompt templates and snippets
```

**Use the agents and skills defined there.** They carry the depth for their
areas — invoke the matching skill rather than reimplementing what it covers from
memory.

- **Skills** — `.agents/skills/` is discovered natively by Codex and Copilot, and
  reached by Claude Code through `.claude/skills`.
- **Agents** — canonical in `.agents/agents/`; Claude Code reads them through
  `.claude/agents`, Copilot through thin wrappers in `.github/agents/`.
- **Commands** — canonical in `.agents/commands/`; exposed to Claude Code via
  `.claude/commands` and to Copilot via `.github/prompts/*.prompt.md`.

## Editing it

Add or edit files **only under `.agents/`**. Never write into `.claude/`,
`.github/prompts/`, or `.github/copilot-instructions.md` — those resolve through
symlinks, so a write lands in the canonical tree anyway, and creating a real file
next to a symlink is how the trees drift apart.

The one exception is `.github/agents/*.agent.md`: Copilot's frontmatter schema is
incompatible with Claude's, so those wrappers duplicate the `name` and
`description` only. Mirror a changed `description` into them; never the body.

Full details in [.agents/README.md](.agents/README.md).
