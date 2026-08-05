package com.roadmind.server.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.roadmind.server.adapter.simulator.SimulatorTripAdapter;
import com.roadmind.server.trip.RouteCoordinate;
import com.roadmind.server.trip.TripStatus;
import com.roadmind.server.trip.TripTelemetry;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.ai.model.chat=none",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"
})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class TripApiTest {
    @Autowired MockMvc mockMvc;
    @MockitoBean SimulatorTripAdapter simulator;

    @BeforeEach
    void setUp() {
        when(simulator.configure(anyString(), anyString(), any(), anyDouble(), anyString()))
                .thenAnswer(invocation -> telemetry(1, TripStatus.READY));
        when(simulator.command(anyString(), anyString(), any(), anyString()))
                .thenAnswer(invocation -> telemetry(2, TripStatus.DRIVING));
    }

    @Test
    void createsAndStartsDigitalTwinTripThroughCsrfProtectedApi() throws Exception {
        String response = mockMvc.perform(post("/api/v1/trips").with(csrf())
                        .header("Idempotency-Key", "trip-api-create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"origin":"南京软件谷","destination":"无锡学院","avoidTraffic":true,"initialBatteryPercent":42}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.route.routeVersion").value(2))
                .andExpect(jsonPath("$.data.route.sourceMode").value("STUB"))
                .andReturn().getResponse().getContentAsString();
        String tripId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).path("data").path("tripId").asText();

        mockMvc.perform(get("/api/v1/trips/active").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tripId").value(tripId));

        mockMvc.perform(post("/api/v1/trips/{tripId}/commands", tripId).with(csrf())
                        .header("Idempotency-Key", "trip-api-start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"START\",\"simulationSpeed\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRIVING"))
                .andExpect(jsonPath("$.data.telemetry.simulationSpeed").value(5));
    }

    private TripTelemetry telemetry(long sequence, TripStatus status) {
        return new TripTelemetry(sequence, new RouteCoordinate(118.7358, 31.9827, "GCJ-02"),
                status == TripStatus.DRIVING ? 90 : 0, 90, 42, 254, 0, 169_000,
                Instant.parse("2026-08-05T02:18:00Z"), status, 5, Instant.parse("2026-08-05T00:00:00Z"));
    }
}
