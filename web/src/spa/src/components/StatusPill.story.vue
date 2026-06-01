<script setup lang="ts">
import StatusPill from './StatusPill.vue'
</script>

<template>
  <Story title="Primitives/StatusPill" :layout="{ type: 'grid', width: 200 }">
    <Variant title="Investigator (role)">
      <StatusPill variant="investigator">Investigator</StatusPill>
    </Variant>
    <Variant title="Monitor (role)">
      <StatusPill variant="monitor">Monitor</StatusPill>
    </Variant>
    <Variant title="Data Manager (role)">
      <StatusPill variant="data-manager">Data Manager</StatusPill>
    </Variant>

    <Variant title="Success (CRF complete)">
      <StatusPill variant="success">Complete</StatusPill>
    </Variant>
    <Variant title="Info (CRF scheduled)">
      <StatusPill variant="info">Scheduled</StatusPill>
    </Variant>
    <Variant title="Warning (failed validation)">
      <StatusPill variant="warning">Failed validation</StatusPill>
    </Variant>
    <Variant title="Danger (open query)">
      <StatusPill variant="danger">Query · New</StatusPill>
    </Variant>
    <Variant title="Neutral (annotation)">
      <StatusPill variant="neutral">Annotation</StatusPill>
    </Variant>

    <Variant title="Compact role chips (per-row)">
      <div class="flex items-center gap-1">
        <StatusPill compact variant="investigator">Inv</StatusPill>
        <StatusPill compact variant="monitor">Mon</StatusPill>
        <StatusPill compact variant="data-manager">DM</StatusPill>
      </div>
    </Variant>
  </Story>
</template>
