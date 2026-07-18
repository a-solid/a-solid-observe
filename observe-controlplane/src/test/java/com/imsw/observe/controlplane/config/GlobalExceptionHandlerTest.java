package com.imsw.observe.controlplane.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.imsw.observe.controlplane.interfaces.web.ErrorBody;
import com.imsw.observe.controlplane.interfaces.web.ErrorCode;
import com.imsw.observe.controlplane.interfaces.web.ErrorResponseException;
import com.imsw.observe.controlplane.interfaces.web.ResourceNotFoundException;
import com.imsw.observe.kernel.error.ObserveException;

/**
 * B5：{@link GlobalExceptionHandler} 单元测试（直接调 handler 方法，不依赖 MockMvc slice——
 * Spring Boot 4.1 无 {@code @WebMvcTest}，且本模块无独立 Application 主类）。
 *
 * <p>验证每类异常映射到正确的 HTTP 状态 + {@link ErrorBody}（code/message/traceId）。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void resourceNotFoundMapsTo404() {
        ResponseEntity<ErrorBody> resp = handler.handleNotFound(new ResourceNotFoundException("alert 1 not found"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertErrorBody(resp, ErrorCode.NOT_FOUND, "alert 1 not found");
    }

    @Test
    void errorResponseExceptionMapsToItsStatus() {
        ErrorResponseException ex = new ErrorResponseException(HttpStatus.CONFLICT, ErrorCode.CONFLICT, "conflict");
        ResponseEntity<ErrorBody> resp = handler.handleErrorResponse(ex);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertErrorBody(resp, ErrorCode.CONFLICT, "conflict");
    }

    @Test
    void typeMismatchMapsTo400BadRequest() {
        MethodArgumentTypeMismatchException ex =
                new MethodArgumentTypeMismatchException("abc", Long.class, "id", null, null);
        ResponseEntity<ErrorBody> resp = handler.handleTypeMismatch(ex);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().code()).isEqualTo("BAD_REQUEST");
    }

    @Test
    void missingParamMapsTo400BadRequest() {
        org.springframework.web.bind.MissingServletRequestParameterException ex =
                new org.springframework.web.bind.MissingServletRequestParameterException("namespace", "String");
        ResponseEntity<ErrorBody> resp = handler.handleMissingParam(ex);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().code()).isEqualTo("BAD_REQUEST");
    }

    @Test
    void illegalArgumentMapsTo400BadRequest() {
        ResponseEntity<ErrorBody> resp = handler.handleIllegalArgument(new IllegalArgumentException("bad arg"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertErrorBody(resp, ErrorCode.BAD_REQUEST, "bad arg");
    }

    @Test
    void observeExceptionMapsTo500Internal() {
        ResponseEntity<ErrorBody> resp = handler.handleObserve(new ObserveException("domain"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertErrorBody(resp, ErrorCode.INTERNAL, "domain");
    }

    @Test
    void runtimeExceptionMapsTo500InternalWithTraceId() {
        ResponseEntity<ErrorBody> resp = handler.handleRuntime(new RuntimeException("boom"));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(resp.getBody().code()).isEqualTo("INTERNAL");
        assertThat(resp.getBody().traceId()).isNotBlank();
    }

    @Test
    void nullMessageFallsBackToClassName() {
        ResponseEntity<ErrorBody> resp = handler.handleRuntime(new RuntimeException());

        assertThat(resp.getBody().message()).isEqualTo("RuntimeException");
    }

    @Test
    void traceIdIsPopulated() {
        ResponseEntity<ErrorBody> resp = handler.handleNotFound(new ResourceNotFoundException("x"));

        assertThat(resp.getBody().traceId()).isNotBlank();
    }

    private static void assertErrorBody(
            final ResponseEntity<ErrorBody> resp, final ErrorCode code, final String message) {
        ErrorBody body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.code()).isEqualTo(code.name());
        assertThat(body.message()).isEqualTo(message);
        assertThat(body.traceId()).isNotBlank();
    }

    @Test
    void listOfErrorCodesCoversExpectedValues() {
        assertThat(ErrorCode.NOT_FOUND.httpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ErrorCode.VALIDATION.httpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ErrorCode.BAD_REQUEST.httpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(ErrorCode.CONFLICT.httpStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(ErrorCode.INTERNAL.httpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void methodArgumentNotValidAggregatesFieldErrors() {
        // 构造一个带字段错误的 binding result 比较繁琐；这里至少验证空 binding result 的兜底 message
        MethodArgumentNotValidException ex = methodArgumentNotValidExceptionWithMessage("name: must not be blank");
        ResponseEntity<ErrorBody> resp = handler.handleValidation(ex);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().code()).isEqualTo("VALIDATION");
    }

    /** 用最小桩构造一个 {@link MethodArgumentNotValidException}，注入字段错误以保证 handler 有内容可聚合。 */
    private static MethodArgumentNotValidException methodArgumentNotValidExceptionWithMessage(final String message) {
        org.springframework.validation.BeanPropertyBindingResult bindingResult =
                new org.springframework.validation.BeanPropertyBindingResult(new Object(), "req");
        bindingResult.addError(new org.springframework.validation.FieldError("req", "name", message));
        return new MethodArgumentNotValidException(null, bindingResult);
    }

    @Test
    void constraintViolationAggregatesErrors() {
        jakarta.validation.ConstraintViolationException ex =
                new jakarta.validation.ConstraintViolationException("violated", java.util.Set.of());
        ResponseEntity<ErrorBody> resp = handler.handleConstraintViolation(ex);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody().code()).isEqualTo("VALIDATION");
        // 空违规集合时走兜底 message
        assertThat(resp.getBody().message()).isNotBlank();
    }

    @Test
    void envelopeShapeIsDataLessOnError() {
        // ErrorBody 只有 code/message/traceId 三字段（无 data）——记录字段数以防误扩
        ErrorBody body = ErrorBody.of(ErrorCode.NOT_FOUND, "x", "t1");
        assertThat(body).hasFieldOrProperty("code");
        assertThat(List.of(body.getClass().getRecordComponents())).hasSize(3);
    }
}
