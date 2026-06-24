<script setup lang="ts">
/**
 * 2026-06-24 user-feedback round — public BCVA-entry portal.
 *
 * <p>Single-screen view mounted at {@code /app/bcva-entry/<studyOid>}.
 * No login: the institutional reverse proxy is the only access
 * gate. The operator picks a date (today by default), types the
 * "Eingegeben von" name, expands a visit card, types BCVA + refraction
 * per eye, and clicks Speichern.
 */
import { computed, onMounted, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'

import { useBcvaPortalStore, type VisitForm } from '@/stores/bcvaPortal'

const route = useRoute()
const { t } = useI18n()
const store = useBcvaPortalStore()

const studyOidFromRoute = computed(() => String(route.params.studyOid ?? ''))

onMounted(() => {
  store.studyOid = studyOidFromRoute.value
  store.loadVisits()
})

watch(studyOidFromRoute, (next) => {
  store.studyOid = next
  store.loadVisits()
})

watch(() => store.selectedDate, () => {
  store.loadVisits()
})

function onBlurEye(form: VisitForm, eye: 'od' | 'os') {
  store.reparseEye(form[eye])
}

function onSubmit(index: number) {
  store.commitVisit(index)
}

function bcvaPreview(form: VisitForm, eye: 'od' | 'os'): string {
  return store.formatEye(form[eye])
}
</script>

<template>
  <div data-testid="bcva-portal-view" class="min-h-screen bg-slate-50 px-6 py-8">
    <div class="max-w-3xl mx-auto">
      <!-- Header -->
      <header class="mb-6">
        <h1 class="text-2xl font-semibold text-slate-900">
          {{ t('bcvaPortal.title') }}
        </h1>
        <p class="text-sm text-slate-500 mt-1">
          <span v-if="store.study">
            {{ store.study.name }}
            <span v-if="store.study.uniqueIdentifier" class="text-slate-400">
              · {{ store.study.uniqueIdentifier }}
            </span>
          </span>
          <span v-else class="text-slate-400">
            {{ t('bcvaPortal.studyOid', { oid: studyOidFromRoute }) }}
          </span>
        </p>
      </header>

      <!-- Date picker + entered-by row -->
      <div class="bg-white rounded-muw shadow-muw-card border border-slate-200 p-4 mb-4">
        <div class="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <label class="block">
            <span class="text-xs font-medium text-slate-700">
              {{ t('bcvaPortal.date') }}
            </span>
            <input
              v-model="store.selectedDate"
              type="date"
              class="mt-1 block w-full rounded border border-slate-300 px-2 py-1.5 text-sm"
              data-testid="bcva-portal-date"
            />
          </label>
          <label class="block">
            <span class="text-xs font-medium text-slate-700">
              {{ t('bcvaPortal.enteredBy') }}
            </span>
            <input
              :value="store.enteredBy"
              @input="(e) => store.setEnteredBy((e.target as HTMLInputElement).value)"
              type="text"
              :placeholder="t('bcvaPortal.enteredByPlaceholder')"
              class="mt-1 block w-full rounded border border-slate-300 px-2 py-1.5 text-sm"
              data-testid="bcva-portal-entered-by"
            />
          </label>
        </div>
      </div>

      <!-- Loading / error / empty states -->
      <div v-if="store.loading" class="text-sm text-slate-500 px-2">
        {{ t('bcvaPortal.loading') }}
      </div>
      <div
        v-else-if="store.loadError"
        class="bg-rose-50 border border-rose-200 text-rose-700 text-sm rounded px-3 py-2 mb-3"
        data-testid="bcva-portal-load-error"
      >
        {{ store.loadError }}
      </div>
      <div
        v-else-if="store.visits.length === 0"
        class="text-sm text-slate-500 italic px-2"
        data-testid="bcva-portal-empty"
      >
        {{ t('bcvaPortal.empty') }}
      </div>

      <!-- Visit list -->
      <ul v-if="!store.loading && store.visits.length > 0" class="space-y-3">
        <li
          v-for="(form, idx) in store.visits"
          :key="form.visit.studyEventId"
          class="bg-white rounded-muw shadow-muw-card border border-slate-200"
          :data-testid="`bcva-portal-visit-${form.visit.studyEventId}`"
        >
          <div class="p-4 border-b border-slate-100 flex items-center justify-between">
            <div>
              <div class="text-sm font-semibold text-slate-900">
                {{ form.visit.subjectLabel }}
              </div>
              <div class="text-xs text-slate-500 mt-0.5">
                {{ form.visit.eventDefinitionLabel }}
                <span v-if="form.visit.dateStarted" class="ml-2 text-slate-400">
                  {{ form.visit.dateStarted }}
                </span>
              </div>
            </div>
            <div class="flex items-center gap-2">
              <span
                v-if="form.visit.bcvaAlreadyEntered && form.commitState === 'ready'"
                class="text-xs px-2 py-0.5 rounded bg-amber-100 text-amber-800 font-medium"
              >
                {{ t('bcvaPortal.alreadyEntered') }}
              </span>
              <span
                v-if="form.commitState === 'committed'"
                class="text-xs px-2 py-0.5 rounded bg-emerald-100 text-emerald-800 font-medium"
                data-testid="bcva-portal-row-committed"
              >
                {{ t('bcvaPortal.committed') }}
              </span>
            </div>
          </div>

          <div class="p-4">
            <div v-if="form.commitState === 'committed'" class="text-sm">
              <button
                type="button"
                class="text-sky-700 underline text-xs"
                @click="store.reopenVisit(idx)"
                data-testid="bcva-portal-reopen"
              >
                {{ t('bcvaPortal.reopen') }}
              </button>
            </div>
            <form
              v-else
              @submit.prevent="onSubmit(idx)"
              class="space-y-3"
              :data-testid="`bcva-portal-form-${form.visit.studyEventId}`"
            >
              <div class="grid grid-cols-2 gap-3">
                <!-- OD column on the LEFT per the ophth bilateral convention -->
                <div>
                  <div class="text-xs font-semibold text-slate-600 mb-1">OD</div>
                  <label class="block">
                    <span class="text-xs text-slate-500">{{ t('bcvaPortal.bcva') }}</span>
                    <input
                      v-model="form.od.bcvaRaw"
                      @blur="onBlurEye(form, 'od')"
                      type="text"
                      :placeholder="t('bcvaPortal.bcvaPlaceholder')"
                      class="mt-1 block w-full rounded border px-2 py-1.5 text-sm"
                      :class="form.od.bcvaError
                        ? 'border-rose-400'
                        : 'border-slate-300'"
                      :data-testid="`bcva-portal-od-bcva-${form.visit.studyEventId}`"
                    />
                    <div
                      v-if="form.od.bcvaError"
                      class="text-xs text-rose-600 mt-0.5"
                    >
                      {{ t('bcvaPortal.bcvaInvalid') }}
                    </div>
                    <div
                      v-else-if="bcvaPreview(form, 'od')"
                      class="text-xs text-slate-500 mt-0.5"
                    >
                      {{ bcvaPreview(form, 'od') }}
                    </div>
                  </label>
                  <div class="grid grid-cols-3 gap-2 mt-2">
                    <label class="block">
                      <span class="text-[10px] text-slate-500">Sph</span>
                      <input
                        v-model="form.od.sphere"
                        type="text"
                        class="mt-0.5 block w-full rounded border border-slate-300 px-1.5 py-1 text-xs"
                      />
                    </label>
                    <label class="block">
                      <span class="text-[10px] text-slate-500">Cyl</span>
                      <input
                        v-model="form.od.cylinder"
                        type="text"
                        class="mt-0.5 block w-full rounded border border-slate-300 px-1.5 py-1 text-xs"
                      />
                    </label>
                    <label class="block">
                      <span class="text-[10px] text-slate-500">Ax</span>
                      <input
                        v-model="form.od.axis"
                        type="text"
                        class="mt-0.5 block w-full rounded border border-slate-300 px-1.5 py-1 text-xs"
                      />
                    </label>
                  </div>
                </div>
                <!-- OS column on the RIGHT -->
                <div>
                  <div class="text-xs font-semibold text-slate-600 mb-1">OS</div>
                  <label class="block">
                    <span class="text-xs text-slate-500">{{ t('bcvaPortal.bcva') }}</span>
                    <input
                      v-model="form.os.bcvaRaw"
                      @blur="onBlurEye(form, 'os')"
                      type="text"
                      :placeholder="t('bcvaPortal.bcvaPlaceholder')"
                      class="mt-1 block w-full rounded border px-2 py-1.5 text-sm"
                      :class="form.os.bcvaError
                        ? 'border-rose-400'
                        : 'border-slate-300'"
                      :data-testid="`bcva-portal-os-bcva-${form.visit.studyEventId}`"
                    />
                    <div
                      v-if="form.os.bcvaError"
                      class="text-xs text-rose-600 mt-0.5"
                    >
                      {{ t('bcvaPortal.bcvaInvalid') }}
                    </div>
                    <div
                      v-else-if="bcvaPreview(form, 'os')"
                      class="text-xs text-slate-500 mt-0.5"
                    >
                      {{ bcvaPreview(form, 'os') }}
                    </div>
                  </label>
                  <div class="grid grid-cols-3 gap-2 mt-2">
                    <label class="block">
                      <span class="text-[10px] text-slate-500">Sph</span>
                      <input
                        v-model="form.os.sphere"
                        type="text"
                        class="mt-0.5 block w-full rounded border border-slate-300 px-1.5 py-1 text-xs"
                      />
                    </label>
                    <label class="block">
                      <span class="text-[10px] text-slate-500">Cyl</span>
                      <input
                        v-model="form.os.cylinder"
                        type="text"
                        class="mt-0.5 block w-full rounded border border-slate-300 px-1.5 py-1 text-xs"
                      />
                    </label>
                    <label class="block">
                      <span class="text-[10px] text-slate-500">Ax</span>
                      <input
                        v-model="form.os.axis"
                        type="text"
                        class="mt-0.5 block w-full rounded border border-slate-300 px-1.5 py-1 text-xs"
                      />
                    </label>
                  </div>
                </div>
              </div>

              <div
                v-if="form.commitError"
                class="text-xs text-rose-600"
                :data-testid="`bcva-portal-error-${form.visit.studyEventId}`"
              >
                <span v-if="form.commitError === 'invalid'">{{ t('bcvaPortal.bcvaInvalid') }}</span>
                <span v-else-if="form.commitError === 'noBcva'">{{ t('bcvaPortal.noBcvaProvided') }}</span>
                <span v-else>{{ form.commitError }}</span>
              </div>

              <div class="flex items-center justify-end gap-2">
                <button
                  type="submit"
                  class="px-3 py-1.5 rounded bg-slate-900 text-white text-sm hover:bg-slate-800 disabled:opacity-50"
                  :disabled="!store.enteredByValid || form.commitState === 'committing'"
                  :data-testid="`bcva-portal-submit-${form.visit.studyEventId}`"
                >
                  <span v-if="form.commitState === 'committing'">{{ t('bcvaPortal.submitting') }}</span>
                  <span v-else>{{ t('bcvaPortal.submit') }}</span>
                </button>
              </div>
              <div
                v-if="!store.enteredByValid"
                class="text-xs text-amber-700 text-right"
              >
                {{ t('bcvaPortal.enteredByRequired') }}
              </div>
            </form>
          </div>
        </li>
      </ul>
    </div>
  </div>
</template>
