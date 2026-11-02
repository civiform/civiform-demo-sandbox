package durablejobs;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import javax.inject.Qualifier;

/** Guice binding annotations for {@link DurableJob} registries. */
public final class BindingAnnotations {

  private BindingAnnotations() {}

  /** Marks the {@link DurableJobRegistry} containing recurring jobs. */
  @Qualifier
  @Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
  @Retention(RUNTIME)
  public @interface RecurringJobsProviderName {}
}
