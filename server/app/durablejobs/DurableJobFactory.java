package durablejobs;

import models.PersistedDurableJobModel;

/** Constructs a concrete {@link DurableJob} from its {@link PersistedDurableJobModel} row. */
@FunctionalInterface
public interface DurableJobFactory {

  DurableJob create(PersistedDurableJobModel persistedDurableJob);
}
