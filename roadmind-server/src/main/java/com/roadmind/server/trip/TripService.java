package com.roadmind.server.trip;

import com.roadmind.server.adapter.simulator.SimulatorTripAdapter;
import com.roadmind.server.agent.AgentIdempotencyConflictException;
import com.roadmind.server.shared.RequestTrace;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class TripService {
    private static final String VEHICLE_ID = "demo-vehicle-001";
    private final TripRoutePlanner routePlanner;
    private final SimulatorTripAdapter simulator;
    private final TripEventHub events;
    private final TripPersistence persistence;
    private final Map<String, TripRecord> trips = new ConcurrentHashMap<>();
    private final Map<String, String> tripOwners = new ConcurrentHashMap<>();
    private final Map<String, CreateReplay> createReplays = new LinkedHashMap<>();
    private long pollCount;

    public TripService(TripRoutePlanner routePlanner, SimulatorTripAdapter simulator, TripEventHub events) {
        this(routePlanner, simulator, events, null);
    }

    @Autowired
    public TripService(
            TripRoutePlanner routePlanner,
            SimulatorTripAdapter simulator,
            TripEventHub events,
            TripPersistence persistence) {
        this.routePlanner = routePlanner;
        this.simulator = simulator;
        this.events = events;
        this.persistence = persistence;
    }

    public synchronized TripSnapshot create(CreateTripRequest request, String idempotencyKey) {
        return create(request, idempotencyKey, null);
    }

    public synchronized TripSnapshot create(
            CreateTripRequest request,
            String idempotencyKey,
            String ownerUsername) {
        String fingerprint = request.origin() + '|' + request.destination() + '|' + request.avoidTraffic()
                + '|' + request.initialBatteryPercent();
        String replayKey = (ownerUsername == null ? "anonymous" : ownerUsername) + ":" + idempotencyKey;
        CreateReplay replay = createReplays.get(replayKey);
        if (replay != null) {
            if (!replay.fingerprint().equals(fingerprint)) throw new AgentIdempotencyConflictException();
            return get(replay.tripId(), ownerUsername);
        }
        RoutePlan route = routePlanner.plan(request.origin(), request.destination(), request.avoidTraffic());
        String tripId = UUID.randomUUID().toString();
        TripTelemetry telemetry = simulator.configure(
                tripId, VEHICLE_ID, route, request.initialBatteryPercent(), "trip-configure:" + tripId);
        Long ownerUserId = persistence == null
                ? null
                : persistence.findUserId(ownerUsername).orElse(null);
        TripRecord record = new TripRecord(tripId, route, telemetry, false, ownerUserId, ownerUsername);
        trips.put(tripId, record);
        if (ownerUsername != null && !ownerUsername.isBlank()) tripOwners.put(tripId, ownerUsername);
        createReplays.put(replayKey, new CreateReplay(fingerprint, tripId));
        events.create(tripId);
        List<EventSpec> specs = new ArrayList<>(applyLowBatteryReplan(record));
        TripSnapshot snapshot = snapshot(record);
        specs.add(new EventSpec(RequestTrace.current(), "trip.snapshot", eventData(snapshot)));
        persist(snapshot, specs, record.ownerUserId);
        return snapshot;
    }

    public TripSnapshot get(String tripId) {
        return get(tripId, null);
    }

    public TripSnapshot get(String tripId, String ownerUsername) {
        return snapshot(requireTrip(tripId, ownerUsername));
    }

    public Optional<TripSnapshot> active(String ownerUsername) {
        if (persistenceAvailable()) {
            return persistence.findActiveForUser(ownerUsername).stream().findFirst();
        }
        return trips.values().stream()
                .filter(record -> record.telemetry.status() != TripStatus.COMPLETED
                        && record.telemetry.status() != TripStatus.CANCELLED
                        && record.telemetry.status() != TripStatus.FAILED)
                .filter(record -> ownerUsername == null || ownerUsername.isBlank()
                        || record.ownerUsername == null
                        || ownerUsername.equals(record.ownerUsername))
                .map(this::snapshot)
                .findFirst();
    }

    public synchronized TripSnapshot command(String tripId, TripCommandRequest request, String idempotencyKey) {
        return command(tripId, request, idempotencyKey, null);
    }

    public synchronized TripSnapshot command(
            String tripId,
            TripCommandRequest request,
            String idempotencyKey,
            String ownerUsername) {
        TripRecord record = requireTrip(tripId, ownerUsername);
        TripTelemetry previous = record.telemetry;
        List<EventSpec> specs = new ArrayList<>();
        TripTelemetry telemetry = simulator.command(
                tripId, request.action().trim().toUpperCase(), request.simulationSpeed(), idempotencyKey);
        if (telemetry.sequence() > record.telemetry.sequence()) {
            record.telemetry = telemetry;
            String eventType = previous.status() == telemetry.status() ? "trip.telemetry" : "trip.status.changed";
            specs.add(new EventSpec(RequestTrace.current(), eventType, Map.of(
                    "status", telemetry.status(), "telemetry", telemetry)));
        }
        TripSnapshot snapshot = snapshot(record);
        persist(snapshot, specs, record.ownerUserId);
        return snapshot;
    }

    public SseEmitter events(String tripId, String lastEventId) {
        return events(tripId, lastEventId, null);
    }

    public SseEmitter events(String tripId, String lastEventId, String ownerUsername) {
        TripSnapshot current = get(tripId, ownerUsername);
        return events.subscribe(tripId, lastEventId, eventData(current));
    }

    @Scheduled(fixedDelayString = "${roadmind.trip.poll-ms:1000}")
    public void pollSimulator() {
        pollCount++;
        restoreActiveTripsIfNeeded();
        for (TripRecord record : trips.values()) {
            TripStatus status = record.telemetry.status();
            if (status == TripStatus.COMPLETED || status == TripStatus.CANCELLED || status == TripStatus.FAILED) continue;
            try {
                TripTelemetry latest = simulator.get(record.tripId);
                if (latest.sequence() > record.telemetry.sequence()) {
                    TripStatus previousStatus = record.telemetry.status();
                    record.telemetry = latest;
                    List<EventSpec> specs = new ArrayList<>(applyLowBatteryReplan(record));
                    String type = previousStatus == latest.status() ? "trip.telemetry" : "trip.status.changed";
                    specs.add(new EventSpec("trip-poller", type,
                            Map.of("status", latest.status(), "telemetry", latest)));
                    persist(snapshot(record), specs, record.ownerUserId);
                } else if (pollCount % 15 == 0) {
                    persist(snapshot(record), List.of(new EventSpec("trip-poller", "stream.heartbeat",
                            Map.of("status", status))), record.ownerUserId);
                }
            } catch (RuntimeException exception) {
                if (pollCount % 15 == 0) {
                    persist(snapshot(record), List.of(new EventSpec("trip-poller", "stream.heartbeat", Map.of(
                            "status", status, "message", "模拟器暂时不可达，保留最后快照"))), record.ownerUserId);
                }
            }
        }
    }

    private List<EventSpec> applyLowBatteryReplan(TripRecord record) {
        double expectedArrivalBattery = record.telemetry.batteryPercent()
                - record.telemetry.remainingDistanceMeters() / 1_000.0 * 0.165;
        if (record.lowBatteryReplanned || expectedArrivalBattery >= 20) return List.of();
        int stationIndex = Math.max(1, record.route.polyline().size() * 2 / 3);
        RouteCoordinate position = record.route.polyline().get(Math.min(stationIndex, record.route.polyline().size() - 1));
        ChargingStation station = new ChargingStation("RoadMind 推荐快充站", position, 18);
        String updatedHash = RouteHash.of(record.route.routeHash() + "|charging", record.route.polyline());
        record.route = record.route.withChargingStation(station, updatedHash);
        record.lowBatteryReplanned = true;
        return List.of(new EventSpec("agent-low-battery", "trip.replanned", Map.of(
                "route", record.route,
                "lowBatteryReplanned", true,
                "message", "预计到达电量 " + Math.max(0, Math.round(expectedArrivalBattery)) + "% ，已加入推荐充电站")));
    }

    private TripRecord requireTrip(String tripId, String ownerUsername) {
        TripRecord record = trips.get(tripId);
        if (record != null) {
            enforceOwner(tripId, ownerUsername);
            return record;
        }
        if (record == null && persistenceAvailable()) {
            record = (ownerUsername == null
                    ? persistence.findById(tripId)
                    : persistence.findByIdForUser(tripId, ownerUsername))
                    .map(TripRecord::from)
                    .orElse(null);
            if (record != null) {
                trips.putIfAbsent(tripId, record);
                events.create(tripId, persistence.findMaxEventSequence(tripId));
            }
        }
        if (record == null) throw new TripNotFoundException(tripId);
        return record;
    }

    private synchronized void restoreActiveTripsIfNeeded() {
        if (!persistenceAvailable()) return;
        for (TripSnapshot snapshot : persistence.findActive()) {
            trips.computeIfAbsent(snapshot.tripId(), id -> {
                events.create(id, persistence.findMaxEventSequence(id));
                return TripRecord.from(snapshot);
            });
        }
    }

    private void persist(TripSnapshot snapshot, List<EventSpec> specs) {
        persist(snapshot, specs, null);
    }

    private void persist(TripSnapshot snapshot, List<EventSpec> specs, Long ownerUserId) {
        List<TripEventEnvelope> prepared = specs.stream()
                .map(spec -> events.prepare(snapshot.tripId(), spec.traceId(), spec.type(), spec.data()))
                .toList();
        if (persistence != null) {
            if (prepared.isEmpty()) persistence.save(snapshot);
            else persistence.saveAndAppendEvents(snapshot, prepared, ownerUserId);
        }
        prepared.forEach(events::publishPrepared);
    }

    private boolean persistenceAvailable() {
        return persistence != null && persistence.isAvailable();
    }

    private void enforceOwner(String tripId, String ownerUsername) {
        if (ownerUsername == null || ownerUsername.isBlank()) return;
        String knownOwner = tripOwners.get(tripId);
        if (knownOwner != null && !knownOwner.equals(ownerUsername)) {
            throw new TripNotFoundException(tripId);
        }
        if (knownOwner == null && persistenceAvailable() && !persistence.isOwnedBy(tripId, ownerUsername)) {
            throw new TripNotFoundException(tripId);
        }
    }

    private TripSnapshot snapshot(TripRecord record) {
        String disclaimer = "LIVE".equals(record.route.sourceMode())
                ? "高德道路路线 + RoadMind 数字孪生遥测"
                : "固定演示路线，不代表实时道路与交通状态";
        return new TripSnapshot(
                record.tripId, VEHICLE_ID, record.telemetry.status(), record.route, record.telemetry,
                record.lowBatteryReplanned, disclaimer, "/api/v1/trips/" + record.tripId + "/events");
    }

    private Map<String, Object> eventData(TripSnapshot snapshot) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tripId", snapshot.tripId());
        data.put("vehicleId", snapshot.vehicleId());
        data.put("status", snapshot.status());
        data.put("route", snapshot.route());
        data.put("telemetry", snapshot.telemetry());
        data.put("lowBatteryReplanned", snapshot.lowBatteryReplanned());
        data.put("sourceDisclaimer", snapshot.sourceDisclaimer());
        data.put("eventsUrl", snapshot.eventsUrl());
        return Map.copyOf(data);
    }

    private static final class TripRecord {
        private final String tripId;
        private RoutePlan route;
        private TripTelemetry telemetry;
        private boolean lowBatteryReplanned;
        private final Long ownerUserId;
        private final String ownerUsername;

        private TripRecord(
                String tripId,
                RoutePlan route,
                TripTelemetry telemetry,
                boolean lowBatteryReplanned,
                Long ownerUserId,
                String ownerUsername) {
            this.tripId = tripId;
            this.route = route;
            this.telemetry = telemetry;
            this.lowBatteryReplanned = lowBatteryReplanned;
            this.ownerUserId = ownerUserId;
            this.ownerUsername = ownerUsername;
        }

        private static TripRecord from(TripSnapshot snapshot) {
            return new TripRecord(
                    snapshot.tripId(), snapshot.route(), snapshot.telemetry(), snapshot.lowBatteryReplanned(), null, null);
        }
    }

    private record CreateReplay(String fingerprint, String tripId) {
    }

    private record EventSpec(String traceId, String type, Map<String, Object> data) {
    }
}
