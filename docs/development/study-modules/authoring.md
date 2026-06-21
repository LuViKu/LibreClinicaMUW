# Authoring a Study Module

The SPA's study-module SPI lets institutional study types (nAMD today, GA / RVO / observational AMD next) plug their own workspace, panels, scheduling hints and i18n keys into shared host views without touching shared code. This guide is the canonical "how do I write one" reference.

The contract lives at [web/src/spa/src/studyModules/types.ts](../../../web/src/spa/src/studyModules/types.ts); the reference implementation lives at [web/src/spa/src/studyModules/nAMD/](../../../web/src/spa/src/studyModules/nAMD/). When in doubt, copy nAMD.

## Quick start (5 minutes)

```sh
cd web/src/spa/src/studyModules
cp -R nAMD myProtocol
```

In `myProtocol/index.ts` change three things:

```ts
protocolType: 'MYPROTOCOL',          // matches study.protocol_type
labelKey: 'studyModules.myProtocol.label',
routes: [{ path: '', name: 'myProtocol-workspace', component: ... }],
```

That's it — `studyModules/registry.ts` discovers the new folder via `import.meta.glob` at boot. **No registry edit, no shared code change.**

Verify locally:

1. Set a study's protocol type: `UPDATE study SET protocol_type = 'MYPROTOCOL' WHERE oc_oid = 'S_DEFAULTS1';`
2. `pnpm exec vitest run src/studyModules/myProtocol/` — copy nAMD's specs as templates.
3. Bring up compose, pick the study, navigate to `/studies/S_DEFAULTS1/modules/myprotocol`.

## The contract

```ts
interface StudyModuleManifest {
  protocolType: string                            // matches study.protocol_type (case-insensitive, trimmed)
  labelKey: string                                // i18n key for the module name
  routes: RouteRecordRaw[]                        // prefixed by the framework
  injections?: Partial<Record<InjectionSlotId, InjectionEntry[]>>
  loadI18n?: () => Promise<{ de, en }>            // lazy bundle merge
  visitScheduler?: (ctx) => VisitSchedulerHint | null   // optional T&E hook
}
```

### Activation

A manifest activates when `auth.user.activeStudy.protocolType` matches `manifest.protocolType` (case-insensitive, whitespace-trimmed). The `useStudyModuleStore()` Pinia store re-derives `activeModule` on every study switch. Re-activation skips lazy i18n re-loads — bundles persist for the session.

### Uniqueness

`protocolType` must be unique across the registry. The boot-time assertion in `registry.ts` emits a `console.warn` when two modules collide; `findModule()` returns whichever registered first (insertion order, which under `import.meta.glob` is alphabetical by directory name).

## Routing

The framework prefixes every module route with `/studies/:studyOid/modules/<protocolType-lowercase>`. Your manifest's `routes[].path` is appended:

```ts
routes: [
  { path: '',         name: 'myProtocol-workspace', component: () => import('./views/Workspace.vue') },
  { path: '/visit',   name: 'myProtocol-visit',    component: () => import('./views/Visit.vue') },
]
```

becomes `/studies/:studyOid/modules/myprotocol` and `/studies/:studyOid/modules/myprotocol/visit`.

The router guard verifies two invariants on every navigation:

1. `auth.activeStudy.protocolType` matches `meta.studyModule` (the framework stamps this from the manifest).
2. The URL's `:studyOid` matches `auth.activeStudy.oid` — prevents bookmarked URLs from landing in the wrong study's data.

Either failure redirects to home with a toast.

### Role gating

Declare per-route role gates the same way the rest of the SPA does:

```ts
{ path: '', name: ..., component: ..., meta: { role: ['Investigator', 'Data Manager'] as const } }
```

`meta.role` is checked before `meta.studyModule`; mismatched roles fail first (more restrictive).

## Injection slots

Six slots are available today. Each host view passes a typed context that your `predicate` receives:

| Slot id                       | Host view                | Context type             | Notes |
|-------------------------------|--------------------------|--------------------------|-------|
| `subject-detail.workspace`    | SubjectDetailView        | `SubjectDetail \| null`  | Top-of-view CTA (e.g. "Open workspace") |
| `subject-detail.tabs`         | SubjectDetailView        | `SubjectDetail \| null`  | Extra tab — host doesn't yet consume this; mount via your own template |
| `event-detail.panels`         | EventDetailView          | `EventDetailDto \| null` | Below-form panels per visit — predicate gates by status / definition |
| `event-detail.actions`        | EventDetailView          | `EventDetailDto \| null` | Header action buttons — slot declared, host doesn't yet consume |
| `crf-entry.banner`            | CrfEntryView             | `null`                   | Top-of-form banner — no context |
| `nav.modules`                 | TopBar                   | `null`                   | Entry in primary nav — mounts whenever your module is active |

