package com.roadmind.server.api;

import com.roadmind.server.trip.CreateTripRequest;
import com.roadmind.server.trip.TripCommandRequest;
import com.roadmind.server.trip.TripService;
import com.roadmind.server.trip.TripSnapshot;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Validated
@RestController
@RequestMapping("/api/v1/trips")
public class TripController {
    private final TripService service;

    public TripController(TripService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<ApiResponse<TripSnapshot>> create(
            Principal principal,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody CreateTripRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(
                "TRIP_CREATED", "数字孪生行程已创建", service.create(request, idempotencyKey, principal.getName())));
    }

    @GetMapping("/active")
    ApiResponse<TripSnapshot> active(Principal principal) {
        return ApiResponse.ok(service.active(principal.getName()).orElse(null));
    }

    @GetMapping("/{tripId}")
    ApiResponse<TripSnapshot> get(Principal principal, @PathVariable String tripId) {
        return ApiResponse.ok(service.get(tripId, principal.getName()));
    }

    @PostMapping("/{tripId}/commands")
    ApiResponse<TripSnapshot> command(
            Principal principal,
            @PathVariable String tripId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody TripCommandRequest request) {
        return ApiResponse.ok(service.command(tripId, request, idempotencyKey, principal.getName()));
    }

    @GetMapping(value = "/{tripId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events(
            Principal principal,
            @PathVariable String tripId,
            @RequestHeader(value = "Last-Event-ID", required = false) String headerLastEventId,
            @RequestParam(value = "lastEventId", required = false) String queryLastEventId) {
        return service.events(
                tripId,
                headerLastEventId != null ? headerLastEventId : queryLastEventId,
                principal.getName());
    }
}
