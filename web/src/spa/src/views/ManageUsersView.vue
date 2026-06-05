<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'

import SideRail from '@/components/SideRail.vue'
import DenseTable from '@/components/DenseTable.vue'
import StatusPill from '@/components/StatusPill.vue'
import TextInput from '@/components/TextInput.vue'
import SelectInput from '@/components/SelectInput.vue'
import InviteUserDialog from '@/components/InviteUserDialog.vue'
import EditUserDialog from '@/components/EditUserDialog.vue'
import UserRolesDialog from '@/components/UserRolesDialog.vue'

import { useUsersStore } from '@/stores/users'
import { useAuthStore } from '@/stores/auth'
import type { StudyUser, UserAuth, UserRole } from '@/types/user'

const { t } = useI18n()
const users = useUsersStore()
const auth = useAuthStore()

onMounted(() => { if (users.rows.length === 0) users.load() })

/* Phase E A7.1 — gate the Invite button on sysadmin-only access.
   The backend ALSO re-checks; this hides the affordance for non-admins. */
const canInvite = computed(() => auth.user?.role === 'Administrator')
const inviteOpen = ref(false)

/* Phase E A7.2 — Edit dialog state. The per-row "Edit" button opens
   the modal with the row pre-filled. Same sysadmin gate as Invite. */
const editOpen = ref(false)
const editTarget = ref<StudyUser | null>(null)
function openEdit(u: StudyUser) {
  editTarget.value = u
  editOpen.value = true
}

/* Phase E A7.3 — lifecycle (disable/restore). Disable is confirmed
   via a native dialog; restore returns a one-time password we surface
   in a small inline panel until the operator dismisses it. */
const isLifecycleBusy = ref<string | null>(null)
const restoredPanel = ref<{ username: string; password: string | null; kind: 'restore' | 'reset' | 'unlock' } | null>(null)

async function onDisable(u: StudyUser) {
  if (!confirm(t('manageUsers.lifecycle.disableConfirm', { username: u.username }))) return
  isLifecycleBusy.value = u.username
  try { await users.disableUser(u.username) }
  finally { isLifecycleBusy.value = null }
}

async function onRestore(u: StudyUser) {
  if (!confirm(t('manageUsers.lifecycle.restoreConfirm', { username: u.username }))) return
  isLifecycleBusy.value = u.username
  try {
    const result = await users.restoreUser(u.username)
    if (result.ok) restoredPanel.value = { username: result.user.username, password: result.generatedPassword, kind: 'restore' }
  } finally { isLifecycleBusy.value = null }
}

async function copyRestoredPassword() {
  if (!restoredPanel.value?.password) return
  try { await navigator.clipboard.writeText(restoredPanel.value.password) } catch { /* older browsers */ }
}

/* Phase E A7.4 — admin password reset. Only meaningful for local
   users; the per-row button hides itself when auth ∈ {sso, ldap}.
   Re-uses the A7.3 inline-panel pattern to surface the one-time
   password. */
function canResetPassword(u: StudyUser): boolean {
  return u.active && u.auth === 'local'
}

async function onResetPassword(u: StudyUser) {
  if (!confirm(t('manageUsers.resetPassword.confirm', { username: u.username }))) return
  isLifecycleBusy.value = u.username
  try {
    const result = await users.resetPassword(u.username)
    if (result.ok) restoredPanel.value = { username: u.username, password: result.generatedPassword, kind: 'reset' }
  } finally { isLifecycleBusy.value = null }
}

/* Phase E.6 unlock-user — per-row Unlock affordance. Only meaningful
   for local users that are currently locked out by repeated failed
   logins; SSO + LDAP users are gated out because the IdP / directory
   owns the credential. Disabled users land on the Restore button
   instead — restore implicitly clears the lock state as well. */
function canUnlock(u: StudyUser): boolean {
  return u.active && u.locked && u.auth === 'local'
}

async function onUnlock(u: StudyUser) {
  if (!confirm(t('manageUsers.lifecycle.unlockConfirm', { username: u.username }))) return
  isLifecycleBusy.value = u.username
  try {
    const result = await users.unlock(u.username)
    if (result.ok) restoredPanel.value = { username: u.username, password: result.generatedPassword, kind: 'unlock' }
  } finally { isLifecycleBusy.value = null }
}

