<script setup lang="ts">
import { computed, ref } from 'vue'
import { useWorkflowStore } from '@/stores/workflow'

const store = useWorkflowStore()
const draft = ref('明天早上8点从南京软件谷出发去无锡学院，避开拥堵，出发前打开空调，到达后关闭家里的灯。')
const selected = ref(0)
const snapshot = computed(() => store.snapshot)
const activeStep = computed(() => snapshot.value?.steps[selected.value])
const statusLabel = computed(() => ({ WAITING_INPUT: '等待补充', WAITING_CONFIRMATION: '等待确认', SUCCEEDED: '执行成功', PARTIAL_SUCCESS: '部分成功' }[snapshot.value?.status || ''] || snapshot.value?.status || '尚未生成'))
</script>

<template>
  <section class="plan-page">
    <header class="plan-hero glass-panel">
      <div><p class="cockpit-kicker">EXPLAINABLE AGENT WORKFLOW</p><h1>执行计划 <span>✦</span></h1><p>{{ snapshot?.response || '生成可解释、可确认、可回查的版本化任务计划' }}</p></div>
      <div class="plan-version"><small>计划版本</small><strong>V{{ snapshot?.planVersion || 0 }}</strong><span>{{ statusLabel }}</span></div>
    </header>

    <div class="plan-layout">
      <main class="plan-canvas glass-panel">
        <form class="plan-prompt" @submit.prevent="store.send(draft)"><input v-model="draft" aria-label="任务补充" /><button :disabled="store.busy">{{ store.busy ? '处理中…' : '生成 / 修正计划' }}</button></form>
        <div v-if="snapshot?.missingSlots.length" class="missing-callout"><strong>还需要补充</strong><span v-for="slot in snapshot.missingSlots" :key="slot">{{ slot }}</span></div>
        <div class="slot-row" v-if="snapshot"><span v-for="slot in snapshot.slots" :key="slot.name"><small>{{ slot.name }}</small>{{ slot.value }} <i>v{{ slot.version }}</i></span></div>
        <div class="dag-list" v-if="snapshot?.steps.length">
          <button v-for="(step, index) in snapshot.steps" :key="step.stepId" type="button" :class="['dag-step', { active: index === selected, danger: step.risk === 'HIGH' }]" @click="selected = index">
            <span class="step-index">{{ index + 1 }}</span><div><small>{{ step.toolName }}</small><strong>{{ step.title }}</strong><p>{{ step.dependsOn.length ? `依赖 ${step.dependsOn.join('、')}` : '可立即执行' }}</p></div><b>{{ step.risk === 'HIGH' ? '需要确认' : step.status }}</b>
          </button>
        </div>
        <div v-else class="plan-empty"><span>⌘</span><strong>输入任务后生成 DAG</strong><p>RoadMind 会先检查缺失信息，再由服务端判定风险。</p></div>
      </main>

      <aside class="plan-detail glass-panel">
        <template v-if="activeStep"><header><small>STEP {{ selected + 1 }}</small><h2>{{ activeStep.title }}</h2></header><dl><dt>工具</dt><dd>{{ activeStep.toolName }}</dd><dt>服务端风险</dt><dd :class="{ coral: activeStep.risk === 'HIGH' }">{{ activeStep.risk }}</dd><dt>状态</dt><dd>{{ activeStep.status }}</dd><dt>回查策略</dt><dd>{{ activeStep.verification }}</dd></dl><pre>{{ JSON.stringify(activeStep.arguments, null, 2) }}</pre></template>
        <template v-else><header><small>POLICY GATE</small><h2>步骤详情</h2></header><p class="detail-placeholder">选择计划步骤后，在这里查看参数、依赖、风险与验证方式。</p></template>
        <div v-if="snapshot?.confirmation?.status === 'PENDING'" class="confirmation-panel"><strong>需要你的确认</strong><p>将执行车辆预热与家居灯光操作，仅作用于数字孪生模拟环境。</p><small>确认与 V{{ snapshot.planVersion }} 及操作摘要绑定，10 分钟内有效。</small><div><button class="reject" @click="store.decide('REJECT')">拒绝执行</button><button class="approve" @click="store.decide('APPROVE')">确认并继续</button></div></div>
        <div class="audit-mini" v-if="snapshot?.timeline.length"><strong>执行时间线</strong><article v-for="(event, index) in snapshot.timeline.slice(-5)" :key="`${event.occurredAt}-${event.type}-${index}`"><i></i><div><b>{{ event.title }}</b><small>{{ event.detail }}</small></div></article></div>
      </aside>
    </div>
    <p v-if="store.error" class="agent-error">{{ store.error }}</p>
    <p class="simulation-footnote">车辆能力为数字孪生模拟，不接入真实汽车</p>
  </section>
</template>
