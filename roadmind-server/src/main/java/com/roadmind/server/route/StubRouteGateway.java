package com.roadmind.server.route;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "roadmind.external.route-mode", havingValue = "STUB", matchIfMissing = true)
public class StubRouteGateway implements RouteGateway {

    private final Clock clock;

    public StubRouteGateway() {
        this(Clock.systemUTC());
    }

    StubRouteGateway(Clock clock) {
        this.clock = clock;
    }

    @Override
    public RouteSummary plan(String origin, String destination, boolean avoidTraffic) {
        boolean nanjingToWuxi = origin.contains("南京") && destination.contains("无锡");
        return new RouteSummary(
                origin,
                destination,
                nanjingToWuxi ? 169.0 : 128.0,
                nanjingToWuxi ? 138 : 112,
                avoidTraffic,
                "RoadMind deterministic fixture",
                "stub-route-001",
                "STUB",
                clock.instant(),
                "演示路线摘要，不代表实时道路规划");
    }
}
