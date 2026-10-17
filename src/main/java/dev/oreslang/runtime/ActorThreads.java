package dev.oreslang.runtime;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Java interop declaration of an actor's requested execution-lane bounds.
 *
 * <p>This is configuration metadata, not execution authority. A Java actor
 * bridge must validate min/max and backend capabilities before registration;
 * the current exclusive actor scheduler supports max == 1 only.
 * AOT consumers may read annotation metadata at build time.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ActorThreads {
    int min() default 1;
    int max() default 1;
}
