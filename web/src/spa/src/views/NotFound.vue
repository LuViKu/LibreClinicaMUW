<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'

/**
 * Phase E hardening — A5 (SPA global error boundary).
 *
 * Renders for the catch-all `/:pathMatch(.*)*` route. The host most
 * commonly hits this after a manual URL edit (typo, stale bookmark
 * pointing at a route that the modernization replaced) or after a
 * SiteMesh-era deep-link that no longer maps to a SPA route. The
 * companion `router.onError` swap-out (in `router/index.ts`) handles
 * the stale-chunk-after-redeploy case via `window.location.reload()`.
 *
 * Localised, minimal — no PHI on this page so no role gating is
 * needed; the global auth guard already redirects anonymous visitors
 * to /login before they can land here.
 */
const { t } = useI18n()
</script>

<template>
  <section class="mx-auto max-w-xl px-6 py-16 text-center">
    <p class="text-xs font-medium uppercase tracking-wider text-muw-coral-700">
      404
    </p>
    <h1 class="mt-2 text-2xl font-semibold text-muw-blue muw-display">
      {{ t('notFound.title') }}
    </h1>
    <p class="mt-3 text-sm text-slate-700">
      {{ t('notFound.body') }}
    </p>
    <RouterLink
      to="/"
      class="mt-6 inline-flex items-center justify-center rounded-md bg-muw-blue px-4 py-2 text-sm font-medium text-white hover:bg-muw-blue/90 focus:outline-none focus:ring-2 focus:ring-muw-blue focus:ring-offset-2"
      data-testid="not-found-cta"
    >
      {{ t('notFound.cta') }}
    </RouterLink>
  </section>
</template>
