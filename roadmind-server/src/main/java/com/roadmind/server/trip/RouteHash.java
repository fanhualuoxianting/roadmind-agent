package com.roadmind.server.trip;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

final class RouteHash {
    private RouteHash() {
    }

    static String of(String prefix, List<RouteCoordinate> polyline) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(prefix.getBytes(StandardCharsets.UTF_8));
            for (RouteCoordinate point : polyline) {
                digest.update(("|" + point.longitude() + ',' + point.latitude()).getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
