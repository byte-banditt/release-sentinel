package com.releasesentinel.review;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice(assignableTypes = PullRequestReviewController.class)
public class ReviewApiExceptionHandler {
    @ExceptionHandler(ReviewNotFoundException.class)
    ResponseEntity<?> notFound(ReviewNotFoundException e, HttpServletRequest request) { return error(HttpStatus.NOT_FOUND, "NOT_FOUND", e.getMessage(), request); }
    @ExceptionHandler(SecurityException.class)
    ResponseEntity<?> unauthorized(SecurityException e, HttpServletRequest request) { return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication failed", request); }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<?> invalid(IllegalArgumentException e, HttpServletRequest request) { return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage(), request); }
    @ExceptionHandler({IllegalStateException.class, DuplicateKeyException.class})
    ResponseEntity<?> conflict(RuntimeException e, HttpServletRequest request) { return error(HttpStatus.CONFLICT, "STATE_CONFLICT", e.getMessage(), request); }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> failed(Exception e, HttpServletRequest request) { return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Request could not be completed", request); }

    private ResponseEntity<?> error(HttpStatus status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(Map.of("timestamp", Instant.now().toString(), "status", status.value(),
                "code", code, "message", message == null ? code : message,
                "requestId", request.getAttribute("requestId") == null ? "" : request.getAttribute("requestId")));
    }
}
