package com.roadmind.server.api;

import com.roadmind.server.preference.PreferenceService;
import com.roadmind.server.preference.PreferenceSnapshot;
import com.roadmind.server.preference.PutPreferenceRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/preferences")
@Validated
@ConditionalOnBean(PreferenceService.class)
public class PreferenceController {

    private final PreferenceService service;

    public PreferenceController(PreferenceService service) {
        this.service = service;
    }

    @GetMapping
    ApiResponse<List<PreferenceSnapshot>> list(
            Principal principal,
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "30") @Min(1) @Max(100) int limit) {
        return ApiResponse.ok(service.list(principal.getName(), category, limit));
    }

    @PutMapping("/{category}/{preferenceKey}")
    ApiResponse<PreferenceSnapshot> put(
            Principal principal,
            @PathVariable String category,
            @PathVariable @NotBlank @Size(max = 80) String preferenceKey,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody PutPreferenceRequest request) {
        return ApiResponse.of("OK", "preference saved", service.put(
                principal.getName(),
                category,
                preferenceKey,
                request.value(),
                request.sensitivity(),
                request.expiresAt(),
                idempotencyKey));
    }

    @DeleteMapping("/{category}/{preferenceKey}")
    ResponseEntity<ApiResponse<PreferenceDeleted>> delete(
            Principal principal,
            @PathVariable String category,
            @PathVariable @NotBlank @Size(max = 80) String preferenceKey,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey) {
        boolean deleted = service.delete(principal.getName(), category, preferenceKey, idempotencyKey);
        return ResponseEntity.ok(ApiResponse.of("OK", "preference deleted", new PreferenceDeleted(deleted)));
    }

    public record PreferenceDeleted(boolean deleted) {
    }
}
