package com.roadmind.server.api;

import com.roadmind.server.agent.AgentWorkflowService;
import com.roadmind.server.agent.ConversationSnapshot;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.security.Principal;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/conversations")
@Validated
public class ConversationController {

    private final AgentWorkflowService service;

    public ConversationController(AgentWorkflowService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<ApiResponse<ConversationSnapshot>> create(
            Principal principal,
            @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 128) String idempotencyKey,
            @Valid @RequestBody CreateConversationRequest request) {
        ConversationSnapshot conversation = service.createConversation(
                principal.getName(),
                request.title(),
                request.timezone(),
                idempotencyKey);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of("OK", "created", conversation));
    }
}
