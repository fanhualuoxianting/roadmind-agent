export interface ApiResponse<T> {
  code: string
  message: string
  data: T
  traceId: string
  timestamp: string
}

export interface ApiErrorResponse {
  code: string
  message: string
  details?: Record<string, unknown>
  traceId?: string
}

export interface GeoLocation {
  coordinateSystem: 'GCJ-02'
  longitude: number
  latitude: number
  city: string
}

export interface TirePressure {
  frontLeft: number
  frontRight: number
  rearLeft: number
  rearRight: number
}

export interface VehicleStatus {
  vehicleId: string
  displayName: string
  mode: 'DIGITAL_TWIN'
  batteryPercent: number
  estimatedRangeKm: number
  cabinTemperature: number
  doorLocked: boolean
  charging: boolean
  gear: string
  location: GeoLocation
  tirePressure: TirePressure
  stateVersion: number
  observedAt: string
}

export interface VehicleStateUpdate {
  expectedVersion: number
  batteryPercent?: number
  cabinTemperature?: number
}
