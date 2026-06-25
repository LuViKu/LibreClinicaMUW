<script setup lang="ts">
/**
 * nAMD — activity pill.
 *
 * Thin wrapper around the {@link Pill} primitive that derives the tone
 * + label from the current total fluid volume. The clinical threshold is
 * pulled from {@link ACTIVITY_THRESHOLD_NL} so a future revision (e.g.
 * 25 nL after the next clinical workshop) lands as a single constant
 * edit.
 */
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import Pill from './primitives/Pill.vue'
import { ACTIVITY_THRESHOLD_NL } from '../fluid'

interface Props {
  /** Total active fluid volume (nL). Null renders the neutral "—" state. */
  activeFluidNl: number | null
}

const props = defineProps<Props>()
const { t } = useI18n()

const isActive = computed(() => (props.activeFluidNl ?? 0) > ACTIVITY_THRESHOLD_NL)
</script>

<template>
  <Pill :tone="isActive ? 'active' : 'dry'">
    {{ isActive ? t('studyModules.namd.activityActive') : t('studyModules.namd.activityDry') }}
  </Pill>
</template>
