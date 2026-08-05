import type { Coordinate, TripTelemetry } from '@/types/trip'

export function clamp(value: number, min = 0, max = 1): number {
  return Math.min(max, Math.max(min, value))
}

export function shortestHeading(from: number, to: number, progress: number): number {
  const delta = ((to - from + 540) % 360) - 180
  return (from + delta * clamp(progress) + 360) % 360
}

export function interpolateCoordinate(from: Coordinate, to: Coordinate, progress: number): Coordinate {
  const t = clamp(progress)
  return {
    longitude: from.longitude + (to.longitude - from.longitude) * t,
    latitude: from.latitude + (to.latitude - from.latitude) * t,
    coordinateSystem: 'GCJ-02',
  }
}

export function acceptTelemetry(current: TripTelemetry | null, incoming: TripTelemetry): boolean {
  return !current || incoming.sequence > current.sequence
}
