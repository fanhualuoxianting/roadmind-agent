package com.roadmind.server.adapter.simulator;

import com.roadmind.server.vehicle.domain.VehicleGatewayClientException;
import com.roadmind.server.vehicle.domain.VehicleNativeScheduler;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;

@Component
public class SimulatorVehicleScheduleAdapter implements VehicleNativeScheduler {

    private static final ParameterizedTypeReference<SimulatorApiResponse<ScheduledClimateCommand>> RESPONSE_TYPE =
            new ParameterizedTypeReference<>() {
            };

    private final RestClient client;

    public SimulatorVehicleScheduleAdapter(@Qualifier("simulatorRestClient") RestClient client) {
        this.client = client;
    }

    @Override
    @CircuitBreaker(name = "vehicleSimulator")
    @Bulkhead(name = "vehicleSimulator")
    public ScheduledClimateCommand scheduleClimate(
            String simulatorVehicleId,
            Instant executeAt,
            double cabinTemperature,
            String idempotencyKey) {
        try {
            SimulatorApiResponse<ScheduledClimateCommand> response = client.post()
                    .uri("/internal/v1/vehicles/{vehicleId}/commands/schedule-climate", simulatorVehicleId)
                    .header("Idempotency-Key", idempotencyKey)
                    .body(new ScheduleClimatePayload(executeAt, cabinTemperature))
                    .retrieve()
                    .body(RESPONSE_TYPE);
            if (response == null || response.data() == null) {
                throw new VehicleGatewayClientException(
                        HttpStatus.BAD_GATEWAY, "TOOL_RESULT_INVALID", "车辆调度接口返回为空");
            }
            return response.data();
        } catch (RestClientResponseException exception) {
            throw new VehicleGatewayClientException(
                    exception.getStatusCode(), "VEHICLE_SCHEDULE_FAILED", "车辆原生定时命令创建失败");
        } catch (ResourceAccessException exception) {
            throw new VehicleGatewayClientException(
                    HttpStatus.SERVICE_UNAVAILABLE, "VEHICLE_SIMULATOR_UNAVAILABLE", "车辆数字孪生模拟器暂时离线");
        }
    }

    private record ScheduleClimatePayload(Instant executeAt, double cabinTemperature) {
    }
}
