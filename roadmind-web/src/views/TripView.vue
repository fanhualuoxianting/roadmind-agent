<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useTripStore } from '@/stores/trip'
import type { SimulationSpeed } from '@/types/trip'
import { AmapMapProvider } from '@/map/AmapMapProvider'
import type { MapProvider } from '@/map/MapProvider'

const store = useTripStore()
const mapContainer = ref<HTMLElement | null>(null)
const mapMode = ref<'AMAP' | 'FALLBACK'>('FALLBACK')
let mapProvider: MapProvider | null = null
const trip = computed(() => store.snapshot)
const telemetry = computed(() => trip.value?.telemetry)
const progress = computed(() => {
  if (!trip.value || trip.value.route.distanceMeters <= 0) return 0
  return Math.min(100, Math.max(0, trip.value.telemetry.travelledMeters / trip.value.route.distanceMeters * 100))
})
const eta = computed(() => telemetry.value
  ? new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(telemetry.value.estimatedArrivalTime))
  : '--:--')
const remainingKm = computed(() => Math.round((telemetry.value?.remainingDistanceMeters || 0) / 1000))
const arrivalBattery = computed(() => Math.max(0, Math.round((telemetry.value?.batteryPercent || 0) - remainingKm.value * 0.165)))
const actionLabel = computed(() => trip.value?.status === 'DRIVING' ? '暂停行程' : trip.value?.status === 'PAUSED' ? '继续行程' : '开始行程')

function mainAction() {
  if (!trip.value) return
  if (trip.value.status === 'DRIVING') void store.command('PAUSE')
  else if (trip.value.status === 'PAUSED') void store.command('RESUME')
  else void store.command('START')
}

function setSpeed(speed: SimulationSpeed) { void store.setSpeed(speed) }

async function initializeMap() {
  if (!trip.value || !mapContainer.value || !import.meta.env.VITE_AMAP_JS_KEY || mapProvider) return
  try {
    mapProvider = new AmapMapProvider(import.meta.env.VITE_AMAP_JS_KEY, import.meta.env.VITE_AMAP_SECURITY_CODE)
    await mapProvider.mount(mapContainer.value)
    mapProvider.renderRoute(trip.value.route)
    mapProvider.setVehiclePose(trip.value.telemetry)
    mapMode.value = 'AMAP'
  } catch {
    mapProvider?.destroy()
    mapProvider = null
    mapMode.value = 'FALLBACK'
  }
}

watch(() => trip.value?.route.routeHash, async () => { await nextTick(); await initializeMap() })
watch(() => telemetry.value?.sequence, () => { if (telemetry.value) mapProvider?.setVehiclePose(telemetry.value) })
onMounted(async () => { if (!store.snapshot) await store.create(); await nextTick(); await initializeMap() })
onBeforeUnmount(() => { store.disconnect(); mapProvider?.destroy() })
</script>

