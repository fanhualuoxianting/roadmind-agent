package com.roadmind.server.api;

import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class IdentityController {

    @GetMapping("/me")
    public ApiResponse<DemoIdentity> me(Authentication authentication) {
        List<String> roles = authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .toList();
        return ApiResponse.ok(new DemoIdentity(
                "198000000000000001",
                authentication.getName(),
                "RoadMind 演示用户",
                roles,
                "DIGITAL_TWIN"));
    }

    @GetMapping("/security/csrf")
    public ApiResponse<CsrfInfo> csrf(CsrfToken token) {
        return ApiResponse.ok(new CsrfInfo(token.getToken(), token.getHeaderName()));
    }

    public record DemoIdentity(
            String userId,
            String username,
            String displayName,
            List<String> roles,
            String mode) {
    }

    public record CsrfInfo(String token, String headerName) {
    }
}
