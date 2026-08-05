package com.roadmind.server.tool;

public class UnknownToolException extends RuntimeException {

    public UnknownToolException(String toolName) {
        super("未注册工具: " + toolName);
    }
}
