<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useAgentStore } from '@/stores/agent'
import { useVehicleStore } from '@/stores/vehicle'
import digitalTwinRearUrl from '@/assets/roadmind-digital-twin-rear.png'
import digitalTwinStatusUrl from '@/assets/roadmind-digital-twin-status-front-v2.png'
import userAvatarUrl from '@/assets/roadmind-user-avatar.png'
import agentAvatarUrl from '@/assets/roadmind-agent-avatar.png'

const defaultRouteOrigin = '南京软件谷'
const defaultRouteDestination = '无锡学院'
const defaultMessage = `明天早上 8 点从${defaultRouteOrigin}出发去${defaultRouteDestination}，避开拥堵，电量不足时安排充电，出发前提前打开空调，到达后关闭家里的灯。`
const draft = ref(defaultMessage)
const agent = useAgentStore()
const vehicle = useVehicleStore()

const weather = computed(() => agent.toolResult('weather.get_forecast'))
const route = computed(() => agent.toolResult('route.plan'))
const vehicleResult = computed(() => agent.toolResult('vehicle.get_status'))

const visibleBattery = computed(() => {
  const value = vehicleResult.value?.batteryPercent ?? vehicle.status?.batteryPercent
  return value == null ? '—' : `${Number(value).toFixed(0)}%`
})

const visibleRange = computed(() => {
  const value = vehicleResult.value?.estimatedRangeKm ?? vehicle.status?.estimatedRangeKm
  return value == null ? '—' : `${Number(value).toFixed(0)} km`
})

const modeLabel = computed(() => agent.plannerMode === 'LIVE_MODEL' ? 'LIVE MODEL' : 'RULE STUB')

const displayMessage = computed(() => agent.lastMessage || defaultMessage)

function parseRequestedRoute(message: string) {
  const match = message.match(/(?:从|由)\s*(.+?)\s*(?:出发(?:去|到|前往)|前往|去|到)\s*(.+?)(?=[，,。；;]|$)/)
  return {
    origin: match?.[1]?.trim() || '',
    destination: match?.[2]?.trim() || '',
  }
}

function routeLabel(value: unknown) {
  return typeof value === 'string' && value.trim() ? value.trim() : ''
}

const requestedRoute = computed(() => parseRequestedRoute(agent.lastMessage || defaultMessage))
const routeOriginLabel = computed(() => routeLabel(route.value?.origin) || requestedRoute.value.origin || defaultRouteOrigin)
const routeDestinationLabel = computed(() => routeLabel(route.value?.destination) || requestedRoute.value.destination || defaultRouteDestination)
const routeSummaryLabel = computed(() => `${routeOriginLabel.value} → ${routeDestinationLabel.value}`)

const vehicleLocationLabel = computed(() => {
  const city = vehicle.status?.location.city
  if (!city) return '南京市雨花台区软件大道'
  return city.endsWith('市') ? `${city}雨花台区软件大道` : `${city}市雨花台区软件大道`
})

const routeMapRef = ref<HTMLElement | null>(null)
const routeStartRef = ref<HTMLElement | null>(null)
const routeEndRef = ref<HTMLElement | null>(null)
const routeLineStyle = ref<Record<string, string>>({})
let routeResizeObserver: ResizeObserver | null = null

function updateRouteLine() {
  const map = routeMapRef.value
  const start = routeStartRef.value
  const end = routeEndRef.value
  if (!map || !start || !end) return

  const mapRect = map.getBoundingClientRect()
  const startRect = start.getBoundingClientRect()
  const endRect = end.getBoundingClientRect()
  const originX = mapRect.left + map.clientLeft
  const originY = mapRect.top + map.clientTop
  const startX = startRect.left + startRect.width / 2 - originX
  const startY = startRect.top + startRect.height / 2 - originY
  const endX = endRect.left + endRect.width / 2 - originX
  const endY = endRect.top + endRect.height / 2 - originY
  const deltaX = endX - startX
  const deltaY = endY - startY
  const distance = Math.hypot(deltaX, deltaY)
  const angle = Math.atan2(deltaY, deltaX) * 180 / Math.PI

  routeLineStyle.value = {
    left: `${startX}px`,
    top: `${startY - 3}px`,
    width: `${distance}px`,
    transform: `rotate(${angle}deg)`,
  }
}

