<script setup lang="ts">
/**
 * Phase E retinal-inference (Wave C) — public OCT-portal footer.
 *
 * Mirrors the mockup's `Footer` (oct-portal.jsx ~ line 67). Left
 * side: the supported file format note (Heidelberg Spectralis .e2e).
 * Right side: version + build stamp pulled from the same Vite-time
 * defines the rest of the SPA's SideRail uses (`__APP_VERSION__`,
 * `__BUILD_HASH__`, `__BUILD_DATE__`).
 */

import { useI18n } from 'vue-i18n'

const { t } = useI18n()

declare const __APP_VERSION__: string
declare const __BUILD_HASH__: string
declare const __BUILD_DATE__: string

// vue-tsc evaluates the consts at type-check time; default to safe
// placeholder strings for jsdom runs that don't run the Vite define
// pass (vitest uses a separate config path).
const version = typeof __APP_VERSION__ !== 'undefined' ? __APP_VERSION__ : 'dev'
const buildHash = typeof __BUILD_HASH__ !== 'undefined' ? __BUILD_HASH__ : 'dev'
const buildDate = typeof __BUILD_DATE__ !== 'undefined' ? __BUILD_DATE__ : '0000-00-00'
</script>

<template>
  <footer
    class="border-t border-slate-200 bg-white px-6 py-3 flex items-center justify-between shrink-0"
    data-testid="public-footer"
  >
    <div class="text-[12px] text-slate-400 inline-flex items-center gap-2">
      <span class="inline-flex items-center gap-1.5">
        <span class="text-slate-400">
          <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="1.6" aria-hidden="true">
            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
            <polyline points="14 2 14 8 20 8" />
          </svg>
        </span>
        {{ t('octPortal.footer.formatNote') }} · <span class="font-mono">.e2e</span>
      </span>
    </div>
    <div class="flex items-center gap-2">
      <span class="text-muw-teal">
        <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2.2" aria-hidden="true">
          <path d="M20 6 9 17l-5-5" />
        </svg>
      </span>
      <span class="text-[11px] text-slate-500 font-medium">{{ t('octPortal.footer.versionLine', { version }) }}</span>
      <span class="text-[10px] text-slate-400 font-mono ml-1">{{ t('octPortal.footer.buildLine', { date: buildDate, hash: buildHash }) }}</span>
    </div>
  </footer>
</template>
