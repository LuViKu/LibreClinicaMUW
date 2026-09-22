<script setup lang="ts">
/**
 * DR-029 — the uploader behind a login, reached from the ingest inbox.
 *
 * The same workbench as the public page, in staff mode: the session's
 * cookie rides along, the upload is attributed to the person, and the visits
 * on offer are the ones their site visibility allows. A data manager filing
 * a folder of Clarus exports at their desk uses this; the operator at the
 * camera uses the public page.
 */
import { useI18n } from 'vue-i18n'

import UploadWorkbench from '@/components/upload/UploadWorkbench.vue'

const { t } = useI18n()
</script>

<template>
  <div class="p-4 sm:p-6 max-w-6xl mx-auto" data-testid="ingest-upload">
    <header class="mb-5 flex flex-wrap items-end justify-between gap-3">
      <div>
        <h1 class="text-xl font-semibold text-slate-800">{{ t('uploadPortal.staff.title') }}</h1>
        <p class="text-[13px] text-slate-500 mt-1 max-w-[720px]">{{ t('uploadPortal.staff.lead') }}</p>
      </div>
      <RouterLink
        :to="{ name: 'ingest-inbox' }"
        class="inline-flex items-center gap-1.5 text-[13px] font-medium text-muw-blue hover:text-muw-blue-700 min-h-8"
        data-testid="back-to-inbox"
      >
        <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true"><path d="m15 18-6-6 6-6" /></svg>
        {{ t('uploadPortal.staff.backToInbox') }}
      </RouterLink>
    </header>
    <UploadWorkbench mode="staff" />
  </div>
</template>
