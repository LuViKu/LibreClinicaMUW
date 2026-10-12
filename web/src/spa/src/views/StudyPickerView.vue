<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'

import StatusPill from '@/components/StatusPill.vue'
import { useAuthStore } from '@/stores/auth'
import { useAdminStudiesStore } from '@/stores/adminStudies'
import type { StudyOption } from '@/types/auth'

/**
 * Phase E.4 M1 — Study picker.
 *
 * After a fresh login the user lands here unless the backend has
 * already auto-bound an active study (which it does when
 * `user_account.active_study_id` is set from a prior session).
 *
 * Calls `loadStudies()` on mount, lets the user pick a row, and
 * POSTs the choice via `pickStudy(oid)`. On success, redirect to
 * the requested-after-login target or `/home` by default.
 *
 * A system administrator may open any study that is not removed, so for
 * them the list is every such study and site (the admin study list),
 * not only their own bindings; without that, an administrator who holds
 * no study role would find nothing to pick.
 *
 * A Standort-Admin who holds no study role has nothing to pick either, and
 * unlike an administrator no way to get a list. That is the typical IT
 * contact of a site, and the picker would be a dead end for them: when the
 * list was read, is empty, and no study is bound, they are sent on to the
 * Uploaders page, the one place they have work. Everyone else keeps the
 * picker and its message; so does anyone when the list could not be read,
 * since an unknown list is not an empty one.
 */
const { t } = useI18n()
const router = useRouter()
const auth = useAuthStore()
const adminStudies = useAdminStudiesStore()

const submitting = ref<string | null>(null)
const localError = ref<string | null>(null)
/** Set while the picker hands a Standort-Admin on, so that it shows "loading" and not its empty message. */
const leaving = ref(false)

onMounted(async () => {
  const read = await auth.loadStudies()
  if (auth.isSysAdmin) await adminStudies.load()
  await leaveWhenNothingToPick(read)
})

const options = computed<StudyOption[]>(() => {
  if (!auth.isSysAdmin || adminStudies.error) return auth.availableStudies
  const activeOid = auth.user?.activeStudy?.oid ?? null
  return adminStudies.openable.map((o) => ({ ...o, isActive: o.oid === activeOid }))
})

const loading = computed(() => auth.isLoading || adminStudies.isLoading)

/**
 * Sends a Standort-Admin with no study to pick to the Uploaders page, replacing
 * the picker in the history so that "back" does not return to an empty list.
 *
 * Only when the picker really has nothing to offer: the lists were read (an
 * unknown list is not an empty one), none is left, and no study is bound
 * (someone with a bound study came here to switch, and has one to switch to).
 */
async function leaveWhenNothingToPick(read: boolean): Promise<void> {
  if (!read || !auth.canManageUploaders || auth.user?.activeStudy) return
  if (auth.isSysAdmin && adminStudies.error) return
  if (options.value.length > 0) return
  leaving.value = true
  try {
    const failure = await router.replace({ name: 'uploaders' })
    // A successful replace unmounts this view, and it stays "leaving" until then. If the
    // navigation did not happen (it was cancelled), the picker is still here: show what it has.
    if (failure) leaving.value = false
  } catch (e) {
    leaving.value = false
    throw e
  }
}

async function choose(option: StudyOption): Promise<void> {
  submitting.value = option.oid
  localError.value = null
  try {
    await auth.pickStudy(option.oid)
    router.push({ name: 'home' })
  } catch (e) {
    localError.value = e instanceof Error ? e.message : 'Could not bind study.'
  } finally {
    submitting.value = null
  }
}

function roleVariant(role: string): 'investigator' | 'monitor' | 'data-manager' | 'neutral' {
  switch (role) {
    case 'Investigator': return 'investigator'
    case 'Monitor': return 'monitor'
    case 'Data Manager': return 'data-manager'
    default: return 'neutral'
  }
}
</script>

<template>
  <div class="min-h-[calc(100vh-3.5rem)] flex flex-col items-center justify-center px-6 py-10 bg-slate-50">
    <div class="w-full max-w-xl">
      <div class="text-center mb-8">
        <h1 class="muw-display text-2xl font-semibold tracking-tight text-muw-blue mb-2">
          {{ t('studyPicker.title') }}
        </h1>
        <p class="text-sm text-slate-600">
          {{ t('studyPicker.subtitle', { name: auth.user?.displayName ?? '' }) }}
        </p>
      </div>

      <div v-if="(loading || leaving) && options.length === 0"
           class="text-slate-500 italic text-center py-8">
        {{ t('common.loading') }}
      </div>

      <div v-else-if="options.length === 0"
           class="rounded-muw border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800 text-center">
        {{ t('studyPicker.empty') }}
      </div>

      <ul v-else class="space-y-2">
        <li v-for="option in options" :key="option.oid">
          <button
            type="button"
            class="w-full bg-white border border-slate-200 rounded-muw px-5 py-4 cursor-pointer hover:bg-slate-50 hover:border-muw-blue transition-colors text-left flex items-center justify-between gap-4 disabled:opacity-50 disabled:cursor-not-allowed"
            :disabled="submitting !== null"
            @click="choose(option)"
          >
            <div class="flex-1 min-w-0">
              <div class="flex items-center gap-2 mb-1">
                <span class="font-medium text-slate-900 truncate">{{ option.name }}</span>
                <StatusPill v-if="option.isActive" compact variant="success">
                  {{ t('studyPicker.activeBadge') }}
                </StatusPill>
                <StatusPill v-if="option.isSite" compact variant="neutral">
                  {{ t('studyPicker.siteBadge') }}
                </StatusPill>
              </div>
              <div class="text-xs text-slate-500">
                <span v-if="option.parentName">{{ option.parentName }} · </span>
                <span class="font-mono">{{ option.oid }}</span>
              </div>
            </div>
            <StatusPill :variant="roleVariant(option.role)" compact>
              {{ option.role }}
            </StatusPill>
            <span v-if="submitting === option.oid" class="text-xs text-slate-500">
              {{ t('studyPicker.binding') }}
            </span>
            <span v-else class="text-muw-blue text-sm">→</span>
          </button>
        </li>
      </ul>

      <p v-if="localError || auth.error"
         class="mt-4 text-sm text-rose-700 text-center" role="alert">
        {{ localError ?? auth.error }}
      </p>

      <div class="mt-8 text-center text-xs text-slate-500 flex items-center justify-center gap-4">
        <button v-if="auth.user?.activeStudy"
                type="button"
                class="underline hover:text-slate-700"
                @click="router.push({ name: 'home' })">
          {{ t('common.cancel') }}
        </button>
        <button type="button" class="underline hover:text-slate-700" @click="auth.logout">
          {{ t('studyPicker.signOut') }}
        </button>
      </div>
    </div>
  </div>
</template>