/* Phase E A7.5 — role-assignments dialog state. */
const rolesOpen = ref(false)
const rolesTarget = ref<StudyUser | null>(null)
function openRoles(u: StudyUser) {
  rolesTarget.value = u
  rolesOpen.value = true
}

function roleVariant(r: UserRole): 'investigator' | 'monitor' | 'data-manager' | 'neutral' {
  switch (r) {
    case 'Investigator':  return 'investigator'
    case 'Monitor':       return 'monitor'
    case 'Data Manager':  return 'data-manager'
    default:              return 'neutral'
  }
}

function authVariant(a: UserAuth): 'success' | 'info' | 'warning' | 'neutral' {
  switch (a) {
    case 'sso':              return 'success'
    case 'local':            return 'info'
    case 'ldap':             return 'neutral'
    case 'pending-invite':   return 'warning'
  }
}

const roleOptions: { v: 'all' | UserRole; l: () => string }[] = [
  { v: 'all',            l: () => t('manageUsers.role.all') },
  { v: 'Investigator',   l: () => t('manageUsers.role.Investigator') },
  { v: 'Monitor',        l: () => t('manageUsers.role.Monitor') },
  { v: 'Data Manager',   l: () => t('manageUsers.role.Data Manager') },
  { v: 'Administrator',  l: () => t('manageUsers.role.Administrator') },
  { v: 'CRC',            l: () => t('manageUsers.role.CRC') },
]

const authOptions: { v: 'all' | UserAuth; l: () => string }[] = [
  { v: 'all',              l: () => t('manageUsers.auth.all') },
  { v: 'sso',              l: () => t('manageUsers.auth.sso') },
  { v: 'local',            l: () => t('manageUsers.auth.local') },
  { v: 'ldap',             l: () => t('manageUsers.auth.ldap') },
  { v: 'pending-invite',   l: () => t('manageUsers.auth.pending-invite') },
]

const MONTH_ABBR = ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec']
function formatDate(iso: string | null): string {
  if (!iso) return '—'
  const d = new Date(iso)
  return `${String(d.getDate()).padStart(2, '0')}-${MONTH_ABBR[d.getMonth()] ?? '???'}-${d.getFullYear()}`
}
</script>

