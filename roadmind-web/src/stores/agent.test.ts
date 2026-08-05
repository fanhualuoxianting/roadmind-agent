import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { agentApi } from '@/api/http'
import type { AgentEventEnvelope, Capabilities } from '@/types/agent'
import { useAgentStore } from './agent'

vi.mock('@/api/http', () => ({
  agentApi: {
    createConversation: vi.fn(),
    submit: vi.fn(),
    getTask: vi.fn(),
    getCapabilities: vi.fn(),
    openEvents: vi.fn(),
  },
}))

const capabilities: Capabilities = {
  agentMode: 'RULE_STUB',
  liveModelAvailable: false,
  modelName: 'gpt-4o-mini',
  weatherSourceMode: 'STUB',
  routeSourceMode: 'STUB',
  toolCount: 3,
  tools: [],
  writesEnabled: false,
  vehicleMode: 'DIGITAL_TWIN',
}

function event(
  sequence: number,
  type: string,
  data: Record<string, unknown>,
): AgentEventEnvelope {
  return {
    schemaVersion: 1,
    eventId: `task-1:${sequence}`,
    sequence,
    type,
    traceId: 'trace-1',
    taskId: 'task-1',
    occurredAt: '2026-08-04T00:00:00Z',
    data,
  }
}

describe('agent store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('applies SSE events in sequence and ignores replay duplicates', () => {
    const store = useAgentStore()
    store.busy = true

    store.applyEvent(event(1, 'agent.plan.created', {
      plannerMode: 'RULE_STUB',
      stepCount: 1,
    }))
    store.applyEvent(event(2, 'tool.call.started', {
      callId: 'call-1',
      tool: 'weather.get_forecast',
    }))
    store.applyEvent(event(3, 'tool.call.completed', {
      callId: 'call-1',
      tool: 'weather.get_forecast',
      status: 'SUCCEEDED',
      durationMs: 12,
      result: { sourceMode: 'STUB', condition: '多云转晴' },
    }))
    store.applyEvent(event(3, 'tool.call.completed', {
      callId: 'call-1',
      status: 'FAILED',
    }))
    store.applyEvent(event(4, 'agent.response.ready', {
      status: 'SUCCEEDED',
      response: '查询完成',
    }))
    store.applyEvent(event(5, 'stream.complete', { finalStatus: 'SUCCEEDED' }))

    expect(store.events).toHaveLength(5)
    expect(store.timeline).toEqual([expect.objectContaining({
      callId: 'call-1',
      status: 'SUCCEEDED',
      durationMs: 12,
    })])
    expect(store.toolResult('weather.get_forecast')).toEqual({
      sourceMode: 'STUB',
      condition: '多云转晴',
    })
    expect(store.response).toBe('查询完成')
    expect(store.busy).toBe(false)
  })

  it('creates one conversation and opens the accepted task stream', async () => {
    vi.mocked(agentApi.createConversation).mockResolvedValue({
      conversationId: 'conversation-1',
      title: 'RoadMind 智能出行',
      status: 'ACTIVE',
      timezone: 'Asia/Shanghai',
      createdAt: '2026-08-04T00:00:00Z',
    })
    vi.mocked(agentApi.submit).mockResolvedValue({
      taskId: 'task-1',
      status: 'PLANNING',
      eventsUrl: '/api/v1/agent-tasks/task-1/events',
    })
    vi.mocked(agentApi.openEvents).mockReturnValue({ close: vi.fn() } as unknown as EventSource)
    const store = useAgentStore()

    await store.submit('查询车辆电量')

    expect(agentApi.createConversation).toHaveBeenCalledOnce()
    expect(agentApi.submit).toHaveBeenCalledWith('conversation-1', '查询车辆电量')
    expect(agentApi.openEvents).toHaveBeenCalledWith(
      '/api/v1/agent-tasks/task-1/events',
      expect.any(Function),
      expect.any(Function),
    )
    expect(store.taskId).toBe('task-1')
    expect(store.busy).toBe(true)
  })

  it('exposes explicit stub capability instead of pretending it is live', async () => {
    vi.mocked(agentApi.getCapabilities).mockResolvedValue(capabilities)
    const store = useAgentStore()

    await store.loadCapabilities()

    expect(store.plannerMode).toBe('RULE_STUB')
    expect(store.capabilities?.routeSourceMode).toBe('STUB')
    expect(store.capabilities?.writesEnabled).toBe(false)
  })
})
