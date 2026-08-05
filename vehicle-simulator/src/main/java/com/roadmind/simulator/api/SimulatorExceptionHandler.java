package com.roadmind.simulator.api;

import com.roadmind.simulator.domain.IdempotencyConflictException;
import com.roadmind.simulator.domain.VehicleNotFoundException;
import com.roadmind.simulator.domain.VehicleStateConflictException;
import com.roadmind.simulator.trip.TripTransitionException;
import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class SimulatorExceptionHandler {

    @ExceptionHandler(VehicleNotFoundException.class)
    ResponseEntity<SimulatorErrorResponse> notFound(VehicleNotFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", exception.getMessage(), Map.of());
    }

    @ExceptionHandler(VehicleStateConflictException.class)
    ResponseEntity<SimulatorErrorResponse> stateConflict(VehicleStateConflictException exception) {
        return error(HttpStatus.CONFLICT, "VEHICLE_STATE_CONFLICT", exception.getMessage(), Map.of());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<SimulatorErrorResponse> idempotencyConflict(IdempotencyConflictException exception) {
        return error(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", exception.getMessage(), Map.of());
    }

    @ExceptionHandler(TripTransitionException.class)
    ResponseEntity<SimulatorErrorResponse> tripTransition(TripTransitionException exception) {
        return error(HttpStatus.CONFLICT, "TRIP_INVALID_TRANSITION", exception.getMessage(), Map.of());
    }

    @ExceptionHandler({ConstraintViolationException.class, MissingRequestHeaderException.class})
    ResponseEntity<SimulatorErrorResponse> constraint(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", exception.getMessage(), Map.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<SimulatorErrorResponse> invalid(MethodArgumentNotValidException exception) {
        Map<String, Object> fields = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors()
                .forEach(error -> fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        exception.getBindingResult().getGlobalErrors()
                .forEach(error -> fields.putIfAbsent("request", error.getDefaultMessage()));
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "车辆状态参数不合法", fields);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<SimulatorErrorResponse> unreadable(HttpMessageNotReadableException exception) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "请求 JSON 无法解析或包含未知字段", Map.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<SimulatorErrorResponse> fallback(Exception exception) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "车辆模拟器发生内部错误", Map.of());
    }

    private ResponseEntity<SimulatorErrorResponse> error(
            HttpStatus status,
            String code,
            String message,
            Map<String, Object> details) {
        return ResponseEntity.status(status).body(SimulatorErrorResponse.of(code, message, details));
    }
}
