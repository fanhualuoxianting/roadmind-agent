package com.roadmind.server.adapter.amap;

import com.fasterxml.jackson.databind.JsonNode;
import com.roadmind.server.config.RoadMindExternalProperties;
import com.roadmind.server.tool.ToolDependencyException;
import com.roadmind.server.weather.WeatherForecast;
import com.roadmind.server.weather.WeatherGateway;
import java.time.Clock;
import java.time.LocalDate;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(name = "roadmind.external.weather-mode", havingValue = "LIVE")
public class AmapWeatherGateway implements WeatherGateway {

    private final RestClient client;
    private final RoadMindExternalProperties properties;
    private final Clock clock = Clock.systemUTC();

    public AmapWeatherGateway(RestClient.Builder builder, RoadMindExternalProperties properties) {
        this.properties = properties;
        this.client = builder.baseUrl(properties.getAmapBaseUrl()).build();
    }

    @Override
    @Retry(name = "weatherGateway")
    @CircuitBreaker(name = "weatherGateway")
    @Bulkhead(name = "weatherGateway")
    public WeatherForecast getForecast(String location, LocalDate date) {
        requireKey();
        try {
            JsonNode response = client.get()
                    .uri(uri -> uri.path("/v3/weather/weatherInfo")
                            .queryParam("key", properties.getAmapKey())
                            .queryParam("city", location)
                            .queryParam("extensions", "all")
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode forecast = requireSuccess(response).path("forecasts").path(0);
            JsonNode casts = forecast.path("casts");
            JsonNode selected = casts.path(0);
            for (JsonNode cast : casts) {
                if (date.toString().equals(cast.path("date").asText())) {
                    selected = cast;
                    break;
                }
            }
            if (selected.isMissingNode() || selected.isNull()) {
                throw invalidResponse();
            }
            return new WeatherForecast(
                    forecast.path("city").asText(location),
                    LocalDate.parse(selected.path("date").asText(date.toString())),
                    selected.path("dayweather").asText("未知"),
                    selected.path("nighttemp").asInt(),
                    selected.path("daytemp").asInt(),
                    "AMap Weather",
                    "LIVE",
                    clock.instant(),
                    "实时来源：高德天气 Web 服务");
        } catch (ToolDependencyException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ToolDependencyException("WEATHER_UNAVAILABLE", "天气服务暂时不可用", true, exception);
        }
    }

    private JsonNode requireSuccess(JsonNode response) {
        if (response == null || !"1".equals(response.path("status").asText())) {
            throw invalidResponse();
        }
        return response;
    }

    private ToolDependencyException invalidResponse() {
        return new ToolDependencyException("WEATHER_RESULT_INVALID", "天气服务返回了无效数据", false);
    }

    private void requireKey() {
        if (properties.getAmapKey() == null || properties.getAmapKey().isBlank()) {
            throw new ToolDependencyException("WEATHER_KEY_MISSING", "实时天气模式缺少高德服务 Key", false);
        }
    }
}