<template>
  <div class="flex">
    <SideRail>
      <RouterLink to="/build-study" class="flex items-center gap-2.5 px-2.5 py-1.5 rounded-md text-slate-700 hover:bg-white">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
          <path d="M3 7h18M3 12h18M3 17h12" />
        </svg>
        {{ t('nav.buildStudy') }}
      </RouterLink>
      <RouterLink to="/manage-users" class="flex items-center gap-2.5 px-2.5 py-1.5 rounded-md bg-muw-blue-50 text-muw-blue font-medium" aria-current="page">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
          <path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8z" />
        </svg>
        {{ t('nav.manageUsers') }}
        <StatusPill v-if="users.pendingInviteCount > 0" compact variant="warning" class="ml-auto">
          {{ users.pendingInviteCount }}
        </StatusPill>
      </RouterLink>
      <RouterLink to="/import-crf-data" class="flex items-center gap-2.5 px-2.5 py-1.5 rounded-md text-slate-700 hover:bg-white">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75" aria-hidden="true">
          <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M17 8 12 3 7 8M12 3v15" />
        </svg>
        {{ t('nav.importCrfData') }}
      </RouterLink>
    </SideRail>

    <main class="flex-1 px-8 py-6">
      <div class="flex items-end justify-between mb-4">
        <div>
          <div class="text-xs text-slate-500 mb-1">{{ t('manageUsers.subTrail') }}</div>
          <h1 class="text-xl font-semibold tracking-tight">{{ t('manageUsers.title') }}</h1>
        </div>
        <button
          v-if="canInvite"
          class="px-3 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 inline-flex items-center gap-1.5"
          @click="inviteOpen = true"
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75">
            <line x1="12" x2="12" y1="5" y2="19" />
            <line x1="5" x2="19" y1="12" y2="12" />
          </svg>
          {{ t('manageUsers.inviteAction') }}
        </button>
      </div>

      <!-- Filter row -->
      <div class="flex flex-wrap items-center gap-3 mb-4 text-xs">
        <div class="w-64">
          <TextInput
            id="users-search"
            v-model="users.query"
            type="search"
            inputmode="search"
            :placeholder="t('manageUsers.searchPlaceholder')"
          >
            <template #prefix-icon>
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.75">
                <circle cx="11" cy="11" r="8" />
                <path d="m21 21-4.3-4.3" />
              </svg>
            </template>
          </TextInput>
        </div>

        <div class="w-44">
          <SelectInput id="users-role-filter" :model-value="users.roleFilter" @update:model-value="(v) => users.roleFilter = v as 'all' | UserRole">
            <option v-for="o in roleOptions" :key="o.v" :value="o.v">{{ o.l() }}</option>
          </SelectInput>
        </div>

        <div class="w-48">
          <SelectInput id="users-auth-filter" :model-value="users.authFilter" @update:model-value="(v) => users.authFilter = v as 'all' | UserAuth">
            <option v-for="o in authOptions" :key="o.v" :value="o.v">{{ o.l() }}</option>
          </SelectInput>
        </div>

        <label class="inline-flex items-center gap-1.5 text-slate-600 cursor-pointer">
          <input v-model="users.onlyActive" type="checkbox" class="rounded text-muw-blue" />
          {{ t('manageUsers.onlyActive') }}
        </label>

        <button
          v-if="users.query || users.roleFilter !== 'all' || users.authFilter !== 'all' || !users.onlyActive"
          type="button"
          class="text-slate-500 hover:text-slate-900"
          @click="users.clearFilters()"
        >
          {{ t('common.clear') }}
        </button>

        <div class="ml-auto text-slate-500">
          {{ t('manageUsers.showingCount', { visible: users.visibleCount, total: users.totalCount }) }}
        </div>
      </div>

      <DenseTable>
        <template #header>
          <tr class="border-b border-slate-200">
            <th scope="col" class="px-3 py-2 font-medium">{{ t('manageUsers.column.user') }}</th>
            <th scope="col" class="px-3 py-2 font-medium w-32">{{ t('manageUsers.column.role') }}</th>
            <th scope="col" class="px-3 py-2 font-medium w-24">{{ t('manageUsers.column.site') }}</th>
            <th scope="col" class="px-3 py-2 font-medium w-32">{{ t('manageUsers.column.auth') }}</th>
            <th scope="col" class="px-3 py-2 font-medium w-28">{{ t('manageUsers.column.lastLogin') }}</th>
            <th scope="col" class="px-3 py-2 font-medium w-20">{{ t('manageUsers.column.active') }}</th>
            <th scope="col" class="px-3 py-2 font-medium text-right w-16"></th>
          </tr>
        </template>

        <tr v-if="users.isLoading">
          <td :colspan="7" class="px-3 py-6 text-center text-slate-500 italic">{{ t('common.loading') }}</td>
        </tr>
        <tr v-else-if="users.visibleCount === 0">
          <td :colspan="7" class="px-3 py-6 text-center text-slate-500">{{ t('manageUsers.empty') }}</td>
        </tr>

        <tr v-for="u in users.filtered" :key="u.id">
          <td class="px-3 py-2">
            <div class="font-medium text-slate-900">{{ u.displayName }}</div>
            <div class="text-xs text-slate-500 font-mono">{{ u.username }}<span v-if="u.email"> · {{ u.email }}</span></div>
          </td>
          <td class="px-3 py-2">
            <StatusPill :variant="roleVariant(u.role)">{{ t(`manageUsers.role.${u.role}`) }}</StatusPill>
          </td>
          <td class="px-3 py-2 text-slate-600">{{ u.siteLabel ?? t('manageUsers.studyWide') }}</td>
          <td class="px-3 py-2">
            <StatusPill :variant="authVariant(u.auth)">{{ t(`manageUsers.auth.${u.auth}`) }}</StatusPill>
          </td>
          <td class="px-3 py-2 text-slate-600 font-mono text-xs">{{ formatDate(u.lastLoginAt) }}</td>
          <td class="px-3 py-2">
            <div class="flex flex-wrap items-center gap-1">
              <StatusPill v-if="u.active" variant="success">{{ t('manageUsers.activeYes') }}</StatusPill>
              <StatusPill v-else variant="neutral">{{ t('manageUsers.activeNo') }}</StatusPill>
              <StatusPill
                v-if="u.locked && u.auth === 'local'"
                variant="warning"
                compact
                :title="t('manageUsers.locked.tooltip')"
              >
                {{ t('manageUsers.locked.badge') }}
              </StatusPill>
            </div>
          </td>
          <td class="px-3 py-2 text-right">
            <div v-if="canInvite" class="inline-flex items-center gap-2 text-xs">
              <button
                class="text-muw-blue hover:underline disabled:opacity-50"
                :disabled="isLifecycleBusy === u.username"
                @click="openEdit(u)"
              >
                {{ t('manageUsers.editRow') }}
              </button>
              <span class="text-slate-300">·</span>
              <button
                v-if="u.active"
                class="text-rose-600 hover:underline disabled:opacity-50"
                :disabled="isLifecycleBusy === u.username"
                @click="onDisable(u)"
              >
                {{ t('manageUsers.lifecycle.disable') }}
              </button>
              <button
                v-else
                class="text-emerald-700 hover:underline disabled:opacity-50"
                :disabled="isLifecycleBusy === u.username"
                @click="onRestore(u)"
              >
                {{ t('manageUsers.lifecycle.restore') }}
              </button>
              <template v-if="canResetPassword(u)">
                <span class="text-slate-300">·</span>
                <button
                  class="text-amber-700 hover:underline disabled:opacity-50"
                  :disabled="isLifecycleBusy === u.username"
                  @click="onResetPassword(u)"
                >
                  {{ t('manageUsers.resetPassword.action') }}
                </button>
              </template>
              <template v-if="canUnlock(u)">
                <span class="text-slate-300">·</span>
                <button
                  class="text-amber-700 hover:underline disabled:opacity-50"
                  :disabled="isLifecycleBusy === u.username"
                  @click="onUnlock(u)"
                >
                  {{ t('manageUsers.lifecycle.unlock') }}
                </button>
              </template>
              <span class="text-slate-300">·</span>
              <button
                class="text-muw-blue hover:underline disabled:opacity-50"
                :disabled="isLifecycleBusy === u.username"
                @click="openRoles(u)"
              >
                {{ t('manageUsers.roles.openAction') }}
              </button>
            </div>
          </td>
        </tr>
      </DenseTable>

      <!-- A7.3 restore-success panel: surfaces the generated one-time
           password the same way A7.1's InviteUserDialog does. -->
      <div
        v-if="restoredPanel"
        class="mt-4 max-w-2xl rounded-md border border-emerald-200 bg-emerald-50 p-3 text-xs"
      >
        <p class="text-emerald-900 mb-2">
          <template v-if="restoredPanel.kind === 'unlock'">
            {{ t('manageUsers.lifecycle.unlockedIntro', { username: restoredPanel.username }) }}
          </template>
          <template v-else>
            {{ t('manageUsers.lifecycle.restoredIntro', { username: restoredPanel.username }) }}
          </template>
        </p>
        <div v-if="restoredPanel.password" class="flex items-center justify-between gap-3 bg-white border border-emerald-200 rounded-md p-2">
          <code class="font-mono text-sm">{{ restoredPanel.password }}</code>
          <div class="flex gap-2">
            <button
              class="px-2 py-1 text-xs border border-emerald-300 rounded-md bg-white text-emerald-800 hover:bg-emerald-100"
              @click="copyRestoredPassword"
            >
              {{ t('manageUsers.invite.copyPassword') }}
            </button>
            <button
              class="px-2 py-1 text-xs border border-slate-300 rounded-md bg-white text-slate-700 hover:bg-slate-100"
              @click="restoredPanel = null"
            >
              {{ t('common.cancel') }}
            </button>
          </div>
        </div>
        <div v-else class="text-emerald-900">
          <p class="mb-1">{{ t('manageUsers.lifecycle.restoredNoPassword') }}</p>
          <button
            class="px-2 py-1 text-xs border border-slate-300 rounded-md bg-white text-slate-700 hover:bg-slate-100"
            @click="restoredPanel = null"
          >
            {{ t('common.cancel') }}
          </button>
        </div>
      </div>
    </main>

    <InviteUserDialog v-model:open="inviteOpen" @close="users.load()" />
    <EditUserDialog v-model:open="editOpen" :user="editTarget" />
    <UserRolesDialog v-model:open="rolesOpen" :user="rolesTarget" @close="users.load()" />
  </div>
</template>
