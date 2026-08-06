package com.roadmind.server.api;

import com.roadmind.server.agent.AgentIdempotencyConflictException;
import com.roadmind.server.agent.AgentResourceNotFoundException;
import com.roadmind.server.preference.PreferenceNotFoundException;
import com.roadmind.server.scheduling.ScheduledTaskIdempotencyConflictException;
import com.roadmind.server.scheduling.ScheduledTaskNotFoundException;
import com.roadmind.server.scheduling.ScheduledTaskStateConflictException;
import com.roadmind.server.tool.ToolInputValidationException;
import com.roadmind.server.tool.UnknownToolException;
import com.roadmind.server.vehicle.domain.VehicleGatewayClientException;
import com.roadmind.server.vehicle.domain.VehicleGatewayUnavailableException;
import com.roadmind.server.vehicle.domain.VehicleNotFoundException;
import com.roadmind.server.workflow.WorkflowConflictException;
import com.roadmind.server.trip.TripNotFoundException;
import com.roadmind.server.shared.RequestTrace;
import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(VehicleNotFoundException.class)
    ResponseEntity<ApiErrorResponse> notFound(VehicleNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", exception.getMessage(), Map.of());
    }

    @ExceptionHandler(AgentResourceNotFoundException.class)
    ResponseEntity<ApiErrorResponse> agentNotFound(AgentResourceNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", exception.getMessage(), Map.of());
    }

    @ExceptionHandler(TripNotFoundException.class)
    ResponseEntity<ApiErrorResponse> tripNotFound(TripNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", exception.getMessage(), Map.of());
    }

    @ExceptionHandler({PreferenceNotFoundException.class, ScheduledTaskNotFoundException.class})
    ResponseEntity<ApiErrorResponse> automationResourceNotFound(RuntimeException exception) {
        return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", exception.getMessage(), Map.of());
    }

    @ExceptionHandler(AgentIdempotencyConflictException.class)
    ResponseEntity<ApiErrorResponse> agentIdempotency(AgentIdempotencyConflictException exception) {
        return error(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", exception.getMessage(), Map.of());
    }

    @ExceptionHandler(ScheduledTaskIdempotencyConflictException.class)
    ResponseEntity<ApiErrorResponse> scheduledTaskIdempotency(
            ScheduledTaskIdempotencyConflictException exception) {
        return error(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", exception.getMessage(), Map.of());
    }

    @ExceptionHandler(ScheduledTaskStateConflictException.class)
    ResponseEntity<ApiErrorResponse> scheduledTaskState(ScheduledTaskStateConflictException exception) {
        return error(HttpStatus.CONFLICT, "SCHEDULED_TASK_STATE_CONFLICT", exception.getMessage(), Map.of());
    }

    @ExceptionHandler(WorkflowConflictException.class)
    ResponseEntity<ApiErrorResponse> workflowConflict(WorkflowConflictException exception) {
        return error(HttpStatus.CONFLICT, "WORKFLOW_CONFLICT", exception.getMessage(), Map.of());
    }

    @ExceptionHandler(UnknownToolException.class)
    ResponseEntity<ApiErrorResponse> unknownTool(UnknownToolException exception) {
        return error(HttpStatus.BAD_REQUEST, "UNKNOWN_TOOL", exception.getMessage(), Map.of());
    }

    @ExceptionHandler(ToolInputValidationException.class)
    ResponseEntity<ApiErrorResponse> invalidToolInput(ToolInputValidationException exception) {
        return error(HttpStatus.BAD_REQUEST, "TOOL_INPUT_INVALID", exception.getMessage(),
                Map.of("fields", exception.fields()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiErrorResponse> illegalArgument(IllegalArgumentException exception) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", exception.getMessage(), Map.of());
    }

    @ExceptionHandler(VehicleGatewayUnavailableException.class)
    ResponseEntity<ApiErrorResponse> unavailable(VehicleGatewayUnavailableException exception) {
        return error(
                HttpStatus.SERVICE_UNAVAILABLE,
                "VEHICLE_SIMULATOR_UNAVAILABLE",
                exception.getMessage(),
                Map.of("recoverable", true));
    }

    @ExceptionHandler(VehicleGatewayClientException.class)
    ResponseEntity<ApiErrorResponse> gatewayClient(VehicleGatewayClientException exception) {
        return ResponseEntity.status(exception.status())
                .body(ApiErrorResponse.of(exception.code(), exception.getMessage(), Map.of()));
    }

    @ExceptionHandler({ConstraintViolationException.class, MissingRequestHeaderException.class})
    ResponseEntity<ApiErrorResponse> constraint(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", exception.getMessage(), Map.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiErrorResponse> invalid(MethodArgumentNotValidException exception) {
        Map<String, Object> fields = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        exception.getBindingResult().getGlobalErrors()
                .forEach(error -> fields.putIfAbsent("request", error.getDefaultMessage()));
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请求参数不合法", fields);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiErrorResponse> unreadable(HttpMessageNotReadableException exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求 JSON 无法解析或包含未知字段", Map.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiErrorResponse> fallback(Exception exception) {
        LOGGER.error("Unhandled API exception, traceId={}", RequestTrace.current(), exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "服务发生内部错误", Map.of());
    }

    private ResponseEntity<ApiErrorResponse> error(
            HttpStatus status,
            String code,
            String message,
            Map<String, Object> details) {
        return ResponseEntity.status(status).body(ApiErrorResponse.of(code, message, details));
    }
}
