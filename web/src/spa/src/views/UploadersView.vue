<script setup lang="ts">
/**
 * Uploaders and tokens, for the system administrator and for the Standort-Admin.
 *
 * An uploader is the program on an acquisition PC (the Export Watcher, the
 * Optomed Bridge) that brings device files into one location's inbox. It
 * identifies itself with a token that belongs to exactly one location, so
 * where a file came from is settled, never guessed. This page issues those
 * tokens: it lists a location's uploaders with the token's hint, status and
 * use, creates an uploader (its first token is shown once), renames it,
 * replaces its token and revokes it.
 *
 * Who is here. The page is for whoever may manage at least one location: a
 * system administrator (every location) or a Standort-Admin (their own),
 * which `/me` reports as `manageableLocations`. A Standort-Admin is no
 * administrator and does not see the System rail, so the page asks for
 * nothing that only an administrator may read: the locations to choose from
 * come from `/me`, not from the locations list.
 *
 * The list needs no polling. The tokens' last use and last report are as
 * fresh as the page; "Aktualisieren" reads them again, for the minute after
 * a token was entered on a PC.
 *
 * Dangerous changes ask first (replacing a token cuts off the program that
 * still has the old one, revoking refuses it), the server's refusals are
 * shown rather than swallowed, and every change confirms itself with a
 * notice. The one thing no notice may carry is the token: it appears only in
 * its own dialog (UploaderTokenDialog), once, and is let go of when that
 * dialog closes.
 *
 * Before a switch to multi mode. In single-location mode the server still
 * takes files from programs that send no token; after a switch to multi mode
 * (`LIBRECLINICA_DEPLOYMENT_LOCATIONS=multi`) it turns them away. So a system
 * administrator in single mode sees how many programs reported in the last
 * 7 days without a token, and which: the switch is safe once that reads 0.
 * Nobody else is asked about it (the server answers a Standort-Admin 403),
 * multi mode has no use for it, and a failed read is a quiet note.
 */
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import SystemRail from '@/components/SystemRail.vue'
import PageHeader from '@/components/PageHeader.vue'
import DenseTable from '@/components/DenseTable.vue'
import StatusPill from '@/components/StatusPill.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import SelectInput from '@/components/SelectInput.vue'
import UploaderEditDialog from '@/components/UploaderEditDialog.vue'
import UploaderTokenDialog from '@/components/UploaderTokenDialog.vue'

import { useAuthStore } from '@/stores/auth'
import { useUploadersStore } from '@/stores/uploaders'
import { useAdminIngestSourcesStore } from '@/stores/adminIngestSources'
import { useNotificationsStore } from '@/stores/notifications'
import { useConfirm } from '@/composables/useConfirm'
import { formatAgo, formatInstant, secondsSince } from '@/lib/byteFormat'
import { programName } from '@/lib/uploaderPrograms'
import type { AnonymousUploaders } from '@/types/ingestSource'
import type { ManagedUploader } from '@/types/uploader'

const { t, locale } = useI18n()
const auth = useAuthStore()
const store = useUploadersStore()
const notifications = useNotificationsStore()
const confirm = useConfirm()
const sources = useAdminIngestSourcesStore()

onMounted(() => {
  void store.load()
  void loadPreSwitch()
})

/** Reads the list again, and the pre-switch count with it: a PC that now sends its token drops out. */
function refresh() {
  void store.load()
  void loadPreSwitch()
}

/* ------------------------------ Before a switch to multi mode ------------------------------ */

/** Only a system administrator may ask, and only single mode has a switch ahead. */
const preSwitchApplies = computed(() => auth.isSysAdmin && auth.locationMode === 'single')

type PreSwitch =
  | { state: 'idle' }
  | { state: 'ready'; anonymous: AnonymousUploaders }
  | { state: 'failed' }
const preSwitch = ref<PreSwitch>({ state: 'idle' })
/** Counts the reads, so that an older answer does not overwrite a newer one. */
let preSwitchSeq = 0

async function loadPreSwitch(): Promise<void> {
  if (!preSwitchApplies.value) return
  const seq = ++preSwitchSeq
  const result = await sources.fetchAnonymousUploaders()
  if (seq !== preSwitchSeq) return
  preSwitch.value = result.ok ? { state: 'ready', anonymous: result.anonymous } : { state: 'failed' }
}

