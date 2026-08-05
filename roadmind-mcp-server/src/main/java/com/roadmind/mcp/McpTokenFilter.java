package com.roadmind.mcp;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class McpTokenFilter extends OncePerRequestFilter {

    private final McpBridgeProperties properties;

    public McpTokenFilter(McpBridgeProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (uri.equals("/mcp") || uri.startsWith("/mcp/") || uri.equals("/sse") || uri.startsWith("/sse/")) {
            if (request.getContentLengthLong() > 64 * 1024) {
                response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "MCP 请求体过大");
                return;
            }
            String expected = properties.internalToken();
            String provided = request.getHeader("X-RoadMind-MCP-Token");
            if (expected == null || expected.isBlank() || "disabled".equals(expected)
                    || !expected.equals(provided)) {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "MCP token 无效");
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
