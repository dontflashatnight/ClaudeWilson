package androidx.annotation;
import java.lang.annotation.*;
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.CONSTRUCTOR, ElementType.METHOD, ElementType.PARAMETER,
        ElementType.FIELD, ElementType.ANNOTATION_TYPE, ElementType.TYPE})
public @interface AnyThread {}
