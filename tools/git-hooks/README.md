# Repo git hooks

Versioned hooks under `tools/git-hooks/`, installed into `.git/hooks/` per worktree.

## Install

```sh
tools/git-hooks/install.sh
```

Run once per clone or worktree. Re-running overwrites prior copies.

## Hooks

### `pre-commit`

Warns (does not block) when a backend API controller is staged but `web/src/spa/src/types/api.ts` is not. Catches the common foot-gun where a controller change alters the OpenAPI surface but the SPA's generated types lag — CI's compose smoke job runs `pnpm run codegen:openapi` against the live stack and fails the build on drift, so the hook surfaces that risk at commit time.

The hook only warns — it never blocks. Pass `--no-verify` to silence it for a single commit, or delete `.git/hooks/pre-commit` to remove it.

To regenerate `api.ts` against a running stack:

```sh
docker compose up -d --build libreclinica db smtp
cd web/src/spa && pnpm run codegen:openapi
```
