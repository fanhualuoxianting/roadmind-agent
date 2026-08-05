package com.roadmind.server.agent;

public class AgentResourceNotFoundException extends RuntimeException {

    public AgentResourceNotFoundException(String resource, String id) {
        super(resource + " 不存在: " + id);
    }
}
