import { describe, expect, it } from 'vitest'
import { acceptTelemetry, interpolateCoordinate, shortestHeading } from './interpolation'
import type { TripTelemetry } from '@/types/trip'

const telemetry = (sequence: number): TripTelemetry => ({
  sequence,
  position: { longitude: 118.8, latitude: 32.0, coordinateSystem: 'GCJ-02' },
  speedKmh: 60,
  heading: 0,
  batteryPercent: 42,
  remainingRangeKm: 200,
  travelledMeters: 0,
  remainingDistanceMeters: 169_000,
  estimatedArrivalTime: '2026-08-05T02:18:00Z',
  status: 'DRIVING',
  simulationSpeed: 1,
  observedAt: '2026-08-05T00:00:00Z',
})

describe('trip rendering math', () => {
  it('interpolates without changing the coordinate system', () => {
    expect(interpolateCoordinate(
      { longitude: 118, latitude: 31, coordinateSystem: 'GCJ-02' },
      { longitude: 120, latitude: 33, coordinateSystem: 'GCJ-02' },
      0.25,
    )).toEqual({ longitude: 118.5, latitude: 31.5, coordinateSystem: 'GCJ-02' })
  })

  it('turns through north using the shortest heading arc', () => {
    expect(shortestHeading(350, 10, 0.5)).toBe(0)
  })

  it('drops replayed and out-of-order telemetry', () => {
    expect(acceptTelemetry(telemetry(8), telemetry(8))).toBe(false)
    expect(acceptTelemetry(telemetry(8), telemetry(7))).toBe(false)
    expect(acceptTelemetry(telemetry(8), telemetry(9))).toBe(true)
  })
})
