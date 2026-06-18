<script setup lang="ts">
/**
 * Phase E retinal-inference (Wave C) — public OCT-portal topbar.
 *
 * Mirrors the mockup's `TopBar` (oct-portal.jsx ~ line 49). Renders
 * the LibreClinica MUW wordmark + the portal label + the
 * "Kein Login erforderlich" pill — no nav, no auth menu. The portal
 * is a standalone surface and the topbar deliberately does NOT
 * inherit the SPA's authenticated `TopBar.vue`.
 *
 * The wordmark uses the {@code muw-display} class so the brand
 * Newsreader serif loads even when the rest of the app is paused;
 * `serif` font is reserved for branding moments per style.css.
 */
</script>

<template>
  <header
    class="h-14 border-b border-slate-200 bg-white flex items-center px-6 shrink-0"
    data-testid="public-top-bar"
  >
    <div class="flex items-center gap-2.5 text-muw-blue">
      <svg
        class="w-7 h-7"
        viewBox="0 0 32 32"
        fill="none"
        stroke="currentColor"
        stroke-linecap="round"
        stroke-linejoin="round"
        aria-hidden="true"
      >
        <circle cx="16" cy="16" r="14" stroke-width="1.4" />
        <path d="M12 8v16M20 8v16M8 12h16M8 20h16" stroke-width="1.75" />
      </svg>
      <span
        class="muw-display font-semibold text-muw-blue tracking-tight text-[17px]"
      >LibreClinica<em
        class="not-italic font-medium text-muw-coral-700 text-[0.66em] uppercase tracking-[0.08em] ml-1.5 align-middle"
      >MUW</em></span>
      <span
        class="ml-2 pl-3 border-l border-slate-200 text-[13px] font-medium text-slate-500"
      >OCT-Upload-Portal</span>
    </div>
    <div class="ml-auto flex items-center gap-4">
      <span
        class="inline-flex items-center gap-1.5 rounded-full bg-muw-teal-50 text-muw-teal-700 text-[12px] font-medium px-2.5 py-1"
      >
        <svg viewBox="0 0 24 24" width="13" height="13" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true">
          <rect width="14" height="9" x="5" y="11" rx="2" />
          <path d="M8 11V7a4 4 0 0 1 7.5-2" />
        </svg>
        Kein Login erforderlich
      </span>
    </div>
  </header>
</template>
