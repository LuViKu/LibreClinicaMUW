<script setup lang="ts">
import DiffCard from './DiffCard.vue'
</script>

<template>
  <Story title="Primitives/DiffCard" :layout="{ type: 'grid', width: 360 }">
    <Variant title="Stacked — Audit Log Reason-for-Change">
      <div class="p-4">
        <DiffCard before-label="Before" after-label="After">
          <template #before>Severe</template>
          <template #after>Moderate</template>
        </DiffCard>
      </div>
    </Variant>

    <Variant title="Compact — Import wizard preview row">
      <div class="p-4">
        <DiffCard compact>
          <template #before>155</template>
          <template #after>55</template>
        </DiffCard>
      </div>
    </Variant>

    <Variant title="Stacked — Numeric edit">
      <div class="p-4">
        <DiffCard before-label="Existing value" after-label="Incoming value">
          <template #before>68.0</template>
          <template #after>67.5</template>
        </DiffCard>
      </div>
    </Variant>
  </Story>
</template>
