<script setup lang="ts">
/**
 * R1-export — the recurring exports of one dataset, under its row on the
 * Data Export page: list, add, edit, pause, resume and delete.
 *
 * The SPA's replacement for the legacy scheduled-export screens
 * (/CreateJobExport, /UpdateJobExport, /ViewJob, /PauseJob). A schedule is
 * a Quartz cron expression; the server validates it and reschedules before
 * it answers, so what the list shows after a change is what will run. A
 * paused schedule stays listed with its settings and can be resumed.
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'

import { useConfirm } from '@/composables/useConfirm'
import { formatDate } from '@/lib/dateFormat'
import { useExportSchedulesStore } from '@/stores/exportSchedules'
import type { ExportFormat, ExportScheduleDto } from '@/types/export'

const props = defineProps<{
  datasetId: number
  datasetName: string
  /** The formats this study may export (the bundle only where it is enabled). */
  formats: ExportFormat[]
}>()

const { t } = useI18n()
const schedules = useExportSchedulesStore()
const confirm = useConfirm()

const list = computed(() => schedules.byDataset.get(props.datasetId) ?? [])
const loading = computed(() => schedules.loading.has(props.datasetId))
const busy = ref<number | null>(null)

onMounted(() => {
  void schedules.load(props.datasetId)
})

/** Quartz cron: second, minute, hour, day of month, month, day of week. */
const PRESETS = [
  { key: 'daily', cron: '0 0 2 * * ?' },
  { key: 'weekly', cron: '0 0 2 ? * MON' },
  { key: 'monthly', cron: '0 0 2 1 * ?' },
] as const

interface ScheduleForm {
  editing: ExportScheduleDto | null
  format: string
  cronExpression: string
  notifyEmail: string
  enabled: boolean
  error: string | null
  saving: boolean
}
const form = ref<ScheduleForm | null>(null)

/** The study's formats, plus the one an edited schedule already has. */
const formatOptions = computed<string[]>(() => {
  const current = form.value?.editing?.format
  return current && !props.formats.includes(current as ExportFormat)
    ? [...props.formats, current]
    : [...props.formats]
})

function openCreate() {
  form.value = {
    editing: null,
    format: props.formats[0] ?? 'odm',
    cronExpression: PRESETS[0].cron,
    notifyEmail: '',
    enabled: true,
    error: null,
    saving: false,
  }
}

function openEdit(s: ExportScheduleDto) {
  form.value = {
    editing: s,
    format: s.format,
    cronExpression: s.cronExpression,
    notifyEmail: s.notifyEmail ?? '',
    enabled: s.enabled,
    error: null,
    saving: false,
  }
}

async function save() {
  const f = form.value
  if (!f) return
  f.error = null
  const cron = f.cronExpression.trim()
  if (!cron) {
    f.error = t('dataExport.schedules.cronRequired')
    return
  }
  const email = f.notifyEmail.trim()
  f.saving = true
  const saved = f.editing
    ? await schedules.update(f.editing, {
        format: f.format as ExportFormat,
        cronExpression: cron,
        enabled: f.enabled,
        // blank removes the address
        notifyEmail: email,
      })
    : await schedules.create(props.datasetId, {
        format: f.format as ExportFormat,
        cronExpression: cron,
        ...(email ? { notifyEmail: email } : {}),
      })
  f.saving = false
  if (saved) form.value = null
  else f.error = schedules.error
}

async function setEnabled(s: ExportScheduleDto, enabled: boolean) {
  busy.value = s.id
  try {
    await schedules.update(s, { enabled })
  } finally {
    busy.value = null
  }
}

async function remove(s: ExportScheduleDto) {
  if (!(await confirm({ message: t('dataExport.schedules.confirmDelete'), danger: true }))) return
  busy.value = s.id
  try {
    await schedules.remove(s)
  } finally {
    busy.value = null
  }
}

function formatLabel(format: string): string {
  const key = `dataExport.format.${format}`
  const label = t(key)
  return label === key ? format : label
}
</script>

