package org.example.dws;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * controller 方法参数注解：从 X-User-Id header（或 userId query 参数）注入当前 userId。
 *
 * <p>{@link #required()} 为 false 时，未提供 userId 会注入 null 而非抛异常。
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUserId {
    boolean required() default true;
}
