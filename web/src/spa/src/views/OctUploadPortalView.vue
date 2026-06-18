<script setup lang="ts">
/**
 * Phase E retinal-inference (Wave C) — OCT-Upload-Portal view.
 *
 * Public, unauthenticated SPA page mounted at `/app/oct-upload`. The
 * institutional reverse proxy is the only access gate; both the router
 * guard ({@code meta.public = true}) and the backend
 * {@code PublicOctUploadController} match the same access model.
 *
 * Three artboards driven by one component:
 *  - {@code ready}    — empty queue, hero dropzone
 *  - {@code parsing}  — at least one row is reading its header
 *  - {@code review}   — every row has parsed; SummaryBar + ReviewQueue
 *
 * Backed by the {@link useOctPortalStore} Pinia store. The view stays
 * declarative — every operator action emits up to here and routes to
 * the store; the store owns the API client wiring.
 *
 * <h3>Strings</h3>
 *
 * German UI text is hard-coded inline per the plan's "no de.json for
 * v1" choice — the portal is unilaterally German for MUW operators.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import PublicTopBar from '@/components/octportal/PublicTopBar.vue'
import PublicFooter from '@/components/octportal/PublicFooter.vue'
import E2eDropzone from '@/components/octportal/E2eDropzone.vue'
import ParseQueue from '@/components/octportal/ParseQueue.vue'
import ReviewQueue from '@/components/octportal/ReviewQueue.vue'
import SummaryBar from '@/components/octportal/SummaryBar.vue'

import { useOctPortalStore } from '@/stores/octPortal'

const { t } = useI18n()
const store = useOctPortalStore()

/** Three-state visual machine derived from store.rows. */
const screen = computed<'ready' | 'parsing' | 'review'>(() => {
  if (store.rows.length === 0) return 'ready'
  if (store.isParsing) return 'parsing'
  return 'review'
})

function onFilesAdded(files: File[]): void {
  // Fire and forget — the store flips rows into the `parsing` state
  // synchronously and the rest runs as promises.
  void store.addFiles(files)
}

function onConfirm(rowId: string): void {
  void store.confirm(rowId)
}

function onConfirmAll(): void {
  void store.confirmAll()
}

function onPark(rowId: string): void {
  void store.park(rowId)
}

function onUndo(rowId: string): void {
  void store.undo(rowId)
}

function onDismiss(rowId: string): void {
  store.dismiss(rowId)
}

/**
 * "Visite wählen" / "Patient suchen" are not implemented in v1 — the
 * mockup surfaces them but the plan's "Out of scope" list defers
 * them. We swallow the event so the row stays interactive and the
 * operator falls back to "Parken" / "Später zuordnen".
 *
 * TODO(phase-e-retinal-v2): replace with a real picker modal once
 * the backend exposes /candidates and /events search endpoints.
 */
function onPickVisitUnsupported(_rowId: string): void {
  // intentionally no-op for v1
}
function onSearchPatientUnsupported(_rowId: string): void {
  // intentionally no-op for v1
}
</script>

<template>
  <div class="flex flex-col bg-slate-50 min-h-screen" data-testid="oct-upload-portal">
    <PublicTopBar />
    <div class="flex-1 min-h-0">
      <div class="mx-auto max-w-[1248px] px-6 md:px-10 py-9">
        <!-- ============================ Page head ============================ -->
        <div class="flex items-end justify-between gap-4 mb-6">
          <div>
            <div class="text-[11px] font-semibold uppercase tracking-[0.14em] text-muw-coral-700 mb-1.5">{{ t('octPortal.head.eyebrow') }}</div>
            <h1 class="muw-display text-[27px] leading-tight font-semibold tracking-tight text-slate-900">{{ t('octPortal.head.title') }}</h1>
            <p class="text-[13.5px] text-slate-500 mt-2 max-w-[660px] leading-relaxed">
              {{ t('octPortal.head.leadIntro') }} <span class="font-medium text-slate-700">{{ t('octPortal.head.studyWord') }}</span> {{ t('octPortal.head.leadAnd') }} <span class="font-medium text-slate-700">{{ t('octPortal.head.visitWord') }}</span> {{ t('octPortal.head.leadDetermine') }} <span class="font-medium text-slate-700">{{ t('octPortal.head.patientIdWord') }}</span> {{ t('octPortal.head.leadAnd') }} <span class="font-medium text-slate-700">{{ t('octPortal.head.scanDateWord') }}</span> {{ t('octPortal.head.leadFromHeader') }}
            </p>
          </div>
          <div v-if="screen === 'parsing'" class="inline-flex items-center gap-2 text-[13px] text-slate-500 mb-1">
            <span class="text-muw-blue inline-block">
              <svg class="muw-portal-spin" viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" stroke-width="2.4" stroke-linecap="round" aria-hidden="true">
                <path d="M21 12a9 9 0 1 1-6.2-8.5" opacity="0.9" />
              </svg>
            </span>
            {{ t('octPortal.head.parsingStatus') }}
          </div>
        </div>

        <!-- ============================ READY artboard ============================ -->
        <template v-if="screen === 'ready'">
          <E2eDropzone mode="hero" @files-added="onFilesAdded" />
          <div class="flex items-start gap-2.5 mt-5 text-[12.5px] text-slate-500">
            <span class="text-muw-blue-300 mt-0.5">
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" stroke-width="1.9" aria-hidden="true">
                <path d="M10.3 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.7 3.86a2 2 0 0 0-3.42 0Z" />
                <path d="M12 9v4M12 17h.01" />
              </svg>
            </span>
            <p class="max-w-[720px] leading-relaxed">
              {{ t('octPortal.hero.noLoginPrefix') }} <span class="font-medium text-slate-700">.e2e</span>{{ t('octPortal.hero.noLoginHeader') }} <span class="font-medium text-slate-700">{{ t('octPortal.head.patientIdWord') }}</span>, <span class="font-medium text-slate-700">{{ t('octPortal.head.scanDateWord') }}</span> {{ t('octPortal.head.leadAnd') }} <span class="font-medium text-slate-700">{{ t('octPortal.hero.noLoginEye') }}</span> {{ t('octPortal.hero.noLoginSuffix') }}
            </p>
          </div>
        </template>

        <!-- ============================ PARSING artboard ============================ -->
        <template v-else-if="screen === 'parsing'">
          <E2eDropzone mode="slim" @files-added="onFilesAdded" />
          <ParseQueue :rows="store.rows" />
        </template>

        <!-- ============================ REVIEW artboard ============================ -->
        <template v-else>
          <E2eDropzone mode="slim" @files-added="onFilesAdded" />
          <SummaryBar :rows="store.rows" @confirm-all="onConfirmAll" />
          <ReviewQueue
            :rows="store.rows"
            @confirm="onConfirm"
            @undo="onUndo"
            @pick-visit="onPickVisitUnsupported"
            @park="onPark"
            @search-patient="onSearchPatientUnsupported"
            @dismiss="onDismiss"
          />
        </template>
      </div>
    </div>
    <PublicFooter />
  </div>
</template>
