package com.roadmind.server.api;

import com.roadmind.server.agent.AgentTaskAccepted;
import com.roadmind.server.agent.AgentTaskSnapshot;
import com.roadmind.server.agent.AgentWorkflowService;
import com.roadmind.server.shared.RequestTrace;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1")
@Validated
public class AgentController {

    private final AgentWorkflowService service;

    public AgentController(AgentWorkflowService service) {
        this.service = service;
    }

    @PostMapping("/conversations/{conversationId}/agent-requests")
    ResponseEntity<ApiResponse<AgentTaskAccepted>> submit(
            Principal principal,
            @PathVariable String conversationId,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody AgentRequestPayload request) {
        String timezone = request.clientContext() == null ? null : request.clientContext().timezone();
        AgentTaskAccepted accepted = service.submit(
                principal.getName(),
                conversationId,
                request.message(),
                timezone,
                idempotencyKey,
                RequestTrace.current());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.of("ACCEPTED", "Agent 已开始处理", accepted));
    }

    @GetMapping("/agent-tasks/{taskId}")
    ApiResponse<AgentTaskSnapshot> task(Principal principal, @PathVariable String taskId) {
        return ApiResponse.ok(service.getTask(taskId, principal.getName()));
    }

    @GetMapping(value = "/agent-tasks/{taskId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter events(Principal principal, @PathVariable String taskId) {
        return service.events(taskId, principal.getName());
    }
}
