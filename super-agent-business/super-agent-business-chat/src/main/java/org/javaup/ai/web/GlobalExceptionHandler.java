package org.javaup.ai.web;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.javaup.common.ApiResponse;
import org.javaup.enums.BaseCode;
import org.javaup.exception.SuperAgentFrameException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一将业务异常、参数异常和系统异常转换为可识别的 HTTP 响应。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(SuperAgentFrameException.class)
    public ResponseEntity<ApiResponse<String>> handleBusinessException(
        HttpServletRequest request, SuperAgentFrameException exception) {
        Integer code = exception.getCode();
        String message = exception.getMessage();
        HttpStatus status = code != null && code == HttpStatus.UNAUTHORIZED.value()
            ? HttpStatus.UNAUTHORIZED
            : HttpStatus.BAD_REQUEST;
        log.warn("业务请求失败: {} {} - {}", request.getMethod(), request.getRequestURI(), message);
        return ResponseEntity.status(status)
            .body(ApiResponse.error(code == null ? BaseCode.PARAMETER_ERROR.getCode() : code, message));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<String>> handleValidationException(
        HttpServletRequest request, MethodArgumentNotValidException exception) {
        FieldError fieldError = exception.getBindingResult().getFieldError();
        String message = fieldError == null
            ? BaseCode.PARAMETER_ERROR.getMsg()
            : fieldError.getDefaultMessage();
        log.warn("参数校验失败: {} {} - {}", request.getMethod(), request.getRequestURI(), message);
        return ResponseEntity.badRequest()
            .body(ApiResponse.error(BaseCode.PARAMETER_ERROR.getCode(), message));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<String>> handleUnreadableBody(
        HttpServletRequest request, HttpMessageNotReadableException exception) {
        log.warn("请求体解析失败: {} {}", request.getMethod(), request.getRequestURI());
        return ResponseEntity.badRequest()
            .body(ApiResponse.error(BaseCode.PARAMETER_ERROR.getCode(), "请求体格式错误。"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<String>> handleUnexpectedException(
        HttpServletRequest request, Exception exception) {
        log.error("未处理的服务异常: {} {}", request.getMethod(), request.getRequestURI(), exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiResponse.error(BaseCode.SYSTEM_ERROR));
    }
}
