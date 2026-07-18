package com.imsw.observe.controlplane.config;

import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.validation.ConstraintViolationException;

import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.imsw.observe.controlplane.interfaces.web.ErrorBody;
import com.imsw.observe.controlplane.interfaces.web.ErrorCode;
import com.imsw.observe.controlplane.interfaces.web.ErrorResponseException;
import com.imsw.observe.controlplane.interfaces.web.ResourceNotFoundException;
import com.imsw.observe.kernel.error.ObserveException;

/**
 * 统一异常 → {@link ErrorBody} 映射。所有错误（含 404）都返回 {@code {error:{code,message,traceId}}}。
 *
 * <p>{@code traceId} 优先取 MDC（OTel 等若注入），空则生成 UUID 并回写 MDC，便于响应与日志串联。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({ResourceNotFoundException.class, com.imsw.observe.kernel.error.ResourceNotFoundException.class})
    public ResponseEntity<ErrorBody> handleNotFound(final RuntimeException e) {
        return body(ErrorCode.NOT_FOUND, message(e), HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(com.imsw.observe.kernel.error.ConflictException.class)
    public ResponseEntity<ErrorBody> handleConflict(final com.imsw.observe.kernel.error.ConflictException e) {
        return body(ErrorCode.CONFLICT, message(e), HttpStatus.CONFLICT);
    }

    @ExceptionHandler(ErrorResponseException.class)
    public ResponseEntity<ErrorBody> handleErrorResponse(final ErrorResponseException e) {
        return body(e.code(), message(e), e.status());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorBody> handleValidation(final MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        if (detail.isEmpty()) {
            detail = "request body validation failed";
        }
        return body(ErrorCode.VALIDATION, detail, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorBody> handleConstraintViolation(final ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining("; "));
        if (detail.isEmpty()) {
            detail = "request validation failed";
        }
        return body(ErrorCode.VALIDATION, detail, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorBody> handleTypeMismatch(final MethodArgumentTypeMismatchException e) {
        return body(ErrorCode.BAD_REQUEST, "参数类型错误: " + e.getName(), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorBody> handleUnreadable(final HttpMessageNotReadableException e) {
        return body(ErrorCode.BAD_REQUEST, "请求体 JSON 不可读或缺失", HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorBody> handleMissingParam(final MissingServletRequestParameterException e) {
        return body(ErrorCode.BAD_REQUEST, message(e), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorBody> handleIllegalArgument(final IllegalArgumentException e) {
        return body(ErrorCode.BAD_REQUEST, message(e), HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(ObserveException.class)
    public ResponseEntity<ErrorBody> handleObserve(final ObserveException e) {
        return body(ErrorCode.INTERNAL, message(e), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ErrorBody> handleRuntime(final RuntimeException e) {
        return body(ErrorCode.INTERNAL, message(e), HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private static ResponseEntity<ErrorBody> body(final ErrorCode code, final String message, final HttpStatus status) {
        return ResponseEntity.status(status).body(ErrorBody.of(code, message, traceId()));
    }

    private static String traceId() {
        String existing = MDC.get("traceId");
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        String generated = UUID.randomUUID().toString();
        MDC.put("traceId", generated);
        return generated;
    }

    private static String message(final Throwable e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
}
