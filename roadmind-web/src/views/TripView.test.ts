import { createPinia, setActivePinia } from 'pinia'
import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it } from 'vitest'
import { useTripStore } from '@/stores/trip'
import type { TripSnapshot } from '@/types/trip'
import TripView from './TripView.vue'

const snapshot: TripSnapshot = {
  tripId: 'trip-1', vehicleId: 'demo-vehicle-001', status: 'DRIVING', lowBatteryReplanned: true,
  sourceDisclaimer: '固定演示路线，不代表实时道路与交通状态', eventsUrl: '/api/v1/trips/trip-1/events',
  route: {
    routePlanId: 'route-1', routeVersion: 2, provider: 'RoadMind deterministic route fixture', sourceMode: 'STUB',
    coordinateSystem: 'GCJ-02', routeHash: 'abcdef1234567890', distanceMeters: 169000, durationSeconds: 9360,
    origin: { name: '南京软件谷', longitude: 118.74, latitude: 31.98, coordinateSystem: 'GCJ-02' },
    destination: { name: '无锡学院', longitude: 120.30, latitude: 31.57, coordinateSystem: 'GCJ-02' },
    polyline: [], fetchedAt: '2026-08-05T00:00:00Z',
    chargingStation: { name: 'RoadMind 推荐快充站', position: { longitude: 119.8, latitude: 31.8, coordinateSystem: 'GCJ-02' }, chargingMinutes: 18 },
  },
  telemetry: {
    sequence: 18, position: { longitude: 119.2, latitude: 31.9, coordinateSystem: 'GCJ-02' },
    speedKmh: 90, heading: 88, batteryPercent: 39, remainingRangeKm: 236,
    travelledMeters: 40000, remainingDistanceMeters: 129000, estimatedArrivalTime: '2026-08-05T02:18:00Z',
    status: 'DRIVING', simulationSpeed: 5, observedAt: '2026-08-05T00:08:00Z',
  },
}

describe('TripView', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('renders simulation source, route version and charging plan without a map key', () => {
    const store = useTripStore()
    store.snapshot = structuredClone(snapshot)
    store.notices = ['预计到达电量 16%，已加入推荐充电站']
    const wrapper = mount(TripView)
    expect(wrapper.text()).toContain('南京软件谷 → 无锡学院')
    expect(wrapper.text()).toContain('路线 2')
    expect(wrapper.text()).toContain('STUB')
    expect(wrapper.text()).toContain('地图降级视图')
    expect(wrapper.text()).toContain('推荐充电')
    expect(wrapper.text()).toContain('数字孪生模拟，不接入真实汽车')
  })
})
