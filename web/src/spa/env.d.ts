/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  // eslint-disable-next-line @typescript-eslint/no-explicit-any, @typescript-eslint/no-empty-object-type
  const component: DefineComponent<{}, {}, any>
  export default component
}

/**
 * vuedraggable ships no types — we use it as an SFC with v-model on the
 * list prop. Declaration mirrors the actual prop / event surface the SPA
 * relies on (Phase E.6 Milestone B authoring wizard drag-reorder).
 */
declare module 'vuedraggable' {
  import type { DefineComponent } from 'vue'
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const draggable: DefineComponent<any, any, any>
  export default draggable
}

/**
 * Phase E.6 — Vite-injected build constants. Defined in vite.config.ts
 * via the `define` map; read by the SideRail footer to surface the
 * running app's version + build hash + date.
 */
declare const __APP_VERSION__: string
declare const __BUILD_HASH__: string
declare const __BUILD_DATE__: string
