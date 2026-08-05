package com.roadmind.server.home;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/** Digital-twin home gateway. Writes are only exposed to authorized workflow executors. */
@Service
public class HomeDeviceService {

    private final Clock clock;
    private final Map<String, HomeDeviceSnapshot> devices = new ConcurrentHashMap<>();

    public HomeDeviceService() {
        this(Clock.systemUTC());
    }

    HomeDeviceService(Clock clock) {
        this.clock = clock;
        devices.put("demo-home-light-01", new HomeDeviceSnapshot(
                "demo-home-light-01", "客厅灯", "LIGHT", true, "SIMULATION", 1, clock.instant()));
    }

    public List<HomeDeviceSnapshot> list() {
        return devices.values().stream().sorted(java.util.Comparator.comparing(HomeDeviceSnapshot::deviceId)).toList();
    }

    public HomeDeviceSnapshot get(String deviceId) {
        HomeDeviceSnapshot device = devices.get(deviceId);
        if (device == null) throw new IllegalArgumentException("家居设备不存在: " + deviceId);
        return device;
    }

    public synchronized HomeDeviceSnapshot setLight(String deviceId, boolean on, String authorizationReference) {
        if (authorizationReference == null || authorizationReference.isBlank()) {
            throw new IllegalArgumentException("家居写操作缺少授权引用");
        }
        HomeDeviceSnapshot current = get(deviceId);
        if (!"LIGHT".equals(current.deviceType())) {
            throw new IllegalArgumentException("设备不是灯光类型");
        }
        return replace(current, on);
    }

    private HomeDeviceSnapshot replace(HomeDeviceSnapshot current, boolean on) {
        HomeDeviceSnapshot next = new HomeDeviceSnapshot(
                current.deviceId(), current.displayName(), current.deviceType(), on,
                current.mode(), current.stateVersion() + 1, Instant.now(clock));
        devices.put(next.deviceId(), next);
        return next;
    }
}
