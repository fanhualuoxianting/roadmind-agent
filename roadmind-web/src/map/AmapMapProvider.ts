import type { MapProvider } from './MapProvider'
import { MapProviderError } from './MapProvider'
import type { RoutePlan, TripTelemetry } from '@/types/trip'

interface AmapMapLike { add(item: unknown): void; setFitView(items?: unknown[]): void; destroy(): void }
interface AmapMarkerLike { setPosition(position: [number, number]): void; setAngle(angle: number): void }
interface AmapNamespace {
  Map: new (container: HTMLElement, options: Record<string, unknown>) => AmapMapLike
  Polyline: new (options: Record<string, unknown>) => unknown
  Marker: new (options: Record<string, unknown>) => AmapMarkerLike
}

declare global {
  interface Window {
    AMap?: AmapNamespace
    _AMapSecurityConfig?: { securityJsCode: string }
  }
}

let loader: Promise<AmapNamespace> | null = null

function loadAmap(key: string, securityCode?: string): Promise<AmapNamespace> {
  if (window.AMap) return Promise.resolve(window.AMap)
  if (loader) return loader
  if (securityCode) window._AMapSecurityConfig = { securityJsCode: securityCode }
  loader = new Promise((resolve, reject) => {
    const callback = `roadmindAmapReady${Date.now()}`
    const callbackHost = window as unknown as Record<string, unknown>
    callbackHost[callback] = () => {
      delete callbackHost[callback]
      if (window.AMap) resolve(window.AMap)
      else reject(new MapProviderError('高德地图脚本已加载但 API 不可用'))
    }
    const script = document.createElement('script')
    script.src = `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(key)}&callback=${callback}`
    script.async = true
    script.onerror = () => reject(new MapProviderError('高德地图加载失败'))
    document.head.appendChild(script)
  })
  return loader
}

export class AmapMapProvider implements MapProvider {
  private map: AmapMapLike | null = null
  private marker: AmapMarkerLike | null = null
  private amap: AmapNamespace | null = null

  constructor(private readonly key: string, private readonly securityCode?: string) {}

  async mount(container: HTMLElement): Promise<void> {
    if (!this.key) throw new MapProviderError('未配置高德浏览器 Key')
    this.amap = await loadAmap(this.key, this.securityCode)
    this.map = new this.amap.Map(container, { zoom: 8, viewMode: '2D', mapStyle: 'amap://styles/light' })
  }

  renderRoute(route: RoutePlan): void {
    if (!this.map || !this.amap) throw new MapProviderError('地图尚未挂载')
    const path = route.polyline.map((point) => [point.longitude, point.latitude])
    const line = new this.amap.Polyline({ path, strokeColor: '#53a7e8', strokeWeight: 7, lineJoin: 'round' })
    this.marker = new this.amap.Marker({
      position: path[0], content: '<div class="amap-roadmind-car">RM</div>', anchor: 'center', angle: 0,
    })
    this.map.add(line)
    this.map.add(this.marker)
    this.map.setFitView([line, this.marker])
  }

  setVehiclePose(telemetry: TripTelemetry): void {
    this.marker?.setPosition([telemetry.position.longitude, telemetry.position.latitude])
    this.marker?.setAngle(telemetry.heading)
  }

  destroy(): void {
    this.map?.destroy()
    this.map = null
    this.marker = null
    this.amap = null
  }
}
