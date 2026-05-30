<script setup lang="ts">
/**
 * Phase E.3 primitive — Side rail.
 *
 * Slot-based: the parent supplies a tree of `<RouterLink>`s or buttons.
 * Width + background + base padding are fixed here so every workflow uses
 * the same rail dimensions. Active-state visuals are owned by the link
 * itself (use `:class` + `router-link-active`).
 */
</script>

<template>
  <aside
    class="w-56 border-r border-slate-200 bg-slate-50 min-h-[calc(100vh-3rem)] px-3 py-4 text-sm"
    role="navigation"
    aria-label="Side rail"
  >
    <nav class="space-y-0.5">
      <slot />
    </nav>

    <template v-if="$slots.footer">
      <hr class="my-4 border-slate-200" />
      <div class="px-1">
        <slot name="footer" />
      </div>
    </template>
  </aside>
</template>
