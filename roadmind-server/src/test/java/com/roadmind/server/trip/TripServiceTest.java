package com.roadmind.server.trip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.roadmind.server.adapter.simulator.SimulatorTripAdapter;
import com.roadmind.server.agent.AgentIdempotencyConflictException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TripServiceTest {
    private final TripRoutePlanner planner = new StubTripRoutePlanner();
    private final SimulatorTripAdapter simulator = mock(SimulatorTripAdapter.class);
    private final TripEventHub events = new TripEventHub();
    private final TripService service = new TripService(planner, simulator, events);

    @Test
    void createsVersionTwoRouteWhenArrivalBatteryIsLow() {
        when(simulator.configure(anyString(), anyString(), any(), anyDouble(), anyString()))
                .thenAnswer(invocation -> telemetry(invocation.getArgument(0)));

        TripSnapshot snapshot = service.create(
                new CreateTripRequest("南京软件谷", "无锡学院", true, 42), "create-1");

        assertThat(snapshot.lowBatteryReplanned()).isTrue();
        assertThat(snapshot.route().routeVersion()).isEqualTo(2);
        assertThat(snapshot.route().chargingStation().chargingMinutes()).isEqualTo(18);
        assertThat(snapshot.sourceDisclaimer()).contains("固定演示路线");
    }

    @Test
    void replaysSameCreateAndRejectsSameKeyWithOtherPayload() {
        when(simulator.configure(anyString(), anyString(), any(), anyDouble(), anyString()))
                .thenAnswer(invocation -> telemetry(invocation.getArgument(0)));
        CreateTripRequest request = new CreateTripRequest("南京软件谷", "无锡学院", true, 42);
        String firstTripId = service.create(request, "same-key").tripId();
        assertThat(service.create(request, "same-key").tripId()).isEqualTo(firstTripId);
        assertThatThrownBy(() -> service.create(
                new CreateTripRequest("南京南站", "无锡学院", true, 42), "same-key"))
                .isInstanceOf(AgentIdempotencyConflictException.class);
    }

    @Test
    void oldIdempotentCommandReplayCannotMoveTelemetrySequenceBackwards() {
        when(simulator.configure(anyString(), anyString(), any(), anyDouble(), anyString()))
                .thenAnswer(invocation -> telemetry(invocation.getArgument(0)));
        when(simulator.command(anyString(), anyString(), any(), anyString()))
                .thenReturn(telemetry(2, TripStatus.DRIVING));
        TripSnapshot created = service.create(
                new CreateTripRequest("南京软件谷", "无锡学院", true, 42), "create-sequence");
        service.command(created.tripId(), new TripCommandRequest("START", 5), "start-sequence");
        when(simulator.get(created.tripId())).thenReturn(telemetry(10, TripStatus.DRIVING));
        service.pollSimulator();

        TripSnapshot replayed = service.command(
                created.tripId(), new TripCommandRequest("START", 5), "start-sequence");
        assertThat(replayed.telemetry().sequence()).isEqualTo(10);
    }

    @Test
    void differentOwnerCannotReadAnInMemoryTrip() {
        when(simulator.configure(anyString(), anyString(), any(), anyDouble(), anyString()))
                .thenAnswer(invocation -> telemetry(invocation.getArgument(0)));
        TripSnapshot created = service.create(
                new CreateTripRequest("南京软件谷", "无锡学院", true, 80), "owner-create", "alice");

        assertThatThrownBy(() -> service.get(created.tripId(), "bob"))
                .isInstanceOf(TripNotFoundException.class);
    }

    @Test
    void returnsTheCurrentOwnersActiveTripForResume() {
        when(simulator.configure(anyString(), anyString(), any(), anyDouble(), anyString()))
                .thenAnswer(invocation -> telemetry(invocation.getArgument(0)));
        TripSnapshot created = service.create(
                new CreateTripRequest("南京软件谷", "无锡学院", true, 42), "resume-key", "alice");

        assertThat(service.active("alice")).contains(created);
        assertThat(service.active("bob")).isEmpty();
    }

    private TripTelemetry telemetry(String tripId) {
        return telemetry(1, TripStatus.READY);
    }

    private TripTelemetry telemetry(long sequence, TripStatus status) {
        return new TripTelemetry(
                sequence, new RouteCoordinate(118.7358, 31.9827, "GCJ-02"), status == TripStatus.DRIVING ? 90 : 0, 90,
                42, 254, 0, 169_000, Instant.parse("2026-08-05T02:18:00Z"),
                status, 1, Instant.parse("2026-08-05T00:00:00Z"));
    }
}
