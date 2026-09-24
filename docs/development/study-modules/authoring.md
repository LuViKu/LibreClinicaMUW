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
protocolType: 'MYPROTOCOL',          // the module id; a study is enrolled in it by that id
labelKey: 'studyModules.myProtocol.label',
routes: [{ path: '', name: 'myProtocol-workspace', component: ... }],
```

That's it — `studyModules/registry.ts` discovers the new folder via `import.meta.glob` at boot. **No registry edit, no shared code change.**

Verify locally:

1. Enrol a study in the module: `PUT /api/v1/studies/S_DEFAULTS1/modules/MYPROTOCOL` as an administrator, or insert the row directly:
   `INSERT INTO study_module_enrollment (study_id, module_id, enrolled_by) VALUES (1, 'MYPROTOCOL', 1);`
2. `pnpm exec vitest run src/studyModules/myProtocol/` — copy nAMD's specs as templates.
3. Bring up compose, pick the study, navigate to `/studies/S_DEFAULTS1/modules/myprotocol`.

## The contract

```ts
interface StudyModuleManifest {
  protocolType: string                            // the module id a study is enrolled in (case-insensitive, trimmed)
  labelKey: string                                // i18n key for the module name
  routes: RouteRecordRaw[]                        // prefixed by the framework
  injections?: Partial<Record<InjectionSlotId, InjectionEntry[]>>
  loadI18n?: () => Promise<{ de, en }>            // lazy bundle merge
  requiredRetinalTasks?: readonly string[]        // DR-034: inference tasks the module reads
}
```

`requiredRetinalTasks` names the retinal inference tasks your module's logic depends on (nAMD: `['fluid']`). The visit imaging plan editor (Visits → edit → *Expected imaging*) shows those tasks pressed and not switchable on every OCT-volume modality a visit includes, and forces them into what it saves, so a study enrolled in your module cannot configure away the input the module reads. Declare only what the module actually consumes: every task listed runs on every OCT of every planned visit.

### Activation

A manifest activates when the active study is **enrolled** in it — `activeStudy.enabledModules` contains the manifest's `protocolType`, compared case-insensitively. Enrollment is administered per study at `PUT /api/v1/studies/{oid}/modules/{moduleId}`; sites inherit their parent study's enrollments.

`study.protocol_type` is **not** consulted. It is free-form text with no admin-visible toggle, which is why enrollment replaced it; the manifest field kept its name.

The `useStudyModuleStore()` Pinia store re-derives on every study switch. Re-activation skips lazy i18n re-loads — bundles persist for the session.

**Every enrolled module is active (P3.0).** A study running both imaging ingest and a decision aid needs both; before P3.0 only the first id the backend happened to list took effect, and the others were silently inert — routes loaded, slots never rendered, nothing said why. `activeModules` holds them all in enrollment order; `activeModule` remains as "the first" for the few callers that genuinely want one.

Entry keys are namespaced `<moduleId>:<key>` on the way out of `injectionsFor`, so two modules may both use `key: 'open-workspace'` without one disappearing. Keys need only be unique **within** your module — you do not have to guess what others called theirs.

### Uniqueness

`protocolType` must be unique across the registry. The boot-time assertion in `registry.ts` emits a `console.warn` when two modules collide; `findModule()` returns whichever registered first (insertion order, which under `import.meta.glob` is alphabetical by directory name).

## What belongs in a module, and what belongs to the study

A module is **SPA-only**: routes, panels, components, i18n. It is not where a study's *configuration* goes, and the distinction matters because getting it wrong is what made a third study expensive.

Ask the platform what the study means, rather than hard-coding it:

| You need | Ask | Not |
|---|---|---|
| Which CRF item a value lands in | `study_item_binding` via `StudyBindings.oidFor(studyId, role, fallback)` | a literal OID in shared code |
| Which device ticks which checklist box | the `imaging_modality` catalogue and its bindings | a row in a migration |
| Whether a study receives DICOM / runs inference | `study_setting` via `StudySettingService` | a `core.*` property naming study OIDs |
| Which groups a trial randomises AI visibility on | `AiArmPolicy` (`ai.arm.*`) | `AI_SHOWN` / `AI_HIDDEN` literals |

`SharedControllersHaveNoStudyLiteralsTest` enforces this on the Java side: a study's CRF or group name appearing in `controller/api` or `service` fails the build unless it is a documented fallback. That list may shrink and must never grow.

An administrator maintains all three catalogues through the UI — the imaging tab in **Modalitäten**, and the settings panel under study parameters — so **onboarding a study needs no code change and no migration**. That is the claim the catalogues exist to make true; a module that hard-codes its study's OIDs quietly breaks it.

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

1. The active study is enrolled in `meta.studyModule` (the framework stamps this from the manifest).
2. The URL's `:studyOid` matches `auth.activeStudy.oid` — prevents bookmarked URLs from landing in the wrong study's data.

Either failure redirects to home with a toast.

### Role gating

Declare per-route role gates the same way the rest of the SPA does:

```ts
{ path: '', name: ..., component: ..., meta: { role: ['Investigator', 'Data Manager'] as const } }
```

`meta.role` is checked before `meta.studyModule`; mismatched roles fail first (more restrictive).

## Injection slots

Six slot ids exist. **Three of them are rendered by a host today** — an entry declared for one of the others is accepted, stored and never displayed, so check this column before relying on one:

| Slot id                       | Host view                | Context type             | Rendered? | Notes |
|-------------------------------|--------------------------|--------------------------|-----------|-------|
| `subject-detail.workspace`    | SubjectDetailView        | `SubjectDetail \| null`  | yes | Top-of-view CTA (e.g. "Open workspace") |
| `event-detail.panels`         | EventDetailView          | `EventDetailDto \| null` | yes | Below-form panels per visit — predicate gates by status / definition |
| `crf-entry.banner`            | CrfEntryView             | `null`                   | yes | Top-of-form banner — no context |
| `subject-detail.tabs`         | SubjectDetailView        | `SubjectDetail \| null`  | yes | Panel below the built-in sections. Entries receive the loaded subject as a `subject` prop, and a `predicate` is evaluated against it before mounting. Consumed since P3.0 — it was declared and mounted nowhere before that |
| `event-detail.actions`        | EventDetailView          | `EventDetailDto \| null` | no  | Declared, not mounted |
| `home.cards`                  | HomeView                 | `null`                   | yes | Card in the landing page's study-scoped lane — the module's way in when the operator does not already have a subject open. Replaced `nav.modules` in P3.0, whose TopBar consumer was removed on 2026-06-21, leaving a slot that rendered nowhere |

The framework only surfaces entries from an **active** module — when the active study is not enrolled in your module, your entries do not render anywhere. No per-view gating needed.

Since P3.0 **every** enrolled module is active, not just the first one the backend happens to list. A slot's entries are concatenated across active modules in enrollment order, and entry keys are namespaced `<moduleId>:<key>` on the way out — so two modules may both use `key: 'open-workspace'` without one disappearing. Keys are unique within your module; you do not need to guess what other modules called theirs.

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
