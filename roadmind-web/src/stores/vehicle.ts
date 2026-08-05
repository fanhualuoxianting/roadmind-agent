import axios from 'axios'
import { defineStore } from 'pinia'
import { vehicleApi } from '@/api/http'
import type { ApiErrorResponse, VehicleStateUpdate, VehicleStatus } from '@/types/vehicle'

export const DEMO_VEHICLE_ID = '198000000000000401'

export const useVehicleStore = defineStore('vehicle', {
  state: () => ({
    status: null as VehicleStatus | null,
    loading: false,
    updating: false,
    offline: false,
    error: null as string | null,
    traceId: null as string | null,
  }),

  actions: {
    async fetchStatus() {
      this.loading = true
      this.error = null
      try {
        this.status = await vehicleApi.getStatus(DEMO_VEHICLE_ID)
        this.offline = false
      } catch (error) {
        this.captureError(error)
      } finally {
        this.loading = false
      }
    },

    async updateState(values: Omit<VehicleStateUpdate, 'expectedVersion'>) {
      if (!this.status) return
      this.updating = true
      this.error = null
      try {
        this.status = await vehicleApi.updateState(DEMO_VEHICLE_ID, {
          expectedVersion: this.status.stateVersion,
          ...values,
        })
        this.offline = false
      } catch (error) {
        this.captureError(error)
      } finally {
        this.updating = false
      }
    },

    captureError(error: unknown) {
      if (axios.isAxiosError<ApiErrorResponse>(error)) {
        this.error = error.response?.data?.message || '无法连接 RoadMind Server'
        this.traceId = error.response?.data?.traceId || null
        this.offline = !error.response || error.response.status === 503
        return
      }
      this.error = error instanceof Error ? error.message : '发生未知错误'
      this.offline = true
    },
  },
})
