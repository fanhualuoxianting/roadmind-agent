package com.roadmind.server.adapter.simulator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.roadmind.server.vehicle.domain.VehicleGateway;
import com.roadmind.server.vehicle.domain.VehicleGatewayClientException;
import com.roadmind.server.vehicle.domain.VehicleGatewayUnavailableException;
import com.roadmind.server.vehicle.domain.VehicleStateUpdate;
import com.roadmind.server.vehicle.domain.VehicleStatus;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;

@Component
public class SimulatorVehicleAdapter implements VehicleGateway {

    private static final ParameterizedTypeReference<SimulatorApiResponse<SimulatorVehicleState>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient client;
    private final ObjectMapper objectMapper;

    public SimulatorVehicleAdapter(
            @org.springframework.beans.factory.annotation.Qualifier("simulatorRestClient") RestClient client,
            ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Override
    @CircuitBreaker(name = "vehicleSimulator")
    @Bulkhead(name = "vehicleSimulator")
    public VehicleStatus getStatus(String simulatorVehicleId) {
        try {
            SimulatorApiResponse<SimulatorVehicleState> response = client.get()
                    .uri("/internal/v1/vehicles/{vehicleId}/state", simulatorVehicleId)
                    .retrieve()
                    .body(RESPONSE_TYPE);
            return mapResponse(response);
        } catch (RestClientResponseException exception) {
            throw mapRemoteError(exception);
        } catch (ResourceAccessException exception) {
            throw unavailable(exception);
        }
    }

    @Override
    public VehicleStatus updateState(
            String simulatorVehicleId,
            VehicleStateUpdate update,
            String idempotencyKey) {
        try {
            SimulatorApiResponse<SimulatorVehicleState> response = client.patch()
                    .uri("/internal/v1/vehicles/{vehicleId}/state", simulatorVehicleId)
                    .header("Idempotency-Key", idempotencyKey)
                    .body(new SimulatorUpdateRequest(
                            update.expectedVersion(),
                            update.batteryPercent(),
                            update.cabinTemperature()))
                    .retrieve()
                    .body(RESPONSE_TYPE);
            return mapResponse(response);
        } catch (RestClientResponseException exception) {
            throw mapRemoteError(exception);
        } catch (ResourceAccessException exception) {
            throw unavailable(exception);
        }
    }

    private VehicleStatus mapResponse(SimulatorApiResponse<SimulatorVehicleState> response) {
        if (response == null || response.data() == null || !"DIGITAL_TWIN".equals(response.data().mode())) {
            throw new VehicleGatewayClientException(
                    HttpStatus.BAD_GATEWAY,
                    "TOOL_RESULT_INVALID",
                    "车辆模拟器返回了无效状态");
        }
        SimulatorVehicleState state = response.data();
        return new VehicleStatus(
                state.vehicleId(),
                state.displayName(),
                state.mode(),
                state.batteryPercent(),
                state.estimatedRangeKm(),
                state.cabinTemperature(),
                state.doorLocked(),
                state.charging(),
                state.gear(),
                state.location(),
                state.tirePressure(),
                state.stateVersion(),
                state.observedAt());
    }

    private VehicleGatewayClientException mapRemoteError(RestClientResponseException exception) {
        try {
            SimulatorErrorResponse error = objectMapper.readValue(
                    exception.getResponseBodyAsByteArray(),
                    SimulatorErrorResponse.class);
            return new VehicleGatewayClientException(exception.getStatusCode(), error.code(), error.message());
        } catch (Exception ignored) {
            return new VehicleGatewayClientException(
                    exception.getStatusCode(),
                    "TOOL_RESULT_INVALID",
                    "车辆模拟器返回了无法识别的错误");
        }
    }

    private VehicleGatewayUnavailableException unavailable(ResourceAccessException exception) {
        return new VehicleGatewayUnavailableException("车辆数字孪生模拟器暂时离线", exception);
    }
}
