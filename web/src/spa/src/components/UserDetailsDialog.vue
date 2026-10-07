<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'

import Modal from '@/components/Modal.vue'
import { formatDate } from '@/lib/dateFormat'
import type { StudyUser } from '@/types/user'

/**
 * One account's details, read-only: what the legacy View User page showed
 * and what a periodic access review checks. Profile fields, both
 * administrator flags (a technical administrator is also a business
 * administrator, as in the legacy page), status, and who created and last
 * changed the account, and when. Roles are in the Roles dialog.
 */
interface Props {
  open: boolean
  user: StudyUser | null
}
const props = defineProps<Props>()
const emit = defineEmits<{ 'update:open': [v: boolean] }>()

const { t } = useI18n()

const rows = computed<{ key: string; value: string }[]>(() => {
  const u = props.user
  if (!u) return []
  const yes = t('manageUsers.details.yes')
  const no = t('manageUsers.details.no')
  const isAdmin = u.userType === 'SYSADMIN' || u.userType === 'TECHADMIN'
  const by = (date: string | null | undefined, who: string | null | undefined) =>
    date ? (who ? t('manageUsers.details.dateBy', { date: formatDate(date), user: who }) : formatDate(date)) : '—'
  return [
    { key: 'username', value: u.username },
    { key: 'firstName', value: u.firstName ?? '—' },
    { key: 'lastName', value: u.lastName ?? '—' },
    { key: 'email', value: u.email ?? '—' },
    { key: 'phone', value: u.phone ?? '—' },
    { key: 'affiliation', value: u.institutionalAffiliation ?? '—' },
    { key: 'businessAdmin', value: isAdmin ? yes : no },
    { key: 'technicalAdmin', value: u.userType === 'TECHADMIN' ? yes : no },
    {
      key: 'status',
      value: (u.active ? t('manageUsers.activeYes') : t('manageUsers.activeNo'))
        + (u.locked ? ` · ${t('manageUsers.locked.badge')}` : ''),
    },
    { key: 'auth', value: t(`manageUsers.auth.${u.auth}`) },
    { key: 'lastLogin', value: formatDate(u.lastLoginAt) },
    { key: 'created', value: by(u.createdDate, u.ownerUsername) },
    { key: 'updated', value: by(u.updatedDate, u.updaterUsername) },
  ]
})
</script>

<template>
  <Modal :open="props.open" labelled-by="user-details-title" panel-class="max-w-lg" @update:open="(v) => emit('update:open', v)">
    <template #header>
      <div>
        <h2 id="user-details-title" class="text-lg font-semibold tracking-tight">{{ t('manageUsers.details.title') }}</h2>
        <p v-if="props.user" class="text-xs text-slate-500 mt-0.5">{{ props.user.displayName }}</p>
      </div>
    </template>

    <dl v-if="props.user" class="grid grid-cols-[max-content_1fr] gap-x-6 gap-y-1.5 text-sm">
      <template v-for="row in rows" :key="row.key">
        <dt class="text-slate-500">{{ t(`manageUsers.details.${row.key}`) }}</dt>
        <dd class="text-slate-900" :data-testid="`user-detail-${row.key}`">{{ row.value }}</dd>
      </template>
    </dl>

    <template #footer>
      <div />
      <button
        type="button"
        class="px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-100 text-slate-700"
        @click="emit('update:open', false)"
      >
        {{ t('manageUsers.details.close') }}
      </button>
    </template>
  </Modal>
</template>
