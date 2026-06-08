<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'

import SideRail from '@/components/SideRail.vue'
import DenseTable from '@/components/DenseTable.vue'
import ModalityEditDialog from '@/components/ModalityEditDialog.vue'

import { useModalitiesStore } from '@/stores/modalities'
import { ApiError } from '@/api/client'
import type {
  CreateModalityRequest,
  Modality,
  UpdateModalityRequest,
} from '@/types/modality'

/**
 * Phase E.6 — Modality admin view.
 *
 * Administrator-only surface. Surfaces the global modality catalog
 * (NOT study-scoped — the table is platform-wide) so an admin can
 * register a new measurement channel + its OD/OS item OID pair before
 * the CRF authoring team binds it into a specific CRF.
 *
 * The Modality model:
 *   - code     stable natural key (read-only after create)
 *   - labelEn  English display label
 *   - labelDe  German display label
 *   - ordinal  ranking inside picker dropdowns
 *   - itemOidOd / itemOidOs   one OR both required at create time
 *   - dataType numeric | categorical
 *   - unit     optional (mm, mmHg, ...)
 */
const { t } = useI18n()
const store = useModalitiesStore()

onMounted(() => { void store.load() })

const editing = ref<Modality | undefined>(undefined)
const dialogOpen = ref(false)
const isSubmitting = ref(false)
const dialogError = ref<string | null>(null)

function openCreate() {
  editing.value = undefined
  dialogError.value = null
  dialogOpen.value = true
}

function openEdit(row: Modality) {
  editing.value = row
  dialogError.value = null
  dialogOpen.value = true
}

function closeDialog() {
  dialogOpen.value = false
  dialogError.value = null
}

async function onSubmit(payload: CreateModalityRequest | UpdateModalityRequest) {
  dialogError.value = null
  isSubmitting.value = true
  try {
    if (editing.value) {
      await store.update(editing.value.modalityId, payload as UpdateModalityRequest)
    } else {
      await store.create(payload as CreateModalityRequest)
    }
    dialogOpen.value = false
  } catch (e) {
    dialogError.value = mapDialogError(e)
  } finally {
    isSubmitting.value = false
  }
}

function mapDialogError(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.status === 409) return t('modalities.error.duplicateCode')
    if (e.status === 400) {
      const body = e.body as { message?: string } | null
      const msg = (body?.message ?? '').toLowerCase()
      if (msg.includes('oid')) {
        if (msg.includes('missing')) return t('modalities.error.missingOid')
        return t('modalities.error.unknownOid')
      }
      return body?.message ?? t('modalities.error.network')
    }
    const body = e.body as { message?: string } | null
    return body?.message ?? `HTTP ${e.status}`
  }
  return t('modalities.error.network')
}

async function onDelete(row: Modality) {
  if (!confirm(t('modalities.action.deleteConfirm', { code: row.code }))) return
  try {
    await store.remove(row.modalityId)
  } catch {
    // Errors surface via store.error in the page.
  }
}

const rows = computed(() => store.list)
</script>

<template>
  <div class="flex">
    <SideRail>
      <RouterLink to="/build-study" class="flex items-center gap-2.5 px-2.5 py-1.5 rounded-md text-slate-700 hover:bg-white">
        {{ t('nav.buildStudy') }}
      </RouterLink>
    </SideRail>

    <main class="flex-1 max-w-6xl px-8 py-6">
      <div class="mb-4 flex items-end justify-between gap-4">
        <div>
          <h1 class="text-xl font-semibold tracking-tight">{{ t('modalities.title') }}</h1>
        </div>
        <button
          class="px-3 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium"
          @click="openCreate"
          data-testid="modalities-new"
        >
          {{ t('modalities.action.new') }}
        </button>
      </div>

      <p v-if="store.isLoading" class="text-slate-500 italic">{{ t('common.loading') }}</p>
      <p v-else-if="store.error" class="text-rose-700">{{ store.error }}</p>

      <DenseTable v-else>
        <template #header>
          <tr class="border-b border-slate-200">
            <th scope="col" class="px-3 py-2 font-medium">{{ t('modalities.columns.code') }}</th>
            <th scope="col" class="px-3 py-2 font-medium">{{ t('modalities.columns.labelDe') }}</th>
            <th scope="col" class="px-3 py-2 font-medium">{{ t('modalities.columns.labelEn') }}</th>
            <th scope="col" class="px-3 py-2 font-medium">{{ t('modalities.columns.oidOd') }}</th>
            <th scope="col" class="px-3 py-2 font-medium">{{ t('modalities.columns.oidOs') }}</th>
            <th scope="col" class="px-3 py-2 font-medium">{{ t('modalities.columns.type') }}</th>
            <th scope="col" class="px-3 py-2 font-medium">{{ t('modalities.columns.unit') }}</th>
            <th scope="col" class="px-3 py-2 font-medium w-16 text-right">{{ t('modalities.columns.ordinal') }}</th>
            <th scope="col" class="px-3 py-2 font-medium text-right">{{ t('modalities.columns.actions') }}</th>
          </tr>
        </template>

        <tr v-if="rows.length === 0">
          <td colspan="9" class="px-3 py-6 text-center text-slate-500 italic">
            {{ t('common.loading') }}
          </td>
        </tr>

        <tr
          v-for="row in rows"
          :key="row.modalityId"
          :data-testid="`modality-row-${row.modalityId}`"
        >
          <td class="px-3 py-2 font-mono text-xs">{{ row.code }}</td>
          <td class="px-3 py-2">{{ row.labelDe }}</td>
          <td class="px-3 py-2 text-slate-600">{{ row.labelEn }}</td>
          <td class="px-3 py-2 font-mono text-[11px] text-slate-600">{{ row.itemOidOd ?? '—' }}</td>
          <td class="px-3 py-2 font-mono text-[11px] text-slate-600">{{ row.itemOidOs ?? '—' }}</td>
          <td class="px-3 py-2 text-slate-600">{{ row.dataType }}</td>
          <td class="px-3 py-2 text-slate-600">{{ row.unit ?? '—' }}</td>
          <td class="px-3 py-2 text-right font-mono text-xs text-slate-600">{{ row.ordinal }}</td>
          <td class="px-3 py-2 text-right whitespace-nowrap">
            <button
              class="text-xs text-muw-blue hover:underline mr-3"
              :data-testid="`modality-edit-${row.modalityId}`"
              @click="openEdit(row)"
            >
              {{ t('modalities.action.edit') }}
            </button>
            <button
              class="text-xs text-rose-600 hover:underline"
              :data-testid="`modality-delete-${row.modalityId}`"
              @click="onDelete(row)"
            >
              {{ t('modalities.action.delete') }}
            </button>
          </td>
        </tr>
      </DenseTable>

      <ModalityEditDialog
        :open="dialogOpen"
        :existing="editing"
        :error-message="dialogError"
        :is-submitting="isSubmitting"
        @submit="onSubmit"
        @cancel="closeDialog"
        @update:open="(v) => { if (!v) closeDialog() }"
      />
    </main>
  </div>
</template>
