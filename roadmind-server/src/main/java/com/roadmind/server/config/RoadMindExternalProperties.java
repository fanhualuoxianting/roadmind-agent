package com.roadmind.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "roadmind.external")
public class RoadMindExternalProperties {

    private String weatherMode = "STUB";
    private String routeMode = "STUB";
    private String amapBaseUrl = "https://restapi.amap.com";
    private String amapKey = "";

    public String getWeatherMode() {
        return weatherMode;
    }

    public void setWeatherMode(String weatherMode) {
        this.weatherMode = weatherMode;
    }

    public String getRouteMode() {
        return routeMode;
    }

    public void setRouteMode(String routeMode) {
        this.routeMode = routeMode;
    }

    public String getAmapBaseUrl() {
        return amapBaseUrl;
    }

    public void setAmapBaseUrl(String amapBaseUrl) {
        this.amapBaseUrl = amapBaseUrl;
    }

    public String getAmapKey() {
        return amapKey;
    }

    public void setAmapKey(String amapKey) {
        this.amapKey = amapKey;
    }
}
