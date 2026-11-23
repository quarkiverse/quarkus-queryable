package io.quarkiverse.queryable.runtime;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface QLikeList {
    String prefix() default "like";

    String name() default "";

    QOption[] options() default {};
}
