package org.example.dws;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 从请求头 {@code X-User-Id} 读取本地 userId，写入 request attribute 供 controller 取用。
 */
@Component
public class UserIdResolver implements HandlerInterceptor {

    public static final String ATTR = "dws.userId";
    public static final String HEADER = "X-User-Id";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String uid = request.getHeader(HEADER);
        if (uid != null && !uid.isBlank()) {
            request.setAttribute(ATTR, uid.trim());
        }
        return true;
    }
}
