import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import VehicleControlForm from './VehicleControlForm.vue'
import type { VehicleStatus } from '@/types/vehicle'

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

describe('VehicleControlForm', () => {
  it('emits only whitelisted fields from controlled inputs', async () => {
    const wrapper = mount(VehicleControlForm, {
      props: { status, updating: false },
    })

    await wrapper.get('input[aria-label="电池电量"]').setValue('42')
    await wrapper.get('input[aria-label="车内温度"]').setValue('24')
    await wrapper.get('form').trigger('submit')

    expect(wrapper.emitted('submit')).toEqual([
      [{ batteryPercent: 42, cabinTemperature: 24 }],
    ])
  })

  it('disables submission while an update is in flight', () => {
    const wrapper = mount(VehicleControlForm, {
      props: { status, updating: true },
    })

    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeDefined()
  })
})
