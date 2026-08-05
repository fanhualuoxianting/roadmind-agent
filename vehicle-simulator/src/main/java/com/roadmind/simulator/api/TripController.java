package com.roadmind.simulator.api;

import com.roadmind.simulator.trip.ConfigureTripRequest;
import com.roadmind.simulator.trip.TripCommandRequest;
import com.roadmind.simulator.trip.TripEngine;
import com.roadmind.simulator.trip.TripSnapshot;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/internal/v1/trips")
public class TripController {
    private final TripEngine engine;

    public TripController(TripEngine engine) {
        this.engine = engine;
    }

    @PostMapping
    public SimulatorApiResponse<TripSnapshot> configure(
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody ConfigureTripRequest request) {
        return SimulatorApiResponse.ok(engine.configure(request, idempotencyKey));
    }

    @GetMapping("/{tripId}")
    public SimulatorApiResponse<TripSnapshot> get(@PathVariable String tripId) {
        return SimulatorApiResponse.ok(engine.get(tripId));
    }

    @PostMapping("/{tripId}/commands")
    public SimulatorApiResponse<TripSnapshot> command(
            @PathVariable String tripId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody TripCommandRequest request) {
        return SimulatorApiResponse.ok(engine.command(tripId, request, idempotencyKey));
    }
}
