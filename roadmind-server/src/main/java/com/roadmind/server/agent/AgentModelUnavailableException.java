package com.roadmind.server.agent;

public class AgentModelUnavailableException extends RuntimeException {

    public AgentModelUnavailableException(String message) {
        super(message);
    }

    public AgentModelUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
