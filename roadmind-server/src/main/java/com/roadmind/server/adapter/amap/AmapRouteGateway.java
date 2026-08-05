package com.roadmind.server.adapter.amap;

import com.fasterxml.jackson.databind.JsonNode;
import com.roadmind.server.config.RoadMindExternalProperties;
import com.roadmind.server.route.RouteGateway;
import com.roadmind.server.route.RouteSummary;
import com.roadmind.server.tool.ToolDependencyException;
import java.time.Clock;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(name = "roadmind.external.route-mode", havingValue = "LIVE")
public class AmapRouteGateway implements RouteGateway {

    private final RestClient client;
    private final RoadMindExternalProperties properties;
    private final Clock clock = Clock.systemUTC();

    public AmapRouteGateway(RestClient.Builder builder, RoadMindExternalProperties properties) {
        this.properties = properties;
        this.client = builder.baseUrl(properties.getAmapBaseUrl()).build();
    }

    @Override
    @Retry(name = "routeGateway")
    @CircuitBreaker(name = "routeGateway")
    @Bulkhead(name = "routeGateway")
    public RouteSummary plan(String origin, String destination, boolean avoidTraffic) {
        requireKey();
        try {
            String originCoordinate = geocode(origin);
            String destinationCoordinate = geocode(destination);
            JsonNode response = client.get()
                    .uri(uri -> uri.path("/v3/direction/driving")
                            .queryParam("key", properties.getAmapKey())
                            .queryParam("origin", originCoordinate)
                            .queryParam("destination", destinationCoordinate)
                            .queryParam("strategy", avoidTraffic ? 10 : 0)
                            .queryParam("extensions", "base")
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode path = requireSuccess(response).path("route").path("paths").path(0);
            if (path.isMissingNode() || path.path("distance").asText().isBlank()) {
                throw invalidResponse();
            }
            double distanceKm = Double.parseDouble(path.path("distance").asText()) / 1000.0;
            long durationMinutes = Math.round(Double.parseDouble(path.path("duration").asText()) / 60.0);
            return new RouteSummary(
                    origin,
                    destination,
                    Math.round(distanceKm * 10.0) / 10.0,
                    durationMinutes,
                    avoidTraffic,
                    "AMap Driving",
                    "amap:" + Integer.toHexString((originCoordinate + destinationCoordinate).hashCode()),
                    "LIVE",
                    clock.instant(),
                    "实时来源：高德驾车路径规划 Web 服务");
        } catch (ToolDependencyException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ToolDependencyException("ROUTE_UNAVAILABLE", "路线服务暂时不可用", true, exception);
        }
    }

    private String geocode(String address) {
        JsonNode response = client.get()
                .uri(uri -> uri.path("/v3/geocode/geo")
                        .queryParam("key", properties.getAmapKey())
                        .queryParam("address", address)
                        .build())
                .retrieve()
                .body(JsonNode.class);
        String location = requireSuccess(response).path("geocodes").path(0).path("location").asText();
        if (!location.matches("-?\\d+(\\.\\d+)?,-?\\d+(\\.\\d+)?")) {
            throw new ToolDependencyException("ROUTE_LOCATION_NOT_FOUND", "无法识别路线地点", false);
        }
        return location;
    }

    private JsonNode requireSuccess(JsonNode response) {
        if (response == null || !"1".equals(response.path("status").asText())) {
            throw invalidResponse();
        }
        return response;
    }

    private ToolDependencyException invalidResponse() {
        return new ToolDependencyException("ROUTE_RESULT_INVALID", "路线服务返回了无效数据", false);
    }

    private void requireKey() {
        if (properties.getAmapKey() == null || properties.getAmapKey().isBlank()) {
            throw new ToolDependencyException("ROUTE_KEY_MISSING", "实时路线模式缺少高德服务 Key", false);
        }
    }
}
