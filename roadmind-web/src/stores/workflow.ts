import { defineStore } from 'pinia'
import { agentApi, workflowApi } from '@/api/http'
import type { WorkflowSnapshot } from '@/types/agent'

export const useWorkflowStore = defineStore('workflow', {
  state: () => ({ conversationId: null as string | null, snapshot: null as WorkflowSnapshot | null, busy: false, error: null as string | null }),
  actions: {
    async send(message: string) {
      if (!message.trim() || this.busy) return
      this.busy = true; this.error = null
      try {
        if (!this.conversationId) this.conversationId = (await agentApi.createConversation()).conversationId
        this.snapshot = await workflowApi.message(this.conversationId, message)
      } catch (error) { this.error = error instanceof Error ? error.message : '工作流请求失败' }
      finally { this.busy = false }
    },
    async decide(decision: 'APPROVE' | 'REJECT') {
      if (!this.snapshot || this.busy) return
      this.busy = true; this.error = null
      try { this.snapshot = await workflowApi.decide(this.snapshot, decision) }
      catch (error) { this.error = error instanceof Error ? error.message : '确认请求失败' }
      finally { this.busy = false }
    },
  },
})
