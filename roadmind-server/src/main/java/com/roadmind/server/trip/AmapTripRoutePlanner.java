package com.roadmind.server.trip;

import com.fasterxml.jackson.databind.JsonNode;
import com.roadmind.server.config.RoadMindExternalProperties;
import com.roadmind.server.tool.ToolDependencyException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(name = "roadmind.external.route-mode", havingValue = "LIVE")
public class AmapTripRoutePlanner implements TripRoutePlanner {
    private final RestClient client;
    private final RoadMindExternalProperties properties;
    private final Clock clock = Clock.systemUTC();

    public AmapTripRoutePlanner(RestClient.Builder builder, RoadMindExternalProperties properties) {
        this.properties = properties;
        this.client = builder.baseUrl(properties.getAmapBaseUrl()).build();
    }

    @Override
    public RoutePlan plan(String origin, String destination, boolean avoidTraffic) {
        requireKey();
        try {
            RouteCoordinate originCoordinate = geocode(origin);
            RouteCoordinate destinationCoordinate = geocode(destination);
            JsonNode response = client.get().uri(uri -> uri.path("/v3/direction/driving")
                            .queryParam("key", properties.getAmapKey())
                            .queryParam("origin", coordinateText(originCoordinate))
                            .queryParam("destination", coordinateText(destinationCoordinate))
                            .queryParam("strategy", avoidTraffic ? 10 : 0)
                            .queryParam("extensions", "all").build())
                    .retrieve().body(JsonNode.class);
            JsonNode path = requireSuccess(response).path("route").path("paths").path(0);
            List<RouteCoordinate> polyline = parsePolyline(path);
            if (polyline.size() < 2) throw invalidResponse();
            double distance = Double.parseDouble(path.path("distance").asText());
            long duration = Math.round(Double.parseDouble(path.path("duration").asText()));
            String hash = RouteHash.of(origin + destination + avoidTraffic, polyline);
            return new RoutePlan(
                    UUID.randomUUID().toString(), 1, "AMap Driving", "LIVE", "GCJ-02",
                    new RoutePlace(origin, originCoordinate.longitude(), originCoordinate.latitude(), "GCJ-02"),
                    new RoutePlace(destination, destinationCoordinate.longitude(), destinationCoordinate.latitude(), "GCJ-02"),
                    distance, duration, polyline, hash, clock.instant(), null);
        } catch (ToolDependencyException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ToolDependencyException("ROUTE_UNAVAILABLE", "实时道路路线暂时不可用", true, exception);
        }
    }

    private RouteCoordinate geocode(String address) {
        JsonNode response = client.get().uri(uri -> uri.path("/v3/geocode/geo")
                        .queryParam("key", properties.getAmapKey()).queryParam("address", address).build())
                .retrieve().body(JsonNode.class);
        String text = requireSuccess(response).path("geocodes").path(0).path("location").asText();
        return parseCoordinate(text);
    }

    private List<RouteCoordinate> parsePolyline(JsonNode path) {
        List<RouteCoordinate> points = new ArrayList<>();
        for (JsonNode step : path.path("steps")) {
            for (String coordinate : step.path("polyline").asText().split(";")) {
                RouteCoordinate point = parseCoordinate(coordinate);
                if (points.isEmpty() || !points.getLast().equals(point)) points.add(point);
            }
        }
        return List.copyOf(points);
    }

    private RouteCoordinate parseCoordinate(String text) {
        String[] parts = text.split(",");
        if (parts.length != 2) throw invalidResponse();
        return new RouteCoordinate(Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), "GCJ-02");
    }

    private JsonNode requireSuccess(JsonNode response) {
        if (response == null || !"1".equals(response.path("status").asText())) throw invalidResponse();
        return response;
    }

    private ToolDependencyException invalidResponse() {
        return new ToolDependencyException("ROUTE_RESULT_INVALID", "高德路线返回了无效坐标或字段", false);
    }

    private void requireKey() {
        if (properties.getAmapKey() == null || properties.getAmapKey().isBlank()) {
            throw new ToolDependencyException("ROUTE_KEY_MISSING", "实时路线模式缺少高德服务 Key", false);
        }
    }

    private String coordinateText(RouteCoordinate coordinate) {
        return coordinate.longitude() + "," + coordinate.latitude();
    }
}
