package com.roadmind.server.api;

import com.roadmind.server.vehicle.application.VehicleApplicationService;
import com.roadmind.server.vehicle.domain.VehicleStateUpdate;
import com.roadmind.server.vehicle.domain.VehicleStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@Profile("demo")
@RequestMapping("/api/v1/demo/vehicles")
public class DemoVehicleController {

    private final VehicleApplicationService service;

    public DemoVehicleController(VehicleApplicationService service) {
        this.service = service;
    }

    @PatchMapping("/{vehicleId}/state")
    public ApiResponse<VehicleStatus> updateState(
            @PathVariable String vehicleId,
            @RequestHeader("Idempotency-Key")
            @Size(min = 1, max = 128)
            @Pattern(regexp = "[A-Za-z0-9._:-]+", message = "Idempotency-Key 格式不合法")
            String idempotencyKey,
            @Valid @RequestBody UpdateVehicleStateRequest request) {
        return ApiResponse.ok(service.updateState(
                vehicleId,
                new VehicleStateUpdate(
                        request.expectedVersion(),
                        request.batteryPercent(),
                        request.cabinTemperature()),
                idempotencyKey));
    }
}
