package org.example.dws;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * 让 controller 方法直接 {@code @CurrentUserId String userId} 注入 header。
 */
@Component
public class CurrentUserIdArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUserId.class)
                && String.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {
        CurrentUserId anno = parameter.getParameterAnnotation(CurrentUserId.class);
        boolean required = anno == null || anno.required();

        HttpServletRequest req = webRequest.getNativeRequest(HttpServletRequest.class);
        if (req == null) {
            if (required) throw new DwsException("missing_user_id", "无法获取请求上下文");
            return null;
        }
        Object attr = req.getAttribute(UserIdResolver.ATTR);
        if (attr != null) return attr;
        // 兜底：query / path 参数
        String q = req.getParameter("userId");
        if (q != null && !q.isBlank()) return q.trim();
        if (required) throw new DwsException.UserNotFound("请求缺少 X-User-Id 头或 userId 参数");
        return null;
    }
}
