package androidx.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.CONSTRUCTOR, ElementType.METHOD, ElementType.FIELD,
        ElementType.TYPE})
public @interface VisibleForTesting {
    int otherwise() default 0;
    int PRIVATE = 0;
    int PACKAGE_PRIVATE = 1;
    int PROTECTED = 2;
    int NONE = 3;
}
