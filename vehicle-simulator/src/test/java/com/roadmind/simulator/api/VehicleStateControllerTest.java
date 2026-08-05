package com.roadmind.simulator.api;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.roadmind.simulator.application.VehicleStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class VehicleStateControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private VehicleStateService service;

    private long currentVersion;

    @BeforeEach
    void resetState() {
        currentVersion = service.reset(
                VehicleStateService.DEMO_VEHICLE_ID,
                "reset-" + System.nanoTime()).stateVersion();
    }

    @Test
    void returnsStateWithSimulationModeAndTraceId() throws Exception {
        mockMvc.perform(get("/internal/v1/vehicles/demo-vehicle-001/state")
                        .header("X-Request-ID", "request-12345678"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-ID", "request-12345678"))
                .andExpect(jsonPath("$.data.mode").value("DIGITAL_TWIN"))
                .andExpect(jsonPath("$.data.stateVersion").value(currentVersion));
    }

    @Test
    void rejectsOutOfRangeBattery() throws Exception {
        String body = objectMapper.writeValueAsString(new UpdateVehicleStateRequest(
                currentVersion,
                101.0,
                null));

        mockMvc.perform(patch("/internal/v1/vehicles/demo-vehicle-001/state")
                        .header("Idempotency-Key", "invalid-range-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.traceId", not(blankOrNullString())));
    }

    @Test
    void rejectsUnknownMutationField() throws Exception {
        String body = """
                {"expectedVersion":%d,"doorLocked":false}
                """.formatted(currentVersion);

        mockMvc.perform(patch("/internal/v1/vehicles/demo-vehicle-001/state")
                        .header("Idempotency-Key", "unknown-field-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void returnsConflictForStaleVersion() throws Exception {
        String firstBody = objectMapper.writeValueAsString(new UpdateVehicleStateRequest(
                currentVersion,
                50.0,
                null));
        mockMvc.perform(patch("/internal/v1/vehicles/demo-vehicle-001/state")
                        .header("Idempotency-Key", "first-update-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(firstBody))
                .andExpect(status().isOk());

        String staleBody = objectMapper.writeValueAsString(new UpdateVehicleStateRequest(
                currentVersion,
                40.0,
                null));
        mockMvc.perform(patch("/internal/v1/vehicles/demo-vehicle-001/state")
                        .header("Idempotency-Key", "stale-update-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(staleBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VEHICLE_STATE_CONFLICT"));
    }

    @Test
    void rejectsMissingIdempotencyKey() throws Exception {
        String body = objectMapper.writeValueAsString(new UpdateVehicleStateRequest(
                currentVersion,
                42.0,
                null));

        mockMvc.perform(patch("/internal/v1/vehicles/demo-vehicle-001/state")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }
}
