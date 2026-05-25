package com.rag.qa_system.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final int MAX_REQUESTS = 60;
    private static final long WINDOW_MS = 60_000;

    private final Map<String, long[]> requestCounts = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/qa/ask")) {
            return true;
        }

        String ip = getClientIp(request);
        long now = System.currentTimeMillis();
        long[] record = requestCounts.computeIfAbsent(ip, k -> new long[]{0, now});

        synchronized (record) {
            if (now - record[1] > WINDOW_MS) {
                record[0] = 1;
                record[1] = now;
            } else if (record[0] >= MAX_REQUESTS) {
                response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"请求过于频繁，请稍后再试\"}");
                return false;
            } else {
                record[0]++;
            }
        }
        return true;
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp.trim();
        }
        return request.getRemoteAddr();
    }
}