<template>
  <div data-testid="dataset-schedules">
    <div class="flex items-center justify-between mb-2">
      <h3 class="text-[11px] uppercase tracking-wider text-slate-500 font-semibold">
        {{ t('dataExport.schedules.title') }}
      </h3>
      <button
        type="button"
        class="px-2.5 py-1 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-100 text-slate-700"
        data-testid="schedule-add"
        @click="openCreate"
      >
        {{ t('dataExport.schedules.add') }}
      </button>
    </div>

    <p v-if="schedules.error && !form" class="text-rose-700 text-xs mb-2" role="alert">{{ schedules.error }}</p>
    <p v-if="loading" class="text-slate-500 italic text-xs">{{ t('common.loading') }}</p>
    <p v-else-if="list.length === 0" class="text-slate-500 italic text-xs">{{ t('dataExport.schedules.empty') }}</p>
    <table v-else class="w-full text-xs">
      <thead class="text-[10px] uppercase tracking-wider text-slate-500">
        <tr>
          <th class="px-2 py-1 text-left">{{ t('dataExport.file.format') }}</th>
          <th class="px-2 py-1 text-left">{{ t('dataExport.schedules.column.when') }}</th>
          <th class="px-2 py-1 text-left">{{ t('dataExport.schedules.column.status') }}</th>
          <th class="px-2 py-1 text-left">{{ t('dataExport.schedules.column.lastRun') }}</th>
          <th class="px-2 py-1 text-left">{{ t('dataExport.schedules.column.contact') }}</th>
          <th class="px-2 py-1 text-right">{{ t('dataExport.column.actions') }}</th>
        </tr>
      </thead>
      <tbody class="divide-y divide-slate-200">
        <tr v-for="s in list" :key="s.id" :data-testid="`schedule-${s.id}`">
          <td class="px-2 py-1.5 text-slate-700">{{ formatLabel(s.format) }}</td>
          <td class="px-2 py-1.5 text-slate-700">
            <code class="font-mono text-[11px]">{{ s.cronExpression }}</code>
            <div v-if="s.enabled && s.nextRunAt" class="text-slate-500">
              {{ t('dataExport.schedules.nextRun', { date: formatDate(s.nextRunAt) }) }}
            </div>
          </td>
          <td class="px-2 py-1.5">
            <span
              class="inline-block px-2 py-0.5 rounded-full border text-[10px]"
              :class="s.enabled
                ? 'bg-emerald-50 text-emerald-800 border-emerald-200'
                : 'bg-amber-50 text-amber-800 border-amber-200'"
              data-testid="schedule-status"
            >
              {{ s.enabled ? t('dataExport.schedules.status.active') : t('dataExport.schedules.status.paused') }}
            </span>
          </td>
          <td class="px-2 py-1.5 text-slate-700">{{ formatDate(s.lastRunAt) }}</td>
          <td class="px-2 py-1.5 text-slate-700 break-all">{{ s.notifyEmail ?? '—' }}</td>
          <td class="px-2 py-1.5 text-right">
            <span v-if="!s.mayChange" class="text-slate-400" data-testid="schedule-readonly" :title="t('dataExport.schedules.creatorOnly')">—</span>
            <div v-else class="inline-flex items-center gap-1.5">
              <button
                type="button"
                class="px-2 py-0.5 border border-slate-200 rounded bg-white hover:bg-slate-100 text-slate-700"
                data-testid="schedule-edit"
                @click="openEdit(s)"
              >
                {{ t('dataExport.schedules.edit') }}
              </button>
              <button
                v-if="s.enabled"
                type="button"
                class="px-2 py-0.5 border border-slate-200 rounded bg-white hover:bg-slate-100 text-slate-700 disabled:opacity-50"
                data-testid="schedule-pause"
                :disabled="busy === s.id"
                @click="setEnabled(s, false)"
              >
                {{ t('dataExport.schedules.pause') }}
              </button>
              <button
                v-else
                type="button"
                class="px-2 py-0.5 border border-emerald-200 rounded bg-white hover:bg-emerald-50 text-emerald-700 disabled:opacity-50"
                data-testid="schedule-resume"
                :disabled="busy === s.id"
                @click="setEnabled(s, true)"
              >
                {{ t('dataExport.schedules.resume') }}
              </button>
              <button
                type="button"
                class="px-2 py-0.5 border border-rose-200 rounded bg-white hover:bg-rose-50 text-rose-700 disabled:opacity-50"
                data-testid="schedule-delete"
                :disabled="busy === s.id"
                @click="remove(s)"
              >
                {{ t('dataExport.schedules.delete') }}
              </button>
            </div>
          </td>
        </tr>
      </tbody>
    </table>

    <!-- Add / edit. -->
    <div
      v-if="form"
      class="fixed inset-0 z-30 bg-slate-900/40 flex items-center justify-center px-4"
      role="dialog"
      aria-modal="true"
      :aria-label="form.editing ? t('dataExport.schedules.editTitle') : t('dataExport.schedules.createTitle')"
    >
      <div class="bg-white rounded-muw shadow-xl max-w-md w-full p-6">
        <h2 class="text-base font-semibold text-slate-900 mb-1">
          {{ form.editing ? t('dataExport.schedules.editTitle') : t('dataExport.schedules.createTitle') }}
        </h2>
        <p class="text-xs text-slate-600 mb-4">{{ t('dataExport.schedules.subTitle', { dataset: datasetName }) }}</p>

        <label for="schedule-format" class="block text-xs uppercase tracking-wider text-slate-500 mb-1">
          {{ t('dataExport.modal.formatLabel') }}
        </label>
        <select
          id="schedule-format"
          v-model="form.format"
          class="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm mb-4 muw-focus"
          data-testid="schedule-format"
        >
          <option v-for="f in formatOptions" :key="f" :value="f">{{ formatLabel(f) }}</option>
        </select>

        <label for="schedule-cron" class="block text-xs uppercase tracking-wider text-slate-500 mb-1">
          {{ t('dataExport.schedules.cronLabel') }}
        </label>
        <input
          id="schedule-cron"
          v-model="form.cronExpression"
          type="text"
          spellcheck="false"
          autocomplete="off"
          class="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm font-mono muw-focus"
          data-testid="schedule-cron"
        />
        <div class="flex flex-wrap gap-1.5 mt-1.5">
          <button
            v-for="p in PRESETS"
            :key="p.key"
            type="button"
            class="px-2 py-0.5 text-[11px] border border-slate-200 rounded bg-white hover:bg-slate-100 text-slate-700"
            :data-testid="`schedule-preset-${p.key}`"
            @click="form.cronExpression = p.cron"
          >
            {{ t(`dataExport.schedules.preset.${p.key}`) }}
          </button>
        </div>
        <p class="text-[11px] text-slate-500 mt-1.5 mb-4">{{ t('dataExport.schedules.cronHelp') }}</p>

        <label for="schedule-email" class="block text-xs uppercase tracking-wider text-slate-500 mb-1">
          {{ t('dataExport.schedules.emailLabel') }}
        </label>
        <input
          id="schedule-email"
          v-model="form.notifyEmail"
          type="email"
          autocomplete="email"
          class="w-full rounded-md border border-slate-200 px-2 py-1.5 text-sm muw-focus"
          data-testid="schedule-email"
        />
        <p class="text-[11px] text-slate-500 mt-1 mb-4">{{ t('dataExport.schedules.emailHelp') }}</p>

        <label v-if="form.editing" class="flex items-center gap-2 text-sm text-slate-700 mb-4 cursor-pointer">
          <input v-model="form.enabled" type="checkbox" class="accent-muw-blue" data-testid="schedule-enabled" />
          {{ t('dataExport.schedules.enabledLabel') }}
        </label>

        <p v-if="form.error" class="text-rose-700 text-xs mb-3" role="alert" data-testid="schedule-error">{{ form.error }}</p>
        <div class="flex items-center justify-end gap-2">
          <button
            type="button"
            class="px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-100 text-slate-700"
            :disabled="form.saving"
            @click="form = null"
          >
            {{ t('common.cancel') }}
          </button>
          <button
            type="button"
            class="px-3 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium disabled:opacity-50 disabled:cursor-not-allowed"
            data-testid="schedule-save"
            :disabled="form.saving"
            @click="save"
          >
            {{ form.saving ? t('common.saving') : t('common.save') }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
