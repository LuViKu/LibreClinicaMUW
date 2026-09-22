<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import LandingCard from '@/components/LandingCard.vue'
import { useAuthStore } from '@/stores/auth'

/**
 * Landing-page card for the nAMD workspace.
 *
 * <p>P3.0 — was {@code NamdNavEntry}, a top-bar link injected into the
 * {@code nav.modules} slot. TopBar stopped consuming that slot on
 * 2026-06-21, so the component has rendered nowhere since: the only way
 * into the workspace was the CTA on a subject's page, which means you
 * had to already know which subject you wanted. It now renders as a
 * card in the study-scoped lane of the landing page, via
 * {@code home.cards}.
 *
 * <p>The store only returns entries from modules active on the bound
 * study, so no {@code v-if} on enrollment is needed here.
 *
 * <p>The route param {@code studyOid} comes from
 * {@code auth.activeStudy.oid}; the router guard verifies the URL OID
 * matches the active study at navigation time, so a stale ref bounces
 * back to home with a toast rather than landing in another study's
 * workspace.
 */
const { t } = useI18n()
const auth = useAuthStore()

const studyOid = computed<string | null>(() => auth.user?.activeStudy?.oid ?? null)
</script>

<template>
  <LandingCard
    compact
    v-if="studyOid"
    data-card-id="namd-workspace"
    data-testid="home-namd-workspace-card"
    :to="{ name: 'namd-workspace', params: { studyOid } }"
    :role-variants="['investigator']"
    :title="t('studyModules.namd.label')"
    :description="t('studyModules.namd.open')"
  />
</template>
