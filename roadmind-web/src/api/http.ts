import axios from 'axios'
import type { ApiResponse, VehicleStateUpdate, VehicleStatus } from '@/types/vehicle'
import type {
  AgentEventEnvelope,
  AgentTask,
  AgentTaskAccepted,
  Capabilities,
  Conversation,
  WorkflowSnapshot,
} from '@/types/agent'
import type { SimulationSpeed, TripEvent, TripSnapshot } from '@/types/trip'
import type { Preference, ScheduledTask } from '@/types/automation'

export const http = axios.create({
  baseURL: '/api',
  timeout: 4_000,
  withCredentials: true,
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
})

let csrfReady = false

export async function ensureCsrf(): Promise<void> {
  if (csrfReady) return
  await http.get<ApiResponse<{ token: string; headerName: string }>>('/v1/security/csrf')
  csrfReady = true
}

function idempotencyKey(): string {
  return crypto.randomUUID()
}

export const vehicleApi = {
  async getStatus(vehicleId: string): Promise<VehicleStatus> {
    await ensureCsrf()
    const response = await http.get<ApiResponse<VehicleStatus>>(
      `/v1/vehicles/${vehicleId}/status`,
    )
    return response.data.data
  },

  async updateState(vehicleId: string, payload: VehicleStateUpdate): Promise<VehicleStatus> {
    await ensureCsrf()
    const response = await http.patch<ApiResponse<VehicleStatus>>(
      `/v1/demo/vehicles/${vehicleId}/state`,
      payload,
      {
        headers: {
          'Idempotency-Key': crypto.randomUUID(),
        },
      },
    )
    return response.data.data
  },
}

const eventTypes = [
  'agent.task.updated',
  'agent.plan.created',
  'tool.call.started',
  'tool.call.completed',
  'agent.response.ready',
  'stream.error',
  'stream.complete',
]

export const agentApi = {
  async createConversation(): Promise<Conversation> {
    await ensureCsrf()
    const response = await http.post<ApiResponse<Conversation>>(
      '/v1/conversations',
      { title: 'RoadMind 智能出行', timezone: 'Asia/Shanghai' },
      { headers: { 'Idempotency-Key': idempotencyKey() } },
    )
    return response.data.data
  },

  async submit(conversationId: string, message: string): Promise<AgentTaskAccepted> {
    await ensureCsrf()
    const response = await http.post<ApiResponse<AgentTaskAccepted>>(
      `/v1/conversations/${conversationId}/agent-requests`,
      { message, clientContext: { timezone: 'Asia/Shanghai' } },
      { headers: { 'Idempotency-Key': idempotencyKey() } },
    )
    return response.data.data
  },

  async getTask(taskId: string): Promise<AgentTask> {
    const response = await http.get<ApiResponse<AgentTask>>(`/v1/agent-tasks/${taskId}`)
    return response.data.data
  },

  async getCapabilities(): Promise<Capabilities> {
    await ensureCsrf()
    const response = await http.get<ApiResponse<Capabilities>>('/v1/settings/capabilities')
    return response.data.data
  },

  openEvents(
    eventsUrl: string,
    onEvent: (event: AgentEventEnvelope) => void,
    onDisconnect: () => void,
  ): EventSource {
    const source = new EventSource(eventsUrl, { withCredentials: true })
    eventTypes.forEach((type) => {
      source.addEventListener(type, (rawEvent) => {
        const event = rawEvent as MessageEvent<string>
        onEvent(JSON.parse(event.data) as AgentEventEnvelope)
      })
    })
    source.onerror = onDisconnect
    return source
  },
}

export const workflowApi = {
  async message(conversationId: string, message: string): Promise<WorkflowSnapshot> {
    await ensureCsrf()
    const response = await http.post<ApiResponse<WorkflowSnapshot>>(
      `/v1/conversations/${conversationId}/workflow-messages`, { message },
    )
    return response.data.data
  },
  async decide(workflow: WorkflowSnapshot, decision: 'APPROVE' | 'REJECT'): Promise<WorkflowSnapshot> {
    await ensureCsrf()
    const response = await http.post<ApiResponse<WorkflowSnapshot>>(
      `/v1/workflows/${workflow.workflowId}/confirmation-decisions`,
      { decision, planVersion: workflow.planVersion, payloadHash: workflow.confirmation?.payloadHash },
    )
    return response.data.data
  },
}

