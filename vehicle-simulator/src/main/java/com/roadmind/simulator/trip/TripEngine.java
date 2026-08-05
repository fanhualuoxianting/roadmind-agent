package com.roadmind.simulator.trip;

import com.roadmind.simulator.application.VehicleStateService;
import com.roadmind.simulator.domain.IdempotencyConflictException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class TripEngine {

    private static final double TARGET_SPEED_METERS_PER_SECOND = 25.0;
    private static final double BATTERY_PERCENT_PER_KM = 0.165;
    private static final int MAX_IDEMPOTENCY_ENTRIES = 1_000;

    private final Clock clock;
    private final Map<String, CommandReplay> commandReplays = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CommandReplay> eldest) {
            return size() > MAX_IDEMPOTENCY_ENTRIES;
        }
    };
    private List<TripCoordinate> polyline = List.of();
    private double[] cumulativeMeters = new double[0];
    private String tripId;
    private String vehicleId;
    private long routeVersion;
    private String routeHash;
    private String sourceMode;
    private TripStatus status;
    private long sequence;
    private double travelledMeters;
    private double batteryPercent;
    private double heading;
    private int simulationSpeed = 1;
    private Instant observedAt;

    public TripEngine() {
        this(Clock.systemUTC());
    }

    TripEngine(Clock clock) {
        this.clock = clock;
    }

    public synchronized TripSnapshot configure(ConfigureTripRequest request, String idempotencyKey) {
        if (!VehicleStateService.DEMO_VEHICLE_ID.equals(request.vehicleId())) {
            throw new TripTransitionException("行程只能绑定 RoadMind 演示车辆");
        }
        if (!"GCJ-02".equals(request.polyline().getFirst().coordinateSystem())
                || request.polyline().stream().anyMatch(point -> !"GCJ-02".equals(point.coordinateSystem()))) {
            throw new TripTransitionException("模拟路线必须统一使用 GCJ-02 坐标");
        }
        String fingerprint = request.tripId() + ':' + request.routeVersion() + ':' + request.routeHash();
        CommandReplay replay = commandReplays.get("CONFIGURE:" + idempotencyKey);
        if (replay != null) {
            requireSameFingerprint(replay, fingerprint);
            return replay.snapshot();
        }
        if (tripId != null && status != TripStatus.COMPLETED && status != TripStatus.CANCELLED) {
            throw new TripTransitionException("已有未结束的模拟行程");
        }
        this.tripId = request.tripId();
        this.vehicleId = request.vehicleId();
        this.routeVersion = request.routeVersion();
        this.routeHash = request.routeHash();
        this.sourceMode = request.sourceMode();
        this.polyline = List.copyOf(request.polyline());
        this.cumulativeMeters = cumulativeDistances(polyline);
        this.status = TripStatus.READY;
        this.sequence = 1;
        this.travelledMeters = 0;
        this.batteryPercent = request.initialBatteryPercent();
        this.heading = bearing(polyline.get(0), polyline.get(1));
        this.simulationSpeed = 1;
        this.observedAt = clock.instant();
        TripSnapshot snapshot = snapshot();
        commandReplays.put("CONFIGURE:" + idempotencyKey, new CommandReplay(fingerprint, snapshot));
        return snapshot;
    }

    public synchronized TripSnapshot get(String requestedTripId) {
        requireTrip(requestedTripId);
        return snapshot();
    }

    public synchronized TripSnapshot command(String requestedTripId, TripCommandRequest request, String idempotencyKey) {
        requireTrip(requestedTripId);
        String action = request.action().trim().toUpperCase();
        int requestedSpeed = request.simulationSpeed() == null ? simulationSpeed : request.simulationSpeed();
        if (requestedSpeed != 1 && requestedSpeed != 5 && requestedSpeed != 20) {
            throw new TripTransitionException("模拟速度只允许 1、5 或 20 倍");
        }
        String fingerprint = action + ':' + requestedSpeed;
        CommandReplay replay = commandReplays.get("COMMAND:" + idempotencyKey);
        if (replay != null) {
            requireSameFingerprint(replay, fingerprint);
            return replay.snapshot();
        }
        switch (action) {
            case "START" -> requireTransition(TripStatus.READY, TripStatus.DRIVING);
            case "PAUSE" -> requireTransition(TripStatus.DRIVING, TripStatus.PAUSED);
            case "RESUME" -> requireTransition(TripStatus.PAUSED, TripStatus.DRIVING);
            case "SET_SPEED" -> {
                if (status != TripStatus.READY && status != TripStatus.DRIVING && status != TripStatus.PAUSED) {
                    throw invalidTransition(action);
                }
            }
            case "CANCEL" -> {
                if (status != TripStatus.READY && status != TripStatus.DRIVING && status != TripStatus.PAUSED) {
                    throw invalidTransition(action);
                }
                status = TripStatus.CANCELLED;
            }
            default -> throw new TripTransitionException("未知行程指令: " + action);
        }
        simulationSpeed = requestedSpeed;
        sequence++;
        observedAt = clock.instant();
        TripSnapshot snapshot = snapshot();
        commandReplays.put("COMMAND:" + idempotencyKey, new CommandReplay(fingerprint, snapshot));
        return snapshot;
    }

    @Scheduled(fixedRateString = "${roadmind.simulator.trip-tick-ms:1000}")
    public void scheduledTick() {
        advance(Duration.ofSeconds(1));
    }

    public synchronized TripSnapshot advance(Duration realDuration) {
        if (tripId == null || status != TripStatus.DRIVING || realDuration.isNegative() || realDuration.isZero()) {
            return tripId == null ? null : snapshot();
        }
        double totalMeters = cumulativeMeters[cumulativeMeters.length - 1];
        double deltaMeters = TARGET_SPEED_METERS_PER_SECOND * realDuration.toMillis() / 1_000.0 * simulationSpeed;
        double nextTravelled = Math.min(totalMeters, travelledMeters + deltaMeters);
        double consumed = (nextTravelled - travelledMeters) / 1_000.0 * BATTERY_PERCENT_PER_KM;
        travelledMeters = nextTravelled;
        batteryPercent = Math.max(0, batteryPercent - consumed);
        if (travelledMeters >= totalMeters) {
            status = TripStatus.COMPLETED;
        }
        sequence++;
        observedAt = clock.instant();
        return snapshot();
    }

    private TripSnapshot snapshot() {
        double totalMeters = cumulativeMeters[cumulativeMeters.length - 1];
        double remaining = Math.max(0, totalMeters - travelledMeters);
        int segment = segmentFor(travelledMeters);
        TripCoordinate from = polyline.get(segment);
        TripCoordinate to = polyline.get(Math.min(segment + 1, polyline.size() - 1));
        double segmentDistance = cumulativeMeters[Math.min(segment + 1, cumulativeMeters.length - 1)] - cumulativeMeters[segment];
        double local = segmentDistance <= 0 ? 0 : (travelledMeters - cumulativeMeters[segment]) / segmentDistance;
        TripCoordinate position = interpolate(from, to, local);
        if (segmentDistance > 0) heading = bearing(from, to);
        double speedKmh = status == TripStatus.DRIVING ? TARGET_SPEED_METERS_PER_SECOND * 3.6 : 0;
        long etaSeconds = status == TripStatus.COMPLETED ? 0 : Math.round(remaining / TARGET_SPEED_METERS_PER_SECOND);
        double expectedArrivalBattery = batteryPercent - remaining / 1_000.0 * BATTERY_PERCENT_PER_KM;
        return new TripSnapshot(
                tripId, vehicleId, status, routeVersion, routeHash, sourceMode, sequence, position,
                rounded(speedKmh), rounded(heading), rounded(batteryPercent), rounded(batteryPercent * 6.06),
                rounded(travelledMeters), rounded(remaining), observedAt.plusSeconds(etaSeconds), simulationSpeed,
                expectedArrivalBattery < 20.0, observedAt);
    }

    private void requireTransition(TripStatus expected, TripStatus next) {
        if (status != expected) throw invalidTransition(next.name());
        status = next;
    }

    private TripTransitionException invalidTransition(String action) {
        return new TripTransitionException("当前状态 " + status + " 不允许执行 " + action);
    }

    private void requireTrip(String requestedTripId) {
        if (tripId == null || !tripId.equals(requestedTripId)) {
            throw new TripTransitionException("模拟行程不存在: " + requestedTripId);
        }
    }

    private void requireSameFingerprint(CommandReplay replay, String fingerprint) {
        if (!replay.fingerprint().equals(fingerprint)) throw new IdempotencyConflictException();
    }

    private int segmentFor(double distance) {
        int low = 0;
        int high = cumulativeMeters.length - 1;
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (cumulativeMeters[mid] <= distance) low = mid;
            else high = mid - 1;
        }
        return Math.min(low, polyline.size() - 2);
    }

    private static double[] cumulativeDistances(List<TripCoordinate> points) {
        double[] values = new double[points.size()];
        for (int i = 1; i < points.size(); i++) {
            values[i] = values[i - 1] + haversine(points.get(i - 1), points.get(i));
        }
        if (values[values.length - 1] < 1) throw new TripTransitionException("模拟路线总距离必须大于 1 米");
        return values;
    }

    private static double haversine(TripCoordinate first, TripCoordinate second) {
        double lat1 = Math.toRadians(first.latitude());
        double lat2 = Math.toRadians(second.latitude());
        double deltaLat = lat2 - lat1;
        double deltaLon = Math.toRadians(second.longitude() - first.longitude());
        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
        return 6_371_000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static double bearing(TripCoordinate first, TripCoordinate second) {
        double lat1 = Math.toRadians(first.latitude());
        double lat2 = Math.toRadians(second.latitude());
        double deltaLon = Math.toRadians(second.longitude() - first.longitude());
        double y = Math.sin(deltaLon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(deltaLon);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    private static TripCoordinate interpolate(TripCoordinate first, TripCoordinate second, double progress) {
        double value = Math.max(0, Math.min(1, progress));
        return new TripCoordinate(
                first.longitude() + (second.longitude() - first.longitude()) * value,
                first.latitude() + (second.latitude() - first.latitude()) * value,
                "GCJ-02");
    }

    private static double rounded(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private record CommandReplay(String fingerprint, TripSnapshot snapshot) {
    }
}
