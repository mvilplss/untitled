package org.example.dws;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
public class DwsWebMvcConfig implements WebMvcConfigurer {

    private final UserIdResolver userIdResolver;
    private final CurrentUserIdArgumentResolver currentUserIdArgumentResolver;

    public DwsWebMvcConfig(UserIdResolver userIdResolver,
                           CurrentUserIdArgumentResolver currentUserIdArgumentResolver) {
        this.userIdResolver = userIdResolver;
        this.currentUserIdArgumentResolver = currentUserIdArgumentResolver;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(userIdResolver)
                .addPathPatterns("/api/dws/**");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserIdArgumentResolver);
    }
}
