package com.roadmind.server.agent;

import com.fasterxml.jackson.databind.JsonNode;

public record ModelToolCall(String toolName, JsonNode arguments) {
}
