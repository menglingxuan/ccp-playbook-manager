package ac.domain.product;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a class as a product definition. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ProductDefinition {

    /** Product code, for example {@code IRS}. */
    String code();

    /** Display name shown in the UI. */
    String name();

    String description() default "";
}
