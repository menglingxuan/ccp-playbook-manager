package ac.domain.product;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares one product field. Fields keep a fixed {@code order}; the header text may repeat
 * because column mapping is positional, not name based.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(ProductFields.class)
public @interface ProductField {

    /** Stable internal identifier, unique inside the product. */
    String key();

    /** 1-based position used for ordering and export column order. */
    int order();

    /** Header text as it appears in source files. */
    String header();

    /** Alternative header spellings accepted by the aligner. */
    String[] aliases() default {};

    FieldType type() default FieldType.TEXT;

    FieldRole role() default FieldRole.TRADE;

    boolean required() default false;

    /** Whether the field contributes to the business record id. */
    boolean businessKey() default true;

    /** Optional pattern hint for display/parsing, for example {@code 0.000000}. */
    String format() default "";

    /** Naming template used by the clone feature for this field; empty means "keep source value". */
    String clonePattern() default "";
}
