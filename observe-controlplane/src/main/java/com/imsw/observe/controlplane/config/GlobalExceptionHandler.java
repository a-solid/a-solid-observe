package com.imsw.observe.controlplane.config;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.imsw.observe.kernel.error.ObserveException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(final IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", message(e)));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, String>> handleMissingParam(final MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest().body(Map.of("error", message(e)));
    }

    @ExceptionHandler(ObserveException.class)
    public ResponseEntity<Map<String, String>> handleObserve(final ObserveException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", message(e)));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> handleRuntime(final RuntimeException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", message(e)));
    }

    private static String message(final Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
