import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { agentApi, workflowApi } from '@/api/http'
import type { WorkflowSnapshot } from '@/types/agent'
import { useWorkflowStore } from './workflow'

vi.mock('@/api/http', () => ({
  agentApi: { createConversation: vi.fn() },
  workflowApi: { message: vi.fn(), decide: vi.fn() },
}))

const pending: WorkflowSnapshot = {
  workflowId: 'workflow-1', conversationId: 'conversation-1', status: 'WAITING_CONFIRMATION',
  contextVersion: 1, planVersion: 1, prompt: '测试', slots: [], missingSlots: [], steps: [], timeline: [],
  confirmation: { confirmationId: 'confirmation-1', status: 'PENDING', planVersion: 1, payloadHash: 'abc', expiresAt: '2026-08-04T01:00:00Z', itemIds: ['s4'] },
  updatedAt: '2026-08-04T00:00:00Z',
}

describe('workflow store', () => {
  beforeEach(() => { setActivePinia(createPinia()); vi.clearAllMocks() })

  it('keeps one conversation across clarification turns', async () => {
    vi.mocked(agentApi.createConversation).mockResolvedValue({ conversationId: 'conversation-1', title: 'RoadMind', status: 'ACTIVE', timezone: 'Asia/Shanghai', createdAt: '2026-08-04T00:00:00Z' })
    vi.mocked(workflowApi.message).mockResolvedValue(pending)
    const store = useWorkflowStore()
    await store.send('明天去苏州')
    await store.send('明天早上8点从南京出发去苏州')
    expect(agentApi.createConversation).toHaveBeenCalledOnce()
    expect(workflowApi.message).toHaveBeenCalledTimes(2)
    expect(store.snapshot?.planVersion).toBe(1)
  })

  it('sends the bound snapshot when approving', async () => {
    vi.mocked(workflowApi.decide).mockResolvedValue({ ...pending, status: 'SUCCEEDED', confirmation: { ...pending.confirmation!, status: 'APPROVED' } })
    const store = useWorkflowStore(); store.snapshot = pending
    await store.decide('APPROVE')
    expect(workflowApi.decide).toHaveBeenCalledWith(pending, 'APPROVE')
    expect(store.snapshot?.status).toBe('SUCCEEDED')
  })
})
