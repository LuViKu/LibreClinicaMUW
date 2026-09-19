<script setup lang="ts">
/**
 * DR-029 — the public upload page, at /app/upload.
 *
 * Replaces the OCT page and the image page: one door for every file a
 * device exports, no login (the institutional reverse proxy is the only
 * access gate, and the backend throttles per client). The two older paths
 * redirect here for a release, because they are in bookmarks and on the
 * QR codes at the cameras.
 */
import { useI18n } from 'vue-i18n'

import PublicFooter from '@/components/octportal/PublicFooter.vue'
import PublicTopBar from '@/components/octportal/PublicTopBar.vue'
import UploadWorkbench from '@/components/upload/UploadWorkbench.vue'

const { t } = useI18n()
</script>

<template>
  <div class="flex flex-col bg-slate-50 min-h-screen" data-testid="upload-portal">
    <PublicTopBar :label="t('uploadPortal.topbar.portalLabel')" />
    <div class="flex-1 min-h-0">
      <div class="mx-auto max-w-[1248px] px-4 sm:px-6 md:px-10 py-6 md:py-9">
        <div class="mb-6">
          <div class="text-[11px] font-semibold uppercase tracking-[0.14em] text-muw-coral-700 mb-1.5">{{ t('uploadPortal.head.eyebrow') }}</div>
          <h1 class="muw-display text-[24px] md:text-[27px] leading-tight font-semibold tracking-tight text-slate-900">{{ t('uploadPortal.head.title') }}</h1>
          <p class="text-[13.5px] text-slate-500 mt-2 max-w-[720px] leading-relaxed">{{ t('uploadPortal.head.lead') }}</p>
        </div>
        <UploadWorkbench mode="public" />
      </div>
    </div>
    <PublicFooter :formats="t('uploadPortal.footer.formats')" />
  </div>
</template>
