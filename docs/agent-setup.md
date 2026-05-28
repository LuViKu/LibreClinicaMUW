# Parallel-agent setup — git worktrees

This repository is worked on by **two concurrent agents**:

- **modernization** — backend Java / Spring / Hibernate / Castor work, the
  [MIGRATION.md](../MIGRATION.md) plan, Phase B / C / D execution.
- **phase-e** (UI) — feature-parity catalogue, UX mockups, Phase E planning.

To stop them stepping on each other's working trees (which caused real
production-grade pain in May 2026 — see `git log --grep="three stacked"`),
each agent runs in its own **git worktree**. The git database is shared
across all worktrees, so branches, commits, refs, and pushes work
normally. But each worktree has its own checked-out branch, its own
index, and its own working files — so a branch switch or `git add` in
one worktree is invisible to the other.

## Layout

| Directory | Branch | Used by |
|-----------|--------|---------|
| `/Users/lukas/LibreClinicaMUW` | `lc-develop` | Coordinator / review / running CI locally. No agent should make commits here directly; treat it as a read-only-ish view that fast-forwards via PR merges. |
| `/Users/lukas/LibreClinicaMUW-modernization` | `feature/phase-b-jakarta-cliff` (or whichever Phase B/C/D branch is active) | **modernization agent** |
| `/Users/lukas/LibreClinicaMUW-phase-e` | `feature/muw-phase-e-ux-mockups` (or whichever Phase E branch is active) | **phase-e (UI) agent** |

`git worktree list` always shows the current state.

## How agents are launched

Each agent's Claude Code session must be started **from its own worktree
directory** — Claude binds its working directory at session start and
cannot change it mid-session.

```sh
# Modernization session
cd /Users/lukas/LibreClinicaMUW-modernization
claude

# Phase E (UI) session
cd /Users/lukas/LibreClinicaMUW-phase-e
claude
```

A side-effect worth knowing: Claude Code's per-project memory and todo
state are keyed to the working directory path, so each worktree gets its
own memory bucket under
`/Users/lukas/.claude/projects/-Users-lukas-LibreClinicaMUW-modernization/`
and `…-LibreClinicaMUW-phase-e/`. That keeps each agent's context
separate, which is what we want.

## Working-tree rules each agent follows

1. **One branch per worktree.** A branch can be checked out in *at most
   one* worktree at a time — `git worktree add` rejects the second
   attempt. If two agents need the same branch, the second one creates a
   short-lived feature branch off it (`git worktree add … -b
   feature/<agent>-<topic>`).
2. **Never `git checkout` to a branch the other agent is on.** The error
   message is clear if you try; treat it as a "switch your own worktree's
   branch, not theirs" reminder.
3. **Stay on a feature branch.** Direct work on `lc-develop` is reserved
   for fast-forward merges from PRs. If an agent commits to `lc-develop`
   directly, it bypasses CI on the PR and risks the same race we are
   here to avoid.
4. **`git fetch` often.** The shared object database means a `fetch`
   immediately surfaces the other agent's pushed commits.
5. **Push to your own feature branch first**, then open a PR into
   `lc-develop`. Merging via PR (instead of direct push to `lc-develop`)
   means GitHub's merge queue + CI catches conflicts before they hit the
   integration branch.

## Switching the modernization worktree to a new feature branch

When Phase B.0 wraps and Phase B.1 starts (per [Phase B execution
playbook](development/modernization/phase-b-execution-playbook.md)):

```sh
cd /Users/lukas/LibreClinicaMUW-modernization
git fetch origin
git checkout -b feature/phase-b-jdk21-baseline origin/lc-develop
# … do the work, push the branch, open PR, merge to lc-develop, repeat
```

The modernization worktree stays in the *same* sibling directory —
only its checked-out branch changes. Same Claude session resumes; same
memory bucket; same todo list.

## Cleaning up a worktree (when a phase finishes)

```sh
# from any directory:
git worktree remove /Users/lukas/LibreClinicaMUW-modernization
```

`git worktree remove` refuses if the worktree has uncommitted changes
(no `--force` unless you know what you're doing — the worktree may
contain ungraded work). Push everything first, then remove.

## Why this is enough (and we don't need a lock file)

Worktrees give us **filesystem-level isolation**. Two agents writing
to two different sibling directories cannot, by construction, observe
each other's in-progress edits. The "two agents on the same working
tree" failure mode goes away entirely.

The only remaining shared mutable state is `lc-develop` on the remote.
That's handled by:
- Branch protection on `lc-develop` requiring PR-based merge (GitHub
  settings — pending; see CONTRIBUTING note below)
- The merge queue serialising concurrent PRs against the integration
  branch

A repo-level `.agent-lock` file was considered and rejected: git has no
notion of who is pushing, so a userland lock just reinvents distributed
locking badly. Worktrees solve the real problem at the filesystem layer
where it lives.

## Open items

- **Branch protection on `lc-develop`** is not yet configured. Enable it
  via GitHub repo settings → Branches → Add rule for `lc-develop`:
  "Require a pull request before merging" + "Require status checks to
  pass" + "Require branches to be up to date before merging". Once on,
  direct pushes to `lc-develop` (including by either agent) will be
  rejected. Both agents already work on feature branches by default so
  the only behaviour change is the requirement to open a PR.
- A future session may add a `CODEOWNERS` file mapping
  `docs/development/modernization/phase-e/**` → phase-e agent and
  `MIGRATION.md`, `docs/development/modernization/decision-record.md` →
  modernization agent, but the path-level boundaries are already de
  facto respected.
