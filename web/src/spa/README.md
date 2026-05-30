# LibreClinica MUW — Phase E SPA

Vue 3 single-page application that progressively replaces the legacy
JSP + Prototype.js + jQuery + GWT chrome with a modern, MUW-branded
clinical-trial UI. Bundled into the WAR at `/LibreClinica/app/` via
the Frontend Maven Plugin (see [`web/pom.xml`](../../pom.xml)).

See [DR-008](../../../docs/development/modernization/decision-record.md)
for the framework decision and the [Phase E execution playbook](../../../docs/development/modernization/phase-e-execution-playbook.md)
for the sub-phase sequencing.

## Stack at a glance

| Layer | Choice |
|---|---|
| Framework | Vue 3.4+ with `<script setup>` Composition API |
| Language | TypeScript 5.x (strict) |
| Build | Vite 5 |
| Styling | Tailwind v4 with `@theme` directive |
| State | Pinia 2 |
| Routing | vue-router 4 |
| i18n | vue-i18n 9 (Composition API) — `de-AT` primary, `en` secondary |
| Forms | Native v-model + Zod validation |
| Unit tests | Vitest + Vue Test Utils |
| E2E tests | Playwright (integrates with the existing `web/src/test/.../SmokeIT` harness) |
| Component catalogue | Histoire 0.17 |
| Package manager | pnpm 9 |
| Node | 20.x LTS |

## Local dev loop

```sh
cd web/src/spa
pnpm install
pnpm dev   # http://127.0.0.1:5173/LibreClinica/app/
```

Vite proxies backend calls (`/MainMenu`, `/pages/*`, `/actuator/*`, etc.) to
`http://127.0.0.1:8080` — start the Docker Compose stack from the repo root
(`docker compose up --build`) to give the SPA a real backend to talk to.

## Maven integration

`mvn package` runs the SPA build automatically via
`frontend-maven-plugin`:

1. **Generate-resources phase** — `install-node-and-pnpm` (cached under
   `web/target/spa-tooling/`) + `pnpm install --frozen-lockfile --prefer-offline`.
2. **Prepare-package phase** — `pnpm build`. Vite writes the bundle to
   `web/src/main/webapp/app/`. The maven-war-plugin then packages it.

Skip the SPA build during fast Java iteration:

```sh
mvn package -DskipSpa=true
```

## Component catalogue

```sh
pnpm stories   # http://localhost:6006
```

Every primitive sits next to its component as `<Name>.story.vue`. Stories
double as the design-token integration test and the accessibility-stress
surface for the Phase E.9 WCAG 2.2 AA gate.

## Project layout

```
src/
├── App.vue                     # Root component
├── main.ts                     # createApp + plugins (Pinia, Router, i18n)
├── style.css                   # Tailwind v4 + MUW @theme + page defaults
├── components/                 # Shared primitives (E.3)
│   ├── TopBar.vue              # Brand lockup + breadcrumb + role chip
│   ├── SideRail.vue            # 56px-wide role-conditional nav rail
│   ├── StatusPill.vue          # Coloured dot + label
│   └── __tests__/              # Component unit tests (Vitest)
├── views/                      # Route components (one per workflow)
│   └── HomeView.vue            # Landing — Phase E.1 smoke test view
├── router/                     # vue-router
├── stores/                     # Pinia (placeholder)
├── locales/                    # vue-i18n JSON dictionaries (DE/EN)
└── assets/                     # Static assets bundled into the build
    └── fonts/                  # Newsreader, Inter, JetBrains Mono WOFF2
```

## Phase E sub-phase status

| Sub-phase | Status | Reference |
|---|---|---|
| E.1 — Vue 3 scaffolding | ✅ shipped 2026-05-30 | This README, vite.config.ts, main.ts, router/index.ts |
| E.2 — Tailwind v4 + MUW tokens + check-tokens guard | ✅ shipped 2026-05-30 | [src/style.css](src/style.css), [scripts/check-tokens.mjs](scripts/check-tokens.mjs) |
| E.3 — Shared component library | 🟢 in progress (8/10 primitives) | TopBar, SideRail, StatusPill, DenseTable, FormPrimitives (×5), Modal, DiffCard, Timeline, Wizard |
| E.4 — Backend API surface review | ✅ first-pass shipped 2026-05-30 | [docs/.../phase-e/api-surface.md](../../../docs/development/modernization/phase-e/api-surface.md) |
| E.5 — Investigator workflow | 🟡 1/3 screens (Subject Matrix shipped) | [SubjectMatrixView.vue](src/views/SubjectMatrixView.vue), [stores/subjects.ts](src/stores/subjects.ts) |
| E.4 — Backend API surface review | pending | TBD |
| E.5 — Investigator workflow | pending | TBD |
| E.6 — Monitor workflow | pending | TBD |
| E.7 — Data Manager workflow | pending | TBD |
| E.8 — Auth integration | pending | TBD |
| E.9 — Accessibility + i18n | pending (vue-i18n wired) | TBD |
| E.10 — Usability testing | pending | TBD |
| E.11 — Cutover + JSP retirement | pending | TBD |

## Phase E.0 entry blocker

The legacy `/pages/login/login` JSP path returns HTTP 404 on `lc-develop @ 5d4932481` —
see [post-Phase-D UI validation report](../../../docs/development/modernization/phase-e/post-phase-d-ui-validation.md).
The SPA scaffolding does **not** depend on that being green (the SPA
serves from `/app/`, not `/pages/`), so E.1–E.3 ship in parallel with
E.0 diagnosis.

## Known caveats (Phase E.0 → E.3)

- Fonts are referenced but not yet vendored — Newsreader / Inter / JetBrains
  Mono WOFF2 files land in `src/assets/fonts/` during Phase E.2's vendoring
  pass. Until then the build falls back to system stacks.
- Histoire config is wired but no a11y plugin yet — added in E.9.
- No backend bindings; the SPA renders a smoke-test page only. E.4 introduces
  the JSON-adapter PR train.
