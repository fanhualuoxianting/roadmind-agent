package com.roadmind.server.weather;

import java.time.LocalDate;

public interface WeatherGateway {

    WeatherForecast getForecast(String location, LocalDate date);
}
