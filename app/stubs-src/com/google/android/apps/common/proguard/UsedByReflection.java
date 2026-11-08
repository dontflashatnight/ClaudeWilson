package com.google.android.apps.common.proguard;
import java.lang.annotation.*;
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR,
        ElementType.FIELD, ElementType.PACKAGE})
public @interface UsedByReflection {
    String value() default "";
    String[] methods() default {};
}
