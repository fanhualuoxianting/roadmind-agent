package com.roadmind.simulator.api;

import com.roadmind.simulator.application.UpdateVehicleStateCommand;
import com.roadmind.simulator.application.ScheduledClimateCommand;
import com.roadmind.simulator.application.VehicleStateService;
import com.roadmind.simulator.domain.VehicleState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import java.util.List;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/internal/v1")
public class VehicleStateController {

    private final VehicleStateService service;

    public VehicleStateController(VehicleStateService service) {
        this.service = service;
    }

    @GetMapping("/vehicles/{vehicleId}/state")
    public SimulatorApiResponse<VehicleState> getState(@PathVariable String vehicleId) {
        return SimulatorApiResponse.ok(service.get(vehicleId));
    }

    @PatchMapping("/vehicles/{vehicleId}/state")
    public SimulatorApiResponse<VehicleState> updateState(
            @PathVariable String vehicleId,
            @RequestHeader("Idempotency-Key")
            @Size(min = 1, max = 128)
            @Pattern(regexp = "[A-Za-z0-9._:-]+", message = "Idempotency-Key 格式不合法")
            String idempotencyKey,
            @Valid @RequestBody UpdateVehicleStateRequest request) {
        VehicleState state = service.update(
                vehicleId,
                new UpdateVehicleStateCommand(
                        request.expectedVersion(),
                        request.batteryPercent(),
                        request.cabinTemperature()),
                idempotencyKey);
        return SimulatorApiResponse.ok(state);
    }

    @PostMapping("/demo/vehicles/{vehicleId}/reset")
    public SimulatorApiResponse<VehicleState> reset(
            @PathVariable String vehicleId,
            @RequestHeader("Idempotency-Key")
            @Size(min = 1, max = 128)
            @Pattern(regexp = "[A-Za-z0-9._:-]+", message = "Idempotency-Key 格式不合法")
            String idempotencyKey) {
        return SimulatorApiResponse.ok(service.reset(vehicleId, idempotencyKey));
    }

    @PostMapping("/vehicles/{vehicleId}/commands/schedule-climate")
    public SimulatorApiResponse<ScheduledClimateCommand> scheduleClimate(
            @PathVariable String vehicleId,
            @RequestHeader("Idempotency-Key")
            @Size(min = 1, max = 128)
            @Pattern(regexp = "[A-Za-z0-9._:-]+", message = "Idempotency-Key 格式不合法")
            String idempotencyKey,
            @Valid @RequestBody ScheduleClimateRequest request) {
        return SimulatorApiResponse.ok(service.scheduleClimate(
                vehicleId, request.executeAt(), request.cabinTemperature(), idempotencyKey));
    }

    @GetMapping("/vehicles/{vehicleId}/scheduled-tasks")
    public SimulatorApiResponse<List<ScheduledClimateCommand>> scheduledClimate(
            @PathVariable String vehicleId) {
        return SimulatorApiResponse.ok(service.scheduledClimate(vehicleId));
    }
}
