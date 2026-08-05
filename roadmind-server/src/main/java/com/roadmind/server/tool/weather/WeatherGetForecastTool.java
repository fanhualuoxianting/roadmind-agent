package com.roadmind.server.tool.weather;

import com.roadmind.server.tool.RetryPolicy;
import com.roadmind.server.tool.RoadMindTool;
import com.roadmind.server.tool.ToolDescriptor;
import com.roadmind.server.tool.ToolExecutionContext;
import com.roadmind.server.tool.ToolRiskLevel;
import com.roadmind.server.weather.WeatherForecast;
import com.roadmind.server.weather.WeatherGateway;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

@Component
public class WeatherGetForecastTool implements RoadMindTool<WeatherGetForecastTool.Input, WeatherForecast> {

    private static final ToolDescriptor DESCRIPTOR = new ToolDescriptor(
            "weather.get_forecast",
            "1.0.0",
            "查询指定地点和日期的天气预报，并保留 LIVE/STUB 来源",
            ToolRiskLevel.READ_ONLY,
            Duration.ofSeconds(3),
            RetryPolicy.retryOnce(Duration.ofMillis(80)),
            true,
            "classpath:/tool-schemas/weather.get_forecast.json");

    private final WeatherGateway gateway;

    public WeatherGetForecastTool(WeatherGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public ToolDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public Class<Input> inputType() {
        return Input.class;
    }

    @Override
    public WeatherForecast execute(Input input, ToolExecutionContext context) {
        return gateway.getForecast(input.location(), input.date());
    }

    public record Input(
            @NotBlank @Size(max = 80) String location,
            @NotNull LocalDate date) {
    }
}
