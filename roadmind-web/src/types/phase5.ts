export interface Preference {
  id: number
  category: string
  preferenceKey: string
  value: unknown
  sensitivity: 'NORMAL' | 'SENSITIVE'
  expiresAt: string | null
  createdAt: string
  updatedAt: string
  version: number
}

export interface ScheduledTask {
  id: number
  taskType: string
  payload: { kind?: string; message?: string }
  payloadHash: string
  idempotencyKey: string
  executeAt: string
  timezone: string
  status: 'PENDING' | 'CLAIMED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | 'EXPIRED'
  attemptCount: number
  lockedBy: string | null
  lockedUntil: string | null
  lastErrorCode: string | null
  createdAt: string
  updatedAt: string
  version: number
}
