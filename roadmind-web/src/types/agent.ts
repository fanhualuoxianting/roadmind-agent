import type { ApiResponse } from './vehicle'

export type AgentMode = 'RULE_STUB' | 'LIVE_MODEL'

export interface Conversation {
  conversationId: string
  title: string
  status: 'ACTIVE'
  timezone: string
  createdAt: string
}

export interface AgentTaskAccepted {
  taskId: string
  status: string
  eventsUrl: string
}

export interface AgentToolCall {
  executionId: string
  toolName: string
  toolVersion: string
  status: 'SUCCEEDED' | 'FAILED'
  attempts: number
  durationMs: number
  result?: Record<string, unknown>
  errorCode?: string
  errorMessage?: string
  traceId: string
}

export interface AgentTask {
  taskId: string
  conversationId: string
  goal: string
  status: string
  plannerMode?: AgentMode
  modelName?: string
  degraded: boolean
  jsonRepaired: boolean
  response?: string
  toolCalls: AgentToolCall[]
  completedToolCalls: number
  totalToolCalls: number
  createdAt: string
  updatedAt: string
}

export interface AgentEventEnvelope {
  schemaVersion: number
  eventId: string
  sequence: number
  type: string
  traceId: string
  taskId: string
  occurredAt: string
  data: Record<string, unknown>
}

export interface ToolDescriptor {
  name: string
  version: string
  description: string
  riskLevel: 'READ_ONLY'
  timeout: string
  idempotent: boolean
  inputSchemaResource: string
}

export interface Capabilities {
  agentMode: AgentMode
  liveModelAvailable: boolean
  modelName: string
  weatherSourceMode: 'LIVE' | 'STUB'
  routeSourceMode: 'LIVE' | 'STUB'
  toolCount: number
  tools: ToolDescriptor[]
  writesEnabled: boolean
  vehicleMode: 'DIGITAL_TWIN'
}

export interface WorkflowSlot { name: string; value: string; source: string; version: number }
export interface WorkflowStep {
  stepId: string; title: string; toolName: string; dependsOn: string[]
  risk: 'READ_ONLY' | 'HIGH'; status: string; arguments: Record<string, unknown>; verification: string
}
export interface WorkflowConfirmation {
  confirmationId: string; status: string; planVersion: number; payloadHash: string
  expiresAt: string; itemIds: string[]
}
export interface WorkflowTimelineEvent { occurredAt: string; type: string; title: string; detail: string }
export interface WorkflowSnapshot {
  workflowId: string; conversationId: string; status: string; contextVersion: number; planVersion: number
  prompt: string; slots: WorkflowSlot[]; missingSlots: string[]; steps: WorkflowStep[]
  confirmation?: WorkflowConfirmation; timeline: WorkflowTimelineEvent[]; response?: string; updatedAt: string
}

export type AgentApiResponse<T> = ApiResponse<T>
