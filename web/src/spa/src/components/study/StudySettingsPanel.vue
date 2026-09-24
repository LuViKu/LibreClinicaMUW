<script setup lang="ts">
/**
 * P3.5 — what this study does, as switches an administrator can flip.
 *
 * Whether it receives DICOM, accepts uploads, runs inference, offers the
 * unauthenticated today's-visits list: all of that lived in a properties file
 * on the server, so changing one meant an edit and a restart.
 *
 * <p>Each switch shows three states, not two. **Not set** is distinct from
 * explicitly on or off, because an unset key means "as before" — it falls back
 * to the configuration it replaced and then to the code default. Collapsing
 * that into a checkbox would make every study look configured, and would turn
 * every first save into a decision the administrator never made.
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import { apiGet, apiPut } from '@/api/client'

const props = defineProps<{ studyOid: string }>()

const { t } = useI18n()

interface Setting {
  key: string
  /** What this study has set; null when it has set nothing. */
  value: string | null
  /** What the platform will actually use. */
  resolved: string
}

interface SettingsResponse {
  studyOid: string
  settings: Setting[]
  itemBindings: Record<string, string>
}

/** The switches, in the order an administrator thinks about them. */
const ORDER = [
  'ingest.dicom.enabled',
  'ingest.image.enabled',
  'ingest.oct.enabled',
  'inference.enabled',
  'ai.blinding.enabled',
  'export.bundle.enabled',
  'portal.todaysVisits',
]

/** Free-text rather than a switch — these name a study's own groups. */
const TEXT_KEYS = new Set(['ai.arm.shownGroup', 'ai.arm.hiddenGroup'])

const settings = ref<Setting[]>([])
const bindings = ref<Record<string, string>>({})
const loading = ref(true)
const saving = ref(false)
const error = ref<string | null>(null)
const saved = ref(false)

/** Pending edits, keyed by setting. '' means "clear it". */
const draft = ref<Record<string, string>>({})

const switches = computed(() =>
  ORDER.map((k) => settings.value.find((s) => s.key === k)).filter((s): s is Setting => !!s),
)
const textSettings = computed(() => settings.value.filter((s) => TEXT_KEYS.has(s.key)))
const dirty = computed(() => Object.keys(draft.value).length > 0)

function base(): string {
  return `/pages/api/v1/studies/${encodeURIComponent(props.studyOid)}/settings`
}

async function load(): Promise<void> {
  loading.value = true
  error.value = null
  try {
    const res = await apiGet<SettingsResponse>(base())
    settings.value = res.settings
    bindings.value = res.itemBindings ?? {}
    draft.value = {}
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)
watch(() => props.studyOid, load)

/** The value in play: a pending edit, else what the study has set. */
function current(s: Setting): string {
  return draft.value[s.key] ?? (s.value ?? '')
}

function set(key: string, value: string): void {
  draft.value = { ...draft.value, [key]: value }
  saved.value = false
}

async function save(): Promise<void> {
  saving.value = true
  error.value = null
  try {
    await apiPut(base(), { settings: draft.value })
    await load()
    saved.value = true
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <section class="space-y-4 mb-6" data-testid="study-settings-panel">
    <h2 class="text-sm font-medium text-slate-700">{{ t('studySettings.title') }}</h2>
    <p class="text-xs text-slate-500">{{ t('studySettings.intro') }}</p>

    <p v-if="error" class="text-xs text-rose-700" data-testid="study-settings-error">{{ error }}</p>
    <p v-if="loading" class="text-xs text-slate-500">{{ t('common.loading') }}</p>

    <template v-else>
      <table class="w-full text-xs">
        <thead>
          <tr class="text-left text-slate-500">
            <th scope="col" class="py-1 font-medium">{{ t('studySettings.setting') }}</th>
            <th scope="col" class="py-1 font-medium">{{ t('studySettings.value') }}</th>
            <th scope="col" class="py-1 font-medium">{{ t('studySettings.effective') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="s in switches" :key="s.key" class="border-t border-slate-100 align-top">
            <!-- The keys carry dots (ingest.dicom.enabled), which vue-i18n reads
                 as a path; the locale files nest them accordingly. The second
                 line says what the switch does — the first production
                 administrator saw "studySettings.keys.ingest.dicom.enabled"
                 and, quite reasonably, asked. -->
            <td class="py-1.5 pr-3 text-slate-700">
              <div class="font-medium">{{ t(`studySettings.keys.${s.key}`) }}</div>
              <div class="mt-0.5 text-[11px] leading-snug text-slate-500 max-w-md" :data-testid="`setting-help-${s.key}`">
                {{ t(`studySettings.help.${s.key}`) }}
              </div>
              <code class="text-[10px] text-slate-400">{{ s.key }}</code>
            </td>
            <td class="py-1.5 pr-3">
              <select
                class="px-2 py-1 rounded ring-1 ring-slate-200 bg-white"
                :value="current(s)"
                :data-testid="`setting-${s.key}`"
                @change="set(s.key, ($event.target as HTMLSelectElement).value)"
              >
                <!-- Not set is a real choice, not an empty one: it means
                     "whatever the platform decides", which is what an
                     untouched study has always had. -->
                <option value="">{{ t('studySettings.notSet') }}</option>
                <option value="true">{{ t('studySettings.on') }}</option>
                <option value="false">{{ t('studySettings.off') }}</option>
              </select>
            </td>
            <td class="py-1.5 text-slate-500">
              {{ s.resolved === 'true' ? t('studySettings.on') : t('studySettings.off') }}
              <span v-if="s.value === null" class="text-slate-400">
                ({{ t('studySettings.inherited') }})
              </span>
            </td>
          </tr>

          <tr v-for="s in textSettings" :key="s.key" class="border-t border-slate-100 align-top">
            <td class="py-1.5 pr-3 text-slate-700">
              <div class="font-medium">{{ t(`studySettings.keys.${s.key}`) }}</div>
              <div class="mt-0.5 text-[11px] leading-snug text-slate-500 max-w-md" :data-testid="`setting-help-${s.key}`">
                {{ t(`studySettings.help.${s.key}`) }}
              </div>
              <code class="text-[10px] text-slate-400">{{ s.key }}</code>
            </td>
            <td class="py-1.5 pr-3">
              <input
                type="text"
                class="px-2 py-1 rounded ring-1 ring-slate-200 w-40"
                :value="current(s)"
                :placeholder="s.resolved"
                :data-testid="`setting-${s.key}`"
                @input="set(s.key, ($event.target as HTMLInputElement).value)"
              />
            </td>
            <td class="py-1.5 text-slate-500">
              {{ s.resolved }}
              <span v-if="s.value === null" class="text-slate-400">
                ({{ t('studySettings.inherited') }})
              </span>
            </td>
          </tr>
        </tbody>
      </table>

      <p v-if="Object.keys(bindings).length" class="text-xs text-slate-500">
        {{ t('studySettings.bindingsCount', { n: Object.keys(bindings).length }) }}
      </p>

      <div class="flex items-center gap-2">
        <button
          type="button"
          class="px-3 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium disabled:opacity-50"
          :disabled="!dirty || saving"
          data-testid="save-study-settings"
          @click="save"
        >{{ saving ? t('common.saving') : t('studySettings.save') }}</button>
        <span v-if="saved" class="text-xs text-muw-teal-700" data-testid="study-settings-saved">
          {{ t('studySettings.saved') }}
        </span>
      </div>
    </template>
  </section>
</template>
