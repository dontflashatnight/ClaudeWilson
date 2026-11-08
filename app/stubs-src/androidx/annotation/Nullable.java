package androidx.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.PARAMETER, ElementType.FIELD,
        ElementType.LOCAL_VARIABLE, ElementType.TYPE_USE, ElementType.TYPE,
        ElementType.PACKAGE})
public @interface Nullable {}
