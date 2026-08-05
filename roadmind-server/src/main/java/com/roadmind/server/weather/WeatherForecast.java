package com.roadmind.server.weather;

import java.time.Instant;
import java.time.LocalDate;

public record WeatherForecast(
        String location,
        LocalDate date,
        String condition,
        int minimumCelsius,
        int maximumCelsius,
        String provider,
        String sourceMode,
        Instant fetchedAt,
        String disclaimer) {
}
