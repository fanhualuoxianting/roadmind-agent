package com.roadmind.server.tool;

public interface RoadMindTool<I, O> {

    ToolDescriptor descriptor();

    Class<I> inputType();

    O execute(I input, ToolExecutionContext context);
}
