<script setup lang="ts">
import { ref, watch } from 'vue'
import type { VehicleStatus } from '@/types/vehicle'

const props = defineProps<{
  status: VehicleStatus
  updating: boolean
}>()

const emit = defineEmits<{
  submit: [values: { batteryPercent: number; cabinTemperature: number }]
}>()

const batteryPercent = ref(props.status.batteryPercent)
const cabinTemperature = ref(props.status.cabinTemperature)

watch(
  () => props.status,
  (status) => {
    batteryPercent.value = status.batteryPercent
    cabinTemperature.value = status.cabinTemperature
  },
)

function submit() {
  emit('submit', {
    batteryPercent: batteryPercent.value,
    cabinTemperature: cabinTemperature.value,
  })
}
</script>

<template>
  <form class="control-form" @submit.prevent="submit">
    <div class="panel-heading">
      <div>
        <p class="eyebrow">DEMO CONTROLS</p>
        <h2>受控状态修改</h2>
      </div>
      <span class="version-tag">v{{ status.stateVersion }}</span>
    </div>

    <label>
      <span>电池电量</span>
      <span class="input-with-unit">
        <input
          v-model.number="batteryPercent"
          aria-label="电池电量"
          type="number"
          min="0"
          max="100"
          step="1"
          required
        />
        <span>%</span>
      </span>
    </label>

    <label>
      <span>车内温度</span>
      <span class="input-with-unit">
        <input
          v-model.number="cabinTemperature"
          aria-label="车内温度"
          type="number"
          min="-30"
          max="60"
          step="0.5"
          required
        />
        <span>°C</span>
      </span>
    </label>

    <p class="form-note">修改请求携带当前版本和幂等键；版本过期时服务端会拒绝执行。</p>
    <button class="primary-action" type="submit" :disabled="updating">
      {{ updating ? '正在同步…' : '更新数字孪生状态' }}
    </button>
  </form>
</template>
