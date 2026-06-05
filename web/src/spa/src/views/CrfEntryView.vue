<script setup lang="ts">
import { computed, onMounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'

/**
 * Phase E.6 carry-over (2026-05-30): the Read-only CRF view alias.
 * Mounted by the `/event-crfs/:eventCrfOid/readonly` route via
 * `meta.readOnly = true`. In read-only mode every input is disabled,
 * the save + mark-complete buttons disappear, and the page header
 * shows a "Read-only — Monitor view" tell.
 */

import SideRail from '@/components/SideRail.vue'
import StatusPill from '@/components/StatusPill.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import TextInput from '@/components/TextInput.vue'
import SelectInput from '@/components/SelectInput.vue'
import HelperText from '@/components/HelperText.vue'
import ErrorText from '@/components/ErrorText.vue'
import CheckboxArrayInput from '@/components/CheckboxArrayInput.vue'
import FileUploadInput from '@/components/FileUploadInput.vue'
import RepeatingGroupSection from '@/components/RepeatingGroupSection.vue'

import { useCrfEntryStore } from '@/stores/crfEntry'
import { useAuthStore } from '@/stores/auth'
import type { CrfEntryStatus, CrfItem } from '@/types/crf'
import { canReopenCrf } from '@/types/crf'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const store = useCrfEntryStore()
const auth = useAuthStore()

const eventCrfOid = computed(() => String(route.params.eventCrfOid))
// Phase E.6: a CRF whose backing event_crf is SIGNED or LOCKED comes
// back as wire status 'locked'. Treat it as read-only on the SPA so
// inputs are disabled and Save draft / Mark complete buttons hide —
// the backend already 409s any write, but the legacy form let users
// type into the fields and then surprised them at submit time. The
// existing meta.readOnly path stays for the Monitor view-only mode.
const isLocked = computed(() => store.status === 'locked')
const isReadOnly = computed(() => route.meta?.readOnly === true || isLocked.value)
const readOnlyLabel = computed(() => isLocked.value
  ? t('crfEntry.lockedTell')
  : t('crfEntry.readOnlyTell'))

onMounted(() => store.load(eventCrfOid.value))
watch(eventCrfOid, (oid) => { void store.load(oid) })

function statusVariant(s: CrfEntryStatus): 'success' | 'info' | 'warning' | 'neutral' {
  switch (s) {
    case 'complete':
    case 'locked':
      return 'success'
    case 'in-progress':
      return 'info'
    case 'not-started':
      return 'warning'
    default:
      return 'neutral'
  }
}

function statusLabel(s: CrfEntryStatus): string {
  return t(`crfEntry.status.${s}`)
}

function showError(item: CrfItem): string | null {
  return store.itemErrors[item.oid] ?? null
}

/**
 * Phase E.6 — items belonging to a repeating group are rendered inside
 * the group's row template, not the top-level section. Filter them out
 * here so the section loop only sees the top-level items.
 */
function topLevelItems(items: CrfItem[]): CrfItem[] {
  return items.filter((it) => !it.groupOid)
}

/** Lookup table the {@code RepeatingGroupSection} consumes. */
const itemsByOid = computed<Record<string, CrfItem>>(() => {
  const out: Record<string, CrfItem> = {}
  for (const section of store.schema?.sections ?? []) {
    for (const item of section.items) {
      out[item.oid] = item
    }
  }
  return out
})

/** Currently-stored FileRef for a file-typed item (top-level row only). */
function fileRefFor(itemOid: string): { filename: string; bytes: number } | null {
  const v = store.values[itemOid]
  if (v && typeof v === 'object' && 'filename' in v && 'bytes' in v) {
    return v as { filename: string; bytes: number }
  }
  return null
}

/** Per-item busy flag — drives the FileUploadInput's spinner. */
function isFileBusy(_itemOid: string): boolean {
  // Coarse-grained: any save-in-flight greys all file widgets. A
  // per-item busy map can replace this in a follow-up if needed.
  return store.isSaving
}

async function onUploadFile(itemOid: string, file: File): Promise<void> {
  await store.uploadFile(itemOid, file)
}

async function onClearFile(itemOid: string): Promise<void> {
  if (!confirm(t('crfEntry.file.removeConfirm'))) return
  await store.deleteFile(itemOid)
}

function inputBindings(item: CrfItem) {
  // The store carries item values as `unknown` (each item declares its own
  // dataType separately), but the form primitives expect `string | null`.
  // The cast is safe because text-binding inputs only flow through here;
  // numeric inputs are bound directly in the template.
  const raw = store.values[item.oid]
  return {
    id: `item-${item.oid}`,
    modelValue: (raw == null ? '' : String(raw)) as string,
    error: showError(item) != null,
    'onUpdate:modelValue': (v: string) => store.setValue(item.oid, v),
  }
}

async function onSave() { await store.save() }
async function onMarkComplete() {
  await store.markComplete()
  if (store.status === 'complete') {
    router.push({ name: 'subject-matrix' })
  }
}

/**
 * Phase E A5 — reopen a completed CRF. Only rendered when the
 * current user's role + the CRF's status both permit it
 * (`canReopenCrf`). The store handles the apiPost; on success the
 * form fields flip back to editable via the existing
 * `isReadOnly` / `isSaving` chain.
 */
async function onReopen() {
  if (!confirm(t('crfEntry.action.reopenConfirm'))) return
  await store.reopen()
}

const canReopen = computed(() => {
  const role = auth.user?.role ?? null
  if (!role || !store.entry) return false
  return canReopenCrf(role, store.entry.status)
})
</script>

<template>
  <div class="flex">
    <SideRail>
      <RouterLink
        to="/subjects"
        class="flex items-center gap-2.5 px-2.5 py-1.5 rounded-md text-slate-700 hover:bg-white"
      >
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
          <rect width="18" height="18" x="3" y="3" rx="2" />
          <path d="M3 9h18M9 21V9" />
        </svg>
        {{ t('nav.subjectMatrix') }}
      </RouterLink>

      <div class="mt-4 px-2.5 text-[10px] uppercase tracking-wider text-slate-400 font-semibold">
        {{ t('crfEntry.railHeading') }}
      </div>
      <nav class="mt-1 space-y-0.5" v-if="store.schema">
        <a
          v-for="section in store.schema.sections"
          :key="section.oid"
          :href="`#${section.oid}`"
          class="block px-2.5 py-1.5 rounded-md text-slate-600 hover:bg-white text-xs"
        >
          {{ section.title }}
        </a>
      </nav>
    </SideRail>

    <main class="flex-1 max-w-3xl px-8 py-8">
      <div class="mb-6">
        <div class="text-xs text-slate-500 mb-1" v-if="store.entry">
          {{ store.entry.subjectId }} · {{ store.entry.eventLabel }}
        </div>
        <div class="flex items-center gap-3 flex-wrap">
          <h1 class="text-xl font-semibold tracking-tight" v-if="store.schema">
            {{ store.schema.name }} <span class="text-slate-400 font-normal text-sm ml-1">{{ store.schema.version }}</span>
          </h1>
          <StatusPill :variant="statusVariant(store.status)">{{ statusLabel(store.status) }}</StatusPill>
          <StatusPill v-if="isReadOnly" variant="monitor">{{ readOnlyLabel }}</StatusPill>
          <span v-if="!isReadOnly && store.pendingChanges && !store.isSaving" class="text-[11px] text-amber-700">
            {{ t('crfEntry.unsaved') }}
          </span>
          <span v-if="!isReadOnly && store.isSaving" class="text-[11px] text-muw-blue">{{ t('crfEntry.saving') }}</span>
        </div>
      </div>

      <p v-if="store.isLoading" class="text-slate-500 italic">{{ t('common.loading') }}</p>

      <p v-if="store.error && !store.entry" class="text-rose-700">{{ store.error }}</p>

      <!-- Phase E.6: explanatory banner when the CRF is locked because the
           subject has been signed. The fieldset below is already disabled
           via :disabled="isReadOnly" and the action row hides itself; the
           banner makes the *reason* legible so the operator doesn't think
           the form is broken. -->
      <div
        v-if="isLocked && store.entry && !store.isLoading"
        class="rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-900 mb-4"
        role="status"
      >
        {{ t('crfEntry.lockedBanner') }}
      </div>

      <form
        v-if="store.entry && !store.isLoading"
        class="space-y-6"
        novalidate
        @submit.prevent="onMarkComplete"
      >
        <fieldset :disabled="isReadOnly" class="space-y-6 [&:disabled_input]:cursor-not-allowed [&:disabled_select]:cursor-not-allowed">
        <section
          v-for="section in store.schema!.sections"
          :id="section.oid"
          :key="section.oid"
          class="bg-white border border-slate-200 rounded-muw p-5"
        >
          <h2 class="text-xs font-semibold uppercase tracking-wider text-slate-500 mb-1">
            {{ section.title }}
          </h2>
          <p v-if="section.instructions" class="text-[11px] text-slate-500 mb-4 leading-relaxed">
            {{ section.instructions }}
          </p>

          <div class="space-y-4">
            <div v-for="item in topLevelItems(section.items)" :key="item.oid">
              <FieldLabel :for="`item-${item.oid}`" :required="item.required">
                {{ item.label }}
              </FieldLabel>

              <template v-if="item.dataType === 'select-one' && item.options">
                <SelectInput v-bind="inputBindings(item)">
                  <option :value="undefined">— {{ t('common.search') }} —</option>
                  <option v-for="opt in item.options" :key="opt.code" :value="opt.code">{{ opt.label }}</option>
                </SelectInput>
              </template>

              <template v-else-if="item.dataType === 'select-multi' && item.options">
                <CheckboxArrayInput
                  :id-prefix="`item-${item.oid}`"
                  :model-value="(store.values[item.oid] as string[] | null | undefined) ?? []"
                  :options="item.options"
                  :error="showError(item) != null"
                  :disabled="isReadOnly"
                  @update:model-value="(v: string[]) => store.setValue(item.oid, v)"
                />
              </template>

              <template v-else-if="item.dataType === 'file'">
                <FileUploadInput
                  :id-prefix="`item-${item.oid}`"
                  :model-value="fileRefFor(item.oid)"
                  :max-bytes="store.entry?.maxFileBytes ?? 0"
                  :allowed-extensions="store.entry?.fileExtensions ?? ''"
                  :drop-prompt-label="t('crfEntry.file.dropPrompt')"
                  :browse-label="t('crfEntry.file.browse')"
                  :uploading-label="t('crfEntry.file.uploading')"
                  :remove-label="t('crfEntry.file.remove')"
                  :replace-label="t('crfEntry.file.replace')"
                  :too-big-message="t('crfEntry.file.tooBig')"
                  :bad-extension-message="t('crfEntry.file.badExtension')"
                  :busy="isFileBusy(item.oid)"
                  :disabled="isReadOnly"
                  :error="showError(item) != null"
                  @upload="(f: File) => onUploadFile(item.oid, f)"
                  @clear="onClearFile(item.oid)"
                />
              </template>

              <template v-else-if="item.dataType === 'integer' || item.dataType === 'real'">
                <input
                  v-bind="{
                    id: `item-${item.oid}`,
                    value: store.values[item.oid] ?? '',
                    'aria-invalid': showError(item) != null || undefined,
                  }"
                  type="number"
                  :min="item.min"
                  :max="item.max"
                  :step="item.dataType === 'integer' ? 1 : 0.1"
                  class="w-full px-3 py-2 border rounded-md focus:outline-none transition-colors muw-focus"
                  :class="showError(item)
                    ? 'border-rose-400 bg-rose-50/40 focus:border-rose-500 focus:ring-2 focus:ring-rose-100'
                    : 'border-slate-300 focus:border-muw-blue focus:ring-2 focus:ring-muw-blue-100'"
                  @input="store.setValue(item.oid, ($event.target as HTMLInputElement).value === '' ? null : Number(($event.target as HTMLInputElement).value))"
                />
              </template>

              <template v-else-if="item.dataType === 'date'">
                <TextInput
                  v-bind="inputBindings(item)"
                  type="text"
                  placeholder="YYYY-MM-DD"
                  inputmode="numeric"
                />
              </template>

              <template v-else>
                <TextInput v-bind="inputBindings(item)" type="text" />
              </template>

              <HelperText v-if="item.helper">{{ item.helper }}</HelperText>
              <ErrorText v-if="showError(item)">{{ showError(item) }}</ErrorText>
            </div>
          </div>
        </section>

        <!-- Phase E.6: repeating item groups. Each group is rendered as
             a standalone section with its own row table; per-cell
             writes flow through store.setValueInRow so the dirty map
             gets the right shape. -->
        <RepeatingGroupSection
          v-for="group in store.groups"
          :key="group.oid"
          :group="group"
          :items-by-oid="itemsByOid"
          :disabled="isReadOnly"
          :busy="store.isSaving"
          :add-row-label="t('crfEntry.group.addRow')"
          :delete-row-label="t('crfEntry.group.deleteRow')"
          :delete-row-confirm="t('crfEntry.group.deleteRowConfirm')"
          :repeat-max-reached-label="t('crfEntry.group.repeatMaxReached')"
          :empty-label="t('crfEntry.group.empty')"
          @add-row="() => store.addGroupRow(group.oid)"
          @delete-row="(ord: number) => store.deleteGroupRow(group.oid, ord)"
          @set-value="(payload: { rowOrdinal: number; itemOid: string; value: unknown }) => store.setValueInRow(group.oid, payload.rowOrdinal, payload.itemOid, payload.value)"
        />

        <!-- Top-level error (e.g. markComplete refused) -->
        <div
          v-if="store.error && store.entry"
          class="rounded-md bg-rose-50 border border-rose-200 px-3 py-2 text-xs text-rose-800"
          role="alert"
        >
          {{ store.error }}
        </div>

        </fieldset>

        <!-- Save action row -->
        <div
          v-if="!isReadOnly"
          class="flex items-center justify-between sticky bottom-0 bg-white/95 backdrop-blur border-t border-slate-200 -mx-8 px-8 py-3"
        >
          <div class="text-xs text-slate-500">
            <span v-if="store.entry.lastSavedAt">
              {{ t('crfEntry.lastSaved', { at: new Date(store.entry.lastSavedAt).toLocaleTimeString() }) }}
            </span>
            <span v-else>{{ t('crfEntry.notSavedYet') }}</span>
          </div>
          <div class="flex items-center gap-2">
            <RouterLink to="/subjects" class="text-xs text-slate-500 hover:text-slate-700">
              {{ t('crfEntry.cancelLink') }}
            </RouterLink>
            <!-- Phase E A5: Reopen button — only visible when the CRF
                 is complete AND the current user's role permits it.
                 Clicking confirms first (the action is GCP-significant
                 because it re-enables editing on signed-off data). -->
            <button
              v-if="canReopen"
              type="button"
              class="px-3 py-2 text-xs border border-amber-300 rounded-md bg-amber-50 hover:bg-amber-100 text-amber-800"
              :disabled="store.isSaving"
              @click="onReopen"
            >
              {{ t('crfEntry.action.reopen') }}
            </button>
            <button
              v-if="!isReadOnly"
              type="button"
              class="px-3 py-2 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-50 text-slate-700"
              :disabled="store.isSaving || !store.pendingChanges || store.status === 'complete'"
              @click="onSave"
            >
              {{ t('crfEntry.action.saveDraft') }}
            </button>
            <button
              v-if="!isReadOnly && store.status !== 'complete'"
              type="submit"
              class="px-4 py-2 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 inline-flex items-center gap-1.5 font-medium"
              :disabled="store.isSaving"
            >
              {{ t('crfEntry.action.markComplete') }}
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
                <polyline points="20 6 9 17 4 12" />
              </svg>
            </button>
          </div>
        </div>
      </form>
    </main>
  </div>
</template>
