package com.roadmind.server.tool;

import java.util.Map;

public class ToolInputValidationException extends RuntimeException {

    private final Map<String, String> fields;

    public ToolInputValidationException(String message, Map<String, String> fields) {
        super(message);
        this.fields = Map.copyOf(fields);
    }

    public Map<String, String> fields() {
        return fields;
    }
}
