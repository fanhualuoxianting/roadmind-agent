package com.roadmind.server.api;

import com.roadmind.server.vehicle.application.VehicleApplicationService;
import com.roadmind.server.vehicle.domain.VehicleStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/vehicles")
public class VehicleController {

    private final VehicleApplicationService service;

    public VehicleController(VehicleApplicationService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<VehicleListData> listVehicles() {
        return ApiResponse.ok(new VehicleListData(service.listVehicles(), null));
    }

    @GetMapping("/{vehicleId}/status")
    public ApiResponse<VehicleStatus> getStatus(@PathVariable String vehicleId) {
        return ApiResponse.ok(service.getStatus(vehicleId));
    }
}