The framework only surfaces entries from the **active** module — when a study with `protocolType !== 'MYPROTOCOL'` is active, your entries don't render anywhere. No per-view gating needed.

Predicates are typed against `SlotContextMap[slotId]`:

```ts
injections: {
  'event-detail.panels': [
    {
      key: 'visit-summary',
      labelKey: 'studyModules.myProtocol.panels.summary',
      component: () => import('./components/VisitSummaryPanel.vue'),
      // event is typed as EventDetailDto | null automatically
      predicate: (event) => event?.status === 'completed',
    },
  ],
}
```

## i18n

Keep your keys under a per-module namespace: `studyModules.<id>.*`. The framework's collision detector warns at dev time when an incoming module overwrites a key set by another module (last-loaded-wins is vue-i18n's default). Adopt the prefix and the warning never fires.

`loadI18n()` returns DE + EN bundles. The store merges them into `de`, `de-AT` (same payload), and `en` on first activation:

```ts
loadI18n: async () => ({
  de: (await import('./locales/de.json')).default,
  en: (await import('./locales/en.json')).default,
}),
```

Use the existing repo convention for the EN bundle: every value prefixed with `[NEEDS_REVIEW] ` until a translator sweeps. The `tools/i18n/check-needs-review.sh` gate doesn't run on per-module bundles today; treat the convention as a courtesy to the next translator.

## Testing

Three test surfaces to cover, each modelled on the nAMD specs:

1. **Manifest** — `studyModules/myProtocol/__tests__/manifest.spec.ts`: assert `protocolType`, route count, injection entries; assert `loadI18n()` resolves with the expected top-level keys.
2. **Components** — per-component vitest specs under each component's `__tests__/`. Mount via `@vue/test-utils`; stub the studyModules store via `vi.mock('@/stores/studyModules', () => ({ useStudyModuleStore: () => ({ injectionsFor: vi.fn(() => []) }) }))`.
3. **Composables** — pure logic tests on the data-derivation composables (e.g. `useNamdVisitData`). No vue-test-utils boot needed.

The registry is exercised by the framework's `registry.spec.ts` — your module is auto-included in any whole-suite vitest run. No need to add registry-level coverage from a module spec.

## Backend

The framework is SPA-only. When you need protocol-conditional behaviour in a shared backend service (e.g. `VisitIntervalCalculator` running treat-and-extend rules for nAMD but a fixed-interval scheduler for GA), introduce a Spring strategy interface at that point:

```java
public interface VisitSchedulerStrategy {
    String forProtocol();                   // "NAMD"
    Optional<ScheduleHint> nextVisit(StudyEventBean current, ...);
}

@Component
public class NamdVisitSchedulerStrategy implements VisitSchedulerStrategy { ... }
```

`@Autowired List<VisitSchedulerStrategy> strategies` + dispatch by `forProtocol()` mirrors the SPA's `findModule()` pattern. **Don't pre-emptively retrofit existing single-protocol services** — wait for the second concrete strategy to make the abstraction earn its keep.

New endpoints, new audit-type ids, new Liquibase migrations all live where they've always lived (`core/src/main/java/.../controller/api/`, `AuditTypeIds.java`, `core/src/main/resources/migration/`). No module-scoped backend code today.

## What lives outside the module

| Concern                                       | Where it lives                                                        |
|-----------------------------------------------|-----------------------------------------------------------------------|
| Liquibase migrations                          | `core/src/main/resources/migration/lc-muw-*.xml`                      |
| Audit type IDs                                | `web/src/main/java/.../controller/api/AuditTypeIds.java`              |
| New backend endpoints                         | `web/src/main/java/.../controller/api/*ApiController.java`            |
| Generated `api.ts` types                      | `web/src/spa/src/types/api.ts` (regenerated; never hand-edited)       |
| Shared Pinia stores                           | `web/src/spa/src/stores/`                                             |
| Tailwind tokens                               | `web/src/spa/src/styles/main.css` (single source for `muw-*` palette) |

If your module needs a brand-new backend endpoint, file it as a separate PR in the shared backend layer + regen `api.ts`. Modules cannot ship Liquibase migrations or shared store changes.

## Reference

- Contract: [`web/src/spa/src/studyModules/types.ts`](../../../web/src/spa/src/studyModules/types.ts)
- Registry: [`web/src/spa/src/studyModules/registry.ts`](../../../web/src/spa/src/studyModules/registry.ts)
- Store: [`web/src/spa/src/stores/studyModules.ts`](../../../web/src/spa/src/stores/studyModules.ts)
- Router guard: [`web/src/spa/src/router/index.ts`](../../../web/src/spa/src/router/index.ts) (`meta.studyModule` block)
- nAMD reference: [`web/src/spa/src/studyModules/nAMD/`](../../../web/src/spa/src/studyModules/nAMD/)
