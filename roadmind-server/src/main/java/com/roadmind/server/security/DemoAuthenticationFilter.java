package com.roadmind.server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.filter.OncePerRequestFilter;

final class DemoAuthenticationFilter extends OncePerRequestFilter {

    private final SecurityContextRepository repository;

    DemoAuthenticationFilter(SecurityContextRepository repository) {
        this.repository = repository;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null && isLoopback(request)) {
            UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(
                    "roadmind-demo",
                    null,
                    List.of(
                            new SimpleGrantedAuthority("ROLE_USER"),
                            new SimpleGrantedAuthority("ROLE_ADMIN")));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            repository.saveContext(context, request, response);
        }
        filterChain.doFilter(request, response);
    }

    private boolean isLoopback(HttpServletRequest request) {
        String address = request.getRemoteAddr();
        return "127.0.0.1".equals(address)
                || "0:0:0:0:0:0:0:1".equals(address)
                || "::1".equals(address);
    }
}
