package com.roadmind.server.adapter.simulator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.roadmind.server.trip.RouteCoordinate;
import com.roadmind.server.trip.RoutePlan;
import com.roadmind.server.trip.TripStatus;
import com.roadmind.server.trip.TripTelemetry;
import com.roadmind.server.vehicle.domain.VehicleGatewayUnavailableException;
import com.roadmind.server.vehicle.domain.VehicleGatewayClientException;
import java.util.List;
import java.util.Map;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;

@Component
public class SimulatorTripAdapter {
    private static final ParameterizedTypeReference<SimulatorApiResponse<SimulatorTripState>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() { };
    private final RestClient client;
    private final ObjectMapper objectMapper;

    public SimulatorTripAdapter(
            @org.springframework.beans.factory.annotation.Qualifier("simulatorRestClient") RestClient client,
            ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    public TripTelemetry configure(String tripId, String vehicleId, RoutePlan route, double initialBattery, String key) {
        List<Map<String, Object>> points = route.polyline().stream().map(point -> Map.<String, Object>of(
                "longitude", point.longitude(), "latitude", point.latitude(), "coordinateSystem", point.coordinateSystem())).toList();
        Map<String, Object> body = Map.of(
                "tripId", tripId, "vehicleId", vehicleId, "routeVersion", route.routeVersion(),
                "routeHash", route.routeHash(), "sourceMode", route.sourceMode(), "polyline", points,
                "initialBatteryPercent", initialBattery);
        return call(() -> client.post().uri("/internal/v1/trips").header("Idempotency-Key", key)
                .body(body).retrieve().body(RESPONSE_TYPE));
    }

    // Read-only polling may be isolated and circuit-broken; command/configure stay
    // single-attempt because retrying them could duplicate simulator side effects.
    @CircuitBreaker(name = "tripSimulator")
    @Bulkhead(name = "tripSimulator")
    public TripTelemetry get(String tripId) {
        return call(() -> client.get().uri("/internal/v1/trips/{tripId}", tripId).retrieve().body(RESPONSE_TYPE));
    }

    public TripTelemetry command(String tripId, String action, Integer speed, String key) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("action", action);
        if (speed != null) body.put("simulationSpeed", speed);
        return call(() -> client.post().uri("/internal/v1/trips/{tripId}/commands", tripId)
                .header("Idempotency-Key", key).body(body).retrieve().body(RESPONSE_TYPE));
    }

    private TripTelemetry call(java.util.function.Supplier<SimulatorApiResponse<SimulatorTripState>> operation) {
        try {
            SimulatorApiResponse<SimulatorTripState> response = operation.get();
            if (response == null || response.data() == null || response.data().position() == null) {
                throw new VehicleGatewayClientException(
                        HttpStatus.BAD_GATEWAY, "TOOL_RESULT_INVALID", "行程模拟器返回了无效状态");
            }
            SimulatorTripState state = response.data();
            return new TripTelemetry(
                    state.sequence(), new RouteCoordinate(state.position().longitude(), state.position().latitude(), state.position().coordinateSystem()),
                    state.speedKmh(), state.heading(), state.batteryPercent(), state.remainingRangeKm(), state.travelledMeters(),
                    state.remainingDistanceMeters(), state.estimatedArrivalTime(), TripStatus.valueOf(state.status()),
                    state.simulationSpeed(), state.observedAt());
        } catch (RestClientResponseException exception) {
            try {
                SimulatorErrorResponse error = objectMapper.readValue(
                        exception.getResponseBodyAsByteArray(), SimulatorErrorResponse.class);
                throw new VehicleGatewayClientException(exception.getStatusCode(), error.code(), error.message());
            } catch (VehicleGatewayClientException mapped) {
                throw mapped;
            } catch (Exception ignored) {
                throw new VehicleGatewayClientException(
                        exception.getStatusCode(), "TOOL_RESULT_INVALID", "行程模拟器返回了无法识别的错误");
            }
        } catch (ResourceAccessException exception) {
            throw new VehicleGatewayUnavailableException("车辆行程模拟器暂时离线", exception);
        }
    }
}
