<script setup lang="ts">
import { computed, onMounted } from 'vue'
import VehicleControlForm from '@/components/VehicleControlForm.vue'
import { useVehicleStore } from '@/stores/vehicle'

const store = useVehicleStore()

const observedTime = computed(() => {
  if (!store.status) return '—'
  return new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(new Date(store.status.observedAt))
})

onMounted(() => store.fetchStatus())
</script>

<template>
  <section class="vehicle-page">
    <div class="simulation-notice">
      <span class="notice-icon">i</span>
      <div>
        <strong>车辆能力为数字孪生模拟，不接入真实汽车</strong>
        <p>所有状态与控制只作用于本地演示车辆。</p>
      </div>
      <span class="mode-badge">DIGITAL TWIN</span>
    </div>

    <div v-if="store.loading && !store.status" class="state-message" role="status">
      <span class="spinner"></span>
      正在读取数字孪生状态…
    </div>

    <div v-else-if="store.offline && !store.status" class="state-message error-state" role="alert">
      <div>
        <strong>车辆模拟器离线</strong>
        <p>{{ store.error }}</p>
        <small v-if="store.traceId">Trace: {{ store.traceId }}</small>
      </div>
      <button class="secondary-action" type="button" @click="store.fetchStatus">重新连接</button>
    </div>

    <template v-else-if="store.status">
      <div v-if="store.error" class="inline-error" role="alert">
        {{ store.error }}
        <button type="button" @click="store.fetchStatus">刷新</button>
      </div>

      <div class="vehicle-grid">
        <article class="vehicle-hero">
          <div class="vehicle-meta">
            <div>
              <p class="eyebrow">ACTIVE VEHICLE</p>
              <h2>{{ store.status.displayName }}</h2>
            </div>
            <div class="online-status">
              <span></span>
              在线
            </div>
          </div>

          <div class="vehicle-visual" aria-label="RoadMind 演示车辆状态示意">
            <div class="scan-line"></div>
            <svg viewBox="0 0 720 260" role="img" aria-label="无品牌电动轿车数字孪生轮廓">
              <defs>
                <linearGradient id="carBody" x1="0" x2="1">
                  <stop offset="0" stop-color="#dbe5eb" />
                  <stop offset="0.45" stop-color="#ffffff" />
                  <stop offset="1" stop-color="#b9c9d2" />
                </linearGradient>
              </defs>
              <path
                d="M104 174c18-45 48-67 95-74l93-15c42-7 77-2 111 18l65 38 105 19c25 5 39 20 42 45H86c2-13 8-23 18-31Z"
                fill="url(#carBody)"
                stroke="#8fa4b1"
                stroke-width="3"
              />
              <path d="m245 105 60-10c37-6 65-1 96 16l41 25H211l34-31Z" fill="#b9d8e8" opacity=".82" />
              <path d="M305 96v40M407 114l-20 22" stroke="#8fa4b1" stroke-width="3" />
              <circle cx="207" cy="203" r="38" fill="#253545" />
              <circle cx="207" cy="203" r="21" fill="#d6e0e5" stroke="#8799a5" stroke-width="5" />
              <circle cx="514" cy="203" r="38" fill="#253545" />
              <circle cx="514" cy="203" r="21" fill="#d6e0e5" stroke="#8799a5" stroke-width="5" />
              <path d="M96 184h78M548 173h54" stroke="#ff6a2b" stroke-width="6" stroke-linecap="round" />
            </svg>
            <div class="vehicle-coordinate">{{ store.status.location.city }} · {{ store.status.location.coordinateSystem }}</div>
          </div>

          <div class="telemetry-row">
            <div class="telemetry-primary">
              <span class="metric-value">{{ store.status.batteryPercent.toFixed(0) }}</span>
              <span class="metric-unit">%</span>
              <p>当前电量</p>
            </div>
            <div>
              <strong>{{ store.status.estimatedRangeKm.toFixed(0) }} km</strong>
              <span>模拟续航</span>
            </div>
            <div>
              <strong>{{ store.status.cabinTemperature.toFixed(1) }}°C</strong>
              <span>车内温度</span>
            </div>
            <div>
              <strong>{{ store.status.doorLocked ? '已锁车' : '未锁车' }}</strong>
              <span>门锁状态</span>
            </div>
          </div>
        </article>

        <VehicleControlForm
          :status="store.status"
          :updating="store.updating"
          @submit="store.updateState"
        />
      </div>

      <div class="detail-strip">
        <div>
          <span>当前档位</span>
          <strong>{{ store.status.gear }}</strong>
        </div>
        <div>
          <span>充电状态</span>
          <strong>{{ store.status.charging ? '充电中' : '未充电' }}</strong>
        </div>
        <div>
          <span>前轮胎压</span>
          <strong>{{ store.status.tirePressure.frontLeft }} / {{ store.status.tirePressure.frontRight }} bar</strong>
        </div>
        <div>
          <span>最后观测</span>
          <strong>{{ observedTime }}</strong>
        </div>
      </div>
    </template>
  </section>
</template>
