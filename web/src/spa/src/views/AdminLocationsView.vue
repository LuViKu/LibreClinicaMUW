<script setup lang="ts">
/**
 * System → Standorte: the global locations, for the system administrator.
 *
 * A location is a physical place (a clinic, a department, a partner
 * hospital). A study is active at a location by having a site there, and
 * files that arrive from devices belong to the location of their source.
 * Not to be confused with the per-study Sites page (SitesView), whose rows
 * are called "Studien-Standorte" here.
 *
 * The list shows every location, removed ones included, with the server's
 * counts. Create and edit are one dialog; disable and restore ask first,
 * and the server's refusal of a disable (a study or site still uses the
 * location, or it is the only one) is shown, not swallowed. A location's
 * panel lists its studies and sites (read only) and the people assigned to
 * it explicitly, who can be added by username and removed. Every change
 * confirms itself with a notice.
 */
import { computed, nextTick, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'

import SystemRail from '@/components/SystemRail.vue'
import PageHeader from '@/components/PageHeader.vue'
import DenseTable from '@/components/DenseTable.vue'
import StatusPill from '@/components/StatusPill.vue'
import TextInput from '@/components/TextInput.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import ErrorText from '@/components/ErrorText.vue'
import LocationEditDialog from '@/components/LocationEditDialog.vue'

import { useAdminLocationsStore } from '@/stores/adminLocations'
import { useAdminStudiesStore } from '@/stores/adminStudies'
import { useNotificationsStore } from '@/stores/notifications'
import { useConfirm } from '@/composables/useConfirm'
import type { Location, LocationUser } from '@/types/location'

const { t, te } = useI18n()
const store = useAdminLocationsStore()
const adminStudies = useAdminStudiesStore()
const notifications = useNotificationsStore()
const confirm = useConfirm()

onMounted(() => { void store.load() })

const activeCount = computed(() => store.locations.filter((l) => l.status === 'available').length)

function nonBlank(v: string | null | undefined): v is string {
  return v != null && v.trim() !== ''
}

function placeOf(l: Location): string {
  return [l.city, l.country].filter(nonBlank).join(', ')
}

/* ------------------------------ Create and edit ------------------------------ */

const dialogOpen = ref(false)
const dialogExisting = ref<Location | null>(null)
/** What opened the dialog, so closing it without a result hands the focus back. */
let opener: HTMLElement | null = null

function openDialog(existing: Location | null, event: Event) {
  opener = event.currentTarget instanceof HTMLElement ? event.currentTarget : null
  dialogExisting.value = existing
  dialogOpen.value = true
}

function onDialogOpen(open: boolean) {
  dialogOpen.value = open
  if (!open) void nextTick(() => opener?.focus())
}

function onSaved(saved: Location, mode: 'create' | 'edit') {
  notifications.success(
    t(mode === 'create' ? 'adminLocations.created' : 'adminLocations.updated', { name: saved.name }),
  )
  // A new location is empty, and people are what it needs next: its panel opens.
  if (mode === 'create') void openDetail(saved)
}

/* ------------------------------ Disable and restore ------------------------------ */

/** The server's refusal of a disable or restore, above the list where the rows are. */
const actionError = ref<string | null>(null)

async function disable(l: Location) {
  actionError.value = null
  const ok = await confirm({
    title: t('adminLocations.disableTitle'),
    message: t('adminLocations.disableConfirm', { name: l.name }),
    confirmLabel: t('adminLocations.action.disable'),
    danger: true,
  })
  if (!ok) return
  const result = await store.disable(l.id)
  if (result.ok) notifications.success(t('adminLocations.disabled', { name: result.location.name }))
  else actionError.value = t('adminLocations.disableFailed', { name: l.name, reason: result.message })
}

async function restore(l: Location) {
  actionError.value = null
  const ok = await confirm({
    title: t('adminLocations.restoreTitle'),
    message: t('adminLocations.restoreConfirm', { name: l.name }),
    confirmLabel: t('adminLocations.action.restore'),
  })
  if (!ok) return
  const result = await store.restore(l.id)
  if (result.ok) notifications.success(t('adminLocations.restored', { name: result.location.name }))
  else actionError.value = t('adminLocations.restoreFailed', { name: l.name, reason: result.message })
}

/* ------------------------------ The location's panel ------------------------------ */

const detailTitle = ref<HTMLElement | null>(null)
const assignName = ref('')
/** A refusal of the last change in the panel; the add field is marked only when the add was refused. */
const panelError = ref<{ from: 'assign' | 'unassign'; message: string } | null>(null)
const assigning = ref(false)

/** What the panel's heading shows while the detail is still on its way. */
const panelLocation = computed<Location | null>(
  () => store.detail ?? store.locations.find((l) => l.id === store.selectedId) ?? null,
)

const address = computed(() => {
  const d = store.detail
  if (!d) return ''
  return [d.street, [d.zip, d.city].filter(nonBlank).join(' '), d.country].filter(nonBlank).join(', ')
})

const canAssign = computed(() => assignName.value.trim() !== '' && !assigning.value)

async function openDetail(l: Location) {
  assignName.value = ''
  panelError.value = null
  // For the names of the studies that sites belong to; the page lives without them.
  void adminStudies.ensureLoaded()
  await store.select(l.id)
  await nextTick()
  detailTitle.value?.focus()
}

function toggleDetail(l: Location) {
  if (store.selectedId === l.id) void closeDetail()
  else void openDetail(l)
}

async function closeDetail() {
  const id = store.selectedId
  store.clearSelection()
  panelError.value = null
  await nextTick()
  if (id !== null) document.getElementById(`location-select-${id}`)?.focus()
}

/** A site names its study; the OID stands in when the study list is not at hand. */
function parentLabel(oid: string): string {
  return adminStudies.studies.find((s) => s.oid === oid)?.name ?? oid
}

function studyStatus(status: string): string {
  const key = `adminStudies.status.${status.toUpperCase()}`
  return te(key) ? t(key) : status
}

function nameOfUser(users: LocationUser[], username: string): string {
  const hit = users.find((u) => u.username.toLowerCase() === username.toLowerCase())
  return hit?.displayName?.trim() || username
}

async function assign() {
  const d = store.detail
  if (!d || !canAssign.value) return
  const username = assignName.value.trim()
  panelError.value = null
  assigning.value = true
  try {
    const result = await store.assignUser(d.id, username)
    if (result.ok) {
      assignName.value = ''
      notifications.success(t('adminLocations.assigned', {
        user: nameOfUser(result.location.users, username),
        name: result.location.name,
      }))
      // More people usually follow: the cursor stays in the field.
      await nextTick()
      document.getElementById('location-assign-username')?.focus()
    } else {
      panelError.value = { from: 'assign', message: result.message }
    }
  } finally {
    assigning.value = false
  }
}

async function unassign(u: LocationUser) {
  const d = store.detail
  if (!d) return
  panelError.value = null
  const result = await store.unassignUser(d.id, u.username)
  if (result.ok) {
    notifications.success(t('adminLocations.unassigned', {
      user: u.displayName?.trim() || u.username,
      name: result.location.name,
    }))
    // The button that was pressed is gone: the cursor goes to the add field, or the heading.
    await nextTick()
    ;(document.getElementById('location-assign-username') ?? detailTitle.value)?.focus()
  } else {
    panelError.value = { from: 'unassign', message: result.message }
  }
}
</script>

<template>
  <div class="flex">
    <SystemRail />

    <div class="flex-1 min-w-0 max-w-6xl px-8 py-6">
      <!-- DR-030: a flat page of its section gets no trail and no eyebrow; the
           System pill in the top bar and the rail's heading say where it is. -->
      <PageHeader :title="t('adminLocations.title')">
        <template #intro>
          <p v-if="store.loaded" class="text-xs text-slate-500 mt-1" data-testid="admin-locations-subtitle">
            {{ t('adminLocations.subtitle', { count: store.locations.length, active: activeCount }) }}
          </p>
          <p class="text-xs text-slate-500 mt-1 max-w-3xl leading-relaxed">{{ t('adminLocations.intro') }}</p>
        </template>
        <template v-if="!store.error" #actions>
          <button
            type="button"
            class="px-3 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium muw-focus"
            data-testid="location-create"
            @click="openDialog(null, $event)"
          >
            {{ t('adminLocations.create') }}
          </button>
        </template>
      </PageHeader>

      <div
        v-if="store.error"
        class="mb-3 rounded-md bg-rose-50 border border-rose-200 px-3 py-2 text-xs text-rose-800"
        role="alert"
        data-testid="locations-error"
      >
        {{ store.error }}
      </div>
      <div
        v-if="actionError"
        class="mb-3 flex items-start gap-2 rounded-md border border-rose-200 bg-rose-50 px-3 py-2 text-xs text-rose-800"
        role="alert"
        data-testid="locations-action-error"
      >
        <span class="flex-1">{{ actionError }}</span>
        <button
          type="button"
          class="shrink-0 text-rose-500 hover:text-rose-700 muw-focus"
          :aria-label="t('common.dismiss')"
          data-testid="locations-action-error-dismiss"
          @click="actionError = null"
        >✕</button>
      </div>

      <DenseTable>
        <template #header>
          <tr class="border-b border-slate-200">
            <th scope="col" class="px-3 py-2 font-medium">{{ t('adminLocations.column.code') }}</th>
            <th scope="col" class="px-3 py-2 font-medium">{{ t('adminLocations.column.name') }}</th>
            <th scope="col" class="px-3 py-2 font-medium">{{ t('adminLocations.column.place') }}</th>
            <th scope="col" class="px-3 py-2 font-medium text-right">{{ t('adminLocations.column.sites') }}</th>
            <th scope="col" class="px-3 py-2 font-medium text-right">{{ t('adminLocations.column.studies') }}</th>
            <th scope="col" class="px-3 py-2 font-medium text-right">{{ t('adminLocations.column.files') }}</th>
            <th scope="col" class="px-3 py-2 font-medium text-right">{{ t('adminLocations.column.users') }}</th>
            <th scope="col" class="px-3 py-2 font-medium">{{ t('adminLocations.column.status') }}</th>
            <th scope="col" class="px-3 py-2 font-medium text-right">
              <span class="sr-only">{{ t('common.actions') }}</span>
            </th>
          </tr>
        </template>

        <tr v-if="store.isLoading">
          <td :colspan="9" class="px-3 py-6 text-center text-slate-500 italic">{{ t('common.loading') }}</td>
        </tr>
        <tr v-else-if="store.locations.length === 0 && !store.error">
          <td :colspan="9" class="px-3 py-6 text-center text-slate-500">{{ t('adminLocations.empty') }}</td>
        </tr>

        <template v-else>
          <tr
            v-for="loc in store.locations"
            :key="loc.id"
            :data-testid="`admin-location-${loc.id}`"
            :class="loc.status === 'removed' ? 'text-slate-500' : ''"
          >
            <td class="px-3 py-2 font-mono text-xs">{{ loc.code }}</td>
            <td class="px-3 py-2">
              <button
                :id="`location-select-${loc.id}`"
                type="button"
                class="font-medium text-left text-muw-blue hover:underline muw-focus rounded-sm"
                :aria-expanded="store.selectedId === loc.id"
                :aria-controls="store.selectedId === loc.id ? 'location-detail' : undefined"
                :data-testid="`location-select-${loc.id}`"
                @click="toggleDetail(loc)"
              >
                {{ loc.name }}
              </button>
            </td>
            <td class="px-3 py-2">{{ placeOf(loc) }}</td>
            <td class="px-3 py-2 text-right tabular-nums" :data-testid="`count-sites-${loc.id}`">{{ loc.siteCount }}</td>
            <td class="px-3 py-2 text-right tabular-nums" :data-testid="`count-studies-${loc.id}`">{{ loc.studyCount }}</td>
            <td class="px-3 py-2 text-right tabular-nums" :data-testid="`count-files-${loc.id}`">{{ loc.ingestItemCount }}</td>
            <td class="px-3 py-2 text-right tabular-nums" :data-testid="`count-users-${loc.id}`">{{ loc.userCount }}</td>
            <td class="px-3 py-2">
              <StatusPill :variant="loc.status === 'available' ? 'success' : 'neutral'" :data-testid="`status-${loc.id}`">
                {{ t(`adminLocations.status.${loc.status}`) }}
              </StatusPill>
            </td>
            <td class="px-3 py-2 text-right">
              <div class="inline-flex items-center gap-2 text-xs whitespace-nowrap">
                <button
                  type="button"
                  class="text-muw-blue hover:underline muw-focus rounded-sm"
                  :aria-label="t('adminLocations.action.editAria', { name: loc.name })"
                  :data-testid="`location-edit-${loc.id}`"
                  @click="openDialog(loc, $event)"
                >
                  {{ t('adminLocations.action.edit') }}
                </button>
                <span class="text-slate-400" aria-hidden="true">·</span>
                <button
                  v-if="loc.status === 'removed'"
                  type="button"
                  class="text-muw-teal-700 hover:underline muw-focus rounded-sm"
                  :aria-label="t('adminLocations.action.restoreAria', { name: loc.name })"
                  :data-testid="`location-restore-${loc.id}`"
                  @click="restore(loc)"
                >
                  {{ t('adminLocations.action.restore') }}
                </button>
                <button
                  v-else
                  type="button"
                  class="text-rose-600 hover:underline muw-focus rounded-sm"
                  :aria-label="t('adminLocations.action.disableAria', { name: loc.name })"
                  :data-testid="`location-disable-${loc.id}`"
                  @click="disable(loc)"
                >
                  {{ t('adminLocations.action.disable') }}
                </button>
              </div>
            </td>
          </tr>
        </template>
      </DenseTable>

      <section
        v-if="store.selectedId !== null"
        id="location-detail"
        class="mt-6 rounded-md border border-slate-200 bg-white p-4"
        aria-labelledby="location-detail-title"
        data-testid="location-detail"
      >
        <div class="flex items-start justify-between gap-3">
          <h2
            id="location-detail-title"
            ref="detailTitle"
            tabindex="-1"
            class="flex flex-wrap items-center gap-2 text-sm font-semibold outline-none"
            data-testid="location-detail-title"
          >
            <span>{{ panelLocation?.name }}</span>
            <span v-if="panelLocation" class="font-mono text-[11px] font-normal text-slate-500">{{ panelLocation.code }}</span>
            <StatusPill
              v-if="panelLocation"
              compact
              :variant="panelLocation.status === 'available' ? 'success' : 'neutral'"
            >
              {{ t(`adminLocations.status.${panelLocation.status}`) }}
            </StatusPill>
          </h2>
          <button
            type="button"
            class="shrink-0 text-xs text-slate-600 hover:underline muw-focus rounded-sm"
            data-testid="location-detail-close"
            @click="closeDetail"
          >
            {{ t('adminLocations.detail.close') }}
          </button>
        </div>

        <p v-if="store.detailLoading" class="mt-3 text-xs italic text-slate-500">{{ t('common.loading') }}</p>
        <div
          v-else-if="store.detailError"
          class="mt-3 rounded-md bg-rose-50 border border-rose-200 px-3 py-2 text-xs text-rose-800"
          role="alert"
          data-testid="location-detail-load-error"
        >
          {{ store.detailError }}
        </div>

        <template v-else-if="store.detail">
          <div class="mt-3">
            <h3 class="mb-0.5 text-xs font-semibold uppercase tracking-wide text-slate-500">
              {{ t('adminLocations.detail.address') }}
            </h3>
            <p class="text-[13px] text-slate-700">{{ address || t('adminLocations.detail.noAddress') }}</p>
          </div>

          <div class="mt-5 grid gap-6 md:grid-cols-2">
            <section aria-labelledby="location-studies-heading">
              <h3 id="location-studies-heading" class="mb-1 text-xs font-semibold uppercase tracking-wide text-slate-500">
                {{ t('adminLocations.detail.studiesHeading') }}
              </h3>
              <p v-if="store.detail.studies.length === 0" class="text-xs text-slate-500">
                {{ t('adminLocations.detail.studiesEmpty') }}
              </p>
              <ul v-else class="divide-y divide-slate-100 rounded-md border border-slate-200">
                <li
                  v-for="s in store.detail.studies"
                  :key="s.oid"
                  class="px-3 py-1.5 text-[13px]"
                  :data-testid="`detail-study-${s.oid}`"
                >
                  <div class="flex flex-wrap items-center gap-2">
                    <span class="font-medium text-slate-900">{{ s.name }}</span>
                    <span class="font-mono text-[11px] text-slate-500">{{ s.oid }}</span>
                    <StatusPill compact variant="neutral">
                      {{ s.isSite ? t('adminLocations.detail.siteBadge') : t('adminLocations.detail.studyBadge') }}
                    </StatusPill>
                    <StatusPill v-if="s.status !== 'available'" compact variant="warning">{{ studyStatus(s.status) }}</StatusPill>
                  </div>
                  <div v-if="s.isSite && s.parentOid" class="text-xs text-slate-500">
                    {{ t('adminLocations.detail.siteOf', { study: parentLabel(s.parentOid) }) }}
                  </div>
                </li>
              </ul>
            </section>

            <section aria-labelledby="location-users-heading">
              <h3 id="location-users-heading" class="mb-1 text-xs font-semibold uppercase tracking-wide text-slate-500">
                {{ t('adminLocations.detail.usersHeading') }}
              </h3>
              <p class="mb-3 text-xs leading-relaxed text-slate-500">{{ t('adminLocations.detail.usersHelp') }}</p>

              <form
                v-if="store.detail.status === 'available'"
                class="mb-2 flex items-end gap-2"
                data-testid="location-assign-form"
                @submit.prevent="assign"
              >
                <div class="flex-1">
                  <FieldLabel for="location-assign-username">{{ t('adminLocations.detail.addLabel') }}</FieldLabel>
                  <TextInput
                    id="location-assign-username"
                    v-model="assignName"
                    autocomplete="off"
                    :placeholder="t('adminLocations.detail.addPlaceholder')"
                    :error="panelError?.from === 'assign'"
                  />
                </div>
                <button
                  type="submit"
                  class="px-3 py-2 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium disabled:opacity-50 muw-focus"
                  :disabled="!canAssign"
                  data-testid="location-assign-submit"
                >
                  {{ assigning ? t('common.saving') : t('adminLocations.detail.add') }}
                </button>
              </form>
              <p v-else class="mb-2 text-xs text-slate-600" data-testid="location-removed-note">
                {{ t('adminLocations.detail.removedNote') }}
              </p>
              <ErrorText v-if="panelError" class="mb-2" data-testid="location-detail-error">{{ panelError.message }}</ErrorText>

              <p v-if="store.detail.users.length === 0" class="text-xs text-slate-500">
                {{ t('adminLocations.detail.usersEmpty') }}
              </p>
              <ul v-else class="mt-3 divide-y divide-slate-100 rounded-md border border-slate-200">
                <li
                  v-for="u in store.detail.users"
                  :key="u.username"
                  class="flex items-center justify-between gap-2 px-3 py-1.5 text-[13px]"
                  :data-testid="`detail-user-${u.username}`"
                >
                  <span class="min-w-0">
                    <span class="font-medium text-slate-900">{{ u.displayName }}</span>
                    <span class="ml-1 font-mono text-[11px] text-slate-500">{{ u.username }}</span>
                    <StatusPill v-if="u.canManageSources" compact variant="info" class="ml-1">
                      {{ t('adminLocations.detail.siteAdmin') }}
                    </StatusPill>
                  </span>
                  <button
                    type="button"
                    class="shrink-0 text-xs text-rose-600 hover:underline muw-focus rounded-sm"
                    :aria-label="t('adminLocations.detail.removeAria', { user: u.displayName || u.username })"
                    :data-testid="`location-unassign-${u.username}`"
                    @click="unassign(u)"
                  >
                    {{ t('adminLocations.detail.remove') }}
                  </button>
                </li>
              </ul>
            </section>
          </div>
        </template>
      </section>
    </div>

    <LocationEditDialog
      :open="dialogOpen"
      :existing="dialogExisting"
      @update:open="onDialogOpen"
      @saved="onSaved"
    />
  </div>
</template>
