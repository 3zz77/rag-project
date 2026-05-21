package com.rag.qa_system.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

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

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Map<String, Object> handleException(Exception ex) {
        Map<String, Object> result = new HashMap<>();
        result.put("code", 500);
        result.put("message", "服务内部错误");
        result.put("detail", ex.getMessage());
        return result;
    }
}
