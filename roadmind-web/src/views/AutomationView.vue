<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { preferenceApi, scheduledTaskApi } from '@/api/http'
import type { Preference, ScheduledTask } from '@/types/phase5'

const preferences = ref<Preference[]>([])
const tasks = ref<ScheduledTask[]>([])
const preferenceKey = ref('学校')
const preferenceValue = ref('无锡学院')
const reminder = ref('出发前检查车辆电量')
const executeAt = ref('')
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const notice = ref('')

const activePreferences = computed(() => preferences.value.filter((item) => item.value != null))
const pendingTasks = computed(() => tasks.value.filter((item) => item.status === 'PENDING' || item.status === 'CLAIMED' || item.status === 'RUNNING'))

function displayValue(value: unknown): string {
  if (typeof value === 'string') return value
  if (value && typeof value === 'object' && 'name' in value) return String(value.name)
  return JSON.stringify(value)
}

function formatTime(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    ;[preferences.value, tasks.value] = await Promise.all([
      preferenceApi.list(),
      scheduledTaskApi.list(),
    ])
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '加载偏好和定时任务失败'
  } finally {
    loading.value = false
  }
}

async function saveLocation() {
  saving.value = true
  notice.value = ''
  error.value = ''
  try {
    await preferenceApi.put('LOCATION', preferenceKey.value, { name: preferenceValue.value })
    notice.value = `已保存“${preferenceKey.value}”，本轮 Agent 会使用新地点。`
    preferences.value = await preferenceApi.list()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '保存偏好失败'
  } finally {
    saving.value = false
  }
}

async function remove(item: Preference) {
  try {
    await preferenceApi.remove(item.category, item.preferenceKey)
    preferences.value = await preferenceApi.list()
    notice.value = `已删除“${item.preferenceKey}”，它不会再进入规划。`
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '删除偏好失败'
  }
}

async function createReminder() {
  if (!executeAt.value) {
    error.value = '请选择提醒时间'
    return
  }
  saving.value = true
  try {
    await scheduledTaskApi.createReminder(reminder.value, new Date(executeAt.value).toISOString())
    tasks.value = await scheduledTaskApi.list()
    notice.value = '提醒已写入 MySQL，服务重启后仍会保留。'
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '创建提醒失败'
  } finally {
    saving.value = false
  }
}

async function cancel(task: ScheduledTask) {
  try {
    await scheduledTaskApi.cancel(task.id)
    tasks.value = await scheduledTaskApi.list()
    notice.value = '定时任务已取消。'
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '取消任务失败'
  }
}

onMounted(() => {
  const next = new Date(Date.now() + 60 * 60 * 1000)
  executeAt.value = new Date(next.getTime() - next.getTimezoneOffset() * 60_000).toISOString().slice(0, 16)
  void load()
})
</script>

<template>
  <section class="automation-page" aria-label="偏好与定时任务">
    <header class="automation-hero glass-panel">
      <div>
        <p class="cockpit-kicker">PHASE 5 · RECOVERABLE STATE</p>
        <h1>偏好与自动化 <span>✦</span></h1>
        <p>可删除的长期偏好与可恢复的普通提醒，车辆高风险定时命令仍由模拟器原生能力负责。</p>
      </div>
      <div class="automation-badge"><i></i><strong>MySQL 事实源</strong><small>Redis 丢失可回源</small></div>
    </header>

    <p v-if="error" class="automation-message error" role="alert">{{ error }}</p>
    <p v-if="notice" class="automation-message success" role="status">{{ notice }}</p>

    <div v-if="loading" class="automation-loading glass-panel">正在恢复偏好和定时任务…</div>
    <div v-else class="automation-grid">
      <section class="automation-card glass-panel">
        <header><div><small>LONG-TERM PREFERENCE</small><h2>常用地点</h2></div><span>⌖</span></header>
        <form class="automation-form" @submit.prevent="saveLocation">
          <label>别名<input v-model="preferenceKey" maxlength="80" placeholder="例如：学校" /></label>
          <label>地点<input v-model="preferenceValue" maxlength="120" placeholder="例如：无锡学院" /></label>
          <button type="submit" :disabled="saving">{{ saving ? '保存中…' : '保存地点' }}</button>
        </form>
        <div class="automation-list">
          <p v-if="!activePreferences.length" class="empty-line">还没有保存的地点。</p>
          <article v-for="item in activePreferences" :key="`${item.category}-${item.preferenceKey}`">
            <div><strong>{{ item.preferenceKey }}</strong><span>{{ displayValue(item.value) }}</span></div>
            <button type="button" @click="remove(item)">删除</button>
          </article>
        </div>
      </section>

      <section class="automation-card glass-panel">
        <header><div><small>DURABLE SCHEDULE</small><h2>普通提醒</h2></div><span>◷</span></header>
        <form class="automation-form" @submit.prevent="createReminder">
          <label>提醒内容<input v-model="reminder" maxlength="200" /></label>
          <label>执行时间<input v-model="executeAt" type="datetime-local" /></label>
          <button type="submit" :disabled="saving">{{ saving ? '写入中…' : '创建提醒' }}</button>
        </form>
        <div class="automation-list">
          <p v-if="!tasks.length" class="empty-line">还没有定时任务。</p>
          <article v-for="task in tasks" :key="task.id">
            <div><strong>{{ task.payload.message || task.taskType }}</strong><span>{{ formatTime(task.executeAt) }} · {{ task.status }}</span></div>
            <button v-if="pendingTasks.some((item) => item.id === task.id)" type="button" @click="cancel(task)">取消</button>
          </article>
        </div>
      </section>
    </div>
  </section>
</template>
