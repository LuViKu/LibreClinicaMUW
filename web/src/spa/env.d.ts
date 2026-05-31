/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  // eslint-disable-next-line @typescript-eslint/no-explicit-any, @typescript-eslint/no-empty-object-type
  const component: DefineComponent<{}, {}, any>
  export default component
}

interface ImportMetaEnv {
  /**
   * Phase E.4 — when `'true'`, all SPA stores bypass the network and
   * hydrate from in-memory mock fixtures. Useful when iterating on
   * SPA UX without a backend (or before the relevant B-adapter ships).
   */
  readonly VITE_USE_MOCK_API?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
