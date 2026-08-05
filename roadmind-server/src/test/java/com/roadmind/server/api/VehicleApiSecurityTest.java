package com.roadmind.server.api;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.roadmind.server.vehicle.domain.GeoLocation;
import com.roadmind.server.vehicle.domain.TirePressure;
import com.roadmind.server.vehicle.domain.VehicleGateway;
import com.roadmind.server.vehicle.domain.VehicleGatewayUnavailableException;
import com.roadmind.server.vehicle.domain.VehicleStatus;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import jakarta.servlet.http.Cookie;

@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration"
})
@AutoConfigureMockMvc
@ActiveProfiles("demo")
class VehicleApiSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VehicleGateway gateway;

    @BeforeEach
    void setUp() {
        when(gateway.getStatus(anyString())).thenReturn(simulatorState());
        when(gateway.updateState(anyString(), any(), anyString())).thenReturn(simulatorState());
    }

    @Test
    void demoReadReturnsAuthenticatedStateAndStringId() throws Exception {
        mockMvc.perform(get("/api/v1/vehicles/198000000000000401/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.vehicleId").value("198000000000000401"))
                .andExpect(jsonPath("$.data.mode").value("DIGITAL_TWIN"));
    }

    @Test
    void demoWriteWithoutCsrfIsRejected() throws Exception {
        mockMvc.perform(patch("/api/v1/demo/vehicles/198000000000000401/state")
                        .header("Idempotency-Key", "demo-update-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":12,\"batteryPercent\":42}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
    }

    @Test
    void demoWriteWithCsrfIsAccepted() throws Exception {
        mockMvc.perform(patch("/api/v1/demo/vehicles/198000000000000401/state")
                        .with(csrf())
                        .header("Idempotency-Key", "demo-update-002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":12,\"batteryPercent\":42}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("DIGITAL_TWIN"));
    }

    @Test
    void demoWriteAcceptsRawCookieTokenUsedByBrowserAxios() throws Exception {
        MvcResult csrfResult = mockMvc.perform(get("/api/v1/security/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        Cookie csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");
        MockHttpSession session = (MockHttpSession) csrfResult.getRequest().getSession(false);

        org.assertj.core.api.Assertions.assertThat(csrfCookie).isNotNull();
        org.assertj.core.api.Assertions.assertThat(session).isNotNull();

        mockMvc.perform(patch("/api/v1/demo/vehicles/198000000000000401/state")
                        .session(session)
                        .cookie(csrfCookie)
                        .header("X-XSRF-TOKEN", csrfCookie.getValue())
                        .header("Idempotency-Key", "demo-update-browser-cookie")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":12,\"batteryPercent\":42}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("DIGITAL_TWIN"));
    }

    @Test
    void unavailableSimulatorReturns503AndTraceId() throws Exception {
        when(gateway.getStatus(anyString())).thenThrow(new VehicleGatewayUnavailableException(
                "车辆数字孪生模拟器暂时离线",
                new IOExceptionMarker()));

        mockMvc.perform(get("/api/v1/vehicles/198000000000000401/status")
                        .header("X-Request-ID", "server-request-001"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("VEHICLE_SIMULATOR_UNAVAILABLE"))
                .andExpect(jsonPath("$.traceId").value("server-request-001"))
                .andExpect(jsonPath("$.message", not(blankOrNullString())));
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
                12,
                Instant.parse("2026-08-04T00:00:00Z"));
    }

    private static final class IOExceptionMarker extends RuntimeException {
    }
}
