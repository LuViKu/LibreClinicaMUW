<script setup lang="ts">
/**
 * Phase E.3 primitive — Side rail.
 *
 * Slot-based: the parent supplies a tree of `<RouterLink>`s or buttons.
 * Width + background + base padding are fixed here so every workflow uses
 * the same rail dimensions. Active-state visuals are owned by the link
 * itself (use `:class` + `router-link-active`).
 *
 * Phase E.6 (2026-06-09) — sticky behaviour + bottom version/build line.
 *   - The rail now positions itself `sticky top-14` (top-bar h-14 = 56px)
 *     so navigation stays visible while the main scrolls.
 *   - Vite injects __APP_VERSION__ / __BUILD_HASH__ / __BUILD_DATE__ via
 *     defines (see vite.config.ts); we render them in a low-visual-weight
 *     footer pinned to the bottom of the rail.
 *   - An optional `metrics` slot anchors a divider + custom action
 *     (e.g. "Studien-Statistik Details") right above the version line.
 */
const appVersion = __APP_VERSION__
const buildHash = __BUILD_HASH__
const buildDate = __BUILD_DATE__
</script>

<template>
  <aside
    class="w-56 shrink-0 border-r border-slate-200 bg-slate-50 text-sm sticky top-14 self-start flex flex-col"
    style="min-height: calc(100vh - 3.5rem);"
    role="navigation"
    aria-label="Side rail"
  >
    <div class="flex-1 px-3 py-4 overflow-y-auto">
      <nav class="space-y-0.5">
        <slot />
      </nav>

      <template v-if="$slots.footer">
        <hr class="my-4 border-slate-200" />
        <div class="px-1">
          <slot name="footer" />
        </div>
      </template>

      <template v-if="$slots.metrics">
        <hr class="my-4 border-slate-200" />
        <div class="px-1">
          <slot name="metrics" />
        </div>
      </template>
    </div>

    <!-- Phase E.6 — version + build pinned to the bottom of the rail.
         JetBrains-Mono fallback chain keeps the build hash monospace
         even when the bundled font hasn't been pre-loaded yet. -->
    <div
      class="border-t border-slate-200 bg-slate-50/80 px-4 py-3"
      data-testid="siderail-version"
    >
      <div class="text-[11px] text-slate-600 font-medium">
        LibreClinica<span class="text-muw-coral-700 ml-1">MUW</span>
        <span class="ml-1 text-slate-500 font-normal">v{{ appVersion }}</span>
      </div>
      <div
        class="mt-1 text-[10px] text-slate-400"
        style="font-family: 'JetBrains Mono', ui-monospace, SFMono-Regular, monospace;"
      >
        Build {{ buildDate }} · {{ buildHash }}
      </div>
    </div>
  </aside>
</template>
