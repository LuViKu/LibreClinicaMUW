<script setup lang="ts">
/**
 * The home page's download of the active study's metadata: its design as
 * CDISC ODM 1.3, the document the legacy Download Study Metadata page
 * gives. It sits among the study's workspaces and looks like one, but it
 * is a button: it downloads rather than navigates. HomeView shows it only
 * to those the endpoint admits ({@link userMayDownloadStudyMetadata}).
 */
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { downloadStudyMetadata } from '@/api/studyMetadata'

const props = defineProps<{ studyOid: string }>()

const { t } = useI18n()
const downloading = ref(false)
const failed = ref(false)

async function download() {
  downloading.value = true
  failed.value = false
  try {
    await downloadStudyMetadata(props.studyOid)
  } catch {
    failed.value = true
  } finally {
    downloading.value = false
  }
}
</script>

<template>
  <button
    type="button"
    data-testid="home-study-metadata"
    class="rounded-muw border border-slate-200 bg-white hover:border-muw-blue-200 hover:shadow-muw-card transition group relative p-4 text-left disabled:opacity-60"
    :disabled="downloading"
    @click="download"
  >
    <div class="font-semibold text-slate-900 group-hover:underline mb-1 text-sm">
      {{ downloading ? t('home.studyMetadata.downloading') : t('home.studyMetadata.title') }}
    </div>
    <p class="text-slate-500 text-xs leading-relaxed line-clamp-2">{{ t('home.studyMetadata.description') }}</p>
    <p v-if="failed" role="alert" class="mt-1 text-xs text-rose-600">{{ t('home.studyMetadata.failed') }}</p>
  </button>
</template>
