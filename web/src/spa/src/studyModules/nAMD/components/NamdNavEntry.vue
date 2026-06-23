<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

/**
 * Top-bar entry to the nAMD workspace.
 *
 * <p>Injected into the {@code nav.modules} slot by the manifest. The
 * slot store ({@code useStudyModuleStore.injectionsFor('nav.modules')})
 * only returns entries from the active module, so this component
 * mounts only when the active study has the nAMD module enrolled
 * (see {@code stores/studyModules.ts.activeModule}) — no extra v-if
 * needed at the host.
 *
 * <p>The route param {@code studyOid} comes from {@code auth.activeStudy.oid};
 * the router guard (PR #245 + hardening fix #1) verifies the URL OID
 * matches the active study at navigation time, so a stale ref here
 * would bounce back to home with a toast rather than land in the
 * wrong study's workspace.
 */
const { t } = useI18n()
const auth = useAuthStore()

const studyOid = computed<string | null>(() => auth.user?.activeStudy?.oid ?? null)
</script>

<template>
  <RouterLink
    v-if="studyOid"
    :to="{ name: 'namd-workspace', params: { studyOid } }"
    class="ml-1 inline-flex items-center gap-1.5 px-2.5 py-1 rounded-md text-xs font-medium text-muw-blue hover:bg-muw-blue-50"
    data-testid="topbar-namd-workspace-link"
  >
    <svg class="w-3.5 h-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7">
      <path d="m12 2 9 5-9 5-9-5 9-5Z" />
      <path d="m3 12 9 5 9-5" />
      <path d="m3 17 9 5 9-5" />
    </svg>
    {{ t('studyModules.namd.label') }}
  </RouterLink>
</template>
