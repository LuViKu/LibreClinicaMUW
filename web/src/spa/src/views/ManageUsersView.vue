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
            <StatusPill v-if="u.active" variant="success">{{ t('manageUsers.activeYes') }}</StatusPill>
            <StatusPill v-else variant="neutral">{{ t('manageUsers.activeNo') }}</StatusPill>
          </td>
          <td class="px-3 py-2 text-right">
            <button
              v-if="canInvite"
              class="text-muw-blue hover:underline text-xs"
              @click="openEdit(u)"
            >
              {{ t('manageUsers.editRow') }}
            </button>
          </td>
        </tr>
      </DenseTable>
    </main>

    <InviteUserDialog v-model:open="inviteOpen" @close="users.load()" />
    <EditUserDialog v-model:open="editOpen" :user="editTarget" />
  </div>
</template>
