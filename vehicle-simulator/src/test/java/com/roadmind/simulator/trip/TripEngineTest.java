package com.roadmind.simulator.trip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.roadmind.simulator.application.VehicleStateService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class TripEngineTest {
    private final TripEngine engine = new TripEngine(Clock.fixed(Instant.parse("2026-08-05T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void advancesDeterministicallyAndPausesWithoutMoving() {
        TripSnapshot ready = engine.configure(request(), "configure-1");
        engine.command(ready.tripId(), new TripCommandRequest("START", 5), "start-1");
        TripSnapshot moving = engine.advance(Duration.ofSeconds(2));
        engine.command(ready.tripId(), new TripCommandRequest("PAUSE", null), "pause-1");
        TripSnapshot paused = engine.advance(Duration.ofSeconds(5));

        assertThat(moving.travelledMeters()).isEqualTo(250.0);
        assertThat(paused.travelledMeters()).isEqualTo(moving.travelledMeters());
        assertThat(paused.status()).isEqualTo(TripStatus.PAUSED);
    }

    @Test
    void speedMultiplierDoesNotChangeEnergyPerDistance() {
        TripSnapshot ready = engine.configure(request(), "configure-1");
        engine.command(ready.tripId(), new TripCommandRequest("START", 20), "start-1");
        TripSnapshot moving = engine.advance(Duration.ofSeconds(2));
        assertThat(moving.travelledMeters()).isEqualTo(1_000.0);
        assertThat(moving.batteryPercent()).isEqualTo(41.84);
    }

    @Test
    void rejectsIllegalTransition() {
        TripSnapshot ready = engine.configure(request(), "configure-1");
        assertThatThrownBy(() -> engine.command(ready.tripId(), new TripCommandRequest("RESUME", null), "bad-1"))
                .isInstanceOf(TripTransitionException.class);
    }

    private ConfigureTripRequest request() {
        return new ConfigureTripRequest(
                "trip-1", VehicleStateService.DEMO_VEHICLE_ID, 1, "route-hash", "STUB",
                List.of(
                        new TripCoordinate(118.74, 31.98, "GCJ-02"),
                        new TripCoordinate(119.20, 31.85, "GCJ-02"),
                        new TripCoordinate(120.30, 31.57, "GCJ-02")),
                42.0);
    }
}
