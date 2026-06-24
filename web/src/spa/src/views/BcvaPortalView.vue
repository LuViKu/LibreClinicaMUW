<script setup lang="ts">
/**
 * 2026-06-24 user-feedback round — public BCVA-entry portal (stub).
 *
 * <p>Skeleton view registered at {@code /app/bcva-entry/<studyOid>}.
 * The controller backing it ({@code PublicBcvaEntryController}) and
 * the visit-list + form UI land in follow-up commits. This stub
 * keeps the route's dynamic import resolvable so the auth-bypass +
 * router wiring can be smoke-tested independently.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'

const route = useRoute()
const { t } = useI18n()

const studyOid = computed(() => String(route.params.studyOid ?? ''))
</script>

<template>
  <div data-testid="bcva-portal-view" class="min-h-screen bg-slate-50 px-6 py-10">
    <div class="max-w-3xl mx-auto bg-white rounded-muw shadow-muw-card border border-slate-200 p-8">
      <h1 class="text-xl font-semibold text-slate-900 mb-2">
        {{ t('bcvaPortal.title') }}
      </h1>
      <p class="text-sm text-slate-500 mb-4">
        {{ t('bcvaPortal.studyOid', { oid: studyOid }) }}
      </p>
      <p class="text-sm text-slate-600">
        {{ t('bcvaPortal.stubBody') }}
      </p>
    </div>
  </div>
</template>
