package com.roadmind.server.vehicle.application;

import com.roadmind.server.vehicle.domain.VehicleGateway;
import com.roadmind.server.vehicle.domain.VehicleNotFoundException;
import com.roadmind.server.vehicle.domain.VehicleStateUpdate;
import com.roadmind.server.vehicle.domain.VehicleStatus;
import com.roadmind.server.vehicle.domain.VehicleSummary;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class VehicleApplicationService {

    public static final String API_VEHICLE_ID = "198000000000000401";
    private static final String SIMULATOR_VEHICLE_ID = "demo-vehicle-001";
    private static final String DISPLAY_NAME = "RoadMind Demo Car";

    private final VehicleGateway gateway;

    public VehicleApplicationService(VehicleGateway gateway) {
        this.gateway = gateway;
    }

    public List<VehicleSummary> listVehicles() {
        return List.of(new VehicleSummary(API_VEHICLE_ID, DISPLAY_NAME, "DIGITAL_TWIN", "ACTIVE"));
    }

    public VehicleStatus getStatus(String vehicleId) {
        requireDemoVehicle(vehicleId);
        return withApiId(gateway.getStatus(SIMULATOR_VEHICLE_ID));
    }

    public VehicleStatus updateState(
            String vehicleId,
            VehicleStateUpdate update,
            String idempotencyKey) {
        requireDemoVehicle(vehicleId);
        return withApiId(gateway.updateState(SIMULATOR_VEHICLE_ID, update, idempotencyKey));
    }

    private void requireDemoVehicle(String vehicleId) {
        if (!API_VEHICLE_ID.equals(vehicleId)) {
            throw new VehicleNotFoundException(vehicleId);
        }
    }

    private VehicleStatus withApiId(VehicleStatus status) {
        return new VehicleStatus(
                API_VEHICLE_ID,
                status.displayName(),
                status.mode(),
                status.batteryPercent(),
                status.estimatedRangeKm(),
                status.cabinTemperature(),
                status.doorLocked(),
                status.charging(),
                status.gear(),
                status.location(),
                status.tirePressure(),
                status.stateVersion(),
                status.observedAt());
    }
}
