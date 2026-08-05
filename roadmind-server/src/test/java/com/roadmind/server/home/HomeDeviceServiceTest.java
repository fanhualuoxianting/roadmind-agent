package com.roadmind.server.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class HomeDeviceServiceTest {

    @Test
    void repeatedWriteToSameStateDoesNotAdvanceVersion() {
        HomeDeviceService service = new HomeDeviceService(
                Clock.fixed(Instant.parse("2026-08-05T00:00:00Z"), ZoneOffset.UTC));

        HomeDeviceSnapshot changed = service.setLight(
                "demo-home-light-01", false, "workflow-1/confirmation-1");
        HomeDeviceSnapshot replay = service.setLight(
                "demo-home-light-01", false, "workflow-1/confirmation-1");

        assertThat(changed.on()).isFalse();
        assertThat(changed.stateVersion()).isEqualTo(2);
        assertThat(replay).isEqualTo(changed);
        assertThat(service.get("demo-home-light-01")).isEqualTo(changed);
    }

    @Test
    void writeWithoutAuthorizationReferenceIsRejected() {
        HomeDeviceService service = new HomeDeviceService();

        assertThatThrownBy(() -> service.setLight("demo-home-light-01", false, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("授权引用");
    }
}
