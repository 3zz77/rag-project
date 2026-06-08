package com.rag.qa_system.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import java.io.IOException;
import java.util.UUID;

/**
 * 请求追踪过滤器 — 为每个请求分配唯一 TraceId
 * 写入 MDC（日志可用）和响应头 X-Trace-Id（前端可追踪）
 * 通过 WebConfig.traceIdFilterRegistration() 注册
 */
public class TraceIdFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String traceId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put("traceId", traceId);
        if (response instanceof HttpServletResponse httpResp) {
            httpResp.setHeader("X-Trace-Id", traceId);
        }
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("traceId");
        }
    }
}
