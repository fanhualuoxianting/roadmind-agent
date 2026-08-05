package com.roadmind.simulator.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.roadmind.simulator.domain.IdempotencyConflictException;
import com.roadmind.simulator.domain.VehicleState;
import com.roadmind.simulator.domain.VehicleStateConflictException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VehicleStateServiceTest {

    private VehicleStateService service;

    @BeforeEach
    void setUp() {
        service = new VehicleStateService(
                new InMemoryVehicleStateRepository(),
                Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void exposesInitialDigitalTwinState() {
        VehicleState state = service.get(VehicleStateService.DEMO_VEHICLE_ID);

        assertThat(state.mode()).isEqualTo("DIGITAL_TWIN");
        assertThat(state.batteryPercent()).isEqualTo(68.0);
        assertThat(state.stateVersion()).isEqualTo(1);
        assertThat(state.location().coordinateSystem()).isEqualTo("GCJ-02");
    }

    @Test
    void updatesWhitelistedFieldsAndIncrementsVersion() {
        VehicleState updated = service.update(
                VehicleStateService.DEMO_VEHICLE_ID,
                new UpdateVehicleStateCommand(1, 42.0, 24.0),
                "update-001");

        assertThat(updated.batteryPercent()).isEqualTo(42.0);
        assertThat(updated.cabinTemperature()).isEqualTo(24.0);
        assertThat(updated.stateVersion()).isEqualTo(2);
        assertThat(updated.estimatedRangeKm()).isEqualTo(254.5);
    }

    @Test
    void rejectsStaleExpectedVersion() {
        service.update(
                VehicleStateService.DEMO_VEHICLE_ID,
                new UpdateVehicleStateCommand(1, 50.0, null),
                "update-001");

        assertThatThrownBy(() -> service.update(
                VehicleStateService.DEMO_VEHICLE_ID,
                new UpdateVehicleStateCommand(1, 40.0, null),
                "update-002"))
                .isInstanceOf(VehicleStateConflictException.class);
    }

    @Test
    void replaysSameIdempotentRequestWithoutSecondMutation() {
        UpdateVehicleStateCommand command = new UpdateVehicleStateCommand(1, 51.0, null);

        VehicleState first = service.update(VehicleStateService.DEMO_VEHICLE_ID, command, "same-key");
        VehicleState replay = service.update(VehicleStateService.DEMO_VEHICLE_ID, command, "same-key");

        assertThat(replay).isEqualTo(first);
        assertThat(service.get(VehicleStateService.DEMO_VEHICLE_ID).stateVersion()).isEqualTo(2);
    }

    @Test
    void rejectsSameIdempotencyKeyWithDifferentParameters() {
        service.update(
                VehicleStateService.DEMO_VEHICLE_ID,
                new UpdateVehicleStateCommand(1, 51.0, null),
                "same-key");

        assertThatThrownBy(() -> service.update(
                VehicleStateService.DEMO_VEHICLE_ID,
                new UpdateVehicleStateCommand(1, 52.0, null),
                "same-key"))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void resetRestoresDemoValuesAndKeepsVersionMonotonic() {
        service.update(
                VehicleStateService.DEMO_VEHICLE_ID,
                new UpdateVehicleStateCommand(1, 15.0, 5.0),
                "update-001");

        VehicleState reset = service.reset(VehicleStateService.DEMO_VEHICLE_ID, "reset-001");

        assertThat(reset.batteryPercent()).isEqualTo(68.0);
        assertThat(reset.cabinTemperature()).isEqualTo(29.0);
        assertThat(reset.stateVersion()).isEqualTo(3);
    }

    @Test
    void nativeClimateScheduleExecutesOnceAndReplaysByIdempotencyKey() {
        Instant executeAt = Instant.parse("2026-08-04T00:00:01Z");

        ScheduledClimateCommand first = service.scheduleClimate(
                VehicleStateService.DEMO_VEHICLE_ID, executeAt, 22.0, "schedule-001");
        ScheduledClimateCommand replay = service.scheduleClimate(
                VehicleStateService.DEMO_VEHICLE_ID, executeAt, 22.0, "schedule-001");

        assertThat(replay).isEqualTo(first);
        assertThat(service.get(VehicleStateService.DEMO_VEHICLE_ID).cabinTemperature()).isEqualTo(29.0);
        service.executeDueClimateCommands(Instant.parse("2026-08-04T00:00:02Z"));
        assertThat(service.get(VehicleStateService.DEMO_VEHICLE_ID).cabinTemperature()).isEqualTo(22.0);
        assertThat(service.scheduledClimate(VehicleStateService.DEMO_VEHICLE_ID).getFirst().status())
                .isEqualTo("SUCCEEDED");
        service.executeDueClimateCommands(Instant.parse("2026-08-04T00:00:03Z"));
        assertThat(service.get(VehicleStateService.DEMO_VEHICLE_ID).stateVersion()).isEqualTo(2);
    }
}