<template>
  <section class="trip-page">
    <header class="trip-headline">
      <div><p class="cockpit-kicker">LIVE DIGITAL TWIN JOURNEY</p><h1>南京软件谷 <span>→</span> 无锡学院</h1><p>真实道路适配 · 模拟车辆遥测 · 可恢复事件流</p></div>
      <div class="trip-live"><i :class="{ delayed: store.telemetryDelayed }"></i><strong>{{ store.telemetryDelayed ? '遥测延迟' : store.connected ? '实时同步' : '正在连接' }}</strong><small>序号 {{ telemetry?.sequence || 0 }}</small></div>
    </header>

    <div v-if="!trip" class="trip-loading glass-panel">
      <span class="spinner"></span><strong>{{ store.error || '正在创建数字孪生行程…' }}</strong>
      <button v-if="store.error" @click="store.create">重新尝试</button>
    </div>

    <div v-else class="trip-layout">
      <main class="map-stage glass-panel" aria-label="行程路线地图降级视图">
        <div class="map-toolbar"><span>◈ 路线 {{ trip.route.routeVersion }}</span><span :class="['source-chip', trip.route.sourceMode.toLowerCase()]">{{ trip.route.sourceMode }}</span><span>{{ mapMode }} · {{ trip.route.coordinateSystem }}</span></div>
        <div :class="['map-surface', { 'provider-live': mapMode === 'AMAP' }]">
          <div ref="mapContainer" class="map-live-canvas"></div>
          <div class="map-grid"></div>
          <span class="river river-a"></span><span class="river river-b"></span>
          <span class="road road-a"></span><span class="road road-b"></span><span class="road road-c"></span>
          <svg class="route-svg" viewBox="0 0 900 530" preserveAspectRatio="none" aria-hidden="true">
            <path class="route-base" d="M125 405 C210 360 230 265 340 285 S515 382 592 294 S682 140 805 116" />
            <path class="route-progress" :style="{ strokeDashoffset: `${860 - 860 * progress / 100}` }" d="M125 405 C210 360 230 265 340 285 S515 382 592 294 S682 140 805 116" />
          </svg>
          <div class="city-label nanjing">南京</div><div class="city-label zhenjiang">镇江</div><div class="city-label changzhou">常州</div><div class="city-label wuxi">无锡</div>
          <div class="map-pin origin"><i></i><span>{{ trip.route.origin.name }}</span></div>
          <div class="vehicle-marker" :style="{ left: `${14 + progress * .67}%`, top: `${78 - progress * .58}%`, transform: `rotate(${telemetry?.heading || 90}deg)` }"><span>RM</span></div>
          <div v-if="trip.route.chargingStation" class="charge-pin"><b>⚡</b><span>{{ trip.route.chargingStation.name }}</span></div>
          <div class="map-pin destination"><i></i><span>{{ trip.route.destination.name }}</span></div>
          <div class="map-disclaimer"><strong>{{ mapMode === 'AMAP' ? '高德明亮底图' : '地图降级视图' }}</strong><span>{{ trip.sourceDisclaimer }}</span></div>
          <div class="zoom-controls"><button>＋</button><button>－</button><button>◎</button></div>
        </div>
        <div class="trip-controlbar">
          <button class="round-action" :disabled="store.busy || ['COMPLETED','CANCELLED','FAILED'].includes(trip.status)" @click="mainAction">{{ trip.status === 'DRIVING' ? 'Ⅱ' : '▶' }}</button>
          <div><strong>{{ actionLabel }}</strong><small>{{ trip.status }} · {{ Math.round(telemetry?.speedKmh || 0) }} km/h（模拟）</small></div>
          <button class="cancel-trip" :disabled="store.busy || ['COMPLETED','CANCELLED'].includes(trip.status)" @click="store.command('CANCEL')">取消</button>
          <div class="speed-control"><small>模拟速度</small><button v-for="speed in ([1,5,20] as SimulationSpeed[])" :key="speed" :class="{ active: telemetry?.simulationSpeed === speed }" @click="setSpeed(speed)">{{ speed }}×</button></div>
        </div>
      </main>

      <aside class="trip-panel glass-panel">
        <header><div><small>预计到达</small><strong>{{ eta }}</strong></div><span>{{ Math.round(progress) }}%</span></header>
        <div class="trip-progress"><i :style="{ width: `${progress}%` }"></i></div>
        <div class="trip-metrics">
          <article><small>剩余里程</small><strong>{{ remainingKm }} <em>km</em></strong></article>
          <article><small>当前电量</small><strong>{{ Math.round(telemetry?.batteryPercent || 0) }}<em>%</em></strong></article>
          <article :class="{ warning: arrivalBattery < 20 }"><small>预计到达电量</small><strong>{{ arrivalBattery }}<em>%</em></strong></article>
          <article><small>推荐充电</small><strong>{{ trip.route.chargingStation?.chargingMinutes || 18 }} <em>分钟</em></strong></article>
        </div>
        <div class="agent-event" :class="{ resolved: trip.lowBatteryReplanned }"><span>✦</span><div><strong>Agent 行程事件</strong><p>{{ store.notices[0] || (arrivalBattery < 20 ? `预计到达仅剩 ${arrivalBattery}% 电量，正在检查充电策略` : '行程状态正常，持续监测电量与路线') }}</p></div></div>
        <div class="route-facts"><h3>当前路线</h3><dl><dt>数据来源</dt><dd>{{ trip.route.provider }} · {{ trip.route.sourceMode }}</dd><dt>路线版本</dt><dd>V{{ trip.route.routeVersion }}</dd><dt>路线摘要</dt><dd>{{ trip.route.routeHash.slice(0, 12) }}…</dd><dt>坐标系统</dt><dd>{{ trip.route.coordinateSystem }}</dd></dl></div>
        <div class="trip-legend"><span><i class="blue"></i>剩余路线</span><span><i class="orange"></i>已行驶</span><span><i class="mint"></i>充电站</span></div>
      </aside>
    </div>
    <p v-if="store.error" class="agent-error">{{ store.error }}</p>
    <p class="simulation-footnote">车辆能力为数字孪生模拟，不接入真实汽车</p>
  </section>
</template>
