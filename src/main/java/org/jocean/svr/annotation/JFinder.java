/**
 *
 */
package org.jocean.svr.annotation;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.PARAMETER;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ FIELD, PARAMETER })
/**
 * @author isdom
 *
 */
public @interface JFinder {

}
