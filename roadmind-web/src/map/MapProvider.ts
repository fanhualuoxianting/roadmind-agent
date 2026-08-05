import type { RoutePlan, TripTelemetry } from '@/types/trip'

export interface MapProvider {
  mount(container: HTMLElement): Promise<void>
  renderRoute(route: RoutePlan): void
  setVehiclePose(telemetry: TripTelemetry): void
  destroy(): void
}

export class MapProviderError extends Error {
  constructor(message: string, options?: ErrorOptions) {
    super(message, options)
    this.name = 'MapProviderError'
  }
}
