<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'

import Modal from '@/components/Modal.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import HelperText from '@/components/HelperText.vue'
import ErrorText from '@/components/ErrorText.vue'
import LocationSelect from '@/components/LocationSelect.vue'

import { useSitesStore } from '@/stores/sites'
import type { LocationOption } from '@/types/location'
import type { StudyIdentity } from '@/types/study'

/**
 * Moves one site (Prüfzentrum) to another location (Standort), DR-040
 * phase 4. Offered by the Sites page in multi-location mode only, to those
 * who may manage sites.
 *
 * The select starts at the site's current location; saving sends
 * `PUT …/sites/{siteOid}` with `{ locationId }` only, which the server reads
 * as "move, leave everything else". The server re-copies the facility
 * address from the new location and audits the move. A 400 on `locationId`
 * (not available, not in the caller's scope) shows under the select; any
 * other refusal is a message of the form. On success it emits `saved` with
 * the updated site and closes.
 */
interface Props {
  open: boolean
  site: StudyIdentity | null
  parentOid: string | null
  options: LocationOption[]
}
const props = defineProps<Props>()
const emit = defineEmits<{
  'update:open': [value: boolean]
  saved: [site: StudyIdentity]
}>()

const { t } = useI18n()
const sites = useSitesStore()

const locationId = ref<number | null>(null)
const fieldError = ref<string | null>(null)
const formError = ref<string | null>(null)
const submitting = ref(false)

watch(
  () => [props.open, props.site] as const,
  ([isOpen]) => {
    if (!isOpen) return
    locationId.value = props.site?.locationId ?? null
    fieldError.value = null
    formError.value = null
    submitting.value = false
  },
  { immediate: true },
)

watch(locationId, () => { fieldError.value = null })

const current = computed(() => {
  const s = props.site
  return s?.locationId != null ? { id: s.locationId, name: s.locationName ?? s.locationCode ?? String(s.locationId) } : null
})

const canSubmit = computed(
  () =>
    !submitting.value &&
    props.site != null &&
    props.parentOid != null &&
    locationId.value != null &&
    locationId.value !== (props.site.locationId ?? null),
)

async function submit() {
  const site = props.site
  const parentOid = props.parentOid
  if (!canSubmit.value || !site || !parentOid || locationId.value == null) return
  fieldError.value = null
  formError.value = null
  submitting.value = true
  try {
    const result = await sites.update(parentOid, site.oid, { locationId: locationId.value })
    if (result.ok) {
      emit('saved', result.site)
      close()
    } else {
      const own = result.fieldErrors.locationId
      const strays = Object.entries(result.fieldErrors)
        .filter(([field]) => field !== 'locationId')
        .map(([, message]) => message)
      fieldError.value = own ?? null
      // A 400 is told by its fields; its generic "Validation failed" would only repeat them.
      formError.value =
        strays.length > 0
          ? strays.join(' ')
          : Object.keys(result.fieldErrors).length === 0
            ? (result.message ?? null)
            : null
    }
  } catch {
    // 401/403: the store keeps the server's message on its banner.
    formError.value = sites.error
  } finally {
    submitting.value = false
  }
}

function close() {
  emit('update:open', false)
}
</script>

<template>
  <Modal
    :open="props.open"
    labelled-by="site-location-dialog-title"
    panel-class="max-w-lg"
    @update:open="(v) => emit('update:open', v)"
  >
    <template #header>
      <h2 id="site-location-dialog-title" class="text-lg font-semibold tracking-tight">
        {{ t('sites.move.title') }}
      </h2>
    </template>

    <form id="site-location-form" novalidate class="space-y-3" @submit.prevent="submit">
      <p v-if="props.site" class="text-xs text-slate-600">
        {{ t('sites.move.intro', { name: props.site.name }) }}
      </p>
      <div>
        <FieldLabel for="site-move-location" required>{{ t('sites.field.location') }}</FieldLabel>
        <LocationSelect
          id="site-move-location"
          v-model="locationId"
          :options="props.options"
          :current="current"
          :error="fieldError !== null"
        />
        <ErrorText v-if="fieldError" data-testid="site-move-field-error">{{ fieldError }}</ErrorText>
        <HelperText v-else>{{ t('sites.field.locationHint') }}</HelperText>
      </div>
      <ErrorText v-if="formError" data-testid="site-move-form-error">{{ formError }}</ErrorText>
    </form>

    <template #footer>
      <div />
      <div class="flex items-center gap-2">
        <button
          type="button"
          class="px-3 py-1.5 text-xs border border-slate-200 rounded-md bg-white hover:bg-slate-100 text-slate-700 muw-focus"
          :disabled="submitting"
          @click="close"
        >
          {{ t('common.cancel') }}
        </button>
        <!-- The footer sits outside the form element, so the button names its form. -->
        <button
          type="submit"
          form="site-location-form"
          class="px-4 py-1.5 text-xs bg-muw-blue text-white rounded-md hover:bg-muw-blue-700 font-medium disabled:opacity-50 muw-focus"
          :disabled="!canSubmit"
          data-testid="site-move-submit"
        >
          {{ submitting ? t('common.saving') : t('sites.move.submit') }}
        </button>
      </div>
    </template>
  </Modal>
</template>
