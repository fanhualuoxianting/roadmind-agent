package com.roadmind.server.home;

import java.time.Instant;

public record HomeDeviceSnapshot(
        String deviceId,
        String displayName,
        String deviceType,
        boolean on,
        String mode,
        long stateVersion,
        Instant updatedAt) {
}
