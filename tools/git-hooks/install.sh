#!/usr/bin/env bash
# Install repo-managed git hooks under .git/hooks/.
#
# Run once per clone:
#
#   tools/git-hooks/install.sh
#
# The hooks themselves live under tools/git-hooks/ and are versioned
# alongside the code. Re-running this script is safe — it overwrites
# any prior copies. Skip with `git commit --no-verify` per-commit, or
# delete .git/hooks/pre-commit to remove it entirely.

set -euo pipefail

REPO_ROOT=$(git rev-parse --show-toplevel)
HOOKS_SRC="$REPO_ROOT/tools/git-hooks"
HOOKS_DST="$REPO_ROOT/.git/hooks"

if [[ ! -d "$HOOKS_DST" ]]; then
  echo "error: $HOOKS_DST does not exist — is this a git worktree?" >&2
  exit 1
fi

install -m 0755 "$HOOKS_SRC/pre-commit" "$HOOKS_DST/pre-commit"
echo "Installed pre-commit hook → $HOOKS_DST/pre-commit"
