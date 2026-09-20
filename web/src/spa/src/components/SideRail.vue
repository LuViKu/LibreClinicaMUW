<script setup lang="ts">
import { useI18n } from 'vue-i18n'

/**
 * Phase E.3 primitive — Side rail.
 *
 * Slot-based: the parent supplies a tree of `<RouterLink>`s or buttons.
 * Width + background + base padding are fixed here so every workflow uses
 * the same rail dimensions. Active-state visuals are owned by the link
 * itself (use `:class` + `aria-current`).
 *
 * A rail is mounted only where it has a section to navigate — the
 * Studienaufbau pages ({@link BuildStudyRail}) and the CRF entry's section
 * table of contents. It used to sit on twenty-four views, most of them
 * holding one or two links the top bar already carried, and it doubled as
 * the home of the version/build line; that line lives in the profile menu
 * now, so a page without a rail no longer lacks it.
 *
 * Sticky under the top bar (h-14 = 56px) so the navigation stays visible
 * while the main column scrolls.
 */
const { t } = useI18n()
</script>

<template>
  <!-- Complementary region (implicit for <aside>). The inner <nav> is the
       single navigation landmark; the <aside> stays complementary (axe
       aria-allowed-role / landmark-unique). -->
  <aside
    class="w-56 shrink-0 border-r border-slate-200 bg-slate-50 text-sm sticky top-14 self-start"
    style="min-height: calc(100vh - 3.5rem);"
  >
    <div class="px-3 py-4 overflow-y-auto">
      <nav class="space-y-0.5" :aria-label="t('a11y.sectionNavigation')">
        <slot />
      </nav>
    </div>
  </aside>
</template>
