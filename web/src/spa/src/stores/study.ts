import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import type { StudyBuildStatus, StudyBuildTask } from '@/types/study'

/**
 * Phase E.7 — Study-build store.
 *
 * Mock-hydrated; planned adapter at `GET /pages/api/v1/studies/{oid}/build-status`
 * per api-surface.md row 14. Until the adapter lands the SPA renders
 * a representative state for LCDemo.
 */
export const useStudyStore = defineStore('study', () => {
  const status = ref<StudyBuildStatus | null>(null)
  const isLoading = ref(false)
  const error = ref<string | null>(null)

  const completedTasks = computed(() => status.value?.tasks.filter((t) => t.status === 'complete').length ?? 0)
  const totalTasks = computed(() => status.value?.tasks.length ?? 0)
  const percentComplete = computed(() => {
    if (totalTasks.value === 0) return 0
    return Math.round((completedTasks.value / totalTasks.value) * 100)
  })

  async function load(_studyOid?: string): Promise<void> {
    isLoading.value = true
    error.value = null
    try {
      status.value = await loadMock()
    } catch (e) {
      error.value = e instanceof Error ? e.message : 'Unknown error loading study status'
    } finally {
      isLoading.value = false
    }
  }

  return {
    status,
    isLoading,
    error,
    completedTasks,
    totalTasks,
    percentComplete,
    load,
  }
})

async function loadMock(): Promise<StudyBuildStatus> {
  await new Promise((resolve) => setTimeout(resolve, 30))
  return MOCK
}

const MOCK_TASKS: StudyBuildTask[] = [
  { id: 'create-study', count: 1,  status: 'complete',    to: null              },
  { id: 'crf',          count: 12, status: 'complete',    to: null              },
  { id: 'events',       count: 5,  status: 'complete',    to: null              },
  { id: 'groups',       count: 2,  status: 'complete',    to: null              },
  { id: 'rules',        count: 18, status: 'in-progress', to: null              },
  { id: 'sites',        count: 3,  status: 'complete',    to: null              },
  { id: 'users',        count: 7,  status: 'in-progress', to: '/manage-users'   },
]

const MOCK: StudyBuildStatus = {
  studyOid: 'S_LCDEMO',
  studyName: 'LCDemo',
  studyVersion: 'v2.1',
  sites: 3,
  enrolledSubjects: 42,
  tasks: MOCK_TASKS,
}
