package com.rag.qa_system.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 400);
        result.put("message", "请求参数校验失败");
        String detail = ex.getBindingResult().getFieldErrors().isEmpty()
                ? "参数不合法"
                : ex.getBindingResult().getFieldErrors().get(0).getDefaultMessage();
        result.put("detail", detail);
        return result;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleIllegalArgument(IllegalArgumentException ex) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 400);
        result.put("message", "请求参数错误");
        result.put("detail", ex.getMessage());
        return result;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleMessageNotReadable(HttpMessageNotReadableException ex) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 400);
        result.put("message", "请求体格式错误，请检查 JSON 格式");
        result.put("detail", ex.getMessage());
        return result;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.PAYLOAD_TOO_LARGE)
    public Map<String, Object> handleMaxUploadSize(MaxUploadSizeExceededException ex) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 413);
        result.put("message", "上传文件大小超过限制（最大 50MB）");
        result.put("detail", ex.getMessage());
        return result;
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleMissingParam(MissingServletRequestParameterException ex) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 400);
        result.put("message", "缺少必要参数: " + ex.getParameterName());
        return result;
    }

    @ExceptionHandler(IllegalStateException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, Object> handleIllegalState(IllegalStateException ex) {
        log.error("Service error", ex);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 500);
        result.put("message", "服务处理失败");
        result.put("detail", ex.getMessage());
        return result;
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, Object> handleException(Exception ex) {
        log.error("Internal server error", ex);
        Map<String, Object> result = new HashMap<>();
        result.put("code", 500);
        result.put("message", "服务内部错误");
        result.put("detail", ex.getMessage());
        return result;
    }
}
