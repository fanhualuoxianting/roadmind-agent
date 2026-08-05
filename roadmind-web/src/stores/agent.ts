import axios from 'axios'
import { defineStore } from 'pinia'
import { agentApi } from '@/api/http'
import type { AgentEventEnvelope, AgentMode, Capabilities } from '@/types/agent'
import type { ApiErrorResponse } from '@/types/vehicle'

export interface ToolTimelineItem {
  callId: string
  tool: string
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  durationMs?: number
  result?: Record<string, unknown>
  errorMessage?: string
}

export const useAgentStore = defineStore('agent', {
  state: () => ({
    conversationId: null as string | null,
    taskId: null as string | null,
    lastMessage: '',
    status: 'IDLE',
    plannerMode: 'RULE_STUB' as AgentMode,
    capabilities: null as Capabilities | null,
    response: '',
    busy: false,
    streamDisconnected: false,
    error: null as string | null,
    traceId: null as string | null,
    lastSequence: 0,
    events: [] as AgentEventEnvelope[],
    timeline: [] as ToolTimelineItem[],
    eventSource: null as EventSource | null,
  }),

  actions: {
    async loadCapabilities() {
      try {
        this.capabilities = await agentApi.getCapabilities()
        this.plannerMode = this.capabilities.agentMode
      } catch (error) {
        this.captureError(error)
      }
    },

    async submit(message: string) {
      const normalized = message.trim()
      if (!normalized || this.busy) return
      this.closeStream()
      this.busy = true
      this.error = null
      this.response = ''
      this.streamDisconnected = false
      this.lastSequence = 0
      this.events = []
      this.timeline = []
      this.lastMessage = normalized
      this.status = 'CREATING_TASK'
      try {
        if (!this.conversationId) {
          this.conversationId = (await agentApi.createConversation()).conversationId
        }
        const accepted = await agentApi.submit(this.conversationId, normalized)
        this.taskId = accepted.taskId
        this.status = accepted.status
        this.eventSource = agentApi.openEvents(
          accepted.eventsUrl,
          (event) => this.applyEvent(event),
          () => this.handleDisconnect(),
        )
      } catch (error) {
        this.busy = false
        this.captureError(error)
      }
    },

    applyEvent(event: AgentEventEnvelope) {
      if (event.sequence <= this.lastSequence) return
      this.lastSequence = event.sequence
      this.traceId = event.traceId
      this.events.push(event)

      if (event.type === 'agent.task.updated') {
        this.status = String(event.data.status || this.status)
      }
      if (event.type === 'agent.plan.created') {
        this.plannerMode = String(event.data.plannerMode || 'RULE_STUB') as AgentMode
      }
      if (event.type === 'tool.call.started') {
        this.timeline.push({
          callId: String(event.data.callId),
          tool: String(event.data.tool),
          status: 'RUNNING',
        })
      }
      if (event.type === 'tool.call.completed') {
        const item = this.timeline.find((entry) => entry.callId === String(event.data.callId))
        if (item) {
          item.status = String(event.data.status) as ToolTimelineItem['status']
          item.durationMs = Number(event.data.durationMs || 0)
          item.result = event.data.result as Record<string, unknown> | undefined
          item.errorMessage = event.data.errorMessage as string | undefined
        }
      }
      if (event.type === 'stream.error') {
        this.error = String(event.data.message || event.data.code || 'Agent 处理失败')
      }
      if (event.type === 'agent.response.ready') {
        this.status = String(event.data.status || this.status)
        this.response = String(event.data.response || '')
      }
      if (event.type === 'stream.complete') {
        this.status = String(event.data.finalStatus || this.status)
        this.busy = false
        this.closeStream()
      }
    },

    toolResult(toolName: string): Record<string, unknown> | undefined {
      return this.timeline.find((item) => item.tool === toolName && item.status === 'SUCCEEDED')?.result
    },

    handleDisconnect() {
      if (this.busy) this.streamDisconnected = true
    },

    closeStream() {
      this.eventSource?.close()
      this.eventSource = null
    },

    captureError(error: unknown) {
      if (axios.isAxiosError<ApiErrorResponse>(error)) {
        this.error = error.response?.data?.message || '无法连接 RoadMind Server'
        this.traceId = error.response?.data?.traceId || null
        return
      }
      this.error = error instanceof Error ? error.message : '发生未知错误'
    },
  },
})
