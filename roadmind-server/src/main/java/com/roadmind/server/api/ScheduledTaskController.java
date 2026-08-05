package com.roadmind.server.api;

import com.roadmind.server.scheduling.CreateScheduledTaskRequest;
import com.roadmind.server.scheduling.ScheduledTaskService;
import com.roadmind.server.scheduling.ScheduledTaskSnapshot;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/scheduled-tasks")
@Validated
@ConditionalOnBean(ScheduledTaskService.class)
public class ScheduledTaskController {

    private final ScheduledTaskService service;

    public ScheduledTaskController(ScheduledTaskService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<ApiResponse<ScheduledTaskSnapshot>> create(
            Principal principal,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody CreateScheduledTaskRequest request) {
        ScheduledTaskSnapshot task = service.createReminder(
                principal.getName(),
                request.message(),
                request.executeAt(),
                request.timezone(),
                idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of("CREATED", "scheduled reminder created", task));
    }

    @GetMapping
    ApiResponse<List<ScheduledTaskSnapshot>> list(
            Principal principal,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "30") @Min(1) @Max(100) int limit) {
        return ApiResponse.ok(service.list(principal.getName(), status, limit));
    }

    @GetMapping("/{id}")
    ApiResponse<ScheduledTaskSnapshot> get(Principal principal, @PathVariable long id) {
        return ApiResponse.ok(service.get(principal.getName(), id));
    }

    @PostMapping("/{id}/cancel")
    ApiResponse<ScheduledTaskSnapshot> cancel(
            Principal principal,
            @PathVariable long id,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey) {
        return ApiResponse.of("OK", "scheduled task cancelled", service.cancel(
                principal.getName(), id, idempotencyKey));
    }
}
