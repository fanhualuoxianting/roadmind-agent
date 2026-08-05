package com.roadmind.server.weather;

import java.time.Clock;
import java.time.LocalDate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "roadmind.external.weather-mode", havingValue = "STUB", matchIfMissing = true)
public class StubWeatherGateway implements WeatherGateway {

    private final Clock clock;

    public StubWeatherGateway() {
        this(Clock.systemUTC());
    }

    StubWeatherGateway(Clock clock) {
        this.clock = clock;
    }

    @Override
    public WeatherForecast getForecast(String location, LocalDate date) {
        return new WeatherForecast(
                location,
                date,
                "多云转晴",
                15,
                24,
                "RoadMind deterministic fixture",
                "STUB",
                clock.instant(),
                "演示数据，不代表实时天气");
    }
}
