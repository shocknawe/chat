# Shared AI Agent Configuration

`.agents/` is the **single source of truth** for all AI agent configuration in this
repository. Claude Code, OpenAI Codex, and GitHub Copilot all resolve back to it —
by native discovery where the tool supports it, by relative symlink where it does
not. Only one thing in the whole tree is duplicated, and it is called out below.

## Layout

```
.agents/
├── agents/     # Canonical agent definitions (one .md per agent)
├── skills/     # Canonical skills (one directory per skill, each with SKILL.md)
├── commands/   # Slash commands / reusable command definitions
├── rules/      # Coding standards, review rules, guardrails
├── prompts/    # Standalone prompt templates and snippets
└── README.md   # This file
```

## How each tool finds it

| | Claude Code | Codex | Copilot |
| --- | --- | --- | --- |
| **Skills** | `.claude/skills` → symlink | **reads `.agents/skills` natively** | **reads `.agents/skills` natively** |
| **Instructions** | `CLAUDE.md` *(none yet)* | `AGENTS.md` | `AGENTS.md` + `.github/copilot-instructions.md` → symlink |
| **Commands / prompts** | `.claude/commands` → symlink | user-level `~/.codex/prompts` only | `.github/prompts/*.prompt.md` → per-file symlinks |
| **Agents** | `.claude/agents` → symlink | no project-level concept | `.github/agents/*.agent.md` — **thin wrappers, see below** |

Codex needs no repo directory of its own: it discovers `.agents/skills` directly
(`$CWD`, parent, and repo root are all searched), and reads `AGENTS.md` for
instructions. A `.codex/` directory would be dead config, so there isn't one.

## The one duplication: `.github/agents/`

Agent frontmatter is the only genuinely non-portable format here. Claude's
`tools: Read, Write, Edit, Bash, Glob, Grep, Skill` and `model: sonnet` are not
valid Copilot values — symlinking these files would hand Copilot a toolset it
cannot resolve. Copilot also requires the `.agent.md` suffix and its own
`.github/agents/` location.

So each `.github/agents/<name>.agent.md` carries **only** Copilot-shaped routing
metadata (`name`, `description`; `tools` and `model` deliberately omitted so the
agent gets defaults) and a body that points at the canonical file in
`.agents/agents/`. The operating instructions themselves are never copied.

When you change an agent's `description` in `.agents/agents/`, mirror it into the
matching `.github/agents/*.agent.md`. Nothing else needs syncing.

## Adding content

Add or edit files **only under `.agents/`**. Never write into `.claude/` or
`.github/prompts/` — those paths resolve through symlinks, so a write there lands
in the canonical tree anyway, and creating a real file next to a symlink is how
the trees drift apart.

- **New agent** — add `.agents/agents/<name>.md`, then add the matching
  `.github/agents/<name>.agent.md` wrapper for Copilot
- **New skill** — add `.agents/skills/<name>/SKILL.md` (plus any `references/`,
  `scripts/`, or supporting files). Live in all three tools immediately, no wrapper
- **New command** — add `.agents/commands/<name>.md`, then
  `ln -s ../../.agents/commands/<name>.md .github/prompts/<name>.prompt.md`
- **New rule / prompt** — add a file to the matching directory

## A note on `.gitignore`

Skill `references/` directories must stay tracked. The root ignore rule is
anchored (`/references/`) for exactly this reason — an unanchored `references/`
matches at every depth and silently strips the supporting material out of nine
skills on clone.

## Verifying the links

```bash
ls -l .claude .github .github/prompts && find .claude .github -type l ! -exec test -e {} \; -print
```

Every symlink should print a relative target; the `find` should print nothing.

## Windows note

Git stores these as true symlinks. On Windows, checking them out as links requires
either Developer Mode or an elevated shell, plus `git config core.symlinks true`.
Without that, Git materialises each link as a small text file containing the target
path, and the agent tooling will not find its configuration. Codex and Copilot are
unaffected for skills, since they read `.agents/skills` directly.
