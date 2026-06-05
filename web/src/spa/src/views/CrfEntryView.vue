<script setup lang="ts">
import { computed, onMounted, onUnmounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'

/**
 * Phase E.6 carry-over (2026-05-30): the Read-only CRF view alias.
 * Mounted by the `/event-crfs/:eventCrfOid/readonly` route via
 * `meta.readOnly = true`. In read-only mode every input is disabled,
 * the save + mark-complete buttons disappear, and the page header
 * shows a "Read-only — Monitor view" tell.
 *
 * Phase E.6 crf-entry-advanced (2026-06-05): SideRail badges +
 * concurrent-edit banner + per-item note indicators. Composed via
 * the {@link useCrfEntryAdvancedStore} so the existing
 * {@link useCrfEntryStore} stays focused on item entry.
 */

import SideRail from '@/components/SideRail.vue'
import StatusPill from '@/components/StatusPill.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import TextInput from '@/components/TextInput.vue'
import SelectInput from '@/components/SelectInput.vue'
import HelperText from '@/components/HelperText.vue'
import ErrorText from '@/components/ErrorText.vue'
import SectionBadge from '@/components/SectionBadge.vue'
import ConcurrentEditBanner from '@/components/ConcurrentEditBanner.vue'
import ItemNoteIndicator from '@/components/ItemNoteIndicator.vue'

import { useCrfEntryStore } from '@/stores/crfEntry'
import { useCrfEntryAdvancedStore } from '@/stores/crfEntryAdvanced'
import { useAuthStore } from '@/stores/auth'
import type { CrfEntryStatus, CrfItem } from '@/types/crf'
import { canReopenCrf } from '@/types/crf'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const store = useCrfEntryStore()
const advanced = useCrfEntryAdvancedStore()
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

onMounted(() => {
  void store.load(eventCrfOid.value)
  void advanced.loadAll(eventCrfOid.value)
  // Soft-lock heartbeat: do NOT start when the view is read-only
  // (Monitor view + signed/locked CRFs) — those sessions aren't
  // editing and don't need to claim presence.
  if (!isReadOnly.value) {
    advanced.startHeartbeat(eventCrfOid.value)
  }
})

watch(eventCrfOid, (oid) => {
  void store.load(oid)
  void advanced.loadAll(oid)
  advanced.stopHeartbeat()
  if (!isReadOnly.value) {
    advanced.startHeartbeat(oid)
  }
})

onUnmounted(() => advanced.stopHeartbeat())

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

/**
 * Phase E.6 — merge the server-side {@link SectionStatus} (required
 * + filled + openQueries) with the client-side derived
 * {@link sectionFilledCounts} so badges reflect typed-but-unsaved
 * edits immediately. The server's filled count is authoritative
 * for persisted state, so we max() the two so unsaved typing
 * upgrades the badge ahead of the next save.
 */
function badgeFor(sectionOid: string): { required: number; filled: number; errors: number; openQueries: number } {
  const server = advanced.sectionStatusByOid[sectionOid]
  const client = store.sectionFilledCounts[sectionOid] ?? { required: 0, filled: 0, errors: 0 }
  const required = server?.requiredCount ?? client.required
  const filled = Math.max(server?.filledCount ?? 0, client.filled)
  const errors = client.errors
  const openQueries = server?.openQueries ?? 0
  return { required, filled, errors, openQueries }
}

/** Click handler stub for the item-note indicator — wires to the
 *  popover thread view in the next slice. For now navigate to the
 *  filtered notes list pinned to this CRF. */
function onItemNoteOpen(_noteIds: string[]) {
  // Defer: open NotesDiscrepanciesView with these note ids
  // pre-selected. Tracked in the deferred list for harmonization.
  router.push({ name: 'notes-discrepancies' })
}
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
          class="flex items-center justify-between px-2.5 py-1.5 rounded-md text-slate-600 hover:bg-white text-xs"
        >
          <span class="truncate">{{ section.title }}</span>
          <SectionBadge
            :required-count="badgeFor(section.oid).required"
            :filled-count="badgeFor(section.oid).filled"
            :error-count="badgeFor(section.oid).errors"
            :open-queries="badgeFor(section.oid).openQueries"
          />
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

      <!-- Phase E.6 crf-entry-advanced: soft-lock concurrent-editor
           warning. Renders when another session's heartbeat is active. -->
      <ConcurrentEditBanner v-if="!isReadOnly" :probe="advanced.lockProbe" />

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
            <div v-for="item in section.items" :key="item.oid">
              <FieldLabel :for="`item-${item.oid}`" :required="item.required">
                {{ item.label }}
                <ItemNoteIndicator
                  v-if="advanced.noteSummaryByItemOid[item.oid]"
                  :summary="advanced.noteSummaryByItemOid[item.oid]"
                  @open="onItemNoteOpen"
                />
              </FieldLabel>

              <template v-if="item.dataType === 'select-one' && item.options">
                <SelectInput v-bind="inputBindings(item)">
                  <option :value="undefined">— {{ t('common.search') }} —</option>
                  <option v-for="opt in item.options" :key="opt.code" :value="opt.code">{{ opt.label }}</option>
                </SelectInput>
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
