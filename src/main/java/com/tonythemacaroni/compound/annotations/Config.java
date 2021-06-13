package com.tonythemacaroni.compound.annotations;

import java.lang.annotation.Target;
import java.lang.annotation.Retention;
import java.lang.annotation.ElementType;
import java.lang.annotation.RetentionPolicy;

@Target({ElementType.FIELD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Config {

    String path() default "";

    String key() default "";

    boolean colorize() default false;

    boolean required() default false;

}
