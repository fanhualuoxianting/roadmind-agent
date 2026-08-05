package com.roadmind.server.agent;

import java.time.ZoneId;

public record PlanningRequest(String message, ZoneId timezone, String vehicleId) {
}
