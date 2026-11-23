package io.quarkiverse.queryable.runtime;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Q {
    String prefix() default "obj";

    String name() default "";

    String condition() default "";

    QOption[] options() default {};
}
