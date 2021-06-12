package com.tonythemacaroni.compound.annotations;

import java.util.function.Function;

import java.lang.annotation.Target;
import java.lang.annotation.Retention;
import java.lang.annotation.ElementType;
import java.lang.annotation.RetentionPolicy;

@Target({ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Resolve {

    Class<? extends Function<?, ?>> resolver();

    Class<?> from();

}
