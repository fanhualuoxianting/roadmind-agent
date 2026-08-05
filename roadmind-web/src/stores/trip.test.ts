import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { tripApi } from '@/api/http'
import type { TripSnapshot } from '@/types/trip'
import { useTripStore } from './trip'

vi.mock('@/api/http', () => ({
  tripApi: { active: vi.fn(), create: vi.fn(), command: vi.fn(), openEvents: vi.fn(() => ({ close: vi.fn() })) },
}))

const snapshot: TripSnapshot = {
  tripId: 'trip-1', vehicleId: 'demo-vehicle-001', status: 'READY', lowBatteryReplanned: false,
  sourceDisclaimer: '固定演示路线', eventsUrl: '/api/v1/trips/trip-1/events',
  route: {
    routePlanId: 'route-1', routeVersion: 1, provider: 'RoadMind fixture', sourceMode: 'STUB',
    coordinateSystem: 'GCJ-02', routeHash: 'abc', distanceMeters: 169000, durationSeconds: 8280,
    origin: { name: '南京软件谷', longitude: 118.74, latitude: 31.98, coordinateSystem: 'GCJ-02' },
    destination: { name: '无锡学院', longitude: 120.30, latitude: 31.57, coordinateSystem: 'GCJ-02' },
    polyline: [], fetchedAt: '2026-08-05T00:00:00Z',
  },
  telemetry: {
    sequence: 1, position: { longitude: 118.74, latitude: 31.98, coordinateSystem: 'GCJ-02' },
    speedKmh: 0, heading: 90, batteryPercent: 42, remainingRangeKm: 254,
    travelledMeters: 0, remainingDistanceMeters: 169000, estimatedArrivalTime: '2026-08-05T02:18:00Z',
    status: 'READY', simulationSpeed: 1, observedAt: '2026-08-05T00:00:00Z',
  },
}

describe('trip store', () => {
  beforeEach(() => { setActivePinia(createPinia()); vi.clearAllMocks() })

  it('creates the fixed demonstration trip and connects its event stream', async () => {
    vi.mocked(tripApi.active).mockResolvedValue(null)
    vi.mocked(tripApi.create).mockResolvedValue(structuredClone(snapshot))
    const store = useTripStore()
    await store.create()
    expect(tripApi.create).toHaveBeenCalledWith(expect.objectContaining({ initialBatteryPercent: 42 }))
    expect(tripApi.openEvents).toHaveBeenCalledWith(snapshot.eventsUrl, null, expect.any(Function), expect.any(Function))
    expect(store.connected).toBe(true)
  })

  it('resumes an existing active trip instead of creating a conflicting simulator trip', async () => {
    vi.mocked(tripApi.active).mockResolvedValue(structuredClone(snapshot))
    const store = useTripStore()

    await store.create()

    expect(tripApi.create).not.toHaveBeenCalled()
    expect(tripApi.openEvents).toHaveBeenCalledWith(snapshot.eventsUrl, null, expect.any(Function), expect.any(Function))
  })

  it('does not apply duplicate telemetry after SSE replay', () => {
    const store = useTripStore(); store.snapshot = structuredClone(snapshot)
    store.applyEvent({ schemaVersion: 1, eventId: 'trip-1:1', sequence: 1, type: 'trip.telemetry', traceId: 't', tripId: 'trip-1', occurredAt: '', data: { telemetry: { ...snapshot.telemetry, batteryPercent: 10 } } })
    expect(store.snapshot.telemetry.batteryPercent).toBe(42)
  })

  it('surfaces a low-battery replan exactly as an Agent event', () => {
    const store = useTripStore(); store.snapshot = structuredClone(snapshot)
    store.applyEvent({ schemaVersion: 1, eventId: 'trip-1:2', sequence: 2, type: 'trip.replanned', traceId: 't', tripId: 'trip-1', occurredAt: '', data: { lowBatteryReplanned: true, message: '预计到达电量 14%，已加入推荐充电站' } })
    expect(store.snapshot.lowBatteryReplanned).toBe(true)
    expect(store.notices[0]).toContain('14%')
  })
})
