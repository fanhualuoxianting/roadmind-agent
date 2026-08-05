package com.roadmind.server.adapter.simulator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.roadmind.server.vehicle.domain.VehicleGatewayUnavailableException;
import com.roadmind.server.vehicle.domain.VehicleStateUpdate;
import com.roadmind.server.vehicle.domain.VehicleStatus;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class SimulatorVehicleAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void mapsSimulatorContractToDomainState() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://simulator.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SimulatorVehicleAdapter adapter = new SimulatorVehicleAdapter(builder.build(), objectMapper);
        server.expect(once(), requestTo("http://simulator.test/internal/v1/vehicles/demo-vehicle-001/state"))
                .andRespond(withSuccess(successBody(), MediaType.APPLICATION_JSON));

        VehicleStatus status = adapter.getStatus("demo-vehicle-001");

        assertThat(status.mode()).isEqualTo("DIGITAL_TWIN");
        assertThat(status.stateVersion()).isEqualTo(12);
        assertThat(status.batteryPercent()).isEqualTo(38.0);
        server.verify();
    }

    @Test
    void forwardsIdempotencyKeyOnMutation() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://simulator.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SimulatorVehicleAdapter adapter = new SimulatorVehicleAdapter(builder.build(), objectMapper);
        server.expect(once(), requestTo("http://simulator.test/internal/v1/vehicles/demo-vehicle-001/state"))
                .andExpect(header("Idempotency-Key", "update-001"))
                .andRespond(withSuccess(successBody(), MediaType.APPLICATION_JSON));

        adapter.updateState(
                "demo-vehicle-001",
                new VehicleStateUpdate(12, 40.0, null),
                "update-001");

        server.verify();
    }

    @Test
    void reportsSimulatorConnectionFailureAsUnavailable() {
        RestClient.Builder builder = RestClient.builder().baseUrl("http://simulator.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        SimulatorVehicleAdapter adapter = new SimulatorVehicleAdapter(builder.build(), objectMapper);
        server.expect(once(), requestTo("http://simulator.test/internal/v1/vehicles/demo-vehicle-001/state"))
                .andRespond(withException(new IOException("connection refused")));

        assertThatThrownBy(() -> adapter.getStatus("demo-vehicle-001"))
                .isInstanceOf(VehicleGatewayUnavailableException.class)
                .hasMessageContaining("离线");
        server.verify();
    }

    private String successBody() {
        return """
                {
                  "code":"OK",
                  "message":"success",
                  "data":{
                    "vehicleId":"demo-vehicle-001",
                    "displayName":"RoadMind Demo Car",
                    "mode":"DIGITAL_TWIN",
                    "batteryPercent":38.0,
                    "estimatedRangeKm":210.0,
                    "cabinTemperature":8.5,
                    "doorLocked":true,
                    "charging":false,
                    "gear":"P",
                    "location":{"coordinateSystem":"GCJ-02","longitude":118.7969,"latitude":32.0603,"city":"南京"},
                    "tirePressure":{"frontLeft":2.4,"frontRight":2.4,"rearLeft":2.3,"rearRight":2.3},
                    "stateVersion":12,
                    "observedAt":"2026-08-04T00:00:00Z"
                  },
                  "traceId":"trace-001",
                  "timestamp":"2026-08-04T00:00:00Z"
                }
                """;
    }
}
