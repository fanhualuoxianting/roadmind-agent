package com.roadmind.server.agent;

/** A durable idempotency replay entry for an Agent request. */
public record AgentTaskReplay(String requestHash, AgentTaskSnapshot snapshot) {
}
