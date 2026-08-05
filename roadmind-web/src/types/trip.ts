export type CoordinateSystem = 'GCJ-02'
export type TripStatus = 'READY' | 'DRIVING' | 'PAUSED' | 'COMPLETED' | 'CANCELLED' | 'FAILED'
export type SimulationSpeed = 1 | 5 | 20

export interface Coordinate {
  longitude: number
  latitude: number
  coordinateSystem: CoordinateSystem
}

export interface RoutePlan {
  routePlanId: string
  routeVersion: number
  provider: string
  sourceMode: 'LIVE' | 'CACHE' | 'STUB'
  coordinateSystem: CoordinateSystem
  origin: Coordinate & { name: string }
  destination: Coordinate & { name: string }
  distanceMeters: number
  durationSeconds: number
  polyline: Coordinate[]
  routeHash: string
  fetchedAt: string
  chargingStation?: { name: string; position: Coordinate; chargingMinutes: number }
}

export interface TripTelemetry {
  sequence: number
  position: Coordinate
  speedKmh: number
  heading: number
  batteryPercent: number
  remainingRangeKm: number
  travelledMeters: number
  remainingDistanceMeters: number
  estimatedArrivalTime: string
  status: TripStatus
  simulationSpeed: SimulationSpeed
  observedAt: string
}

export interface TripSnapshot {
  tripId: string
  vehicleId: string
  status: TripStatus
  route: RoutePlan
  telemetry: TripTelemetry
  lowBatteryReplanned: boolean
  sourceDisclaimer: string
  eventsUrl: string
}

export interface TripEvent {
  schemaVersion: number
  eventId: string
  sequence: number
  type: 'trip.snapshot' | 'trip.telemetry' | 'trip.status.changed' | 'trip.replanned' | 'stream.heartbeat'
  traceId: string
  tripId: string
  occurredAt: string
  data: Partial<TripSnapshot> & { telemetry?: TripTelemetry; message?: string }
}