function send() {
  const message = draft.value
  void agent.submit(message)
}

function applySuggestion(value: string) {
  draft.value = value
}

onMounted(() => {
  void Promise.all([agent.loadCapabilities(), vehicle.fetchStatus()])
  void nextTick(() => {
    updateRouteLine()
    if (typeof ResizeObserver !== 'undefined' && routeMapRef.value) {
      routeResizeObserver = new ResizeObserver(updateRouteLine)
      routeResizeObserver.observe(routeMapRef.value)
    }
    window.addEventListener('resize', updateRouteLine)
  })
})

onBeforeUnmount(() => {
  routeResizeObserver?.disconnect()
  window.removeEventListener('resize', updateRouteLine)
  agent.closeStream()
})
</script>

<template>
  <section class="agent-cockpit" aria-label="Agent 出行工作台">
    <aside class="drive-preview glass-panel" aria-label="数字孪生道路预览">
      <div class="speed-readout">
        <strong>68</strong>
        <span>km/h</span>
      </div>
      <div class="speed-limits">
        <span>90</span>
        <span>90</span>
      </div>
      <div class="road-scene" aria-hidden="true">
        <div class="road-haze"></div>
        <div class="lane lane-left"></div>
        <div class="lane lane-right"></div>
        <div class="road-speedline road-speedline-left"></div>
        <div class="road-speedline road-speedline-right"></div>
        <div class="road-scan-ring"></div>
        <div class="road-hud">
          <span class="hud-corner hud-corner-tl"></span>
          <span class="hud-corner hud-corner-tr"></span>
          <span class="hud-corner hud-corner-bl"></span>
          <span class="hud-corner hud-corner-br"></span>
          <span class="hud-dot hud-dot-left"></span>
          <span class="hud-dot hud-dot-right"></span>
          <span class="hud-direction">↑</span>
        </div>
        <img class="road-car road-car-main" :src="digitalTwinRearUrl" alt="" />
        <div class="road-car road-car-left"></div>
        <div class="road-car road-car-right"></div>
      </div>
      <div class="drive-summary">
        <span>▰ {{ visibleBattery }}</span>
        <span>{{ visibleRange }}</span>
        <span>❉ 舒适</span>
      </div>
    </aside>

    <main class="agent-workbench glass-panel">
      <header class="workbench-heading">
        <div>
          <p class="cockpit-kicker">ROADMind Intelligence</p>
          <h1>Agent 出行工作台 <span>✦</span></h1>
          <p>理解复杂出行需求，自动选择受控工具并反馈执行过程</p>
        </div>
        <span class="source-pill" :class="{ live: agent.plannerMode === 'LIVE_MODEL' }">
          {{ modeLabel }}
        </span>
      </header>

      <div class="conversation-flow">
        <div class="message-label"><span class="avatar user-avatar"><img :src="userAvatarUrl" alt="" /></span> 用户需求</div>
        <div class="message user-message">{{ displayMessage }}</div>

        <div class="message-label"><span class="avatar agent-avatar"><img :src="agentAvatarUrl" alt="" /></span> RoadMind Agent</div>
        <div class="message agent-message" :class="{ thinking: agent.busy }">
          <span v-if="agent.busy" class="thinking-dot"></span>
          {{ agent.response || (agent.busy ? '正在分析需求并调用只读工具…' : '已准备好。提交需求后，我会通过 SSE 实时展示工具调用。') }}
        </div>
      </div>

      <div class="intent-grid" aria-label="识别条件">
        <div><span class="intent-icon blue">◷</span><small>出发时间</small><strong>明天 08:00</strong></div>
        <div><span class="intent-icon blue">⌖</span><small>起点</small><strong>{{ routeOriginLabel }}</strong></div>
        <div><span class="intent-icon green">⚑</span><small>终点</small><strong>{{ routeDestinationLabel }}</strong></div>
      </div>

      <div class="tool-progress" v-if="agent.timeline.length">
        <div
          v-for="item in agent.timeline"
          :key="item.callId"
          class="tool-progress-item"
          :class="item.status.toLowerCase()"
        >
          <span>{{ item.status === 'RUNNING' ? '◌' : item.status === 'SUCCEEDED' ? '✓' : '!' }}</span>
          <div><strong>{{ item.tool }}</strong><small>{{ item.durationMs == null ? '调用中' : `${item.durationMs} ms` }}</small></div>
        </div>
      </div>
      <div v-else class="feature-strip">
        <span>⌁ 避堵</span><span>▣ 充电判断</span><span>▤ 车辆状态</span><span>⌂ 家居待确认</span>
      </div>

      <div v-if="agent.error" class="agent-error" role="alert">
        {{ agent.error }}<small v-if="agent.traceId">Trace {{ agent.traceId }}</small>
      </div>

      <form class="agent-composer" @submit.prevent="send">
        <textarea
          v-model="draft"
          aria-label="出行需求"
          maxlength="8192"
          rows="2"
          placeholder="请输入您的出行需求"
          @keydown.ctrl.enter.prevent="send"
        ></textarea>
        <div class="composer-actions">
          <span title="语音能力将在后续阶段加入">◉</span>
          <span title="附件能力将在后续阶段加入">⌕</span>
          <small>Ctrl + Enter 发送</small>
          <button type="submit" :disabled="agent.busy" aria-label="发送需求">
            {{ agent.busy ? '···' : '➤' }}
          </button>
        </div>
      </form>

      <div class="suggestion-row">
        <button type="button" @click="applySuggestion('查询当前车辆电量和续航')">查询车辆状态</button>
        <button type="button" @click="applySuggestion('明天从南京软件谷出发去无锡学院，查询天气并规划避堵路线')">快速路线演示</button>
      </div>
    </main>

    <aside class="task-insight glass-panel">
      <header><h2>任务理解 <span>✦</span></h2><small>{{ agent.status }}</small></header>

      <article class="insight-card weather-card">
        <span class="large-icon">☀</span>
        <div><small>天气（{{ weather ? '工具结果' : '待查询' }}）</small><strong>{{ weather?.condition || '多云转晴' }}</strong><p>{{ weather ? `${weather.minimumCelsius}–${weather.maximumCelsius}°C` : '15–24°C' }}</p></div>
        <span class="data-source">{{ weather?.sourceMode || agent.capabilities?.weatherSourceMode || 'STUB' }}</span>
      </article>

      <article class="insight-card battery-card">
        <div class="battery-ring"><span>{{ visibleBattery }}</span></div>
        <div><small>电量与续航</small><strong>{{ visibleRange }}</strong><p>数字孪生车辆</p></div>
      </article>

      <article class="insight-card route-card">
        <span class="large-icon">▥</span>
        <div><small>预计里程</small><strong>{{ route ? `${route.distanceKm} km` : '169 km' }}</strong><p>{{ route ? `约 ${route.durationMinutes} 分钟` : '约 2 时 18 分' }}</p></div>
        <span class="data-source">{{ route?.sourceMode || agent.capabilities?.routeSourceMode || 'STUB' }}</span>
      </article>

      <article class="insight-card vehicle-card">
        <div class="vehicle-status-info">
          <small>当前车辆状态</small>
          <strong>{{ vehicle.offline ? '模拟器离线' : '已锁车 · 在线' }}</strong>
          <div class="vehicle-status-details">
            <span><i class="status-symbol">◈</i>{{ vehicle.status?.doorLocked === false ? '车门未锁' : '已锁车' }}</span>
            <span><i class="status-symbol climate-symbol">♨</i>空调未开启</span>
            <span class="vehicle-location-line"><i class="status-symbol location-symbol">⌖</i><em>位置</em><b>{{ vehicleLocationLabel }}</b></span>
          </div>
        </div>
        <img class="vehicle-status-car" :src="digitalTwinStatusUrl" alt="数字孪生车辆侧视图" />
      </article>

      <div ref="routeMapRef" class="route-miniature" :aria-label="`路线摘要：${routeSummaryLabel}`">
        <div class="route-line" :style="routeLineStyle"></div>
        <span ref="routeStartRef" class="route-start">A</span>
        <span ref="routeEndRef" class="route-end">B</span>
        <small>{{ routeSummaryLabel }}</small>
      </div>

      <button class="plan-button" type="button" :disabled="agent.busy" @click="send">
        {{ agent.busy ? '正在执行只读计划…' : '生成只读执行计划 ✦' }}
      </button>
      <p class="simulation-footnote">车辆能力为数字孪生模拟，不接入真实汽车</p>
    </aside>
  </section>
</template>
