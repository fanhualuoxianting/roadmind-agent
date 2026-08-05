package com.roadmind.simulator.application;

import com.roadmind.simulator.domain.GeoLocation;
import com.roadmind.simulator.domain.IdempotencyConflictException;
import com.roadmind.simulator.domain.TirePressure;
import com.roadmind.simulator.domain.VehicleNotFoundException;
import com.roadmind.simulator.domain.VehicleState;
import com.roadmind.simulator.domain.VehicleStateConflictException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

@Service
public class VehicleStateService {

    public static final String DEMO_VEHICLE_ID = "demo-vehicle-001";
    private static final int MAX_IDEMPOTENCY_ENTRIES = 1_000;

    private final VehicleStateRepository repository;
    private final Clock clock;
    private final Map<String, IdempotencyResult> idempotencyResults = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, IdempotencyResult> eldest) {
            return size() > MAX_IDEMPOTENCY_ENTRIES;
        }
    };
    private final Map<String, ScheduledClimateCommand> scheduledCommands = new LinkedHashMap<>();
    private final Map<String, String> scheduledFingerprints = new LinkedHashMap<>();

    public VehicleStateService(VehicleStateRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
        repository.save(initialState(1, clock.instant()));
    }

    public synchronized VehicleState get(String vehicleId) {
        requireDemoVehicle(vehicleId);
        return repository.get();
    }

    public synchronized VehicleState update(
            String vehicleId,
            UpdateVehicleStateCommand command,
            String idempotencyKey) {
        requireDemoVehicle(vehicleId);
        String cacheKey = "PATCH:" + idempotencyKey;
        String fingerprint = command.fingerprint();
        IdempotencyResult replay = idempotencyResults.get(cacheKey);
        if (replay != null) {
            if (!replay.fingerprint().equals(fingerprint)) {
                throw new IdempotencyConflictException();
            }
            return replay.state();
        }

        VehicleState current = repository.get();
        if (current.stateVersion() != command.expectedVersion()) {
            throw new VehicleStateConflictException(command.expectedVersion(), current.stateVersion());
        }

        double battery = command.batteryPercent() == null
                ? current.batteryPercent()
                : command.batteryPercent();
        double temperature = command.cabinTemperature() == null
                ? current.cabinTemperature()
                : command.cabinTemperature();
        VehicleState updated = new VehicleState(
                current.vehicleId(),
                current.displayName(),
                current.mode(),
                battery,
                estimatedRange(battery),
                temperature,
                current.doorLocked(),
                current.charging(),
                current.gear(),
                current.location(),
                current.tirePressure(),
                current.stateVersion() + 1,
                clock.instant());
        repository.save(updated);
        idempotencyResults.put(cacheKey, new IdempotencyResult(fingerprint, updated));
        return updated;
    }

    public synchronized VehicleState reset(String vehicleId, String idempotencyKey) {
        requireDemoVehicle(vehicleId);
        String cacheKey = "RESET:" + idempotencyKey;
        IdempotencyResult replay = idempotencyResults.get(cacheKey);
        if (replay != null) {
            return replay.state();
        }
        long nextVersion = repository.get().stateVersion() + 1;
        VehicleState reset = initialState(nextVersion, clock.instant());
        repository.save(reset);
        idempotencyResults.put(cacheKey, new IdempotencyResult("reset", reset));
        return reset;
    }

    public synchronized ScheduledClimateCommand scheduleClimate(
            String vehicleId,
            Instant executeAt,
            double cabinTemperature,
            String idempotencyKey) {
        requireDemoVehicle(vehicleId);
        if (executeAt == null || !executeAt.isAfter(clock.instant())) {
            throw new IllegalArgumentException("executeAt 必须是未来的 UTC 时间");
        }
        if (cabinTemperature < -30 || cabinTemperature > 60) {
            throw new IllegalArgumentException("cabinTemperature 必须在 -30 到 60°C 之间");
        }
        String fingerprint = executeAt.truncatedTo(ChronoUnit.MILLIS) + "|" + cabinTemperature;
        String existingId = scheduledFingerprints.get(idempotencyKey);
        if (existingId != null) {
            if (!fingerprint.equals(scheduledCommands.get(existingId).executeAt() + "|"
                    + scheduledCommands.get(existingId).cabinTemperature())) {
                throw new IdempotencyConflictException();
            }
            return scheduledCommands.get(existingId);
        }
        ScheduledClimateCommand command = new ScheduledClimateCommand(
                java.util.UUID.randomUUID().toString(),
                vehicleId,
                executeAt.truncatedTo(ChronoUnit.MILLIS),
                cabinTemperature,
                "PENDING",
                idempotencyKey,
                clock.instant(),
                null,
                null);
        scheduledCommands.put(command.commandId(), command);
        scheduledFingerprints.put(idempotencyKey, command.commandId());
        return command;
    }

    public synchronized List<ScheduledClimateCommand> scheduledClimate(String vehicleId) {
        requireDemoVehicle(vehicleId);
        return scheduledCommands.values().stream()
                .filter(command -> command.vehicleId().equals(vehicleId))
                .sorted(Comparator.comparing(ScheduledClimateCommand::executeAt))
                .toList();
    }

    @Scheduled(fixedDelayString = "${simulator.scheduled-command-poll-ms:250}")
    public synchronized void executeDueClimateCommands() {
        executeDueClimateCommands(clock.instant());
    }

    synchronized void executeDueClimateCommands(Instant now) {
        List<ScheduledClimateCommand> due = new ArrayList<>(scheduledCommands.values()).stream()
                .filter(command -> "PENDING".equals(command.status()) && !command.executeAt().isAfter(now))
                .toList();
        for (ScheduledClimateCommand command : due) {
            VehicleState current = repository.get();
            try {
                VehicleState updated = update(
                        command.vehicleId(),
                        new UpdateVehicleStateCommand(current.stateVersion(), null, command.cabinTemperature()),
                        "scheduled-climate:" + command.commandId());
                scheduledCommands.put(command.commandId(), new ScheduledClimateCommand(
                        command.commandId(), command.vehicleId(), command.executeAt(), command.cabinTemperature(),
                        "SUCCEEDED", command.idempotencyKey(), command.createdAt(), now, updated.stateVersion()));
            } catch (RuntimeException exception) {
                scheduledCommands.put(command.commandId(), new ScheduledClimateCommand(
                        command.commandId(), command.vehicleId(), command.executeAt(), command.cabinTemperature(),
                        "FAILED", command.idempotencyKey(), command.createdAt(), now, null));
            }
        }
    }

    private void requireDemoVehicle(String vehicleId) {
        if (!DEMO_VEHICLE_ID.equals(vehicleId)) {
            throw new VehicleNotFoundException(vehicleId);
        }
    }

    private VehicleState initialState(long version, Instant observedAt) {
        return new VehicleState(
                DEMO_VEHICLE_ID,
                "RoadMind Demo Car",
                "DIGITAL_TWIN",
                68.0,
                estimatedRange(68.0),
                29.0,
                true,
                false,
                "P",
                new GeoLocation("GCJ-02", 118.7969, 32.0603, "南京"),
                new TirePressure(2.4, 2.4, 2.3, 2.3),
                version,
                observedAt);
    }

    private double estimatedRange(double batteryPercent) {
        return BigDecimal.valueOf(batteryPercent * 412.0 / 68.0)
                .setScale(1, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private record IdempotencyResult(String fingerprint, VehicleState state) {
    }
}
