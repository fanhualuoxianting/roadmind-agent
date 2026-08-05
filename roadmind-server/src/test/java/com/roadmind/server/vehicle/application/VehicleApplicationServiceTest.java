package com.roadmind.server.vehicle.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.roadmind.server.vehicle.domain.GeoLocation;
import com.roadmind.server.vehicle.domain.TirePressure;
import com.roadmind.server.vehicle.domain.VehicleGateway;
import com.roadmind.server.vehicle.domain.VehicleNotFoundException;
import com.roadmind.server.vehicle.domain.VehicleStatus;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class VehicleApplicationServiceTest {

    private final VehicleGateway gateway = mock(VehicleGateway.class);
    private final VehicleApplicationService service = new VehicleApplicationService(gateway);

    @Test
    void exposesBigintVehicleIdAsString() {
        assertThat(service.listVehicles())
                .singleElement()
                .extracting(summary -> summary.vehicleId())
                .isEqualTo("198000000000000401");
    }

    @Test
    void mapsPublicIdToStableSimulatorId() {
        when(gateway.getStatus("demo-vehicle-001")).thenReturn(simulatorState());

        VehicleStatus status = service.getStatus(VehicleApplicationService.API_VEHICLE_ID);

        assertThat(status.vehicleId()).isEqualTo(VehicleApplicationService.API_VEHICLE_ID);
        assertThat(status.mode()).isEqualTo("DIGITAL_TWIN");
        verify(gateway).getStatus("demo-vehicle-001");
    }

    @Test
    void hidesUnknownVehicleAsNotFound() {
        assertThatThrownBy(() -> service.getStatus("198000000000009999"))
                .isInstanceOf(VehicleNotFoundException.class);
    }

    private VehicleStatus simulatorState() {
        return new VehicleStatus(
                "demo-vehicle-001",
                "RoadMind Demo Car",
                "DIGITAL_TWIN",
                68.0,
                412.0,
                29.0,
                true,
                false,
                "P",
                new GeoLocation("GCJ-02", 118.7969, 32.0603, "南京"),
                new TirePressure(2.4, 2.4, 2.3, 2.3),
                1,
                Instant.parse("2026-08-04T00:00:00Z"));
    }
}
