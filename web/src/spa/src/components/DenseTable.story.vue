<script setup lang="ts">
import DenseTable from './DenseTable.vue'
import StatusPill from './StatusPill.vue'

interface Row {
  subjectId: string
  site: string
  v1Status: 'complete' | 'partial' | 'open'
  v2Status: 'complete' | 'partial' | 'open'
  v3Status: 'complete' | 'partial' | 'open'
  signed: boolean
}

const rows: Row[] = [
  { subjectId: 'M-001', site: 'München',  v1Status: 'complete', v2Status: 'complete', v3Status: 'partial',  signed: false },
  { subjectId: 'M-002', site: 'München',  v1Status: 'complete', v2Status: 'partial',  v3Status: 'open',     signed: false },
  { subjectId: 'M-003', site: 'Wien',     v1Status: 'complete', v2Status: 'complete', v3Status: 'complete', signed: true  },
  { subjectId: 'M-004', site: 'Wien',     v1Status: 'partial',  v2Status: 'open',     v3Status: 'open',     signed: false },
]

const variantFor = (s: Row['v1Status']) => (s === 'complete' ? 'success' : s === 'partial' ? 'info' : 'neutral')
const labelFor = (s: Row['v1Status']) => (s === 'complete' ? 'Complete' : s === 'partial' ? 'In progress' : 'Open')
</script>

<template>
  <Story title="Primitives/DenseTable" :layout="{ type: 'single' }">
    <Variant title="Subject Matrix shape (3 events, sign column)">
      <div class="p-6 bg-slate-50">
        <DenseTable>
          <template #header>
            <tr class="border-b border-slate-200">
              <th class="px-3 py-2 font-medium w-8"><input type="checkbox" class="rounded text-muw-blue" /></th>
              <th class="px-3 py-2 font-medium w-24">Subject ID</th>
              <th class="px-3 py-2 font-medium w-24">Site</th>
              <th class="px-3 py-2 font-medium">V1 Inclusion</th>
              <th class="px-3 py-2 font-medium">V2 Day 30</th>
              <th class="px-3 py-2 font-medium">V3 Day 90</th>
              <th class="px-3 py-2 font-medium w-20">Signed</th>
              <th class="px-3 py-2 font-medium text-right w-20"></th>
            </tr>
          </template>

          <tr v-for="row in rows" :key="row.subjectId">
            <td class="px-3 py-2"><input type="checkbox" class="rounded text-muw-blue" /></td>
            <td class="px-3 py-2 font-medium">{{ row.subjectId }}</td>
            <td class="px-3 py-2 text-slate-600">{{ row.site }}</td>
            <td class="px-3 py-2"><StatusPill :variant="variantFor(row.v1Status)">{{ labelFor(row.v1Status) }}</StatusPill></td>
            <td class="px-3 py-2"><StatusPill :variant="variantFor(row.v2Status)">{{ labelFor(row.v2Status) }}</StatusPill></td>
            <td class="px-3 py-2"><StatusPill :variant="variantFor(row.v3Status)">{{ labelFor(row.v3Status) }}</StatusPill></td>
            <td class="px-3 py-2">
              <StatusPill v-if="row.signed" variant="success">Signed</StatusPill>
              <span v-else class="text-slate-400">—</span>
            </td>
            <td class="px-3 py-2 text-right"><button class="text-muw-blue text-xs hover:underline">Open</button></td>
          </tr>

          <template #statusBar>
            <span>Showing 4 of 4</span>
            <span>Rows: 25</span>
          </template>
        </DenseTable>
      </div>
    </Variant>

    <Variant title="Empty + non-bordered">
      <div class="p-6">
        <DenseTable :bordered="false">
          <template #header>
            <tr class="border-b border-slate-200">
              <th class="px-3 py-2 font-medium">No data yet — table renders flush-on-page.</th>
            </tr>
          </template>
        </DenseTable>
      </div>
    </Variant>

    <Variant title="Sticky header at offset 56 (TopBar height)">
      <div class="p-6 max-h-96 overflow-y-auto bg-slate-50">
        <DenseTable :sticky-header-offset="0">
          <template #header>
            <tr class="border-b border-slate-200">
              <th class="px-3 py-2 font-medium">Sticky header demo (scroll within this container)</th>
            </tr>
          </template>
          <tr v-for="n in 30" :key="n">
            <td class="px-3 py-2 text-slate-600">Row {{ n }}</td>
          </tr>
        </DenseTable>
      </div>
    </Variant>
  </Story>
</template>
