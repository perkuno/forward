package uno.perk.forward;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Generates a forwarding implementation for a given set of interfaces.
 *
 * Applying this annotation to a type will generate forwarding methods for the specified interface
 * types.
 * <p>
 * The only requirements are:
 * <ol>
 *   <li>The annotated class must extend the package-private generated forwarder.</li>
 *   <li>If any of the the forwarded types have type parameters, the annotated class must either
 *   declare at least those same type parameters and forward them to the package-private generated
 *   forwarder or else it must seal concrete type parameters in to the extended forwarder.</li>
 * </ol>
 */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface Forward {

  /**
   * @return The interface types to forward.
   */
  Class<?>[] value();

  /**
   * By default, the generated forwarder to extend will be named
   * "[{@code @Forward} annotated class name]Forwarder", but this can be altered by specifying a
   * replacement pattern for {@link java.util.regex.Matcher#replaceFirst(String)}.  The full name
   * of the {@code @Forward} annotated class is available in the {@code $1} capture group.
   *
   * @return The pattern that will be used to produce the backing forwarder class name.
   */
  String forwarderPattern() default "$1Forwarder";
}