function preSwitchLabel(i: AnonymousUploaders['instances'][number]): string {
  return [
    i.displayName?.trim() || t('uploaders.preSwitch.unnamed'),
    programName(i.kind),
    t('uploaders.preSwitch.seen', { when: ago(i.lastSeenAt) }),
  ].join(' · ')
}

/* ------------------------------ The location shown ------------------------------ */

const locations = computed(() => auth.manageableLocations)
const selectedId = ref<number | null>(locations.value[0]?.id ?? null)

// The list of locations is the session's: when it changes under the page
// (rights granted or withdrawn), keep the choice if it still stands, else
// start again on the first.
watch(locations, (list) => {
  if (!list.some((l) => l.id === selectedId.value)) selectedId.value = list[0]?.id ?? null
})

const selected = computed(() => locations.value.find((l) => l.id === selectedId.value) ?? null)
const rows = computed(() => (selected.value ? store.forLocation(selected.value.id) : []))
const activeCount = computed(() => rows.value.filter((u) => u.status === 'active').length)

function onLocationChange(value: string) {
  selectedId.value = Number(value)
}

function locationOption(l: { name: string; code: string }): string {
  return `${l.name} (${l.code})`
}

/* ------------------------------ Times ------------------------------ */

/** "2 minutes ago" for an instant; the exact time is the tooltip. */
function ago(iso: string): string {
  return formatAgo(secondsSince(iso), locale.value)
}

/* ------------------------------ Create and rename ------------------------------ */

const dialogOpen = ref(false)
const dialogExisting = ref<ManagedUploader | null>(null)
/** What began the current piece of work, so that the cursor goes back to it afterwards. */
let opener: HTMLElement | null = null

function rememberOpener(event: Event) {
  opener = event.currentTarget instanceof HTMLElement ? event.currentTarget : null
}

function openDialog(existing: ManagedUploader | null, event: Event) {
  rememberOpener(event)
  dialogExisting.value = existing
  dialogOpen.value = true
}

function onDialogOpen(open: boolean) {
  dialogOpen.value = open
  // A token dialog may follow a create; it takes the cursor back to the opener when it closes.
  if (!open && issued.value === null) void nextTick(() => opener?.focus())
}

function onSaved(result: { uploader: ManagedUploader; token: string | null }, mode: 'create' | 'rename') {
  if (mode === 'rename') {
    notifications.success(t('uploaders.renamed', { name: result.uploader.displayName }))
    return
  }
  notifications.success(t('uploaders.created', { name: result.uploader.displayName }))
  if (result.token !== null) showToken(result.uploader, result.token, 'created')
}

/* ------------------------------ The token, shown once ------------------------------ */

interface Issued {
  uploader: ManagedUploader
  token: string
  mode: 'created' | 'replaced'
}
/** The token lives here and nowhere else; setting it to null lets it go. */
const issued = ref<Issued | null>(null)

function showToken(uploader: ManagedUploader, token: string, mode: 'created' | 'replaced') {
  issued.value = { uploader, token, mode }
}

function onTokenOpen(open: boolean) {
  if (open) return
  issued.value = null
  void nextTick(() => opener?.focus())
}

/* ------------------------------ Replace the token and revoke ------------------------------ */

/** The server's refusal of a replace or revoke, above the list where the rows are. */
const actionError = ref<string | null>(null)

/** After a refusal that means the row is no longer this person's to change, read the list again. */
function afterRefusal(status: number | null) {
  if (status === 404) void store.load()
}

async function replaceToken(u: ManagedUploader, event: Event) {
  rememberOpener(event)
  actionError.value = null
  const ok = await confirm({
    title: t('uploaders.replaceTitle'),
    message: t(u.status === 'revoked' ? 'uploaders.replaceConfirmRevoked' : 'uploaders.replaceConfirm', {
      name: u.displayName,
    }),
    confirmLabel: t('uploaders.action.replace'),
    danger: true,
  })
  if (!ok) {
    // The confirmation closes with the cursor inside it: back to the button that asked.
    void nextTick(() => opener?.focus())
    return
  }
  const result = await store.replaceToken(u.id)
  if (result.ok) {
    notifications.success(t('uploaders.replaced', { name: result.uploader.displayName }))
    showToken(result.uploader, result.token, 'replaced')
  } else {
    actionError.value = t('uploaders.replaceFailed', { name: u.displayName, reason: result.message })
    afterRefusal(result.status)
    void nextTick(() => opener?.focus())
  }
}

