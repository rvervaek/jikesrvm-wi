package org.vmmagic.pragma;

import java.lang.annotation.*;

/**
 * Annotation that indicates that an elementype has write intensive property.
 *
 * @author Ruben Vervaeke
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface WriteIntensive {
}
