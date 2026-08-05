import { defineStore } from 'pinia'
import { tripApi } from '@/api/http'
import { acceptTelemetry } from '@/trip/interpolation'
import type { SimulationSpeed, TripEvent, TripSnapshot } from '@/types/trip'

export const useTripStore = defineStore('trip', {
  state: () => ({
    snapshot: null as TripSnapshot | null,
    source: null as EventSource | null,
    busy: false,
    connected: false,
    telemetryDelayed: false,
    error: null as string | null,
    notices: [] as string[],
    lastEventId: null as string | null,
    reconnectTimer: null as ReturnType<typeof setTimeout> | null,
  }),
  actions: {
    async create() {
      if (this.busy) return
      this.busy = true
      this.error = null
      try {
        const active = await tripApi.active()
        this.snapshot = active ?? await tripApi.create({
          origin: '南京软件谷', destination: '无锡学院', avoidTraffic: true, initialBatteryPercent: 42,
        })
        this.connect()
      } catch (error) {
        this.error = error instanceof Error ? error.message : '创建模拟行程失败'
      } finally {
        this.busy = false
      }
    },
    connect() {
      if (!this.snapshot) return
      this.disconnect()
      this.source = tripApi.openEvents(this.snapshot.eventsUrl, this.lastEventId, this.applyEvent, () => {
        this.connected = false
        this.telemetryDelayed = true
        if (!this.reconnectTimer) {
          this.reconnectTimer = setTimeout(() => {
            this.reconnectTimer = null
            if (this.snapshot && !['COMPLETED', 'CANCELLED', 'FAILED'].includes(this.snapshot.status)) this.connect()
          }, 1_500)
        }
      })
      this.connected = true
      this.telemetryDelayed = false
    },
    disconnect() {
      this.source?.close()
      this.source = null
      this.connected = false
    },
    applyEvent(event: TripEvent) {
      if (!this.snapshot) return
      this.lastEventId = event.eventId
      if (event.data.telemetry && acceptTelemetry(this.snapshot.telemetry, event.data.telemetry)) {
        this.snapshot.telemetry = event.data.telemetry
        this.snapshot.status = event.data.telemetry.status
      }
      if (event.data.route) this.snapshot.route = event.data.route
      if (event.data.status) this.snapshot.status = event.data.status
      if (typeof event.data.lowBatteryReplanned === 'boolean') {
        this.snapshot.lowBatteryReplanned = event.data.lowBatteryReplanned
      }
      if (event.type === 'trip.replanned') this.notices.unshift(event.data.message || '低电量已触发重新规划')
      this.telemetryDelayed = false
    },
    async command(action: 'START' | 'PAUSE' | 'RESUME' | 'CANCEL' | 'SET_SPEED', speed?: SimulationSpeed) {
      if (!this.snapshot || this.busy) return
      this.busy = true
      this.error = null
      try {
        this.snapshot = await tripApi.command(this.snapshot.tripId, action, speed)
      } catch (error) {
        this.error = error instanceof Error ? error.message : '行程控制失败'
      } finally {
        this.busy = false
      }
    },
    async setSpeed(speed: SimulationSpeed) {
      if (!this.snapshot || this.snapshot.telemetry.simulationSpeed === speed) return
      await this.command('SET_SPEED', speed)
    },
  },
})