const tripEventTypes = [
  'trip.snapshot',
  'trip.telemetry',
  'trip.status.changed',
  'trip.replanned',
  'stream.heartbeat',
]

export const tripApi = {
  async active(): Promise<TripSnapshot | null> {
    await ensureCsrf()
    const response = await http.get<ApiResponse<TripSnapshot | null>>('/v1/trips/active')
    return response.data.data
  },
  async create(payload: { origin: string; destination: string; avoidTraffic: boolean; initialBatteryPercent: number }): Promise<TripSnapshot> {
    await ensureCsrf()
    const response = await http.post<ApiResponse<TripSnapshot>>('/v1/trips', payload, {
      headers: { 'Idempotency-Key': idempotencyKey() },
    })
    return response.data.data
  },
  async command(tripId: string, action: 'START' | 'PAUSE' | 'RESUME' | 'CANCEL' | 'SET_SPEED', simulationSpeed?: SimulationSpeed): Promise<TripSnapshot> {
    await ensureCsrf()
    const response = await http.post<ApiResponse<TripSnapshot>>(
      `/v1/trips/${tripId}/commands`,
      { action, simulationSpeed },
      { headers: { 'Idempotency-Key': idempotencyKey() } },
    )
    return response.data.data
  },
  openEvents(eventsUrl: string, lastEventId: string | null, onEvent: (event: TripEvent) => void, onDisconnect: () => void): EventSource {
    const separator = eventsUrl.includes('?') ? '&' : '?'
    const url = lastEventId ? `${eventsUrl}${separator}lastEventId=${encodeURIComponent(lastEventId)}` : eventsUrl
    const source = new EventSource(url, { withCredentials: true })
    tripEventTypes.forEach((type) => {
      source.addEventListener(type, (rawEvent) => {
        const event = rawEvent as MessageEvent<string>
        onEvent(JSON.parse(event.data) as TripEvent)
      })
    })
    source.onerror = onDisconnect
    return source
  },
}

export const preferenceApi = {
  async list(category?: string): Promise<Preference[]> {
    const response = await http.get<ApiResponse<Preference[]>>('/v1/preferences', {
      params: category ? { category } : undefined,
    })
    return response.data.data
  },

  async put(category: string, preferenceKey: string, value: unknown): Promise<Preference> {
    await ensureCsrf()
    const response = await http.put<ApiResponse<Preference>>(
      `/v1/preferences/${encodeURIComponent(category)}/${encodeURIComponent(preferenceKey)}`,
      { value, sensitivity: category === 'LOCATION' ? 'SENSITIVE' : 'NORMAL' },
      { headers: { 'Idempotency-Key': idempotencyKey() } },
    )
    return response.data.data
  },

  async remove(category: string, preferenceKey: string): Promise<void> {
    await ensureCsrf()
    await http.delete(`/v1/preferences/${encodeURIComponent(category)}/${encodeURIComponent(preferenceKey)}`, {
      headers: { 'Idempotency-Key': idempotencyKey() },
    })
  },
}

export const scheduledTaskApi = {
  async list(status?: string): Promise<ScheduledTask[]> {
    const response = await http.get<ApiResponse<ScheduledTask[]>>('/v1/scheduled-tasks', {
      params: status ? { status } : undefined,
    })
    return response.data.data
  },

  async createReminder(message: string, executeAt: string, timezone = 'Asia/Shanghai'): Promise<ScheduledTask> {
    await ensureCsrf()
    const response = await http.post<ApiResponse<ScheduledTask>>('/v1/scheduled-tasks', {
      message,
      executeAt,
      timezone,
    }, { headers: { 'Idempotency-Key': idempotencyKey() } })
    return response.data.data
  },

  async cancel(id: number): Promise<ScheduledTask> {
    await ensureCsrf()
    const response = await http.post<ApiResponse<ScheduledTask>>(`/v1/scheduled-tasks/${id}/cancel`, null, {
      headers: { 'Idempotency-Key': idempotencyKey() },
    })
    return response.data.data
  },
}