async function revoke(u: ManagedUploader, event: Event) {
  rememberOpener(event)
  actionError.value = null
  const ok = await confirm({
    title: t('uploaders.revokeTitle'),
    message: t('uploaders.revokeConfirm', { name: u.displayName }),
    confirmLabel: t('uploaders.action.revoke'),
    danger: true,
  })
  if (!ok) {
    void nextTick(() => opener?.focus())
    return
  }
  const result = await store.revoke(u.id)
  if (result.ok) {
    notifications.success(t('uploaders.revoked', { name: result.uploader.displayName }))
    // The revoke button that was pressed is gone: the cursor goes on to the way back.
    await nextTick()
    document.getElementById(`uploader-replace-${u.id}`)?.focus()
  } else {
    actionError.value = t('uploaders.revokeFailed', { name: u.displayName, reason: result.message })
    afterRefusal(result.status)
    void nextTick(() => opener?.focus())
  }
}
</script>

<template>
  <div class="flex">
    <!-- Only the administrators' section has a rail; a Standort-Admin comes in from the
         Home card and has no System section to navigate. -->
    <SystemRail v-if="auth.isSysAdmin" />

    <div class="flex-1 min-w-0 max-w-6xl px-8 py-6" :class="auth.isSysAdmin ? '' : 'mx-auto'">
      <!-- DR-030: a flat page of its section gets no trail and no eyebrow. -->
      <PageHeader :title="t('uploaders.title')">
        <template #intro>
          <p v-if="store.loaded && selected" class="text-xs text-slate-500 mt-1" data-testid="uploaders-subtitle">
            {{ t('uploaders.subtitle', { count: rows.length, active: activeCount }) }}
          </p>
          <p class="text-xs text-slate-500 mt-1 max-w-3xl leading-relaxed">{{ t('uploaders.intro') }}</p>
        </template>
        <template v-if="selected && !store.error" #actions>
          <button
            type="button"
            class="px-3 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium muw-focus"
            data-testid="uploaders-create"
            @click="openDialog(null, $event)"
          >
            {{ t('uploaders.create') }}
          </button>
        </template>
      </PageHeader>

      <!-- Single mode, system administrator: how many programs a switch to multi mode would turn away. -->
      <section
        v-if="preSwitchApplies && preSwitch.state !== 'idle'"
        class="mb-4 rounded-md border px-3 py-2.5 text-xs leading-relaxed"
        :class="preSwitch.state === 'ready' && preSwitch.anonymous.count > 0
          ? 'border-amber-300 bg-amber-50 text-amber-950'
          : 'border-slate-200 bg-slate-50 text-slate-600'"
        aria-labelledby="uploaders-preswitch-title"
        data-testid="uploaders-preswitch"
        :data-state="preSwitch.state"
      >
        <h2 id="uploaders-preswitch-title" class="sr-only">{{ t('uploaders.preSwitch.title') }}</h2>
        <template v-if="preSwitch.state === 'ready'">
          <p v-if="preSwitch.anonymous.count === 0" data-testid="uploaders-preswitch-none">
            {{ t('uploaders.preSwitch.none') }}
          </p>
          <template v-else>
            <p class="text-[13px] font-semibold" data-testid="uploaders-preswitch-count">
              {{ t('uploaders.preSwitch.count', preSwitch.anonymous.count) }}
            </p>
            <p class="mt-1">{{ t('uploaders.preSwitch.hint') }}</p>
            <details v-if="preSwitch.anonymous.instances.length > 0" class="mt-1.5">
              <summary class="cursor-pointer font-medium muw-focus rounded-sm">{{ t('uploaders.preSwitch.show') }}</summary>
              <ul class="mt-1 max-h-40 list-disc space-y-0.5 overflow-y-auto pl-5" data-testid="uploaders-preswitch-list">
                <li v-for="(u, i) in preSwitch.anonymous.instances" :key="i" data-testid="uploaders-preswitch-item">
                  {{ preSwitchLabel(u) }}
                </li>
              </ul>
              <p
                v-if="preSwitch.anonymous.count > preSwitch.anonymous.instances.length"
                class="mt-1"
                data-testid="uploaders-preswitch-more"
              >
                {{ t('uploaders.preSwitch.more', { n: preSwitch.anonymous.count - preSwitch.anonymous.instances.length }) }}
              </p>
            </details>
          </template>
        </template>
        <p v-else class="italic" data-testid="uploaders-preswitch-unknown">{{ t('uploaders.preSwitch.unknown') }}</p>
      </section>

      <p
        v-if="!selected"
        class="rounded-md border border-slate-200 bg-white px-4 py-6 text-center text-sm text-slate-600"
        data-testid="uploaders-no-location"
      >
        {{ t('uploaders.noLocation') }}
      </p>

      <template v-else>
        <div class="mb-3 flex flex-wrap items-end justify-between gap-3">
          <div v-if="locations.length > 1" class="w-72 max-w-full">
            <FieldLabel for="uploaders-location">{{ t('uploaders.location.label') }}</FieldLabel>
            <SelectInput
              id="uploaders-location"
              :model-value="String(selectedId)"
              @update:model-value="onLocationChange"
            >
              <option v-for="l in locations" :key="l.id" :value="String(l.id)">{{ locationOption(l) }}</option>
            </SelectInput>
          </div>
          <p v-else class="text-sm text-slate-700" data-testid="uploaders-location-single">
            {{ t('uploaders.location.single', { name: selected.name }) }}
          </p>

          <button
            type="button"
            class="px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-100 text-slate-700 disabled:opacity-50 muw-focus"
            :disabled="store.isLoading"
            data-testid="uploaders-refresh"
            @click="refresh()"
          >
            {{ t('uploaders.refresh') }}
          </button>
        </div>

        <div
          v-if="store.error"
          class="mb-3 rounded-md bg-rose-50 border border-rose-200 px-3 py-2 text-xs text-rose-800"
          role="alert"
          data-testid="uploaders-error"
        >
          {{ store.error }}
        </div>
        <div
          v-if="actionError"
          class="mb-3 flex items-start gap-2 rounded-md border border-rose-200 bg-rose-50 px-3 py-2 text-xs text-rose-800"
          role="alert"
          data-testid="uploaders-action-error"
        >
          <span class="flex-1">{{ actionError }}</span>
          <button
            type="button"
            class="shrink-0 text-rose-500 hover:text-rose-700 muw-focus"
            :aria-label="t('common.dismiss')"
            data-testid="uploaders-action-error-dismiss"
            @click="actionError = null"
          >✕</button>
        </div>

        <DenseTable>
          <template #header>
            <tr class="border-b border-slate-200">
              <th scope="col" class="px-3 py-2 font-medium">{{ t('uploaders.column.name') }}</th>
              <th scope="col" class="px-3 py-2 font-medium">{{ t('uploaders.column.token') }}</th>
              <th scope="col" class="px-3 py-2 font-medium">{{ t('uploaders.column.status') }}</th>
              <th scope="col" class="px-3 py-2 font-medium">{{ t('uploaders.column.created') }}</th>
              <th scope="col" class="px-3 py-2 font-medium">{{ t('uploaders.column.lastUsed') }}</th>
              <th scope="col" class="px-3 py-2 font-medium">{{ t('uploaders.column.lastReport') }}</th>
              <th scope="col" class="px-3 py-2 font-medium text-right">
                <span class="sr-only">{{ t('common.actions') }}</span>
              </th>
            </tr>
          </template>

          <!-- The first load shows a row of its own; a refresh keeps the rows that are there. -->
          <tr v-if="store.isLoading && !store.loaded">
            <td :colspan="7" class="px-3 py-6 text-center text-slate-500 italic">{{ t('common.loading') }}</td>
          </tr>
          <tr v-else-if="rows.length === 0 && !store.error">
            <td
              :colspan="7"
              class="px-3 py-6 text-center text-slate-500"
              data-testid="uploaders-empty"
            >
              {{ t('uploaders.empty') }}
            </td>
          </tr>

          <template v-else>
            <tr v-for="u in rows" :key="u.id" :data-testid="`uploader-row-${u.id}`">
              <td class="px-3 py-2 font-medium text-slate-900" :data-testid="`uploader-name-${u.id}`">
                {{ u.displayName }}
              </td>
              <td class="px-3 py-2 font-mono text-xs text-slate-700" :data-testid="`uploader-token-${u.id}`">
                <template v-if="u.tokenHint">mwu_{{ u.tokenHint }}…</template>
                <template v-else>—</template>
              </td>
              <td class="px-3 py-2" :data-testid="`uploader-status-${u.id}`">
                <StatusPill :variant="u.status === 'active' ? 'success' : 'danger'">
                  {{ t(`uploaders.status.${u.status}`) }}
                </StatusPill>
                <div v-if="u.status === 'revoked' && u.revokedAt" class="mt-0.5 text-xs text-slate-500">
                  {{ formatInstant(u.revokedAt, locale) }}
                </div>
              </td>
              <td class="px-3 py-2 text-xs text-slate-700" :data-testid="`uploader-created-${u.id}`">
                <div>{{ formatInstant(u.createdAt, locale) }}</div>
                <div v-if="u.createdBy" class="text-slate-500">{{ t('uploaders.createdBy', { user: u.createdBy }) }}</div>
              </td>
              <td class="px-3 py-2 text-xs text-slate-700" :data-testid="`uploader-used-${u.id}`">
                <time v-if="u.lastUsedAt" :datetime="u.lastUsedAt" :title="formatInstant(u.lastUsedAt, locale)">{{ ago(u.lastUsedAt) }}</time>
                <span v-else class="text-slate-500">{{ t('uploaders.never') }}</span>
              </td>
              <td class="px-3 py-2 text-xs text-slate-700" :data-testid="`uploader-report-${u.id}`">
                <time v-if="u.lastHeartbeatAt" :datetime="u.lastHeartbeatAt" :title="formatInstant(u.lastHeartbeatAt, locale)">{{ ago(u.lastHeartbeatAt) }}</time>
                <span v-else class="text-slate-500">{{ t('uploaders.never') }}</span>
              </td>
              <td class="px-3 py-2 text-right">
                <div class="inline-flex items-center gap-2 text-xs whitespace-nowrap">
                  <button
                    :id="`uploader-rename-${u.id}`"
                    type="button"
                    class="text-muw-blue hover:underline muw-focus rounded-sm"
                    :aria-label="t('uploaders.action.renameAria', { name: u.displayName })"
                    :data-testid="`uploader-rename-${u.id}`"
                    @click="openDialog(u, $event)"
                  >
                    {{ t('uploaders.action.rename') }}
                  </button>
                  <span class="text-slate-400" aria-hidden="true">·</span>
                  <button
                    :id="`uploader-replace-${u.id}`"
                    type="button"
                    class="text-muw-blue hover:underline muw-focus rounded-sm"
                    :aria-label="t('uploaders.action.replaceAria', { name: u.displayName })"
                    :data-testid="`uploader-replace-${u.id}`"
                    @click="replaceToken(u, $event)"
                  >
                    {{ t('uploaders.action.replace') }}
                  </button>
                  <template v-if="u.status === 'active'">
                    <span class="text-slate-400" aria-hidden="true">·</span>
                    <button
                      :id="`uploader-revoke-${u.id}`"
                      type="button"
                      class="text-rose-600 hover:underline muw-focus rounded-sm"
                      :aria-label="t('uploaders.action.revokeAria', { name: u.displayName })"
                      :data-testid="`uploader-revoke-${u.id}`"
                      @click="revoke(u, $event)"
                    >
                      {{ t('uploaders.action.revoke') }}
                    </button>
                  </template>
                </div>
              </td>
            </tr>
          </template>
        </DenseTable>
      </template>
    </div>

    <UploaderEditDialog
      :open="dialogOpen"
      :existing="dialogExisting"
      :location="selected"
      @update:open="onDialogOpen"
      @saved="onSaved"
    />
    <UploaderTokenDialog
      :open="issued !== null"
      :uploader-name="issued?.uploader.displayName ?? ''"
      :token="issued?.token ?? ''"
      :mode="issued?.mode ?? 'created'"
      :location-name="issued?.uploader.locationName ?? ''"
      @update:open="onTokenOpen"
    />
  </div>
</template>
