import { createPinia, setActivePinia } from 'pinia'
import { AxiosError } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { vehicleApi } from '@/api/http'
import { useVehicleStore } from './vehicle'
import type { VehicleStatus } from '@/types/vehicle'

vi.mock('@/api/http', () => ({
  vehicleApi: {
    getStatus: vi.fn(),
    updateState: vi.fn(),
  },
}))

const status: VehicleStatus = {
  vehicleId: '198000000000000401',
  displayName: 'RoadMind Demo Car',
  mode: 'DIGITAL_TWIN',
  batteryPercent: 68,
  estimatedRangeKm: 412,
  cabinTemperature: 29,
  doorLocked: true,
  charging: false,
  gear: 'P',
  location: {
    coordinateSystem: 'GCJ-02',
    longitude: 118.7969,
    latitude: 32.0603,
    city: '南京',
  },
  tirePressure: { frontLeft: 2.4, frontRight: 2.4, rearLeft: 2.3, rearRight: 2.3 },
  stateVersion: 12,
  observedAt: '2026-08-04T00:00:00Z',
}

describe('vehicle store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('loads the digital twin state', async () => {
    vi.mocked(vehicleApi.getStatus).mockResolvedValue(status)
    const store = useVehicleStore()

    await store.fetchStatus()

    expect(store.status).toEqual(status)
    expect(store.offline).toBe(false)
    expect(store.loading).toBe(false)
  })

  it('marks a 503 dependency error as offline', async () => {
    const error = new AxiosError(
      'unavailable',
      'ERR_BAD_RESPONSE',
      undefined,
      undefined,
      {
        data: {
          code: 'VEHICLE_SIMULATOR_UNAVAILABLE',
          message: '车辆数字孪生模拟器暂时离线',
          traceId: 'trace-001',
        },
        status: 503,
        statusText: 'Service Unavailable',
        headers: {},
        config: { headers: {} } as never,
      },
    )
    vi.mocked(vehicleApi.getStatus).mockRejectedValue(error)
    const store = useVehicleStore()

    await store.fetchStatus()

    expect(store.offline).toBe(true)
    expect(store.error).toContain('暂时离线')
    expect(store.traceId).toBe('trace-001')
  })

  it('sends the current version with a controlled update', async () => {
    vi.mocked(vehicleApi.updateState).mockResolvedValue({
      ...status,
      batteryPercent: 42,
      cabinTemperature: 24,
      stateVersion: 13,
    })
    const store = useVehicleStore()
    store.status = status

    await store.updateState({ batteryPercent: 42, cabinTemperature: 24 })

    expect(vehicleApi.updateState).toHaveBeenCalledWith(status.vehicleId, {
      expectedVersion: 12,
      batteryPercent: 42,
      cabinTemperature: 24,
    })
    expect(store.status?.stateVersion).toBe(13)
  })
})
